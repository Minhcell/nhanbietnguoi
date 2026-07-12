# LangID

Xác định một người nước ngoài đến từ đâu, dựa trên **bằng chứng thật**:

- **Giọng nói** → Whisper tự dò ngôn ngữ (Bengali / Hindi / Urdu / Ả Rập / ...)
- **Ảnh giấy tờ, hộ chiếu, visa, chữ viết** → Claude Vision đọc chữ, hệ chữ, mã nước, dòng MRZ
- **Hội thoại 2 chiều** → dịch qua lại + đọc thành tiếng (TTS)

## Build

Đẩy code lên GitHub → tab **Actions** tự build → tải APK ở mục **Artifacts**.

Không cần `gradlew`: workflow tự cài Gradle 8.7 trên runner.

## Cần chuẩn bị

| Key | Dùng cho |
|---|---|
| OpenAI API key | Whisper — dò ngôn ngữ từ giọng nói |
| Anthropic API key | Claude — đọc giấy tờ, phân tích, dịch |

Nhập trong tab **Cấu hình** của app.

## Nguyên tắc

App **không phân tích khuôn mặt, màu da hay dáng người** — những thứ đó không cho biết quốc tịch.
Nếu ảnh chỉ có mặt người mà không có chữ hay giấy tờ, app trả về "Không đủ bằng chứng".

Bằng chứng mạnh nhất: **trang thông tin hộ chiếu** (mã nước 3 chữ + dòng MRZ ở đáy).
