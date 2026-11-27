# Backend local với Docker Compose

Frontend chính nằm trong `src/FE`, chạy tại **http://localhost:5173/** khi Compose
khởi động. Bao gồm các luồng hành khách, tài xế và duyệt hồ sơ của admin. Hướng
dẫn chạy dev, cấu trúc source và kiểm thử: [src/FE/README.md](../src/FE/README.md).
`FE_HTTP_PORT` cho phép đổi cổng. Trang `/maps/` cổng 8083 bên dưới vẫn là demo bản đồ.

Ch?y t? th? m?c g?c repository, Docker Desktop d?ng Linux containers:

```powershell
docker compose -f deploy/docker-compose.yml up -d --build
docker compose -f deploy/docker-compose.yml ps -a
```

Stack n?y ??c l?p v?i `docker-compose.infra.yml` v? Compose dev ri?ng c?a Matching.
Kh?ng ch?y ??ng th?i c?c stack c? c?ng tr?ng nhau. D? li?u d?ng volume c?a project
`rhds-local`; `down` gi? d? li?u, kh?ng c?n x?a volume ?? ?p d?ng b?n s?a.

## C?ng v? database

| Backend | REST tr?n localhost | gRPC tr?n localhost | PostgreSQL tr?n localhost |
| --- | --- | --- | --- |
| User | 8082 | 9090 | 5435 / `rhds_user` |
| Driver | 8081 | 9091 | 5433 / `rhds_driver` |
| Trip | 8080 | ? | 5434 / `rhds_trip` |
| Pricing | 8083 | 9093 | 5436 / `rhds_pricing` |
| Matching | 8084, health | ? | 5544 / `matching_db` |

Kafka: `localhost:9092` t? host, `kafka:29092` trong Docker. Redis c?a Driver:
`localhost:6379` t? host, `redis:6379` trong Docker. URL DB trong container lu?n
tr? t?i hostname c?a DB t??ng ?ng, c?ng 5432. C?ng 5435 c?a User tr?nh PostgreSQL
IoT ?ang d?ng 5432. Pricing hi?n l?u quote trong PostgreSQL, kh?ng m?t quote khi
restart; kh?ng d?ng l?i c?u h?nh in-memory c?a b?n tr??c.

## C?u h?nh v? kh?i t?o

Compose c? gi? tr? m?c ??nh cho local; kh?ng c?n s?a `.env` trong t?ng service.
Mu?n ??i m?t kh?u/c?ng:

```powershell
Copy-Item deploy/.env.example deploy/.env
# S?a deploy/.env r?i ch?y c?ng --env-file:
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build
```

Gi? c?ng `JWT_SECRET` cho User, Driver, Trip, Pricing. `MATCHING_DB_PASSWORD` ???c
nh?ng trong URL n?n d?ng ch?/s?/`_`/`-`. ??i bi?n password kh?ng t? ??i m?t kh?u
c?a t?i kho?n PostgreSQL ?? ???c t?o trong volume c?.

C?c b??c t? ch?y khi `up`:

- User: job `user-schema-init` ?p d?ng `deploy/sql/user-v2.sql` gi? nguy?n response
  idempotency c? v? t?ch password verifier BCrypt kh?i request digest. Hibernate
  c?p nh?t c?c entity c?n l?i, g?m `refresh_session` v? `registration_request`.
  Kh?ng import script `userdb_init.sql` c? c? role constraint ch?a ph? h?p.
- Driver: volume m?i ch?y `scripts/sql/driver/schema.sql`; Hibernate validate schema.
- Trip: volume m?i ch?y `scripts/sql/tripdb_init.sql`. Job `trip-schema-init` lu?n
  ch?y migration c?ng th?m `deploy/sql/trip-v2.sql` tr??c khi Trip kh?i ??ng;
  gi? nguy?n d? li?u v? b? sung Saga step/retry, reservation, sequence, event dedup.
- Pricing: volume m?i ch?y `deploy/sql/pricing.sql`, l?u quote immutable v? key
  idempotency ???c scope theo passenger.
- Matching: job `matching-init` ch?y `python -m matching init-db`, kh?ng drop b?ng.
- Kafka: `kafka-volume-init` c?p quy?n volume cho UID/GID 1000, sau ?? `kafka-init`
  t?o 4 topic nghi?p v? v? 5 DLQ, 3 partition, replication 1, retention 7 ng?y.
  Topic ?? c? ???c gi? nguy?n. Kafka/Redis/DB ??u d?ng volume b?n.

C?c job init hi?n th? `Exited (0)` l? b?nh th??ng. C?c service ch?y l?u d?i c?n
hi?n th? healthy.

## X?c th?c v? API mutation

??ng nh?p qua User ?? l?y `access_token`. C?c REST API nghi?p v? c?a Driver,
Trip v? Pricing x?c th?c access token n?y, ph?n bi?t `token_use=access` v?i refresh.
`X-User-Id`/`X-Role` kh?ng quy?t ??nh danh t?nh ho?c quy?n. Token c?a b?n c? kh?ng
c? `token_use` c?n ??ng nh?p l?i.

Refresh token ???c l?u d?ng hash, ch? d?ng m?t l?n; rotation/revoke b?n trong DB.
Reuse refresh token ?? rotate s? thu h?i c? family. Logout idempotent v? revoke
family. Access token c?n hi?u l?c t?i exp theo contract.

Mutation c? `Idempotency-Key` ph?i gi? nguy?n key v? body khi retry:

- Register/CreateQuote/CreateTrip tr? l?i c?ng resource. Fingerprint ??ng k? kh?ng
  ch?a m?t kh?u; password ???c ??i chi?u b?ng verifier BCrypt c? salt ri?ng.
- Action Trip tr? `202` c?ng operation ID; poll `GET /api/v1/operations/{id}`
  b?ng access token c?a participant cho t?i SUCCEEDED/FAILED.
- Accept/reject c?n `expected_version`, `attempt_id`, `reservation_id` l?y t? Trip.
- Cancel c?n `expected_version` v? `reason` (1..300 k? t?).
- Arrive/start/complete/retry-payment c?n `expected_version`.
- Kh?ng cancel IN_PROGRESS. Ch? driver ???c g?n thao t?c chuy?n; ch? passenger s?
  h?u ???c retry payment khi COMPLETED v? payment FAILED.

Driver gRPC d?ng RS256 cho service token, t?ch bi?t access token HMAC c?a user.
`local-service-tokens` l? fixture local, l?u private key trong volume ri?ng v?
renew token m?i 60 gi?y. Matching/Trip ch? mount token c?a m?nh; Driver ch? nh?n
public key. User ch?a c? endpoint `client_credentials` ch?nh th?c. Matching d?ng
`DEMO_MODE=true`, Driver plaintext v? Kafka plaintext cho local; g?i Driver th?t.

## Lu?ng Saga v? ph?c h?i

Trip persist operation tr??c RPC. Worker g?i Driver/Pricing gi?a c?c transaction,
retry c?ng mutation key khi k?t qu? ch?a r?; kh?ng gi? row lock l?c ch? gRPC.

- CreateTrip ghi trip + TripRequested + idempotency atomically. Deadline 120 gi?y
  ???c l?u b?n; m?i l?n reject/offer expiry d?ng attempt m?i v? gi? t?ng deadline.
- DriverReserved ch? ?p d?ng ??ng attempt; Trip ki?m tra reservation v?i Driver
  r?i ph?t DriverOffered. Accept confirm th?nh c?ng m?i ghi ACCEPTED.
- Cancel ghi CANCELLED/MatchingCancelled b?n r?i release. K?t qu? ??n mu?n ???c
  ??a v?o cleanup b?n, kh?ng h?i sinh trip.
- Complete l?u final fare, COMPLETED, PaymentRequested, r?i release Driver. Driver
  down th? operation c?n PENDING, retry/restart ti?p t?c t? step RELEASE.
- Event consumer dedup event ID, ki?m tra payment ID/attempt/amount/passenger v?
  b? qua k?t qu? c?. Outbox gi? PENDING khi Kafka ch?a ack; kh?ng b? event sau 5 l?i.
- Retry poison event theo 1/5/15/30/60 gi?y r?i g?i DeadLetter envelope ??ng group.

Payment v? Notification Service ch?a c? m? ngu?n trong repo. Topic ???c t?o s?n,
nh?ng ledger thanh to?n v? th?ng b?o ho?n ch?nh c?n c?c service ??. API gateway
c? ch?a n?m trong stack n?y.

## Ki?m th? v? b?ng ch?ng

D?ng m?i tr??ng Python c?a Matching ?? c?i `requirements.lock.txt`:

```powershell
.\src\BE\matching-service\.venv\Scripts\python.exe -u scripts/verify_backend_flow.py
# N?u d?ng bi?n ri?ng:
.\src\BE\matching-service\.venv\Scripts\python.exe -u scripts/verify_backend_flow.py --env-file deploy/.env
```

Runner t?o t?i kho?n `flow-<run_id>-...@example.invalid`, admin fixture ri?ng,
xe/v? tr? v? UUID ri?ng. Kh?ng x?a d? li?u c? s?n ho?c reset consumer group.

Th? t? test:

1. Readiness, register/login, JWT, idempotency register, refresh rotation/logout,
   concurrent refresh, concurrent quote v? hai t?nh hu?ng CreateTrip ??ng th?i
   (c?ng key v? kh?c key c?a c?ng passenger).
2. Driver profile, admin approve, xe, online, location session/heartbeat, sequence
   c?, idempotency conflict. Duy tr? presence m?i 5 gi?y.
3. Quote -> CreateTrip -> Kafka -> Matching -> Driver HELD -> Trip DRIVER_OFFERED
   t? ??ng, ??ng th?i ki?m tra outbox/inbox v? JSON Schema.
4. Reserve concurrent, duplicate event, deadline, no-driver, cancel tr??c/sau reserve.
5. Accept -> CONFIRMED -> arrive -> start -> complete -> RELEASED, ki?m tra ownership,
   version, stale offer v? retry c?ng idempotency key.
6. G?i payment event test sai/current/old attempt; ch? consumer commit qua offset
   tr??c khi ??i chi?u DB. ??y l? test consumer, ch?a ph?i ledger c?a Payment.
7. T?o fixture attempt m?i ri?ng ?? ki?m tra late MatchingFailed kh?ng ??i tr?ng th?i.
8. Restart Pricing sau khi quote ?? ch?p nh?n; d?ng Driver khi complete, restart
   Trip trong l?c operation PENDING, b?t Driver v? ch? t? release. Ch? restart c?c
   service trong stack n?y; runner lu?n c? b?t l?i Driver trong finally.
9. G?i m?t event c? ? sai schema ?? ki?m tra retry/DLQ; validate event Trip ph?t ra.

Runner l?u `deploy/backend-flow-report.json`, g?m request/response, expected/actual,
correlation ID, event ID, Kafka partition/offset v? snapshot DB. M?t kh?u/token
???c che. Exit 0 = t?t c? check PASS; 1 = c? check FAIL; 2 = l?i ch?y/prerequisite.

Test Kafka/gRPC/SQL t? t?o v? fault injection ??u ???c ??nh d?u `fixtures`. Lu?ng
ch?nh kh?ng d?ng event s?a tay ho?c confirm tay ?? v??t qua l?i Saga. K?t th?c:
release reservation c?a l??t test, ??a driver test offline, h?y trip ch?a k?t th?c,
h? role admin fixture; gi? c?c d?ng d? li?u test ?? ??i chi?u.

User Docker build ch?y 5 test `JwtTokenProviderTest` v? 3 test `CorrelationFilterTest`
trong common. Header correlation kh?ng h?p l? b? t? ch?i tr??c khi t?o event.
Worker ki?m tra l?i status/step d??i row lock tr??c khi ghi k?t qu? RPC, tr?nh ?p
m?t k?t qu? ?? ???c worker kh?c ho?n t?t. Unit test Matching:

```powershell
Push-Location src/BE/matching-service
.\.venv\Scripts\python.exe -m pytest -q
Pop-Location
```

B? test n?y kh?ng thay th? th? t?i 100 kh?ch hay to?n b? nghi?m thu ledger/notification.

## D?ng

```powershell
docker compose -f deploy/docker-compose.yml down
```

Gi? nguy?n volume. Xem log: `docker compose -f deploy/docker-compose.yml logs -f trip-service matching-service`.

## Bản đồ, địa chỉ và tuyến đường

Mở **http://localhost:8083/maps/** sau khi chạy Compose. Đăng nhập bằng tài khoản
PASSENGER của User Service, hoặc tạo tài khoản ngay trên trang (mật khẩu 12–128 ký tự).
Token chỉ nằm trong bộ nhớ của trang; tải lại trang cần đăng nhập lại. Access token
hết hạn được refresh qua User Service; đăng xuất thu hồi refresh token.

Thông báo đăng nhập/đăng ký hiển thị ngay dưới form tài khoản. Nếu nền bản đồ trống,
cảnh báo xuất hiện trên bản đồ; việc tải ảnh nền độc lập với đăng nhập. Có thể
kiểm tra DNS bằng `Resolve-DnsName tile.openstreetmap.org` rồi đối chiếu với
`Resolve-DnsName tile.openstreetmap.org -Server 1.1.1.1`. Nếu DNS mặc định báo
không tồn tại nhưng DNS công cộng trả địa chỉ, hãy sửa DNS của trình duyệt/mạng.
Trên Brave: Settings → Privacy and security → Security → Use secure DNS →
Cloudflare (1.1.1.1), rồi tải lại trang. Xem
[hướng dẫn DNS của Cloudflare](https://developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/encrypted-dns-browsers/).

1. Nhập ít nhất 3 ký tự vào **Điểm đón**. Sau khi ngừng gõ khoảng 450 ms, danh sách
   địa điểm khớp tự hiện dưới ô. Bấm một kết quả, hoặc dùng phím mũi tên và Enter.
2. Làm tương tự với **Điểm đến**. Phải chọn kết quả để xác nhận tọa độ; nhập chữ
   rồi để nguyên chưa đủ để tìm tuyến. Bản đồ tự đặt marker theo kết quả đã chọn.
   Sửa địa chỉ sẽ xóa tọa độ, tuyến và quote cũ. Marker không kéo được, bấm bản đồ
   không thay đổi địa chỉ. Escape đóng danh sách gợi ý.
3. Chọn phương tiện, nhấn **Xem tuyến đường** để vẽ đường thực tế, xem km và phút.
4. Nhấn **Lấy giá chuyến đi** để tạo quote 120 giây. Quote dùng khoảng cách/thời gian
   OSRM do server lấy; giá và snapshot được lưu vào PostgreSQL. Trang này phục vụ
   chọn hành trình/xem giá, chưa có màn hình đặt chuyến hoặc theo dõi tài xế.

Leaflet 1.9.4 được lưu tại `pricing-service/src/main/resources/static/maps/vendor`
(giấy phép BSD-2-Clause trong `vendor/LICENSE`). Tiles hiển thị trực tiếp từ
OpenStreetMap qua HTTPS, có attribution; giữ cache trình duyệt mặc định, không
prefetch hay tải offline. Photon, Nominatim và OSRM được gọi qua Pricing Service. Không
đưa mật khẩu/JWT lên URL hoặc gửi thông tin tài khoản tới các nhà cung cấp bản đồ.

API mở rộng dưới Pricing HTTP 8083, cần `Authorization: Bearer <access_token>`:

| API | Input | Output |
| --- | --- | --- |
| `GET /api/v1/maps/suggestions?q=...` | Địa chỉ 3–200 ký tự, Photon autocomplete | Tối đa 8 `{place_id, display_name, latitude, longitude}` |
| `GET /api/v1/maps/places?q=...` | Địa chỉ 3–200 ký tự | Tối đa 5 `{place_id, display_name, latitude, longitude}` |
| `GET /api/v1/maps/reverse?latitude=...&longitude=...` | Vĩ/kinh độ hợp lệ | Một địa điểm; giữ nguyên tọa độ người dùng đã chọn khi gắn nhãn |
| `POST /api/v1/maps/route` | `{pickup:{latitude,longitude},dropoff:{latitude,longitude},vehicle_type:"CAR"}` | `{distance_m,duration_s,geometry,provider,profile,vehicle_model}` |
| `POST /api/v1/quotes` | Body như route, thêm header `Idempotency-Key` | Quote theo contract cũ; `estimated_distance_m`/`estimated_duration_s` lấy từ OSRM |

`geometry` là GeoJSON LineString, tọa độ `[longitude, latitude]`. Không đảo thứ tự
khi chuyển sang OSRM. Leaflet `L.geoJSON` tự chuyển sang quy ước hiển thị của nó.
Không tin khoảng cách, thời gian hoặc giá do client tự gửi.

`pricing-schema-init` áp dụng `deploy/sql/pricing.sql` trước Pricing Service cho cả
volume cũ và mới, không xóa quote. Hai bảng bổ sung: `map_response_cache` lưu cache
JSON (địa chỉ 7 ngày, tuyến đường 1 giờ); `map_provider_gate` giới hạn tốc độ dùng
chung cho các replica. Truy vấn giống nhau được gom bằng advisory lock; cache hit
không gọi provider. Entry hết hạn được dọn theo lô khi ghi cache mới. Không giữ
transaction DB trong thời gian chờ HTTP tới nhà cung cấp.

**Gợi ý khi gõ:** Trang dùng Photon (dữ liệu OSM), dịch vụ hỗ trợ autocomplete;
không gọi Nominatim từ sự kiện gõ. Kết quả được lọc theo quốc gia (`vn` mặc định),
loại trùng và giữ tọa độ Photon trả về. Frontend debounce 450 ms, cách các lượt
gửi ít nhất 1,2 giây, hủy request và bỏ kết quả cũ khi người dùng sửa ô, hỗ trợ bộ
gõ tiếng Việt. Hai ô dùng chung lịch gửi; chỉ retry truy vấn hiện tại một lần khi
nhận 429. Cache phía trang tối đa 50 truy vấn; cache/rate gate PostgreSQL dùng
chung giữa replica. Photon demo yêu cầu lưu lượng vừa phải, không đảm bảo SLA;
có thể chuyển `PHOTON_BASE_URL` sang instance tự host khi cần phục vụ nhiều người.
Xem [Photon](https://github.com/komoot/photon) và
[Photon API](https://github.com/komoot/photon/blob/master/docs/api-v1.md).

**Giới hạn dịch vụ công cộng:** Nominatim và OSRM demo đều tối đa 1 request/giây;
backend đặt khoảng cách 1,1 giây cho từng provider, dùng cache PostgreSQL và
User-Agent định danh; Photon cũng dùng giới hạn thận trọng 1,1 giây. Nominatim
cấm autocomplete: `/places` và `/reverse` được giữ cho các thao tác tra cứu thủ
công của client khác, không dùng cho gợi ý khi gõ hay fallback tự động từ Photon.
Nếu quá tốc độ trả `429 MAP_RATE_LIMITED` với `Retry-After: 2`; không có đường trả
`422 ROUTE_NOT_FOUND`; timeout/lỗi provider trả `503 MAP_PROVIDER_UNAVAILABLE`.
Không dùng khoảng cách đường chim bay để thay thế khi OSRM lỗi. Quote đã lưu vẫn
replay theo idempotency và dùng để quyết toán khi provider tạm ngừng.

Đây là endpoint công cộng miễn phí, phù hợp demo lưu lượng thấp, không có SLA.
OSRM public dùng **driving graph cho cả CAR và MOTORBIKE**, không phải mô hình xe
máy chuyên dụng; thời gian không có giao thông trực tiếp. Trang hiển thị rõ giới
hạn này. Muốn định tuyến xe máy đúng hạn chế đường, cần tự host OSRM với graph
xe máy và cấu hình endpoint tương ứng; chỉ đổi tên profile trong URL không đổi
được graph đã preprocess. Phạm vi tìm tài xế gần của Driver/Matching vẫn dùng bán
kính địa lý, độc lập với tuyến đường dùng để báo giá.

Cấu hình trong `deploy/.env.example` (dùng `--env-file deploy/.env` khi chạy Compose):
`PHOTON_BASE_URL`, `NOMINATIM_BASE_URL`, `NOMINATIM_COUNTRY_CODES` (lọc quốc gia cho cả
Photon và Nominatim, mặc định `vn`, để rỗng nếu tìm toàn
cầu), `OSRM_BASE_URL`, `OSRM_MOTORBIKE_BASE_URL`, `OSRM_CAR_PROFILE`,
`OSRM_MOTORBIKE_PROFILE`, `OSRM_MOTORBIKE_USES_DRIVING_MODEL`, `MAPS_USER_AGENT` và
`MAPS_AUTH_BASE_URL` (địa chỉ User API mà trình duyệt truy cập được). Khi triển khai,
đặt User-Agent kèm URL/email liên hệ của ứng dụng. Các URL có thể đổi sang nhà cung
cấp khác hoặc self-host mà không sửa mã. Giới hạn toàn cục đòi hỏi các replica dùng
chung Pricing DB; dùng nhiều DB tách rời cần một bộ giới hạn chung ở gateway.

Chính sách và tài liệu: [Nominatim](https://operations.osmfoundation.org/policies/nominatim/),
[OSM tiles](https://operations.osmfoundation.org/policies/tiles/),
[OSRM demo](https://github.com/Project-OSRM/osrm-backend/wiki/Demo-server),
[OSRM API](https://project-osrm.org/docs/v5.24.0/api/).

Kiểm thử phần bản đồ:

```powershell
# Build tự chạy 18 test Java với HTTP provider giả lập, không gọi dịch vụ công cộng.
docker compose -f deploy/docker-compose.yml build pricing-service

# Stack phải đang healthy. Tạo hai Pricing replica tạm dùng DB thật rồi tự dọn.
# Kiểm tra auth, cache/rate limit giữa replica, coalesce request, route, quote và lỗi provider.
python scripts/verify_maps_flow.py
# Nếu dùng env riêng: thêm --env-file deploy/.env; đổi User port: --user-url http://127.0.0.1:8082

# Kiểm tra UI trên Edge headless. Dependency chỉ ở thư mục tạm, không thêm npm vào backend.
npm.cmd install --prefix "$env:TEMP\rhds-maps-browser" playwright@1.56.1 --no-audit --no-fund
$env:RHDS_PLAYWRIGHT_MODULE = "$env:TEMP\rhds-maps-browser\node_modules\playwright"
node scripts/verify_maps_ui.cjs
# Muốn kiểm tra trên Brave:
# $env:RHDS_BROWSER_PATH = 'C:/Program Files/BraveSoftware/Brave-Browser/Application/brave.exe'
# node scripts/verify_maps_ui.cjs
```

Test UI chặn toàn bộ tile request công cộng và dùng API fixture; không tải tile
để chạy test tự động. `deploy/maps-flow-report.json` ghi kết quả DB/API fixture;
`deploy/maps-live-report.json` ghi lần thử thủ công với một tìm kiếm địa điểm công
cộng và một tuyến Hà Nội thật. Bộ kiểm tra luồng backend ở phần trên tiếp tục
kiểm tra Trip/Matching/Saga với quote OSRM; giữ nguyên cache để tránh gọi public
provider lặp lại không cần thiết. Các tài khoản/quote test được giữ làm bằng chứng,
không ghi token hoặc mật khẩu vào report.
