# Matching Service (Python)

Matching nhận `TripRequested`/`MatchingCancelled` từ Kafka, gọi Driver qua gRPC,
lưu workflow Saga trong PostgreSQL và phát `DriverReserved`/`MatchingFailed` bằng outbox.

## Tích hợp với repo hiện tại

- User Service Java quản lý đăng ký/đăng nhập và access/refresh token của người dùng.
- Matching là worker nội bộ; không đăng nhập bằng tài khoản hành khách, không xử lý refresh token,
  không yêu cầu Identity Service hoặc client-credentials endpoint chưa tồn tại.
- `IDENTITY_BASE_URL`, `SERVICE_CLIENT_ID`, `SERVICE_CLIENT_SECRET` cũ không được sử dụng.
  `.env` hiện có được giữ nguyên; các khóa không dùng được bỏ qua.
- `DRIVER_GRPC_BEARER_TOKEN` là tùy chọn khi Driver hỗ trợ xác minh token do User Service cấp.
  Nó không tự chuyển user token thành service token và không tự gia hạn token.
- Giao tiếp local mặc định giống client gRPC Java hiện tại: không bearer. Giới hạn các cổng
  nội bộ vào localhost/mạng dịch vụ; khi triển khai ra mạng cần phối hợp xác thực Caller với Driver
  và cấu hình TLS/ACL tại hạ tầng. Không truyền token người dùng trong Kafka event.
- Driver Java hiện mới có đăng ký/xem hồ sơ và gọi User gRPC. Nhóm phải triển khai
  `ds03.driver.v1.DriverService` trong contract để Matching làm việc với Driver thật.
  `localhost:9090` hiện là **UserGrpcService**, không phải Driver reservation API.
- Driver chịu trách nhiệm eligibility, ownership reservation và chống gán cùng tài xế cho hai chuyến.
  Matching không đọc database của User/Driver/Trip. `driver_id` và `user_id` là hai ID khác nhau.

## Cài và chạy local (PowerShell, Python >= 3.11)

Từ thư mục `src/BE/matching-service`:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

Sửa `DATABASE_URL` bằng tài khoản PostgreSQL local thực tế. URL dùng driver async:

```dotenv
DATABASE_URL=postgresql+asyncpg://postgres:YOUR_PASSWORD@localhost:5432/matching
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
DRIVER_GRPC_ADDRESS=localhost:9091
```

Nếu mật khẩu có ký tự đặc biệt (`@`, `:`, `/`, `%`...), URL-encode phần mật khẩu.
Các cổng là ví dụ; cấu hình theo hạ tầng nhóm. `.env` không được commit.

Tạo database `matching` trong pgAdmin trước. Chạy migration:

```powershell
.\.venv\Scripts\python.exe -m alembic upgrade head
```

Migration đầu tiên cũng nhận các bảng đã import từ `scripts/sql/matchingdb_init.sql` và
ghi baseline vào `alembic_version`. Không drop dữ liệu. Bootstrap không sửa khác biệt schema
đã tồn tại; thay đổi schema sau baseline cần migration mới. Downgrade phá hủy dữ liệu bị chặn.
Models ORM ánh xạ các cột; constraint/index/trigger được định nghĩa trong SQL bootstrap.
Migration tiếp theo cần khai báo những thay đổi đó rõ ràng thay vì dựa vào autogenerate để suy luận.

Code protobuf và JSON Schema đã đi kèm package. Khi contract được nhóm cập nhật, chạy:

```powershell
.\.venv\Scripts\python.exe scripts/generate_proto.py
```

Script sinh client từ proto dùng chung và đồng bộ schema/SQL vào resources để wheel/Docker chạy
độc lập. Không sửa file `_pb2.py`/`_pb2_grpc.py` hoặc resource copy bằng tay.

Khởi động Kafka và tạo ba topic với retention >= 7 ngày:

```text
ds03.trip.events.v1
ds03.matching.events.v1
ds03.trip.events.v1.dlq.matching-v1
```

Sau đó chạy:

```powershell
.\.venv\Scripts\matching-service.exe
```

Hai endpoint HTTP nội bộ:

```text
GET http://127.0.0.1:8084/internal/matching/health/live
GET http://127.0.0.1:8084/internal/matching/health/ready
```

Liveness không kiểm tra dependency. Readiness kiểm tra schema DB, Kafka, channel Driver và worker;
trả 503 theo error envelope của contract nếu chưa sẵn sàng. Channel kết nối thành công chưa chứng minh
Driver đã implement mọi RPC: RPC thiếu sẽ trả `UNIMPLEMENTED` trong luồng nghiệp vụ.
Service vẫn khởi động và reconnect khi DB/Kafka/Driver gián đoạn. Migration chạy riêng trước ứng dụng.

## Chạy bằng Docker Compose

Compose này tách biệt compose gateway hiện có, dùng PostgreSQL/Kafka local cho phát triển:

```powershell
docker compose up --build -d
```

- PostgreSQL: `localhost:15432`, database/user `matching`, mật khẩu mẫu `matching_local_only`.
- Kafka dành cho chương trình trên máy host: `localhost:19092`.
- Matching HTTP: `localhost:18084`.
- Driver chạy trên máy host, gRPC `9091`; sửa `DRIVER_GRPC_ADDRESS` trong compose khi nhóm chọn cổng khác.
- PostgreSQL có volume riêng. Kafka trong compose là broker phát triển, chưa cấu hình nhân bản/TLS/ACL.
- `topics` tạo topic và `migrate` tạo schema trước khi Matching khởi động.

Muốn dùng riêng Kafka Docker với DB local đã có:

```powershell
docker compose up -d kafka topics
```

Sau đó đặt `KAFKA_BOOTSTRAP_SERVERS=localhost:19092` trong `.env` và chạy Python trên host.

## Workflow và ranh giới transaction

1. Consumer validate schema, key=trip_id=aggregate_id; chỉ nhận hai loại sự kiện Matching quan tâm.
2. Transaction ngắn ghi `processed_events` + attempt/tombstone, rồi mới commit Kafka offset.
3. Worker claim attempt có lease/token; transaction ngắn lưu mutation key/request trước RPC.
4. Find trong 5km, tối đa 20 ứng viên; reserve tuần tự, mặc định hold 30s.
5. Timeout reserve giữ `UNKNOWN`, retry cùng key/request; không chọn driver khác khi kết quả chưa rõ.
6. Kết quả + outbox + aggregate version được commit trong cùng transaction.
7. Publisher gửi at-least-once; retry giữ event_id/payload và chặn version mới khi version cũ còn pending.
8. Cancel trước request được tombstone chặn; cancel giữa reserve phải reconcile rồi release HELD.
   Matching tuyệt đối không release CONFIRMED; reservation đó thuộc Trip.

`trip_id`, `attempt_id` là UUID do Trip cấp. Version sự kiện tăng theo trip đối với producer Matching.
`version` của row/claim phục vụ kiểm soát worker, không phải aggregate_version.
Tổng search budget giữ nguyên `search_deadline` từ Trip, không reset sau retry/restart.
Không giữ transaction/row lock trong khi chờ RPC/Kafka.

Các lỗi RPC có ba lần retry nhanh với backoff 200/500/1000ms + jitter, sau đó chuyển lịch retry bền.
Query thất bại kết thúc khi hết search budget; mutation chưa rõ kết quả và compensation vẫn được giữ để
đối soát. Sau 7 ngày không tự gửi lại mutation vì Driver không bảo đảm giữ key lâu hơn; cần operator xử lý.

## Retry, DLQ và vận hành

Message xử lý lỗi được lưu bytes/key/headers/vị trí Kafka vào `message_failures` trước commit offset.
Retry sau 1/5/15/30/60 giây; sau năm retry ghi DLQ vào outbox. Không đánh dấu processed chỉ vì đưa DLQ.
JSON hỏng dùng `original_bytes_base64`; offset trong DLQ là chuỗi. Lỗi nghiệp vụ `NO_DRIVER` phát
`MatchingFailed`, không đưa DLQ. Outbox chưa gửi được tiếp tục retry, không rollback reservation.

Giới hạn payload Kafka/gRPC 64 KiB, metadata gRPC 8 KiB. Một message hỏng lớn gần giới hạn có thể
thành DLQ vượt 64 KiB sau Base64; trong trường hợp đó bytes gốc vẫn nằm trong DB và outbox pending,
log `OUTBOX_PAYLOAD_TOO_LARGE_REQUIRES_OPERATOR` để operator đối soát, không cắt mất dữ liệu.

Sau khi sửa nguyên nhân, replay message về original topic với event_id/key gốc; consumer dedup
sẽ ngăn lặp nghiệp vụ đã tiếp nhận. Không xóa processed_events để ép thực hiện lại một Saga đã thành công.
Giữ processed_events >=30 ngày và tombstone >=7 ngày; chỉ purge khi workflow/cleanup liên quan đã kết thúc.
Hiện chưa tự purge lịch sử để tránh xóa công việc còn dang dở.

Log JSON có timestamp, service, event type, correlation/attempt/trip IDs và kết quả; không log token,
password hoặc nội dung request. Theo dõi các log retry và các row UNKNOWN/outbox pending lâu ngày.

## Kiểm thử

Unit/schema/HTTP tests (không cần DB):

```powershell
.\.venv\Scripts\python.exe -m pytest tests/test_contracts.py -q
```

Toàn bộ test PostgreSQL + gRPC thật trên localhost (Driver là test double implement proto):

```powershell
.\.venv\Scripts\python.exe scripts/run_isolated_tests.py -q
```

Cần `initdb`, `postgres`, `pg_ctl`, `createdb` trên PATH. Script tạo cluster/database tạm, chạy migration
và tự dọn dẹp; không dùng `.env` hay database `matching` đang có. Test có guard chỉ cho phép truncate
database tên `matching_test_*`. Không dùng SQLite để giả lập các ràng buộc PostgreSQL.

Kiểm tra Kafka thật khi có broker dành cho test:

```powershell
$env:TEST_KAFKA_BOOTSTRAP_SERVERS = "localhost:19092"
.\.venv\Scripts\python.exe scripts/run_isolated_tests.py -q
```

Test Kafka chỉ tạo/xóa hai topic có tên UUID riêng và dùng consumer group riêng.
Nếu không cấu hình broker, test này được báo skipped; test khác vẫn chạy.

Các kịch bản có test: tranh chấp một tài xế, replay message/attempt, mất response sau reserve,
rollback kết quả/outbox, restart worker với key cũ, cancel đến trước request hoặc giữa RPC,
không release CONFIRMED, race confirm/release, retry/DLQ, expired search, stale worker và migration.
Tích hợp cuối cùng vẫn cần chạy với Driver Java và Trip thật của nhóm.

Dependencies trực tiếp ở `pyproject.toml`; `requirements.lock` ghi phiên bản môi trường đã kiểm thử.
Nguồn kỹ thuật: [SQLAlchemy asyncio](https://docs.sqlalchemy.org/en/20/orm/extensions/asyncio.html),
[aiokafka](https://aiokafka.readthedocs.io/en/stable/consumer.html),
[gRPC Python](https://grpc.io/docs/languages/python/basics/),
[Saga](https://microservices.io/patterns/data/saga.html),
[Kafka Docker](https://kafka.apache.org/39/getting-started/docker/).
