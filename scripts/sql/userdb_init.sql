-- =====================================================================
-- DATABASE: rhds-user (User Service Database)
-- MÔ TẢ: Quản lý danh tính, tài khoản người dùng và xác thực (Auth)
-- =====================================================================

-- Xóa bảng cũ nếu cần thiết lập lại
DROP TABLE IF EXISTS public."user" CASCADE;

-- 1. TẠO BẢNG USER
CREATE TABLE public."user" (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    username VARCHAR(50) NOT NULL,
    first_name VARCHAR(50),
    last_name VARCHAR(50),
    phone VARCHAR(20) NOT NULL,
    email VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    status VARCHAR(20) NOT NULL DEFAULT 'INACTIVE',
    created_at TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ(6),
    deleted_at TIMESTAMPTZ(6),

    -- Khóa chính
    CONSTRAINT pk_user PRIMARY KEY (id),

    -- Ràng buộc giá trị hợp lệ (Enum Check)
    CONSTRAINT chk_user_role CHECK (role IN ('CUSTOMER', 'DRIVER')),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'BLOCKED'))
);

-- 2. TẠO CHỈ MỤC (INDEXES) & RÀNG BUỘC DUY NHẤT KÈM XÓA MỀM (SOFT DELETE)
-- Dùng Partial Unique Index: Chỉ bắt buộc duy nhất với những user CHƯA BỊ XÓA (deleted_at IS NULL).
-- Nhờ đó nếu user cũ bị xóa mềm, họ vẫn có thể dùng lại phone/email/username để đăng ký mới.
CREATE UNIQUE INDEX uk_user_username_active ON public."user" (username) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_user_phone_active ON public."user" (phone) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uk_user_email_active ON public."user" (email) WHERE deleted_at IS NULL;

-- Index tối ưu tốc độ cho câu lệnh SELECT có điều kiện "deleted_at IS NULL" của Hibernate
CREATE INDEX idx_user_deleted_at ON public."user" (deleted_at);

-- 3. CHÈN DỮ LIỆU MẪU BAN ĐẦU (SEED DATA)
-- Mật khẩu mặc định trong ví dụ đã mã hóa BCrypt cho chuỗi '123456'
-- (hoặc dùng pass giả lập $2a$10$abcdef... nếu test Spring Security)
INSERT INTO public."user" (
    id, username, first_name, last_name, phone, email, password, role, status, created_at, updated_at, deleted_at
)
VALUES 
(
    'de28a2a8-33b1-4155-bfa1-76136eaece19',
    'khanhpham',
    'Khanh',
    'Pham',
    '0914343201',
    'quyen@gmail.com',
    '$2a$10$7zB3cT6Yt3W9H.yMv3Lg2O8mK2qZp9eQyGjTqR8U.7eD7L8H2bS5i',
    'CUSTOMER',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    NULL,
    NULL
),
(
    'a1111111-2222-3333-4444-555555555555',
    'driver_nam',
    'Nam',
    'Nguyen',
    '0987654321',
    'driver.nam@gmail.com',
    '$2a$10$7zB3cT6Yt3W9H.yMv3Lg2O8mK2qZp9eQyGjTqR8U.7eD7L8H2bS5i',
    'DRIVER',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    NULL,
    NULL
);
