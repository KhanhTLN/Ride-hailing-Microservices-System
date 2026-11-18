-- Run with psql -v ON_ERROR_STOP=1 against the dedicated Driver database.
BEGIN;

CREATE TABLE IF NOT EXISTS public.driver (
    user_id UUID PRIMARY KEY,
    identity_card VARCHAR(20) UNIQUE,
    license_number VARCHAR(50) NOT NULL UNIQUE,
    approval_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    vehicle_type VARCHAR(20) DEFAULT 'BIKE',
    vehicle_plate VARCHAR(20) UNIQUE,
    vehicle_model VARCHAR(50),
    activity_status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE'
        CHECK (activity_status IN ('OFFLINE', 'AVAILABLE', 'BUSY')),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    last_location_updated_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
);

-- Keep legacy profile fields available to the existing API.
ALTER TABLE public.driver
    ADD COLUMN IF NOT EXISTS driver_id UUID,
    ADD COLUMN IF NOT EXISTS accepting_trips BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS current_session_id UUID,
    ADD COLUMN IF NOT EXISTS current_sequence_number BIGINT,
    ADD COLUMN IF NOT EXISTS last_location_recorded_at TIMESTAMPTZ,
    ALTER COLUMN identity_card DROP NOT NULL,
    ALTER COLUMN vehicle_type DROP NOT NULL,
    ALTER COLUMN vehicle_plate DROP NOT NULL,
    DROP CONSTRAINT IF EXISTS chk_driver_approval_status,
    DROP CONSTRAINT IF EXISTS chk_driver_vehicle_type;

ALTER TABLE public.driver
    ADD CONSTRAINT chk_driver_approval_status
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'SUSPENDED', 'REJECTED')),
    ADD CONSTRAINT chk_driver_vehicle_type
        CHECK (vehicle_type IN ('BIKE', 'CAR_4_SEATS', 'CAR_7_SEATS'));

UPDATE public.driver SET driver_id = gen_random_uuid() WHERE driver_id IS NULL;
ALTER TABLE public.driver ALTER COLUMN driver_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_driver_driver_id ON public.driver (driver_id);

CREATE TABLE IF NOT EXISTS public.vehicle (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id UUID NOT NULL REFERENCES public.driver (driver_id),
    plate_number VARCHAR(20) NOT NULL CHECK (btrim(plate_number) <> ''),
    vehicle_type VARCHAR(20) NOT NULL CHECK (vehicle_type IN ('MOTORBIKE', 'CAR')),
    brand VARCHAR(80),
    model VARCHAR(80),
    color VARCHAR(40),
    seat_count INTEGER,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_vehicle_normalized_plate
    ON public.vehicle (upper(btrim(plate_number)));
CREATE UNIQUE INDEX IF NOT EXISTS uk_vehicle_active_driver
    ON public.vehicle (driver_id) WHERE is_active;

-- Preserve any vehicle already migrated and fail on conflicting normalized plates.
INSERT INTO public.vehicle (driver_id, plate_number, vehicle_type, model, seat_count)
SELECT d.driver_id, upper(btrim(d.vehicle_plate)),
       CASE WHEN d.vehicle_type = 'BIKE' THEN 'MOTORBIKE' ELSE 'CAR' END,
       d.vehicle_model,
       CASE d.vehicle_type WHEN 'CAR_4_SEATS' THEN 4 WHEN 'CAR_7_SEATS' THEN 7 END
FROM public.driver d
WHERE btrim(d.vehicle_plate) <> ''
  AND d.vehicle_type IN ('BIKE', 'CAR_4_SEATS', 'CAR_7_SEATS')
  AND NOT EXISTS (
      SELECT 1 FROM public.vehicle v
      WHERE v.driver_id = d.driver_id
        AND (v.is_active OR upper(btrim(v.plate_number)) = upper(btrim(d.vehicle_plate)))
  );

CREATE TABLE IF NOT EXISTS public.driver_reservation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id UUID NOT NULL REFERENCES public.driver (driver_id),
    trip_id UUID NOT NULL,
    owner_service TEXT NOT NULL CHECK (btrim(owner_service) <> ''),
    status VARCHAR(20) NOT NULL CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_driver_reservation_active_driver
    ON public.driver_reservation (driver_id) WHERE status IN ('HELD', 'CONFIRMED');
CREATE UNIQUE INDEX IF NOT EXISTS uk_driver_reservation_active_trip
    ON public.driver_reservation (trip_id) WHERE status IN ('HELD', 'CONFIRMED');
CREATE INDEX IF NOT EXISTS idx_driver_reservation_held_expiry
    ON public.driver_reservation (expires_at) WHERE status = 'HELD';

CREATE TABLE IF NOT EXISTS public.driver_idempotency (
    caller TEXT NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '168 hours'),
    PRIMARY KEY (caller, operation, idempotency_key),
    CHECK (expires_at >= created_at + INTERVAL '168 hours')
);

CREATE INDEX IF NOT EXISTS idx_driver_idempotency_expiry
    ON public.driver_idempotency (expires_at);

COMMIT;
