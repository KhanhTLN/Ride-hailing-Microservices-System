# Tài liệu API Người dùng (User Management API)

Dịch vụ đảm nhiệm: **User Service**  
Base URL: `http://localhost:8082`  
Mã nguồn: [UserController.java](file:///d:/Ride-hailing-Microservices-System/src/BE/user-service/src/main/java/com/threecats/userservice/user/controller/UserController.java)

---

## 1. Lấy danh sách toàn bộ người dùng (Get All Users)

Lấy danh sách tất cả các tài khoản người dùng đang có trong hệ thống (không bao gồm các tài khoản đã bị soft-delete).

- **Phương thức**: `GET`
- **Đường dẫn**: `/api/user`
- **Headers**:
  ```http
  Authorization: Bearer <accessToken>
  ```

### Phản hồi thành công (`HTTP 200 OK`)
```json
{
  "success": true,
  "status": 200,
  "data": [
    {
      "id": "de28a2a8-33b1-4155-bfa1-76136eaece19",
      "username": "customer_an",
      "firstName": "An",
      "lastName": "Nguyễn",
      "email": "customer.an@gmail.com",
      "phone": "0911223344",
      "role": "CUSTOMER",
      "status": "ACTIVE",
      "createdAt": "2026-11-17T08:00:00+07:00",
      "updatedAt": "2026-11-17T08:00:00+07:00",
      "deletedAt": null
    },
    {
      "id": "a1111111-2222-3333-4444-555555555555",
      "username": "driver_nam",
      "firstName": "Nam",
      "lastName": "Lê Hoàng",
      "email": "driver.nam@gmail.com",
      "phone": "0987654321",
      "role": "DRIVER",
      "status": "ACTIVE",
      "createdAt": "2026-11-17T08:30:00+07:00",
      "updatedAt": "2026-11-17T08:30:00+07:00",
      "deletedAt": null
    }
  ],
  "message": null,
  "errorCode": null,
  "timestamp": "2026-11-17T10:00:00.000Z"
}
```

---

## 2. Tra cứu người dùng theo ID (Get User By ID)

Tra cứu hồ sơ chi tiết của một người dùng theo UUID định danh.

- **Phương thức**: `GET`
- **Đường dẫn**: `/api/user/{id}`
- **Tham số đường dẫn (Path Variable)**:
  - `id`: Mã UUID của người dùng (Ví dụ: `de28a2a8-33b1-4155-bfa1-76136eaece19`).
- **Headers**:
  ```http
  Authorization: Bearer <accessToken>
  ```

### Phản hồi thành công (`HTTP 200 OK`)
```json
{
  "success": true,
  "status": 200,
  "data": {
    "id": "de28a2a8-33b1-4155-bfa1-76136eaece19",
    "username": "customer_an",
    "firstName": "An",
    "lastName": "Nguyễn",
    "email": "customer.an@gmail.com",
    "phone": "0911223344",
    "role": "CUSTOMER",
    "status": "ACTIVE",
    "createdAt": "2026-11-17T08:00:00+07:00",
    "updatedAt": "2026-11-17T08:00:00+07:00",
    "deletedAt": null
  },
  "message": null,
  "errorCode": null,
  "timestamp": "2026-11-17T10:00:00.000Z"
}
```

### Phản hồi thất bại
- **HTTP 404 Not Found** (Không tìm thấy người dùng):
  ```json
  {
    "status": 404,
    "message": "Không tìm thấy người dùng với ID: de28a2a8-33b1-4155-bfa1-76136eaece19"
  }
  ```

---

## 3. Tạo người dùng trực tiếp (Create User)

Tạo người dùng mới trực tiếp bởi quản trị viên hoặc luồng tích hợp nội bộ.

- **Phương thức**: `POST`
- **Đường dẫn**: `/api/user/create`
- **Headers**:
  ```http
  Content-Type: application/json
  Authorization: Bearer <accessToken>
  ```
- **File mẫu**: [user-create.json](samples/user-create.json)

### Request Body
```json
{
  "firstName": "Bình",
  "lastName": "Phạm",
  "phone": "0988776655",
  "email": "binh.pham@gmail.com",
  "password": "password123"
}
```

### Phản hồi thành công (`HTTP 200 OK`)
```json
{
  "success": true,
  "status": 200,
  "data": {
    "id": "c3333333-4444-5555-6666-777777777777",
    "username": null,
    "firstName": "Bình",
    "lastName": "Phạm",
    "email": "binh.pham@gmail.com",
    "phone": "0988776655",
    "role": "CUSTOMER",
    "status": "ACTIVE",
    "createdAt": "2026-11-17T10:05:00+07:00",
    "updatedAt": "2026-11-17T10:05:00+07:00",
    "deletedAt": null
  },
  "message": null,
  "errorCode": null,
  "timestamp": "2026-11-17T10:05:00.000Z"
}
```

---

## 4. Cập nhật hồ sơ người dùng (Update User Profile)

Cập nhật thông tin profile của người dùng (họ, tên, số điện thoại, email, username).

- **Phương thức**: `PATCH`
- **Đường dẫn**: `/api/user/{id}`
- **Tham số đường dẫn**: `id` (UUID)
- **Headers**:
  ```http
  Content-Type: application/json
  Authorization: Bearer <accessToken>
  ```
- **File mẫu**: [user-update-profile.json](samples/user-update-profile.json)

### Request Body
```json
{
  "username": "customer_an_new",
  "firstName": "An Khang",
  "lastName": "Nguyễn",
  "phone": "0911223399",
  "email": "an.new@gmail.com"
}
```

### Phản hồi thành công (`HTTP 200 OK`)
```json
{
  "success": true,
  "status": 200,
  "data": {
    "id": "de28a2a8-33b1-4155-bfa1-76136eaece19",
    "username": "customer_an_new",
    "firstName": "An Khang",
    "lastName": "Nguyễn",
    "email": "an.new@gmail.com",
    "phone": "0911223399",
    "role": "CUSTOMER",
    "status": "ACTIVE",
    "createdAt": "2026-11-17T08:00:00+07:00",
    "updatedAt": "2026-11-17T10:10:00+07:00",
    "deletedAt": null
  },
  "message": null,
  "errorCode": null,
  "timestamp": "2026-11-17T10:10:00.000Z"
}
```

---

## 5. Xóa người dùng (Delete User)

Thực hiện xóa người dùng theo ID (đánh dấu `deletedAt` hoặc xóa bản ghi theo chính sách hệ thống).

- **Phương thức**: `DELETE`
- **Đường dẫn**: `/api/user/{id}`
- **Tham số đường dẫn**: `id` (UUID)
- **Headers**:
  ```http
  Authorization: Bearer <accessToken>
  ```

### Phản hồi thành công (`HTTP 200 OK`)
```json
{
  "success": true,
  "status": 200,
  "data": null,
  "message": "Delete user de28a2a8-33b1-4155-bfa1-76136eaece19 successfully",
  "errorCode": null,
  "timestamp": "2026-11-17T10:15:00.000Z"
}
```
