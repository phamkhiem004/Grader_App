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

Các danh sách vẫn nhận CSV để nhập nhanh. Khi dữ liệu có dấu phẩy hoặc cần giữ phần tử
rỗng, dùng JSON array, ví dụ `['Doe, John', '']` phải được nhập theo JSON chuẩn là
`["Doe, John", ""]`. Chỉ các key vẫn nên là chuỗi semantic ngắn, không chứa dấu phẩy.

## Kiểm tra chuyển trạng thái, không chỉ ảnh chụp sau cùng

Runner hành vi mặc định phải chứng minh kết quả được tạo bởi thao tác đang chấm:

- `BUTTON_ACTION` và `FORM_SUBMIT`: `resultKey` phải xuất hiện mới (`requireNewResult`).
- `NAVIGATION`: `destinationKey` phải được mở mới; sau Back, màn hình đích phải ẩn.
- `FORM_REQUIRED_FIELDS` và `FORM_VALIDATE_FIELDS`: `errorKeys` phải xuất hiện mới sau submit.
- `DIALOG_FLOW`: `dialogKey` phải được mở mới và `decisionKey` phải nằm trong đúng dialog.
- `STATE_REACTIVE_FLOW`: `updatedKey` phải là state mới, khác state ban đầu.
- `FORM_PREFILL`: thao tác Edit phải tạo thay đổi prefill quan sát được.

Các cờ này có thể tắt ở một đề đặc biệt, nhưng khi tắt thì testcase chỉ xác nhận trạng thái
sau cùng và mức chống pass giả sẽ thấp hơn. Finder thông thường chỉ tính widget đang hiển thị;
kiểm tra `absentKey` mới quét cả widget offstage để chắc chắn đối tượng thực sự biến mất.

`LIST_ITEM_COUNT` chỉ đếm các `itemKeys` đã khai báo nằm bên trong `listKey`; nó không thể kết
luận danh sách không chứa thêm item chưa được gắn key. Vì vậy tên hiển thị của mẫu là
“Số itemKey mong đợi xuất hiện trong list”, không phải tổng số phần tử nội bộ của mọi cách dựng list.

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


## Khung bộ testcase (suite contract)

Khi tạo bộ testcase mới, giảng viên có thể khai báo `suite` ở cấp bộ đề. Cấu hình Draft
đầy đủ được backend giữ trong database để có thể mở lại và chỉnh sửa. Khi sinh bộ chấm
template-contract, backend loại bỏ schema biên tập `template_contract`, persistence/golden đang tắt
và các giá trị mặc định. Chỉ `suite` runtime không rỗng mà Dart runner thực sự đọc mới được ghi
một lần trong dòng testcase đầu tiên của `skills_matrix.json`; các dòng sau tự dùng lại cấu hình đó.

ZIP và thư mục chấm công khai luôn chỉ có đúng ba file: `exam_test.dart`, `grader.dart`
và `skills_matrix.json`. Không có `testcase-config.json` trong artifact chấm.

Pack `TODO_USER_STARTER_V12` là bộ chấm cố định cho đúng starter V12, không thuộc thư viện
tái sử dụng. Không kéo 48 tiêu chí của pack đó sang một đề khác. Đề linh hoạt phải dùng các
runner common theo Key hoặc template-contract theo public symbol/label của starter tương ứng.

```json
{
  "suite": {
    "suite_version": 1,
    "name": "Todo CRUD cơ bản",
    "context": "todo_crud",
    "fixture_name": "one_existing_todo",
    "fixture_description": "Starter mở lên có một Todo để sửa.",
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

`setup_steps` chỉ hỗ trợ thao tác black-box trong whitelist:

- `tap`: bấm semantic key.
- `enter_text`: nhập `value` vào semantic key của input.
- `expect_visible`: yêu cầu key xuất hiện ngay.
- `expect_absent`: yêu cầu key không xuất hiện.
- `wait_for_visible`: chờ key xuất hiện trong `timeout_ms`.

Mỗi testcase cũng có thể có `setup_steps` riêng. Engine chạy theo thứ tự:

```text
boot app mới → kiểm tra required_keys → chờ ready_key → suite.setup_steps → testcase.setup_steps → assertion
```

Starter/template dùng khung strict phải công bố `ValueKey` ổn định, ví dụ:

```dart
Scaffold(key: const ValueKey('screen.home'))
ListView(key: const ValueKey('list.items'))
ElevatedButton(key: const ValueKey('action.add'), onPressed: ...)
```

Không đưa Dart code, model, repository hoặc tên provider vào `suite`. Nếu yêu cầu cần seed
database, API mock hoặc kiểm tra persistence thật, hãy chọn runner hybrid tương ứng thay vì
nhét logic fixture vào `suite` black-box.

Cấu hình testcase cũ không có `suite` vẫn chạy ở chế độ tương thích fallback. Bộ đề mới nên
bật `strict_semantic_keys` để thiếu key sẽ fail rõ ràng thay vì runner tự đoán theo User CRUD.

## Hybrid starter + semantic Key (không adapter)

Engine `STARTER_KEY_HYBRID_V1` dùng hai ranh giới rõ ràng:

- Logic/model/service/storage: starter phát public contract và các hàm top-level nhỏ để testcase
  gọi trực tiếp. Sinh viên hoàn thiện TODO bên trong implementation thật.
- Widget/behavior: starter phát semantic `ValueKey`; sinh viên có thể tự thiết kế cây widget,
  miễn gắn đúng key vào đối tượng mang vai trò được công bố.

Mỗi direct testcase được grader chạy trong Flutter process riêng. Vì vậy singleton, database
đã mở hoặc Future treo của testcase trước không được dùng làm trạng thái ngầm cho testcase sau.

### Persistence thật qua process mới

`STARTER_CALL_SEQUENCE` chỉ phù hợp với nhiều bước trong cùng process. Muốn chấm dữ liệu còn
sau reload, dùng `PROCESS_PERSISTENCE_SEQUENCE`:

```json
{
  "runner": "PROCESS_PERSISTENCE_SEQUENCE",
  "parameters": {
    "sourcePath": "lib/storage/item_store.dart",
    "fixtureNamespace": "item-persistence",
    "seedStepsJson": "[{\"functionName\":\"resetStore\",\"arguments\":[],\"expectedType\":\"null\",\"expectedValue\":null},{\"functionName\":\"saveItem\",\"arguments\":[{\"id\":1}],\"expectedType\":\"null\",\"expectedValue\":null}]",
    "verifyStepsJson": "[{\"functionName\":\"readAll\",\"arguments\":[],\"expectedType\":\"json\",\"expectedValue\":[{\"id\":1}]}]"
  }
}
```

Grader chạy pha `seed`, hủy process, rồi chạy pha `verify` trong process mới. Cả hai pha nhận
cùng biến `GRADER_FIXTURE_ID`. Grader ghép namespace đã nhập với `instance_id`, vì vậy hai testcase
dùng cùng một template vẫn được cô lập. Starter phải dùng giá trị này để tạo tên/path database hoặc file
cô lập; không được giữ dữ liệu chỉ trong static `List` hay singleton:

```dart
final fixtureId = Platform.environment['GRADER_FIXTURE_ID'] ?? 'local';
final databaseName = 'exam_$fixtureId.db';
```

Đối với SQLite headless, starter phải khóa sẵn factory phù hợp môi trường grader. Testcase không
tự thay factory hoặc sửa repository của sinh viên, vì làm vậy sẽ chấm nhầm một implementation
khác với ứng dụng thật.
