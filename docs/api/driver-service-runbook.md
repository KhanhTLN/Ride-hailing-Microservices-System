# Chạy và kiểm tra Driver Service

Driver chạy REST trên cổng `8081` và gRPC trên cổng `9091`. Dùng JDK 25 và PostgreSQL dành riêng cho Driver Service. Với Supabase, cấu hình JDBC theo thông tin kết nối của Driver DB và bật SSL. Schema tại `scripts/sql/driver/schema.sql`.

## Khởi động

Đặt các biến trong `.env.example` vào môi trường của tiến trình. `JWT_SECRET` dùng chung với User Service hiện tại để xác thực các API REST. `USER_GRPC_SERVER_ADDRESS` mặc định là `static://localhost:9090` cho đăng ký và tra cứu legacy.

Để gọi gRPC, cấu hình `DRIVER_GRPC_TOKEN_ISSUER` và `DRIVER_GRPC_TOKEN_PUBLIC_KEY`. Khóa công khai dùng X.509 DER mã hóa Base64. Token nội bộ dùng RS256, audience `driver-service`, thời hạn tối đa 5 phút và một trong hai cặp subject/scope.

| Subject | Scope |
|---|---|
| `matching-service` | `driver:match` |
| `trip-service` | `driver:trip` |

Mỗi RPC gửi metadata `authorization: Bearer <service-token>` và `x-correlation-id` dạng UUID. Khi triển khai qua mạng, cấu hình TLS cho gRPC; kiểm thử local dùng loopback.

```powershell
cd src/BE
.\mvnw.cmd -pl driver-service -am verify
java -jar driver-service/target/driver-service-0.0.1-SNAPSHOT.jar
```

Nếu Maven Wrapper chưa cài được Maven, chạy lệnh Maven tương đương với `mvn -pl driver-service -am verify`.

```powershell
curl.exe -i http://localhost:8081/internal/driver/health/live
curl.exe -i http://localhost:8081/internal/driver/health/ready
```

## Heartbeat qua Redis

Đặt `DRIVER_REDIS_ENABLED=true` và `DRIVER_REDIS_URL` để bật cache heartbeat. Gửi `session_id` hiện hành đến `POST /api/v1/drivers/me/heartbeat` mỗi 10 giây. Driver ghi key `driver:presence:{driver_id}` sau khi commit PostgreSQL và đặt hạn 30 giây tính từ thời điểm nhận. Sau một heartbeat hợp lệ, dùng `redis-cli TTL "driver:presence:<driver-id>"` để kiểm tra TTL. Khi ngừng gửi heartbeat, TTL sẽ về `-2` sau khi key hết hạn.

PostgreSQL vẫn cung cấp dữ liệu heartbeat cho matching và readiness. Nếu Redis mất kết nối, endpoint vẫn dùng kết quả ghi PostgreSQL và ghi log lỗi cache.

## Kịch bản nghiệm thu

1. Tạo và duyệt hồ sơ, đặt xe active, bật nhận chuyến. Mở phiên vị trí, gửi heartbeat và cập nhật vị trí. Gửi `Idempotency-Key` cho các mutation mà OpenAPI yêu cầu.
2. Dùng Matching gọi `FindNearbyDrivers`. Gửi hai yêu cầu giữ cùng một tài xế với hai mã chuyến khác nhau. Kiểm tra một yêu cầu tạo `HELD` và yêu cầu còn lại nhận `DRIVER_UNAVAILABLE`.
3. Gửi lại yêu cầu thành công với cùng key và payload. Kiểm tra ID và hạn giữ chỗ được giữ nguyên. Dùng `GetReservation` để đọc trạng thái hiện tại.
4. Dùng Trip xác nhận lượt giữ chỗ. Kiểm tra Matching bị từ chối khi giải phóng lượt `CONFIRMED`. Dùng Trip giải phóng với đúng mã chuyến. Tìm lại tài xế sau khi cập nhật heartbeat và vị trí.

Driver ghi tên RPC, correlation ID và kết quả. Matching và Trip có thể dùng cùng correlation ID để đối chiếu luồng đặt xe.

## Kết quả kiểm tra

- PostgreSQL local đã chạy schema trên DB rỗng và DB legacy. Readiness trả `200` khi DB sẵn sàng và `503` khi dừng DB.
- Kiểm tra REST đã chạy với JWT thử nghiệm, bao gồm quyền admin/tài xế, phiên cũ, sequence lùi và idempotency đồng thời.
- Kiểm tra reservation đã chạy với hai request đồng thời tranh cùng tài xế, hai tài xế cho cùng chuyến, retry cùng key, hết hạn và quyền giải phóng.
- Bảy RPC đã được gọi qua kết nối gRPC local với token RS256. Các ca sai audience, thiếu scope, sai mã chuyến và Matching giải phóng lượt đã xác nhận đều bị từ chối.
- Demo Kafka/Saga với Matching và Trip thực tế cần hai service đó. Các kiểm tra trên dùng PostgreSQL local và gRPC client thử nghiệm.
