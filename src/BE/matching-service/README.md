# Matching Service bằng Python — hướng dẫn triển khai

Đây là bản nền để học và tích hợp vào Ride-hailing-Microservices-System, dựa trên
commit `349684b`. Chỉ đọc contract tại `ds03-api-contracts-v1`; không dùng API contract
cũ trong `docs`. `user-service` đảm nhiệm vai trò Identity.

## 1. Mục tiêu và giới hạn của bản đầu

Matching nhận Kafka `TripRequested`, gọi Driver bằng gRPC để tìm và giữ chỗ tài xế,
rồi gửi `DriverReserved` hoặc `MatchingFailed`. Matching không confirm tài xế nhận
chuyến; Trip thực hiện việc đó. Không có business REST endpoint trong v1.

Bản này có persistent inbox, chống trùng event, workflow lưu DB, idempotency key,
outbox, retry, DLQ và cancellation tombstone. Tất cả dữ liệu nghiệp vụ thuộc DB riêng.
Không đọc hoặc ghi trực tiếp DB của Driver/Trip/User.

Phạm vi chạy: **một instance Matching trên một database**. PostgreSQL advisory lock
chặn instance thứ hai. Chỉ dùng một worker ứng dụng; chưa mở rộng bằng nhiều replica.
Việc mở rộng đòi hỏi thiết kế claim/lease theo attempt, fencing và ordering outbox.

Đây chưa phải kết quả nghiệm thu toàn hệ thống. Driver Java hiện tại chưa có bộ RPC
trong contract, User chưa có service-token endpoint theo contract, Trip chưa có code
trong snapshot đã đọc. Vì vậy hướng dẫn bắt đầu với mock Driver.

## 2. Những file bạn cần hiểu

| File | Vai trò |
|---|---|
| `matching/config.py` | Đọc biến môi trường, cấu hình DB/Kafka/Driver/User |
| `matching/contracts.py` | Kiểm tra event bằng chính JSON Schema của contract |
| `matching/db.py` | Các bảng SQLAlchemy và khóa một instance |
| `matching/driver.py` | Driver gRPC client; lấy service token từ User khi tích hợp thật |
| `matching/workflow.py` | Luồng tìm, reserve, hủy, retry và phục hồi |
| `matching/runtime.py` | Kafka consumer, workflow worker, outbox publisher |
| `matching/__main__.py` | Lệnh init-db/run và health endpoints |
| `scripts/generate_proto.py` | Sinh Python code từ contract, không tự viết lại proto |
| `scripts/create_topics.py` | Tạo topic cho môi trường dev |
| `scripts/mock_driver.py` | Driver giả lập gRPC cho học và kiểm thử |
| `scripts/demo.py` | Đóng vai Trip, tạo event với UUID và deadline mới |
| `tests/` | Kiểm tra workflow và gọi gRPC thật tới mock |

Không thêm Matching Python vào Maven `pom.xml`. Không sửa `common/user_service.proto`
để thay thế các proto DS03: hai bộ proto hiện có mục đích và mức hoàn thiện khác nhau.

## 3. Chuẩn bị trên Windows

Cài Python 3.12 và Docker Desktop đang chạy Linux containers. Các lệnh dưới dùng
PowerShell. Nếu bạn đặt repo ở ổ khác, thay đường dẫn tương ứng.

Đặt toàn bộ thư mục `matching-service` từ bộ source này vào:

`D:\Ride-hailing-Microservices-System\src\BE\matching-service`

Giữ nguyên thư mục `ds03-api-contracts-v1` tại gốc repo. Nếu dùng gói ZIP, giải nén nó
vào một thư mục tạm rồi chép `src/BE/matching-service` sang repo của bạn; không cần
chép đè contract nếu repo của bạn đã có đúng bộ contract.

```powershell
cd D:\Ride-hailing-Microservices-System\src\BE\matching-service
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.lock.txt
Copy-Item .env.example .env
.\.venv\Scripts\python.exe scripts\generate_proto.py
```

Dùng trực tiếp `.venv\Scripts\python.exe` giúp bạn không phải thay PowerShell
ExecutionPolicy để activate môi trường.

`requirements.lock.txt` ghi các phiên bản đã dùng kiểm tra. `requirements.txt` ghi
khoảng phiên bản để chủ động nâng cấp sau; không tự sửa file `_pb2.py` hoặc `_pb2_grpc.py`.

File sinh ra gồm `generated/ds03/common/v1/common_pb2.py` và
`generated/ds03/driver/v1/driver_pb2.py`, `driver_pb2_grpc.py`. `driver.py` tự thêm
thư mục `generated` vào đường dẫn import.

## 4. Bật PostgreSQL và Kafka cho môi trường dev

```powershell
docker compose -f docker-compose.dev.yml up -d --wait
.\.venv\Scripts\python.exe -m matching init-db
.\.venv\Scripts\python.exe scripts\create_topics.py
```

| Thành phần | Địa chỉ từ laptop |
|---|---|
| PostgreSQL | `localhost:5544`, database `matching_db` |
| DB user/password dev | `matching` / `matching_demo` |
| Kafka | `localhost:19092` |
| Mock Driver | `localhost:50052` |
| Matching health | `localhost:8084` |

Compose tạo volume giữ DB và Kafka khi container khởi động lại. `docker compose ... down`
không xóa volume; không thêm `-v` nếu bạn muốn giữ dữ liệu.

`init-db` chỉ tạo những bảng chưa tồn tại, không drop bảng. Đây là bước tạo schema ban
đầu riêng biệt, chưa phải bộ migration có quản lý phiên bản. Khi thay đổi schema ở
các bước tiếp theo, thêm Alembic migration; đừng dùng `create_all` thay cho migration.

Nếu nhóm đã có Kafka/DB, sửa `.env` trỏ tới môi trường đó và thống nhất topic trước
khi chạy lệnh tạo topic. Không cần chạy thêm broker trùng lặp.

## 5. Chạy mock Driver và Matching

Mở PowerShell thứ nhất, vào thư mục Matching:

```powershell
.\.venv\Scripts\python.exe scripts\mock_driver.py
```

Mở PowerShell thứ hai, cùng thư mục:

```powershell
.\.venv\Scripts\python.exe -m matching run
```

Kiểm tra từ PowerShell thứ ba:

```powershell
Invoke-RestMethod http://localhost:8084/internal/matching/health/live
Invoke-RestMethod http://localhost:8084/internal/matching/health/ready
```

Liveness chỉ kiểm tra process đang phục vụ. Readiness kiểm tra worker, DB, Kafka
topics, kết nối Driver và khả năng lấy service token khi không ở demo mode. Readiness
không thay thế kiểm thử quyền từng RPC và cấu hình TLS/SASL trong môi trường thật.

Demo mode không lấy token từ User và dùng gRPC plaintext tới mock chạy local. Mock
không có toàn bộ nghiệp vụ Driver, không kiểm tra quyền, heartbeat hay vị trí thật.
File `mock-driver.sqlite3` giữ reservation; đừng xóa file khi đang thử phục hồi sau lỗi.
Sau mỗi demo thành công, reservation HELD giữ 30 giây; hết hạn tài xế mới rảnh lại.

## 6. Gửi yêu cầu như Trip và xem kết quả

Trong PowerShell thứ ba:

```powershell
.\.venv\Scripts\python.exe scripts\demo.py success
```

Kết quả mong đợi là event `DriverReserved`, có `trip_id`, `attempt_id`, `driver_id`,
`reservation_id`, `expires_at`. Script validate response theo schema trước khi in.
`DriverReserved` chứng minh Matching đã giữ chỗ; không có nghĩa tài xế đã accept.

Thử tiếp từng trường hợp, chờ reservation cũ hết 30 giây nếu cần:

```powershell
.\.venv\Scripts\python.exe scripts\demo.py duplicate
.\.venv\Scripts\python.exe scripts\demo.py no-driver
.\.venv\Scripts\python.exe scripts\demo.py cancel-before
.\.venv\Scripts\python.exe scripts\demo.py cancel-after
.\.venv\Scripts\python.exe scripts\demo.py expired
```

| Scenario | Cần quan sát |
|---|---|
| `success` | Có DriverReserved và một reservation HELD |
| `duplicate` | Gửi cùng event hai lần; DB chỉ có một kết quả nghiệp vụ |
| `no-driver` | Loại trừ cả hai tài xế mock; nhận MatchingFailed/NO_DRIVER |
| `cancel-before` | Hủy đến trước yêu cầu; DB attempt CANCELLED, không reserve |
| `cancel-after` | Nhận kết quả rồi hủy; DB attempt CANCELLED, mock reservation RELEASED |
| `expired` | Không reserve; nhận MatchingFailed/DEADLINE_EXCEEDED |

Script chỉ hiển thị result đầu tiên tương ứng; với duplicate/cancel, hãy kiểm tra DB
để xác nhận điều kiện. Không dùng các JSON example có deadline cũ để thử thành công.

Xem tiến trình và outbox (có thể thêm WHERE với trip_id script vừa in):

```powershell
docker compose -f docker-compose.dev.yml exec postgres psql -U matching -d matching_db -c "SELECT attempt_id, trip_id, state, driver_id, retries, last_error FROM matching_attempts;"
docker compose -f docker-compose.dev.yml exec postgres psql -U matching -d matching_db -c "SELECT id, payload->>'event_type' AS event_type, payload->>'aggregate_id' AS trip_id, published FROM matching_outbox ORDER BY id;"
```

Để mô phỏng Driver đã commit nhưng response reserve bị mất: dừng mock bằng Ctrl+C,
chạy lại terminal mock như sau rồi gửi demo success mới:

```powershell
$env:MOCK_LOSE_RESERVE_RESPONSE="true"
.\.venv\Scripts\python.exe scripts\mock_driver.py
```

Matching phải retry cùng reserve key, không chuyển ngay sang tài xế khác. Mock lưu
reservation vào SQLite trước khi trả UNAVAILABLE lần đầu. Tắt tùy chọn ở lần chạy sau:

```powershell
Remove-Item Env:MOCK_LOSE_RESERVE_RESPONSE
```

## 7. Đọc code theo một lượt tìm xe

1. `runtime.consume()` nhận Kafka record, ghi `matching_inbox`, rồi mới commit offset.
2. `Workflow.process_inbox()` validate, ghi `matching_processed_events` và attempt
   trong cùng transaction. Một workflow đã lưu bền có thể tiếp tục sau restart.
3. `Workflow._find()` gọi Driver FindNearbyDrivers: 5 km, tối đa 20 ứng viên.
   Driver là nơi kiểm tra APPROVED, xe active, online, vị trí/heartbeat fresh.
4. `_choose()` lưu tài xế và reserve key bền trước khi gọi network.
5. `_reserve()` gọi ReserveDriver giữ 30 giây. Nếu response không rõ, giữ RESERVING,
   retry cùng key; không chọn người tiếp theo ngay cả khi đã nhận hủy/hết deadline.
6. Khi có kết quả chắc chắn, transaction ghi trạng thái + outbox nguyên tử.
7. `runtime.deliver()` publish outbox, chỉ đánh dấu published sau broker ack.

Không giữ DB transaction/row lock trong lúc chờ gRPC/Kafka. Delivery là at-least-once:
crash sau publish nhưng trước đánh dấu có thể gửi lại **cùng event_id**; Trip phải dedup.

Với MatchingCancelled, lưu tombstone trước khi xử lý request đến muộn. Nếu đã giữ HELD,
get trạng thái rồi release. Nếu đã CONFIRMED, không release; log để Trip xử lý phần
reservation của nó. Có thể xảy ra DriverReserved đến Trip sau khi Trip đã hủy; Trip
vẫn phải xử lý cleanup sự kiện muộn theo contract.

Lỗi schema/inbox có retry bền 1/5/15/30/60 giây, sau đó handoff vào outbox DLQ.
Message vào DLQ không được ghi processed thành công; sửa nguyên nhân rồi replay vào
topic gốc ở offset mới. Mutation gRPC chưa rõ kết quả tiếp tục reconcile riêng, không
được giả định thất bại để bỏ reservation. Sau 6 ngày unresolved reserve chuyển
NEEDS_REVIEW và readiness 503 để tránh replay ngoài cửa sổ idempotency bảo đảm.

## 8. Kiểm thử

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```

Các bài kiểm tra dùng DB SQLite tạm cho state machine và một gRPC server thật trên
loopback cho mock. Có kiểm tra success, duplicate, cancellation tới trước, hết hạn,
ứng viên bị tranh chấp, response bị mất, restart bằng DB đã lưu, không release
CONFIRMED, cleanup retry, DLQ và producer sequence.

SQLite chỉ dùng trong test; runtime cố ý yêu cầu PostgreSQL. Test này chưa chứng minh
Kafka broker, PostgreSQL locking, Docker networking, TLS và tích hợp Java hoạt động.
Chạy demo với Compose để kiểm chứng các phần đó tại máy bạn trước khi merge.

## 9. Chuyển sang Driver/Trip/User thật

Thống nhất với nhóm:

| Service | Việc còn cần |
|---|---|
| Driver Java | Implement DS03 DriverService gRPC; ID tách driver_id/user_id; reserve idempotent, chống trùng và expiry |
| Trip | Producer TripRequested/MatchingCancelled; consumer kết quả; search budget và cleanup event muộn |
| User (Identity) | Service-token endpoint RS256/JWKS và provision scope driver:match |
| Hạ tầng | DB riêng, Kafka TLS/ACL, topic retention, địa chỉ và chứng chỉ gRPC |

Matching sử dụng phương thức gRPC đầy đủ `/ds03.driver.v1.DriverService/ReserveDriver`.
Driver Java phải sinh code từ cùng proto DS03; endpoint cũ UserGrpcService đang dùng
để đăng ký tài xế không thay thế DriverService này.

Sửa `.env`:

```dotenv
DEMO_MODE=false
DRIVER_GRPC_TARGET=driver-service.example.internal:50052
DRIVER_TLS=true
USER_TOKEN_URL=https://user-service.example.internal/internal/v1/auth/service-token
SERVICE_CLIENT_ID=matching-service
SERVICE_CLIENT_SECRET=provision-outside-git
KAFKA_SECURITY_PROTOCOL=SASL_SSL
KAFKA_SASL_USERNAME=matching-service
KAFKA_SASL_PASSWORD=provision-outside-git
```

Audience token là `driver-service`, scope `driver:match`; metadata có `authorization`
và `x-correlation-id`. Chứng chỉ CA riêng có thể đặt qua DRIVER_CA_FILE/KAFKA_CA_FILE.
Code kiểm tra chứng chỉ theo mặc định; không dùng verify=False. Với User HTTPS cần
CA tin cậy trong môi trường Python; hỗ trợ mTLS chưa nằm trong starter này.

Đổi các địa chỉ còn lại cho đúng môi trường. Nếu chạy Matching trong container:
`DATABASE_URL` dùng hostname DB, `KAFKA_BOOTSTRAP_SERVERS` dùng listener INTERNAL
`kafka:9092`, không dùng `localhost` để gọi container khác.

Dockerfile dùng build context là gốc repo và không COPY file .env:

```powershell
cd D:\Ride-hailing-Microservices-System
docker build -f src/BE/matching-service/Dockerfile -t matching-service:dev .
```

Bạn vẫn phải init schema riêng và cung cấp environment/network phù hợp khi chạy image.
Dockerfile chưa được chạy nghiệm thu trong môi trường soạn source này.

## 10. Những việc cần làm tiếp trước nghiệm thu

- Chạy end-to-end với Kafka/PostgreSQL và Driver/Trip/User thật; thử crash process
  sau Driver commit, trước publish và sau broker ack.
- Thêm migration có phiên bản khi schema thay đổi; chỉ chạy một migrator.
- Thiết lập metrics/alert, đặc biệt outbox backlog, workflow retry, DLQ, NEEDS_REVIEW.
- Thiết lập retention/cleanup: tombstone ít nhất 7 ngày, processed_events ít nhất
  30 ngày; không xóa workflow chưa reconcile hoặc outbox chưa publish. Bản đầu
  không tự purge, nên dữ liệu được giữ nhưng dung lượng sẽ tăng.
- Kiểm thử payload limits và quyền RPC trên Driver thật; mock không chứng minh auth.
- Nếu cần nhiều replica, triển khai claim/lease/fencing và kiểm tra concurrent workers
  trước khi gỡ khóa một instance.

## Nguồn kỹ thuật

- https://grpc.io/docs/languages/python/quickstart/
- https://docs.confluent.io/kafka-clients/python/current/overview.html
- https://docs.sqlalchemy.org/en/20/orm/session_basics.html
- https://hub.docker.com/r/apache/kafka
- https://hub.docker.com/_/postgres
