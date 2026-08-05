# Kế hoạch `result.json` v2 — trạng thái thi công

Sổ theo dõi để không mất mạch giữa các phiên. **Đọc file này trước khi làm tiếp bất kỳ P nào.**

- Hợp đồng đích: `D:\AGS-PRM393\SPEC_grader_result_json\SPEC_result_json.md` (bản 2)
- Luật nghiệm thu: `ACCEPTANCE.md` + `verify_result.py` cùng thư mục
- Bộ đo: `fixtures/result-json-v2/` — `./run-fixture.sh`

## ⚠️ Có phía thứ hai ăn output này

`result.json` là **input duy nhất** của `D:\AGS-PRM393\prm393-feedback-bot` (một phiên Claude
khác đang làm). Memory hai bên **không dùng chung** — thư mục dự án khác nhau.

`SPEC_grader_result_json/` nay là **repo git riêng, tài sản chung hai bên** — kênh liên lạc
hai chiều:

| File | Chiều | Ai ghi |
|---|---|---|
| `CHANGELOG_FOR_NLP.md` | Grader → NLP | **Tôi** |
| `CHANGELOG_FOR_GRADER.md` | NLP → Grader | Họ — **đọc mỗi lần bắt đầu phase** |

**Xong mỗi P mà có đổi schema thì phải ghi `CHANGELOG_FOR_NLP.md` + phát hành `samples/`**
trước khi coi là hết việc, kèm mục "CẦN LÀM GÌ" cho bên kia. Họ pin test vào `samples/` nên
**file mẫu mạnh hơn văn xuôi** — mẫu sai còn tệ hơn không có mẫu. Kiểm chéo bằng
`prm393-feedback-bot/app/schemas.py` — model của họ là hợp đồng phía nhận.

## Mục tiêu

Biến `result.json` từ bảng điểm thành **hồ sơ bằng chứng tự đủ** cho hệ sinh nhận xét (NLP):
nhúng sẵn nhãn phân loại vào từng test, `actual` là **điều quan sát được** bằng tiếng Việt, và
phân biệt được *"làm sai"* với *"chưa có cơ hội chạy"*.

Nguyên tắc bất di bất dịch: **chỉ ghi điều quan sát được, không suy đoán nguyên nhân trong code
sinh viên.** Mọi tranh cãi thiết kế phân xử bằng nguyên tắc này.

## Ba quyết định đã chốt

- **D1′** — Đóng track upload ZIP. Engine chung là con đường duy nhất ra đề mới; đề legacy chỉ còn
  đọc và chấm lại được.
- **D2** — Một trục phân loại duy nhất: `layer` của SPEC, suy từ `runner` (bảng 23 dòng, SPEC 3.1).
  `testcase_group` (LOGIC/WIDGET/BEHAVIOR) hạ xuống thành nhãn hiển thị cho giáo viên.
- **D3** — `rubric` lấy từ `group_id`/`group_name` giáo viên gom trên UI, **không** phải
  `testcase_group`.

## Tiến độ

| P | Việc | Trạng thái | Cổng nghiệm thu |
|---|---|---|---|
| **P0** | Chốt hợp đồng + thước đo + fixture | ✅ **Xong** 2026-08-05 | verify tự kiểm 29/29 vi phạm; fixture chạy lại cho kết quả y hệt |
| **P1** | `rubric` + `layer` + `chapter` | ✅ **Xong** 2026-08-05 | A3, A4, A5, B1, B2 xanh trên bài chấm thật; 22 test đơn vị; điểm không đổi (không đụng Dart) |
| **P3** | Engine chung v2 (đọc event `print`, `grading_result` đầy đủ, `engine_version`) | 🔜 kế tiếp | **Điểm y hệt** trên cả 4 bài fixture |
| **P4** | `executed` / `not_run` **+ P2a: thêm `error_code` phẳng** | ⬜ | A7, A8, A9, A10, C8 xanh trên `broken-compile` |
| **P5** | Sinh `actual` tự động | ⬜ | C1–C7 xanh trên `medium` |
| **P2b** | *Xoá* `error` + `student_safe_summary` | ⬜ | E1, E2, E3 xanh; FE + bot còn chạy |
| **P4b** | `blocked_by` qua cơ chế `_boot()` | ⬜ | D1–D5 xanh trên `broken-boot` |
| **P6** | `exam.requirements` | ⬜ | — |

> **P2 đã bị đẩy xuống sau P5** theo phản đối của phía NLP (2026-08-05): P2 chỉ *xoá* trường,
> P5 mới *tạo ra* thứ thay thế. Xoá trước ⇒ cửa sổ 600–750 bản nhận xét rỗng. Phần *thêm*
> `error_code` tách thành **P2a**, gộp vào P4 vì không phá vỡ gì.

## Việc nợ, phải làm trước hoặc trong P3

| # | Việc | Vì sao |
|---|---|---|
| 1 | `TestcaseTemplateService:864` — `expected` của testcase `GROUP` đang là `"Tất cả N assert trong nhóm phải đạt."` | Lộ từ vựng nội bộ (`assert`) + đếm số test, đi thẳng tới sinh viên. Dựng lại từ `group_name` + `expected` các con |
| 2 | `FixtureResultAssemblyTest` bỏ qua `sanitizeTestCaseErrors` ⇒ `samples/p1-*.json` **không trung thực** (thiếu `error`, `student_safe_summary`) | Phía NLP sẽ pin test vào `samples/`; mẫu sai làm họ kết luận sai rằng P2 đã xong. Đã báo họ đừng pin cho tới khi phát hành lại |

**Không chạy song song hai P.** P1/P2/P4 chồng nhau ở `assembleResultJson`; P3/P4/P5 chồng nhau ở
`grader.dart`. Commit riêng từng P (P2 tách 2 commit backend/frontend).

## Số đo giữa các P

Chạy `FixtureResultAssemblyTest` (sinh `grader/target/fixture-result-*.json` từ dữ liệu chấm
thật của fixture) rồi đưa qua `verify_result.py`:

| Mốc | `high` | `medium` | Luật còn đỏ |
|---|---|---|---|
| Trước P1 (schema hiện tại) | — | 13 FAIL | A1 A3 A4 A5 B3 · A7 A8 A9 · C1 C2 · E1 E2 E3 |
| Sau P1 | 5 FAIL | 7 FAIL | A1 A7 A8 A9 D6 (⇒ P4) · C1 C2 (⇒ P5) |

> ⚠️ Nhóm **E xanh trong artifact này chưa có ý nghĩa**: harness không gọi
> `sanitizeTestCaseErrors` — chính chỗ sinh ra `error.message`/`student_safe_summary`. E chỉ
> được nghiệm thu thật sau P2, trên `result.json` do backend ghép đầy đủ.

## Điểm neo trong code

| Việc | File |
|---|---|
| Sinh `skills_matrix.json` cho đề mới | `TestcaseTemplateService.commonRubricRow` / `commonGroupRow` |
| Ghép `result.json` | `BatchGradingService.assembleResultJson` → `enrichTestCases` |
| Gắn nhãn năng lực | `CompetencyService.annotateTestCases` + `SyllabusService.Resolver` |
| Chuẩn hoá khi ĐỌC (dễ quên, phải sửa cùng lúc) | `ResultController.normalizeResultNode` |
| Engine chấm dùng chung | `grader/src/main/resources/common-testcase-engine/` |
| Chạy chấm trong Docker | `GradingService.runDockerGrader` — chỉ mount `lib/` + testcase |

## Bẫy đã gặp, đừng vấp lại

1. **Sửa một nơi là vô hiệu.** `student_safe_summary` được sinh ở `BatchGradingService` nhưng
   `ResultController` **tự bơm lại lúc đọc**. Gỡ phải gỡ cả hai.
2. **Dữ liệu cũ không cứu được.** Lớp normalize khi đọc đã ghi đè log cũ thành một câu chung.
   Nghiệm thu luôn phải trên bài **chấm lại**.
3. **`commonRubricRow` đang làm rơi `layer`** mà template đã khai sẵn.
4. **Hai khiếm khuyết engine làm CHẤM SAI ĐIỂM** — xem README của fixture, phải sửa ở P3. Fixture
   hiện chỉ né chứ chưa sửa.
5. **`actual` không phải bug một dòng.** `testWidgets` nuốt exception; nội dung thật ở event
   `print`, không phải event `error`.
6. `exams/` và `submissions/` trong `.gitignore` đã được neo vào gốc repo — đừng bỏ dấu `/` đầu,
   nếu không `fixtures/*/submissions/` biến mất khỏi git.
