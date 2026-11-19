from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
import time
from uuid import uuid4

import pytest
from sqlalchemy import select

from matching.config import Settings
from matching.contracts import Contracts, iso_now
from matching.db import Database, Attempt, Inbox, Outbox, Processed
from matching.driver import DriverError
from matching.workflow import Workflow

D1, D2 = str(uuid4()), str(uuid4())


class FakeDriver:
    def __init__(self):
        self.candidates = [D1, D2]
        self.reservations = {}
        self.reserve_calls = []
        self.release_calls = []
        self.lose_response = False
        self.reject = set()
        self.fail_release = False

    def find(self, data, correlation):
        return self.candidates

    def reserve(self, driver, trip, key, correlation):
        self.reserve_calls.append((driver, trip, key))
        if driver in self.reject:
            raise DriverError("DRIVER_UNAVAILABLE", rejected=True)
        result = self.reservations.setdefault(key, {
            "id": str(uuid4()), "driver_id": driver, "trip_id": trip, "status": "HELD",
            "expires_at": (datetime.now(timezone.utc) + timedelta(seconds=30)).isoformat()})
        if self.lose_response:
            self.lose_response = False
            raise DriverError("UNAVAILABLE")
        return deepcopy(result)

    def get(self, reservation_id, correlation):
        return deepcopy(next(r for r in self.reservations.values() if r["id"] == reservation_id))

    def release(self, reservation_id, trip, key, correlation):
        self.release_calls.append(reservation_id)
        if self.fail_release:
            self.fail_release = False
            raise DriverError("UNAVAILABLE")
        result = next(r for r in self.reservations.values() if r["id"] == reservation_id)
        result["status"] = "RELEASED"
        return deepcopy(result)


def request():
    trip = str(uuid4())
    return {"event_id": str(uuid4()), "event_type": "TripRequested", "schema_version": 1,
        "producer": "trip", "occurred_at": iso_now(), "correlation_id": str(uuid4()),
        "causation_id": str(uuid4()), "aggregate_id": trip, "aggregate_version": 1,
        "data": {"trip_id": trip, "attempt_id": str(uuid4()), "passenger_id": str(uuid4()),
            "pickup": {"latitude": 21.0, "longitude": 105.0}, "vehicle_type": "MOTORBIKE",
            "search_deadline": (datetime.now(timezone.utc) + timedelta(seconds=120)).isoformat(),
            "excluded_driver_ids": []}}


def cancellation(event):
    return {**event, "event_id": str(uuid4()), "event_type": "MatchingCancelled", "aggregate_version": 2,
        "data": {"trip_id": event["data"]["trip_id"], "attempt_id": event["data"]["attempt_id"], "reason": "cancel"}}


@pytest.fixture
def setup(tmp_path):
    settings = Settings(_env_file=None, demo_mode=True, driver_tls=False)
    db = Database(f"sqlite:///{tmp_path / 'matching.db'}")
    db.initialize()
    driver = FakeDriver()
    return Workflow(db, Contracts(settings.contracts_dir), driver, settings)


def ingest(w, event, offset=0):
    w.accept_record(w.settings.trip_topic, 0, offset, event["aggregate_id"].encode(), json.dumps(event).encode())
    assert w.process_inbox()


def advance(w, count=6):
    for _ in range(count):
        w.step()


def state(w, event):
    with w.db.session() as s:
        return s.get(Attempt, event["data"]["attempt_id"])


def events(w):
    with w.db.session() as s:
        return [r.payload for r in s.scalars(select(Outbox).order_by(Outbox.id))]


def make_due(w):
    with w.db.session.begin() as s:
        for row in s.scalars(select(Attempt)):
            row.next_run = 0


def test_success_valid_event_and_atomic_outbox(setup):
    w, e = setup, request()
    ingest(w, e)
    advance(w)
    assert state(w, e).state == "RESERVED"
    output, = events(w)
    w.contracts.validate("DriverReserved", output)
    assert output["data"]["driver_id"] == D1
    assert output["causation_id"] == e["event_id"]


def test_duplicate_source_and_duplicate_event_are_deduplicated(setup):
    w, e = setup, request()
    ingest(w, e)
    w.accept_record(w.settings.trip_topic, 0, 0, e["aggregate_id"].encode(), json.dumps(e).encode())
    ingest(w, e, offset=1)
    advance(w)
    assert len(events(w)) == 1
    assert len(w.driver.reserve_calls) == 1


def test_cancel_before_request_tombstone(setup):
    w, e = setup, request()
    ingest(w, cancellation(e))
    ingest(w, e, offset=1)
    advance(w)
    assert state(w, e).state == "CANCELLED"
    assert not w.driver.reserve_calls
    assert not events(w)


def test_no_driver(setup):
    w, e = setup, request()
    w.driver.candidates = []
    ingest(w, e)
    advance(w)
    assert events(w)[0]["data"]["reason"] == "NO_DRIVER"


def test_unavailable_first_candidate_uses_second(setup):
    w, e = setup, request()
    w.driver.reject.add(D1)
    ingest(w, e)
    advance(w, 8)
    assert events(w)[0]["data"]["driver_id"] == D2


def test_lost_response_restart_same_key_does_not_reserve_another_driver(setup):
    w, e = setup, request()
    w.driver.lose_response = True
    ingest(w, e)
    advance(w, 3)
    assert state(w, e).state == "RESERVING"
    assert not events(w)
    # Recreate service state from the on-disk DB, retaining only the external Driver.
    restarted_db = Database(str(w.db.engine.url))
    restarted = Workflow(restarted_db, w.contracts, w.driver, w.settings)
    make_due(restarted)
    advance(restarted)
    assert len(events(restarted)) == 1
    assert len(w.driver.reservations) == 1
    assert w.driver.reserve_calls[0] == w.driver.reserve_calls[1]


def test_cancel_unknown_reserve_reconciles_then_releases(setup):
    w, e = setup, request()
    w.driver.lose_response = True
    ingest(w, e)
    advance(w, 3)
    ingest(w, cancellation(e), offset=1)
    advance(w)
    assert state(w, e).state == "CANCELLED"
    assert len(w.driver.reserve_calls) == 2
    assert len(w.driver.release_calls) == 1
    assert not events(w)


def test_cancel_confirmed_does_not_release(setup):
    w, e = setup, request()
    ingest(w, e)
    advance(w)
    for reservation in w.driver.reservations.values():
        reservation["status"] = "CONFIRMED"
    ingest(w, cancellation(e), offset=1)
    advance(w)
    assert not w.driver.release_calls
    assert state(w, e).state == "CANCELLED"


def test_expired_request_never_reserves(setup):
    w, e = setup, request()
    e["data"]["search_deadline"] = (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat()
    ingest(w, e)
    advance(w)
    assert not w.driver.reserve_calls
    assert events(w)[0]["data"]["reason"] == "DEADLINE_EXCEEDED"


def test_deadline_during_unknown_reserve_cleanup_retry_retains_failure_result(setup):
    w, e = setup, request()
    w.driver.lose_response = True
    ingest(w, e)
    advance(w, 3)
    with w.db.session.begin() as s:
        a = s.get(Attempt, e["data"]["attempt_id"])
        a.deadline, a.next_run = time.time() - 1, 0
    w.driver.fail_release = True
    advance(w, 2)
    assert state(w, e).state == "RESERVED"
    assert not events(w)
    make_due(w)
    advance(w)
    assert state(w, e).state == "FAILED"
    assert events(w)[0]["data"]["reason"] == "DEADLINE_EXCEEDED"


def test_malformed_json_persisted_to_dlq_without_processed_marker(setup):
    w = setup
    w.accept_record(w.settings.trip_topic, 0, 0, b"bad", b"not json")
    for _ in range(6):
        assert w.process_inbox()
        with w.db.session.begin() as s:
            s.get(Inbox, 1).next_run = 0
    with w.db.session() as s:
        assert s.get(Inbox, 1).state == "DLQ"
        assert not list(s.scalars(select(Processed)))
        row = s.scalar(select(Outbox))
        assert row.topic.endswith(".dlq.matching-v1")
        w.contracts.validate("DeadLetter", row.payload)
        assert row.payload["original_bytes_base64"] == "bm90IGpzb24="


def test_invalid_kafka_trip_key_rejected(setup):
    e = request()
    with pytest.raises(ValueError, match="TRIP_KEY_MISMATCH"):
        setup.contracts.incoming(json.dumps(e).encode(), str(uuid4()).encode())


def test_producer_sequence_increases_across_attempts(setup):
    w, e = setup, request()
    w.driver.candidates = []
    ingest(w, e)
    advance(w)
    second = deepcopy(e)
    second["event_id"] = str(uuid4())
    second["data"]["attempt_id"] = str(uuid4())
    ingest(w, second, offset=1)
    advance(w)
    assert [x["aggregate_version"] for x in events(w)] == [1, 2]
