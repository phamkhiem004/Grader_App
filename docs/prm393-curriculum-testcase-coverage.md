# Ma trận giáo trình PRM393 và testcase tái sử dụng

## Nguồn đã đọc

Học liệu gốc `PRM393_Hoc lieu (1).zip` đã được sao chép và giải nén ngoài repository tại:

`C:\Users\khaiq\Documents\PRM393_Curriculum_Reference_20260810`

Thư mục này giữ cả ZIP nguồn, 26 tài liệu gốc trong `raw/`, bản Markdown tìm kiếm được trong `text/`, script trích xuất và `INDEX.md`. Repository không phụ thuộc vào đường dẫn cá nhân này khi build hoặc chấm.

Ma trận máy đọc được nằm trong `grader/src/main/resources/prm393-curriculum-testcase-coverage.json`. Test backend bắt buộc mọi `skill_code` trong `syllabus.json` phải có ít nhất một blueprint hybrid tái sử dụng.

## Nguyên tắc sử dụng

Không chọn toàn bộ thư viện cho một đề. Người ra đề thực hiện theo thứ tự:

1. Tạo starter và khóa public contract cho phần logic/data cần chấm: file, class, hàm top-level, schema, storage factory và hàm reset fixture.
2. Công bố semantic `ValueKey<String>` cho các điểm UI/behavior cần quan sát. Key mô tả vai trò, không mô tả vị trí hoặc domain cố định.
3. Chọn đúng blueprint theo yêu cầu của đề.
4. Thay toàn bộ tham số đặc thù: path, symbol, function, arguments, expected, Key, viewport, timeout và workflow.
5. Thêm nhiều instance của cùng blueprint khi cần test nhiều nhánh hoặc giá trị biên.
6. Xem code sinh ra và publish. Artifact cuối vẫn chỉ gồm `exam_test.dart`, `skills_matrix.json` và `grader.dart`; không có grading adapter.

## Độ phủ theo Module/Lab

| Module | Học liệu/Lab | Nhóm năng lực | Cách chấm chính |
|---|---|---|---|
| M1 | Lab 1 | Cấu trúc project, pubspec, Stateful/Stateless | Source/project contract và app boot |
| M2 | Lab 2 | Dart types, null safety, control flow, function, OOP | Hàm public + test vector JSON |
| M3 | Lab 3 | Advanced Dart, exception, Future, event loop, Stream, Isolate | Kết quả trực tiếp, exception, chuỗi event và source contract có phạm vi |
| M4 | Lab 4 | Widget, layout, theme, list/grid/stack/card/sliver | Semantic Key + runtime widget type/property/relationship |
| M5 | Lab 5 | State, navigation, router, animation | Key workflow + source contract ở đúng file |
| M6 | Lab 6 | Responsive/adaptive | Nhiều viewport, visible/absent Key, grid count và quan hệ layout |
| M7 | Lab 7 | Form, validation, focus, async validation | Field/error Key + vector invalid/boundary/cross-field + focus/workflow |
| M8 | Lab 8 và 8B | REST, JSON, async state, service DI | Fixture response deterministic; không gọi internet thật |
| M9 | Lab 9 | SharedPreferences, JSON file, SQLite, persistence | Starter-owned storage fixture + public call sequence |
| M10 | Lab 10 | Login/signup, token/session, Firebase/Google, notification | Fixture flow + static integration contract + device evidence khi bắt buộc |
| M11 | Lab 11 | Unit/widget/integration test, DevTools | Artifact contract + chạy pipeline + evidence rubric |
| M12 | Lab 12 | Rebuild/list/image performance, release | Static guard + profile/build pipeline evidence |

Ma trận JSON ghi đầy đủ 71 mã kỹ năng, 12 module, 13 lab và project tổng hợp. Catalogue hiện có 122 mẫu ngữ cảnh trên 45 blueprint nền hybrid; các mẫu chỉ cung cấp điểm khởi đầu và mọi giá trị đều sửa được ở testcase instance.

## Định vị đúng đối tượng cần test

- Logic/data: `functionPath + functionName`, `sourcePath + class/symbol/method`, hoặc `sourcePath + stepsJson`.
- Widget: `targetKey + targetType`.
- Quan hệ UI: `ancestorKey + descendantKeys + type + orderedAxis`.
- Behavior: từng bước trong `stepsJson` có Key riêng; không có “form hiện tại” mơ hồ.
- Responsive: mỗi phần tử `casesJson` có viewport, Key hiện/ẩn và kỳ vọng layout riêng.
- File/project: mỗi phần tử `filesJson` có path, required/forbidden terms và kích thước tối thiểu riêng.

Các target type được hỗ trợ gồm input/button/form, list/grid/scrollable, Row/Column/Stack/IndexedStack, Expanded/LayoutBuilder, Table/Card, BottomSheet, CustomScrollView/Sliver, navigation widgets, FutureBuilder/StreamBuilder, animation widgets, MaterialApp/Scaffold/SafeArea và InheritedWidget.

## Giới hạn không được báo cáo sai thành “đã auto test”

- Persistence qua restart cần fixture chạy nhiều process; call sequence trong một process không tự chứng minh dữ liệu sống sau restart.
- API phải dùng fixture/injection. Test phụ thuộc endpoint hoặc internet thật sẽ gây flaky/timeout.
- Firebase/Google Sign-In và notification hệ điều hành cần emulator/device hoặc bằng chứng thủ công ngoài static contract.
- DevTools, jank, memory và chất lượng profile cần trace/screenshot/rubric; tìm chuỗi trong source không đủ chứng minh.
- APK/AAB size, signing và release cần pipeline build; source contract chỉ kiểm tra cấu hình đầu vào.

Những giới hạn này được lưu trực tiếp trong ma trận coverage để UI/report không diễn giải nhầm mức tự động hóa.
