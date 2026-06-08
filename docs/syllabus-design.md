# Thiết kế: Syllabus & Đánh giá năng lực theo category

Tài liệu này mô tả: (1) **list category đánh giá năng lực**, (2) cách quy testcase → mức năng lực,
(3) schema DB + API CRUD cho giảng viên (Giai đoạn 2). Dữ liệu gốc nằm ở [`syllabus.json`](./syllabus.json).

---

## 0. Hai trục đánh giá (đọc trước để khỏi nhầm)

Một testcase được mô tả bằng **2 trục độc lập**:

| Trục | Là gì | Ví dụ | Field trong `skills_matrix.json` |
|---|---|---|---|
| **Category / Skill** | Thuộc *mảng kiến thức* nào | "Code Dart", "Giao diện", "Validate" | `skill_code` (vd `DART_LOGIC`) |
| **Difficulty** | *Độ khó* của testcase đó | basic / intermediate / advanced | `difficulty` |

> Cụm "**giao diện basic**" bạn nói thực ra là **category = Giao diện** × **difficulty = basic**.
> Đừng gộp 2 thứ này làm một — gộp lại sẽ không tổng hợp được "SV yếu mảng nào" tách khỏi "yếu ở mức khó nào".

**List category năng lực** = trục thứ nhất (Category). Đây là câu trả lời trực tiếp cho câu hỏi của bạn.

---

## 1. LIST CATEGORY ĐÁNH GIÁ NĂNG LỰC (master)

Đây là danh mục năng lực lớn dùng để chấm năng lực (rollup từ nhiều testcase). 6 category:

| # | code | Nhãn năng lực | Bao gồm | Ví dụ skill chi tiết |
|---|---|---|---|---|
| 1 | `DART` | **Code Dart** | Cú pháp, OOP, model, hàm thuần logic | `DART_BASIC`, `DART_OOP`, `DART_LOGIC` |
| 2 | `UI` | **Giao diện** | Widget, layout, danh sách, dialog | `UI_BASIC`, `UI_LAYOUT`, `UI_LIST`, `UI_DIALOG` |
| 3 | `VALIDATION` | **Validate** | Kiểm tra nhập, ràng buộc nghiệp vụ | `VAL_INPUT`, `VAL_BUSINESS`, `VAL_FORM` |
| 4 | `STATE` | **Tương tác/State** | setState, callback, state mgmt | `STATE_SETSTATE`, `STATE_LIFTING` |
| 5 | `NAVIGATION` | **Điều hướng** | Push/pop, truyền dữ liệu | `NAV_BASIC`, `NAV_ARGS` |
| 6 | `ASYNC` | **Async/Data** | Future, JSON, API, FutureBuilder | `ASYNC_FUTURE`, `ASYNC_JSON` |

> 3 category đầu (**Code Dart, Giao diện, Validate**) phủ gần như toàn bộ đề PE hiện tại của bạn.
> 3 category sau là phần mở rộng cho đề nâng cao — có sẵn để không phải sửa lược đồ sau này.

### Vì sao 2 tầng (Category → Skill)?
- **Testcase** map tới **skill chi tiết** (mịn) → AI nhận xét chính xác "yếu đúng phần nào".
- **Đánh giá năng lực** roll-up lên **category** (thô) → bảng tổng quan dễ đọc cho SV/GV.

Toàn bộ ~22 skill chi tiết nằm trong [`syllabus.json`](./syllabus.json) (mảng `skills`).

---

## 2. Độ khó (difficulty) — tiêu chí gán

| code | Nhãn | Khi nào gán |
|---|---|---|
| `basic` | Cơ bản | Kiến thức 1 buổi học, áp dụng thẳng, không kết hợp. |
| `intermediate` | Trung bình | Kết hợp 2–3 khái niệm, có rẽ nhánh/điều kiện, vài edge case. |
| `advanced` | Nâng cao | Tổng hợp nhiều phần, edge case khó, tối ưu/đệ quy/async. |

Mỗi skill có `default_difficulty` gợi ý, nhưng **testcase được phép override** (cùng skill `DART_LOGIC`
có thể có testcase basic và advanced khác nhau).

---

## 3. `testable: auto | manual` — ràng buộc của môi trường chấm

Container chấm **chỉ có `flutter` + `flutter_test`, KHÔNG mạng, KHÔNG package ngoài**. Vì vậy:

- `testable: "auto"` → chấm tự động được bằng testcase (đa số skill).
- `testable: "manual"` → cần package ngoài/mạng (vd `STATE_MGMT` dùng Provider, `ASYNC_HTTP` gọi API)
  → **không** ra testcase tự động; chỉ dùng cho chấm tay / câu hỏi lý thuyết.

> Khi ra đề: **chỉ chọn skill `auto`** cho phần chấm tự động. Skill `manual` để dành cho rubric chấm tay
> (bảng `manual_json` đã có sẵn trong entity `ExamResult`).

---

## 4. Công thức quy ra MỨC NĂNG LỰC

Với mỗi `category` (gom mọi testcase có `skill_code` thuộc category đó):

```
ratio = Σ(weight các testcase PASS) / Σ(weight tất cả testcase của category)

ratio < 0.40            → YẾU
0.40 ≤ ratio < 0.70     → TRUNG BÌNH
ratio ≥ 0.70            → TỐT
```

Ngưỡng lấy từ `level_thresholds_default` trong `syllabus.json` (có thể override theo category).

### Tinh chỉnh theo độ khó (khuyến nghị — chỉ dùng cho NHẬN XÉT, không đổi điểm)
Tách thêm thống kê `pass/total` theo từng difficulty trong category để AI nói có chiều sâu:
- Fail ở `basic` → "hổng kiến thức nền tảng" (báo động).
- Pass basic, fail `advanced` → "nắm cơ bản, cần luyện nâng cao" (bình thường).

> **Lưu ý:** điểm số cuối cùng vẫn tính theo `weight` như hiện tại. Difficulty **không** đổi cách tính điểm,
> chỉ làm giàu phần đánh giá năng lực → tránh thang điểm khó giải thích.

### Cấu trúc kết quả đánh giá năng lực (đề xuất thêm vào `result_json`)
Đây chính là "list category đánh giá năng lực" ở mức **một bài nộp**:

```json
"competency_assessment": [
  {
    "category": "DART",
    "label": "Code Dart",
    "passed_weight": 2.5,
    "total_weight": 3.0,
    "ratio": 0.83,
    "level": "TỐT",
    "by_difficulty": { "basic": "2/2", "intermediate": "1/1", "advanced": "0/1" },
    "weak_skills": ["DART_LOGIC"],
    "comment": "<AI điền>"
  },
  {
    "category": "VALIDATION",
    "label": "Validate",
    "passed_weight": 0.5,
    "total_weight": 1.5,
    "ratio": 0.33,
    "level": "YẾU",
    "by_difficulty": { "basic": "1/2", "intermediate": "0/1" },
    "weak_skills": ["VAL_INPUT", "VAL_BUSINESS"],
    "comment": "<AI điền>"
  }
]
```

Phần tổng hợp này nên tính ở **backend (Java)** lúc lưu kết quả (đọc `test_cases[]` đã có `skill_code`,
`difficulty`, `status`, `weight`), **không cần** nhét syllabus vào container Docker.

---

## 5. Đề xuất sửa `skills_matrix.json` (đầu vào)

Thêm 2 field cho mỗi testcase: `skill_code` (bắt buộc, lấy từ syllabus) và `difficulty`.
Giữ `skill` (tên hiển thị) để tương thích đề cũ là tuỳ chọn.

```json
"TC_LOGIC_03": {
  "weight": 0.5,
  "skill_code": "DART_LOGIC",      ← MỚI: trỏ syllabus bằng code ổn định
  "difficulty": "intermediate",     ← MỚI
  "skill": "Dart Logic",            ← cũ, để hiển thị (tuỳ chọn)
  "name": "nextId trả về max(id)+1",
  "description": "Danh sách có id {3,7,2}, gọi nextId.",
  "expected": "Trả về 8 (id lớn nhất + 1)."
}
```

`grader.dart` chỉ cần **pass-through** 2 field mới này vào `test_cases[]` (giống cách đang làm với `skill`).
Không cần đổi logic chấm.

---

## 6. Quản lý syllabus — Giai đoạn 1 (FILE) vs Giai đoạn 2 (CRUD)

### Giai đoạn 1 — File (làm trước, bắt buộc)
- `syllabus.json` đặt tại `grader/src/main/resources/syllabus.json`, backend load lúc khởi động (cache in-memory).
- Sửa syllabus = sửa file + restart backend. Có version control qua git.
- **Validate lúc upload đề**: khi GV upload testcase, backend đọc mọi `skill_code` trong `skills_matrix.json`;
  nếu có code **không tồn tại** trong syllabus (hoặc đang `deprecated`) → **từ chối** + báo code sai.
  Đây là chốt chặn giữ taxonomy sạch.

### Giai đoạn 2 — CRUD cho giảng viên (nếu còn thời gian)
Đưa syllabus vào DB + UI quản lý. Nhờ Giai đoạn 1 đã trỏ-bằng-code, nâng cấp **không phá vỡ** đề cũ.

#### Schema DB (MySQL)

```sql
-- Category năng lực (mảng lớn)
CREATE TABLE skill_category (
  code          VARCHAR(40)  PRIMARY KEY,        -- 'DART', 'UI', ... (ID ỔN ĐỊNH, không đổi)
  name          VARCHAR(120) NOT NULL,
  competency_label VARCHAR(80) NOT NULL,         -- 'Code Dart', 'Giao diện', 'Validate'
  description   TEXT,
  display_order INT          NOT NULL DEFAULT 0,
  weak_threshold  DECIMAL(3,2) NOT NULL DEFAULT 0.40,
  good_threshold  DECIMAL(3,2) NOT NULL DEFAULT 0.70,
  active        BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Skill chi tiết (testcase trỏ vào đây)
CREATE TABLE skill (
  code            VARCHAR(60)  PRIMARY KEY,       -- 'DART_LOGIC' (ID ỔN ĐỊNH)
  category_code   VARCHAR(40)  NOT NULL,
  name            VARCHAR(120) NOT NULL,
  description     TEXT,
  default_difficulty VARCHAR(20) NOT NULL DEFAULT 'basic',  -- basic|intermediate|advanced
  testable        VARCHAR(10)  NOT NULL DEFAULT 'auto',     -- auto|manual
  resources_json  JSON,                            -- ["dart.dev/...", "Slide buổi 3"]
  display_order   INT          NOT NULL DEFAULT 0,
  deprecated      BOOLEAN      NOT NULL DEFAULT FALSE,       -- soft-delete: không xoá cứng
  CONSTRAINT fk_skill_category FOREIGN KEY (category_code)
    REFERENCES skill_category(code) ON UPDATE CASCADE
);

-- (Tuỳ chọn) version syllabus để audit
CREATE TABLE syllabus_meta (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  version     VARCHAR(20) NOT NULL,
  subject     VARCHAR(160),
  updated_by  VARCHAR(120),
  updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

> Entity Java tương ứng: `SkillCategory`, `Skill` (JPA `@Entity`), repo `SkillCategoryRepository`, `SkillRepository`.
> Seed lần đầu từ `syllabus.json` (giống cách backend seed 2 tài khoản mẫu).

#### REST API

| Method | Endpoint | Vai trò | Mô tả |
|---|---|---|---|
| `GET` | `/api/syllabus` | mọi GV | Trả cả cây: categories + skills (cho ra đề & nhận xét) |
| `GET` | `/api/syllabus/categories` | mọi GV | Danh sách category |
| `POST` | `/api/syllabus/categories` | ADMIN | Thêm category mới |
| `PUT` | `/api/syllabus/categories/{code}` | ADMIN | Sửa tên/mô tả/ngưỡng (KHÔNG cho đổi `code`) |
| `DELETE` | `/api/syllabus/categories/{code}` | ADMIN | `active=false` (soft) — chặn nếu còn skill active |
| `GET` | `/api/syllabus/skills` | mọi GV | Danh sách skill (lọc `?category=`, `?testable=auto`) |
| `POST` | `/api/syllabus/skills` | TEACHER+ | Thêm skill mới |
| `PUT` | `/api/syllabus/skills/{code}` | TEACHER+ | Sửa thông tin skill (KHÔNG cho đổi `code`) |
| `DELETE` | `/api/syllabus/skills/{code}` | TEACHER+ | `deprecated=true` (soft-delete) |
| `POST` | `/api/exam-setup/validate-skills` | TEACHER+ | Nhận `skills_matrix.json`, trả về danh sách `skill_code` sai/đã deprecated |

#### Quy tắc quản trị (governance) — QUAN TRỌNG
1. **KHÔNG bao giờ cho đổi `code`** qua API. `code` là khoá ổn định mà testcase trỏ vào. Muốn "đổi tên" → chỉ sửa `name`.
2. **Xóa = soft-delete** (`deprecated`/`active=false`), không xoá cứng. Đề cũ trỏ vào vẫn đọc map được;
   chỉ ẩn khỏi danh sách chọn khi ra đề mới.
3. **Thêm**: tự do, không ảnh hưởng đề cũ.
4. (Tuỳ chọn) Khi `PUT/DELETE`, cảnh báo "có N đề đang dùng skill này" — đếm qua `skills_matrix` đã lưu.

---

## 7. Tác động khi syllabus thay đổi (trả lời thẳng câu hỏi)

| Hành động | Đề cũ có sao không? | Vì sao |
|---|---|---|
| Đổi `name`/`description`/`resources` | ✅ An toàn | testcase trỏ theo `code`, không theo tên |
| Đổi ngưỡng `weak/good` | ✅ An toàn | chỉ ảnh hưởng cách *xếp loại* lần chấm sau |
| Thêm category/skill mới | ✅ An toàn | đề cũ không tham chiếu nên không đổi |
| Đổi `code` | ❌ Vỡ | nên **cấm** ở API (mục 6) |
| Xóa cứng skill đang dùng | ❌ Vỡ | nên dùng **soft-delete** thay thế |

→ Kết luận: nếu thiết kế **trỏ-bằng-code + soft-delete + cấm đổi code**, thì dù sửa file (GĐ1) hay CRUD (GĐ2),
việc thay đổi syllabus **không đụng** tới các category năng lực của đề/bài đã chấm trước đó.

---

## 8. Lộ trình triển khai gợi ý

1. **GĐ1a** — Bổ sung `skill_code` + `difficulty` vào `skills_matrix.json` các đề (sửa tay / qua prompt mới).
2. **GĐ1b** — `grader.dart` pass-through 2 field mới vào `test_cases[]`.
3. **GĐ1c** — Backend: tính `competency_assessment[]` lúc lưu kết quả + load `syllabus.json` từ resources + validate lúc upload đề.
4. **GĐ1d** — Frontend: hiện bảng năng lực theo category (TỐT/TB/YẾU) + nút xuất JSON kèm `competency_assessment`.
5. **GĐ2** — DB + CRUD + UI quản lý syllabus cho GV (chỉ làm khi cần self-service).

Prompt cập nhật (ra đề & AI nhận xét) ở [`prompt-nang-luc.md`](./prompt-nang-luc.md).
