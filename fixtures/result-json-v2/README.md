# Fixture nghiệm thu `result.json` v2

Bộ dữ liệu cố định để đo mọi thay đổi của kế hoạch P1–P5 (xem `SPEC_grader_result_json/`).

Lý do tồn tại: `exams/` và `submissions/` đều bị gitignore, nên trước bộ này repo **không có
dữ liệu nào để đo**. Không có thước đo thì không phase nào nghiệm thu được.

## Chạy

```bash
./run-fixture.sh                 # chấm cả 4 bài rồi so với expected/
./run-fixture.sh medium          # chấm 1 bài
```

Cần Docker đang chạy và ảnh `grading-base:latest`. Mỗi bài mất ~30–60 giây.

Script tự dựng thư mục testcase từ **engine chung** (`grader/src/main/resources/common-testcase-engine/`)
ghép với `exam/skills_matrix.json`, nên nó luôn đo đúng engine hiện tại trong repo.

## Bộ đề

`exam/skills_matrix.json` — 13 testcase, tổng trọng số 100, phủ cả ba `layer` mà engine chung
sinh ra và 5 nhóm chức năng (`rubric`).

| `layer` | Testcase |
|---|---|
| `integration` | TC_APP_BOOT, TC_SAVE_ENABLED, TC_ADD_USER (GROUP), TC_VALIDATE_FIELDS, TC_DELETE_CONFIRM, TC_DETAIL_NAV |
| `widget` | TC_HOME_TITLE, TC_LIST_VISIBLE, TC_LIST_COUNT, TC_NAME_FIELD, TC_EMAIL_FIELD |
| `responsive` | TC_RESPONSIVE_NO_OVERFLOW, TC_RESPONSIVE_TARGET |

`TC_ADD_USER` là testcase `GROUP` có hai con (`WIDGET_VISIBLE` + `FORM_SUBMIT`) — dùng để kiểm
luật **B2** (nhóm lấy tầng cao nhất của các con: `widget` + `integration` → `integration`).

## Bốn bài nộp

| Bài | Điểm | Đạt | Dùng để kiểm |
|---|---|---|---|
| `high` | 10.0 | 13/13 | C7, A6, A7 — trạng thái "mọi thứ đúng" |
| `medium` | 6.0 | 8/13 | C1–C6, B1–B2 — hỏng rải ở 3 nhóm chức năng |
| `broken-boot` | 0.0 | 0/13 | Ứng dụng **biên dịch được nhưng crash ở khung hình đầu**. Mọi test đều CHẠY và cùng hỏng vì một nguyên nhân gốc → ca kiểm `blocked_by` của P4b |
| `broken-compile` | 0.0 | 0/13 | `lib/` **không biên dịch được** → không test nào chạy → ca kiểm `not_run`, A9, A10, C8 |

Hai bài hỏng tách đôi có chủ đích: chúng là **hai cơ chế khác nhau**, và bản 1 của SPEC gộp
chung làm một nên mới bế tắc ở `blocked_by`.

Lỗi được cấy vào `medium`: sai tiêu đề màn hình · bỏ hẳn kiểm tra dữ liệu nhập · xoá không hỏi
xác nhận · bố cục tràn khung ở máy tính bảng ngang.

## Cách đo

**1. Điểm không đổi** — `run-fixture.sh` so `expected/*.json` (điểm, số đạt, danh sách test hỏng).
Đây là cổng bắt buộc của **P3**: engine v2 không được làm lệch một điểm nào.
Cố ý **không** so nội dung `actual`, vì P5 sẽ thay đổi nó.

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

## Hai khiếm khuyết của engine chung phát hiện khi dựng fixture

Đây là lỗi **chấm sai điểm**, không phải lỗi hiển thị — sinh viên làm đúng vẫn có thể mất điểm.

**1. `_byKey` có fallback theo vai trò, làm mọi phép kiểm "đã biến mất" trở nên vô nghĩa.**
Khi không tìm thấy `ValueKey` chính xác, `exam_test.dart` tự đoán một finder thay thế. Hệ quả:

- `_byKey('item.1')` → `ListTile` ở **chỉ số 0**. Sinh viên xoá đúng người dùng thì `item.1` biến
  mất, nhưng finder lại bắt được `item.2` đang đứng đầu ⇒ `DIALOG_FLOW.absentKey` **luôn hỏng**.
- `_byKey('error.name')` → bất kỳ `Text` nào khớp `name|họ|tên|full` ⇒ bắt trúng **nhãn của ô nhập**
  (`"Full name"`) ⇒ `FORM_SUBMIT.errorKeys` (đòi *không còn* lỗi) **luôn hỏng**.

Ảnh hưởng mọi runner khẳng định sự vắng mặt: `FORM_SUBMIT.errorKeys`, `DIALOG_FLOW.absentKey`,
`STATE_REACTIVE_FLOW.absentKey`.

Fixture né bằng cách đặt id người dùng từ **5** (`item.5`, `item.6`) và đặt khoá thông báo lỗi là
`validation.*` thay vì `error.*` — hai dạng khoá này rơi vào nhánh `default` nên trả về finder rỗng
đúng nghĩa. **Đây là cách né, không phải cách sửa.**

**2. Thao tác chạm ngay sau khi mở màn hình mới bị lớp phủ chuyển cảnh nuốt.**
`_settle()` chỉ chờ 300ms thời gian thật rồi `pump()`, mà `pump()` không đẩy đồng hồ ảo của test
nên hoạt ảnh chuyển route **không bao giờ chạy xong**. Nút nằm trên `AppBar` của màn hình mới bị
`AbsorbPointer` chắn, `tester.tap` trượt và chỉ ghi một dòng cảnh báo.

Kèm theo: phép kiểm `homeKey` của `NAVIGATION` gần như **vô nghĩa**, vì màn hình trước vẫn nằm
trong cây widget suốt lúc màn hình sau đang mở.

Fixture né bằng cách đặt nút quay lại **trong `body`** thay vì trên `AppBar`.

> Cả hai nên được xử lý ở **P3** (engine v2). Ghi ở đây để không rơi mất.

## Ghi chú

- Bài nộp chỉ gồm `lib/` và `pubspec.yaml`; container chỉ mount `lib/` còn `pubspec.yaml` lấy bản
  đóng băng trong ảnh nền. Bản trong này chép nguyên từ `grader-base/pubspec.base.yaml` để bài nộp
  giống bài thật và vô hại nếu bị mount đè.
- IDE sẽ báo đỏ `Target of URI doesn't exist: 'package:flutter/material.dart'` nếu máy không cài
  Flutter SDK. Bình thường — bộ này chỉ biên dịch bên trong container.
- `.build/` là thư mục tạm do script sinh, đã được gitignore.
