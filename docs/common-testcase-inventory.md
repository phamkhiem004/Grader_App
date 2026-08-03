# Kiểm kê testcase trong kho đề

Đã rà các bộ testcase dưới `exams/`, gồm 46 file `skills_matrix.json`, 45 file
`exam_test.dart` và các bản archive. Có khoảng 577 ID testcase, trong đó nhiều ID
lặp lại giữa các phiên bản đề User Manager và Expense/Transaction.

## Nhóm có thể dùng chung

Các nhóm sau có thể dùng common engine nếu đề công bố semantic key ổn định:

- App boot và widget tồn tại.
- Kiểm tra widget đúng loại: `form`, `image`, `text`, `input`, `button`, `dialog`.
- Kiểm tra text bằng `equals` hoặc `contains`.
- Kiểm tra width/height với `targetKey`, `targetType`, phép so sánh và sai số.
- Kiểm tra padding, text style và khoảng cách render giữa hai target.
- Form validation: nhập giá trị lỗi theo từng field, submit và kiểm tra error key.
- Form prefill khi edit và form submit với dữ liệu hợp lệ.
- Button/input/checkbox/switch/dropdown enabled hoặc disabled.
- List render đúng các item key và số lượng item.
- Dialog flow: mở dialog, chọn cancel/confirm, kiểm tra kết quả.
- Semantics label cho accessibility.
- Navigation bằng key, responsive không overflow và responsive giữ target ở cả portrait/landscape.
- State reactive qua UI: kiểm tra state ban đầu, action và state sau cập nhật; phù hợp
  cho Riverpod khi chỉ cần chấm hành vi black-box.

Các template tương ứng nằm trong `common-testcase-templates.json` và chạy qua
`common-testcase-engine/exam_test.dart`.

## Nhóm không đưa vào common engine

Các testcase dưới đây xuất hiện nhiều trong kho nhưng phụ thuộc public API, tên file,
package hoặc dữ liệu của từng đề:

- Model: constructor, field, `copyWith`, JSON/SQLite mapping, equality, immutability.
- Function: validate title/amount/date, parse, normalize, format, date range.
- Store/repository: add/update/delete, duplicate ID, search, filter, sort, totals,
  grouping, daily summary và business rules.
- Async: Future, Stream, event-loop order, retry và loading/error state.
- Architecture: provider, Riverpod, MVVM, SQLite, persistence và architecture audit.

Những nhóm này cần profile kiểm tra riêng. Chỉ có thể dùng lại khi nhiều đề cùng
công bố một API chuẩn, ví dụ cùng tên class/hàm và cùng kiểu dữ liệu. Đưa chúng vào
common engine bằng cách thay tên trong parameters là không đủ vì Dart test vẫn phải
import và gọi symbol cụ thể.

Riverpod có hai mức kiểm tra:

- Common black-box: `STATE_REACTIVE_FLOW` chỉ kiểm tra trạng thái quan sát được qua UI,
  nên không ép tên provider, class hay file generated.
- Profile kiến trúc: các yêu cầu như phải có `ProviderScope`, provider symbol,
  `ref.watch`, generator hoặc ViewModel cụ thể vẫn cần layered testcase/profile riêng,
  vì phải đọc/import API của từng đề.

## Gộp testcase

Các testcase common có thể được chọn từ hai mục trở lên và gộp thành một group. Mỗi
item trong group vẫn giữ runner và parameters riêng; khi sinh matrix, group trở thành
một testcase cha. Engine chạy hết các item con, gom lỗi, và chỉ cho group pass khi tất
cả assert con đều pass. Trọng số group bằng tổng trọng số các item con.

## Quy tắc lọc

Một testcase chỉ được đưa vào thư viện dùng chung nếu:

1. Có thể tìm target bằng semantic key hoặc tham số chuẩn hóa.
2. Không import model/repository/provider riêng của một đề.
3. Kết quả kiểm tra không phụ thuộc text nghiệp vụ cố định, trừ khi text đó là
   parameter của instance đề.
4. Có thể mô tả rõ input, expected và sai số trong `parameters_schema`.

`targetKey` định vị widget; `targetType` xác nhận key được gắn đúng widget. Vì vậy
hai đề cùng kiểm tra `height` nhưng một đề nhắm vào `form`, đề kia nhắm vào `image`
vẫn dùng chung runner mà không bị lẫn target.
