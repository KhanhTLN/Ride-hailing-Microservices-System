BEGIN;
ALTER TABLE trip
 ADD COLUMN IF NOT EXISTS reservation_id UUID,
 ADD COLUMN IF NOT EXISTS reservation_expires_at TIMESTAMPTZ,
 ADD COLUMN IF NOT EXISTS search_deadline TIMESTAMPTZ,
 ADD COLUMN IF NOT EXISTS matching_correlation_id UUID,
 ADD COLUMN IF NOT EXISTS matching_event_id UUID,
 ADD COLUMN IF NOT EXISTS excluded_driver_ids TEXT NOT NULL DEFAULT '';
UPDATE trip SET search_deadline=created_at+search_budget_seconds*INTERVAL '1 second' WHERE search_deadline IS NULL;
CREATE TABLE IF NOT EXISTS trip_event_sequence(trip_id UUID PRIMARY KEY, value BIGINT NOT NULL);
INSERT INTO trip_event_sequence SELECT aggregate_id,GREATEST(MAX(aggregate_version),0) FROM outbox_event GROUP BY aggregate_id
ON CONFLICT(trip_id) DO UPDATE SET value=GREATEST(trip_event_sequence.value,excluded.value);
ALTER TABLE trip_operation
 ADD COLUMN IF NOT EXISTS caller_id UUID,
 ADD COLUMN IF NOT EXISTS expected_version BIGINT,
 ADD COLUMN IF NOT EXISTS reservation_id UUID,
 ADD COLUMN IF NOT EXISTS driver_id UUID,
 ADD COLUMN IF NOT EXISTS attempt_id UUID,
 ADD COLUMN IF NOT EXISTS correlation_id UUID,
 ADD COLUMN IF NOT EXISTS reason VARCHAR(300),
 ADD COLUMN IF NOT EXISTS step VARCHAR(255) NOT NULL DEFAULT 'START',
 ADD COLUMN IF NOT EXISTS retries INTEGER NOT NULL DEFAULT 0,
 ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;
CREATE UNIQUE INDEX IF NOT EXISTS uk_trip_pending_operation ON trip_operation(trip_id) WHERE status='PENDING';
CREATE INDEX IF NOT EXISTS idx_trip_operation_due ON trip_operation(next_retry_at) WHERE status='PENDING';
ALTER TABLE idempotency_record ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);
CREATE TABLE IF NOT EXISTS trip_processed_event (
 event_id UUID PRIMARY KEY, request_hash VARCHAR(64) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS trip_reservation_cleanup (
 reservation_id UUID PRIMARY KEY, trip_id UUID NOT NULL,
 retries INTEGER NOT NULL DEFAULT 0, next_retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 done BOOLEAN NOT NULL DEFAULT FALSE, last_error TEXT
);
COMMIT;
