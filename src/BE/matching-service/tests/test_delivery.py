import json
import os
import subprocess
import sys
from datetime import timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from sqlalchemy import select, update

from matching_service.application.event_handlers import EventHandlers
from matching_service.application.matching_workflow import MatchingWorkflow
from matching_service.domain.events import IncomingRecord, utcnow
from matching_service.messaging.consumer import KafkaTransport
from matching_service.messaging.dead_letter import retry_one
from matching_service.messaging.publisher import OutboxPublisher
from matching_service.persistence.models import MessageFailure, OutboxEvent, ProcessedEvent
from tests.helpers import force_due, outputs, record, tick, trip_request


class Broker:
    ready = True

    def __init__(self):
        self.sent = []
        self.fail_after_ack = False

    async def publish(self, topic, key, value):
        self.sent.append((topic, key, value))
        if self.fail_after_ack:
            self.fail_after_ack = False
            raise TimeoutError("broker acknowledged but response lost")


async def emit_result(repository, driver):
    _, client = driver
    await EventHandlers(repository).handle(record(trip_request()))
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    await tick(repository, workflow)


async def test_outbox_resend_keeps_event_id_and_payload(repository, driver):
    await emit_result(repository, driver)
    broker = Broker()
    broker.fail_after_ack = True
    publisher = OutboxPublisher(repository, broker, repository.settings)
    await publisher.publish_one()
    assert (await outputs(repository))[0].status == "PENDING"
    async with repository.db.transaction() as session:
        await session.execute(update(OutboxEvent).values(next_retry_at=utcnow() - timedelta(seconds=1)))
    await publisher.publish_one()
    assert broker.sent[0] == broker.sent[1]
    assert (await outputs(repository))[0].status == "SENT"


async def test_no_claim_when_broker_down(repository, driver):
    await emit_result(repository, driver)
    broker = Broker()
    broker.ready = False
    assert not await OutboxPublisher(repository, broker, repository.settings).publish_one()
    assert (await outputs(repository))[0].locked_by is None


async def test_older_pending_version_blocks_newer_result_for_same_trip(repository, driver):
    fake, client = driver
    fake.drivers = []
    first = trip_request()
    second = trip_request(trip_id=first["data"]["trip_id"])
    handlers = EventHandlers(repository)
    workflow = MatchingWorkflow(repository, client)
    for request in (first, second):
        await handlers.handle(record(request))
        await tick(repository, workflow)
    events = await outputs(repository)
    assert [event.aggregate_version for event in events] == [1, 2]
    async with repository.db.transaction() as session:
        await session.execute(
            update(OutboxEvent)
            .where(OutboxEvent.event_id == events[0].event_id)
            .values(next_retry_at=utcnow() + timedelta(hours=1))
        )
    assert await repository.claim_outbox() is None


async def test_invalid_message_retries_then_dlq_without_processed_marker(repository):
    handlers = EventHandlers(repository)
    transport = KafkaTransport(repository.settings, handlers, repository)
    raw = b"\xff\xfeinvalid-json"
    message = IncomingRecord("ds03.trip.events.v1", 0, 2**53 + 1, raw, None)
    await transport.process(message)
    # Replayed source offset creates one durable failure only.
    await transport.process(message)
    for _ in range(5):
        async with repository.db.transaction() as session:
            await session.execute(
                update(MessageFailure).values(next_retry_at=utcnow() - timedelta(seconds=1))
            )
        await retry_one(repository, handlers)
    async with repository.db.sessions() as session:
        failures = list(await session.scalars(select(MessageFailure)))
        assert len(failures) == 1
        assert failures[0].status == "DLQ_PENDING"
        assert failures[0].original_bytes == raw
        assert failures[0].attempts == 6
        assert list(await session.scalars(select(ProcessedEvent))) == []
    broker = Broker()
    await OutboxPublisher(repository, broker, repository.settings).publish_one()
    payload = json.loads(broker.sent[0][2])
    assert payload["offset"] == str(2**53 + 1)
    assert "original_bytes_base64" in payload
    assert broker.sent[0][0] == "ds03.trip.events.v1.dlq.matching-v1"
    async with repository.db.sessions() as session:
        assert (await session.scalar(select(MessageFailure))).status == "DLQ_SENT"


async def test_offset_handoff_fails_when_database_cannot_store_failure(settings):
    repository = SimpleNamespace(record_failure=AsyncMock(side_effect=RuntimeError("db unavailable")))
    handlers = SimpleNamespace(handle=AsyncMock(side_effect=ValueError("bad record")))
    transport = KafkaTransport(settings, handlers, repository)
    with pytest.raises(RuntimeError, match="db unavailable"):
        await transport.process(record(trip_request()))


async def test_failed_message_can_be_replayed_after_repair(repository):
    message = record(trip_request())
    await repository.record_failure(message, "WorkflowBusyError")
    async with repository.db.transaction() as session:
        await session.execute(update(MessageFailure).values(next_retry_at=utcnow() - timedelta(seconds=1)))
    await retry_one(repository, EventHandlers(repository))
    async with repository.db.sessions() as session:
        assert (await session.scalar(select(MessageFailure))).status == "RESOLVED"
        assert await session.scalar(select(ProcessedEvent)) is not None


async def test_schema_migration_on_fresh_and_preimported_database(repository):
    # repository fixture already checks matching_test_ before any destructive setup.
    root = Path(__file__).resolve().parents[1]
    env = os.environ.copy()
    env["DATABASE_URL"] = env["TEST_DATABASE_URL"]
    async with repository.db.transaction() as session:
        from sqlalchemy import text

        await session.execute(text("DROP TABLE IF EXISTS public.alembic_version"))
    for _ in range(2):
        result = subprocess.run(
            [sys.executable, "-m", "alembic", "upgrade", "head"],
            cwd=root,
            env=env,
            capture_output=True,
            text=True,
        )
        assert result.returncode == 0, result.stderr
    async with repository.db.engine.connect() as conn:
        from sqlalchemy import text

        assert await conn.scalar(text("SELECT version_num FROM public.alembic_version")) == "0001_matching"


async def test_release_outage_preserves_compensation_until_recovered(repository, driver):
    from tests.helpers import attempt, cancel

    fake, client = driver
    request = trip_request()
    await EventHandlers(repository).handle(record(request))
    workflow = MatchingWorkflow(repository, client)
    await tick(repository, workflow)
    await tick(repository, workflow)
    await EventHandlers(repository).handle(record(cancel(request)))
    fake.fail_release = True
    await tick(repository, workflow)
    assert (await attempt(repository, request["data"]["attempt_id"])).status == "RESERVED"
    fake.fail_release = False
    await force_due(repository)
    await tick(repository, MatchingWorkflow(repository, client))
    assert (await attempt(repository, request["data"]["attempt_id"])).status == "CANCELLED"
