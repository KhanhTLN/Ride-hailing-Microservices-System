import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from uuid import UUID, uuid4


def utcnow() -> datetime:
    return datetime.now(UTC)


def canonical_hash(value: dict) -> str:
    raw = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class IncomingRecord:
    topic: str
    partition: int
    offset: int
    value: bytes
    key: bytes | None
    headers: tuple[tuple[str, bytes | None], ...] = ()


def result_event(attempt, event_type: str, version: int, data: dict) -> dict:
    return {
        "event_id": str(uuid4()),
        "event_type": event_type,
        "schema_version": 1,
        "producer": "matching",
        "occurred_at": utcnow().isoformat().replace("+00:00", "Z"),
        "correlation_id": str(attempt.correlation_id),
        "causation_id": str(attempt.source_event_id),
        "aggregate_id": str(attempt.trip_id),
        "aggregate_version": version,
        "data": {"trip_id": str(attempt.trip_id), "attempt_id": str(attempt.attempt_id), **data},
    }


def as_uuid(value: str) -> UUID:
    return UUID(value)
