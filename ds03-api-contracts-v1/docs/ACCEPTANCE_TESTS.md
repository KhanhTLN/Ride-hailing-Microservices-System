# Kịch bản nghiệm thu hợp đồng

Mỗi test ghi correlation_id, request/event_id, state trước/sau và bằng chứng DB/log. Đây là kế hoạch kiểm thử tích hợp; chưa phải kết quả chạy backend.

| ID | Kịch bản | Kết quả yêu cầu |
|---|---|---|
| F01 | Register + login passenger/driver | JWT đúng claims; driver PENDING |
| F02 | Admin approve + vehicle + bật online | Driver đủ điều kiện khi heartbeat/location mới |
| F03 | Lấy quote + tạo trip | SEARCHING, một TripRequested bền, trả 201 |
| F04 | Matching giữ chỗ | Một HELD, Trip DRIVER_OFFERED |
| F05 | Tài xế accept | Driver CONFIRMED trước Trip ACCEPTED |
| F06 | Arrive + start + complete | Đúng transition; final fare bằng quote |
| F07 | Payment success | COMPLETED/SUCCEEDED, ledger chỉ một lần |
| F08 | Notification polling/mark-read | Chỉ đúng người nhận truy cập được |
| C01 | Hai trip cùng reserve một driver | Chỉ một reservation active |
| C02 | Hai accept/cancel đồng thời | Một operation được claim; không trạng thái mâu thuẫn |
| C03 | Hai CreateTrip cùng key | Một trip, cùng resource id |
| C04 | Hai CreateTrip khác key cùng passenger | Tối đa một trip nonterminal |
| R01 | Driver commit nhưng RPC response mất | Retry cùng key trả reservation ban đầu |
| R02 | Trip chết sau confirm trước update DB | Restart reconcile thành ACCEPTED đúng một lần |
| R03 | Payment down khi có command | Restart tiếp tục từ Kafka, không mất payment |
| R04 | Notification xử lý lỗi | Retry -> DLQ đúng group, Trip không bị rollback |
| R05 | Cancel rồi DriverReserved đến muộn | Trip vẫn CANCELLED, reservation được release |
| R06 | Cancel event đến trước matching request | Tombstone chặn reserve mới |
| R07 | Worker expiry và confirm đồng thời | Chỉ một kết quả hợp lệ; không EXPIRED thành CONFIRMED |
| R08 | PaymentSucceeded bị gửi lại | Không ghi ledger/cập nhật/notification trùng |
| R09 | Complete khi Driver down | Trip COMPLETED, operation PENDING, restart release được |
| S01 | Token hết hạn/sai audience | 401 hoặc UNAUTHENTICATED |
| S02 | Driver A gọi accept trip của B | 403 hoặc NOT_FOUND |
| S03 | Matching release CONFIRMED | PERMISSION_DENIED, reservation giữ nguyên |
| S04 | User đăng ký ADMIN | Payload bị từ chối |
| S05 | Tọa độ ngoài range / key cũ body khác | 422 / IDEMPOTENCY_CONFLICT |
| S06 | Location session cũ và seq thấp | Không ghi đè vị trí mới |
| P01 | 100 khách tạo trip đồng thời | Đo p50/p95 latency, throughput, lỗi và số driver active trùng (=0) |

Thử crash bằng dừng process thật ở các điểm sau DB commit và trước publish/response. Mock chỉ là bước đầu; nghiệm thu phải chạy service độc lập và DB/Kafka thật.
