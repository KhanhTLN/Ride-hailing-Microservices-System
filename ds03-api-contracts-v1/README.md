# DS03 — API contracts v1.0.0

Bộ hợp đồng giao tiếp đề xuất cho dự án đặt xe của nhóm, ngày 02/10/2026.
Đây là baseline để nhóm thống nhất và triển khai; không phải backend đã chạy.

## Mở file nào trước?

1. `docs/CONTRACT.md`: quy tắc nghiệp vụ và phân quyền bắt buộc.
2. `docs/REST_ENDPOINTS.md`: danh sách REST API và service sở hữu.
3. `rest/openapi.yaml`: OpenAPI 3.1 tổng hợp, mở bằng Swagger Editor hoặc import Postman.
4. `rest/<service>.openapi.yaml`: hợp đồng REST riêng từng service.
5. `proto/ds03/`: file gRPC dùng chung để sinh code Python và Java.
6. `docs/KAFKA_EVENTS.md`: danh sách producer, consumer, topic.
7. `kafka/<service>.asyncapi.yaml`: AsyncAPI 3.0 theo từng service.
8. `kafka/schemas/`: JSON Schema draft-07 độc lập cho từng Kafka message.
9. `examples/`: payload ví dụ về cấu trúc, không phải dữ liệu seed hay chuỗi giao dịch hoàn chỉnh.
10. `docs/ACCEPTANCE_TESTS.md`: các kịch bản nhóm cần thực hiện.
11. `VALIDATION.md`: kết quả kiểm tra bộ contract.

## Phạm vi v1

- Bảy service: Identity, Driver/Vehicle/Location, Trip, Matching, Pricing, Payment mô phỏng, Notification.
- REST cho app; gRPC cho lời gọi nội bộ; Kafka cho xử lý nền.
- Notification v1 là hộp thông báo lưu bền + REST polling mỗi 2 giây; WebSocket/push chưa thuộc v1.
- Vị trí do app mô phỏng. Tính giá cố định theo quote; không tích hợp bản đồ hay cổng thanh toán thật.
- Database/schema riêng cho mỗi service. Không chia sẻ bảng nghiệp vụ giữa service.
- Gateway tùy chọn. Matching không có business REST/gRPC endpoint: nhận Kafka và gọi Driver gRPC.
- Bộ SQL Driver đã tạo trước đó KHÔNG được import tự động bởi bộ contract này.

## Sinh Python gRPC code

Chạy từ thư mục gốc bộ contract, trong môi trường Python đã cài grpcio-tools:

```bash
mkdir -p generated/python
python -m grpc_tools.protoc -I proto --python_out=generated/python --grpc_python_out=generated/python proto/ds03/common/v1/common.proto proto/ds03/driver/v1/driver.proto proto/ds03/pricing/v1/pricing.proto proto/ds03/identity/v1/identity.proto
```

Đặt `generated/python` vào PYTHONPATH hoặc đóng gói thành package nội bộ. Không sửa file sinh tự động.
Java dùng cùng thư mục proto làm import root và plugin `protoc-gen-grpc-java`; package Java được khai báo trong từng file.
Server implement các stub sinh ra; client dùng channel TLS và JWT metadata theo tài liệu.

## Chạy kiểm tra contract

```bash
python -m pip install -r requirements-validation.txt
python scripts/validate.py
```

Kiểm tra AsyncAPI bằng AsyncAPI CLI riêng nếu có: `asyncapi validate kafka/trip.asyncapi.yaml` và tương tự các service khác.
Các cổng localhost chỉ là ví dụ; cấu hình endpoint qua biến môi trường khi tích hợp.

## Quy trình làm việc nhóm

- Đặt bộ này vào một repo `ds03-contracts` hoặc thư mục `contracts/` trong monorepo.
- Các thành viên cùng pin tag `v1.0.0`; tạo mock theo contract rồi viết service song song.
- Đổi ý nghĩa field, bỏ field, thay enum hoặc trạng thái phải review liên service.
- Chỉ thêm protobuf field với số mới; không tái sử dụng số đã xóa, phải `reserved` số/tên đó.
- Thay đổi không tương thích: REST `/v2`, protobuf package `v2`, Kafka topic `.v2`.
- Không đưa JWT, secret hay mật khẩu thật vào payload ví dụ hoặc repository.

Nguồn định dạng: https://spec.openapis.org/oas/v3.1.0 ; https://www.asyncapi.com/docs/reference/specification/v3.0.0 ; https://protobuf.dev/programming-guides/proto3/ .
