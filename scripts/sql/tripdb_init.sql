-- =====================================================================
-- DATABASE: rhds_trip (Trip Service Database)
-- MÔ TẢ: Quản lý vòng đời chuyến đi (Saga), tác vụ bất đồng bộ (Operations),
--       hộp thư sự kiện phân tán (Outbox) và chống lặp (Idempotency).
-- =====================================================================

-- 1. BẢNG CHUYẾN ĐI (TRIP - AGGREGATE ROOT)
CREATE TABLE IF NOT EXISTS public.trip (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    passenger_id UUID NOT NULL,
    driver_id UUID,
    driver_user_id UUID,
    quote_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'SEARCHING',
    current_attempt_id UUID NOT NULL,
    search_budget_seconds INT NOT NULL DEFAULT 120,
    
    -- Tọa độ và địa chỉ đón / trả
    pickup_latitude DOUBLE PRECISION NOT NULL,
    pickup_longitude DOUBLE PRECISION NOT NULL,
    pickup_address VARCHAR(255),
    dropoff_latitude DOUBLE PRECISION NOT NULL,
    dropoff_longitude DOUBLE PRECISION NOT NULL,
    dropoff_address VARCHAR(255),

    -- Phương tiện và cước phí
    vehicle_type VARCHAR(20) NOT NULL,
    fare_amount NUMERIC(12, 2) NOT NULL,
    fare_currency VARCHAR(10) NOT NULL DEFAULT 'VND',

    -- Thanh toán mô phỏng (Simulated Payment)
    payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_id UUID,
    attempt_number INT NOT NULL DEFAULT 1,

    -- Optimistic Concurrency Control (OCC)
    version BIGINT NOT NULL DEFAULT 0,

    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6),

    CONSTRAINT pk_trip PRIMARY KEY (id),
    CONSTRAINT uk_trip_quote UNIQUE (quote_id),
    CONSTRAINT chk_trip_status CHECK (
        status IN ('SEARCHING', 'DRIVER_OFFERED', 'ACCEPTED', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_DRIVER')
    ),
    CONSTRAINT chk_trip_vehicle_type CHECK (
        vehicle_type IN ('MOTORBIKE', 'CAR')
    ),
    CONSTRAINT chk_trip_payment_status CHECK (
        payment_status IN ('PENDING', 'SUCCEEDED', 'FAILED')
    )
);

-- Ràng buộc Concurrency: Một hành khách tại một thời điểm chỉ được phép có tối đa 1 cuốc xe đang hoạt động
CREATE UNIQUE INDEX IF NOT EXISTS uk_passenger_active_trip 
ON public.trip (passenger_id) 
WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'NO_DRIVER');

CREATE INDEX IF NOT EXISTS idx_trip_passenger_created ON public.trip (passenger_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_trip_driver_status ON public.trip (driver_id, status);
CREATE INDEX IF NOT EXISTS idx_trip_attempt_id ON public.trip (current_attempt_id);


-- 2. BẢNG TÁC VỤ BẤT ĐỒNG BỘ (TRIP OPERATIONS - PHỤC VỤ HTTP 202 & POLLING)
CREATE TABLE IF NOT EXISTS public.trip_operation (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    trip_id UUID NOT NULL,
    operation_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    idempotency_key VARCHAR(128),
    error_code VARCHAR(50),
    error_message TEXT,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6),

    CONSTRAINT pk_trip_operation PRIMARY KEY (id),
    CONSTRAINT fk_operation_trip FOREIGN KEY (trip_id) REFERENCES public.trip (id) ON DELETE CASCADE,
    CONSTRAINT chk_operation_type CHECK (
        operation_type IN ('ACCEPT', 'REJECT', 'CANCEL', 'COMPLETE', 'PAYMENT_RETRY')
    ),
    CONSTRAINT chk_operation_status CHECK (
        status IN ('PENDING', 'SUCCEEDED', 'FAILED')
    )
);

CREATE INDEX IF NOT EXISTS idx_operation_trip_status ON public.trip_operation (trip_id, status);
CREATE INDEX IF NOT EXISTS idx_operation_idempotency ON public.trip_operation (idempotency_key);


-- 3. BẢNG HỘP THƯ SỰ KIỆN ĐI (OUTBOX EVENTS - TRANSACTIONAL OUTBOX PATTERN)
CREATE TABLE IF NOT EXISTS public.outbox_event (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL, -- chính là trip_id (Kafka record key)
    aggregate_version INT NOT NULL DEFAULT 1,
    topic VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ(6),

    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
    CONSTRAINT chk_outbox_status CHECK (
        status IN ('PENDING', 'SENT', 'FAILED')
    )
);

-- Index tối ưu tốc độ cho Outbox Poller quét các tin nhắn đang chờ gửi
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON public.outbox_event (created_at ASC) WHERE status = 'PENDING';


-- 4. BẢNG CHỐNG TRÙNG LẶP YÊU CẦU (IDEMPOTENCY RECORDS)
CREATE TABLE IF NOT EXISTS public.idempotency_record (
    key VARCHAR(255) NOT NULL,
    caller_id VARCHAR(100),
    resource_id UUID,
    response_status INT NOT NULL,
    response_body JSONB,
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ(6) NOT NULL,

    CONSTRAINT pk_idempotency_record PRIMARY KEY (key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON public.idempotency_record (expires_at);

-- =====================================================================
-- DỮ LIỆU MẪU BAN ĐẦU (SEED DATA KIỂM THỬ)
-- =====================================================================
-- Tạo 1 cuốc xe mẫu đã hoàn thành để xem trực quan trên pgAdmin
INSERT INTO public.trip (
    id, passenger_id, driver_id, driver_user_id, quote_id, status,
    current_attempt_id, search_budget_seconds,
    pickup_latitude, pickup_longitude, pickup_address,
    dropoff_latitude, dropoff_longitude, dropoff_address,
    vehicle_type, fare_amount, fare_currency,
    payment_status, attempt_number, version, created_at, updated_at
) VALUES (
    'e5b7b9d3-8b77-4a1c-9e2d-3d8e5f1a2b3c',
    'de28a2a8-33b1-4155-bfa1-76136eaece19',
    'a1111111-2222-3333-4444-555555555555',
    'de28a2a8-33b1-4155-bfa1-76136eaece19',
    'f47ac10b-58cc-4372-a567-0e02b2c3d479',
    'COMPLETED',
    'b2222222-3333-4444-5555-666666666666',
    120,
    21.028511, 105.854444, 'Hồ Hoàn Kiếm, Hà Nội',
    21.036889, 105.834611, 'Lăng Bác, Hà Nội',
    'MOTORBIKE', 25000.00, 'VND',
    'SUCCEEDED', 1, 5,
    CURRENT_TIMESTAMP - INTERVAL '1 hour',
    CURRENT_TIMESTAMP - INTERVAL '30 minutes'
) ON CONFLICT (id) DO NOTHING;
