# Fixture nghiệm thu `result.json` v2

Bộ dữ liệu cố định để đo mọi thay đổi của kế hoạch P1–P5 (xem `SPEC_grader_result_json/`).

Lý do tồn tại: `exams/` và `submissions/` đều bị gitignore, nên trước bộ này repo **không có
dữ liệu nào để đo**. Không có thước đo thì không phase nào nghiệm thu được.

## Chạy

```bash
./run-fixture.sh                 # chấm cả 5 bài rồi so với expected/
./run-fixture.sh medium          # chấm 1 bài
```

Cần Docker đang chạy và ảnh `grading-base:latest`. Mỗi bài mất ~30–60 giây.

Script tự dựng thư mục testcase từ **engine chung** (`grader/src/main/resources/common-testcase-engine/`)
ghép với `exam/skills_matrix.json`, nên nó luôn đo đúng engine hiện tại trong repo.

## Bộ đề

`exam/skills_matrix.json` — **24 testcase**, tổng trọng số 100, phủ **đủ 23/23 runner** của engine
chung, cả ba `layer` và 9 nhóm chức năng (`rubric`).

| `layer` | Testcase |
|---|---|
| `integration` | TC_APP_BOOT, TC_ADD_USER (GROUP), TC_VALIDATE_FIELDS, TC_REQUIRED_FIELDS, TC_DELETE_CONFIRM, TC_CLEAR_ALL, TC_DETAIL_NAV, TC_EDIT_BANNER, TC_EDIT_PREFILL |
| `widget` | TC_HOME_TITLE, TC_TITLE_STYLE, TC_LIST_VISIBLE, TC_LIST_COUNT, TC_HEADER_ICON, TC_HEADER_ICON_LABEL, TC_AVATAR_SIZE, TC_FORM_PADDING, TC_FIELD_GAP, TC_NAME_FIELD, TC_EMAIL_FIELD, TC_SAVE_ENABLED, TC_NOTIFY_ENABLED |
| `responsive` | TC_RESPONSIVE_NO_OVERFLOW, TC_RESPONSIVE_TARGET |

`TC_ADD_USER` là testcase `GROUP` có hai con (`WIDGET_VISIBLE` + `FORM_SUBMIT`) — dùng để kiểm
luật **B2** (nhóm lấy tầng cao nhất của các con: `widget` + `integration` → `integration`).

**Độ phủ runner được khoá bằng test, không chỉ ghi ở đây:**
`FixtureResultAssemblyTest.fixtureExercisesEveryCommonRunner` đối chiếu matrix với
`TestCaseTaxonomy.commonRunners()`. Thêm runner vào engine mà quên bổ sung testcase là đỏ ngay —
vì trước A2 đã có **mười** runner được công bố trong hợp đồng mà chưa từng chạy một lần nào.

## Năm bài nộp

| Bài | Điểm | Đạt | Dùng để kiểm |
|---|---|---|---|
| `high` | 10.0 | 24/24 | C7, A6, A7 — trạng thái "mọi thứ đúng" |
| `medium` | 6.7 | 18/24 | C1–C6, B1–B2 — hỏng rải ở 3 nhóm chức năng |
| `sloppy` | 6.6 | 15/24 | **Chạy được nhưng sai hàng loạt chi tiết đo được.** Bài duy nhất làm 7 `kind` Mức 2 của SPEC 5.5 phát ra thật |
| `broken-boot` | 0.0 | 0/24 | Ứng dụng **biên dịch được nhưng crash ở khung hình đầu**. Mọi test đều CHẠY và cùng hỏng vì một nguyên nhân gốc → ca kiểm `blocked_by` của P4b |
| `broken-compile` | 0.0 | 0/24 | `lib/` **không biên dịch được** → không test nào chạy → ca kiểm `not_run`, A9, A10, C8 |

Hai bài hỏng tách đôi có chủ đích: chúng là **hai cơ chế khác nhau**, và bản 1 của SPEC gộp
chung làm một nên mới bế tắc ở `blocked_by`.

Lỗi được cấy vào `medium`: sai tiêu đề màn hình · bỏ hẳn kiểm tra dữ liệu nhập · xoá không hỏi
xác nhận · bố cục tràn khung ở máy tính bảng ngang.

### `sloppy` — bảy lỗi, mỗi lỗi một `kind`

| Lỗi cấy vào bài | `kind` phát ra | Testcase hỏng |
|---|---|---|
| Seed **1** người dùng thay vì 2 | `COUNT_MISMATCH` | TC_LIST_COUNT (+ TC_LIST_VISIBLE) |
| Ô chọn quên `onChanged` | `ENABLED_MISMATCH` | TC_NOTIFY_ENABLED |
| Tiêu đề đúng cỡ nhưng không in đậm | `STYLE_MISMATCH` | TC_TITLE_STYLE |
| Khung ảnh rộng 64 thay vì 48 | `NUMBER_MISMATCH` | TC_AVATAR_SIZE |
| Nhãn trợ năng để nguyên `"Users"` | `LABEL_MISMATCH` | TC_HEADER_ICON_LABEL |
| Xoá hết chỉ bật thông báo rỗng, không xoá dữ liệu | `STILL_PRESENT` | TC_CLEAR_ALL |
| Bố cục ngang quên `Expanded` cho danh sách | `LAYOUT_ERROR` | TC_RESPONSIVE_* |

Lỗi cuối cố ý **khác** `OVERFLOW`: viewport dọc nhận bề rộng vô hạn thì bố cục *dựng không xong*,
thông điệp không chứa chữ `overflow` — đúng nhánh phân biệt hai `kind` đó của engine.

**Vì sao COUNT_MISMATCH không dùng cách seed 3 người dùng.** Cách đó nghe tự nhiên hơn nhưng làm
`resultKey: item.3` của `FORM_SUBMIT` được thoả sẵn từ trước khi lưu ⇒ TC_ADD_USER thành **phép
kiểm KHÔNG THỂ HỎNG** trên bài này, đúng khuyết tật P3b vừa dọn. Seed 1 người dùng kéo theo
TC_LIST_VISIBLE hỏng, và đó là dữ liệu THẬT đáng có: **một nguyên nhân gốc làm hai testcase hỏng**.

Độ phủ `kind` cũng được khoá bằng test: `fixtureEmitsEveryObservationKind` đòi cả 13 giá trị của
`TestObservationRenderer.renderableKinds()` phải xuất hiện trong output thật của ít nhất một bài.

## Cách đo

**1. Điểm không đổi** — `run-fixture.sh` so `expected/*.json` (điểm, số đạt, danh sách test hỏng).
Đây là cổng bắt buộc của **P3**: engine v2 không được làm lệch một điểm nào.
Cố ý **không** so nội dung `actual`, vì P5 sẽ thay đổi nó.

> Ở **P3b** cổng này đổi vai: điểm vẫn phải bằng `expected/*.json`, nhưng ý nghĩa là *"engine
> nay chấm ĐÚNG bài đúng"* chứ không còn là *"không đổi gì"* — vì fixture đã bỏ cách né nên
> engine cũ **không** đạt được các số này.

> Ở **A2** cổng này cần thêm một luật, vì A2 không có mỏ neo như P3b: P3b **không đụng**
> `expected/*.json` nên chính nó làm trọng tài, còn A2 thì cùng một người vừa viết bài nộp, vừa
> cấy lỗi, vừa viết đáp án — engine chấm lệch là rất dễ sửa đáp án cho khớp rồi tuyên bố "0 lệch".
>
> **Luật A2: viết `expected/*.json` BẰNG TAY và commit RIÊNG trước khi chạy Docker lần đầu.** Sau
> khi chạy, lệch thì **mặc định engine sai**; muốn sửa đáp án thì phải nói được vì sao đáp án tay
> ghi sai, ghi vào commit — không sửa im lặng.
>
> Luật này đã trả kết quả ngay lần đầu áp dụng: đáp án tay chỉ ra **một** chỗ lệch
> (`TC_HEADER_ICON_LABEL` hỏng ở `high` và `medium`), và đó là khiếm khuyết engine thứ tư ở trên —
> không phải đáp án sai. `expected/*.json` không sửa một chữ.

**2. Đúng hợp đồng schema** — `SPEC_grader_result_json/verify_result.py`:

```bash
python ../../../SPEC_grader_result_json/verify_result.py <result.json> \
       --matrix exam/skills_matrix.json \
       --syllabus ../../grader/src/main/resources/syllabus.json
```

⚠️ File trong `.build/out/*.json` là output của **grader.dart**, chưa phải `result.json` đầy đủ —
còn thiếu `student`, `exam`, `skill_name`, `category`, `competency_assessment`… do backend ghép ở
`BatchGradingService.assembleResultJson`. Muốn nghiệm thu trọn bộ luật A–F thì phải chấm qua
backend. Bộ fixture này đã đủ để khoá điểm và để tái hiện lỗi `actual`.

## Khiếm khuyết thứ tư — ✅ đã sửa ở A2

**`WIDGET_SEMANTICS_LABEL` KHÔNG BAO GIỜ ĐẠT ĐƯỢC.** Runner mở kênh trợ năng bằng
`tester.ensureSemantics()` rồi trả handle qua `addTearDown(semantics.dispose)`. Nhưng
`flutter_test` kiểm *"còn SemanticsHandle nào đang mở?"* **ngay khi thân test kết thúc**, trước
khi các `addTearDown` chạy — nên test hỏng dù phép kiểm nhãn đã đạt.

Mất điểm theo cách tệ nhất: `observation` là `null` nên `actual` rơi về log tiếng Anh nói chuyện
nội bộ của bộ chấm (*"All SemanticsHandle instances must be disposed"*), sinh viên không thể đối
chiếu được gì. → Sửa: trả handle trong `finally`, chạy trong cả hai đường (đạt và không đạt).

**Đúng bài học P3b, lặp lại lần nữa:** runner này **chưa từng có testcase nào** trước A2. Không
chạy thì không đo được — và nó đã được công bố trong hợp đồng như một năng lực có thật.

### Kèm theo: `result_json` phình 745 KB

Lỗi bố cục được `flutter_test` báo LẠI mỗi khung hình, mà `_failureMessage` gộp **cả loạt** dump.
Đo trên `sloppy`: một testcase có `actual` dài **174.928 ký tự**, cả bài **745 KB** — chuỗi đó chảy
vào `result_json` lưu DB, ra API, và ra file mẫu gửi phía NLP. → Sửa: chỉ giữ khối chẩn đoán **đầu
tiên**, cắt ở 4.000 ký tự (`_firstBlock`). Không dùng `_shorten` vì nó gộp cả dấu xuống dòng, mà
bộ phân loại lỗi còn cần cấu trúc nhiều dòng cho dữ liệu chưa có `observation`. Điểm không đổi.

## Ba khiếm khuyết CHẤM SAI ĐIỂM của engine chung — ✅ đã sửa ở P3b

Không phải lỗi hiển thị: **điểm sai thật**. Fixture trước đây **né** cả ba nên không đo được
chúng; P3b bỏ hết cách né, nay bộ này chạm thẳng vào từng lỗi và canh không cho tái diễn.

**1. `_byKey` có fallback theo vai trò ⇒ mọi phép kiểm "đã biến mất" vô nghĩa.** Không thấy
`ValueKey` chính xác thì engine tự đoán một finder thay thế:

- `_byKey('item.1')` → `ListTile` ở **chỉ số 0**. Sinh viên xoá đúng người dùng thứ nhất thì
  `item.1` biến mất, nhưng finder lại bắt được `item.2` đang đứng đầu ⇒ `DIALOG_FLOW.absentKey`
  **luôn hỏng**.
- `_byKey('error.name')` → bất kỳ `Text` khớp `name|họ|tên|full` ⇒ bắt trúng **nhãn của ô nhập**
  (`"Full name"`) ⇒ `FORM_SUBMIT.errorKeys` (đòi *không còn* lỗi) **luôn hỏng**.

→ Sửa: thêm `_goneByKey` — chỉ nhận `ValueKey` chính xác, không fallback, bỏ qua widget offstage —
dùng cho cả ba chỗ khẳng định vắng mặt (`FORM_SUBMIT.errorKeys`, `DIALOG_FLOW.absentKey`,
`STATE_REACTIVE_FLOW.absentKey`).

**2. Thao tác chạm sau khi chuyển màn hình bị lớp phủ chuyển cảnh nuốt.** `_settle()` chờ 300ms
thời gian thật rồi `pump()` trần, mà `pump()` không đẩy đồng hồ ảo nên hoạt ảnh chuyển route
**không bao giờ chạy xong**; nút trên `AppBar` màn hình mới bị chắn, `tester.tap` trượt và chỉ
ghi một dòng cảnh báo.

→ Sửa: `_settle` chờ I/O thật rồi `pumpAndSettle` **có giới hạn 2s** (vài nhịp `pump(Duration)`
cố định là KHÔNG đủ — hoạt ảnh pop route còn ticker đang chạy, màn hình cũ chưa bị gỡ khỏi cây).
Đo được: cảnh báo `would not hit test` trong log **1 → 0**.

**3. `_validationErrorFor` cho điểm oan.** Nhánh "khớp tên field" không đòi nội dung phải nói lên
chuyện lỗi, nên **nhãn ô nhập** cũng được tính là thông báo lỗi ⇒ form không kiểm dữ liệu gì vẫn
đạt `FORM_VALIDATE_FIELDS`. Lỗi này chỉ lộ ra sau khi bỏ cách né số 1. → Sửa: bắt buộc `Text` phải
khớp **cả** từ vựng lỗi lẫn tên field.

**Kèm theo — phép kiểm `homeKey` của `NAVIGATION` KHÔNG THỂ HỎNG.** Màn hình trước vẫn nằm trong
cây widget suốt lúc màn hình sau đang mở, nên nút quay lại có bấm được hay không thì `homeKey` vẫn
đạt. Chính nó đã **che** khiếm khuyết 2. → Sửa: kiểm thêm chiều biến mất
(`_goneByKey(destinationKey)` phải rỗng).

### Cách né đã bỏ

| Trước | Nay | Chạm vào lỗi |
|---|---|---|
| id người dùng từ **5** (`item.5`, `item.6`) | từ **1** (`item.1`, `item.2`) | 1 |
| khoá thông báo lỗi `validation.*` | `error.*` | 1 và 3 |
| nút quay lại đặt trong `body` | đặt trên `AppBar` | 2 |

### Chứng minh fixture thật sự canh được

Chạy fixture MỚI bằng engine **trước P3b**:

| Bài | Engine trước P3b | Engine sau P3b |
|---|---|---|
| `high` (làm đúng hoàn toàn) | **7.8** — hỏng oan `TC_ADD_USER`, `TC_DELETE_CONFIRM` | 10.0, 13/13 |
| `medium` | 5.8 — hỏng oan `TC_ADD_USER`, **đạt oan** `TC_VALIDATE_FIELDS` | 6.0, 8/13 |

Hạ riêng `_settle` về bản cũ (giữ nguyên phần còn lại) ⇒ `high` xuống **9.1**, hỏng `TC_DETAIL_NAV`.

`expected/*.json` **không đổi**: 10.0 / 6.0 / 0.0 / 0.0 vốn luôn là kết quả đúng — engine cũ chỉ
không trả nổi.

> Bảng trên đo trên bộ đề **13 testcase** của thời P3b, nên các số `/13` và điểm `6.0` là số cũ.
> A2 mở lên 24 testcase nên mẫu số đổi: `medium` nay 6.7. Cố ý giữ số cũ ở đây — đây là **biên
> bản của một phép đo đã làm**, viết lại theo bộ đề mới là làm sai lịch sử.

## Ghi chú

- Bài nộp chỉ gồm `lib/` và `pubspec.yaml`; container chỉ mount `lib/` còn `pubspec.yaml` lấy bản
  đóng băng trong ảnh nền. Bản trong này chép nguyên từ `grader-base/pubspec.base.yaml` để bài nộp
  giống bài thật và vô hại nếu bị mount đè.
- IDE sẽ báo đỏ `Target of URI doesn't exist: 'package:flutter/material.dart'` nếu máy không cài
  Flutter SDK. Bình thường — bộ này chỉ biên dịch bên trong container.
- `.build/` là thư mục tạm do script sinh, đã được gitignore.
