-- ============================================================================
-- MATCHING SERVICE - PostgreSQL 14+
-- Database đề xuất: matching (đúng tên trong matching-service/.env.example).
-- Chạy file này khi đang kết nối database RIÊNG của Matching Service.
--
-- Cách 1 - pgAdmin:
--   Tạo database matching -> Query Tool của database đó -> mở và chạy file này.
-- Cách 2 - PowerShell, từ thư mục gốc repo:
--   createdb -h localhost -p 5432 -U postgres matching
--   psql -h localhost -p 5432 -U postgres -d matching -v ON_ERROR_STOP=1 -f scripts/sql/matchingdb_init.sql
--
-- Đây là bootstrap schema, không phải migration nâng cấp schema đã tồn tại.
-- Chạy lại sẽ giữ các bảng/dữ liệu hiện có; không có DROP TABLE hoặc dữ liệu mẫu.
-- Không tạo role/password. Dùng tài khoản đã import hoặc cấp quyền cho app riêng.
-- Không tạo FK sang Trip/Driver/User DB; các ID đó chỉ là tham chiếu liên service.
-- Reservation và ràng buộc một tài xế/một chuyến thuộc Driver Service.
-- ============================================================================

BEGIN;
SET LOCAL TIME ZONE 'UTC';

-- 1. WORKFLOW: một bản ghi cho một attempt_id do Trip cấp.
-- request_payload lưu nguyên data của TripRequested để đối chiếu khi replay.
-- candidates lưu danh sách ứng viên đã tìm để restart không mất tiến trình.
CREATE TABLE IF NOT EXISTS public.matching_attempts (
    attempt_id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    passenger_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    request_payload JSONB NOT NULL,
    request_hash VARCHAR(64) NOT NULL,

    status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    current_step VARCHAR(64) NOT NULL DEFAULT 'FIND_CANDIDATES',
    vehicle_type VARCHAR(16) NOT NULL,
    pickup_latitude DOUBLE PRECISION NOT NULL,
    pickup_longitude DOUBLE PRECISION NOT NULL,
    pickup_address VARCHAR(300),
    excluded_driver_ids UUID[] NOT NULL DEFAULT ARRAY[]::UUID[],
    search_deadline TIMESTAMPTZ NOT NULL,
    radius_m INTEGER NOT NULL DEFAULT 5000,
    candidate_limit INTEGER NOT NULL DEFAULT 20,
    hold_seconds INTEGER NOT NULL DEFAULT 30,
    candidates JSONB NOT NULL DEFAULT '[]'::JSONB,
    candidate_index INTEGER NOT NULL DEFAULT 0,

    current_driver_id UUID,
    reservation_id UUID UNIQUE,
    reservation_expires_at TIMESTAMPTZ,
    cancellation_requested_at TIMESTAMPTZ,
    failure_reason VARCHAR(32),
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_code VARCHAR(128),
    last_error TEXT,

    -- Claim worker trong transaction ngắn. Dùng version để chặn worker cũ ghi đè.
    locked_by VARCHAR(128),
    locked_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,

    CONSTRAINT uq_matching_attempt_trip UNIQUE (attempt_id, trip_id),
    CONSTRAINT ck_matching_attempt_status CHECK (status IN (
        'RECEIVED', 'FINDING', 'RESERVING', 'RESERVE_UNKNOWN',
        'RESERVED', 'CANCELLING', 'CANCELLED', 'FAILED'
    )),
    CONSTRAINT ck_matching_attempt_vehicle CHECK (vehicle_type IN ('MOTORBIKE', 'CAR')),
    CONSTRAINT ck_matching_attempt_latitude CHECK (pickup_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_matching_attempt_longitude CHECK (pickup_longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_matching_attempt_request CHECK (jsonb_typeof(request_payload) = 'object'),
    CONSTRAINT ck_matching_attempt_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_matching_attempt_excluded CHECK (
        cardinality(excluded_driver_ids) <= 100
        AND array_position(excluded_driver_ids, NULL) IS NULL
    ),
    CONSTRAINT ck_matching_attempt_radius CHECK (radius_m BETWEEN 100 AND 10000),
    CONSTRAINT ck_matching_attempt_limit CHECK (candidate_limit BETWEEN 1 AND 20),
    CONSTRAINT ck_matching_attempt_hold CHECK (hold_seconds BETWEEN 15 AND 60),
    CONSTRAINT ck_matching_attempt_candidates CHECK (
        jsonb_typeof(candidates) = 'array' AND jsonb_array_length(candidates) <= 20
    ),
    CONSTRAINT ck_matching_attempt_index CHECK (candidate_index BETWEEN 0 AND 20),
    CONSTRAINT ck_matching_attempt_retry CHECK (retry_count >= 0),
    CONSTRAINT ck_matching_attempt_version CHECK (version >= 0),
    CONSTRAINT ck_matching_attempt_lock CHECK (
        (locked_by IS NULL) = (locked_until IS NULL)
    ),
    CONSTRAINT ck_matching_attempt_reserved CHECK (
        status <> 'RESERVED' OR (
            current_driver_id IS NOT NULL AND reservation_id IS NOT NULL
            AND reservation_expires_at IS NOT NULL
        )
    ),
    CONSTRAINT ck_matching_attempt_failure_reason CHECK (
        failure_reason IN ('NO_DRIVER', 'DEADLINE_EXCEEDED', 'DEPENDENCY_UNAVAILABLE')
    ),
    CONSTRAINT ck_matching_attempt_failed CHECK (
        status <> 'FAILED' OR failure_reason IS NOT NULL
    )
);

-- Không xử lý song song hai lượt tìm đang hoạt động cho cùng một trip.
-- RESERVED đã bàn giao kết quả cho Trip; Trip điều phối reject/expiry/attempt mới.
CREATE UNIQUE INDEX IF NOT EXISTS uq_matching_attempt_active_trip
    ON public.matching_attempts (trip_id)
    WHERE status IN ('RECEIVED', 'FINDING', 'RESERVING', 'RESERVE_UNKNOWN', 'CANCELLING');

CREATE INDEX IF NOT EXISTS idx_matching_attempt_due
    ON public.matching_attempts (next_retry_at, locked_until)
    WHERE status IN ('RECEIVED', 'FINDING', 'RESERVING', 'RESERVE_UNKNOWN', 'CANCELLING');

CREATE INDEX IF NOT EXISTS idx_matching_attempt_trip
    ON public.matching_attempts (trip_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_matching_attempt_deadline
    ON public.matching_attempts (search_deadline)
    WHERE status IN ('RECEIVED', 'FINDING', 'RESERVING', 'RESERVE_UNKNOWN');

-- 2. RPC/SAGA: lưu request và key TRƯỚC lời gọi Driver, ngoài transaction mạng.
-- UNKNOWN có nghĩa kết quả chưa rõ; không được coi là FAILED rồi chọn driver khác.
CREATE TABLE IF NOT EXISTS public.rpc_operations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id UUID NOT NULL,
    trip_id UUID NOT NULL,
    caller VARCHAR(128) NOT NULL DEFAULT 'matching-service',
    operation VARCHAR(16) NOT NULL,
    driver_id UUID NOT NULL,
    reservation_id UUID,
    idempotency_key VARCHAR(128) NOT NULL,
    request_payload JSONB NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    response_payload JSONB,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_code VARCHAR(128),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,

    CONSTRAINT fk_rpc_attempt FOREIGN KEY (attempt_id, trip_id)
        REFERENCES public.matching_attempts (attempt_id, trip_id),
    CONSTRAINT uq_rpc_idempotency UNIQUE (caller, operation, idempotency_key),
    CONSTRAINT uq_rpc_candidate_operation UNIQUE (attempt_id, operation, driver_id),
    CONSTRAINT ck_rpc_operation CHECK (operation IN ('RESERVE', 'RELEASE')),
    CONSTRAINT ck_rpc_status CHECK (status IN ('PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_rpc_key CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT ck_rpc_request CHECK (jsonb_typeof(request_payload) = 'object'),
    CONSTRAINT ck_rpc_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_rpc_response CHECK (jsonb_typeof(response_payload) = 'object'),
    CONSTRAINT ck_rpc_retry CHECK (retry_count >= 0),
    CONSTRAINT ck_rpc_release CHECK (operation <> 'RELEASE' OR reservation_id IS NOT NULL),
    CONSTRAINT ck_rpc_completed CHECK (
        (status IN ('SUCCEEDED', 'FAILED')) = (completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_rpc_success_response CHECK (
        status <> 'SUCCEEDED' OR response_payload IS NOT NULL
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_rpc_unresolved_attempt
    ON public.rpc_operations (attempt_id) WHERE status IN ('PENDING', 'UNKNOWN');

CREATE INDEX IF NOT EXISTS idx_rpc_retry
    ON public.rpc_operations (next_retry_at) WHERE status IN ('PENDING', 'UNKNOWN');

-- 3. INBOX/DEDUP: ghi cùng transaction với tiếp nhận workflow/cancellation.
-- processed_at là đã tiếp nhận bền, không khẳng định toàn bộ Saga đã hoàn thành.
-- Message chỉ mới vào retry/DLQ không được đánh dấu processed tại bảng này.
CREATE TABLE IF NOT EXISTS public.processed_events (
    consumer_group VARCHAR(128) NOT NULL DEFAULT 'matching-v1',
    event_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    trip_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    original_topic VARCHAR(249) NOT NULL,
    original_partition INTEGER NOT NULL,
    original_offset BIGINT NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '720 hours'),

    PRIMARY KEY (consumer_group, event_id),
    CONSTRAINT ck_processed_event_type CHECK (event_type IN ('TripRequested', 'MatchingCancelled')),
    CONSTRAINT ck_processed_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_processed_partition CHECK (original_partition >= 0),
    CONSTRAINT ck_processed_offset CHECK (original_offset >= 0),
    CONSTRAINT ck_processed_retention CHECK (expires_at >= processed_at + INTERVAL '720 hours')
);

CREATE INDEX IF NOT EXISTS idx_processed_event_expiry ON public.processed_events (expires_at);

-- 4. CANCELLATION: không FK attempt_id, vì cancel có thể đến TRƯỚC request.
CREATE TABLE IF NOT EXISTS public.cancellation_tombstones (
    attempt_id UUID PRIMARY KEY,
    trip_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    reason TEXT NOT NULL,
    cancelled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '168 hours'),
    CONSTRAINT ck_cancellation_retention CHECK (
        expires_at >= cancelled_at + INTERVAL '168 hours'
    )
);

CREATE INDEX IF NOT EXISTS idx_cancellation_expiry ON public.cancellation_tombstones (expires_at);

-- 5. MESSAGE RETRY/DLQ: luôn giữ bytes gốc, kể cả JSON/UTF-8 không hợp lệ.
-- original_message chỉ chứa JSON object khi parse được. Khi tạo DLQ, dùng đúng
-- MỘT trường original_message hoặc original_bytes_base64 theo DeadLetter.schema.
CREATE TABLE IF NOT EXISTS public.message_failures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumer_group VARCHAR(128) NOT NULL DEFAULT 'matching-v1',
    original_topic VARCHAR(249) NOT NULL,
    original_partition INTEGER NOT NULL,
    original_offset BIGINT NOT NULL,
    original_key BYTEA,
    original_bytes BYTEA NOT NULL,
    original_message JSONB,
    original_headers JSONB NOT NULL DEFAULT '[]'::JSONB,
    event_id UUID,
    correlation_id UUID,
    attempts INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'RETRY_PENDING',
    error_code VARCHAR(128) NOT NULL,
    last_error TEXT NOT NULL,
    failed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    dlq_topic VARCHAR(249),
    locked_by VARCHAR(128),
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,

    CONSTRAINT uq_message_failure_record UNIQUE (
        consumer_group, original_topic, original_partition, original_offset
    ),
    CONSTRAINT ck_message_failure_partition CHECK (original_partition >= 0),
    CONSTRAINT ck_message_failure_offset CHECK (original_offset >= 0),
    CONSTRAINT ck_message_failure_attempts CHECK (attempts >= 1),
    CONSTRAINT ck_message_failure_status CHECK (
        status IN ('RETRY_PENDING', 'DLQ_PENDING', 'DLQ_SENT', 'RESOLVED')
    ),
    CONSTRAINT ck_message_failure_json CHECK (jsonb_typeof(original_message) = 'object'),
    CONSTRAINT ck_message_failure_headers CHECK (jsonb_typeof(original_headers) = 'array'),
    CONSTRAINT ck_message_failure_lock CHECK ((locked_by IS NULL) = (locked_until IS NULL)),
    CONSTRAINT ck_message_failure_dlq CHECK (
        status NOT IN ('DLQ_PENDING', 'DLQ_SENT') OR dlq_topic IS NOT NULL
    ),
    CONSTRAINT ck_message_failure_resolved CHECK (
        (status = 'RESOLVED') = (resolved_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_message_failure_due
    ON public.message_failures (next_retry_at, locked_until)
    WHERE status IN ('RETRY_PENDING', 'DLQ_PENDING');

-- 6. VERSION: tăng nguyên tử theo trip trong cùng transaction ghi outbox.
-- Dùng INSERT ... ON CONFLICT (trip_id) DO UPDATE
-- SET last_version = event_sequences.last_version + 1 RETURNING last_version.
-- Không dùng MAX(version) + 1: có race khi nhiều worker cùng ghi.
CREATE TABLE IF NOT EXISTS public.event_sequences (
    trip_id UUID PRIMARY KEY,
    last_version INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_event_sequence_version CHECK (last_version >= 0)
);

-- 7. OUTBOX: ghi cùng transaction với kết quả nghiệp vụ hoặc handoff DLQ.
-- DeadLetter là phân loại NỘI BỘ, không thêm event_type vào payload DLQ.
-- Không giữ khóa DB trong lúc publish; claim batch ngắn và kiểm tra version khi ack.
CREATE TABLE IF NOT EXISTS public.outbox_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(32) NOT NULL,
    topic VARCHAR(249) NOT NULL,
    kafka_key TEXT NOT NULL,
    aggregate_id UUID,
    aggregate_version INTEGER,
    attempt_id UUID,
    correlation_id UUID,
    causation_id UUID,
    source_failure_id UUID UNIQUE REFERENCES public.message_failures (id),
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,
    locked_by VARCHAR(128),
    locked_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,

    CONSTRAINT fk_outbox_attempt FOREIGN KEY (attempt_id, aggregate_id)
        REFERENCES public.matching_attempts (attempt_id, trip_id),
    CONSTRAINT uq_outbox_aggregate_version UNIQUE (aggregate_id, aggregate_version),
    CONSTRAINT ck_outbox_event_type CHECK (
        event_type IN ('DriverReserved', 'MatchingFailed', 'DeadLetter')
    ),
    CONSTRAINT ck_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'SENT')),
    CONSTRAINT ck_outbox_attempts CHECK (publish_attempts >= 0),
    CONSTRAINT ck_outbox_version CHECK (version >= 0),
    CONSTRAINT ck_outbox_lock CHECK ((locked_by IS NULL) = (locked_until IS NULL)),
    CONSTRAINT ck_outbox_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT ck_outbox_routing CHECK (
        (event_type = 'DeadLetter' AND source_failure_id IS NOT NULL
            AND aggregate_id IS NULL AND aggregate_version IS NULL AND attempt_id IS NULL)
        OR
        (event_type IN ('DriverReserved', 'MatchingFailed') AND source_failure_id IS NULL
            AND aggregate_id IS NOT NULL AND aggregate_version IS NOT NULL
            AND aggregate_version >= 1 AND attempt_id IS NOT NULL
            AND correlation_id IS NOT NULL AND causation_id IS NOT NULL
            AND kafka_key = aggregate_id::TEXT AND topic = 'ds03.matching.events.v1')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_outbox_attempt_result
    ON public.outbox_events (attempt_id)
    WHERE event_type IN ('DriverReserved', 'MatchingFailed');

CREATE INDEX IF NOT EXISTS idx_outbox_due
    ON public.outbox_events (next_retry_at, created_at) WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_outbox_trip_pending
    ON public.outbox_events (aggregate_id, aggregate_version) WHERE status = 'PENDING';

-- updated_at tự động; version do repository tăng rõ ràng trong optimistic update.
CREATE OR REPLACE FUNCTION public.matching_set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE TRIGGER trg_matching_attempt_updated_at
    BEFORE UPDATE ON public.matching_attempts
    FOR EACH ROW EXECUTE FUNCTION public.matching_set_updated_at();

CREATE OR REPLACE TRIGGER trg_rpc_operation_updated_at
    BEFORE UPDATE ON public.rpc_operations
    FOR EACH ROW EXECUTE FUNCTION public.matching_set_updated_at();

CREATE OR REPLACE TRIGGER trg_message_failure_updated_at
    BEFORE UPDATE ON public.message_failures
    FOR EACH ROW EXECUTE FUNCTION public.matching_set_updated_at();

CREATE OR REPLACE TRIGGER trg_event_sequence_updated_at
    BEFORE UPDATE ON public.event_sequences
    FOR EACH ROW EXECUTE FUNCTION public.matching_set_updated_at();

CREATE OR REPLACE TRIGGER trg_outbox_event_updated_at
    BEFORE UPDATE ON public.outbox_events
    FOR EACH ROW EXECUTE FUNCTION public.matching_set_updated_at();

COMMENT ON TABLE public.matching_attempts IS
    'Workflow Matching bền theo attempt_id; không phải nguồn trạng thái chuyến/reservation.';
COMMENT ON TABLE public.rpc_operations IS
    'Persist trước gRPC. Retry cùng operation dùng nguyên key/request; UNKNOWN cần reconcile.';
COMMENT ON TABLE public.processed_events IS
    'Inbox dedup cùng transaction tiếp nhận workflow; chỉ commit Kafka offset sau DB commit.';
COMMENT ON TABLE public.cancellation_tombstones IS
    'Chặn request đến sau cancel; giữ ít nhất 7 ngày và không xóa khi cleanup còn dang dở.';
COMMENT ON TABLE public.message_failures IS
    'Message lỗi và lịch retry bền; đưa DLQ không đồng nghĩa xử lý nghiệp vụ thành công.';
COMMENT ON TABLE public.event_sequences IS
    'Sequence sự kiện do Matching phát theo trip; không so với version producer khác.';
COMMENT ON TABLE public.outbox_events IS
    'At-least-once: retry giữ event_id. Publisher phải giữ thứ tự version trong cùng trip.';

-- Repository vẫn cần: kiểm tra JSON Schema; giới hạn message; claim/version chống
-- worker cũ; đồng bộ cancellation; cấp event version và ghi outbox cùng transaction;
-- giữ đúng thứ tự publish; retry/compensation và cleanup theo retention.
-- SQL này chỉ tạo cấu trúc lưu trữ, không tự thực thi Saga hoặc gọi Kafka/gRPC.
COMMIT;
