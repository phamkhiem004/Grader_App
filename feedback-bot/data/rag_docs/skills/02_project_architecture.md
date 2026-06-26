# Skill: Cấu trúc & Kiến trúc dự án (Project Structure & Architecture)

- **skill (lớn):** `project_architecture`
- **Tên skill:** Cấu trúc và kiến trúc dự án Flutter
- **Nguồn:** *Mastering Flutter* (Kevin Moore, 2025), Chương 3–4.
- **skill_code trong skill này:** `proj_pubspec_dependencies`, `proj_folder_structure`, `proj_stateless_vs_stateful`

---

## skill_code: `proj_pubspec_dependencies`
**skill_name:** pubspec.yaml và quản lý dependencies · **skill:** `project_architecture`

### Khái niệm
`pubspec.yaml` định nghĩa dự án cùng toàn bộ packages và plugins mà app cần. File YAML gồm field, dấu hai chấm và value; phần tử con thụt lề đúng hai khoảng trắng (spaces, không phải tabs). Các field chính gồm `name`, `description`, `publish_to`, `version`, `environment`, `dependencies`.

### Dấu hiệu đạt yêu cầu
- Khai báo `flutter: sdk: flutter` trong `dependencies` và thụt lề đúng hai khoảng trắng.
- Đặt package mới (ví dụ `material_symbols_icons`, `cupertino_icons`) đúng dưới `dependencies` và chạy Pub get.
- Đặt `flutter_test`, `integration_test`, `flutter_lints` trong `dev_dependencies`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Thụt lề ba khoảng trắng hoặc dùng tab → build không chạy.
- Để package test ngoài `dev_dependencies` → cấu hình phụ thuộc sai, lint cảnh báo.
- Thiếu `environment` SDK constraint `'>=3.3.4 <4.0.0'` → không kiểm soát được phiên bản Flutter/Dart.

### API/Widget chính
`pubspec.yaml`, `name`, `description`, `version`, `environment`, `dependencies`, `dev_dependencies`, `flutter_test`, `integration_test`, `flutter_lints`, `uses-material-design`, Pub get

### Từ khóa
pubspec.yaml, dependencies, dev_dependencies, YAML indentation, environment SDK, flutter_lints, Pub get, material_symbols_icons

### Nguồn: Mastering Flutter, Ch.3–4, tr. 99–102

---

## skill_code: `proj_folder_structure`
**skill_name:** Cấu trúc thư mục lib/ và Clean Architecture · **skill:** `project_architecture`

### Khái niệm
Thư mục `lib/` là nơi chứa toàn bộ file Dart, có thể lồng folder tùy ý. Clean architecture tách biệt mối quan tâm và giữ business logic độc lập với UI, database, framework, làm code dễ test và bảo trì. Cấu trúc folder gợi ý gồm `data/` (database, models, repository, sources), `network/`, `router/`, `ui/` (screens, themes, widgets), `utils/`.

### Dấu hiệu đạt yêu cầu
- Tổ chức code theo các layer riêng biệt (domain, use cases, interface adapters, frameworks/drivers).
- Tuân thủ dependency rule: thành phần cấp cao không phụ thuộc UI/framework/database.
- Đặt screens, widgets, themes trong `ui/`; tách model và repository trong `data/`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Gộp toàn bộ code vào một file/folder → khó test, vi phạm single-responsibility.
- Business logic phụ thuộc trực tiếp UI/database → vi phạm dependency rule, test khó cô lập.
- Thiếu abstraction (interface) để swap component → test không mock được.

### API/Widget chính
`lib/`, `data/`, `database/`, `models/`, `repository/`, `sources/`, `network/`, `router/`, `ui/`, `screens/`, `themes/`, `widgets/`, `utils/`, `test/`, `integration_test/`

### Từ khóa
clean architecture, folder structure, lib folder, layering, dependency rule, SOLID, separation of concerns, testability

### Nguồn: Mastering Flutter, Ch.3–4, tr. 91–103

---

## skill_code: `proj_stateless_vs_stateful`
**skill_name:** StatelessWidget và StatefulWidget · **skill:** `project_architecture`

### Khái niệm
Flutter dùng widget để dựng UI declarative. `StatelessWidget` là immutable, chỉ hiển thị thông tin được truyền vào và không đổi giá trị. `StatefulWidget` giữ state có thể thay đổi (ví dụ một field như `_counter` hoặc text controller), khi state đổi widget được rebuild.

### Dấu hiệu đạt yêu cầu
- Dùng `StatelessWidget` cho UI tĩnh không có state (ví dụ `MyApp`).
- Dùng `StatefulWidget` kèm `State` và `createState()` khi UI thay đổi theo state (ví dụ `MyHomePage`, `MainApp`).
- Override `build(BuildContext context)` trả về cây widget.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Chọn `StatelessWidget` cho màn cần cập nhật trạng thái → UI không rebuild, test thay đổi state fail.
- Dùng `StatefulWidget` cho giao diện tĩnh → thừa boilerplate `State`/`createState()`.
- Quên `createState()` hoặc lớp `State` → build fail, widget không khởi tạo được.

### API/Widget chính
`StatelessWidget`, `StatefulWidget`, `State<T>`, `createState()`, `build(BuildContext)`, `main()`, `runApp()`, `MaterialApp`, `Scaffold`, `ThemeData`, `Placeholder`

### Từ khóa
StatelessWidget, StatefulWidget, State, createState, build, immutable, setState, widget tree

### Nguồn: Mastering Flutter, Ch.3–4, tr. 85–96
