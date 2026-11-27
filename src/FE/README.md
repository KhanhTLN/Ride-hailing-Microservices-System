# ride. Frontend

React + TypeScript + Vite, tiếng Việt, responsive desktop/mobile. Mã FE nằm hoàn
toàn trong `src/FE`. Giao diện chính: **http://localhost:5173/**.
Trang `http://localhost:8083/maps/` vẫn là trang thử nghiệm bản đồ trước đây.

## Chạy bằng Docker

Từ thư mục gốc repository:

```powershell
docker compose -f deploy/docker-compose.yml up -d --build
```

Nếu backend đã healthy, chỉ build/chạy lại FE:

```powershell
docker compose -f deploy/docker-compose.yml up -d --build --no-deps frontend
```

FE được build thành static assets, phục vụ bằng Nginx. Proxy cùng origin:

| Prefix FE | Backend trong Docker |
| --- | --- |
| `/backend/user/` | `user-service:8082` |
| `/backend/driver/` | `driver-service:8081` |
| `/backend/trip/` | `trip-service:8080` |
| `/backend/pricing/` | `pricing-service:8083` |

Nginx phân giải lại hostname Docker, giữ query string, Authorization và các
header idempotency/correlation. Không phụ thuộc gateway Nginx cũ trong `src/BE/api-gateway`.
Đổi cổng FE qua `FE_HTTP_PORT` trong `deploy/.env` và truyền `--env-file deploy/.env`.

## Phát triển local

Cần Node.js 24, đã thử trên Windows/PowerShell và Docker Linux:

```powershell
cd src/FE
npm.cmd ci
Copy-Item .env.example .env
npm.cmd run dev
```

Vite proxy tới các cổng backend trên localhost. `.env` chỉ chứa URL backend,
không chứa JWT secret, password DB hoặc service token. Nếu Docker FE đang giữ
cổng 5173, chạy dev bằng `npm.cmd run dev -- --port 5174`.

## Các luồng

- **Tài khoản:** đăng ký PASSENGER/DRIVER; đăng nhập, hiện lỗi tại form, xem/ẩn mật
  khẩu, khôi phục phiên khi reload, refresh access token, đăng xuất. Đăng ký ADMIN
  công khai không được hỗ trợ; dùng tài khoản quản trị đã được cấp quyền.
- **Hành khách:** nhập địa chỉ → chọn gợi ý Photon → chọn xe → tuyến OSRM + quote
  120 giây → đặt chuyến → theo dõi SEARCHING/DRIVER_OFFERED/ACCEPTED/ARRIVED/
  IN_PROGRESS/COMPLETED. Hủy trước khi bắt đầu, nêu lý do; xem lịch sử; thử thanh
  toán lại khi backend trả FAILED. Chặn đặt thêm khi có chuyến đang hoạt động.
- **Tài xế:** tạo hồ sơ GPLX → thêm/sửa xe → chờ admin duyệt → cho phép GPS và
  bật nhận chuyến → cập nhật vị trí/heartbeat → nhận hoặc từ chối lời mời → đến
  điểm đón → bắt đầu → hoàn thành. Phiên định vị tồn tại khi chuyển giữa các trang
  trong FE; ngừng nhận chuyến hoặc đăng xuất sẽ chuyển availability về false.
- **Admin:** tìm/lọc hồ sơ; duyệt hoặc đình chỉ tài xế với lý do bắt buộc.
- **Tài khoản cá nhân:** xem hồ sơ và cập nhật email bằng API backend hiện có.
  Tên hiển thị hiện chỉ đọc vì API sửa hồ sơ hiện tại chưa hỗ trợ trường này.

Chỉ chọn kết quả trong danh sách mới xác nhận tọa độ. Sửa địa chỉ xóa quote cũ.
Hai ô dùng debounce 450 ms, lịch gửi chung cách nhau ít nhất 1,2 giây, cache,
AbortController và loại bỏ phản hồi cũ; hỗ trợ bộ gõ, phím mũi tên/Enter/Escape.
Autocomplete gọi Photon, không gọi public Nominatim. Xem
[Photon](https://github.com/komoot/photon),
[chính sách Nominatim](https://operations.osmfoundation.org/policies/nominatim/).

Map là Leaflet + OSM, có attribution, không tải trước/offline. Nếu nền trống do
DNS, xem hướng dẫn trong `deploy/README.md`. OSRM hiện dùng driving graph cho cả
xe máy/ô tô, ETA chưa có giao thông trực tiếp. Phí chuyến lấy từ quote backend.

## Đồng bộ và chống gửi trùng

Trip/offer được poll tuần tự mỗi 2–2,5 giây, operation mỗi 1,2 giây; hủy request
khi đổi trang. UI chỉ chuyển bước sau khi đọc trạng thái backend. Lệnh Trip dùng
`expected_version`, offer dùng thêm `attempt_id`/`reservation_id`. Ghi lệnh đang
chờ, body, key và operation ID vào sessionStorage để tiếp tục sau reload. Lỗi
transport/5xx giữ nguyên lệnh cho lần thử lại, không sinh thao tác mới tự động.
Quote hết hạn được cấp key mới; một quote đã chấp nhận giữ nguyên giá.

Access token chỉ ở bộ nhớ; refresh token ở **sessionStorage theo tab**, không dùng
localStorage. Nhiều request 401 trong một tab dùng chung một lần refresh. Đăng
xuất revoke refresh token, xóa phiên; phản hồi refresh cũ không làm sống lại phiên
đã đăng xuất. SessionStorage vẫn có thể bị đọc bởi JavaScript cùng origin; khi
triển khai production nên bổ sung BFF/HttpOnly cookie và cấu hình HTTPS phù hợp.

Giới hạn theo backend hiện có: Payment/Notification Service chưa có trong stack;
FE không tạo kết quả thanh toán giả. Không có API để hành khách đọc GPS tài xế,
nên trang theo dõi hiển thị trạng thái và tuyến, chưa có marker tài xế di chuyển
trực tiếp. Lịch sử hiển thị tối đa 100 chuyến theo API, không giả lập pagination.
GPS cần localhost hoặc HTTPS và quyền định vị của người dùng. Đóng tab/dừng GPS
sẽ ngừng heartbeat, backend loại vị trí cũ theo TTL. Chưa có API quên mật khẩu.

## Cấu trúc

| File/thư mục | Mục đích |
| --- | --- |
| `src/App.tsx` | Khôi phục phiên, menu theo role, định tuyến hash, bảo vệ màn hình |
| `src/lib/api.ts` | HTTP client, refresh single-flight, lỗi và idempotency key |
| `src/lib/commands.ts` | Lưu và theo dõi operation Saga, tiếp tục sau reload |
| `src/lib/presence.tsx` | GPS, location session/sequence, heartbeat và availability |
| `src/lib/suggestions.ts` | Hạn chế tốc độ/cache gợi ý Photon |
| `src/lib/hooks.ts` | Poll tuần tự, hủy request, user/session và điều hướng |
| `src/lib/types.ts` | Kiểu dữ liệu tương ứng DTO backend |
| `src/components/AddressInput.tsx` | Combobox địa chỉ và chọn kết quả |
| `src/components/MapView.tsx` | Leaflet, marker, GeoJSON, cảnh báo ảnh nền |
| `src/pages/` | Tài khoản, đặt xe, tài xế, chi tiết/lịch sử chuyến, duyệt hồ sơ, hướng dẫn |
| `src/styles.css` | Giao diện, responsive, focus và reduced-motion |
| `vite.config.ts` / `nginx.conf` | Proxy dev / Docker production |
| `tests/` | Kiểm thử giao diện fixture và luồng backend thật |

## Kiểm thử

```powershell
# Trong src/FE:
npm.cmd run build
npm.cmd test
# Docker FE hoặc Vite phải đang chạy ở cổng 5173. Mặc định dùng Edge đã cài.
npm.cmd run test:e2e
# Nếu chưa có Edge, cài browser theo hướng dẫn Playwright hoặc sửa channel ở config.
```

Unit tests kiểm tra refresh đồng thời, chống sống lại phiên sau logout,
giữ nguyên body/key sau refresh, không tự retry write 5xx và scope key theo user.
Các browser test dùng API fixture, chặn tile công cộng; bao phủ tạo chuyến một
lần, quote hết hạn, phục hồi lệnh bị mất phản hồi sau reload, quy trình tài xế,
onboarding, admin, sai mật khẩu, sửa email và mobile.

Luồng thật (opt-in, Docker stack đang healthy):

```powershell
$env:FE_LIVE_TEST = '1'
npm.cmd run test:e2e -- tests/live.spec.ts
Remove-Item Env:FE_LIVE_TEST
```

Test tạo tài khoản riêng, tạm promote **đúng tài khoản test mới tạo** qua SQL để
có admin duyệt hồ sơ (đây là fixture, không phải quyền tự nâng cấp trong FE).
Sau đó thao tác qua UI thật: đăng ký, hồ sơ/xe, duyệt, GPS, gợi ý, báo giá, đặt,
Matching tự động, nhận/đón/bắt đầu/hoàn thành và lịch sử. Không inject Matching
hoặc Payment event. Cuối test hạ admin về PASSENGER, revoke token test, chuyển
tài xế offline và xử lý chuyến còn dang dở; giữ các bản ghi để đối chiếu.
Artifacts ở `test-results/`, không commit. Chỉ dùng tài khoản test khi bật trace.
