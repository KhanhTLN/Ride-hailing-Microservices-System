from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import ARRAY, BigInteger, DateTime, Double, Integer, LargeBinary, String, Text, text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import Mapped, mapped_column

from matching_service.persistence.database import Base

UUIDType = PGUUID(as_uuid=True)
Timestamp = DateTime(timezone=True)


class Updated:
    created_at: Mapped[datetime] = mapped_column(Timestamp, server_default=text("CURRENT_TIMESTAMP"))
    updated_at: Mapped[datetime] = mapped_column(Timestamp, server_default=text("CURRENT_TIMESTAMP"))


class MatchingAttempt(Updated, Base):
    __tablename__ = "matching_attempts"
    __table_args__ = {"schema": "public"}

    attempt_id: Mapped[UUID] = mapped_column(UUIDType, primary_key=True)
    trip_id: Mapped[UUID] = mapped_column(UUIDType)
    passenger_id: Mapped[UUID] = mapped_column(UUIDType)
    source_event_id: Mapped[UUID] = mapped_column(UUIDType)
    correlation_id: Mapped[UUID] = mapped_column(UUIDType)
    request_payload: Mapped[dict] = mapped_column(JSONB)
    request_hash: Mapped[str] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(24), server_default="RECEIVED")
    current_step: Mapped[str] = mapped_column(String(64), server_default="FIND_CANDIDATES")
    vehicle_type: Mapped[str] = mapped_column(String(16))
    pickup_latitude: Mapped[float] = mapped_column(Double)
    pickup_longitude: Mapped[float] = mapped_column(Double)
    pickup_address: Mapped[str | None] = mapped_column(String(300))
    excluded_driver_ids: Mapped[list[UUID]] = mapped_column(
        ARRAY(UUIDType), server_default=text("'{}'::uuid[]")
    )
    search_deadline: Mapped[datetime] = mapped_column(Timestamp)
    radius_m: Mapped[int] = mapped_column(Integer, server_default="5000")
    candidate_limit: Mapped[int] = mapped_column(Integer, server_default="20")
    hold_seconds: Mapped[int] = mapped_column(Integer, server_default="30")
    candidates: Mapped[list[dict]] = mapped_column(JSONB, server_default=text("'[]'::jsonb"))
    candidate_index: Mapped[int] = mapped_column(Integer, server_default="0")
    current_driver_id: Mapped[UUID | None] = mapped_column(UUIDType)
    reservation_id: Mapped[UUID | None] = mapped_column(UUIDType)
    reservation_expires_at: Mapped[datetime | None] = mapped_column(Timestamp)
    cancellation_requested_at: Mapped[datetime | None] = mapped_column(Timestamp)
    failure_reason: Mapped[str | None] = mapped_column(String(32))
    retry_count: Mapped[int] = mapped_column(Integer, server_default="0")
    next_retry_at: Mapped[datetime] = mapped_column(Timestamp, server_default=text("CURRENT_TIMESTAMP"))
    last_error_code: Mapped[str | None] = mapped_column(String(128))
    last_error: Mapped[str | None] = mapped_column(Text)
    locked_by: Mapped[str | None] = mapped_column(String(128))
    locked_until: Mapped[datetime | None] = mapped_column(Timestamp)
    version: Mapped[int] = mapped_column(BigInteger, server_default="0")
    completed_at: Mapped[datetime | None] = mapped_column(Timestamp)


class RpcOperation(Updated, Base):
    __tablename__ = "rpc_operations"
    __table_args__ = {"schema": "public"}

    id: Mapped[UUID] = mapped_column(UUIDType, primary_key=True)
    attempt_id: Mapped[UUID] = mapped_column(UUIDType)
    trip_id: Mapped[UUID] = mapped_column(UUIDType)
    caller: Mapped[str] = mapped_column(String(128), server_default="matching-service")
    operation: Mapped[str] = mapped_column(String(16))
    driver_id: Mapped[UUID] = mapped_column(UUIDType)
    reservation_id: Mapped[UUID | None] = mapped_column(UUIDType)
    idempotency_key: Mapped[str] = mapped_column(String(128))
    request_payload: Mapped[dict] = mapped_column(JSONB)
    request_hash: Mapped[str] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(16), server_default="PENDING")
    response_payload: Mapped[dict | None] = mapped_column(JSONB(none_as_null=True))
    retry_count: Mapped[int] = mapped_column(Integer, server_default="0")
    next_retry_at: Mapped[datetime] = mapped_column(Timestamp, server_default=text("CURRENT_TIMESTAMP"))
    last_error_code: Mapped[str | None] = mapped_column(String(128))
    last_error: Mapped[str | None] = mapped_column(Text)
    completed_at: Mapped[datetime | None] = mapped_column(Timestamp)


class ProcessedEvent(Base):
    __tablename__ = "processed_events"
    __table_args__ = {"schema": "public"}

    consumer_group: Mapped[str] = mapped_column(String(128), primary_key=True)
    event_id: Mapped[UUID] = mapped_column(UUIDType, primary_key=True)
    event_type: Mapped[str] = mapped_column(String(32))
    trip_id: Mapped[UUID] = mapped_column(UUIDType)
    attempt_id: Mapped[UUID] = mapped_column(UUIDType)
    correlation_id: Mapped[UUID] = mapped_column(UUIDType)
    payload_hash: Mapped[str] = mapped_column(String(64))
    original_topic: Mapped[str] = mapped_column(String(249))
    original_partition: Mapped[int] = mapped_column(Integer)
    original_offset: Mapped[int] = mapped_column(BigInteger)
    processed_at: Mapped[datetime] = mapped_column(Timestamp, server_default=text("CURRENT_TIMESTAMP"))
    expires_at: Mapped[datetime] = mapped_column(
        Timestamp, server_default=text("CURRENT_TIMESTAMP + INTERVAL '720 hours'")
    )


class CancellationTombstone(Base):
    __tablename__ = "cancellation_tombstones"
    __table_args__ = {"schema": "public"}

    attempt_id: Mapped[UUID] = mapped_column(UUIDType, primary_key=True)
    trip_id: Mapped[UUID] = mapped_column(UUIDType)
    source_event_id: Mapped[UUID] = mapped_column(UUIDType)
    correlation_id: Mapped[UUID] = mapped_column(UUIDType)
    reason: Mapped[str] = mapped_column(Text)
    cancelled_at: Mapped[datetime] = mapped_column(Timestamp, server_default=text("CURRENT_TIMESTAMP"))
    expires_at: Mapped[datetime] = mapped_column(
        Timestamp, server_default=text("CURRENT_TIMESTAMP + INTERVAL '168 hours'")
    )


class MessageFailure(Updated, Base):
    __tablename__ = "message_failures"
    __table_args__ = {"schema": "public"}

    id: Mapped[UUID] = mapped_column(UUIDType, primary_key=True)
    consumer_group: Mapped[str] = mapped_column(String(128))
    original_topic: Mapped[str] = mapped_column(String(249))
    original_partition: Mapped[int] = mapped_column(Integer)
    original_offset: Mapped[int] = mapped_column(BigInteger)
    original_key: Mapped[bytes | None] = mapped_column(LargeBinary)
    original_bytes: Mapped[bytes] = mapped_column(LargeBinary)
    original_message: Mapped[dict | None] = mapped_column(JSONB(none_as_null=True))
    original_headers: Mapped[list[Any]] = mapped_column(JSONB, server_default=text("'[]'::jsonb"))
    event_id: Mapped[UUID | None] = mapped_column(UUIDType)
    correlation_id: Mapped[UUID | None] = mapped_column(UUIDType)
    attempts: Mapped[int] = mapped_column(Integer, server_default="1")
    status: Mapped[str] = mapped_column(String(16), server_default="RETRY_PENDING")
    error_code: Mapped[str] = mapped_column(String(128))
    last_error: Mapped[str] = mapped_column(Text)
    failed_at: Mapped[datetime] = mapped_column(Timestamp, server_default=text("CURRENT_TIMESTAMP"))
    next_retry_at: Mapped[datetime] = mapped_column(Timestamp, server_default=text("CURRENT_TIMESTAMP"))
    dlq_topic: Mapped[str | None] = mapped_column(String(249))
    locked_by: Mapped[str | None] = mapped_column(String(128))
    locked_until: Mapped[datetime | None] = mapped_column(Timestamp)
    resolved_at: Mapped[datetime | None] = mapped_column(Timestamp)


class EventSequence(Base):
    __tablename__ = "event_sequences"
    __table_args__ = {"schema": "public"}

    trip_id: Mapped[UUID] = mapped_column(UUIDType, primary_key=True)
    last_version: Mapped[int] = mapped_column(Integer, server_default="0")
    updated_at: Mapped[datetime] = mapped_column(Timestamp, server_default=text("CURRENT_TIMESTAMP"))


class OutboxEvent(Updated, Base):
    __tablename__ = "outbox_events"
    __table_args__ = {"schema": "public"}

    event_id: Mapped[UUID] = mapped_column(UUIDType, primary_key=True)
    event_type: Mapped[str] = mapped_column(String(32))
    topic: Mapped[str] = mapped_column(String(249))
    kafka_key: Mapped[str] = mapped_column(Text)
    aggregate_id: Mapped[UUID | None] = mapped_column(UUIDType)
    aggregate_version: Mapped[int | None] = mapped_column(Integer)
    attempt_id: Mapped[UUID | None] = mapped_column(UUIDType)
    correlation_id: Mapped[UUID | None] = mapped_column(UUIDType)
    causation_id: Mapped[UUID | None] = mapped_column(UUIDType)
    source_failure_id: Mapped[UUID | None] = mapped_column(UUIDType)
    payload: Mapped[dict] = mapped_column(JSONB)
    status: Mapped[str] = mapped_column(String(16), server_default="PENDING")
    publish_attempts: Mapped[int] = mapped_column(Integer, server_default="0")
    next_retry_at: Mapped[datetime] = mapped_column(Timestamp, server_default=text("CURRENT_TIMESTAMP"))
    last_error: Mapped[str | None] = mapped_column(Text)
    locked_by: Mapped[str | None] = mapped_column(String(128))
    locked_until: Mapped[datetime | None] = mapped_column(Timestamp)
    version: Mapped[int] = mapped_column(BigInteger, server_default="0")
    sent_at: Mapped[datetime | None] = mapped_column(Timestamp)
