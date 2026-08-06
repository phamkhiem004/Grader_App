# Kế hoạch `result.json` v2 — trạng thái thi công

Sổ theo dõi để không mất mạch giữa các phiên. **Đọc file này trước khi làm tiếp bất kỳ P nào.**

- Hợp đồng đích: `D:\AGS-PRM393\SPEC_grader_result_json\SPEC_result_json.md` (bản 2)
- Luật nghiệm thu: `ACCEPTANCE.md` + `verify_result.py` cùng thư mục
- Bộ đo: `fixtures/result-json-v2/` — `./run-fixture.sh`

## ⚠️ Có phía thứ hai ăn output này

`result.json` là **input duy nhất** của `D:\AGS-PRM393\prm393-feedback-bot` (một phiên Claude
khác đang làm).

⚠️ **Hai phiên CÓ THỂ dùng chung thư mục memory** `C:\Users\ASUS\.claude\projects\d--AGS-PRM393\`
— đã xảy ra: file `hai-phia-grader-va-nlp.md` từng bị phiên kia ghi lại theo góc nhìn của họ, và
bản đó khẳng định *"phiên trong thư mục này = phía NLP"*. Ai đọc memory thì **tự xác định vai bằng
repo đang sửa** (`Grader_App/` → Grader → ghi `CHANGELOG_FOR_NLP.md`), đừng tin câu khẳng định vai
theo thư mục. Bàn giao **vẫn phải qua file trong repo hợp đồng**, không qua memory.

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
| **P3** | Engine chung v2 (đọc event `print`, `grading_result` đầy đủ, `engine_version`) | ✅ **Xong** 2026-08-05 | Điểm y hệt trên cả 4 bài (10.0 / 6.0 / 0.0 / 0.0); FAIL giảm 5·9·9·10 → 5·7·8·8, không luật nào đỏ thêm |
| **P3b** | Sửa khiếm khuyết CHẤM SAI ĐIỂM của engine | ✅ **Xong** 2026-08-06 | Bỏ hết cách né trong fixture; engine **trước** P3b chấm `high` 7.8/13 và `medium` đạt oan 1 test, engine **sau** P3b đúng 10.0 / 6.0 / 0.0 / 0.0 |
| **P4** | `executed` / `not_run` **+ P2a: thêm `error_code` phẳng** | ✅ **Xong** 2026-08-06 | `high` và `broken-compile` **0 luật đỏ**; `medium`/`broken-boot` chỉ còn E1+E2 (thuộc P2b). Điểm không đổi |
| **P5** | Sinh `actual` tự động | ✅ **Xong** 2026-08-06 | **C1–C7 xanh hết** trên `medium`, C6 đạt **100%** (trước P3: 20%). `actual_source == "observation"` ở mọi test không đạt. Điểm không đổi |
| **P2b** | *Xoá* `error` + `student_safe_summary` **+ gửi `rubric_label`** | ✅ **Xong** 2026-08-06 | **0 luật FAIL trên cả 4 bài** — lần đầu toàn bộ ACCEPTANCE xanh. 57 test; `tsc` xanh |
| ~~**P4b**~~ | ~~`blocked_by`~~ | 🚫 **ĐÓNG** 2026-08-06 — xem dưới | — |
| **A1** | Suy `error_code` từ `observation.kind` + 4 mã mới | ✅ **Xong** 2026-08-06 | **0 giá trị lệch** trên dữ liệu thật (song ánh 1–1); 4 mẫu vẫn 0 luật FAIL; 61 test |
| **A2** | Mở rộng fixture: 10 runner chưa chạy + 7 `kind` chưa từng phát | ⬜ **nặng** | Nâng 7 `kind` Mức 2 → Mức 1 trong SPEC 5.5 |
| **P6** | `exam.requirements` | ⬜ | — |

### Vì sao ĐÓNG P4b

Không phải vì khó — **P4 đã giải trọn nhu cầu**. Engine chung chỉ có **một** cửa chặn (`_boot()`),
nên tổ hợp *các test `not_run`* + *testcase `APP_BOOT` mang `failed`* đã xác định thủ phạm, không
còn chỗ mơ hồ; `blocked_by` chỉ lặp lại thứ bên đọc suy ra được chắc chắn. Phía NLP xác nhận trên
dữ liệu thật: *"giữ `TC_APP_BOOT` là `failed` cho NLP đúng thứ cần — một testcase để trỏ vào"*.

Tôi từng xếp P4b vào danh sách với lý do *"gần như miễn phí"* — đó là lý do về **chi phí**, không
phải về **giá trị**. Rẻ không có nghĩa là đáng làm. Ghi lại vì đây là lỗi xếp ưu tiên dễ lặp.

Trường `blocked_by` **giữ nguyên, luôn `null`** (bên đọc đã khai; bỏ là thay đổi phá vỡ vô ích).
Mở lại chỉ khi engine có **cửa chặn thứ hai** — xem SPEC 4.1.

> **P3 tách khỏi P3b** (user chốt 2026-08-05). Hai việc loại trừ nhau: P3 là refactor hạ tầng nên
> lấy "điểm không đổi" làm lưới an toàn, còn P3b *phải* làm điểm đổi vì đang có bài bị chấm sai.
> Gộp lại thì mất lưới an toàn. Sau P3b **chấm lại** các bài bị ảnh hưởng (user đã duyệt).

> **P2 đã bị đẩy xuống sau P5** theo phản đối của phía NLP (2026-08-05): P2 chỉ *xoá* trường,
> P5 mới *tạo ra* thứ thay thế. Xoá trước ⇒ cửa sổ 600–750 bản nhận xét rỗng. Phần *thêm*
> `error_code` tách thành **P2a**, gộp vào P4 vì không phá vỡ gì.

## Việc nợ — ✅ đã trả 2026-08-05 (trước khi mở P3)

| # | Việc | Đã làm gì |
|---|---|---|
| 1 | `expected` của testcase `GROUP` là `"Tất cả N assert trong nhóm phải đạt."` — lộ từ vựng nội bộ (`assert`) + đếm số test, đi thẳng tới sinh viên | `TestCaseTaxonomy.groupExpected` dựng câu từ tên nhóm + `expected` các con. Gọi ở **hai** đầu: `commonGroupRow` (đề mới) và `enrichTestCases` (đề đã publish, không cần publish lại). **Giữ nguyên câu giáo viên viết tay** — chỉ thay khi trống hoặc còn đúng câu tự sinh của bản cũ |
| 2 | `FixtureResultAssemblyTest` bỏ qua `sanitizeTestCaseErrors` ⇒ `samples/p1-*.json` không trung thực | Harness nay chạy **đủ chuỗi** của `assembleResultJson`: `enrich → annotateTaxonomy → normalizeExpectedFields → sanitizeTestCaseErrors → annotateTestCases → assess`. Phát hành lại 4 mẫu + `samples/README.md` ghi rõ 4 khuyết tật còn trong mẫu |

> Trả nợ 2 làm số FAIL **tăng** trên `medium` (7 → 9): E1/E2/E3 trước đây xanh giả vì harness
> không chạy chỗ sinh `error`/`student_safe_summary`. Số mới mới là số thật.

**Không chạy song song hai P.** P1/P2/P4 chồng nhau ở `assembleResultJson`; P3/P4/P5 chồng nhau ở
`grader.dart`. Commit riêng từng P (P2 tách 2 commit backend/frontend).

## Chấm lại sau P3b — hiện KHÔNG có bài nào phải chấm lại

Kiểm ngày 2026-08-06: `exams/` chỉ có **`PE11`**, và đó là đề **legacy** (`grader.dart` riêng, không
có `testcase-config.json`); `submissions/` cũng chỉ có bài của `PE11`. P3b chỉ sửa **engine dùng
chung**, còn đề legacy chạy grader của giáo viên và `refreshCommonEngine` cố ý bỏ qua. ⇒ yêu cầu
chấm lại là **đúng nhưng hiện rỗng**.

**Luật cho tương lai — khi đã có đề engine chung được chấm thật:**

1. Điều kiện phải chấm lại: `grading_result.engine_version` **vắng mặt hoặc `< COMMON_V1-2.1.0`**.
2. Chấm lại bằng **"Chấm lại đề"** (`POST /api/batch/regrade-exam/{examId}`), KHÔNG chấm lại lẻ —
   chỉ đường đó mới nâng engine, và mới bảo đảm cả đề dùng chung một engine.
3. Điểm **sẽ đổi hai chiều**: có bài tăng (trước bị trừ oan ở phép kiểm "đã biến mất" và ở thao tác
   chạm sau chuyển màn hình), có bài giảm (trước được điểm oan ở `FORM_VALIDATE_FIELDS`). Phải nói
   trước với người ra quyết định điểm, đừng chấm lại âm thầm.
4. Nhận xét của bot sinh từ kết quả cũ cũng phải sinh lại.

## Số đo giữa các P

Chạy `FixtureResultAssemblyTest` (sinh `grader/target/fixture-result-*.json` từ dữ liệu chấm
thật của fixture) rồi đưa qua `verify_result.py`:

| Mốc | `high` | `medium` | `broken-boot` | `broken-compile` |
|---|---|---|---|---|
| Trước P1 | — | 13 FAIL | — | — |
| Sau P1 (harness còn thiếu bước) | 5 | 7 | — | — |
| Sau khi trả nợ — số THẬT | 5 | 9 | 9 | 10 |
| Sau P3 | 5 | 7 | 8 | 8 |
| Sau P4 | 0 ✅ | 2 | 2 | 0 ✅ |
| Sau P5 | 0 ✅ | 2 | 2 | 0 ✅ |
| **Sau P2b** | **0** ✅ | **0** ✅ | **0** ✅ | **0** ✅ |

**Hết luật đỏ.** P2b gỡ xong E1/E2 — hai luật cuối cùng.

P3 làm xanh: C4, F2 (bài không biên dịch được không còn phát lỗi thô ra `actual`), C6 trên
medium, E3 cả ba bài. P4 làm xanh: A1, A7, A8, A9, A10, C8, D6 — và C6 trên hai bài hỏng nặng,
vì chúng thành `not_run` nên C6 bỏ qua **đúng luật** thay vì bị tính là vi phạm.

## Điểm neo trong code

| Việc | File |
|---|---|
| Sinh `skills_matrix.json` cho đề mới | `TestcaseTemplateService.commonRubricRow` / `commonGroupRow` |
| **Kênh quan sát** (engine phát) | `exam_test.dart` — `_observe` + `_expectPresent` / `_expectGone` / `_expectNoLayoutError` / `_assertNumber` |
| **Render tiếng Việt** + **bảng `kind` → `error_code`** (nguồn sự thật DUY NHẤT của cả hai) | `TestObservationRenderer` |
| Nhãn hiển thị của `rubric` (P2b sẽ gửi) | `TestCaseTaxonomy.rubricOf` lấy `group_id`; nhãn nằm ở `group_name` |
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
4. **Ba khiếm khuyết engine làm CHẤM SAI ĐIỂM** — ✅ sửa ở P3b, xem README của fixture.
   Bài học: fixture **né** một khiếm khuyết thì cũng không đo được nó. Chỉ sau khi bỏ né mới lộ ra
   khiếm khuyết thứ ba (`_validationErrorFor` cho điểm oan) và chuyện `homeKey` của `NAVIGATION`
   là phép kiểm **không thể hỏng** — chính nó đã che một lỗi chấm sai điểm suốt thời gian đó.
   ⇒ Né trong bộ đo là nợ, không phải giải pháp.
5. **Câu chữ tiếng Việt KHÔNG được để trong `exam_test.dart`/`grader.dart`** — hai file đó bị chép
   đóng băng vào từng đề lúc publish, nên sửa một chữ là phải nâng engine cho mọi đề. Engine chỉ
   phát dữ liệu **máy đọc** (`kind`/`subject`/số đo); mọi câu cho người đọc dựng ở
   `TestObservationRenderer`. Câu trong engine chỉ được là phương án chống rỗng.
   *(P4 từng đặt câu `not_run` vào `grader.dart` — P5 đã kéo về backend.)*
6. **`actual` không phải bug một dòng.** `testWidgets` nuốt exception; nội dung thật ở event
   `print`, không phải event `error`. Bằng chứng trong `.build/out/medium.log` (testID 11):
   event `error` chỉ có `"Test failed. See exception logs above."`, còn event `print` mới chứa
   `"Thiếu lỗi validation key: validation.name"` — chính chuỗi `reason:` của engine.
   **Tin tốt cho P5:** các `reason:` trong `exam_test.dart` đã là tiếng Việt và đã là "điều quan
   sát được", nên P3 chỉ cần dẫn event `print` về đúng testcase là `actual` đã dùng được ngay.
7. `exams/` và `submissions/` trong `.gitignore` đã được neo vào gốc repo — đừng bỏ dấu `/` đầu,
   nếu không `fixtures/*/submissions/` biến mất khỏi git.
8. **Sửa engine trong `resources` KHÔNG tới được đề đã publish** — ✅ đã xử lý ở P3.
   `TestcaseTemplateService.materializeEngine` **chép đóng băng** `exam_test.dart` + `grader.dart`
   vào `exams/<examId>/` lúc lưu/publish, còn `resolveTestcasePath` lúc chấm lại thì ưu tiên đúng
   thư mục đó → chấm lại đề cũ vẫn chạy engine CŨ. Cùng dạng bẫy với mục 1.
   Nay `regradeExam` gọi `TestcaseTemplateService.refreshCommonEngine(examId)` trước khi xếp hàng.
   **Chỉ nâng khi chấm lại CẢ ĐỀ**, không nâng khi chấm lại lẻ — nâng lúc chấm lại một bài thì
   trong cùng đề sẽ có bài chấm bằng engine mới, bài chấm bằng engine cũ. Đề legacy không bị
   đụng tới (grader do giáo viên nộp). Khoá bằng `TestcaseEngineRefreshTest`.
   Dấu hiệu đề còn engine cũ: `grading_result.engine_version` **vắng mặt**.
9. **Harness đo phải chạy ĐỦ chuỗi hàm của `assembleResultJson`.** Thiếu một bước là luật
   nghiệm thu xanh giả — đã xảy ra với `sanitizeTestCaseErrors` (nhóm E) và `assess`
   (`competency_assessment`). Thêm bước mới vào `assembleResultJson` thì thêm luôn vào
   `FixtureResultAssemblyTest.assemble`.
10. **"Field mới lên ⇒ gỡ guard cũ" gần như luôn SAI** (bài học phía NLP, 2026-08-06). Dữ liệu cũ
   không biến mất: `result_json` cũ không được migrate, nên trong DB vĩnh viễn tồn tại song song
   hai đời `actual`. Guard phải gỡ theo **cờ đời dữ liệu** (`actual_source`, `schema_version`,
   sự có mặt của `chapter`), **không** theo ngày phase lên. ⇒ Grader **cam kết không bỏ**
   `actual_source` kể cả khi mọi runner đã chuyển xong.
11. **Rẻ không có nghĩa là đáng làm.** P4b từng nằm trong danh sách với lý do "gần như miễn phí" —
   lý do về chi phí, không phải về giá trị. Xét lại thì P4 đã giải trọn nhu cầu và P4b chỉ lặp
   lại thứ suy được. Xếp ưu tiên phải theo **giá trị**, chi phí chỉ là điều kiện phụ.
12. **Công bố hợp đồng trên code CHƯA CHẠY là lặp lại bẫy cũ.** Bảng `observation.kind` khai 13 giá
   trị nhưng fixture mới chạy thật 6 ⇒ SPEC 5.5 chia **hai mức**, chỉ cam kết phần đã đo. Cùng
   dạng với hai lần đã vấp: `samples/` từng không trung thực, fixture từng né khiếm khuyết engine.
