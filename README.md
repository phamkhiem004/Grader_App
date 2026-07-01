# 🎓 Grader App — Hệ thống chấm thi Flutter tự động

Hệ thống chấm bài thi thực hành **Flutter/Dart** tự động trong môi trường **Docker cô lập**. Giáo viên upload hàng loạt bài nộp (file ZIP), hệ thống tự biên dịch, chạy testcase và trả về điểm + log chi tiết cho từng sinh viên. **AI feedback bot** (Ollama, nằm trong `feedback-bot/`) đọc kết quả chấm và viết lời nhận xét cho từng sinh viên.

> **Repo này là MỘT gói hoàn chỉnh**: backend (`grader/`) · frontend (`frontend/`) · AI feedback bot (`feedback-bot/`) · script chạy (`run.cmd`, `start-all.ps1`) · bộ cài (`installer/`). Clone 1 repo là đủ.

---

## 🚀 Chạy nhanh — luồng cho người mới

**Cấu hình cần biết khi clone về:**
- Nhận xét AI local dùng model ghi trong **`bot-model.txt`** (mặc định `qwen3:14b`; máy không có GPU nên đổi sang `qwen2.5-coder:3b` cho nhanh).
- Nếu muốn dùng **Tạo đề bằng AI** hoặc feedback qua OpenAI/Gemini, copy file mẫu `grader/secret.properties.example` thành `grader/secret.properties` rồi dán API key của bạn. File key thật này bị `.gitignore`, không lên GitHub.

### Cách A — máy đã có sẵn Docker + Node + Java + Python + Ollama
```powershell
# 1) Cài model AI (1 lần)
ollama pull qwen3:14b      # hoặc model nhỏ hơn ghi trong bot-model.txt
ollama pull bge-m3         # model embedding cho RAG (bắt buộc)
# 2) Chạy TẤT CẢ bằng 1 lệnh (mở terminal tại thư mục Grader_App)
.\run                      # = run.cmd: bật MySQL + bot + backend + frontend
```
Mở **http://localhost:3000** → đăng nhập → dùng.

### Cách B — máy TRỐNG (chưa có Docker/Node/Java/Python/Ollama)
Sau khi **clone repo**, chạy **`grader-setup.cmd`** (tự xin quyền admin → winget cài Docker/Node/Java/Python/Ollama + tải model + build ảnh chấm `grading-base`). Xong → chạy **`GraderLauncher.exe`** hoặc **`.\run`** ngay trong repo. Chi tiết: [`installer/README-INSTALLER.md`](installer/README-INSTALLER.md).

### Luồng sử dụng đầy đủ (chấm → nhận xét)
1. **Cấu hình Đề thi** → upload ZIP testcase (`exam_test.dart`, `grader.dart`, `skills_matrix.json`).
2. **Chấm bài (Batch)** → nhập mã đề + kéo thả ZIP bài nộp (`MaSV_HoTen.zip`) → chấm.
3. **Nhận xét AI** (sidebar) → nhập mã đề → bấm **“Đọc & nhận xét bài làm”** → AI viết nhận xét từng SV → **Tải Excel (.xls)**.

> ⏱️ **Tốc độ AI**: trên máy CPU‑only, `qwen3:14b` rất chậm (>300s/bài, dễ rơi về nhận xét mẫu). Đã kiểm thử `qwen2.5-coder:3b` cho nhận xét AI thật (~270s/bài). Không có GPU → đổi `bot-model.txt` sang model nhỏ.

### Các file bị `.gitignore` có làm clone về không chạy được không?

Không. Những file bị ignore là dữ liệu phát sinh ở từng máy hoặc chứa secret cá nhân. Repo vẫn commit đầy đủ **file template** và script để tạo lại:

| File/thư mục bị ignore | Lý do không commit | Cách tạo lại sau khi clone |
|---|---|---|
| `grader/secret.properties` | Chứa API key OpenAI/Gemini thật | Copy từ `grader/secret.properties.example`, rồi dán key |
| `feedback-bot/.env` | Cấu hình provider/model feedback theo máy, có thể chứa `OPENAI_API_KEY` thật | `.\run` / `start-all.ps1` tự ghi; chạy bot thủ công thì copy từ `feedback-bot/.env.example` |
| `frontend/.env.local` | URL backend theo cổng máy local | `.\run` / `start-all.ps1` tự ghi; chạy tay thì copy từ `frontend/.env.example` |
| `exams/` | Testcase giáo viên upload khi dùng app | Tự sinh khi cấu hình đề trên UI |
| `submissions/` | ZIP bài nộp sinh viên, dữ liệu nhạy cảm | Tự sinh khi chấm batch |
| `.idea/`, `.claude/`, `.codex/`, `.agents/` | Cấu hình IDE/agent và đường dẫn/quyền local | Không cần để chạy app |

Vì vậy người mới clone repo vẫn chạy được. Nếu họ muốn dùng API trả phí, họ chỉ thêm key vào file local `grader/secret.properties`.

---

## ✨ Tính năng chính

| Nhóm | Tính năng |
|---|---|
| **Chấm bài** | Upload & chấm hàng loạt; hàng đợi 8 worker song song; mỗi bài chạy trong 1 container Docker riêng (`--rm`); tự giới hạn RAM/CPU/timeout |
| **Theo dõi** | Tiến độ real-time (queued / grading / done / error); **không mất kết quả khi rời trang** (tự khôi phục từ backend) |
| **Lịch sử chấm** | Xem lại theo từng đề: danh sách bài + điểm + trạng thái; tải JSON từng bài; **xuất CSV** |
| **Thống kê** | Tổng hợp pass/fail, điểm trung bình, biểu đồ; tối ưu O(log N) bằng Flag Pattern |
| **Tài khoản GV** | Đăng nhập/đăng ký; token 7 ngày; trang hồ sơ + số liệu chấm theo từng giáo viên |
| **Tạo đề bằng AI** | Nhập đề bài → AI sinh testcase + lời giải mẫu, **tự biên dịch trong Docker & sửa** tới khi chạy được, lưu thẳng thành đề (cắm API key Gemini/GPT) |
| **Giao diện** | Sáng/Tối (dark mode); responsive; xuất CSV & JSON (cho AI nhận xét) |

---

## 🏗️ Kiến trúc & Công nghệ

```
┌──────────────┐      REST API       ┌──────────────────┐      docker.sock      ┌─────────────────┐
│   Frontend   │ ─────────────────►  │     Backend      │ ───────────────────►  │  Docker Engine  │
│  Next.js 16  │  ◄───────────────   │  Spring Boot 4   │  ◄──── kết quả JSON   │ grading-base +  │
│  React 19    │                     │  (8 worker pool) │                       │  container/ bài │
└──────────────┘                     └────────┬─────────┘                       └─────────────────┘
                                              │ JPA/Hibernate
                                       ┌──────▼──────┐
                                       │  MySQL 8.0  │
                                       └─────────────┘
```

| Thành phần | Công nghệ |
|---|---|
| **Backend** | Spring Boot 4.0.6 · Java 17 · JPA/Hibernate · MySQL 8.0 |
| **Frontend** | Next.js 16.2.6 · React 19 · Tailwind CSS v4 · Recharts · lucide-react |
| **Chấm bài** | Docker · Flutter SDK (ảnh nền `grading-base`) |

---

## 📋 Yêu cầu hệ thống

- **Docker Desktop** (bật, chia sẻ ổ đĩa chứa project nếu trên Windows)
- **Java 17+** và **Maven** (hoặc dùng `mvnw` kèm sẵn)
- **Node.js 20+** và **npm**
- RAM khuyến nghị ≥ 8GB (mỗi container chấm mặc định 2GB)

---

## 🚀 Cài đặt & Chạy

### Bước 1 — Khởi động MySQL

```bash
docker compose up -d        # chạy MySQL 8.0 (cổng 3306), tự nạp mysql/init.sql
```

### Bước 2 — Build ảnh nền chấm bài (chỉ 1 lần)

```bash
cd grader-base
# Windows
./build-base.ps1
# Linux/Mac
./build-base.sh
# hoặc thủ công:
docker build -f Dockerfile.base -t grading-base:latest .
```
> Ảnh `grading-base` gói sẵn Flutter SDK + package → các lần chấm sau chỉ mount testcase (nhanh vài giây). Xem chi tiết ở mục [Docker image & container](#-docker-image--container).

### Bước 3 — Chạy Backend (khuyến nghị chạy trên host)

```bash
cd grader
./mvnw spring-boot:run        # Windows: .\mvnw spring-boot:run
```
Backend chạy ở **http://localhost:8080**. Lần đầu sẽ tự seed 2 tài khoản mẫu (xem bên dưới).

### Bước 4 — Chạy Frontend

```bash
cd frontend
npm install
npm run dev
```
Mở **http://localhost:3000** → đăng nhập → bắt đầu chấm.

Nếu chạy bằng `.\run` thì script tự tạo `frontend/.env.local` đúng cổng backend. Nếu chạy thủ công và muốn cấu hình riêng, copy:

```powershell
Copy-Item frontend/.env.example frontend/.env.local
notepad frontend/.env.local
```

> 💡 **Triển khai trọn gói trên 1 máy Linux**: `docker compose --profile full up -d --build` (bật cả service backend).

### Bước 5 — (Tuỳ chọn) Bật **Tạo đề bằng AI** · tạo file `secret.properties`

Tính năng *Tạo đề bằng AI* cần **API key** của một nhà cung cấp LLM (OpenAI/GPT hoặc Google/Gemini).
Key được đặt ở file **`grader/secret.properties`** — file này **đã được `.gitignore`** nên **KHÔNG bao giờ
bị commit lên GitHub**. `application.properties` tự nạp nó lúc khởi động (`spring.config.import`).

```powershell
# 1) Tạo file secret từ template (chạy ở thư mục gốc repo)
Copy-Item grader/secret.properties.example grader/secret.properties

# 2) Mở rồi DÁN API KEY của bạn vào
notepad grader/secret.properties
```

Nội dung `grader/secret.properties` cần điền:

```properties
# Chọn nhà cung cấp: openai (GPT) | gemini (Google)
grader.ai.provider=openai
# DÁN KEY THẬT vào đây (file này không bị commit):
grader.ai.openai.api-key=sk-...key-cua-ban...
# Nếu dùng Gemini:
#grader.ai.provider=gemini
#grader.ai.gemini.api-key=...key-gemini...
```

- Lấy key **OpenAI (GPT)**: https://platform.openai.com/api-keys
- Lấy key **Gemini (Google, có free tier)**: https://aistudio.google.com/app/apikey

Khởi động lại backend → vào trang **Tạo đề bằng AI** ở sidebar. Nếu **không** tạo file / để trống key ⇒
tính năng tắt (trang báo *"Chưa cắm API key"*), mọi phần khác vẫn chạy bình thường.

> 🔒 **Tuyệt đối KHÔNG** dán key vào `application.properties` (file đó được commit lên git). Lỡ commit key →
> **revoke key ngay** ở trang nhà cung cấp rồi tạo key mới. Thay vì file, có thể dùng biến môi trường:
> `$env:GRADER_AI_OPENAI_API_KEY = "sk-..."` (Spring tự map sang `grader.ai.openai.api-key`).
>
> 🧩 Dùng được **model open-source/local**: đặt `provider=openai` rồi trỏ `grader.ai.openai.base-url` tới
> endpoint tương thích OpenAI (Ollama `http://localhost:11434/v1`, vLLM, OpenRouter, Groq...).
> Chi tiết kiến trúc + vòng compile-fix: [`docs/ai-generator.md`](docs/ai-generator.md).

**Mỗi người clone/pull code về** chỉ cần lặp lại Bước 5 (copy `.example` → dán key của mình → chạy) —
không ai thấy key của ai vì `secret.properties` không nằm trong git.

### Dùng OpenAI cho feedback bot thay vì Ollama local

Mặc định feedback bot chạy Ollama local. Nếu muốn nhận xét nhanh hơn bằng OpenAI:

1. Điền `grader.ai.openai.api-key` trong `grader/secret.properties`.
2. Sửa dòng model trong `bot-model.txt` thành dạng:

```text
openai:gpt-4o-mini
```

3. Chạy lại `.\run`.

`start-all.ps1` sẽ đọc key từ `grader/secret.properties` và tự ghi `feedback-bot/.env`. Không cần commit `.env`.

---

## 📖 Hướng dẫn sử dụng

### 1. Cấu hình đề thi (trang **Cấu hình Đề thi**)
Upload file ZIP testcase chứa: `exam_test.dart`, `grader.dart`, `skills_matrix.json`.

### 2. Chấm bài (trang **Chấm bài (Batch)**)
- Nhập **mã đề** → kéo thả các file ZIP bài nộp.
- Tên file phải đúng định dạng: **`MaSV_HoTen.zip`** (vd `HE123456_Nguyen_Van_A.zip`).
- Bấm **Bắt đầu chấm** → theo dõi tiến độ real-time.
- Rời trang rồi quay lại **vẫn còn kết quả** (tự tải lại từ server).
- Tải **CSV** (bảng điểm) hoặc **JSON** (đầy đủ, cho AI nhận xét).

### 3. Xem lịch sử (trang **Lịch sử chấm**)
- Chọn đề ở cột trái → xem toàn bộ bài đã chấm (điểm, trạng thái, thời gian).
- Tìm theo mã SV / tên; tải **JSON** từng bài; **xuất CSV** cả đề.

### 4. Thống kê (trang **Thống kê**)
Chọn đề (hoặc tất cả) để xem pass/fail, điểm trung bình, phân bố điểm.

### 5. Tạo đề bằng AI (trang **Tạo đề bằng AI**) — tuỳ chọn
1. Nhập **mã đề** + **đề bài/ý tưởng** (ghi rõ tên file/class/hàm + text UI càng tốt), số testcase, độ khó.
2. Bấm **Tạo đề**, AI chạy **2 pha** (xem tiến trình từng vòng trực tiếp):
   - **Pha A** — sinh `exam_test.dart` + `skills_matrix.json` + lời giải mẫu, **tự biên dịch trong Docker và
     sửa** tới khi mọi testcase PASS (tối đa hoá số testcase, phủ syllabus, trộn độ khó để chia điểm hợp lý).
   - **Pha B** — từ testcase đã chốt, sinh **đề bài** (phát SV) + **khung code starter** và kiểm tra khung **biên dịch sạch**.
3. **Xem trước** → **Lưu thành đề** (vào *Kho đề thi* để chấm). Khu **Tải về** có 3 nút ZIP:
   **testcase** (3 file để upload đề) · **đề bài + khung code** (phát SV: `de_bai.md` + `lib/`) · **tất cả**.
   *Lời giải mẫu* chỉ để kiểm thử, không phát cho SV.

> Cần cắm API key trước (xem **Bước 5** ở mục Cài đặt). `grader.dart` luôn là bản chuẩn của hệ thống
> (AI không sinh phần chấm) và đề chỉ được lưu khi lời giải mẫu PASS hết → đề tạo ra an toàn, đúng hợp đồng.

---

## ⚙️ Cấu hình (biến môi trường)

Tất cả có giá trị mặc định — chỉ ghi đè khi cần.

### Backend (`grader`)

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/chamthi_db` | Kết nối MySQL |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `root` / `123456` | Tài khoản DB |
| `GRADER_MAX_CONCURRENT` | `8` | Số bài chấm song song (= số container đồng thời) |
| `GRADER_TIMEOUT_SECONDS` | `240` | Timeout mỗi bài (giây) |
| `GRADER_RUN_MEMORY` | `2048m` | RAM mỗi container chấm |
| `GRADER_RUN_CPUS` | `2.0` | Số CPU mỗi container chấm |
| `GRADER_BASE_IMAGE` | `grading-base:latest` | Tên ảnh nền |
| `GRADER_SAVE_SUBMISSIONS` | `true` | Lưu file ZIP để audit/chấm lại |
| `GRADER_SUBMISSIONS_RETENTION_DAYS` | `30` | Số ngày giữ bài nộp (≤0 = giữ mãi) |
| `GRADER_PASS_THRESHOLD` | `5` | Ngưỡng điểm đạt (hệ 10) |

> ⚠️ **Máy yếu/ít RAM**: giảm `GRADER_MAX_CONCURRENT` và/hoặc `GRADER_RUN_MEMORY`.
> Tối đa RAM ước tính = `MAX_CONCURRENT × RUN_MEMORY` (mặc định 8 × 2GB = 16GB).

### Frontend (`frontend/.env.local`)

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `NEXT_PUBLIC_API_BASE` | `http://localhost:8080/api` | URL backend |
| `NEXT_PUBLIC_DEFAULT_EXAM_ID` | `FLUTTER_PE_01` | Mã đề gợi ý sẵn |
| `NEXT_PUBLIC_PASS_THRESHOLD` | `5` | Ngưỡng điểm đạt |

---

## 🔌 API chính

| Method | Endpoint | Mô tả |
|---|---|---|
| `POST` | `/api/auth/login` · `/register` · `/logout` · `/me` | Xác thực giáo viên |
| `POST` | `/api/exam-setup/upload-testcase` | Cấu hình đề (upload testcase) |
| `GET` | `/api/exam-setup/status/{examId}` | Trạng thái đề |
| `POST` | `/api/batch/upload` | Upload & chấm hàng loạt |
| `GET` | `/api/batch/progress/{batchId}` | Tiến độ 1 phiên chấm |
| `GET` | `/api/results/exam/{examId}` | **Lịch sử chấm theo đề** (danh sách bài) |
| `GET` | `/api/results/{examId}/{studentId}` | JSON đầy đủ 1 bài |
| `GET` | `/api/results/batch/{batchId}` | JSON đầy đủ cả phiên chấm |
| `GET` | `/api/statistics` · `/statistics/exams` | Thống kê & danh sách đề đã chấm |
| `GET` | `/api/ai-generator/status` · `/job/{id}` | Trạng thái AI + tiến trình job sinh đề |
| `POST` | `/api/ai-generator/generate` · `/save` | Tạo đề bằng AI (sinh + lưu thành đề) |

---

## 🐳 Docker image & container

### Ảnh nền `grading-base` (build 1 lần)
`grader-base/Dockerfile.base` — gói sẵn Flutter SDK, package, script chấm. Mọi đề thi dùng chung ảnh này, **không** build ảnh riêng cho từng đề → upload nhanh, không tích tụ image.

### Container chấm (mỗi bài 1 container, tự xóa)
Khi chấm 1 bài, backend chạy:
```bash
docker run --rm --memory 2048m --cpus 2.0 \
  -v <bài-nộp>/lib:/app/lib \      # code sinh viên
  -v <testcase>:/app/test \         # testcase của đề (mount mode)
  grading-base:latest
```
→ container chạy `run_grader.sh` (flutter test) → trả JSON kết quả → tự xóa (`--rm`).

**Lợi ích**: cô lập (lỗi 1 bài không ảnh hưởng bài khác) · nhanh · sạch · kiểm soát tài nguyên.

---

## 📁 Cấu trúc thư mục

```
Grader_App/
├── grader/                 # Backend Spring Boot
│   ├── secret.properties.example  # Mẫu cắm API key AI (copy → secret.properties; đã .gitignore)
│   └── src/main/java/com/example/grader/
│       ├── controller/     # REST API (Auth, Batch, Result, ExamSetup, Statistics, AiGenerator)
│       ├── service/        # BatchGradingService (hàng đợi), GradingService (docker), AuthService
│       │   └── ai/         # Tạo đề bằng AI: LlmClient (Gemini/GPT), compile-fix loop
│       ├── entity/         # Exam, ExamResult, GradingBatch, Teacher
│       └── repository/     # JPA repositories
├── frontend/               # Next.js
│   └── app/
│       ├── page.jsx        # Chấm bài (dashboard)
│       ├── history/        # Lịch sử chấm
│       ├── statistics/     # Thống kê
│       ├── teacher/        # Cấu hình đề, Kho đề, Tạo đề bằng AI (ai-generator), Thư viện chấm
│       ├── login·register·profile/
│       └── components/     # SidebarLayout, AuthProvider
├── grader-base/            # Dockerfile.base + script chấm + pubspec base
├── exams/                  # Testcase từng đề (mount lúc chấm)
├── submissions/            # File ZIP bài nộp đã lưu (audit)
├── mysql/init.sql          # Khởi tạo schema + bảng teachers
└── docker-compose.yml      # MySQL (+ backend khi --profile full)
```

---

## 🔧 Xử lý sự cố thường gặp

| Triệu chứng | Nguyên nhân & cách xử lý |
|---|---|
| Chấm bị kẹt `QUEUED` mãi | Đã fix (worker không chết). Nếu còn → restart backend, `recoverPendingJobs` tự nạp lại hàng đợi |
| `image not found: grading-base` | Chưa build ảnh nền → chạy `grader-base/build-base.ps1` |
| Maven báo `No compiler is provided` | Máy đang dùng JRE, thiếu JDK. Chạy lại `grader-setup.cmd`; script sẽ cài/tìm Temurin JDK 17 và set `JAVA_HOME` cho backend |
| Docker build báo `failed to compute cache key` / `input/output error` | Lỗi storage/cache của Docker Desktop/WSL hoặc ổ Docker thiếu dung lượng. Chạy lại `grader-setup.cmd`; `build-base.ps1` sẽ prune cache, retry `--no-cache`, restart Docker/WSL rồi retry lần cuối |
| Bài báo `0/0 — không chạy được testcase` | Bài nộp sai tên class/thiếu file so với đề, hoặc lỗi biên dịch |
| `Sai format — cần MaSV_Ten.zip` | Đổi tên file ZIP đúng định dạng `MaSV_HoTen.zip` |
| Hết RAM khi chấm nhiều | Giảm `GRADER_MAX_CONCURRENT` hoặc `GRADER_RUN_MEMORY` |
| Mất kết nối DB | Kiểm tra `docker compose ps`, cổng 3306, biến `SPRING_DATASOURCE_*` |

---

## 📝 Ghi chú

- Backend **khuyến nghị chạy trên host** (không trong container) vì cần mount đường dẫn host thật vào Docker để chấm.
- Bài nộp được lưu tại `submissions/` để audit & chấm lại; tự dọn sau `RETENTION_DAYS` ngày.
- Chấm lại cùng (SV + đề) sẽ **ghi đè** kết quả cũ.
