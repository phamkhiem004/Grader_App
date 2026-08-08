# Kế hoạch `result.json` v2 — trạng thái thi công

Sổ theo dõi để không mất mạch giữa các phiên. **Đọc file này trước khi làm tiếp bất kỳ P nào.**

- Hợp đồng đích: `D:\AGS-PRM393\SPEC_grader_result_json\SPEC_result_json.md` (bản 2)
- Luật nghiệm thu: `ACCEPTANCE.md` + `verify_result.py` cùng thư mục
- Bộ đo: `fixtures/result-json-v2/` — `./run-fixture.sh`

---

# 🚩 BẮT ĐẦU TỪ ĐÂY (phiên mới đọc phần này trước)

## Đang ở đâu — 2026-08-08

**P0 → A2c → (c) → P6a xong.** Engine `COMMON_V1-2.7.0` **không đổi từ A2c** — (c) và P6a đều
nằm ngoài khâu chấm, **không đề nào phải chấm lại**. **85 test xanh** (69 → 85), fixture **25
testcase / 7 bài nộp**, **0 luật FAIL** trên **8** mẫu — cả 8 nay mang `exam.requirements` thật.
Việc còn lại DUY NHẤT của plan: **P6b** (một ô nhập FE). Bảng tiến độ chi tiết ở mục *Tiến độ* dưới.

## Việc kế tiếp, theo thứ tự

**1. ~~(c) — dọn `expected` máy sinh.~~ ✅ XONG** — xem mục *(c)* dưới. Còn **hai chỗ mở** nó phơi
ra, chưa đóng:

- **(c-nợ-1) `group_id` mang HAI nghĩa.** Khâu soạn đề hiểu là *gom thành một testcase lớn* (UI:
  *"N testcase nhỏ · một assert fail sẽ làm cả nhóm fail"*), còn fixture + cả 7 mẫu dùng nó làm
  *nhãn rubric* trên 25 dòng / 9 nhóm. ⇒ **hình dạng đề trong mọi mẫu đã phát không phải hình dạng
  khâu soạn đề sinh ra.** Chưa biết bên nào sai; **chưa sửa gì**, đã báo NLP vì họ gom nhận xét theo
  `rubric`.
- **(c-nợ-2) không có cờ phân biệt `expected` máy sinh với `expected` giảng viên gõ** trong
  `result.json`. NLP không gỡ lưới theo mốc này được. Làm được ngay bằng `expected_source` lấy từ
  `expected_custom` — nhưng là thêm trường vào hợp đồng nên **chờ NLP xin**.

**2. ~~P6a~~ ✅ XONG 2026-08-08 → còn P6b.** Hình dạng đã thành dữ liệu thật (8/8 mẫu). Những gì
P6a đã chốt bằng code:

- lưu **y nguyên văn** trên cột `exams.requirements` (LONGTEXT, NULL = đề trước P6/legacy);
- tách dòng ở MỘT chỗ: `BatchGradingService.splitRequirements` — bỏ `\r` cuối dòng + dòng trắng,
  còn lại nguyên văn ⇒ phần tử **không bao giờ rỗng/chứa xuống dòng** (luật **A11** khoá);
- **trần NHẬP: 4000 ký tự · 40 dòng** (`TestcaseTemplateService.validateRequirements`) — con số đã
  công bố với NLP, đổi là phải báo (test ghim);
- đường đọc `ResultController.normalizeResultNode` **bù `[]`** cho kết quả lưu trước P6 (bẫy #1);
- `getExamConfig`/`response` trả `requirements` nguyên văn để P6b prefill.

**P6b — ✅ code xong 2026-08-08, ⚠️ CHƯA chạy thật trên stack.** Textarea "Yêu cầu của đề" trong
`frontend/app/teacher/testcases/page.tsx`: gửi `requirements` **nguyên văn không trim**; gương ba
phép chặn của backend (4000 ký tự / 40 dòng / bắt buộc) + bộ đếm sống `x/4000 ký tự · y/40 yêu
cầu` (đếm dòng đúng cách backend đếm — dòng trắng không tính); hai nút lưu gạt khi trống. Trang
này là luồng TẠO MỚI (mã đề phải chưa tồn tại) nên không có prefill; backend vẫn trả
`requirements` trong `getExamConfig` cho luồng sửa sau này. `tsc` xanh.
**Phạm vi chưa đo** (stack đang tắt, guard `/auth/me` chặn cả việc render trang): giao diện chạy
thật + vòng POST → DB. Lần đầu bật stack: vào *Tạo testcase*, thấy ô "Yêu cầu của đề", lưu thử
một đề — đó là phép đo còn thiếu.

## Luồng vận hành thật — chủ đồ án xác nhận 2026-08-08

```
sinh viên nộp ZIP → KHẢO THÍ chấm trong Grader → result.json
   → giảng viên BẤM MỘT NÚT, bot NLP sinh nhận xét cho TOÀN BỘ bài nộp
   → khảo thí gửi HÀNG LOẠT qua Gmail (Grader chưa có code gửi mail — để sau)
   → CHỈ bài bị GẮN CỜ mới có người xem lại
```

Bốn điều rút ra, **đừng suy lại từ đầu**:

1. **Sinh viên KHÔNG truy cập Grader.** Không có entity `Student`; chỉ hai tài khoản nội bộ (giảng
   viên + khảo thí). `result.json` không tới tay sinh viên.
2. Vậy luật *"không lộ khoá nội bộ"* vẫn giữ, nhưng lý do là **vệ sinh đầu vào cho bot** — bot có
   thể chép nguyên chúng vào nhận xét.
3. **Gửi hàng loạt** ⇒ một chữ sai không dừng ở một sinh viên; **cờ của bot NLP là chốt kiểm soát
   duy nhất của con người** trong cả luồng.
4. **`actual` = điều Grader QUAN SÁT ĐƯỢC từ hành vi app**, không phải sự thật *về mã nguồn*. Engine
   chấm blackbox qua semantic key, **không đọc code sinh viên**. Nói *"hàm delete thiếu setState"* là
   suy đoán ⇒ cấm. Ba trạng thái: `passed` → "Đã đáp ứng yêu cầu"; `failed` → điều quan sát được;
   `not_run` → **vì sao chưa quan sát được gì**.

## Luật làm việc (chủ đồ án đặt, giữ nguyên)

- ⛔ **Không code khi chưa được cho phép.** Xin phép theo từng mốc.
- ✅ **Tự ghi `CHANGELOG_FOR_NLP.md`** khi có việc cần bàn với phía NLP — không phải hỏi. Nhưng
  **code thì vẫn xin phép**.
- 🤝 *"Như hai con người, phản biện nhau xong mới bắt tay vào làm; còn cãi nhau thì phải giải quyết."*
- 📏 **Đáp án viết tay TRƯỚC khi chạy, commit riêng.** Lệch thì **mặc định engine sai**; muốn sửa
  `expected/*.json` phải nói được vì sao đáp án tay ghi sai. Luật này đã bắt được khiếm khuyết thứ tư.
- 🔁 Nhắc chủ đồ án commit theo từng mốc, đừng để dồn.
- ✂️ Trả lời ngắn gọn, hỏi gì đáp nấy.

## Bài học đắt nhất, lặp lại 5 lần — đọc kỹ

**Phát biểu rộng hơn phép đo.** Mọi lần đều cùng hình dạng: một cổng/mẫu/lưới xanh, rồi kết luận
"đường đó an toàn" — trong khi nó xanh vì **bộ đo né ca đó**.

| Lần | Tưởng | Thật |
|---|---|---|
| 1 | fixture đo được engine | fixture **né** 3 khiếm khuyết chấm sai điểm (P3b) |
| 2 | "phủ 23/23 runner" | đó là *đã gọi*, chỉ 14/22 từng chạy đường **hỏng** (A2b) |
| 3 | "0 test thiếu `observation`" | vì fixture không có bài nào ném lỗi giữa runner (A2c) |
| 4 | "cổng độ phủ của tôi có lỗ hổng" | **không có lỗ hổng** — cổng đã tồn tại từ P1, tôi soát nhầm class |
| 5 | "`expected` sạch" | 25/25 `expected` của fixture **gõ tay**; đường máy sinh chưa đo lần nào |
| 6 | "(c) đóng xong vùng mù khâu soạn đề" | chỉ đóng **một trường**; lần đo đầu tiên lộ ngay `group_id` mang hai nghĩa (c-nợ-1) |

Kèm hai lần phát biểu sai về **code của phía NLP** mà không chạy thử, một lần còn kèm số dòng file.
**Quy tắc rút ra: đo trước khi phát biểu, và nói rõ phạm vi đã đo.**

---

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
| **A2** | Mở rộng fixture: 10 runner chưa chạy + 7 `kind` chưa từng phát | ✅ **Xong** 2026-08-07 | Fixture 13 → **24 testcase**, phủ **23/23 runner** và **13/13 `kind`**; bài nộp thứ 5 `sloppy`; tìm ra **khiếm khuyết chấm sai điểm thứ tư**; 7 `kind` lên Mức 1 |
| **A2b** | Tám runner *chỉ từng đạt* phải chạy cả đường **HỎNG** | ✅ **Xong** 2026-08-07 | Fixture → **25 testcase**, bài nộp thứ 6 `unwired`; **22/22 runner đủ hai đường**; bít **hai lỗ hổng kênh quan sát**; `kind` thứ 14 `TYPE_MISMATCH`; **0 test `failed` thiếu `observation`**; điểm 5 bài cũ KHÔNG đổi |
| **A2c** | Trả nợ hai lỗ hổng NLP phơi ra | ✅ **Xong** 2026-08-07 | Tìm ra ca **CHẨN ĐOÁN SAI LỆCH**; `kind` thứ 15 `ACTION_FAILED`; bài nộp thứ 7 `broken-action`; mã classifier chạy thật **0/9 → 3/9**; 0 luật FAIL trên 7 mẫu |
| **(c)** | Dọn `expected` MÁY SINH — lần đầu đo **đường soạn đề** | ✅ **Xong** 2026-08-08 | 22/22 template sạch khoá + sạch enum Anh; 7 test mới (**76 xanh**); luật **F4** trong `verify_result.py`; mẫu thứ 8 `latest-machine-expected`; **0 FAIL trên 8 mẫu**; điểm KHÔNG đổi (không đụng engine) |
| **P6a** | `exam.requirements` — backend + hợp đồng + mẫu | ✅ **Xong** 2026-08-08 | 8/8 mẫu mang 6 yêu cầu thật (2 ca cố ý: đường dẫn trong đề · yêu cầu kiến trúc không kiểm được); trần 4000 ký tự/40 dòng; luật **A11** (thử phá 5 kiểu: 4 đỏ đúng, 1 xanh đúng); **85 test xanh**; SPEC có bảng field khối `exam`; engine KHÔNG đổi |
| **P6b** | `exam.requirements` — ô nhập FE | ✅ **Code xong** 2026-08-08 | `tsc` xanh; gửi nguyên văn không trim; gương 3 phép chặn + bộ đếm sống; ⚠️ **chưa chạy thật** — stack tắt, đo ở lần bật stack tới |
| **NPK** | Ngữ pháp khoá — NLP xin sau P6a | ✅ **Xong** 2026-08-08 | `common-key-grammar.json`: **13 namespace** sinh từ phép đo (attested của NLP thiếu 8; `validation.` là legacy); pin bằng `KeyGrammarContractTest` — thử phá 2 chiều đều đỏ; **86 test xanh**; câu hỏi mở: có cưỡng chế ở khâu nhập không (chờ NLP + chủ đồ án) |

### A2 — mở fixture, và cái giá của việc công bố năng lực chưa chạy

Trước A2, hợp đồng công bố 23 runner và 13 `kind`, nhưng fixture chỉ chạm tới **13 runner** và
**6 `kind`**. A2 đóng khoảng cách đó: 24 testcase, phủ đủ **23/23 runner** và **13/13 `kind`**.

**Chỉ riêng việc chạy đã tìm ra khiếm khuyết chấm sai điểm thứ tư.** `WIDGET_SEMANTICS_LABEL` trả
`SemanticsHandle` bằng `addTearDown`, mà `flutter_test` kiểm handle **ngay khi thân test kết thúc**,
trước lúc `addTearDown` chạy ⇒ runner đó **không bao giờ đạt được**. Sinh viên gắn nhãn trợ năng
đúng vẫn mất điểm, và lỗi báo về là log nội bộ của bộ chấm. Kèm theo: gộp cả loạt dump lỗi bố cục
làm `result_json` phình **745 KB** cho một bài nộp.

Đây là lần thứ hai bài học P3b tự chứng minh: **năng lực chưa chạy thì chưa biết nó có đúng không**,
và công bố nó trong hợp đồng là mời bên đọc xây trên cát. Nên A2 khoá độ phủ bằng test, không bằng
lời hứa trong README:

| Test | Chặn điều gì | Cần Docker |
|---|---|---|
| `fixtureExercisesEveryCommonRunner` | thêm runner mà quên testcase | không |
| `fixtureEmitsEveryObservationKind` | thêm `kind` mà quên cấy lỗi tương ứng | có |
| `everyRunnerHasBothAPassAndAFailSomewhere` (A2b) | runner chỉ chạy một đường | có |

#### A2b — "đã gọi" không phải "đã hỏng"

A2 vòng 1 công bố "phủ 23/23 runner", rồi tôi tự soát lại và thấy mình **overclaim đúng kiểu A2 dựng
ra để chống**: 23/23 là *đã được gọi*. Đo lại thì chỉ **14/22** runner từng đi qua đường hỏng.

Và tám runner chưa chạy đường hỏng **chính là nơi hai lỗ hổng còn lại của kênh quan sát nằm** — cả
hai làm `observation` null nên sinh viên nhận log tiếng Anh:

| Lỗ hổng | Phạm vi | Sửa |
|---|---|---|
| `tester.tap` không kiểm nút có tồn tại | **tám** chỗ | qua `_tap()`, phát `MISSING` |
| `_assertTargetType` `fail()` trần | **chín** runner gọi | phát `TYPE_MISMATCH` (`kind` thứ 14) |

Ở A2 tôi đã **thấy** cả hai nhưng cố ý không sửa, vì lúc đó không bài nộp nào chạm vào — sửa là thêm
code chưa ai chạy, đúng thứ A2 đang dọn. Bài `unwired` tạo đúng điều kiện đó: **cấy lỗi trước, sửa
sau**. Nay `0` test `failed` nào thiếu `observation`.

`TYPE_MISMATCH` cố ý **không** gộp vào `MISSING`: gộp thì câu góp ý bảo sinh viên *thêm thành phần
còn thiếu* vào chỗ **đã có sẵn một cái** — em ấy thêm cái thứ hai.

Cổng thứ ba: **`everyRunnerHasBothAPassAndAFailSomewhere`** — mỗi runner phải từng ĐẠT và từng HỎNG.
Đường đạt và đường hỏng là hai đường khác nhau; phủ một cái không nói gì về cái kia.

**Luật mới của A2 — đáp án viết tay trước khi chạy.** P3b có mỏ neo: `expected/*.json` không đụng
tới nên chính nó làm trọng tài. A2 không có mỏ neo — cùng một người vừa viết bài nộp, vừa cấy lỗi,
vừa viết đáp án. Nên `expected/*.json` được viết tay và **commit riêng** (`a261ee2`) trước khi chạy
Docker lần đầu; sau khi chạy, lệch thì **mặc định engine sai**. Áp dụng lần đầu đã ra kết quả: đáp
án tay chỉ đúng **một** chỗ lệch, và chỗ đó là khiếm khuyết engine — đáp án không sửa một chữ.
Ở A2b thì **6/6 khớp ngay lần đầu**, kể cả bốn con số điểm phải giữ nguyên khi mẫu số đổi từ 24 lên
25 testcase — vì trọng số được chọn có chủ đích để đúng như vậy.

#### A2c — khuyết tật nặng nhất không phải sai điểm, mà là NÓI SAI NGUYÊN NHÂN

Phía NLP đo lại bộ mẫu A2b và phát hiện hai chỗ tôi yếu hơn mức họ đang tin (xem
`CHANGELOG_FOR_GRADER.md` `e861d21`). Trả nợ cả hai thì lộ ra thứ nặng hơn cả hai:

`flutter_test` **không dừng thân test** khi handler của bài ném lỗi. Nó bắt lấy, test chạy tiếp, rồi
phần khẳng định của runner hỏng vì *hệ quả* — và luật A1 *"quan sát thắng classifier"* ghi đè nguyên
nhân gốc bằng triệu chứng. App ném `RangeError` lúc bấm xoá, báo cáo nói *"không thấy hộp thoại nào"*
với `error_code: WIDGET_NOT_FOUND`. **Sai lệch tệ hơn xấu**: sinh viên đi tìm widget thiếu.

Luật A1 là của tôi, và tôi dựng nó trên phép đo **không có ca crash nào** — bijection 12↔12 đo trên
dữ liệu chưa từng chạm đường này. Bài học: *"đo xong thấy an toàn"* chỉ đúng trong phạm vi dữ liệu đã
đo, và phạm vi đó phải nói ra cùng với kết luận.

Sửa: `_failIfActionThrew` sau mọi thao tác (10 chỗ) phát **`ACTION_FAILED`** — `kind` thứ 15, cố ý
**không mang `error_code`** để classifier giữ độ mịn. Ở ca này **classifier biết nhiều hơn engine**:
engine chỉ biết *"app ném lỗi"*, classifier bóc được loại. Hai nguồn bổ sung nhau, không cạnh tranh —
và đó là bằng chứng cụ thể cho việc `kind` **hẹp hơn** `error_code`.

Hai lỗ hổng ban đầu cũng vá xong: lưới song ánh `kind`→`error_code` chỉ quét 7/12 (danh sách chép
tay) → nay lấy từ chính bảng; và bất biến `actual_source == "observation"` là overclaim (xanh vì
fixture né) → nay kiểm **nội dung** `actual` không chứa log của bộ chấm.

### (c) — bộ đo đứng sai chỗ, không phải lưới thủng

Suốt P0→A2c mọi phép đo đều đặt trên **đường CHẤM**. `expected` thì đi đường **SOẠN ĐỀ**, và
`exam/skills_matrix.json` của fixture là **JSON gõ tay** — chưa đi qua `TestcaseTemplateService`
lần nào. Nên 20/22 template nhét thẳng khoá vào `expected` mà **không cổng nào đỏ**, kể cả ba cổng
độ phủ của A2/A2b: chúng canh runner và `kind`, tức canh khâu chấm.

Ba thứ (c) để lại, xếp theo giá trị:

1. **Từ điển là VŨ TRỤ GIÁ TRỊ, không phải bảng tra song song.** `enumUniverse(param, fallback)`
   lấy tập giá trị hợp lệ của `targetType`/`comparison`/`fontWeight`/… **từ chính từ điển**. Thêm
   giá trị mà quên nhãn ⇒ **không lưu được đề**, thay vì lưu êm rồi chữ Anh đi vào báo cáo gửi hàng
   loạt. Chép tay danh sách vào test là đúng lỗi A2c đã mắc.
2. **`renderExpected` có BẢN SONG SINH ở frontend** ([page.tsx:131](../frontend/app/teacher/testcases/page.tsx#L131)),
   và **bản FE mới là bản được lưu** — backend chỉ dùng bản của nó khi FE gửi rỗng, lệch thì đánh
   dấu `expected_custom: true`. Sửa một bên là câu máy sinh bị ghi nhận nhầm thành "giảng viên tự
   gõ", im lặng. Cùng bẫy `student_safe_summary`. Khoá bằng test quét mã FE.
3. **Thử phá từng chỗ, không tin màu xanh:** trả lại template cũ ⇒ 3 luật đỏ, in ra đúng câu NLP
   báo (`Bấm action.delete.1 phải mở dialog.delete`); bỏ từ điển ở một lời gọi FE ⇒ đỏ; xoá một
   nhãn ⇒ đỏ. Luật F4 cấy lại câu cũ vào mẫu ⇒ đỏ.

**Phạm vi đã đo, đừng phát biểu rộng hơn:** chỉ trường `expected`. Phần còn lại của khâu soạn đề
vẫn chưa có phép đo nào — và ngay lần đo đầu đã lộ (c-nợ-1: `group_id` hai nghĩa).

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
| **Dựng `expected` máy sinh** — HAI bản phải khớp | `TestcaseTemplateService.renderExpected` **và** `frontend/app/teacher/testcases/page.tsx` (bản FE là bản được lưu) |
| Từ vựng tiếng Việt của `expected` (nguồn DUY NHẤT, và là vũ trụ giá trị enum) | `grader/src/main/resources/common-expected-vocabulary.json` |
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
