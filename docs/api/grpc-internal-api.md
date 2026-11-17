# Tài liệu Hợp đồng gRPC Nội bộ (Internal gRPC Contract)

Hợp đồng gRPC được định nghĩa tập trung tại module `common` để các microservices tái sử dụng code generated từ `protobuf-maven-plugin`.

- **File Proto**: [user_service.proto](file:///d:/Ride-hailing-Microservices-System/src/BE/common/src/main/proto/user_service.proto)
- **gRPC Server**: `User Service` (Cổng: `9090`) - Triển khai tại [UserGrpcServiceImpl.java](file:///d:/Ride-hailing-Microservices-System/src/BE/user-service/src/main/java/com/threecats/userservice/grpc/UserGrpcServiceImpl.java)
- **gRPC Client**: `Driver Service` - Triển khai tại [UserGrpcClient.java](file:///d:/Ride-hailing-Microservices-System/src/BE/driver-service/src/main/java/com/threecats/driverservice/grpc/UserGrpcClient.java)

---

## 1. Định nghĩa Protobuf (`user_service.proto`)

```protobuf
syntax = "proto3";

package com.threecats.common.grpc;

option java_multiple_files = true;
option java_package = "com.threecats.common.grpc";
option java_outer_classname = "UserServiceProto";

// Service gRPC định danh người dùng phục vụ liên lạc nội bộ giữa các Microservices
service UserGrpcService {
    // 1. Tạo tài khoản người dùng cho tài xế với role DRIVER
    rpc RegisterDriverUser (RegisterDriverUserRequest) returns (UserProtoResponse);

    // 2. Tra cứu thông tin người dùng theo UUID
    rpc GetUserById (GetUserByIdRequest) returns (UserProtoResponse);

    // 3. Rollback người dùng nếu phía driver-service lưu DB thất bại (Compensating action)
    rpc RollbackUser (RollbackUserRequest) returns (RollbackUserResponse);
}

message RegisterDriverUserRequest {
    string username = 1;
    string password = 2;
    string firstName = 3;
    string lastName = 4;
    string phone = 5;
    string email = 6;
}

message GetUserByIdRequest {
    string userId = 1;
}

message RollbackUserRequest {
    string userId = 1;
}

message RollbackUserResponse {
    bool success = 1;
    string message = 2;
}

message UserProtoResponse {
    string id = 1;
    string username = 2;
    string firstName = 3;
    string lastName = 4;
    string phone = 5;
    string email = 6;
    string role = 7;
    string status = 8;
}
```

---

## 2. Chi tiết các RPC Methods

### 2.1. `RegisterDriverUser`
- **Mục đích**: Được gọi bởi `Driver Service` khi tiếp nhận yêu cầu đăng ký tài xế mới. `User Service` tạo một bản ghi tài khoản tại bảng `users` với `role = 'DRIVER'` và mã hóa mật khẩu qua BCrypt.
- **Request**:
  - `username` (string): Tên đăng nhập.
  - `password` (string): Mật khẩu thô (sẽ được BCrypt hash tại User Service).
  - `firstName` (string): Tên.
  - `lastName` (string): Họ đệm.
  - `phone` (string): Số điện thoại.
  - `email` (string): Email.
- **Response**: `UserProtoResponse` chứa `id` (UUID), `role`, `status`.
- **Mã lỗi gRPC chuẩn**:
  - `Status.ALREADY_EXISTS`: Trùng `username`, `email` hoặc `phone`.
  - `Status.INVALID_ARGUMENT`: Thiếu trường bắt buộc.

### 2.2. `GetUserById`
- **Mục đích**: Tra cứu thông tin cơ bản của người dùng (họ tên, email, SĐT) dựa trên `userId`. Phục vụ việc kết hợp (composite) dữ liệu khi xem hồ sơ tài xế, tài xế nhận chuyến đi, hoặc khách hàng xem thông tin tài xế.
- **Request**: `GetUserByIdRequest` chứa `userId` (chuỗi UUID).
- **Response**: `UserProtoResponse`.
- **Mã lỗi gRPC chuẩn**:
  - `Status.NOT_FOUND`: Người dùng không tồn tại hoặc đã bị xóa.
  - `Status.INVALID_ARGUMENT`: Chuỗi UUID không đúng định dạng.

### 2.3. `RollbackUser` (Compensating Transaction)
- **Mục đích**: Hành động bồi hoàn trong kiến trúc giao dịch phân tán Saga. Khi Driver Service tạo user thành công qua gRPC nhưng bước lưu thông tin tài xế vào database của mình bị lỗi (sập nguồn, lỗi mạng, vi phạm ràng buộc), Driver Service sẽ gọi RPC này để xóa người dùng vừa tạo, tránh sinh ra tài khoản mồ côi (orphan user).
- **Request**: `RollbackUserRequest` chứa `userId`.
- **Response**: `RollbackUserResponse` (`success`: true/false, `message`).

---

## 3. Bản đồ chuyển đổi mã lỗi gRPC sang HTTP REST

Lớp adapter `UserGrpcClient` tại `driver-service` tự động bắt `StatusRuntimeException` và chuyển đổi thành Exception RESTful:

| Mã trạng thái gRPC | Exception tại Driver Service | HTTP Status trả về cho Client |
| :--- | :--- | :--- |
| `Status.Code.ALREADY_EXISTS` | `ResourceAlreadyExistException` | **409 Conflict** |
| `Status.Code.NOT_FOUND` | `ResourceNotFoundException` | **404 Not Found** |
| `Status.Code.INVALID_ARGUMENT` | `InvalidInputException` | **400 Bad Request** |
| `Status.Code.UNAVAILABLE` | `RuntimeException` | **500 Internal Server Error** |
