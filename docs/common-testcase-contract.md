# Quy ước key cho testcase dùng chung Flutter

Testcase chung không import model, repository hoặc tên màn hình nội bộ của bài sinh viên.
Testcase chỉ tìm các semantic key mà starter/đề bài công bố.

## Key cần có trong starter

```dart
TextFormField(key: const ValueKey('field.title'))
ElevatedButton(key: const ValueKey('action.save'))
ListView(key: const ValueKey('list.items'))
Scaffold(key: const ValueKey('screen.home'))
```

Quy ước:

- `screen.*`: màn hình hoặc vùng gốc của màn hình.
- `field.*`: ô nhập liệu.
- `action.*`: nút hoặc thao tác chính.
- `list.*`: danh sách.
- `item.*`: phần tử trong danh sách.
- `error.*`: thông báo lỗi có thể kiểm tra được.
- `message.*`: trạng thái thành công/thông báo sau thao tác.

## Ví dụ cấu hình

```json
{
  "runner": "FORM_REQUIRED_FIELDS",
  "parameters": {
    "fieldKeys": "field.title,field.description",
    "submitKey": "action.save",
    "errorKeys": "error.title,error.description"
  }
}
```

Tên nghiệp vụ có thể khác nhau giữa Todo, Expense hoặc Product; semantic key giữ cho
runner dùng chung không bị phụ thuộc vào tên class/model của từng đề.

## Khi lưu cấu hình testcase

Chức năng tạo testcase materialize một engine chung thành ba file chạy được:

- `exam_test.dart`: tạo các `testWidgets` theo từng dòng trong `skills_matrix.json`, đọc
  `runner` và `parameters` rồi chạy kiểm tra trên app sinh viên.
- `grader.dart`: chạy `flutter test` và tổng hợp điểm/kết quả.
- `skills_matrix.json`: danh sách testcase đã chọn, expected, điểm, runner và thông số
  cụ thể của từng instance.

Vì vậy `exam_test.dart` có tạo ra code test, nhưng phần code runner là dùng chung; khi
đổi đề, hệ thống chủ yếu sinh lại `skills_matrix.json` để truyền target key và thông số
khác nhau vào runner. Việc lưu Draft có thể tạo file để tải kiểm tra; muốn chạy chấm
thực tế thì bài Flutter được chấm phải có `lib/main.dart` và các key mà testcase khai báo.

## Layout: target key và target type

Các yêu cầu layout dùng `targetKey` để chỉ đúng widget và `targetType` để xác nhận
key đó đang được gắn vào đúng loại widget. `targetType` không thay thế cho key; nó
là lớp bảo vệ để tránh trường hợp testcase định kiểm tra ảnh nhưng sinh viên gắn key
nhầm lên widget bao quanh.

Ví dụ hai đề cùng yêu cầu kiểm tra chiều cao nhưng khác đối tượng:

```json
{
  "runner": "WIDGET_DIMENSION",
  "parameters": {
    "targetKey": "profile.form",
    "targetType": "form",
    "dimension": "height",
    "expected": 15,
    "comparison": "equals",
    "tolerance": 0.5
  }
}
```

```json
{
  "runner": "WIDGET_DIMENSION",
  "parameters": {
    "targetKey": "profile.avatar.image",
    "targetType": "image",
    "dimension": "height",
    "expected": 80,
    "comparison": "equals",
    "tolerance": 0.5
  }
}
```

Code sinh viên cần gắn key vào đúng đối tượng:

```dart
Form(
  key: const ValueKey('profile.form'),
  child: ...,
)

Image.network(
  avatarUrl,
  key: const ValueKey('profile.avatar.image'),
  height: 80,
)
```

Các runner layout hiện có:

- `WIDGET_DIMENSION`: width/height của một target.
- `WIDGET_PADDING`: padding của widget `Padding` được định danh bằng key.
- `WIDGET_TEXT_STYLE`: `fontSize` và `fontWeight` của `Text`.
- `WIDGET_GAP`: khoảng cách render thực tế giữa hai target; dùng để kiểm tra
  spacing/margin theo trục dọc hoặc ngang.
- `RESPONSIVE_NO_OVERFLOW`: render ở hai kích thước và kiểm tra không phát sinh lỗi.
- `RESPONSIVE_TARGET`: ngoài kiểm tra responsive, xác nhận một target vẫn tồn tại ở
  cả portrait và landscape.
- `STATE_REACTIVE_FLOW`: kiểm tra state thay đổi qua UI sau một action. Đây là kiểm tra
  black-box; không kết luận sinh viên dùng Riverpod hay một thư viện state nào.

Với yêu cầu “margin”, nên kiểm tra khoảng cách render giữa hai phần tử bằng
`WIDGET_GAP` thay vì phụ thuộc vào việc sinh viên dùng `Container.margin` hay
`Padding`. Như vậy testcase kiểm tra kết quả layout, không ép một cách cài đặt
duy nhất.

## Gộp testcase thành một testcase lớn

Có thể chọn nhiều testcase common rồi gộp thành một group. Mỗi testcase nhỏ trong
group vẫn là một assert/runner độc lập, nhưng `exam_test.dart` chỉ công bố một kết quả
cho group. Engine vẫn chạy toàn bộ testcase con để báo đủ lỗi; chỉ cần một testcase
con hoặc một assert fail thì group fail. Điểm của group bằng tổng điểm các testcase con.


## Khung b? testcase (suite contract)

Khi t?o b? testcase m?i, gi?ng vi?n c? th? khai b?o `suite` ? c?p b? ??. C?u h?nh n?y ???c
l?u trong `testcase-config.json` v? ???c nh?ng v?o t?ng d?ng c?a `skills_matrix.json`, do ??
ZIP legacy g?m `exam_test.dart`, `grader.dart`, `skills_matrix.json` v?n ch?y b?nh th??ng.

```json
{
  "suite": {
    "suite_version": 1,
    "name": "Todo CRUD c? b?n",
    "context": "todo_crud",
    "fixture_name": "one_existing_todo",
    "fixture_description": "Starter m? l?n c? m?t Todo ?? s?a.",
    "strict_semantic_keys": true,
    "ready_key": "screen.home.ready",
    "required_keys": ["screen.home", "list.items", "action.add"],
    "boot_timeout_ms": 3000,
    "step_timeout_ms": 2000,
    "setup_steps": [
      {"type": "tap", "key": "action.add"},
      {"type": "expect_visible", "key": "screen.form"}
    ]
  }
}
```

`setup_steps` ch? h? tr? thao t?c black-box trong whitelist:

- `tap`: b?m semantic key.
- `enter_text`: nh?p `value` v?o semantic key c?a input.
- `expect_visible`: y?u c?u key xu?t hi?n ngay.
- `expect_absent`: y?u c?u key kh?ng xu?t hi?n.
- `wait_for_visible`: ch? key xu?t hi?n trong `timeout_ms`.

M?i testcase c?ng c? th? c? `setup_steps` ri?ng. Engine ch?y theo th? t?:

```text
boot app m?i ? ki?m tra required_keys ? ch? ready_key ? suite.setup_steps ? testcase.setup_steps ? assertion
```

Starter/template d?ng khung strict ph?i c?ng b? `ValueKey` ?n ??nh, v? d?:

```dart
Scaffold(key: const ValueKey('screen.home'))
ListView(key: const ValueKey('list.items'))
ElevatedButton(key: const ValueKey('action.add'), onPressed: ...)
```

Kh?ng ??a Dart code, model, repository ho?c t?n provider v?o `suite`. N?u y?u c?u c?n seed
database, API mock ho?c ki?m tra persistence th?t, ?? l? profile testcase ri?ng ch? kh?ng ph?i
fixture black-box c?a common engine.

C?u h?nh testcase c? kh?ng c? `suite` v?n ch?y ? ch? ?? t??ng th?ch fallback. B? ?? m?i n?n
b?t `strict_semantic_keys` ?? thi?u key s? fail r? r?ng thay v? runner t? ?o?n theo User CRUD.
