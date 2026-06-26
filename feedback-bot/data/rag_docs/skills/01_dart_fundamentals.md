# Skill: Nền tảng Dart (Dart Fundamentals)

- **skill (lớn):** `dart_fundamentals`
- **Tên skill:** Nền tảng ngôn ngữ Dart
- **Nguồn:** *Mastering Flutter* (Kevin Moore, 2025), Chương 2 — "Dart Essentials".
- **skill_code trong skill này:** `dart_variables_types`, `dart_null_safety`, `dart_control_flow`, `dart_functions`, `dart_classes_oop`, `dart_enums_mixins_ext`, `dart_exceptions`

---

## skill_code: `dart_variables_types`
**skill_name:** Biến và kiểu dữ liệu · **skill:** `dart_fundamentals`

### Khái niệm
Dart là ngôn ngữ type-safe, đảm bảo biến khớp với kiểu đã khai báo. Khai báo bằng `var` thì kiểu suy ra từ giá trị gán; `final` và `const` cho giá trị không đổi; `late` báo biến sẽ khởi tạo sau. Dart có các kiểu dựng sẵn như `int`, `double`, `String`, `bool`, `List`, `Set`, `Map`, cùng generics dạng `List<T>` và alias qua `typedef`.

### Dấu hiệu đạt yêu cầu
- Chọn kiểu phù hợp (`int`/`double`, `String`, `bool`) và dùng `var` khi kiểu rõ ràng.
- Dùng `final`/`const` cho giá trị không đổi, đặt tên biến private bằng dấu `_` ở đầu.
- Dùng generics `List<T>`, `Map<K,V>` đúng kiểu phần tử.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Dùng biến `late` trước khi khởi tạo → app crash khi chạy.
- Gán sai kiểu cho biến đã có kiểu → compile fail.
- Dùng `var` cho mọi thứ, thiếu `final`/`const` → cảnh báo lint.

### API/Widget chính
`var`, `final`, `const`, `late`, `int`, `double`, `String`, `bool`, `List<T>`, `Set`, `Map`, `dynamic`, `typedef`

### Từ khóa
var, final, const, late, built-in types, generics, typedef

### Nguồn: Mastering Flutter, Ch.2, tr. 46–49

---

## skill_code: `dart_null_safety`
**skill_name:** An toàn null (Null Safety) · **skill:** `dart_fundamentals`

### Khái niệm
Từ Dart 2.12 có null safety; Dart 3.0 toàn bộ kiểu non-nullable mặc định, cần thêm `?` sau kiểu để cho phép null. Tính năng này giúp tránh null pointer exception. Truy cập biến nullable dùng `?.`, lấy giá trị mặc định bằng `??`, và ép không null bằng `!`.

### Dấu hiệu đạt yêu cầu
- Khai báo biến có thể null bằng `Type?` (ví dụ `String? name`).
- Truy cập an toàn qua `?.` và cung cấp giá trị thay thế bằng `??`.
- Chỉ dùng `!` khi chắc chắn biến không null.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Dùng `!` trên biến đang null → app crash khi chạy.
- Truy cập trực tiếp biến nullable không qua `?.` → compile fail.
- Quên `?` ở kiểu nhưng gán null → compile fail.

### API/Widget chính
`Type?`, `?.`, `??`, `!`, non-nullable, nullable

### Từ khóa
null safety, nullable, non-nullable, null-aware operator

### Nguồn: Mastering Flutter, Ch.2, tr. 49–50

---

## skill_code: `dart_control_flow`
**skill_name:** Luồng điều khiển · **skill:** `dart_fundamentals`

### Khái niệm
Control flow quyết định hành động theo giá trị biến. Dart có rẽ nhánh `if`/`else if`/`else` và `switch` (gồm cả switch expression dạng `pattern => expression` với `_` mặc định). Vòng lặp gồm `for`, `for-in`, `while` và `do...while`. Hai từ khóa `break` và `continue` thay đổi luồng lặp.

### Dấu hiệu đạt yêu cầu
- Dùng `if/else if/else` cho rẽ nhánh đơn, `switch` với `default` cho nhiều trường hợp.
- Dùng `for-in` khi duyệt list để tránh lỗi chỉ số.
- Dùng `break`/`continue` đúng mục đích thoát hoặc bỏ qua vòng lặp.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Thiếu `default` trong `switch` enum → bỏ sót trường hợp, test fail.
- Sai điều kiện hoặc chỉ số trong `for` truyền thống → lặp sai, test fail.
- `do...while` luôn chạy ít nhất một lần → kết quả ngoài mong đợi.

### API/Widget chính
`if`, `else if`, `else`, `switch`, `case`, `default`, `for`, `for-in`, `while`, `do...while`, `break`, `continue`

### Từ khóa
control flow, if else, switch, for loop, while, break, continue

### Nguồn: Mastering Flutter, Ch.2, tr. 51–53

---

## skill_code: `dart_functions`
**skill_name:** Hàm trong Dart · **skill:** `dart_fundamentals`

### Khái niệm
Hàm là đoạn code tái sử dụng, định nghĩa theo dạng `returnType tên(kiểu tên)`; dùng `void` khi không trả giá trị. Hàm một dòng dùng arrow syntax `=>`. Tham số có ba dạng: plain, named (bọc `{}`), và optional (bọc `[]`). Mọi app bắt đầu từ hàm `main`. Có thể gán hàm cho biến và dùng anonymous function.

### Dấu hiệu đạt yêu cầu
- Khai báo đúng kiểu trả về và kiểu tham số; dùng `void` khi không trả giá trị.
- Dùng named parameter `{}` hoặc optional `[]` đúng nhu cầu.
- Dùng arrow `=>` cho hàm một dòng, anonymous function cho callback.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Quên `return` trong hàm có kiểu trả về → compile fail.
- Gọi sai tên/thứ tự named parameter → compile fail.
- Sai kiểu tham số truyền vào → compile fail.

### API/Widget chính
`returnType name(...)`, `void`, `=>`, named `{}`, optional `[]`, `main()`, anonymous function

### Từ khóa
functions, arrow syntax, named parameters, optional parameters, main

### Nguồn: Mastering Flutter, Ch.2, tr. 54–55

---

## skill_code: `dart_classes_oop`
**skill_name:** Lớp và lập trình hướng đối tượng · **skill:** `dart_fundamentals`

### Khái niệm
Class là khuôn mẫu tạo object, gồm field và method. Dart chỉ kế thừa đơn qua `extends` và gọi cha bằng `super`. Field `final` phải khởi tạo trong constructor; named constructor dùng `{}` với `required` cho field bắt buộc. Override method dùng `@override`. Field/method của lớp dùng `static`. String interpolation dùng `$` và `${}`.

### Dấu hiệu đạt yêu cầu
- Constructor khởi tạo đúng các field `final`, dùng `required` cho tham số bắt buộc.
- Kế thừa bằng `extends`, truyền giá trị lên cha bằng `super`.
- Đánh dấu `@override` khi ghi đè method, dùng `static` cho thành viên của lớp.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Field `final` không được khởi tạo trong constructor → compile fail.
- Thiếu `required` cho named parameter bắt buộc → compile fail.
- Quên `super` khi cha cần tham số → compile fail.

### API/Widget chính
`class`, `extends`, `super`, `this`, `required`, named constructor `{}`, `@override`, `static`, string interpolation `$`, `${}`

### Từ khóa
class, constructor, extends, super, override, static, interpolation

### Nguồn: Mastering Flutter, Ch.2, tr. 58–62

---

## skill_code: `dart_enums_mixins_ext`
**skill_name:** Enum, Mixin và Extension · **skill:** `dart_fundamentals`

### Khái niệm
`enum` là lớp đặc biệt có tập giá trị hằng cố định, có thể chứa field và constructor; lấy vị trí bằng `index`, lấy tất cả bằng `.values`. `mixin` cung cấp chức năng dùng chung qua `with`, nhưng không có constructor và không `extends` lớp khác. `extension ... on Type` thêm method cho một lớp có sẵn (ví dụ `String`, `int`).

### Dấu hiệu đạt yêu cầu
- Định nghĩa `enum` với danh sách giá trị; dùng field qua `const` constructor khi cần lưu dữ liệu.
- Dùng `mixin` với `with` để chia sẻ chức năng cho nhiều lớp.
- Dùng `extension on` để thêm method, gọi qua đối tượng đích.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Cho `mixin` có constructor hoặc `extends` lớp khác → compile fail.
- Truy cập sai giá trị enum hoặc quên `.values`/`index` → test fail.
- Quên từ khóa `on` khi khai báo extension → compile fail.

### API/Widget chính
`enum`, `.values`, `index`, `mixin`, `with`, `extension ... on`

### Từ khóa
enum, values, index, mixin, with, extension

### Nguồn: Mastering Flutter, Ch.2, tr. 61–65

---

## skill_code: `dart_exceptions`
**skill_name:** Xử lý ngoại lệ · **skill:** `dart_fundamentals`

### Khái niệm
Dart dùng lớp `Exception` và `Error` để báo lỗi; ngoại lệ không bắt sẽ làm app crash. Dart có thể `throw` bất kỳ object nào và chỉ có unchecked exception. Bắt lỗi bằng `try/catch`, lọc theo loại bằng `on Exception catch`, và chạy code dọn dẹp bằng `finally`. `Error` (như `OutOfMemoryError`) là lỗi nghiêm trọng không khôi phục được.

### Dấu hiệu đạt yêu cầu
- Bọc code có thể lỗi trong `try`, bắt bằng `catch` hoặc `on Type catch`.
- Dùng `finally` để chạy code dọn dẹp sau khi xử lý lỗi.
- `throw Exception(...)` với thông điệp rõ ràng khi cần báo lỗi.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Không bắt ngoại lệ được ném → app crash, test fail.
- Bắt sai loại trong `on ... catch` → ngoại lệ lọt qua, test fail.
- Quên `finally` nên không dọn dẹp tài nguyên → trạng thái sai.

### API/Widget chính
`try`, `catch`, `on Exception catch`, `finally`, `throw`, `Exception`, `Error`

### Từ khóa
exception, try catch, on catch, finally, throw, error

### Nguồn: Mastering Flutter, Ch.2, tr. 67–68
