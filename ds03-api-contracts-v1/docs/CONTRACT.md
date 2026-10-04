# Quy định giao tiếp và nghiệp vụ DS03 v1

Các quy định dưới đây là một phần của contract, đi cùng OpenAPI, protobuf và Kafka JSON Schema.
Các ngưỡng thời gian là mặc định đề xuất cho đồ án, cần thống nhất nếu thay đổi.

## 1. Ownership và đơn vị dữ liệu

| Service | Nguồn dữ liệu có thẩm quyền | Giao tiếp |
|---|---|---|
| Identity | User, role, credential, refresh token, service client | REST + GetUser gRPC |
| Driver | Hồ sơ tài xế, xe, vị trí, heartbeat, reservation | REST + DriverService gRPC |
| Trip | Chuyến, assignment attempt, operation, Saga, trạng thái thanh toán phản chiếu | REST + Kafka; gọi Driver/Pricing/Identity gRPC |
| Matching | Tiến trình tìm ứng viên theo attempt_id, kết quả và cancellation tombstone | Kafka; gọi Driver gRPC |
| Pricing | Quote, phiên bản giá, final fare | REST + PricingService gRPC |
| Payment | Sổ thanh toán mô phỏng, kết quả từng attempt | Kafka + REST tra cứu |
| Notification | Inbox thông báo theo user_id | Kafka + REST polling |

UUID dạng lowercase canonical cho mọi ID. `user_id` và `driver_id` là hai ID khác nhau.
Trip lấy driver_user_id bằng GetDriver, dùng để phân quyền và gửi thông báo; không coi driver_id là JWT sub.
Thời gian UTC RFC3339 trên JSON và google.protobuf.Timestamp trên gRPC. DB lưu UTC.
Khoảng cách mét, thời lượng giây. Tiền JSON/protobuf là chuỗi thập phân, DB DECIMAL; v1 VND là số nguyên không âm.
Không cho client tự khai passenger_id, user_id hoặc số tiền phải trả. Lấy danh tính từ JWT và giá từ Pricing.
OpenAPI field tùy chọn được bỏ khỏi response khi chưa có, không trả null trừ khi schema cho phép.
V1 payload JSON từ chối field không khai báo để phát hiện lỗi tích hợp sớm; thêm field cần nâng contract và phối hợp rollout consumer trước.

## 2. Authentication / authorization

- REST: `Authorization: Bearer <access_token>`. gRPC metadata: `authorization: Bearer <service_token>`.
- Identity ký RS256; giữ private key, công bố public JWKS. Các service kiểm tra chữ ký, alg cố định, kid, iss, aud, exp, nbf nếu có.
- User access token: sub=user_id, roles, iss, aud=`ds03-api`, exp, iat, jti. Hạn 15 phút; refresh token 7 ngày, rotate và phát hiện reuse.
- Public register chỉ cho PASSENGER/DRIVER. ADMIN và service clients được tạo ngoài API đăng ký công khai.
- Tài khoản DRIVER mới chưa được nhận chuyến cho đến khi Driver APPROVED và có xe active.
- Token service: sub=service name, aud=service đích (ví dụ `driver-service`), scope, exp tối đa 5 phút. Chỉ cấp audience/scope đã được provision cho client.
- `/internal/v1/auth/service-token` có security=[] vì xác thực bằng client_secret trong body qua TLS; phải rate limit và che body khỏi log.
- JWT không tự được chuyển tiếp qua gRPC. Baseline dùng service token; user_id trong request chỉ được tin khi caller service có quyền thực hiện nghiệp vụ đó.
- JWT còn hiệu lực sau logout cho tới exp; logout thu hồi refresh token. JWKS cache phải giữ public key cũ tới khi access token đã cấp hết hạn; unknown kid refresh một lần.
- JWT chỉ xác minh danh tính; phải kiểm tra ownership tài nguyên riêng. Trip participant hoặc ADMIN mới được đọc trip/operation. Driver được đề nghị chỉ được accept/reject đúng attempt hiện tại.
- Payment chỉ cho passenger của payment hoặc ADMIN đọc; Notification chỉ cho chủ user_id đọc/mark-read.
- ID không thuộc người gọi có thể trả NOT_FOUND để không tiết lộ tài nguyên.
- TLS cho REST/gRPC; Kafka ACL theo producer/consumer, không đưa bearer token vào event.

| Caller | gRPC được phép | Scope tối thiểu |
|---|---|---|
| Matching | FindNearbyDrivers, ReserveDriver, GetReservation, ReleaseReservation, GetDriver | driver:match |
| Trip | GetDriver, GetReservation, ConfirmReservation, ReleaseReservation, GetDriverLocation | driver:trip |
| Trip | GetQuote, CalculateFinalFare | pricing:read |
| Trip | GetUser | user:read |

Matching chỉ release reservation HELD mà chính nó đã tạo, tuyệt đối không release CONFIRMED. Trip chỉ release reservation đã liên kết trip_id của operation.
Driver phải lưu caller ownership của reservation (metadata nội bộ hoặc bảng riêng). Trường này cần migration bổ sung nếu dùng file SQL Driver trước đó.
GetDriverLocation dành riêng Trip cho tính năng nội bộ tương lai; v1 chưa công khai vị trí tài xế cho hành khách.

## 3. Headers, validation, lỗi và retry

REST tùy chọn X-Correlation-ID (UUID); ingress tạo nếu thiếu, echo trong response. gRPC metadata `x-correlation-id` bắt buộc do caller tạo hoặc truyền tiếp.
Kafka giữ correlation_id của luồng. causation_id là ID event/command trực tiếp gây ra event mới; ingress tạo command UUID cho REST mutation.
Tối đa body REST/Kafka 64 KiB; gRPC message 64 KiB; metadata 8 KiB. Không log token, password, client_secret.

REST error envelope: `{code,message,correlation_id,retryable,details}`. Không dùng text message để rẽ nhánh logic.
gRPC dùng status chuẩn, metadata trailer `error-code` (ASCII) và `x-correlation-id`; không trả OK với error payload.

| code nghiệp vụ | HTTP | gRPC | Xử lý |
|---|---|---|---|
| INVALID_ARGUMENT | 422 | INVALID_ARGUMENT | Sửa payload |
| UNAUTHENTICATED | 401 | UNAUTHENTICATED | Lấy token hợp lệ |
| FORBIDDEN | 403 | PERMISSION_DENIED | Không retry |
| NOT_FOUND | 404 | NOT_FOUND | Không retry tự động |
| DRIVER_UNAVAILABLE | 409 | FAILED_PRECONDITION | Chọn ứng viên khác |
| RESERVATION_EXPIRED | 409 | FAILED_PRECONDITION | Tìm tài xế lại |
| RESERVATION_MISMATCH | 409 | FAILED_PRECONDITION | Không release reservation khác |
| INVALID_TRANSITION / QUOTE_EXPIRED | 409 | FAILED_PRECONDITION | Đọc lại trạng thái / lấy quote mới |
| OPERATION_IN_PROGRESS | 409 | ABORTED | Poll operation hiện tại rồi đọc lại trip |
| VERSION_CONFLICT | 409 | ABORTED | Đọc lại; không retry mù |
| IDEMPOTENCY_CONFLICT | 409 | ALREADY_EXISTS | Key cũ, nội dung khác; sửa client |
| RATE_LIMITED | 429 | RESOURCE_EXHAUSTED | Backoff; HTTP Retry-After |
| DEPENDENCY_UNAVAILABLE | 503 | UNAVAILABLE | Retry giới hạn |
| DEADLINE_EXCEEDED | 504 | DEADLINE_EXCEEDED | Kết quả có thể đã commit; retry cùng key hoặc tra cứu |
| INTERNAL | 500 | INTERNAL | Không giả định transaction đã thất bại |

Malformed JSON trả 400. gRPC enum *_UNSPECIFIED và UUID/field bắt buộc rỗng đều INVALID_ARGUMENT.
Readiness 503 trả Error theo OpenAPI; liveness không kiểm tra dependency, readiness kiểm tra dependency thực sự cần.
RPC deadline mặc định: query 2s, mutation 3s. Retry lỗi tạm thời tối đa 3 lần với backoff 200/500/1000ms + jitter trong budget nghiệp vụ.
Không giữ DB transaction/row lock trong lúc chờ RPC. Persist operation/Saga trước RPC; reconcile kết quả sau bằng khóa/version.

### Idempotency

REST mutation có Idempotency-Key theo OpenAPI; gRPC mutation dùng MutationContext.idempotency_key.
Namespace key = caller + operation + key. Public register dùng email chuẩn hóa làm namespace caller; không replay kết quả cho body khác. Hash nội dung nghiệp vụ canonical, không gồm JWT/correlation headers.
Cùng key/cùng body trả resource hoặc operation ban đầu; key/body khác trả IDEMPOTENCY_CONFLICT.
Persist kết quả ít nhất 7 ngày; client không tự động retry cùng operation sau cửa sổ này. UUID key khuyến nghị.
CreateTrip phải ghi trip + outbox + idempotency trong cùng transaction.
Operation 202 là tài nguyên bền: retry trả cùng operation id, GET operation cho kết quả cuối; không đồng nghĩa đã hoàn tất.
Refresh token không dùng generic idempotency: rotation, mất response thì client có thể phải đăng nhập lại. Heartbeat và location có quy tắc riêng bên dưới.

## 4. Driver / Location

CreateDriver tạo hồ sơ PENDING và runtime cùng transaction, user_id duy nhất từ JWT. ADMIN approve sau khi kiểm tra hồ sơ.
PUT vehicle chuẩn hóa biển số, upsert xe của chính tài xế; chỉ một xe active, không đổi xe khi có HELD/CONFIRMED.
Tắt accepting_trips không giải phóng reservation đang hoạt động. Bật chỉ được khi APPROVED và có xe active.
Heartbeat mỗi 10s; quá 30s không có heartbeat thì không được chọn cho chuyến mới. Mất mạng không giải phóng CONFIRMED.
POST location-sessions tạo session UUID server-side, vô hiệu session cũ và reset vị trí/sequence trong cùng transaction.
Location mỗi 3–5s: chỉ current session, sequence tăng; retry cùng sequence/cùng payload trả bản ghi hiện tại, cùng sequence/khác payload hoặc sequence thấp hơn trả VERSION_CONFLICT. received_at là giờ server, recorded_at phải nằm trong ±60s.
Location stale >30s không dùng để matching. Sequence tối đa 2^53-1 để JSON an toàn.
GetMyLocation trước lần cập nhật đầu trả NOT_FOUND. Heartbeat session không hiện hành trả VERSION_CONFLICT.

FindNearbyDrivers: radius_m 100..10000, limit 1..20, tối đa 100 excluded_driver_ids. Lọc APPROVED + active vehicle đúng loại + accepting + heartbeat/location fresh + không active reservation; sắp khoảng cách tăng rồi driver_id. Không bảo đảm giữ chỗ.
ReserveDriver: hold_seconds 15..60 (mặc định caller dùng 30), mutation key bắt buộc; khóa runtime row và recheck eligibility. HELD/CONFIRMED tối đa một trên driver và trip bằng DB unique constraint.
Matching phải kết thúc/reconcile mutation chưa rõ kết quả trước khi chọn ứng viên khác. Không song song reserve nhiều tài xế cho một trip.
ConfirmReservation chỉ HELD chưa hết hạn, đúng trip_id. Đã CONFIRMED cùng reservation thì thành công. RELEASED/EXPIRED không được hồi sinh.
ReleaseReservation đúng reservation_id+trip_id; terminal RELEASED/EXPIRED trả thành công terminal. GetReservation trả trạng thái hiện tại, kể cả đã hết hạn.
Expiry worker chỉ chuyển HELD quá hạn sang EXPIRED dưới cùng lock; xử lý reservation được confirm sát thời hạn một cách nguyên tử.

## 5. Pricing

POST quotes dùng passenger sub, pickup/dropoff, loại xe; quote immutable, hạn 120s. Lưu pricing_version và inputs.
Demo: khoảng cách Haversine nhân 1.3 rồi làm tròn lên mét; tốc độ mô phỏng 25 km/h. MOTORBIKE = 10000 + 5000/km; CAR = 20000 + 10000/km; làm tròn lên 1000 VND. Đây là giá giả lập, không phải giá Grab/Uber.
Trip GetQuote kiểm tra passenger và expires_at tại thời điểm tạo trip. Một quote chỉ tạo một trip (unique quote_id tại Trip).
CalculateFinalFare v1 trả đúng giá quote đã được Trip chấp nhận; không áp dụng lại hạn quote ở cuối chuyến. Không nhận số tiền từ client.
Nếu muốn giá theo thời gian/khoảng cách thực tế thì cần contract mới và nguồn đo được xác thực; không tự thêm vào v1.

## 6. Trip và Saga

| From | Action / event | To |
|---|---|---|
| SEARCHING | DriverReserved đúng attempt hiện tại | DRIVER_OFFERED |
| SEARCHING | hết budget tìm | NO_DRIVER |
| DRIVER_OFFERED | accept + Driver confirm thành công | ACCEPTED |
| DRIVER_OFFERED | reject / hết hạn + release đã reconcile | SEARCHING |
| ACCEPTED | arrive bởi tài xế được gán | ARRIVED |
| ARRIVED | start bởi tài xế được gán | IN_PROGRESS |
| IN_PROGRESS | complete, final fare đã lưu | COMPLETED |
| SEARCHING / DRIVER_OFFERED / ACCEPTED / ARRIVED | cancel hợp lệ | CANCELLED |

Không hủy IN_PROGRESS trong baseline. Passenger hủy chuyến của mình; driver chỉ hủy ACCEPTED/ARRIVED của mình (trước đó dùng reject).
Mỗi passenger tối đa một trip nonterminal; enforcement tại Trip DB. GET /trips/me trả mới nhất trước theo created_at + id, limit mặc định 20, opaque cursor.
Các action yêu cầu expected_version: dùng optimistic update/row lock. Mutation đồng thời trên một trip được serialize bằng persistent operation claim; có operation PENDING xung đột thì trả 409 OPERATION_IN_PROGRESS.
Action accept/reject/cancel/complete/retry-payment trả 202 Operation; client poll mỗi 1s (operation type PAYMENT_RETRY cho endpoint retry-payment). Nếu không thể nhận command vì trạng thái sai, trả lỗi ngay.
Một operation mới chỉ thành công khi các điều kiện dưới đây thỏa; timeout tạm thời giữ PENDING, không báo FAILED khi kết quả RPC chưa rõ.

### Tạo và Matching

Trip lưu SEARCHING, attempt UUID, tổng search budget 120s, outbox TripRequested. Khách nhận 201 ngay.
Matching dedup event_id và attempt_id, persist deadline/progress; tìm ứng viên trong 5km, tối đa 20; reserve tuần tự. DriverReserved chỉ phát sau khi reserve commit, cùng local transaction ghi kết quả/outbox.
Nếu Matching chết sau Driver commit trước ghi local, replay cùng ReserveDriver key phải phục hồi được reservation cũ.
Trip nhận DriverReserved chỉ chấp nhận attempt hiện tại khi SEARCHING. Dùng GetDriver lấy user_id phục vụ phân quyền/notification. Phát DriverOffered sau commit.
Trip nhận kết quả cũ/đến muộn thì không đổi state, tạo cleanup task ReleaseReservation và retry tới terminal.
MatchingFailed cho attempt hiện tại kết thúc NO_DRIVER, trừ khi Trip còn chiến lược tìm lại trong cùng tổng budget.
Tài xế reject/hết hạn: giải phóng/reconcile trước khi phát TripRequested với attempt_id mới; thêm tài xế từ chối vào excluded list. Hết budget => NO_DRIVER + TripUnmatched.

### Accept / cancel compensation

Accept lưu operation trước; gọi Driver ConfirmReservation. Thành công mới commit ACCEPTED + outbox TripAccepted + operation SUCCEEDED. Crash giữa RPC và DB được reconcile bằng GetReservation/retry cùng key.
Cancel commit CANCELLED và MatchingCancelled (nếu có attempt) để chặn kết quả đến muộn; operation còn PENDING trong lúc giải phóng reservation. Sau giải phóng đã biết hoặc xác nhận không có reservation, operation SUCCEEDED; cleanup kết quả matching đến muộn vẫn chạy độc lập.
Matching lưu cancellation tombstone theo attempt_id ít nhất 7 ngày; nếu cancel đến trước request thì request bị bỏ. Nếu đang reserve chưa rõ kết quả, reconcile rồi release HELD đã giữ. Matching không release CONFIRMED.
Vì HTTP requests đã serialize bằng operation claim, cancel không chen giữa accept đang PENDING; trả OPERATION_IN_PROGRESS để client đọc lại rồi quyết định.
Saga phải persist step, attempt count, next_retry_at, last_error. Không dựa vào task in-memory để phục hồi.

### Complete / Payment

Complete: Pricing final fare -> commit COMPLETED + payment_status PENDING + PaymentRequested + TripCompleted. Trip tạo payment_id bền và attempt_number=1. Tiếp tục release Driver; operation SUCCEEDED sau release. Nếu Driver down, trip vẫn COMPLETED nhưng operation PENDING và tài xế chưa được giải phóng.
Payment consumes command, ghi ledger và outbox kết quả nguyên tử; dedup (payment_id,attempt_number), một trip chỉ có một payment aggregate. Amount/passenger không được thay giữa các attempt.
Demo payment mặc định success; kiểm thử decline bằng cấu hình nội bộ SIMULATED_PAYMENT_MODE=decline, không nhận quyền điều khiển kết quả từ public request.
PaymentSucceeded/Failed chỉ Trip consume; Trip kiểm tra payment_id, amount, attempt hiện hành, commit state + TripPaymentUpdated. Notification dùng TripPaymentUpdated để tránh thông báo trước khi Trip cập nhật.
Retry-payment chỉ passenger của COMPLETED+FAILED, tạo operation, tăng attempt_number, giữ payment_id và amount, phát PaymentRequested. Operation thành công khi đã tiếp nhận retry bền; kết quả tiền theo payment_status. Không retry một payment đã SUCCEEDED.
Kết quả attempt cũ không được ghi đè kết quả mới. Transport error chưa rõ trạng thái phải retry cùng attempt, không tạo attempt mới. Refund, ví tiền thật và thu tiền mặt chưa thuộc v1.

## 7. Kafka delivery contract

JSON UTF-8, schema_version=1; các event type và data theo schemas. Kafka record key=trip_id=aggregate_id. EventId khác nhau cho mỗi event mới, không dùng lại correlation_id làm event_id.
aggregate_version là sequence bền của producer theo trip, tăng với mỗi event mới của producer; không so sánh version giữa producer. trip_version trong data là version resource Trip.
Topic ordering chỉ trong partition; không có ordering toàn cục giữa topic. Consumer dựa trên state/attempt/payment IDs để chống sự kiện sai thứ tự.
Các consumer group riêng: matching-v1, trip-matching-v1, trip-payment-v1, payment-v1, notification-v1. Replica cùng một chức năng dùng chung group.
Outbox delivery at-least-once: publish retry giữ nguyên event_id. Consumer ghi processed_events và nghiệp vụ cùng transaction, commit offset sau DB commit; với RPC cần persistent workflow trước commit offset.
Business failure (không tài xế, payment decline) là event nghiệp vụ, không đưa DLQ. Lỗi xử lý tạm thời: retry backoff 1/5/15/30/60s; sau 5 retry đưa `<original-topic>.dlq.<consumer-group>` và alert. Handoff retry/DLQ phải được ghi bền trước khi commit offset.
Retry/DLQ record có original_topic, partition, offset, consumer_group, attempts, error_code, failed_at và original_message. Invalid JSON lưu original_bytes_base64 thay original_message; không để một poison message chặn vô hạn partition.
DLQ replay giữ event_id gốc; không đánh dấu processed thành công cho message mới chỉ được đưa DLQ. Repair nguyên nhân trước replay.
Kafka retention tối thiểu 7 ngày; processed_events giữ tối thiểu retention + cửa sổ DLQ replay (v1 30 ngày). Sau 30 ngày replay cần đối soát nghiệp vụ.
Matching/Trip dùng scheduler bền cho timeout; Kafka không tự sinh sự kiện hết hạn. Consumer không sleep dài giữ transaction.

## 8. Notification

Nhận DriverOffered gửi cho driver_user_id; TripAccepted/DriverArrived gửi cho passenger; TripStarted/TripCompleted/TripCancelled gửi cả hai nếu có driver_user_id; TripUnmatched/TripPaymentUpdated gửi passenger.
Unique (source_event_id,user_id,type). Commit inbox notification + dedup nguyên tử; lỗi Notification không rollback chuyến.
Polling GET /notifications với cursor; mark-read idempotent và chỉ chủ sở hữu. Inbox lưu lịch sử, client đọc Trip hiện tại trước thực hiện action từ thông báo cũ.

## 9. Những thay đổi so với phác thảo trước

- Notification dùng REST polling ở v1, chưa bắt buộc WebSocket.
- REST action dài trả operation 202 để không giữ HTTP tới khi Saga hoàn thành.
- Bổ sung GetReservation để reconcile timeout và MatchingCancelled để dừng attempt.
- Payment event chỉ đến Trip; Notification nhận TripPaymentUpdated sau commit.
- Driver SQL cũ cần migration lưu owner của reservation để giới hạn quyền Matching; không sửa generated active_slot hoặc bỏ unique indexes.
- Cấu hình endpoint và JWKS issuer do nhóm triển khai cung cấp; không có credential thật trong contract.

## 10. Quy tắc response/event chi tiết

- DriverOffered.status=DRIVER_OFFERED; TripAccepted.status=ACCEPTED; DriverArrived.status=ARRIVED; TripStarted.status=IN_PROGRESS; TripCompleted.status=COMPLETED; TripCancelled.status=CANCELLED.
- Với TripCancelled, driver_id và driver_user_id cùng có hoặc cùng vắng.
- PaymentSucceeded/PaymentFailed.amount phải đúng PaymentRequested; TripPaymentUpdated phản ánh kết quả tương ứng.
- Pagination: cursor là vị trí opaque thuộc bộ lọc hiện tại; next_cursor vắng khi hết trang. Notification cũng sắp created_at DESC, id DESC.
- DLQ offset là chuỗi số để không mất độ chính xác JSON; xem DeadLetter.schema.json.
- Startup tạo/cập nhật schema bằng migration riêng; contract không tự cấp quyền DB/Kafka.
