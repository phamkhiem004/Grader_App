# Prompt cho hệ năng lực: (A) sinh testcase gắn syllabus, (B) AI nhận xét năng lực

Đi kèm [`syllabus.json`](./syllabus.json) và [`syllabus-design.md`](./syllabus-design.md).

---

## A. DELTA cho Prompt 1 (sinh testcase) — chèn vào `docs/prompt-tao-testcase.md`

Prompt sinh testcase hiện tại để AI **tự gõ** `skill` tự do → taxonomy bị lệch. Sửa lại để AI **chọn
`skill_code` từ syllabus** và **gán `difficulty`**. Chèn khối dưới đây vào Prompt 1 (ngay sau QUY TẮC số 2).

````text
## DANH MỤC KỸ NĂNG (SYLLABUS v2026.2) — BẮT BUỘC CHỌN TỪ ĐÂY
Mỗi testcase PHẢI gắn `skill_code` lấy CHÍNH XÁC từ danh sách dưới (không tự bịa code mới).
Chỉ dùng skill có testable=auto cho phần chấm tự động (skill manual = cần package ngoài/mạng → chấm tay).

[DART_ESSENTIALS]   DART_SYNTAX, DART_FUNCTIONS, DART_CLASSES, DART_COLLECTIONS, DART_NULL_SAFETY
[OOP_ASYNC]         OOP_INHERITANCE, OOP_PATTERNS, OOP_MODEL, ASYNC_FUTURE, ASYNC_STREAM
[UI_FUNDAMENTALS]   UI_WIDGETS, UI_MATERIAL, UI_LISTS, UI_PICKERS
[NAV_STATE]         NAV_BASIC, NAV_NAMED, NAV_ADVANCED, STATE_BASIC, STATE_LIFTING
[LAYOUT_RESPONSIVE] LAYOUT_FLEX, LAYOUT_STACK, LAYOUT_GRID, LAYOUT_RESPONSIVE
[FORMS_VALIDATION]  FORM_INPUT, FORM_VALIDATE, FORM_BUSINESS
[NETWORKING]        NET_JSON, NET_FUTUREBUILDER, (NET_HTTP = manual)
[STORAGE]           STORE_CACHE, (STORE_PREFS, STORE_DB = manual)
[AUTH]              AUTH_GUARD, (AUTH_BASIC, AUTH_SESSION = manual)

(Mô tả đầy đủ từng code xem syllabus.json. Nếu một testcase phủ nhiều skill → chọn skill CHÍNH.)

## GÁN ĐỘ KHÓ (difficulty) + CHO ĐIỂM THEO ĐỘ KHÓ
- basic        : kiến thức 1 buổi, áp dụng thẳng, không kết hợp.   → weight = 1
- intermediate : kết hợp 2–3 khái niệm, có điều kiện/edge case.    → weight = 2
- advanced     : tổng hợp nhiều phần, edge case khó, tối ưu/async. → weight = 3
`weight` SUY RA TỪ `difficulty` (không tự chọn). Testcase càng khó càng nhiều điểm.

## ĐỊNH DẠNG skills_matrix.json (skill_code + difficulty + weight theo độ khó)
{
  "TC_LOGIC_03": {
    "skill_code": "DART_COLLECTIONS",  // BẮT BUỘC, lấy từ danh mục trên
    "difficulty": "intermediate",       // BẮT BUỘC: basic | intermediate | advanced
    "weight": 2,                        // = điểm theo độ khó (basic=1, intermediate=2, advanced=3)
    "skill": "Collection & logic thuần",// tuỳ chọn: tên hiển thị thân thiện
    "name": "nextId trả về max(id)+1",
    "description": "Danh sách có id {3,7,2}, gọi nextId.",
    "expected": "Trả về 8 (id lớn nhất + 1)."
  }
}

KIỂM TRA CUỐI (bổ sung): mọi testcase có `skill_code` thuộc danh mục trên + có `difficulty` hợp lệ +
`weight` ĐÚNG theo độ khó (1/2/3); nên phủ nhiều category & nhiều mức độ khó. KHÔNG ép tổng = 10
(grader tự chuẩn hóa: điểm = Σweight_pass / Σweight × 10).
````

> Backend tự đọc `skill_code`/`difficulty` từ skills_matrix.json để gắn nhãn & tính năng lực — KHÔNG
> cần sửa `grader.dart`. `grader.dart` vẫn chấm theo `weight` (giờ weight đã phản ánh độ khó).

---

## B. Prompt AI NHẬN XÉT NĂNG LỰC (dùng khi xuất JSON cho AI)

Dán prompt này + kèm `syllabus.json` + `result_json` của bài nộp. AI trả về đánh giá theo category.

````text
Bạn là TRỢ GIẢNG chấm năng lực môn Mobile Flutter. Bạn nhận:
1. SYLLABUS: danh mục category + skill (kèm mô tả, resources học liệu).
2. KẾT QUẢ BÀI NỘP: mảng test_cases[] — mỗi phần tử có skill_code, difficulty, status (passed/failed),
   weight, và (nếu fail) expected + actual (lý do fail thực tế).

NHIỆM VỤ — sinh đánh giá năng lực, KHÔNG chấm lại điểm:

BƯỚC 1 — Gom test_cases theo `category` (tra skill_code → category trong syllabus).
BƯỚC 2 — Mỗi category tính:
   ratio = Σweight(passed) / Σweight(all)
   level = YẾU (<0.40) | TRUNG BÌNH (0.40–0.69) | TỐT (≥0.70)
   thống kê pass/total theo difficulty (basic/intermediate/advanced).
BƯỚC 3 — Mỗi category viết `comment` ngắn (2–4 câu) theo nguyên tắc:
   - Nếu FAIL ở difficulty=basic → nhấn mạnh "hổng kiến thức nền tảng", nêu rõ skill yếu + 1 gợi ý học
     liệu lấy từ `resources` của skill đó trong syllabus.
   - Nếu chỉ fail ở advanced (basic/intermediate pass) → ghi nhận nền tảng tốt, khuyến nghị luyện nâng cao.
   - Bám vào `actual` (lỗi thực tế) để chỉ ra SAI Ở ĐÂU, không nói chung chung.
BƯỚC 4 — Viết `overall`: 2–3 câu tổng kết điểm mạnh/điểm yếu lớn nhất + 2–3 việc cần làm tiếp (ưu tiên
   category YẾU và skill basic bị fail).

Văn phong: tiếng Việt, ngắn gọn, mang tính hướng dẫn (không chê bai). Xưng "em" với sinh viên.

XUẤT RA JSON đúng cấu trúc:
{
  "competency_assessment": [
    {
      "category": "<code>", "label": "<competency_label>",
      "passed_weight": <num>, "total_weight": <num>, "ratio": <num 0..1>,
      "level": "YẾU|TRUNG BÌNH|TỐT",
      "by_difficulty": { "basic": "x/y", "intermediate": "x/y", "advanced": "x/y" },
      "weak_skills": ["<skill_code>", ...],
      "comment": "<nhận xét + gợi ý học liệu>"
    }
  ],
  "overall": "<tổng kết + việc cần làm tiếp>"
}

CHỈ xuất JSON, không thêm lời dẫn.
````

### Ví dụ minh hoạ (rút gọn)
Input: testcase `TC_LOGIC_01`(DART_LOGIC, basic, FAILED), `TC_LOGIC_02`(DART_LOGIC, basic, passed),
`TC_UI_01`(UI_BASIC, basic, passed), `TC_UI_08`(VAL_INPUT, basic, FAILED).

Output (rút gọn):
```json
{
  "competency_assessment": [
    { "category": "DART", "label": "Code Dart", "ratio": 0.5, "level": "TRUNG BÌNH",
      "by_difficulty": { "basic": "1/2" }, "weak_skills": ["DART_LOGIC"],
      "comment": "Em viết được hàm cơ bản nhưng sai ở xử lý danh sách (nextId). Đây là kiến thức nền — em xem lại phần Iterables (dart.dev/codelabs/iterables) và luyện thêm filter/sort." },
    { "category": "VALIDATION", "label": "Validate", "ratio": 0.0, "level": "YẾU",
      "by_difficulty": { "basic": "0/1" }, "weak_skills": ["VAL_INPUT"],
      "comment": "Em chưa kiểm tra dữ liệu nhập rỗng nên không hiện thông báo lỗi. Cần bổ sung kiến thức validate cơ bản (cookbook/forms/validation)." }
  ],
  "overall": "Nền tảng UI ổn, nhưng yếu Validate và còn lỗ hổng ở logic Dart. Ưu tiên: (1) ôn validate form, (2) luyện xử lý List, (3) làm lại 2 testcase đã fail."
}
```

---

## Tóm tắt thay đổi cần làm theo file

| File | Thay đổi |
|---|---|
| `docs/prompt-tao-testcase.md` | Chèn khối DELTA mục A (chọn skill_code + difficulty) |
| `exams/*/testcase/skills_matrix.json` | Thêm `skill_code` + `difficulty` mỗi testcase |
| `exams/*/testcase/grader.dart` | Pass-through `skill_code` + `difficulty` vào `test_cases[]` |
| Backend (GĐ1) | Load `syllabus.json`; tính `competency_assessment[]`; validate skill_code lúc upload |
| Backend (GĐ2) | Bảng `skill_category`/`skill` + API CRUD (xem syllabus-design.md mục 6) |
| Frontend | Bảng năng lực theo category; gọi prompt B để sinh nhận xét |
