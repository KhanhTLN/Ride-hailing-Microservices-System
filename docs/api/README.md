# Tài liệu API Hệ thống Ride-Hailing Microservices (RHDS)

Hệ thống được thiết kế theo kiến trúc **Microservices** (chuẩn đồ án môn học DS03) với nguyên tắc **Database-per-service**, giao tiếp song song giữa **RESTful API** (cho Client) và **gRPC** (cho giao tiếp liên dịch vụ nội bộ tốc độ cao).

---

## 1. Bản đồ Dịch vụ & Cổng truy cập (Service Ports)

| Microservice | Giao thức | Host & Port | Mục đích |
| :--- | :--- | :--- | :--- |
| **User Service** | REST HTTP | `http://localhost:8082` | Đăng ký khách hàng, đăng nhập (JWT), quản lý hồ sơ người dùng |
| **User Service** | gRPC | `static://localhost:9090` | RPC nội bộ cho các service khác (Driver, Trip) tra cứu & tạo user |
| **Driver Service** | REST HTTP | `http://localhost:8081` | Đăng ký tài xế, quản lý hồ sơ xe, trạng thái hoạt động |

---

## 2. Cấu trúc thư mục tài liệu API

```text
docs/api/
├── README.md                           # Trang tổng quan & mục lục API
├── auth-api.md                         # Chi tiết API Xác thực (/api/auth)
├── user-api.md                         # Chi tiết API Người dùng (/api/user)
├── driver-api.md                       # Chi tiết API Tài xế (/api/drivers)
├── grpc-internal-api.md                # Hợp đồng Protobuf gRPC nội bộ (Port 9090)
├── postman/
│   ├── Ride_Hailing_All_Services.postman_collection.json  # File Collection nhập vào Postman để test
│   └── Ride_Hailing_Local.postman_environment.json       # File Environment Postman cấu hình biến local
└── samples/                            # Các file JSON Request Payload mẫu
    ├── auth-register-customer.json
    ├── auth-login.json
    ├── user-create.json
    ├── user-update-profile.json
    ├── driver-register-bike.json
    ├── driver-register-car.json
    └── driver-register-duplicate-plate.json
```

---

## 3. Quy chuẩn Định dạng Phản hồi (Standard Response Format)

Toàn bộ các REST API đều chuẩn hóa đầu ra theo cấu trúc `ApiResponse<T>`:

### 3.1. Thành công (`HTTP 200 OK` hoặc `HTTP 201 Created`)
```json
{
  "success": true,
  "status": 200,
  "data": { ... },
  "message": "Thao tác thành công",
  "errorCode": null,
  "timestamp": "2026-11-17T10:00:00.000Z"
}
```

### 3.2. Lỗi nghiệp vụ hoặc hệ thống (`HTTP 400`, `401`, `403`, `404`, `409`, `500`)
```json
{
  "success": false,
  "status": 409,
  "data": null,
  "message": "Biển số xe đã được đăng ký trong hệ thống!",
  "errorCode": null,
  "timestamp": "2026-11-17T10:00:00.000Z"
}
```

---

## 4. Danh mục API tóm tắt

### 4.1. Auth Service (`/api/auth` - Port 8082)
- [Xem chi tiết tại auth-api.md](auth-api.md)
- `POST /api/auth/register/customer`: Đăng ký tài khoản khách hàng thông thường (role `CUSTOMER`).
- `POST /api/auth/login`: Đăng nhập bằng `username` + `password`, nhận `accessToken` trong body và `refreshToken` trong HttpOnly Cookie.

### 4.2. User Service (`/api/user` - Port 8082)
- [Xem chi tiết tại user-api.md](user-api.md)
- `GET /api/user`: Lấy danh sách toàn bộ người dùng trong hệ thống.
- `GET /api/user/{id}`: Tra cứu chi tiết thông tin người dùng theo UUID.
- `POST /api/user/create`: Tạo người dùng mới trực tiếp (Internal / Admin).
- `PATCH /api/user/{id}`: Cập nhật thông tin profile người dùng.
- `DELETE /api/user/{id}`: Xóa tài khoản người dùng theo UUID.

### 4.3. Driver Service (`/api/drivers` - Port 8081)
- [Xem chi tiết tại driver-api.md](driver-api.md)
- `POST /api/drivers/register`: Đăng ký tài xế tích hợp gRPC (tạo User role `DRIVER` tại User Service -> lưu Driver tại Driver Service; tự động Rollback bồi hoàn nếu gặp lỗi).
- `GET /api/drivers/{userId}`: Tra cứu hồ sơ tài xế (kết hợp dữ liệu xe/bằng lái từ Driver DB và thông tin cá nhân từ User Service qua gRPC).

### 4.4. gRPC Internal Service (Port 9090)
- [Xem chi tiết tại grpc-internal-api.md](grpc-internal-api.md)
- RPC `RegisterDriverUser`: Ủy thác tạo tài khoản tài xế.
- RPC `GetUserById`: Tra cứu thông tin người dùng theo UUID qua kết nối nhị phân tốc độ cao.
- RPC `RollbackUser`: Bồi hoàn (Compensating Transaction) khi lưu Driver DB thất bại.

---

## 5. Hướng dẫn Kiểm thử nhanh bằng Postman

1. Mở ứng dụng **Postman**.
2. Nhấn nút **Import** ở góc trên bên trái.
3. Chọn file [Ride_Hailing_All_Services.postman_collection.json](postman/Ride_Hailing_All_Services.postman_collection.json).
4. Tiếp tục Import file môi trường [Ride_Hailing_Local.postman_environment.json](postman/Ride_Hailing_Local.postman_environment.json).
5. Chọn Environment là **Ride Hailing - Local**.
6. Khởi động 2 service (`user-service` và `driver-service`) và bắt đầu gửi request kiểm thử theo thứ tự đã đánh số sẵn trong Collection.
