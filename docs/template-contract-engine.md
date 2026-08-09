# Engine testcase theo contract của từng đề

`TEMPLATE_CONTRACT_V1` tái sử dụng **cách kiểm tra**, không tái sử dụng một bộ đề cố định.
Engine này dành cho starter có sẵn public contract và các vị trí TODO để sinh viên hoàn thiện. Nó không yêu cầu `Widget Key` hoặc `grading_adapter.dart`.

## Quy trình tạo bộ chấm

1. Giảng viên tạo starter cho đề và công bố tên file, class, field, method, label/hint và nội dung nút cần giữ nguyên.
2. Trong màn hình **Tạo testcase**, chọn **Bộ testcase chấm theo khung template mẫu**.
3. Có thể mở **Thiết lập contract gợi ý cho đề**. Contract v4 gồm các nhóm động; giảng viên được thêm/xóa nhóm, thêm/xóa trường và đặt mã ánh xạ riêng. Các nhóm Model, Storage, Service, State, UI, Behavior và Responsive chỉ là gợi ý ban đầu, không phải cấu trúc bắt buộc.
4. Chỉ kéo các mẫu kiểm tra cần thiết vào đề. Testcase mới tự nhận phần contract phù hợp; không có thao tác nạp toàn bộ một pack.
5. Mở **Cấu hình** của từng testcase để kiểm tra hoặc thay giá trị riêng.
6. Nếu thư viện chưa có biến thể cần dùng, bấm **Tạo testcase mới**, chọn runner được hỗ trợ và đặt schema mặc định. Không nhập Dart code tùy ý ở đây.
7. Lưu Draft để sinh đúng ba file công khai: `exam_test.dart`, `grader.dart` và `skills_matrix.json`.

Trang chính theo dõi tiến độ từ các testcase đang chọn, không dùng độ dài `exam_test.dart` làm
thước đo. Mỗi testcase được đánh dấu **Sẵn sàng**, **Cần xử lý** hoặc **Đang tắt**; giảng viên
có thể lọc theo Logic/Widget/Behavior, lưu Draft rồi tiếp tục bổ sung từng testcase. Ba file kỹ
thuật chỉ mở trong modal khi cần đối chiếu.

## Module nội bộ và artifact ba file

Backend bảo trì runner trong module nguồn nội bộ `common_testcase_engine.dart`. Khi giảng viên
lưu Draft, backend phân tích dependency rồi chỉ ghép các runner đang được testcase của đề sử dụng
thành một `exam_test.dart` độc lập. Mỗi testcase được đăng ký bằng ID cụ thể trong file sinh; thêm,
xóa hoặc sắp xếp lại testcase sẽ tạo một snapshot mới tương ứng. Module nguồn và
`testcase-config.json` không được đưa vào bộ chấm, preview hoặc ZIP tải xuống.

- `exam_test.dart`: chứa testcase và runner cần để Flutter chạy độc lập;
- `skills_matrix.json`: chứa testcase được chọn, tham số, expected và trọng số. Contract đầy đủ của màn hình soạn Draft không được nhét vào file này; chỉ cấu hình runtime khác mặc định mà runner thực sự đọc mới được xuất;
- `grader.dart`: chạy từng case trong process riêng và tổng hợp điểm.

Nhờ vậy code phát triển vẫn tách module để bảo trì, nhưng hợp đồng phát hành và upload luôn giữ
đúng ba file như các bộ testcase cũ.

Tham số nhận từ contract được cập nhật tự động cho các testcase đang kế thừa. Nếu giảng viên sửa
trực tiếp một tham số trong testcase, tham số đó được đánh dấu override và không bị lần chỉnh
contract sau ghi đè. Nút áp dụng contract thủ công sẽ xóa override và đồng bộ lại toàn bộ tham số.

## Các mẫu dùng chung hiện có

| Mẫu | Tham số thay đổi theo đề |
|---|---|
| File có symbol | `sourcePath`, `symbols` |
| Source wiring/import | `sourcePath`, `requiredTerms`, `forbiddenTerms` |
| Model fields | `sourcePath`, `className`, `fields` dạng `uid:String,age:int` |
| Model copy method | file, class, tên method và field cần hỗ trợ |
| Model mapping | file, class, tên hai method và danh sách cột |
| SQLite schema | file database, tên bảng và danh sách cột |
| Repository methods | file, class và danh sách method |
| Form fields | label/hint của từng ô nhập |
| Buttons | nội dung chữ của từng nút |
| Form action | label các ô, dữ liệu thử, nút bấm và nội dung phải xuất hiện |
| Form validation tùy chỉnh | label, dữ liệu thử sai, nút submit và nội dung lỗi |
| Required | `<empty>` và thông báo bắt buộc của field |
| Sai định dạng | mẫu email/số/ngày/URL sai do giảng viên chỉ định |
| Vi phạm giới hạn | giá trị dưới/trên min-max hoặc sai độ dài |
| Đối chiếu nhiều field | mật khẩu xác nhận, khoảng ngày hoặc cặp min-max |
| Dữ liệu hợp lệ | dữ liệu đúng phải thực hiện action và tạo kết quả mới |
| Visible text | các chuỗi phải hiển thị |
| UI workflow | JSON gồm các bước `enter`, `tap`, `expectVisible`, `expectAbsent`, `wait` |
| Responsive | kích thước portrait và landscape |

Các danh sách có thể là CSV hoặc JSON array. `fieldLabels` và `inputValues`/`invalidValues` phải có cùng số phần tử và cùng thứ tự. Dùng `<empty>` khi cần nhập chuỗi rỗng trong CSV; nếu dữ liệu chứa dấu phẩy hoặc cần giữ phần tử rỗng, dùng JSON array như `["Doe, John", ""]`.

Với màn hình có nhiều `Form`, runner không tìm field và nút trên toàn màn hình. Nó chọn
`Form` duy nhất chứa đủ `fieldLabels`. Nếu nhiều Form cùng khớp, giảng viên cấu hình thêm
`formIndex` (vị trí bắt đầu từ 1) hoặc `formAnchorText` (nội dung nằm bên trong Form). Field,
nút submit và thông báo validation sau đó chỉ được đối chiếu trong đúng Form đã chọn. Nếu cấu
hình `errorFieldLabels`, từng phần tử phải tương ứng với một phần tử trong `errorTexts`; runner
kiểm tra lỗi nằm dưới đúng field thay vì chỉ xuất hiện ở đâu đó trong Form. `errorTextMatchMode`
chọn so khớp `contains` hoặc `exact`.

`invalidValues` không chứa hệ thống kiểu dữ liệu tự động. Chỉ `<empty>` là từ khóa; `abc@`,
`invalid`, `-1` hoặc `31/02/2026` đều là dữ liệu literal được nhập nguyên văn. Mỗi quy tắc nên là
một testcase riêng để báo cáo chỉ rõ required, format, boundary hay cross-field bị sai.

## Bộ chọn và điều kiện chống pass nhầm

Các runner linh hoạt không được tự lấy widget đầu tiên khi có nhiều kết quả. Những trường sau
được dùng chung cho Button, Text, List và từng bước Workflow:

- `scopeType`: `screen`, `form`, `dialog`, `list`, `appbar` hoặc `bottomsheet`;
- `scopeIndex`: vị trí 1-based khi có nhiều scope cùng loại;
- `scopeAnchorText`: nội dung nằm trong scope dùng để nhận diện;
- `occurrence`: vị trí 1-based của target trong một bước workflow;
- `textMatchMode`: `contains` hoặc `exact`;
- `minimumOccurrences`/`expectedCount`: số lần nội dung phải xuất hiện.

Nếu selector vẫn trả về nhiều field hoặc action, testcase fail với lỗi contract thay vì tap phần tử
đầu tiên. Form action mặc định bật `requireNewResult`; validation mặc định bật `requireNewErrors`,
vì vậy nội dung/lỗi đã có sẵn trước thao tác không được dùng để pass giả. Có thể giới hạn kết quả
bằng `resultScopeType`, `resultScopeIndex` và `resultScopeAnchorText`.

Nhóm logic cũng được giới hạn phạm vi: `symbolTypes` phân biệt class/function/variable;
`schemaMethod` giới hạn schema SQLite vào đúng method; `copyWith`, `toMap` và `fromMap` được
kiểm tra trong thân method thay vì chỉ tìm tên field/cột ở đâu đó trong class. APP_BOOT có thể chờ
`readyText` với `readyTimeoutMs`; responsive có thể yêu cầu nội dung riêng cho portrait/landscape.

## Contract động v4

Mỗi trường contract có bốn phần:

- `label`: tên dễ hiểu trên giao diện;
- `key`: mã ánh xạ ổn định, ví dụ `model.path`, `service.methods`, `behavior.stepsJson`;
- `kind`: text, path, Dart identifier, CSV, JSON hoặc number;
- `value`: giá trị thực tế của đề hiện tại.

Blueprint khai báo `contract_bindings` để nối tham số runner với các key này. Ví dụ `TEMPLATE_MODEL_FIELDS` nối `sourcePath -> model.path`, còn workflow nối `stepsJson -> behavior.stepsJson`. Vì vậy đề không có SQLite có thể xóa hẳn nhóm Storage; đề gọi API có thể đổi nhóm đó thành API và thêm các key mới. Draft cũ được tự bổ sung selector chống mơ hồ và ánh xạ lỗi theo field khi mở lại, sau đó lưu thành v4; trường đã nhập trước đó không bị ghi đè.

## Những phần đã tái chế từ V9

V9 có 48 testcase riêng cho User CRUD. Thư viện mới không nạp nguyên 48 testcase mà tách chúng thành các mẫu tổng quát:

- `CONTRACT_*`, `ARCH_*` thành kiểm tra symbol và source wiring;
- `MODEL_GRANULAR_*` thành model fields, mapping và copy method;
- `VIEWMODEL_*` thành kiểm tra public method của state/ViewModel;
- `SCREEN_VALIDATE_*`, `SCREEN_FORM_*`, `SCREEN_LIST_*` thành form, validation và list content;
- `UI_CREATE/EDIT/DELETE/DETAIL_*` thành workflow nhiều bước theo label/text;
- `UI_RESPONSIVE_*`, `UI_LAYOUT_OVERFLOW` thành responsive theo kích thước cấu hình.

Nhóm repository behavior và persistence hai process của V9 chưa được biến thành kiểm tra text. Muốn chấm hai nhóm này một cách công bằng, starter phải công bố public contract khởi tạo repository/database đủ rõ để engine sinh import và lời gọi có kiểu cụ thể. Không nên dùng source regex để thay cho việc chạy SQLite thật.

## Ví dụ đề UID / first name / last name

- Model fields: `uid:String,firstName:String,lastName:String`
- SQLite columns: `uid,first_name,last_name`
- Repository methods: `addUser,readUsers,removeUser`
- Form labels: `input uid,input firstname,input lastname`
- Buttons: `Add,Read,Remove`
- Form action Add:
  - `inputValues`: `SV01,An,Nguyen`
  - `actionLabel`: `Add`
  - `expectedTexts`: `SV01,An,Nguyen`

Nếu đề sau chuyển thành Product, Todo hoặc một form khác, giảng viên vẫn dùng các mẫu trên nhưng thay toàn bộ contract tương ứng. Không dùng lại 48 tiêu chí User CRUD V12.

## Giới hạn cần công bố trong đề

Chế độ không dùng Key nên testcase UI tìm ô nhập bằng `labelText`/`hintText` và tìm nút bằng nội dung chữ. Các chuỗi này là public contract và sinh viên phải giữ nguyên. Kiểm tra source xác nhận cấu trúc khai báo; các testcase action mới xác nhận hành vi chạy thực tế.
