import asyncio
import copy
from datetime import timedelta
from uuid import uuid4

import pytest
from sqlalchemy import func, select, update

from ds03.driver.v1 import driver_pb2
from matching_service.application.event_handlers import EventHandlers
from matching_service.application.matching_workflow import MatchingWorkflow
from matching_service.domain.errors import EventConflictError, LostClaimError
from matching_service.domain.events import utcnow
from matching_service.persistence.models import MatchingAttempt, ProcessedEvent, RpcOperation
from tests.helpers import attempt, cancel, force_due, outputs, record, tick, trip_request


async def accept(repo, event):
    await EventHandlers(repo).handle(record(event))


async def test_success_real_postgres_and_grpc(repository, driver):
    fake, client = driver
    request = trip_request()
    await accept(repository, request)
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    await tick(repository, workflow)
    row = await attempt(repository, request["data"]["attempt_id"])
    assert row.status == "RESERVED"
    messages = await outputs(repository)
    assert len(messages) == 1
    event = messages[0].payload
    assert event["event_type"] == "DriverReserved"
    assert event["causation_id"] == request["event_id"]
    assert event["correlation_id"] == request["correlation_id"]
    assert event["aggregate_version"] == 1
    assert event["data"]["reservation_id"] in fake.reservations


async def test_event_and_attempt_deduplication(repository, driver):
    fake, client = driver
    request = trip_request()
    await accept(repository, request)
    await accept(repository, request)
    replay = copy.deepcopy(request)
    replay["event_id"] = str(uuid4())
    await accept(repository, replay)
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    await tick(repository, workflow)
    async with repository.db.sessions() as session:
        assert await session.scalar(select(func.count()).select_from(MatchingAttempt)) == 1
        assert await session.scalar(select(func.count()).select_from(ProcessedEvent)) == 2
    assert len(fake.reservations) == len(await outputs(repository)) == 1


async def test_conflicting_event_or_attempt_is_rejected(repository):
    request = trip_request()
    await accept(repository, request)
    bad = copy.deepcopy(request)
    bad["data"]["pickup"]["latitude"] = 22
    with pytest.raises(EventConflictError):
        await accept(repository, bad)
    bad["event_id"] = str(uuid4())
    with pytest.raises(EventConflictError):
        await accept(repository, bad)


async def test_two_trips_compete_for_one_driver(repository, driver):
    fake, client = driver
    fake.drivers = fake.drivers[:1]
    requests = [trip_request(), trip_request()]
    await asyncio.gather(*(accept(repository, r) for r in requests))
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    await tick(repository, workflow)
    jobs = await asyncio.gather(repository.claim_attempt(), repository.claim_attempt())
    assert all(jobs)
    await asyncio.gather(*(workflow.advance(j.attempt_id, j.locked_by) for j in jobs))
    for _ in range(3):
        await tick(repository, workflow)
    assert len(fake.reservations) == 1
    messages = await outputs(repository)
    assert {m.event_type for m in messages} == {"DriverReserved", "MatchingFailed"}


async def test_lost_reserve_response_reuses_key_after_restart(repository, driver):
    fake, client = driver
    fake.lose_response = True
    request = trip_request()
    await accept(repository, request)
    await tick(repository, MatchingWorkflow(repository, client))
    await tick(repository, MatchingWorkflow(repository, client))
    assert (await attempt(repository, request["data"]["attempt_id"])).status == "RESERVE_UNKNOWN"
    assert not await outputs(repository)
    await repository.db.engine.dispose()
    await force_due(repository)
    await tick(repository, MatchingWorkflow(repository, client))
    assert (await attempt(repository, request["data"]["attempt_id"])).status == "RESERVED"
    assert len(set(fake.reserve_keys)) == len(fake.reservations) == 1


async def test_crash_before_local_result_recovers_same_remote_reservation(repository, driver, monkeypatch):
    fake, client = driver
    request = trip_request()
    await accept(repository, request)
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    original_emit = repository._emit

    async def crash(*args):
        raise RuntimeError("simulated process crash inside local result transaction")

    monkeypatch.setattr(repository, "_emit", crash)
    with pytest.raises(RuntimeError):
        await tick(repository, workflow)
    assert len(fake.reservations) == 1
    assert not await outputs(repository)
    async with repository.db.sessions() as session:
        op = await session.scalar(select(RpcOperation))
        assert op.status == "PENDING"
    monkeypatch.setattr(repository, "_emit", original_emit)
    async with repository.db.transaction() as session:
        await session.execute(update(MatchingAttempt).values(locked_until=utcnow() - timedelta(seconds=1)))
    await force_due(repository)
    await tick(repository, MatchingWorkflow(repository, client))
    assert len(fake.reservations) == len(await outputs(repository)) == 1
    assert len(set(fake.reserve_keys)) == 1


async def test_cancel_before_request_blocks_reserve(repository, driver):
    fake, client = driver
    request = trip_request()
    await accept(repository, cancel(request))
    await accept(repository, request)
    assert (await attempt(repository, request["data"]["attempt_id"])).status == "CANCELLED"
    assert await tick(repository, MatchingWorkflow(repository, client)) is None
    assert not fake.reserve_keys and not await outputs(repository)


async def test_cancel_during_reserve_compensates_without_emitting_result(repository, driver):
    fake, client = driver
    fake.resume_reserve = asyncio.Event()
    request = trip_request()
    await accept(repository, request)
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    work = await repository.claim_attempt()
    task = asyncio.create_task(workflow.advance(work.attempt_id, work.locked_by))
    await asyncio.wait_for(fake.reserve_entered.wait(), timeout=2)
    await accept(repository, cancel(request))
    fake.resume_reserve.set()
    await task
    await tick(repository, workflow)
    assert (await attempt(repository, request["data"]["attempt_id"])).status == "CANCELLED"
    assert fake.release_count == 1
    assert all(r.status == driver_pb2.RELEASED for r in fake.reservations.values())
    assert not await outputs(repository)


async def test_cancel_unknown_reserve_reconciles_then_releases(repository, driver):
    fake, client = driver
    fake.lose_response = True
    request = trip_request()
    await accept(repository, request)
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    await tick(repository, workflow)
    await accept(repository, cancel(request))
    await tick(repository, workflow)
    await tick(repository, workflow)
    assert (await attempt(repository, request["data"]["attempt_id"])).status == "CANCELLED"
    assert fake.release_count == 1 and len(set(fake.reserve_keys)) == 1


async def test_confirmed_reservation_is_never_released(repository, driver):
    fake, client = driver
    request = trip_request()
    await accept(repository, request)
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    await tick(repository, workflow)
    reservation = next(iter(fake.reservations.values()))
    reservation.status = driver_pb2.CONFIRMED
    await accept(repository, cancel(request))
    await tick(repository, workflow)
    assert fake.release_count == 0
    assert reservation.status == driver_pb2.CONFIRMED
    assert (await attempt(repository, request["data"]["attempt_id"])).status == "CANCELLED"


async def test_release_confirmation_race_is_reconciled(repository, driver):
    fake, client = driver
    fake.confirm_on_release = True
    request = trip_request()
    await accept(repository, request)
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    await tick(repository, workflow)
    await accept(repository, cancel(request))
    await tick(repository, workflow)
    await force_due(repository)
    await tick(repository, workflow)
    assert fake.release_count == 1
    assert next(iter(fake.reservations.values())).status == driver_pb2.CONFIRMED
    assert (await attempt(repository, request["data"]["attempt_id"])).status == "CANCELLED"


async def test_expired_search_does_not_start_reserve(repository, driver):
    fake, client = driver
    request = trip_request(search_deadline=(utcnow() - timedelta(seconds=1)).isoformat())
    await accept(repository, request)
    await tick(repository, MatchingWorkflow(repository, client))
    assert not fake.reserve_keys
    assert (await outputs(repository))[0].payload["data"]["reason"] == "DEADLINE_EXCEEDED"


async def test_no_candidates_is_business_failure(repository, driver):
    fake, client = driver
    fake.drivers = []
    await accept(repository, trip_request())
    await tick(repository, MatchingWorkflow(repository, client))
    assert (await outputs(repository))[0].payload["data"]["reason"] == "NO_DRIVER"


async def test_stale_worker_cannot_overwrite_new_claim(repository):
    request = trip_request()
    await accept(repository, request)
    first = await repository.claim_attempt()
    async with repository.db.transaction() as session:
        await session.execute(update(MatchingAttempt).values(locked_until=utcnow() - timedelta(seconds=1)))
    second = await repository.claim_attempt()
    assert second.locked_by != first.locked_by
    with pytest.raises(LostClaimError):
        await repository.save_candidates(first.attempt_id, first.locked_by, [])


async def test_late_cancel_does_not_conflict_with_new_active_attempt(repository, driver):
    _, client = driver
    request = trip_request()
    await accept(repository, request)
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    await tick(repository, workflow)
    new_request = trip_request(trip_id=request["data"]["trip_id"])
    await accept(repository, new_request)
    await accept(repository, cancel(request))
    old = await attempt(repository, request["data"]["attempt_id"])
    assert old.status == "RESERVED" and old.cancellation_requested_at is not None


async def test_orm_columns_match_imported_sql(repository):
    from sqlalchemy import inspect

    from matching_service.persistence.database import Base

    async with repository.db.engine.connect() as conn:

        def check(connection):
            inspector = inspect(connection)
            for table in Base.metadata.tables.values():
                assert {c["name"] for c in inspector.get_columns(table.name, schema="public")} == set(
                    table.c.keys()
                )

        await conn.run_sync(check)
