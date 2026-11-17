# Tài liệu API Tài xế (Driver Service API)

Dịch vụ đảm nhiệm: **Driver Service**  
Base URL: `http://localhost:8081`  
Mã nguồn: [DriverController.java](file:///d:/Ride-hailing-Microservices-System/src/BE/driver-service/src/main/java/com/threecats/driverservice/driver/controller/DriverController.java)

---

## 1. Đăng ký tài xế (Register Driver via gRPC Orchestration)

Đăng ký trọn gói tài xế mới vào hệ thống. API này thực hiện quy trình điều phối phân tán (Distributed Orchestration):
1. **Kiểm tra nghiệp vụ cục bộ tại Driver DB**: Kiểm tra trùng `identityCard` (CCCD), `licenseNumber` (GPLX), `vehiclePlate` (Biển số xe). Nếu đã tồn tại, trả về ngay lập tức lỗi `409 Conflict`.
2. **Gọi gRPC tạo tài khoản sang User Service**: Gọi hàm RPC `UserGrpcService/RegisterDriverUser` tới cổng `9090` để tạo tài khoản người dùng có vai trò `role = DRIVER`.
3. **Lưu hồ sơ tài xế vào Driver DB**: Lưu thông tin phương tiện, giấy phép, trạng thái duyệt (`PENDING`), trạng thái hoạt động (`OFFLINE`) với khóa ngoại `user_id` liên kết.
4. **Cơ chế bồi hoàn (Compensating Transaction)**: Nếu bước lưu DB của Driver Service gặp lỗi bất ngờ, Driver Service tự động gọi RPC `UserGrpcService/RollbackUser` để xóa người dùng vừa tạo ở User DB, đảm bảo tính nhất quán dữ liệu giữa 2 microservice.

- **Phương thức**: `POST`
- **Đường dẫn**: `/api/drivers/register`
- **Quyền truy cập**: Public (Không cần Token)
- **Headers**:
  ```http
  Content-Type: application/json
  ```
- **Files mẫu**:
  - Đăng ký xe máy: [driver-register-bike.json](samples/driver-register-bike.json)
  - Đăng ký ô tô 4 chỗ: [driver-register-car.json](samples/driver-register-car.json)
  - Thử nghiệm trùng biển số: [driver-register-duplicate-plate.json](samples/driver-register-duplicate-plate.json)

### Request Body (Ví dụ: Tài xế ô tô 4 chỗ)
```json
{
  "username": "driver_duc",
  "password": "password123",
  "firstName": "Đức",
  "lastName": "Trần Văn",
  "phone": "0934567891",
  "email": "driver.duc@gmail.com",
  "identityCard": "001202099991",
  "licenseNumber": "B2-55667791",
  "vehicleType": "CAR_4_SEATS",
  "vehiclePlate": "30E-999.91",
  "vehicleModel": "Toyota Vios 2024"
}
```

### Ràng buộc dữ liệu (Validation Rules)
| Trường | Kiểu dữ liệu | Bắt buộc | Ràng buộc / Giá trị cho phép |
| :--- | :--- | :--- | :--- |
| `username` | String | Có | Tối thiểu 6 ký tự |
| `password` | String | Có | Tối thiểu 6 ký tự |
| `firstName` | String | Có | Không được để trống |
| `lastName` | String | Có | Không được để trống |
| `phone` | String | Có | Định dạng số điện thoại 10-15 chữ số |
| `email` | String | Có | Định dạng email hợp lệ |
| `identityCard` | String | Có | Số CCCD/CMND không được để trống |
| `licenseNumber` | String | Có | Số GPLX không được để trống |
| `vehicleType` | Enum | Có | Các giá trị: `BIKE`, `CAR_4_SEATS`, `CAR_7_SEATS` |
| `vehiclePlate` | String | Có | Biển số xe không được để trống (Ví dụ: `30E-999.91`) |
| `vehicleModel` | String | Không | Model/dòng xe (Ví dụ: `Honda Wave Alpha`, `Toyota Vios`) |

### Phản hồi thành công (`HTTP 201 Created`)
```json
{
  "success": true,
  "status": 201,
  "data": {
    "userId": "a1111111-2222-3333-4444-555555555555",
    "username": "driver_duc",
    "fullName": "Trần Văn Đức",
    "phone": "0934567891",
    "email": "driver.duc@gmail.com",
    "identityCard": "001202099991",
    "licenseNumber": "B2-55667791",
    "approvalStatus": "PENDING",
    "vehicleType": "CAR_4_SEATS",
    "vehiclePlate": "30E-999.91",
    "vehicleModel": "Toyota Vios 2024",
    "activityStatus": "OFFLINE",
    "latitude": null,
    "longitude": null,
    "createdAt": "2026-11-17T10:00:00+07:00"
  },
  "message": null,
  "errorCode": null,
  "timestamp": "2026-11-17T10:00:00.000Z"
}
```

### Phản hồi thất bại
- **HTTP 409 Conflict** (Biển số xe, CCCD hoặc GPLX đã tồn tại ở Driver DB):
  ```json
  {
    "status": 409,
    "message": "Biển số xe đã được đăng ký trong hệ thống!"
  }
  ```
- **HTTP 409 Conflict** (Username/email/phone đã tồn tại ở User Service qua gRPC):
  ```json
  {
    "status": 409,
    "message": "Tên đăng nhập đã tồn tại trong hệ thống!"
  }
  ```
- **HTTP 400 Bad Request** (Sai format hoặc thiếu trường dữ liệu):
  ```json
  {
    "success": false,
    "status": 400,
    "data": null,
    "message": "Số căn cước công dân không được để trống",
    "errorCode": null,
    "timestamp": "2026-11-17T10:00:00.000Z"
  }
  ```

---

## 2. Lấy hồ sơ tài xế (Get Driver Profile)

Truy vấn hồ sơ chi tiết của tài xế dựa theo `userId`. Hệ thống sẽ kết hợp dữ liệu từ 2 nguồn:
- Thông tin phương tiện, trạng thái duyệt, trạng thái online, vị trí từ **Driver Service DB**.
- Thông tin cá nhân (họ tên, username, số điện thoại, email) từ **User Service thông qua gRPC**.

- **Phương thức**: `GET`
- **Đường dẫn**: `/api/drivers/{userId}`
- **Tham số đường dẫn (Path Variable)**:
  - `userId`: Mã UUID của tài xế (Ví dụ: `a1111111-2222-3333-4444-555555555555`).

### Phản hồi thành công (`HTTP 200 OK`)
```json
{
  "success": true,
  "status": 200,
  "data": {
    "userId": "a1111111-2222-3333-4444-555555555555",
    "username": "driver_nam",
    "fullName": "Lê Hoàng Nam",
    "phone": "0987654321",
    "email": "driver.nam@gmail.com",
    "identityCard": "001202088888",
    "licenseNumber": "B2-88888888",
    "approvalStatus": "APPROVED",
    "vehicleType": "CAR_4_SEATS",
    "vehiclePlate": "29A-888.88",
    "vehicleModel": "Hyundai Accent 2023",
    "activityStatus": "AVAILABLE",
    "latitude": 21.028511,
    "longitude": 105.854444,
    "createdAt": "2026-11-17T08:30:00+07:00"
  },
  "message": null,
  "errorCode": null,
  "timestamp": "2026-11-17T10:00:00.000Z"
}
```

### Phản hồi thất bại
- **HTTP 404 Not Found** (Không tìm thấy tài xế với userId chỉ định):
  ```json
  {
    "status": 404,
    "message": "Không tìm thấy thông tin tài xế với User ID: a1111111-2222-3333-4444-555555555555"
  }
  ```
