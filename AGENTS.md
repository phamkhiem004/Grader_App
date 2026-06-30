# AGENTS.md — Grader App (chấm thi Flutter + AI nhận xét)

Hướng dẫn cho Codex khi làm việc trong repo này. Đọc kỹ phần **Gotchas** — nhiều thứ
khác với mặc định và đã từng gây lỗi thật.

## Repo này là MỘT gói hoàn chỉnh
```
Grader_App/                  ← repo duy nhất (clone 1 cái là đủ)
├── grader/        Backend Spring Boot 4 · Java 17 · MySQL · gọi Docker để chấm
├── frontend/      Next.js 16 · React 19 · Tailwind v4  (ĐỌC frontend/AGENTS.md trước khi sửa FE)
├── feedback-bot/  AI nhận xét: FastAPI + Ollama + ChromaDB (RAG) — Python
├── grader-base/   Dockerfile ảnh nền chấm (Flutter SDK) → image `grading-base:latest`
├── exams/  submissions/   dữ liệu runtime (gitignore, rỗng khi mới clone)
├── bot-model.txt  ← 1 dòng: model Ollama cho bot nhận xét
├── grader-setup.cmd  ← MÁY TRỐNG: cài Docker/Node/Java/Python/Ollama + model (gọi installer/setup-prereqs.ps1, tự UAC)
├── run.cmd · start-all.ps1 · GraderLauncher.exe  ← chạy tất cả (chạy NGAY trong repo, không nhân bản)
└── installer/     setup-prereqs.ps1 (cài thành phần nền) · README-INSTALLER.md
```

## Chạy & build
- **Chạy tất cả:** `run` (cmd) hoặc `.\run` (PowerShell) tại thư mục Grader_App → MySQL + bot(:8000) + backend(:8080) + frontend(:3000).
- **Backend only:** `cd grader && .\mvnw.cmd spring-boot:run` (cổng khác: `$env:SERVER_PORT=8090`). Phải chạy **trên host**, không trong container (cần mount docker.sock host để chấm).
- **Compile backend:** `cd grader && .\mvnw.cmd -q -o compile` (offline, deps đã cache).
- **Bot:** `cd feedback-bot && .\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8000`.
- **Frontend:** `cd frontend && npm run dev`.
- **Tài khoản test:** `giaovien@fpt.edu.vn` / `123456` (TEACHER), `admin@fpt.edu.vn` / `123456`.

## Luồng dữ liệu cốt lõi
```
Upload ZIP bài nộp → grade trong Docker (grading-base) → result_json lưu DB (LONGTEXT)
result_json = ĐÚNG shape mà bot cần (student/exam/grading_result/test_cases/competency/...)
Trang "Nhận xét AI" → POST /api/feedback/exam/{examId}/{studentId}
   → FeedbackBotClient chuẩn hoá result_json → POST bot :8000/feedback/generate → feedback_text
```
- Backend chỉ nói chuyện `/api` (cổng 8080). Backend mới gọi bot server-to-server (không CORS).
- `result_json` dựng ở `BatchGradingService.assembleResultJson`. Endpoint đọc: `ResultController`.
- Tích hợp bot: `service/FeedbackBotClient.java` + `controller/FeedbackController.java` + `dto/FeedbackRow.java`.

## Gotchas (đã gây lỗi thật — đừng lặp lại)
1. **Gọi bot phải ép HTTP/1.1.** `java.net.http.HttpClient` mặc định HTTP/2; với `http://` cleartext nó thử h2c-upgrade và **làm RỖNG body POST** → FastAPI báo 422 "body required". `FeedbackBotClient` đã `.version(HTTP_1_1)`. Giữ nguyên.
2. **Hai phiên bản Jackson cùng classpath.** Services dùng `com.fasterxml.jackson` (Jackson 2); vài controller dùng `tools.jackson` (Jackson 3, mặc định Spring Boot 4). Khi thêm code, theo file xung quanh. `SyllabusService`/`BatchGradingService` = Jackson 2.
3. **skill_code phải có trong syllabus (bảng `skill`).** AI sinh đề đôi khi BỊA code (vd `DART_ESSENTIALS`). `SyllabusService.sanitizeSkillsMatrix` tự thay code lạ → code hợp lệ cùng tiền tố; được gọi trong `AiExamGenService.saveAsExam`. Upload TAY vẫn validate nghiêm (`ExamService.validateSkillCodes` ném lỗi).
4. **Model bot chậm trên CPU.** Đổi model ở `bot-model.txt` (KHÔNG sửa chỗ khác — `start-all.ps1` ghi `.env` từ đó). CPU-only → dùng `qwen2.5-coder:3b` (~270s/bài). `qwen3:14b` cần GPU. Timeout mặc định đã nới 600s (backend `feedback.timeout-seconds`, bot `OLLAMA_TIMEOUT_SECONDS`).
5. **Windows: `python` là Store-stub** (báo "Python was not found"). Dùng `C:\Python313\python.exe` hoặc `py -3`. Bot venv ở `feedback-bot/.venv`.
6. **Cần Docker bật + image `grading-base:latest`** để chấm và để AI gen compile-fix. Build ảnh: `grader-base/build-base.ps1` (lâu, Flutter SDK).
7. **`grader/secret.properties`** (API key LLM cho "Tạo đề bằng AI") bị gitignore. Người mới: copy từ `.example` + dán key. Chấm + bot nhận xét KHÔNG cần key này.
8. **POST `/api/**` cần token** (AuthFilter), trừ `/api/auth/*`. GET đa phần mở. FE gửi `Authorization: Bearer <token>` (xem `lib/auth.js`).

## API bot (feedback-bot)
- `POST /feedback/generate` body = `FeedbackRequest` (schemas.py) → `FeedbackTextResponse` { student_id, score_summary, feedback_text, teacher_review_required, sources, review_reasons }.
- `teacher_review_required=false` ⇔ có test_cases + total_tests>0 + không có cảnh báo chất lượng dữ liệu + RAG trả context + LLM sinh nội dung thật (KHÔNG fallback). `true` = nên để GV rà lại.
- Sửa prompt: `app/prompt_builder.py`; rule engine: `app/rule_engine.py`; tốc độ/options Ollama: `app/feedback_engine.py`.

## Quy ước khi sửa code
- Comment trong repo bằng tiếng Việt, súc tích, giải thích "tại sao" (theo style sẵn có).
- Sửa xong backend: `mvnw -q -o compile`. Sửa FE: `npx tsc --noEmit`. Đừng tự khởi động lại service đang chạy của user trừ khi cần test.
- Scratchpad/tạm: dùng thư mục scratch của session, KHÔNG rải file tạm vào repo.
