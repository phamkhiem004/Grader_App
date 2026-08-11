# Quy ước key cho testcase dùng chung Flutter

Testcase chung không import model, repository hoặc tên màn hình nội bộ của bài sinh viên.
Testcase chỉ tìm các semantic key mà starter/đề bài công bố.

## Khu vực 0 — Cấu hình bài làm (hợp đồng)

Trước khi tạo testcase, giáo viên khai **mỗi semantic key được nhận diện thế nào** nếu bài
nộp không gắn `ValueKey`. Đây là bước quan trọng nhất về độ chính xác: engine từng dò cứng
(list = `ListView`, item = `ListTile`, nút = nút có chữ) nên bài dùng `SliverGrid`/`Card`/nút
chỉ có icon — vẫn đúng đề — bị chấm trượt oan.

Hợp đồng lưu trong `testcase_config_json` (khóa `contract`) và được materialize thành
`contract.json` (engine đọc lúc chấm) + `contract.md` (yêu cầu dán vào đề + đoạn
`ExamKeys` phát cho sinh viên).

```json
{
  "require_keys": false,
  "keys": [
    {"key": "list.items", "label": "Danh sách", "strategy": "widget_type", "value": "SliverGrid", "index": 0},
    {"key": "action.item.edit", "label": "Nút sửa", "strategy": "icon", "value": "edit", "index": 0}
  ]
}
```

Các cách dò (`strategy`) — khớp `_contractFinder` trong `exam_test.dart`:

| strategy | Ý nghĩa | Cần `value` |
|---|---|---|
| `auto` | Dò theo quy tắc mặc định (heuristic sẵn có của engine) | không |
| `key_only` | Không dò thay thế — thiếu `ValueKey` là không tìm thấy | không |
| `widget_type` | Theo tên class widget (`SliverGrid`, `Card`, `TextField`…) | có |
| `icon` | Theo **nhóm** icon (`edit`, `delete`, `add`, `save`, `back`, `forward`, `close`, `search`, `person`, `email`, `image`, `menu`) | có |
| `tooltip` | Theo tooltip của nút | có |
| `text` | Theo nội dung chữ | có |
| `button_text` | Nút chứa chữ đó | có |
| `type_with_text` | Widget loại X có chứa chữ Y (cần thêm `text`) | có |

Ghi chú:

- `value` khớp đúng chuỗi, hoặc bọc trong `/…/` để dùng biểu thức chính quy (không phân biệt hoa thường).
- `index` chọn phần tử thứ mấy khi nhiều widget cùng khớp (0 = đầu tiên). Thiếu phần tử thứ
  `index` thì coi như **không tìm thấy** — không tự lùi về phần tử đầu, tránh pass giả.
- `icon`/`tooltip`/`button_text` tự lấy **nút bọc ngoài** nếu có, nên `targetType=button` và
  `tap()` đều trúng đúng đối tượng. Nút Material tự dựng `InkWell` bên trong nên engine ưu tiên
  `ElevatedButton`/`IconButton`/… trước, chỉ dùng `InkWell` khi không có nút thật.
- `require_keys=true` thì bỏ toàn bộ fallback; muốn miễn trừ vài key thì đặt
  `allow_fallback: true` ở đúng key đó.
- Đề không có hợp đồng vẫn chấm như cũ, nên đề cũ không bị ảnh hưởng.

### File `ExamKeys` trong starter là TÙY CHỌN

Engine chỉ so **chuỗi bên trong key** (`find.byKey(ValueKey<String>(key))`). Sinh viên viết
thẳng `const ValueKey('field.email')` trong widget là đủ; tên class, tên biến, cách chia
widget và loại widget hoàn toàn tự do. `const Key('x')` tương đương `ValueKey<String>('x')`
nên cũng khớp — nhưng `ObjectKey`, `GlobalKey` hay key ghép chuỗi động thì không.

File `exam_keys.dart` sinh ra ở màn "Xem hợp đồng" chỉ để sinh viên khỏi gõ sai chính tả
tên key (sai một ký tự là mất điểm dù chức năng làm đúng). Không phát cũng không sao.

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

Bộ testcase tạo mới mặc định bật `require_keys: true`: các runner UI chỉ nhận đúng
`ValueKey` đã công bố, không đoán theo thứ tự widget hoặc chữ hiển thị. Giáo viên vẫn có
thể tắt cho một đề legacy; giá trị `false` đã lưu của đề cũ không bị tự đổi. Logic/SQLite
không dùng Widget Key mà đi qua public contract cố định trong starter.

Khi thêm testcase UI, màn hình đối chiếu toàn bộ tham số loại `semantic_key`/
`semantic_keys` với Khu vực 0 và hiện nút **Thêm các key testcase còn thiếu**. Backend kiểm
lại lần cuối và không cho lưu một contract bật `require_keys` nhưng chưa công bố key đang
được testcase sử dụng. Nhờ vậy bộ chấm và đề phát cho sinh viên không thể lệch địa chỉ.

## Chấm logic bằng public contract của starter (không dùng adapter)

Logic, parser, mapping và stream không nên bị định vị bằng Widget Key. Với các phần này,
starter phát một file contract nhỏ dưới `lib/` và export API cần chấm qua `lib/main.dart`:

```dart
// lib/main.dart
export 'domain/price_rules.dart';

void main() => runApp(const MyApp());
```

Giảng viên chọn một trong ba template tham số hóa:

- `COMMON_PUBLIC_FUNCTION_RESULT`: hàm hoặc static method trả về JSON-compatible result.
- `COMMON_PUBLIC_FUNCTION_THROWS`: dữ liệu lỗi phải ném đúng loại ngoại lệ/nội dung.
- `COMMON_PUBLIC_STREAM_EVENTS`: Stream phát đúng chuỗi event; engine chỉ lấy đúng số
  event mong đợi nên không treo với stream chạy liên tục.

Các giá trị thay đổi theo đề đều nhập ở instance: `contractPath`, `callable`,
`argumentsJson`, expected tương ứng và `timeoutMs`. Ví dụ cùng một template có thể gọi
`validateTitle` của Todo, `PriceRules.total` của Product hoặc `isValidAmount` của Expense.
Không có `grading_adapter.dart`, không nhập biểu thức Dart tự do và không phụ thuộc tên
repository/view model nội bộ của sinh viên.

`contractPath` phải là đường dẫn an toàn dạng `lib/...dart`; `callable` chỉ nhận tên hàm
top-level hoặc static method. Đối số và kết quả là JSON nên backend kiểm tra được trước khi
sinh code. Starter phải export callable qua `main.dart`, nếu không Dart sẽ báo lỗi contract
rõ ràng thay vì testcase đoán tên class của bài làm.

## Kiểm tra source contract mà không pass nhầm

Các template `SOURCE_CONTAINS` chỉ dùng khi đề **bắt buộc kỹ thuật/cấu trúc cụ thể**,
ví dụ một class phải nằm trong file model hoặc `pubspec.yaml` phải khai báo dependency.
Chúng không thay thế testcase hành vi runtime. Nếu tiêu chí vừa yêu cầu kỹ thuật vừa yêu
cầu kết quả chạy, phải ghép thêm testcase Logic/Widget/Behavior tương ứng.

Với một file đơn, ba trường cũ `sourcePathsJson`, `requiredTokensJson` và
`forbiddenTokensJson` vẫn hoạt động để giữ tương thích đề đã lưu. Với nhiều file, dùng
`sourceChecksJson` để mỗi token trỏ đúng file thay vì tìm trên phần source đã gộp:

```json
[
  {
    "path": "lib/models/task.dart",
    "requiredTokens": ["class Task"],
    "forbiddenTokens": ["dynamic id"]
  },
  {
    "path": "lib/repositories/task_repository.dart",
    "requiredTokens": ["class TaskRepository"],
    "forbiddenTokens": []
  }
]
```

Engine loại comment Dart/YAML trước khi tìm token, nhưng giữ nguyên chuỗi ký tự như URL.
Vì vậy sinh viên không thể làm testcase pass chỉ bằng cách ghi tên class/dependency trong
comment. Đường dẫn vẫn bị giới hạn ở `lib/*.dart`, `test/*.dart`, `pubspec.yaml` và
`analysis_options.yaml`; không đọc file bên ngoài bài nộp.

Riêng persistence qua **process mới** cần starter cấp fixture hai pha seed/reload và một
database path cô lập. Mẫu public-function thông thường chỉ chấm scenario mà contract cung
cấp; không được mô tả nó là bằng chứng sống qua process nếu fixture chưa thực sự mở lại dữ
liệu ở pha thứ hai.

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

## Quản lý thư viện testcase (Khu vực 2)

Thư viện gồm 25 mẫu engine nền ở
`grader/src/main/resources/common-testcase-templates.json` và 52 mẫu theo curriculum ở
`grader/src/main/resources/prm393-curriculum-testcase-templates.json` (tổng 77 mẫu,
phủ 62/62 kỹ năng có thể chấm tự động). Giáo viên
thêm/sửa/ẩn template ngay trên trang tạo testcase; phần khác biệt lưu ở bảng
`testcase_template`, file gốc không bị ghi đè:

| Thao tác | Kết quả |
|---|---|
| Thêm | Dòng mới `origin=CUSTOM` |
| Sửa template gốc | Dòng `origin=OVERRIDE` chồng lên bản classpath |
| Xóa | `hidden=true` — ẩn khỏi Khu vực 2, **không** xóa cứng |
| Khôi phục | Template gốc: xóa dòng DB → về đúng bản classpath. Template tự thêm: bỏ ẩn |

Không xóa cứng vì `testcase_config_json` của mỗi đề chỉ lưu `template_id`; mất template
là đề cũ không mở/lưu lại được. `listTemplates` lọc mục ẩn, còn `getTemplate` và
`normalizeItems` vẫn resolve được.

Form thêm/sửa dựng theo `GET /api/testcase-templates/runners` (xem
`TestcaseRunnerCatalog`): mỗi runner khai nhãn tiếng Việt, layer mặc định và danh sách
tham số kèm `type` (`semantic_key`, `semantic_keys`, `values`, `enum`, `number`, `bool`).
Tham số mặc định phải qua đúng `validateCommonParameters` dùng khi lưu đề, nên không tạo
được template hỏng. `TestcaseRunnerCatalogTest` chốt danh mục này khớp với switch trong
`exam_test.dart`.

Nguồn học liệu PRM393 được lập chỉ mục riêng, không nhét toàn bộ giáo trình vào database
runtime. Backend công bố mapping học liệu → skill → template qua
`GET /api/testcase-templates/curriculum-source` từ
`grader/src/main/resources/prm393-curriculum-testcase-source.json`. Đây là nguồn gợi ý để
chọn testcase theo yêu cầu đề, **không phải nút nạp tất cả testcase**. Một template chỉ được
coi là tái sử dụng khi invariant giữ nguyên và đã chứng minh thay parameters được cho tối
thiểu ba ngữ cảnh độc lập.

Chín kỹ năng cần thiết bị/tài khoản thật, chạy artifact, DevTools profile hoặc pipeline
release không được sinh thành testcase tự động giả. Chúng vẫn nằm trong syllabus và nguồn
học liệu, nhưng phải chấm bằng execution/profile/manual evidence đúng loại.

## Testcase tự viết code (CUSTOM_CODE)

Khi yêu cầu của đề không diễn đạt được bằng runner có sẵn, giáo viên gõ code Dart ngay
trong khu vực "Tự viết code". Mục sinh ra có `template_id = CUSTOM_CODE`,
`runner = CUSTOM_CODE`, `layer = CUSTOM`, `parameters` rỗng và code nằm ở `custom_code`.

Giáo viên chỉ viết **phần thân** test; hệ thống tự bọc:

```dart
testWidgets('PE01_custom_01', (tester) async {
  // phần thân do giáo viên viết
});
```

Tên test luôn bằng `instance_id` nên `grader.dart` vẫn map được kết quả về đúng dòng
rubric, giống hệt testcase dựng từ template.

Code được chèn vào vùng đánh dấu trong `exam_test.dart`:

```dart
// ─────────────────── CUSTOM_TESTCASES_BEGIN ───────────────────
void _registerCustomTestcases() { ... }
// ──────────────────── CUSTOM_TESTCASES_END ────────────────────
```

Vùng này bị **sinh lại toàn bộ** mỗi lần lưu cấu hình testcase — sửa tay trong file sẽ
mất. Mục bị tắt (`enabled = false`) không vào `skills_matrix.json` lẫn file sinh ra.
Trong `exam_test.dart`, vòng lặp theo matrix bỏ qua entry `CUSTOM_CODE` để tên test
không bị đăng ký hai lần.

Ràng buộc với đoạn code (kiểm tra tĩnh mỗi lần lưu):

- Không viết `import` / `export` / `part` / `library`, `main()`, `testWidgets(`,
  `group(`, `setUp*(`, `tearDown*(` — những thứ này đã có sẵn ở file bao ngoài.
- Đã import sẵn `material.dart`, `flutter_test.dart`, `rendering.dart`, `dart:io`.
- Ngoặc `{} () []` và chuỗi phải cân bằng (bộ kiểm tra hiểu chuỗi raw, chuỗi ba nháy và
  interpolation `${...}`), tối đa 20.000 ký tự.
- `skill_code` vẫn phải tồn tại trong syllabus như mọi testcase khác.

Helper dùng lại được từ engine: `_boot(tester)`, `_settle(tester)`, `_byKey('field.email')`,
`_textFormFieldAt(i)`, `_listTileAt(i)`, `_buttonWithText(regexp)`, `_validationErrorFor(key)`.

Khi **Publish**, toàn bộ đoạn code tay được ghép thành một file rồi parse bằng
`dart format --output=none` trong ảnh `grading-base:latest` (một lần gọi Docker cho cả đề);
sai cú pháp thì chặn publish và báo đúng testcase + số dòng. Nếu máy chưa bật Docker,
hệ thống chỉ cảnh báo chứ không chặn — lúc đó lỗi cú pháp sẽ lộ ra khi chấm.

## Gộp testcase thành một testcase lớn

Có thể chọn nhiều testcase common rồi gộp thành một group. Mỗi testcase nhỏ trong
group vẫn là một assert/runner độc lập, nhưng `exam_test.dart` chỉ công bố một kết quả
cho group. Engine vẫn chạy toàn bộ testcase con để báo đủ lỗi; chỉ cần một testcase
con hoặc một assert fail thì group fail. Điểm của group bằng tổng điểm các testcase con.
