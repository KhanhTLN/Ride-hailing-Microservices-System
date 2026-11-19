import copy
import itertools
import json
from datetime import timedelta
from uuid import uuid4

from sqlalchemy import select, update

from matching_service.domain.events import IncomingRecord, utcnow
from matching_service.persistence.models import MatchingAttempt, OutboxEvent

offsets = itertools.count()


def trip_request(**changes):
    trip_id, attempt_id = str(uuid4()), str(uuid4())
    event = {
        "event_id": str(uuid4()),
        "event_type": "TripRequested",
        "schema_version": 1,
        "producer": "trip",
        "occurred_at": utcnow().isoformat(),
        "correlation_id": str(uuid4()),
        "causation_id": str(uuid4()),
        "aggregate_id": trip_id,
        "aggregate_version": 1,
        "data": {
            "trip_id": trip_id,
            "attempt_id": attempt_id,
            "passenger_id": str(uuid4()),
            "pickup": {"latitude": 21.0285, "longitude": 105.8542},
            "vehicle_type": "MOTORBIKE",
            "search_deadline": (utcnow() + timedelta(seconds=120)).isoformat(),
            "excluded_driver_ids": [],
        },
    }
    event["data"].update(changes)
    event["aggregate_id"] = event["data"]["trip_id"]
    return event


def cancel(request):
    event = copy.deepcopy(request)
    event.update(event_id=str(uuid4()), event_type="MatchingCancelled", aggregate_version=2)
    event["data"] = {k: request["data"][k] for k in ("trip_id", "attempt_id")}
    event["data"]["reason"] = "PASSENGER_CANCELLED"
    return event


def record(event):
    return IncomingRecord(
        "ds03.trip.events.v1", 0, next(offsets), json.dumps(event).encode(), event["data"]["trip_id"].encode()
    )


async def attempt(repo, attempt_id):
    async with repo.db.sessions() as session:
        from uuid import UUID

        return await session.get(MatchingAttempt, UUID(str(attempt_id)))


async def outputs(repo):
    async with repo.db.sessions() as session:
        return list((await session.scalars(select(OutboxEvent).order_by(OutboxEvent.created_at))).all())


async def tick(repo, workflow):
    work = await repo.claim_attempt()
    if work:
        await workflow.advance(work.attempt_id, work.locked_by)
    return work


async def force_due(repo):
    async with repo.db.transaction() as session:
        await session.execute(update(MatchingAttempt).values(next_retry_at=utcnow() - timedelta(seconds=1)))
