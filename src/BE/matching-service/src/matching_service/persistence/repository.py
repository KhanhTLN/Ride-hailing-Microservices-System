from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from random import uniform
from uuid import UUID, uuid4

from sqlalchemy import and_, exists, func, or_, select, text
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import aliased

from matching_service.config import Settings
from matching_service.domain.errors import EventConflictError, LostClaimError, WorkflowBusyError
from matching_service.domain.events import canonical_hash, result_event, utcnow
from matching_service.domain.states import ACTIVE_STATUSES, MESSAGE_BACKOFF, RPC_BACKOFF
from matching_service.messaging.validation import validate_payload
from matching_service.persistence.database import Database
from matching_service.persistence.models import (
    CancellationTombstone,
    EventSequence,
    MatchingAttempt,
    MessageFailure,
    OutboxEvent,
    ProcessedEvent,
    RpcOperation,
)


class MatchingRepository:
    """All transactions are short. Network calls belong outside this class."""

    def __init__(self, db: Database, settings: Settings):
        self.db, self.settings = db, settings

    @staticmethod
    async def _trip_lock(session, trip_id):
        # Serializes request/cancel even when the attempt row does not exist yet.
        await session.execute(
            text("SELECT pg_advisory_xact_lock(hashtextextended(:trip, 0))"), {"trip": str(trip_id)}
        )

    async def accept(self, event, record):
        data = event["data"]
        trip_id, attempt_id = UUID(data["trip_id"]), UUID(data["attempt_id"])
        digest = canonical_hash(event)
        async with self.db.transaction() as session:
            await self._trip_lock(session, trip_id)
            old_event = await session.get(
                ProcessedEvent, (self.settings.kafka_consumer_group, UUID(event["event_id"]))
            )
            if old_event:
                if old_event.payload_hash != digest:
                    raise EventConflictError("Event ID reused with different content")
                return
            attempt = await session.scalar(
                select(MatchingAttempt).where(MatchingAttempt.attempt_id == attempt_id).with_for_update()
            )
            tombstone = await session.get(CancellationTombstone, attempt_id)
            if (attempt and attempt.trip_id != trip_id) or (tombstone and tombstone.trip_id != trip_id):
                raise EventConflictError("Attempt ID reused for another trip")
            if event["event_type"] == "MatchingCancelled":
                if not tombstone:
                    session.add(
                        CancellationTombstone(
                            attempt_id=attempt_id,
                            trip_id=trip_id,
                            source_event_id=UUID(event["event_id"]),
                            correlation_id=UUID(event["correlation_id"]),
                            reason=data["reason"],
                        )
                    )
                if attempt and attempt.status not in ("CANCELLED", "FAILED"):
                    attempt.cancellation_requested_at = attempt.cancellation_requested_at or utcnow()
                    # A late cancel for an old RESERVED attempt must not conflict with
                    # a newer active attempt's unique index. Recovery also selects these rows.
                    if attempt.status != "RESERVED":
                        attempt.status = "CANCELLING"
                    attempt.next_retry_at = utcnow()
                    attempt.version += 1
            elif attempt:
                if attempt.request_hash != canonical_hash(data):
                    raise EventConflictError("Attempt ID reused with a different request")
            else:
                if not tombstone:
                    busy = await session.scalar(
                        select(MatchingAttempt.attempt_id).where(
                            MatchingAttempt.trip_id == trip_id, MatchingAttempt.status.in_(ACTIVE_STATUSES)
                        )
                    )
                    if busy:
                        raise WorkflowBusyError("Previous attempt has unresolved work")
                session.add(
                    MatchingAttempt(
                        attempt_id=attempt_id,
                        trip_id=trip_id,
                        passenger_id=UUID(data["passenger_id"]),
                        source_event_id=UUID(event["event_id"]),
                        correlation_id=UUID(event["correlation_id"]),
                        request_payload=data,
                        request_hash=canonical_hash(data),
                        status="CANCELLED" if tombstone else "RECEIVED",
                        cancellation_requested_at=utcnow() if tombstone else None,
                        completed_at=utcnow() if tombstone else None,
                        vehicle_type=data["vehicle_type"],
                        pickup_latitude=data["pickup"]["latitude"],
                        pickup_longitude=data["pickup"]["longitude"],
                        pickup_address=data["pickup"].get("address"),
                        excluded_driver_ids=[UUID(v) for v in data["excluded_driver_ids"]],
                        search_deadline=datetime.fromisoformat(
                            data["search_deadline"].replace("Z", "+00:00")
                        ),
                        radius_m=self.settings.matching_radius_m,
                        candidate_limit=self.settings.matching_candidate_limit,
                        hold_seconds=self.settings.reservation_hold_seconds,
                    )
                )
            session.add(
                ProcessedEvent(
                    consumer_group=self.settings.kafka_consumer_group,
                    event_id=UUID(event["event_id"]),
                    event_type=event["event_type"],
                    trip_id=trip_id,
                    attempt_id=attempt_id,
                    correlation_id=UUID(event["correlation_id"]),
                    payload_hash=digest,
                    original_topic=record.topic,
                    original_partition=record.partition,
                    original_offset=record.offset,
                )
            )

    async def claim_attempt(self):
        async with self.db.transaction() as session:
            row = await session.scalar(
                select(MatchingAttempt)
                .where(
                    or_(
                        MatchingAttempt.status.in_(ACTIVE_STATUSES),
                        and_(
                            MatchingAttempt.status == "RESERVED",
                            MatchingAttempt.cancellation_requested_at.is_not(None),
                        ),
                    ),
                    MatchingAttempt.next_retry_at <= func.now(),
                    or_(MatchingAttempt.locked_until.is_(None), MatchingAttempt.locked_until < func.now()),
                )
                .order_by(MatchingAttempt.next_retry_at, MatchingAttempt.created_at)
                .with_for_update(skip_locked=True)
                .limit(1)
            )
            if not row:
                return None
            row.locked_by = str(uuid4())
            row.locked_until = utcnow() + timedelta(seconds=self.settings.worker_lease_seconds)
            row.version += 1
            return row

    @asynccontextmanager
    async def owned(self, attempt_id, token):
        async with self.db.transaction() as session:
            # Determine trip before taking locks; trip_id is immutable.
            trip_id = await session.scalar(
                select(MatchingAttempt.trip_id).where(MatchingAttempt.attempt_id == attempt_id)
            )
            if not trip_id:
                raise LostClaimError("Attempt no longer exists")
            await self._trip_lock(session, trip_id)
            row = await session.scalar(
                select(MatchingAttempt).where(MatchingAttempt.attempt_id == attempt_id).with_for_update()
            )
            if row.locked_by != token or row.locked_until is None or row.locked_until <= utcnow():
                raise LostClaimError("Worker claim expired or was replaced")
            yield session, row
            row.version += 1

    async def load_work(self, attempt_id, token):
        async with self.owned(attempt_id, token) as (session, row):
            op = await session.scalar(
                select(RpcOperation).where(
                    RpcOperation.attempt_id == attempt_id, RpcOperation.status.in_(("PENDING", "UNKNOWN"))
                )
            )
            return row, op

    @staticmethod
    def _unlock(row, delay=0):
        row.locked_by = row.locked_until = None
        row.next_retry_at = utcnow() + timedelta(seconds=delay)

    async def unlock(self, attempt_id, token):
        async with self.owned(attempt_id, token) as (_, row):
            self._unlock(row)

    async def save_candidates(self, attempt_id, token, candidates):
        async with self.owned(attempt_id, token) as (session, row):
            row.candidates = candidates
            row.candidate_index = 0
            row.current_step = "RESERVE_DRIVER"
            row.status = "CANCELLING" if row.cancellation_requested_at else "FINDING"
            if not candidates and not row.cancellation_requested_at:
                reason = "DEADLINE_EXCEEDED" if row.search_deadline <= utcnow() else "NO_DRIVER"
                await self._finish_failed(session, row, reason)
            self._unlock(row)

    async def prepare_reserve(self, attempt_id, token):
        async with self.owned(attempt_id, token) as (session, row):
            if row.cancellation_requested_at:
                self._unlock(row)
                return None
            if row.search_deadline <= utcnow():
                await self._finish_failed(session, row, "DEADLINE_EXCEEDED")
                self._unlock(row)
                return None
            if row.candidate_index >= len(row.candidates):
                await self._finish_failed(session, row, "NO_DRIVER")
                self._unlock(row)
                return None
            driver_id = UUID(row.candidates[row.candidate_index]["driver_id"])
            op_id = uuid4()
            payload = {
                "driver_id": str(driver_id),
                "trip_id": str(row.trip_id),
                "hold_seconds": row.hold_seconds,
                "idempotency_key": str(op_id),
            }
            op = RpcOperation(
                id=op_id,
                attempt_id=attempt_id,
                trip_id=row.trip_id,
                operation="RESERVE",
                driver_id=driver_id,
                idempotency_key=str(op_id),
                request_payload=payload,
                request_hash=canonical_hash(payload),
            )
            session.add(op)
            row.current_driver_id = driver_id
            row.status, row.current_step = "RESERVING", "RESERVE_DRIVER"
            await session.flush()
            return op

    async def reserved(self, attempt_id, token, op_id, response):
        async with self.owned(attempt_id, token) as (session, row):
            op = await session.get(RpcOperation, op_id)
            op.status, op.completed_at, op.response_payload = "SUCCEEDED", utcnow(), response
            op.reservation_id = UUID(response["id"])
            row.reservation_id = op.reservation_id
            row.reservation_expires_at = datetime.fromisoformat(response["expires_at"].replace("Z", "+00:00"))
            if response["status"] in ("RELEASED", "EXPIRED"):
                row.reservation_id = row.reservation_expires_at = None
                row.current_driver_id = None
                row.candidate_index += 1
                row.status, row.current_step = "FINDING", "RESERVE_DRIVER"
            elif (
                row.cancellation_requested_at
                or row.search_deadline <= utcnow()
                or (response["status"] == "HELD" and row.reservation_expires_at <= utcnow())
            ):
                row.status, row.current_step = "CANCELLING", "RELEASE_RESERVATION"
                if not row.cancellation_requested_at:
                    row.failure_reason = "DEADLINE_EXCEEDED"
            else:
                row.status, row.current_step, row.completed_at = "RESERVED", "DONE", utcnow()
                await self._emit(
                    session,
                    row,
                    "DriverReserved",
                    {
                        "driver_id": str(row.current_driver_id),
                        "reservation_id": str(row.reservation_id),
                        "expires_at": response["expires_at"],
                    },
                )
            self._unlock(row)

    async def rpc_error(self, attempt_id, token, op_id, error):
        async with self.owned(attempt_id, token) as (session, row):
            op = await session.get(RpcOperation, op_id)
            op.last_error_code = row.last_error_code = error.code
            op.last_error = row.last_error = error.code
            op.retry_count += 1
            row.retry_count += 1
            # Release outcomes are always reconciled by GetReservation, including
            # a permission denial caused by a concurrent Trip confirmation.
            unresolved = error.ambiguous or error.retryable or op.operation == "RELEASE"
            if unresolved:
                op.status = "UNKNOWN"
                if op.operation == "RESERVE":
                    row.status = "CANCELLING" if row.cancellation_requested_at else "RESERVE_UNKNOWN"
                delay = (RPC_BACKOFF[op.retry_count - 1] if op.retry_count <= 3 else 5) + uniform(0, 0.1)
                op.next_retry_at = utcnow() + timedelta(seconds=delay)
                self._unlock(row, delay)
            else:
                op.status, op.completed_at = "FAILED", utcnow()
                if row.cancellation_requested_at:
                    row.status, row.current_step, row.completed_at = "CANCELLED", "DONE", utcnow()
                elif error.code in ("DRIVER_UNAVAILABLE", "RESERVATION_EXPIRED", "VERSION_CONFLICT"):
                    row.candidate_index += 1
                    row.current_driver_id = None
                    row.status, row.current_step = "FINDING", "RESERVE_DRIVER"
                else:
                    await self._finish_failed(session, row, "DEPENDENCY_UNAVAILABLE")
                self._unlock(row)

    async def query_error(self, attempt_id, token, error):
        async with self.owned(attempt_id, token) as (session, row):
            row.retry_count += 1
            row.last_error_code = row.last_error = error.code
            unresolved = await session.scalar(
                select(RpcOperation.id).where(
                    RpcOperation.attempt_id == attempt_id, RpcOperation.status.in_(("PENDING", "UNKNOWN"))
                )
            )
            cleanup = row.cancellation_requested_at or row.reservation_id or unresolved
            if not cleanup and (not error.retryable or row.search_deadline <= utcnow()):
                await self._finish_failed(session, row, "DEPENDENCY_UNAVAILABLE")
            delay = (RPC_BACKOFF[row.retry_count - 1] if row.retry_count <= 3 else 5) + uniform(0, 0.1)
            self._unlock(row, delay)

    async def fail(self, attempt_id, token, reason):
        async with self.owned(attempt_id, token) as (session, row):
            if row.cancellation_requested_at:
                row.status, row.current_step, row.completed_at = "CANCELLED", "DONE", utcnow()
            else:
                await self._finish_failed(session, row, reason)
            self._unlock(row)

    async def _finish_failed(self, session, row, reason):
        row.status, row.failure_reason = "FAILED", reason
        row.current_step, row.completed_at = "DONE", utcnow()
        await self._emit(session, row, "MatchingFailed", {"reason": reason})

    async def _emit(self, session, row, event_type, data):
        stmt = insert(EventSequence).values(trip_id=row.trip_id, last_version=1)
        version = await session.scalar(
            stmt.on_conflict_do_update(
                index_elements=[EventSequence.trip_id], set_={"last_version": EventSequence.last_version + 1}
            ).returning(EventSequence.last_version)
        )
        event = result_event(row, event_type, version, data)
        validate_payload(event, event_type)
        session.add(
            OutboxEvent(
                event_id=UUID(event["event_id"]),
                event_type=event_type,
                topic=self.settings.kafka_matching_topic,
                kafka_key=str(row.trip_id),
                aggregate_id=row.trip_id,
                aggregate_version=version,
                attempt_id=row.attempt_id,
                correlation_id=row.correlation_id,
                causation_id=row.source_event_id,
                payload=event,
            )
        )

    async def prepare_release(self, attempt_id, token):
        async with self.owned(attempt_id, token) as (session, row):
            old = await session.scalar(
                select(RpcOperation).where(
                    RpcOperation.attempt_id == attempt_id, RpcOperation.operation == "RELEASE"
                )
            )
            if old:
                return old
            op_id = uuid4()
            payload = {
                "reservation_id": str(row.reservation_id),
                "trip_id": str(row.trip_id),
                "reason": "MATCHING_CANCELLED" if row.cancellation_requested_at else "SEARCH_EXPIRED",
                "idempotency_key": str(op_id),
            }
            op = RpcOperation(
                id=op_id,
                attempt_id=attempt_id,
                trip_id=row.trip_id,
                operation="RELEASE",
                driver_id=row.current_driver_id,
                reservation_id=row.reservation_id,
                idempotency_key=str(op_id),
                request_payload=payload,
                request_hash=canonical_hash(payload),
            )
            session.add(op)
            await session.flush()
            return op

    async def released(self, attempt_id, token, response):
        async with self.owned(attempt_id, token) as (session, row):
            op = await session.scalar(
                select(RpcOperation).where(
                    RpcOperation.attempt_id == attempt_id,
                    RpcOperation.operation == "RELEASE",
                    RpcOperation.status.in_(("PENDING", "UNKNOWN")),
                )
            )
            if op:
                op.status = "FAILED" if response["status"] == "CONFIRMED" else "SUCCEEDED"
                op.completed_at, op.response_payload = utcnow(), response
                if response["status"] == "CONFIRMED":
                    op.last_error_code = op.last_error = "RESERVATION_CONFIRMED_OWNED_BY_TRIP"
            if row.cancellation_requested_at or row.failure_reason is None:
                row.status, row.current_step, row.completed_at = "CANCELLED", "DONE", utcnow()
            else:
                await self._finish_failed(session, row, row.failure_reason)
            self._unlock(row)

    async def claim_outbox(self):
        older = aliased(OutboxEvent)
        async with self.db.transaction() as session:
            row = await session.scalar(
                select(OutboxEvent)
                .where(
                    OutboxEvent.status == "PENDING",
                    OutboxEvent.next_retry_at <= func.now(),
                    or_(OutboxEvent.locked_until.is_(None), OutboxEvent.locked_until < func.now()),
                    ~exists(
                        select(1).where(
                            older.aggregate_id == OutboxEvent.aggregate_id,
                            older.aggregate_version < OutboxEvent.aggregate_version,
                            older.status == "PENDING",
                        )
                    ),
                )
                .order_by(OutboxEvent.created_at, OutboxEvent.aggregate_version)
                .with_for_update(skip_locked=True)
                .limit(1)
            )
            if row:
                row.locked_by = str(uuid4())
                row.locked_until = utcnow() + timedelta(seconds=self.settings.worker_lease_seconds)
                row.version += 1
            return row

    async def outbox_result(self, event_id, token, *, error=None):
        async with self.db.transaction() as session:
            row = await session.scalar(
                select(OutboxEvent)
                .where(
                    OutboxEvent.event_id == event_id,
                    OutboxEvent.locked_by == token,
                    OutboxEvent.locked_until > func.now(),
                )
                .with_for_update()
            )
            if not row:
                raise LostClaimError("Outbox claim replaced")
            row.publish_attempts += 1
            if error:
                row.last_error = error
                row.next_retry_at = utcnow() + timedelta(seconds=min(60, 2 ** min(row.publish_attempts, 6)))
            else:
                row.status, row.sent_at = "SENT", utcnow()
                if row.source_failure_id:
                    failure = await session.get(MessageFailure, row.source_failure_id)
                    failure.status = "DLQ_SENT"
            row.locked_by = row.locked_until = None
            row.version += 1

    async def record_failure(self, record, error_code):
        from matching_service.messaging.dead_letter import headers_json

        async with self.db.transaction() as session:
            stmt = (
                insert(MessageFailure)
                .values(
                    id=uuid4(),
                    consumer_group=self.settings.kafka_consumer_group,
                    original_topic=record.topic,
                    original_partition=record.partition,
                    original_offset=record.offset,
                    original_key=record.key,
                    original_bytes=record.value,
                    original_headers=headers_json(record.headers),
                    error_code=error_code,
                    last_error=error_code,
                    next_retry_at=utcnow() + timedelta(seconds=MESSAGE_BACKOFF[0]),
                )
                .on_conflict_do_nothing(constraint="uq_message_failure_record")
            )
            await session.execute(stmt)

    async def claim_failure(self):
        async with self.db.transaction() as session:
            row = await session.scalar(
                select(MessageFailure)
                .where(
                    MessageFailure.status == "RETRY_PENDING",
                    MessageFailure.next_retry_at <= func.now(),
                    or_(MessageFailure.locked_until.is_(None), MessageFailure.locked_until < func.now()),
                )
                .order_by(MessageFailure.next_retry_at)
                .with_for_update(skip_locked=True)
                .limit(1)
            )
            if row:
                row.locked_by = str(uuid4())
                row.locked_until = utcnow() + timedelta(seconds=self.settings.worker_lease_seconds)
            return row

    async def failure_result(self, failure_id, token, *, error_code=None):
        from matching_service.messaging.dead_letter import dead_letter_payload

        async with self.db.transaction() as session:
            row = await session.scalar(
                select(MessageFailure)
                .where(
                    MessageFailure.id == failure_id,
                    MessageFailure.locked_by == token,
                    MessageFailure.locked_until > func.now(),
                )
                .with_for_update()
            )
            if not row:
                raise LostClaimError("Message failure claim replaced")
            if not error_code:
                row.status, row.resolved_at = "RESOLVED", utcnow()
            elif row.attempts >= len(MESSAGE_BACKOFF):
                row.attempts += 1
                row.error_code = row.last_error = error_code
                row.failed_at = utcnow()
                row.status = "DLQ_PENDING"
                row.dlq_topic = f"{row.original_topic}.dlq.{row.consumer_group}"
                payload = dead_letter_payload(row)
                validate_payload(payload, "DeadLetter")
                session.add(
                    OutboxEvent(
                        event_id=uuid4(),
                        event_type="DeadLetter",
                        topic=row.dlq_topic,
                        kafka_key=str(row.id),
                        source_failure_id=row.id,
                        payload=payload,
                    )
                )
            else:
                row.attempts += 1
                row.error_code = row.last_error = error_code
                row.next_retry_at = utcnow() + timedelta(seconds=MESSAGE_BACKOFF[row.attempts - 1])
            row.locked_by = row.locked_until = None
