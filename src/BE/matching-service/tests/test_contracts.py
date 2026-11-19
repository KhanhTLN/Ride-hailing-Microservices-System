import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

import httpx
import pytest

from matching_service.clients.auth import driver_metadata
from matching_service.config import Settings
from matching_service.domain.errors import InvalidEventError
from matching_service.domain.events import canonical_hash, utcnow
from matching_service.main import create_app
from matching_service.messaging.dead_letter import dead_letter_payload
from matching_service.messaging.validation import decode_event, validate_payload
from tests.helpers import record, trip_request


def test_request_round_trip():
    event = trip_request()
    msg = record(event)
    assert decode_event(msg.value, msg.key) == event


@pytest.mark.parametrize(
    "change",
    [
        lambda e: e["data"]["pickup"].update(latitude=91),
        lambda e: e["data"].update(vehicle_type="BIKE"),
        lambda e: e["data"].update(extra="unexpected"),
        lambda e: e.update(event_id="invalid"),
        lambda e: e.update(aggregate_id=str(uuid4())),
        lambda e: e["data"].update(excluded_driver_ids=[str(uuid4()) for _ in range(101)]),
        lambda e: e.update(event_id="ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF"),
    ],
)
def test_invalid_request_rejected(change):
    event = trip_request()
    change(event)
    msg = record(event)
    with pytest.raises(InvalidEventError):
        decode_event(msg.value, msg.key)


@pytest.mark.parametrize("raw", [b"{", b"[]", b"\xff", b'{"x":1,"x":2}', b"NaN", b" " * 65537])
def test_invalid_wire_payload(raw):
    with pytest.raises(InvalidEventError):
        decode_event(raw, None)


def test_other_trip_event_is_ignored():
    assert decode_event(b'{"event_type":"TripAccepted"}', None) is None


def test_kafka_key_must_match():
    msg = record(trip_request())
    with pytest.raises(InvalidEventError):
        decode_event(msg.value, b"another-key")


def test_canonical_hash_is_independent_of_json_field_order():
    assert canonical_hash({"a": 1, "b": 2}) == canonical_hash({"b": 2, "a": 1})


def test_no_identity_credentials_are_required():
    cfg = Settings(_env_file=None, IDENTITY_BASE_URL="old-placeholder", SERVICE_CLIENT_ID="old-placeholder")
    assert driver_metadata(cfg, "correlation") == (("x-correlation-id", "correlation"),)


def test_optional_bearer_is_redacted_in_settings():
    cfg = Settings(_env_file=None, driver_grpc_bearer_token="private-access-token")
    assert "private-access-token" not in repr(cfg)
    assert driver_metadata(cfg, "cid")[-1] == ("authorization", "Bearer private-access-token")


def test_packaged_contracts_match_shared_sources():
    root = Path(__file__).resolve().parents[4]
    packaged = Path(__file__).resolve().parents[1] / "src/matching_service/resources"
    assert (packaged / "schema.sql").read_bytes() == (root / "scripts/sql/matchingdb_init.sql").read_bytes()
    for schema in (packaged / "schemas").glob("*.json"):
        assert json.loads(schema.read_text()) == json.loads(
            (root / "ds03-api-contracts-v1/kafka/schemas" / schema.name).read_text()
        )


@pytest.mark.parametrize("raw", [b"invalid json", b"\xff", b"[]", b'{"a":1,"a":2}', b'{"a":1e9999}'])
def test_dlq_preserves_invalid_original_bytes(raw):
    failure = SimpleNamespace(
        original_topic="ds03.trip.events.v1",
        original_partition=0,
        original_offset=2**53 + 1,
        consumer_group="matching-v1",
        attempts=6,
        error_code="INVALID_JSON",
        failed_at=utcnow(),
        original_bytes=raw,
    )
    payload = dead_letter_payload(failure)
    validate_payload(payload, "DeadLetter")
    assert payload["offset"] == str(2**53 + 1)
    assert "original_bytes_base64" in payload and "original_message" not in payload


async def test_health_live_does_not_depend_on_database(settings):
    app = create_app(settings)
    db = SimpleNamespace(ready=AsyncMock(side_effect=RuntimeError("db down")))
    app.state.runtime = SimpleNamespace(
        db=db, driver=SimpleNamespace(ready=AsyncMock()), transport=SimpleNamespace(ready=True), tasks=[]
    )
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        live = await client.get("/internal/matching/health/live")
        assert live.status_code == 200
        db.ready.assert_not_called()
        ready = await client.get("/internal/matching/health/ready")
        assert ready.status_code == 503
        assert ready.json()["code"] == "DEPENDENCY_UNAVAILABLE"
        assert ready.json()["correlation_id"] == ready.headers["x-correlation-id"]


async def test_ready_echoes_correlation(settings):
    app = create_app(settings)
    app.state.runtime = SimpleNamespace(
        db=SimpleNamespace(ready=AsyncMock()),
        driver=SimpleNamespace(ready=AsyncMock()),
        transport=SimpleNamespace(ready=True),
        tasks=[],
    )
    cid = str(uuid4())
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/internal/matching/health/ready", headers={"x-correlation-id": cid})
    assert response.json() == {"status": "ok", "service": "matching"}
    assert response.headers["x-correlation-id"] == cid
