# CLAUDE.md — Grader App (chấm thi Flutter)

Hướng dẫn cho Claude Code khi làm việc trong repo này. Đọc kỹ phần **Gotchas** — nhiều thứ
khác với mặc định và đã từng gây lỗi thật.

## Repo này là MỘT gói hoàn chỉnh
```
Grader_App/                  ← repo duy nhất (clone 1 cái là đủ)
├── grader/        Backend Spring Boot 4 · Java 17 · MySQL · gọi Docker để chấm
├── frontend/      Next.js 16 · React 19 · Tailwind v4  (ĐỌC frontend/AGENTS.md trước khi sửa FE)
├── grader-base/   Dockerfile ảnh nền chấm (Flutter SDK) → image `grading-base:latest`
├── exams/  submissions/   dữ liệu runtime (gitignore, rỗng khi mới clone)
├── grader-setup.cmd  ← MÁY TRỐNG: cài Docker/Node/Java + build ảnh (gọi installer/setup-prereqs.ps1, tự UAC)
├── run.cmd · start-all.ps1 · GraderLauncher.exe  ← chạy tất cả (chạy NGAY trong repo, không nhân bản)
└── installer/     setup-prereqs.ps1 (cài thành phần nền) · README-INSTALLER.md
```

## Chạy & build
- **Chạy tất cả:** `run` (cmd) hoặc `.\run` (PowerShell) tại thư mục Grader_App → MySQL + backend(:8080) + frontend(:3000).
- **Backend only:** `cd grader && .\mvnw.cmd spring-boot:run` (cổng khác: `$env:SERVER_PORT=8090`). Phải chạy **trên host**, không trong container (cần mount docker.sock host để chấm).
- **Compile backend:** `cd grader && .\mvnw.cmd -q -o compile` (offline, deps đã cache).
- **Frontend:** `cd frontend && npm run dev`.
- **Tài khoản test:** `giaovien@fpt.edu.vn` / `123456` (TEACHER), `admin@fpt.edu.vn` / `123456`.

## Luồng dữ liệu cốt lõi
```
Upload ZIP bài nộp → grade trong Docker (grading-base) → result_json lưu DB (LONGTEXT)
result_json = ĐÚNG shape cho API (student/exam/grading_result/test_cases/competency/...)
Trang "Lịch sử" → GET /api/exam/{examId}/results
```
- Backend chỉ nói chuyện `/api` (cổng 8080).
- `result_json` dựng ở `BatchGradingService.assembleResultJson`. Endpoint đọc: `ResultController`.

## Gotchas (đã gây lỗi thật — đừng lặp lại)
1. **Hai phiên bản Jackson cùng classpath.** Services dùng `com.fasterxml.jackson` (Jackson 2); vài controller dùng `tools.jackson` (Jackson 3, mặc định Spring Boot 4). Khi thêm code, theo file xung quanh. `SyllabusService`/`BatchGradingService` = Jackson 2.
2. **skill_code phải có trong syllabus (bảng `skill`).** Upload testcase validate nghiêm (`ExamService.validateSkillCodes` ném lỗi) để giữ taxonomy sạch.
3. **Cần Docker bật + image `grading-base:latest`** để chấm. Build ảnh: `grader-base/build-base.ps1` (lâu, Flutter SDK).
4. **POST `/api/**` cần token** (AuthFilter), trừ `/api/auth/*`. GET đa phần mở. FE gửi `Authorization: Bearer <token>` (xem `lib/auth.js`).

## Quy ước khi sửa code
- Comment trong repo bằng tiếng Việt, súc tích, giải thích "tại sao" (theo style sẵn có).
- Sửa xong backend: `mvnw -q -o compile`. Sửa FE: `npx tsc --noEmit`. Đừng tự khởi động lại service đang chạy của user trừ khi cần test.
- Scratchpad/tạm: dùng thư mục scratch của session, KHÔNG rải file tạm vào repo.
