# 🪄 Tạo đề bằng AI — khung tích hợp

Trang **Tạo đề bằng AI** (`/teacher/ai-generator`) để AI tự sinh `exam_test.dart` + `skills_matrix.json`
+ **lời giải mẫu**, rồi **tự biên dịch trong Docker và sửa** cho tới khi testcase chạy được — trước khi
giảng viên lưu thành đề. Đây là **khung (scaffold)**: chỉ cần **cắm API key** là chạy.

---

## 1. Cắm API key (chỗ để trống)

Mở `grader/src/main/resources/application.properties`:

```properties
grader.ai.enabled=true
grader.ai.provider=gemini          # gemini | openai

# Gemini (Google) — key ở https://aistudio.google.com/app/apikey
grader.ai.gemini.api-key=          # ← ĐIỀN VÀO ĐÂY
grader.ai.gemini.model=gemini-2.0-flash

# OpenAI (GPT) — key ở https://platform.openai.com/api-keys
grader.ai.openai.api-key=          # ← hoặc dùng cái này (đổi provider=openai)
grader.ai.openai.model=gpt-4o-mini
```

> Để **trống** key ⇒ nút Tạo đề vẫn bấm được nhưng job báo `NO_API_KEY` (đúng tinh thần "khung").

### 🔐 CHỖ DÁN KEY — `grader/secret.properties` (không bao giờ commit)
`application.properties` **được git theo dõi** → KHÔNG dán key vào đó. Key đặt ở **`grader/secret.properties`**
đã được `.gitignore`; `application.properties` tự nạp nó qua:
```properties
spring.config.import=optional:file:secret.properties,optional:file:grader/secret.properties
```
`optional:` = thiếu file vẫn chạy (báo `NO_API_KEY`). Giá trị trong `secret.properties` **đè** `application.properties`.

**Quy trình cho mỗi người sau khi clone/pull:**
```powershell
Copy-Item grader/secret.properties.example grader/secret.properties   # 1) tạo file từ mẫu
notepad grader/secret.properties                                       # 2) dán API key của mình vào
cd grader; .\mvnw spring-boot:run                                      # 3) chạy bình thường
```
Chỉ `secret.properties.example` (template, KHÔNG có key) được commit → người khác pull về, copy, dán key, chạy.
File `secret.properties` chứa key thật **không bao giờ lên GitHub**.

**Cách thay thế — biến môi trường** (không tạo file): Spring map `GRADER_AI_OPENAI_API_KEY` → `grader.ai.openai.api-key`:
```powershell
$env:GRADER_AI_PROVIDER = "openai"; $env:GRADER_AI_OPENAI_API_KEY = "sk-..."
cd grader; .\mvnw spring-boot:run
```

> ⚠️ Key đã từng dán vào terminal/commit/chat = đã LỘ → **revoke ngay** ở trang nhà cung cấp, tạo key mới.
> Xóa khỏi commit sau KHÔNG cứu được (đã nằm trong lịch sử git + bị bot quét trong vài phút).

### Dùng model open-source / local
`OpenAiClient` gọi chuẩn **Chat Completions**, nên trỏ `grader.ai.openai.base-url` tới bất kỳ endpoint
tương thích OpenAI là chạy: **Ollama** (`http://localhost:11434/v1`), **vLLM**, **OpenRouter**, **Groq**,
Azure OpenAI… Đặt `provider=openai`, điền `base-url` + `model` tương ứng (key có thể là chuỗi bất kỳ với Ollama).

---

## 2. Luồng hoạt động (2 pha, mỗi pha một vòng compile-fix)

Tách 2 pha để KHÔNG nhồi mọi thứ vào 1 response (tránh bị giới hạn token → sinh ít testcase),
và để mỗi pha tự kiểm thử riêng:

```
Wizard (cấu hình) ─POST /generate→ JOB chạy nền (poll GET /job/{id} xem tiến trình live)
  ▼
PHA A — TESTCASE (SINH THEO LÔ: tối đa hóa số test + phủ syllabus + trộn độ khó)
 [Lô 1] LLM(JSON) ──► exam_test + skills_matrix + solution(lib)   (+ backend CHÈN grader.dart chuẩn)
        TestcaseCompiler.checkSolution: solution→/app/lib, test→/app/test → flutter test
        ├─ lỗi / còn FAIL ──► feed lỗi cho LLM ──► sửa ──► lặp
        └─ biên dịch sạch + PASS HẾT:
              ├─ đã đạt mục tiêu (count ≥ target) ──► sang Pha B
              └─ chưa đủ ──► "THÊM batch-size test nữa, phủ skill chưa có, GỘP & giữ test cũ" ──► [Lô kế]
   (mỗi lô response nhỏ → KHÔNG bị cắt do giới hạn token; trần max-batch-rounds vòng)
  ▼
PHA B — BỘ PHÁT SV (từ exam_test đã chốt)
 [Vòng 1..3] LLM(JSON) ──► de_bai (đề bài) + starter (khung lib/)
        TestcaseCompiler.checkStarter: starter→/app/lib → flutter test (chỉ cần BIÊN DỊCH SẠCH)
        ├─ starter compile OK ──► SUCCESS
        └─ lỗi compile ──► feed lỗi ──► sửa ──► lặp
  ▼
 Xem trước: đề bài + exam_test/skills_matrix/grader + starter + solution
   ├─ POST /save → zip 3 file testcase → pipeline upload-testcase (validate) → ĐỀ (chấm được)
   │             → đồng thời ghi de_bai.md + starter/lib/ vào <exams>/<id>/handout/ (KHÔNG mount khi chấm)
   └─ Tải/Copy: đề bài (.md) phát SV · starter (nén lib/ gửi SV làm)
```

Điểm mấu chốt: **AI tự kiểm thử bằng chính ảnh nền chấm**:
- Testcase đảm bảo *biên dịch được + mọi test PASS với lời giải đúng* → khớp 100% hợp đồng bộ chấm.
- **Khung code (starter)** được kiểm tra *biên dịch sạch* → SV không bị 0 điểm oan vì khung lỗi.

`grader.dart` luôn là **bản chuẩn của backend** (không để LLM sinh) → loại rủi ro AI viết sai bộ chấm.
`de_bai` (đề bài) + `starter` (khung) là **tài liệu PHÁT cho SV**; `solution` chỉ để verify. Cả 3 **không**
nằm trong **bộ chấm** (đề chấm chỉ gồm 3 file testcase được mount). Khi lưu: `de_bai.md` + `starter/lib/`
được ghi riêng vào `<exams>/<id>/handout/` (không mount lúc chấm) để **Kho đề** tải lại sau — tải qua
`GET /api/exam-setup/{id}/download/{de-bai|exam-test|starter}`; `solution` vẫn KHÔNG lưu.

---

## 3. Thành phần (khung đã dựng)

| Lớp | Vai trò |
|---|---|
| `service/ai/LlmClient` (interface) | Hợp đồng gọi LLM; `isConfigured()` = đã cắm key chưa |
| `GeminiClient`, `OpenAiClient` | 2 provider (java.net.http, không SDK ngoài); ép JSON-mode |
| `LlmService` | Router chọn provider theo `grader.ai.provider` |
| `PromptBuilder` | Prompt PHA A (testcase: hợp đồng + syllabus + ép nhiều test/phủ/độ khó) · PHA B (đề bài + starter) · feed lỗi |
| `ArtifactParser` | `parse` (PHA A: exam_test/skills_matrix/solution) · `parseHandout` (PHA B: de_bai/starter) |
| `TestcaseCompiler` | `checkSolution` (compile + PASS hết) · `checkStarter` (chỉ cần compile sạch) trong ảnh nền |
| `AiExamGenService` | 2 pha: A=testcase (compile-fix) → B=đề bài + khung; job chạy nền để wizard poll |
| `AiGeneratorController` | `/status` · `/generate` · `/job/{id}` · `/save` |
| `resources/ai/grader.dart` | grader.dart CHUẨN backend chèn |
| `app/teacher/ai-generator/page.tsx` | Wizard 3 bước + timeline 2 pha live + đề bài/starter (copy/tải) |

---

## 4. Open-source free vs GPT/Gemini — chọn cái nào?

| Tiêu chí | Open-source local (Ollama: Qwen2.5-Coder, Llama 3.x) | GPT‑4o‑mini / Gemini 2.0 Flash (API) |
|---|---|---|
| Chi phí | **0đ** (chạy máy mình) | Trả theo token (rẻ với bản mini/flash) |
| Chất lượng sinh code Flutter/test | Khá → tốt (cần model code ≥14B + máy mạnh) | **Ổn định, ít vòng sửa hơn** |
| Cài đặt | Nặng (GPU/RAM, tải model vài GB) | Chỉ cần API key |
| Riêng tư dữ liệu | **Không gửi ra ngoài** | Gửi đề lên nhà cung cấp |
| Phù hợp | Demo/đồ án không muốn tốn tiền, cần offline | Muốn ít lỗi, nhanh có đề chuẩn |

**Khuyến nghị:** vòng compile-fix giúp **bù** chênh lệch chất lượng (model yếu chỉ cần thêm vòng sửa).
→ Đồ án trình diễn: dùng **Gemini Flash** (free tier rộng, nhanh, ít vòng). Cần offline/miễn phí tuyệt đối:
**Ollama + Qwen2.5‑Coder** qua `OpenAiClient` (đổi base-url). Code khung **không đổi** khi chuyển provider.

---

## 5. Nâng cấp gợi ý (ngoài khung)
- **Streaming** từng vòng qua SSE thay vì poll (hiện poll 1.5s — đủ cho khung).
- Cho GV **sửa tay** artifacts ngay trên wizard trước khi lưu (hiện chỉ xem + lưu/tạo lại).
- Sinh kèm **starter code** phát cho SV (Prompt 2 trong `prompt-tao-testcase.md`).
