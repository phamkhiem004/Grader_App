# Bộ chấm Golden Record–Abstract–Replay

## Mục tiêu

Giảng viên không viết `exam_test.dart` bằng tay. Hệ thống chạy ứng dụng đáp án
(Golden Solution), ghi thao tác theo ngữ nghĩa, tách dữ liệu nhập thành biến và
replay cùng hành vi trên bài sinh viên. Kết quả được đối chiếu ở cả UI và SQLite.

UI được nhận diện theo semantic label, hint hoặc text; không ghi tọa độ và không
so ảnh pixel. Vì vậy sinh viên được tự thiết kế giao diện miễn là hành vi và nội
dung công khai của đề vẫn đúng.

## Bảy thành phần của một bộ chấm

1. `STUDENT_DATABASE`: database mẫu được phát cho sinh viên.
2. `HIDDEN_DATABASE`: cùng schema nhưng dữ liệu bí mật dùng khi chấm.
3. `GOLDEN_SOLUTION`: ZIP ứng dụng đáp án.
4. `AUTOMATION_RECORD`: trace thao tác do hệ thống sinh khi dừng record.
5. `GRADING_ENVIRONMENT`: cấu hình runtime/package/môi trường chấm.
6. `TESTCASE_DEFINITION`: scenario đã trừu tượng hóa, gồm Stage, Attribute,
   AttributeValue, ValueType, Value, Action và Browser.
7. `OUTPUT_DATABASE`: trạng thái SQLite sau khi chạy luồng trên Golden Solution.

Giảng viên chỉ cung cấp ba thành phần đầu. Bốn thành phần 4–7 do hệ thống tự sinh:
record tạo Automation Record, bước abstract tạo Testcase Definition, và replay
Golden Solution với Hidden Database tự capture Output Database.

## Quy trình trên giao diện

1. Từ **Quản lý bộ testcase**, chọn **Tạo bộ testcase** rồi **Tạo từ Golden Solution**.
2. Khởi tạo Golden app và suite, khai báo mã đề và tên file SQLite.
3. Tải Student DB, Hidden DB và Golden ZIP. Môi trường chấm được sinh từ cấu hình suite.
4. Bấm **Build & mở Golden**. Backend build Flutter Web trong Docker và nhúng
   recorder semantic.
5. Bấm **Bắt đầu record**, thao tác trực tiếp trong Golden app và chụp checkpoint UI.
6. Có thể thêm checkpoint DB có chủ đích. Khi dừng record, hệ thống tự replay toàn bộ
   luồng trên Golden với Hidden DB, capture Output DB và so hai database để tách
   INSERT, UPDATE và DELETE; giảng viên không upload Output DB bằng tay.
7. Dừng record. Hệ thống đổi dữ liệu cụ thể thành biến có generator, sinh scenario,
   oracle và testcase definition.
8. Chọn viewport điện thoại và desktop, chạy preflight trên Golden rồi publish.

## File sinh ra khi publish

- `exam_test.dart`: replay engine Flutter duy nhất.
- `grader.dart`: runner và bộ tổng hợp kết quả.
- `behavior_plan.json`: scenario, steps, checkpoint, biến và viewport.
- `skills_matrix.json`: mã tiêu chí, trọng số và checkpoint độc lập.
- `contract.json`: contract công khai và quy tắc package/database.
- `suite_manifest.json`: phiên bản và checksum artifact để audit.
- `fixtures/student.db`, `fixtures/hidden.db`, `fixtures/expected-output.db`.

Các file này được materialize nguyên tử vào thư mục testcase của Exam. Batch
grader hiện hữu tiếp tục mount thư mục đó vào container bài sinh viên; không cần
thêm một hệ thống chấm lô thứ hai.

## Quy tắc an toàn và giới hạn hiện tại

- Golden ZIP được giới hạn số entry/dung lượng giải nén, chặn Zip Slip và symlink.
- Golden runtime và oracle gắn với SHA-256; thay Golden hoặc Output DB làm kết quả
  cũ hết hiệu lực.
- Mỗi scenario/viewport chạy trong một Flutter process riêng và dùng DB riêng.
- Recorder chỉ chấp nhận đích có semantic locator. Widget không có semantics sẽ
  hiện cảnh báo để giảng viên bổ sung label, không âm thầm lưu tọa độ dễ sai.
- Flutter Web chỉ dùng để ghi thao tác semantic. Việc tạo oracle SQLite diễn ra trong
  container bằng cùng plan và Hidden DB, vì vậy không phụ thuộc browser có truy cập
  được file SQLite hay không.
- Backend testcase cũ được giữ để các bộ đã publish vẫn chấm được, nhưng màn tạo
  bộ mới chỉ đi theo Golden Record–Abstract–Replay.
