-- =====================================================================
-- DATABASE: rhds-driver (Driver Service Database)
-- MÔ TẢ: Quản lý hồ sơ đối tác tài xế, phương tiện và vị trí hoạt động
-- =====================================================================

-- Xóa bảng cũ nếu cần thiết lập lại
DROP TABLE IF EXISTS public.driver CASCADE;

-- 1. TẠO BẢNG DRIVER
CREATE TABLE public.driver (
    -- user_id đóng vai trò là Primary Key (Shared ID ánh xạ sang user-service)
    user_id UUID NOT NULL,

    -- 1. Giấy tờ pháp lý & Xét duyệt hồ sơ (Admin Approval)
    identity_card VARCHAR(20) NOT NULL,
    license_number VARCHAR(50) NOT NULL,
    approval_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- 2. Thông tin xe & phân loại phương tiện (Vehicle Info)
    vehicle_type VARCHAR(20) NOT NULL DEFAULT 'BIKE',
    vehicle_plate VARCHAR(20) NOT NULL,
    vehicle_model VARCHAR(50),

    -- 3. Trạng thái vận hành thực tế (Driver Presence / Operational Availability)
    activity_status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',

    -- 4. Tọa độ vị trí mô phỏng (Simulated Location cho Matching Service)
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    last_location_updated_at TIMESTAMPTZ(6),

    -- 5. Quản lý tranh chấp đồng thời (Optimistic Concurrency Control)
    version BIGINT NOT NULL DEFAULT 0,

    -- 6. Thời gian tạo & cập nhật
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6),

    -- Ràng buộc khóa chính
    CONSTRAINT pk_driver PRIMARY KEY (user_id),

    -- Ràng buộc duy nhất: Giấy tờ & Biển số xe không được trùng lặp
    CONSTRAINT uk_driver_license_number UNIQUE (license_number),
    CONSTRAINT uk_driver_identity_card UNIQUE (identity_card),
    CONSTRAINT uk_driver_vehicle_plate UNIQUE (vehicle_plate),

    -- Ràng buộc Enum Check
    CONSTRAINT chk_driver_approval_status 
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_driver_vehicle_type 
        CHECK (vehicle_type IN ('BIKE', 'CAR_4_SEATS', 'CAR_7_SEATS')),
    CONSTRAINT chk_driver_activity_status 
        CHECK (activity_status IN ('OFFLINE', 'AVAILABLE', 'BUSY'))
);

-- 2. TẠO CHỈ MỤC (INDEXES) TỐI ƯU HIỆU NĂNG CHO MATCHING SERVICE
-- Tìm kiếm các tài xế: ĐÃ DUYỆT (APPROVED) + ĐANG BẬT APP SẴN SÀNG (AVAILABLE) + THEO LOẠI XE
CREATE INDEX idx_driver_matching_available 
ON public.driver (vehicle_type, activity_status) 
WHERE approval_status = 'APPROVED';

-- Index tìm nhanh theo biển số xe hoặc bằng lái
CREATE INDEX idx_driver_license_search ON public.driver (license_number);

-- 3. CHÈN DỮ LIỆU MẪU BAN ĐẦU (SEED DATA)
-- Chú ý: user_id ở đây khớp với tài khoản role DRIVER trong userdb ('a1111111-2222-3333-4444-555555555555')
INSERT INTO public.driver (
    user_id,
    identity_card,
    license_number,
    approval_status,
    vehicle_type,
    vehicle_plate,
    vehicle_model,
    activity_status,
    latitude,
    longitude,
    last_location_updated_at,
    version,
    created_at,
    updated_at
)
VALUES (
    'a1111111-2222-3333-4444-555555555555',
    '001201012345',
    'B2-99887766',
    'APPROVED',
    'CAR_4_SEATS',
    '29A-888.88',
    'Toyota Vios 2024',
    'AVAILABLE',
    21.028511,       -- Tọa độ Hà Nội mô phỏng
    105.854167,
    CURRENT_TIMESTAMP,
    0,
    CURRENT_TIMESTAMP,
    NULL
);
