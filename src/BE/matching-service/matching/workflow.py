"""Durable state machine. Never hold a DB transaction while waiting for gRPC."""
import base64
import hashlib
import json
import logging
import time
from uuid import uuid4, uuid5, NAMESPACE_URL

from sqlalchemy import select

from .contracts import iso_now, timestamp
from .db import Attempt, Cancellation, Inbox, Outbox, Processed, Sequence
from .driver import DriverError

log = logging.getLogger(__name__)
RETRY_DELAYS = (1, 5, 15, 30, 60)


def mutation_key(attempt_id, action):
    return str(uuid5(NAMESPACE_URL, f"ds03/matching/{attempt_id}/{action}"))


class Workflow:
    def __init__(self, database, contracts, driver, settings):
        self.db, self.contracts, self.driver, self.settings = database, contracts, driver, settings

    def accept_record(self, topic, partition, offset, key, raw):
        # Commit to durable inbox BEFORE Kafka offset commit. A crash/rebalance can
        # redeliver the record, which this unique source-position constraint dedups.
        with self.db.session.begin() as session:
            exists = session.scalar(select(Inbox.id).where(
                Inbox.topic == topic, Inbox.partition == partition, Inbox.offset == str(offset)))
            if exists is None:
                session.add(Inbox(topic=topic, partition=partition, offset=str(offset), key=key, raw=raw))

    def process_inbox(self):
        with self.db.session() as session:
            row = session.scalar(select(Inbox).where(Inbox.state == "PENDING",
                Inbox.next_run <= time.time()).order_by(Inbox.id).limit(1))
        if row is None:
            return False
        try:
            event = self.contracts.incoming(row.raw, row.key)
            with self.db.session.begin() as session:
                current = session.get(Inbox, row.id)
                if event is not None:
                    self._ingest(session, event)
                current.state = "DONE"
        except Exception as exc:
            # Store only exception class, never request bodies/secrets in logs.
            with self.db.session.begin() as session:
                current = session.get(Inbox, row.id)
                current.retries += 1
                current.last_error = type(exc).__name__
                if current.retries <= len(RETRY_DELAYS):
                    current.next_run = time.time() + RETRY_DELAYS[current.retries - 1]
                else:
                    self._dead_letter(session, current)
                    current.state = "DLQ"
                    log.error("Inbox moved to DLQ: id=%s code=%s", current.id, current.last_error)
        return True

    def _ingest(self, session, event):
        digest = hashlib.sha256(json.dumps(event, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        processed = session.get(Processed, event["event_id"])
        if processed:
            if processed.digest != digest:
                raise ValueError("EVENT_ID_REUSED_WITH_DIFFERENT_CONTENT")
            return
        data = event["data"]
        attempt_id, trip_id = data["attempt_id"], data["trip_id"]
        cancellation = session.get(Cancellation, attempt_id)
        attempt = session.get(Attempt, attempt_id)
        if (attempt and attempt.trip_id != trip_id) or (cancellation and cancellation.trip_id != trip_id):
            raise ValueError("ATTEMPT_TRIP_MISMATCH")
        if event["event_type"] == "MatchingCancelled":
            if cancellation is None:
                session.add(Cancellation(attempt_id=attempt_id, trip_id=trip_id))
            if attempt:
                attempt.cancel_requested = True
                attempt.next_run = 0
        elif attempt is None:
            session.add(Attempt(attempt_id=attempt_id, trip_id=trip_id,
                request=event, deadline=timestamp(data["search_deadline"]),
                state="CANCELLED" if cancellation else "SEARCHING"))
        elif attempt.request["data"] != data:
            raise ValueError("ATTEMPT_ID_REUSED_WITH_DIFFERENT_CONTENT")
        session.add(Processed(event_id=event["event_id"], digest=digest))

    def _dead_letter(self, session, row):
        payload = {"original_topic": row.topic, "partition": row.partition,
            "offset": row.offset, "consumer_group": self.settings.consumer_group,
            "attempts": row.retries, "error_code": row.last_error, "failed_at": iso_now()}
        try:
            original = json.loads(row.raw)
            if not isinstance(original, dict):
                raise ValueError("Not an object")
            payload["original_message"] = original
        except (ValueError, UnicodeError):
            payload["original_bytes_base64"] = base64.b64encode(row.raw).decode()
        self.contracts.validate("DeadLetter", payload)
        session.add(Outbox(event_id=str(uuid4()),
            topic=f"{row.topic}.dlq.{self.settings.consumer_group}",
            key=(row.key or b"invalid").decode(errors="replace"), payload=payload))

    def emit(self, session, attempt, name, details):
        sequence = session.get(Sequence, attempt.trip_id)
        if sequence is None:
            sequence = Sequence(trip_id=attempt.trip_id, version=0)
            session.add(sequence)
        sequence.version += 1
        request = attempt.request
        event = {"event_id": str(uuid4()), "event_type": name, "schema_version": 1,
            "producer": "matching", "occurred_at": iso_now(),
            "correlation_id": request["correlation_id"], "causation_id": request["event_id"],
            "aggregate_id": attempt.trip_id, "aggregate_version": sequence.version,
            "data": {"trip_id": attempt.trip_id, "attempt_id": attempt.attempt_id, **details}}
        self.contracts.validate(name, event)
        session.add(Outbox(event_id=event["event_id"], topic=self.settings.matching_topic,
                           key=attempt.trip_id, payload=event))
        log.info("Result persisted: trip=%s attempt=%s event=%s", attempt.trip_id, attempt.attempt_id, name)

    def fail(self, session, attempt, reason):
        if attempt.cancel_requested:
            attempt.state = "CANCELLED"
        else:
            self.emit(session, attempt, "MatchingFailed", {"reason": reason})
            attempt.state = "FAILED"

    def step(self):
        with self.db.session() as session:
            attempt = session.scalar(select(Attempt).where(
                Attempt.next_run <= time.time(),
                (Attempt.state.in_(["SEARCHING", "RESERVING"])) |
                ((Attempt.state == "RESERVED") & Attempt.cancel_requested))
                .order_by(Attempt.next_run, Attempt.attempt_id).limit(1))
        if attempt is None:
            return False
        try:
            if attempt.state == "RESERVING":
                # Even after deadline/cancel: reconcile SAME reserve key first.
                self._reserve(attempt)
            elif attempt.cancel_requested:
                self._cleanup(attempt)
            elif attempt.deadline <= time.time():
                with self.db.session.begin() as session:
                    self.fail(session, session.get(Attempt, attempt.attempt_id), "DEADLINE_EXCEEDED")
            elif attempt.candidates is None:
                self._find(attempt)
            else:
                self._choose(attempt)
        except Exception as exc:
            code = exc.code if isinstance(exc, DriverError) else type(exc).__name__
            with self.db.session.begin() as session:
                current = session.get(Attempt, attempt.attempt_id)
                current.retries += 1
                current.last_error = code
                current.next_run = time.time() + RETRY_DELAYS[min(current.retries - 1, 4)]
                if current.state == "SEARCHING" and current.deadline <= time.time():
                    self.fail(session, current, "DEPENDENCY_UNAVAILABLE")
                # The provider's generic idempotency guarantee is >=7 days.
                # Stop automated replay outside that known window for unresolved reserves.
                if (current.state == "RESERVING" and current.reserve_started and
                        time.time() - current.reserve_started >= 6 * 86400):
                    current.state = "NEEDS_REVIEW"
                    log.critical("Manual reserve reconciliation required: attempt=%s", current.attempt_id)
            log.warning("Workflow retry: attempt=%s code=%s", attempt.attempt_id, code)
        return True

    def _find(self, attempt):
        candidates = self.driver.find(attempt.request["data"], attempt.request["correlation_id"])
        with self.db.session.begin() as session:
            current = session.get(Attempt, attempt.attempt_id)
            current.candidates = list(dict.fromkeys(candidates))
            current.retries, current.next_run = 0, 0

    def _choose(self, attempt):
        with self.db.session.begin() as session:
            current = session.get(Attempt, attempt.attempt_id)
            if current.cancel_requested:
                current.state = "CANCELLED"
            elif current.candidate_index >= len(current.candidates):
                self.fail(session, current, "NO_DRIVER")
            else:
                current.driver_id = current.candidates[current.candidate_index]
                current.reserve_key = mutation_key(current.attempt_id, f"reserve/{current.driver_id}")
                current.reserve_started = time.time()
                current.state = "RESERVING"
                current.next_run = 0
                # The gRPC call happens in a LATER step, after this transaction commits.

    def _reserve(self, attempt):
        correlation = attempt.request["correlation_id"]
        try:
            result = self.driver.reserve(attempt.driver_id, attempt.trip_id, attempt.reserve_key, correlation)
        except DriverError as exc:
            if not exc.rejected:
                raise
            with self.db.session.begin() as session:
                current = session.get(Attempt, attempt.attempt_id)
                current.candidate_index += 1
                current.state, current.next_run, current.retries = "SEARCHING", 0, 0
            return
        # Replaying an old idempotency key may return an already terminal reservation.
        if result["trip_id"] != attempt.trip_id or result["driver_id"] != attempt.driver_id:
            raise ValueError("RESERVATION_MISMATCH")
        if result["status"] not in ("HELD", "CONFIRMED", "EXPIRED", "RELEASED"):
            raise ValueError("INVALID_RESERVATION_STATUS")
        with self.db.session.begin() as session:
            current = session.get(Attempt, attempt.attempt_id)
            current.reservation, current.retries, current.next_run = result, 0, 0
            if result["status"] in ("EXPIRED", "RELEASED"):
                current.candidate_index += 1
                current.state = "CANCELLED" if current.cancel_requested else "SEARCHING"
            else:
                current.state = "RESERVED"
                if current.deadline <= time.time():
                    # Cleanup before declaring failure; an active reservation exists.
                    current.cancel_requested = True
                    current.deadline_cleanup = True
                elif not current.cancel_requested:
                    self.emit(session, current, "DriverReserved", {
                        "driver_id": result["driver_id"], "reservation_id": result["id"],
                        "expires_at": result["expires_at"]})

    def _cleanup(self, attempt):
        result = attempt.reservation
        if result:
            correlation = attempt.request["correlation_id"]
            result = self.driver.get(result["id"], correlation)
            if result["trip_id"] != attempt.trip_id or result["driver_id"] != attempt.driver_id:
                raise ValueError("RESERVATION_MISMATCH")
            if result["status"] == "HELD":
                result = self.driver.release(result["id"], attempt.trip_id,
                    mutation_key(attempt.attempt_id, "release"), correlation)
                if result["status"] not in ("RELEASED", "EXPIRED"):
                    raise ValueError("RELEASE_NOT_TERMINAL")
            elif result["status"] == "CONFIRMED":
                # Ownership has moved to Trip. Never call Release on CONFIRMED.
                log.warning("Confirmed reservation cleanup belongs to Trip: attempt=%s", attempt.attempt_id)
            elif result["status"] not in ("RELEASED", "EXPIRED"):
                raise ValueError("INVALID_RESERVATION_STATUS")
        with self.db.session.begin() as session:
            current = session.get(Attempt, attempt.attempt_id)
            current.state = "CANCELLED"
            # Deadline cleanup is internal, not a cancellation from Trip.
            if (current.deadline_cleanup and
                    session.get(Cancellation, current.attempt_id) is None):
                self.emit(session, current, "MatchingFailed", {"reason": "DEADLINE_EXCEEDED"})
                current.state = "FAILED"
