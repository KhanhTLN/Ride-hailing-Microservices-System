# Kết quả kiểm tra bộ API contract

Ngày kiểm tra: 02/10/2026.

| Kiểm tra | Kết quả |
|---|---|
| 8 OpenAPI 3.1 documents bằng openapi-spec-validator | PASS |
| Local $ref và operationId trong từng OpenAPI | PASS |
| 16 JSON Schemas bằng Draft7Validator.check_schema | PASS |
| 15 event examples qua JSON Schema + format checker | PASS |
| Từ chối event thiếu event_id | PASS |
| 4 AsyncAPI documents qua @asyncapi/parser 3.6.3 | PASS, không có error-level diagnostics |
| AsyncAPI local references và schema khớp file độc lập | PASS |
| 4 protobuf files bằng grpc_tools.protoc | PASS |
| Sinh Python protobuf và gRPC stubs, 10 RPC methods | PASS |

46 REST operations bao gồm 32 business/auth operations và 14 health endpoints.
15 Kafka message types, cộng một schema DeadLetter.

Các kiểm tra này xác nhận cấu trúc hợp đồng và khả năng sinh code. Chưa chạy backend,
MariaDB, Kafka, JWT issuer, hoặc Java server/client của nhóm. Các kịch bản trong
docs/ACCEPTANCE_TESTS.md là kế hoạch nghiệm thu cần thực hiện khi tích hợp.

Chạy lại: scripts/validate.py và scripts/validate-asyncapi.cjs (xem yêu cầu dependency trong README/script).
