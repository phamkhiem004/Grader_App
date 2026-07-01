## 📋 Hướng dẫn Contribute vào Grader App

Cảm ơn bạn vì muốn đóng góp vào dự án! Hướng dẫn này sẽ giúp bạn bắt đầu.

---

## 🚀 Bước 1: Chuẩn bị môi trường

### Clone repo
```bash
git clone https://github.com/phamkhiem004/Grader_App.git
cd Grader_App
```

### Yêu cầu hệ thống
- **Docker Desktop** (bật, chia sẻ ổ đĩa trên Windows)
- **Java 17+** & **Maven** (dùng `mvnw` có sẵn hoặc cài riêng)
- **Node.js 20+** & **npm**
- **Python 3.9+** & **pip** (cho feedback bot)
- **Ollama** (cho feedback bot - tuỳ chọn)
- RAM ≥ 8GB

### Cài đặt backend
```bash
cd grader
./mvnw clean install      # Windows: .\mvnw clean install
```

### Cài đặt frontend
```bash
cd ../frontend
npm install
```

### Cài đặt feedback bot
```bash
cd ../feedback-bot
python -m venv .venv
# Windows
.\.venv\Scripts\Activate.ps1
# Linux/Mac
source .venv/bin/activate
pip install -r requirements.txt
```

### Khởi động MySQL
```bash
docker compose up -d
```

---

## 🌿 Bước 2: Tạo branch

Luôn tạo branch mới cho từng tính năng/bug fix:

```bash
git checkout -b feature/tên-tính-năng
# hoặc
git checkout -b bugfix/mô-tả-bug
# hoặc
git checkout -b docs/cập-nhật-docs
```

**Quy ước tên branch:**
- `feature/` — tính năng mới (vd: `feature/add-export-excel`)
- `bugfix/` — sửa bug (vd: `bugfix/fix-grading-timeout`)
- `docs/` — cập nhật tài liệu (vd: `docs/update-api-readme`)
- `chore/` — cập nhật dependencies, cấu hình (vd: `chore/upgrade-spring-boot`)
- `refactor/` — tái cấu trúc code (vd: `refactor/simplify-batch-service`)

---

## 💻 Bước 3: Phát triển & Kiểm tra

### Backend (Spring Boot)

```bash
cd grader
./mvnw spring-boot:run
```

Backend chạy ở **http://localhost:8080**

**Quy tắc code:**
- Sử dụng **Java 17+** features (records, sealed classes, text blocks)
- Tuân theo **Spring Boot best practices**
- Viết **unit tests** cho logic mới (JUnit 5)
- Sử dụng **Lombok** để giảm boilerplate
- Comment bằng **Vietnamese** cho những phần phức tạp

**Cấu trúc package:**
```
com.example.grader
├── controller/      # REST endpoints
├── service/         # Business logic
│   └── ai/         # AI-related services
├── entity/         # JPA entities
├── repository/     # Data access (JPA)
├── dto/            # Data transfer objects
├── util/           # Utilities
└── config/         # Spring configuration
```

**Chạy tests:**
```bash
./mvnw test
```

### Frontend (Next.js + React)

```bash
cd frontend
npm run dev
```

Frontend chạy ở **http://localhost:3000**

**Quy tắc code:**
- Sử dụng **TypeScript** cho tất cả component mới
- Tuân theo **React hooks** (không class components)
- Sử dụng **Tailwind CSS** cho styling
- Component phải **responsive** (mobile-first)
- Viết **comments** cho phần logic phức tạp

**Cấu trúc:**
```
app/
├── (auth)/         # Login, register, profile
├── page.jsx        # Dashboard chính
├── history/        # Trang lịch sử chấm
├── statistics/     # Trang thống kê
├── teacher/        # Trang cấu hình (setup, kho đề, AI generator)
├── api/            # Server components/actions
└── components/     # Reusable UI components
```

**Kiểm tra linting:**
```bash
npm run lint
```

### Feedback Bot (FastAPI + Python)

```bash
cd feedback-bot
source .venv/bin/activate  # hoặc .\.venv\Scripts\Activate.ps1 trên Windows
uvicorn app.main:app --reload
```

API chạy ở **http://localhost:8000**

**Quy tắc code:**
- Sử dụng **Python 3.9+** type hints
- Validate input với **Pydantic**
- Viết **docstrings** cho tất cả functions
- Tuân theo **PEP 8**

**Chạy tests:**
```bash
pytest
```

---

## 🔍 Bước 4: Kiểm tra toàn diện

### Trước khi commit

1. **Chạy tests** của thành phần bạn sửa:
   ```bash
   # Backend
   cd grader && ./mvnw test
   
   # Frontend
   cd frontend && npm run lint
   
   # Bot
   cd feedback-bot && pytest
   ```

2. **Kiểm tra linting & formatting**:
   - Backend: Maven plugins
   - Frontend: `npm run lint`
   - Python: `black` (tuỳ chọn)

3. **Kiểm tra thủ công** — mở ứng dụng trên **http://localhost:3000** rồi test:
   - Login/register
   - Upload testcase
   - Chấm bài
   - Xem lịch sử & thống kê
   - Tính năng AI (nếu có API key)

### Docker image
Nếu sửa thứ gì trong `grader-base/`:
```bash
cd grader-base
./build-base.ps1        # Windows
# hoặc
./build-base.sh         # Linux/Mac
```

---

## 📝 Bước 5: Commit & Push

### Viết commit message tốt

**Format:**
```
<type>(<scope>): <subject>

<body>

<footer>
```

**Ví dụ:**
```
feat(batch-grading): add timeout configuration

- Add GRADER_TIMEOUT_SECONDS env variable
- Default timeout is 240 seconds
- Can be overridden per batch via UI

Closes #42
```

**Types:**
- `feat` — tính năng mới
- `fix` — sửa bug
- `docs` — cập nhật documentation
- `style` — cấu hình, whitespace (không ảnh hưởng logic)
- `refactor` — tái cấu trúc code
- `perf` — cải thiện performance
- `test` — thêm/sửa tests
- `chore` — dependencies, build scripts

**Scopes chính:**
- `auth` — xác thực
- `batch-grading` — hàng đợi chấm
- `exam-setup` — cấu hình đề
- `statistics` — thống kê
- `ai-generator` — tạo đề bằng AI
- `feedback-bot` — bot nhận xét
- `frontend` — giao diện
- `docker` — Docker setup
- `docs` — tài liệu

### Commit & push
```bash
git add .
git commit -m "feat(batch-grading): add timeout config"
git push origin feature/tên-tính-năng
```

---

## 🔐 Bước 6: Security & Secrets

### KHÔNG BẰNG HỌ:
- ❌ Commit `grader/secret.properties` (chứa API keys)
- ❌ Commit `.env` hoặc `.env.local` (chứa credentials)
- ❌ Commit files trong `submissions/` hoặc `exams/` (dữ liệu cá nhân)
- ❌ Commit IDE config (`.idea/`, `.vscode/`, `.codex/`)

### LÀM:
- ✅ Cập nhật `.gitignore` khi thêm file config mới
- ✅ Dùng file `.example` làm template
- ✅ Hướng dẫn contributor copy `.example` → `.local` rồi dán key
- ✅ Validate user input (xóa phần script, SQL injection)

### API Keys & Credentials
Nếu cần test với API key:
1. Copy file mẫu (vd: `grader/secret.properties.example`)
2. Tạo file local: `grader/secret.properties`
3. Dán key của bạn vào file local
4. File `.gitignore` đã exclude nó — không bị commit

---

## 🧪 Bước 7: Pull Request

### Tạo PR
Tên PR nên rõ ràng:
```
[feature] Add timeout configuration for batch grading
[bugfix] Fix student name encoding in CSV export
[docs] Update API documentation
```

### Mô tả PR phải chứa:
```markdown
## 📝 Mô tả
Đây là tính năng gì / bug gì được sửa.

## 🎯 Liên quan đến
- Closes #42 (nếu fix issue)
- Related to #35

## 🔄 Cách test
1. Login vào http://localhost:3000
2. Upload testcase
3. Check output ở [chi tiết hơn]

## ✅ Checklist
- [ ] Tests pass (`./mvnw test`, `npm run lint`)
- [ ] Không có console errors
- [ ] Cập nhật README nếu cần
- [ ] Không commit secrets
- [ ] Code tuân theo style guide

## 📸 Screenshots (nếu có)
[Paste screenshots hoặc GIFs]
```

### Yêu cầu PR
- Tối thiểu 1 approval từ maintainer
- Tất cả CI checks (tests) pass
- Không có merge conflicts
- Commit messages rõ ràng

---

## 📚 Cấu trúc code đã được thiết lập

### Backend (`grader/`)
```
src/main/java/com/example/grader/
├── controller/
│   ├── AuthController.java
│   ├── BatchGradingController.java
│   ├── ExamSetupController.java
│   ├── ResultController.java
│   ├── StatisticsController.java
│   └── AiGeneratorController.java
├── service/
│   ├── AuthService.java
│   ├── BatchGradingService.java          # Hàng đợi + workers
│   ├── GradingService.java               # Docker executor
│   ├── AiGeneratorService.java
│   └── ai/
│       ├── LlmClient.java                # OpenAI/Gemini client
│       └── CompileFixLoop.java
├── entity/
│   ├── Teacher.java
│   ├── Exam.java
│   ├── ExamResult.java
│   ├── GradingBatch.java
│   └── GradingBatchItem.java
└── repository/
    ├── TeacherRepository.java
    ├── ExamRepository.java
    ├── ExamResultRepository.java
    └── GradingBatchRepository.java
```

### Frontend (`frontend/`)
- Next.js 16 + React 19 (app directory)
- Tailwind CSS v4
- Recharts (biểu đồ)
- TypeScript

### Python Bot (`feedback-bot/`)
- FastAPI (async API)
- Ollama (local LLM)
- ChromaDB (RAG - retrieval-augmented generation)
- Pydantic (validation)

---

## 🤝 Code Review Guidelines

### Khi review code:
- ✅ Kiểm tra **logic** — có chính xác không?
- ✅ Kiểm tra **performance** — có optimize không?
- ✅ Kiểm tra **security** — có lỗ hổng không?
- ✅ Kiểm tra **style** — tuân theo convention không?
- ✅ Kiểm tra **tests** — có cover được không?

### Comment constructive:
```
❌ Tệ:  "Đây là cách viết sai"
✅ Tốt: "Đề xuất dùng `List.of()` thay vì `new ArrayList<>()`
         vì tạo immutable list hiệu quả hơn."
```

---

## 🐛 Báo cáo Bug

### Tạo GitHub Issue với template:
```markdown
## 🐛 Mô tả Bug
[Mô tả ngắn gọn]

## 🔄 Cách Reproduce
1. Vào http://localhost:3000
2. Chọn [action]
3. Kết quả: [what happened]

## ✅ Mong đợi
[What should happen]

## 📸 Screenshots/Logs
[Paste error log hoặc screenshot]

## 💻 Môi trường
- OS: Windows / Mac / Linux
- RAM: 
- Docker version:
- Java version:
- Node version:
```

---

## 📞 Liên hệ & Hỗ trợ

- **Issues**: Mở GitHub Issues cho bug/feature request
- **Discussions**: Sử dụng GitHub Discussions cho câu hỏi chung
- **Email**: [Thêm email maintainer nếu có]

---

## 📄 License

Khi contribute, bạn đồng ý code của bạn tuân theo license của project (xem `LICENSE` file).

---

## 🙏 Cảm ơn

Cảm ơn bạn đã contribute! Mọi đóng góp, dù lớn hay nhỏ, đều giúp dự án phát triển.

**Happy coding! 🚀**
