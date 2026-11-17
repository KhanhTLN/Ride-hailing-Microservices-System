# Tài liệu API Xác thực (Authentication API)

Dịch vụ đảm nhiệm: **User Service**  
Base URL: `http://localhost:8082`  
Mã nguồn: [AuthController.java](file:///d:/Ride-hailing-Microservices-System/src/BE/user-service/src/main/java/com/threecats/userservice/auth/controller/AuthController.java)

---

## 1. Đăng ký khách hàng (Register Customer)

Đăng ký tài khoản cho khách hàng thông thường để đặt chuyến đi. Tài khoản được cấp vai trò (`role`) mặc định là `CUSTOMER`.

- **Phương thức**: `POST`
- **Đường dẫn**: `/api/auth/register/customer`
- **Quyền truy cập**: Public (Không cần Token)
- **Headers**:
  ```http
  Content-Type: application/json
  ```
- **File mẫu**: [auth-register-customer.json](samples/auth-register-customer.json)

### Request Body
```json
{
  "username": "customer_an",
  "firstName": "An",
  "lastName": "Nguyễn",
  "phone": "0911223344",
  "email": "customer.an@gmail.com",
  "password": "password123"
}
```

### Ràng buộc dữ liệu (Validation Rules)
| Trường | Kiểu dữ liệu | Bắt buộc | Ràng buộc |
| :--- | :--- | :--- | :--- |
| `username` | String | Có | Tối thiểu 6 ký tự |
| `password` | String | Có | Tối thiểu 6 ký tự |
| `firstName` | String | Có | Không được để trống |
| `lastName` | String | Có | Không được để trống |
| `phone` | String | Có | Định dạng số điện thoại 10-15 chữ số |
| `email` | String | Có | Định dạng email hợp lệ |

### Phản hồi thành công (`HTTP 200 OK`)
```json
{
  "success": true,
  "status": 200,
  "data": null,
  "message": "Register successfully!",
  "errorCode": null,
  "timestamp": "2026-11-17T10:00:00.000Z"
}
```

### Phản hồi thất bại
- **HTTP 400 Bad Request** (Lỗi vi phạm validation):
  ```json
  {
    "success": false,
    "status": 400,
    "data": null,
    "message": "Username must be at leat 6 characters",
    "errorCode": null,
    "timestamp": "2026-11-17T10:00:00.000Z"
  }
  ```
- **HTTP 409 Conflict** (Tài nguyên đã tồn tại):
  ```json
  {
    "status": 409,
    "message": "Tên đăng nhập đã tồn tại!"
  }
  ```

---

## 2. Đăng nhập hệ thống (Login)

Xác thực thông tin tài khoản người dùng (`username` + `password`). Khi thành công, hệ thống cấp:
1. **Access Token** (JWT) chứa trong Body phản hồi: Dùng để đính kèm vào Header `Authorization: Bearer <accessToken>` khi gọi các API cần xác thực (thời hạn 15 phút).
2. **Refresh Token** (JWT) chứa trong Header `Set-Cookie` (HttpOnly): Dùng để cấp lại Access Token mới khi hết hạn (thời hạn 7 ngày).

- **Phương thức**: `POST`
- **Đường dẫn**: `/api/auth/login`
- **Quyền truy cập**: Public
- **Headers**:
  ```http
  Content-Type: application/json
  ```
- **File mẫu**: [auth-login.json](samples/auth-login.json)

### Request Body
```json
{
  "username": "customer_an",
  "password": "password123"
}
```

### Phản hồi thành công (`HTTP 200 OK`)
**Headers trả về**:
```http
Set-Cookie: refreshToken=eyJhbGciOiJIUzI1NiIsInR5cCI...; Path=/; Max-Age=604800; HttpOnly; SameSite=Lax
```
**Body**:
```json
{
  "success": true,
  "status": 200,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  },
  "message": "Login successfully",
  "errorCode": null,
  "timestamp": "2026-11-17T10:00:00.000Z"
}
```

### Phản hồi thất bại
- **HTTP 401 Unauthorized** (Sai username hoặc password):
  ```json
  {
    "success": false,
    "status": 401,
    "data": null,
    "message": "Tên đăng nhập hoặc mật khẩu không chính xác",
    "errorCode": null,
    "timestamp": "2026-11-17T10:00:00.000Z"
  }
  ```
- **HTTP 403 Forbidden** (Tài khoản đang bị khóa / vô hiệu hóa):
  ```json
  {
    "status": 403,
    "message": "Tài khoản của bạn đã bị khóa hoặc vô hiệu hóa!"
  }
  ```
