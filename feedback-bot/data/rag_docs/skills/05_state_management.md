# Skill: Quản lý trạng thái (State Management)

- **skill (lớn):** `STATE_MANAGEMENT`
- **Tên skill:** Quản lý trạng thái trong Flutter
- **Nguồn:** *Mastering Flutter* (Kevin Moore, 2025), Chương 6 — "State Management Fundamentals".
- **skill_code trong skill này:** `STATE_SETSTATE_STATEFUL`, `STATE_INHERITED_WIDGET`, `STATE_PROVIDER`, `STATE_RIVERPOD`, `STATE_BLOC_OTHER`, `STATE_IMMUTABLE`

---

## skill_code: `STATE_SETSTATE_STATEFUL`
**skill_name:** setState và state có sẵn với StatefulWidget · **skill:** `STATE_MANAGEMENT`

### Khái niệm
State là dữ liệu thay đổi theo thời gian và làm UI cập nhật. `StatelessWidget` chỉ hiển thị dữ liệu, còn `StatefulWidget` giữ state riêng và gọi `setState` để báo cho hệ thống Flutter biết cần vẽ lại. State class lưu dữ liệu, build UI và theo dõi vòng đời qua `initState`, `setState`, `build`. Nên gọi `setState` ở vị trí thấp nhất trong widget tree để chỉ vẽ lại phần thay đổi.

### Dấu hiệu đạt yêu cầu
- Dùng `StatefulWidget` + `State` để lưu dữ liệu thay đổi cục bộ.
- Gọi `setState` khi state đổi để UI vẽ lại.
- Khởi tạo dữ liệu trong `initState`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Đổi biến mà không gọi `setState` → UI không cập nhật sau khi state đổi, test tìm giá trị mới fail.
- Đặt `setState` quá cao trong tree → cả màn hình bị vẽ lại, test hiệu năng/widget fail.
- Quên `initState` → dữ liệu chưa khởi tạo, test đọc giá trị ban đầu fail.

### API/Widget chính
`StatefulWidget`, `StatelessWidget`, `State`, `setState`, `initState`, `build`, `BuildContext`

### Từ khóa
setState, StatefulWidget, State class, initState, build, rebuild, local state

### Nguồn: Mastering Flutter, Ch.6, tr. 174–175

---

## skill_code: `STATE_INHERITED_WIDGET`
**skill_name:** InheritedWidget · **skill:** `STATE_MANAGEMENT`

### Khái niệm
`InheritedWidget` là widget của Flutter truyền state xuống dưới widget tree mà không cần truyền qua tham số; state được lưu trong `BuildContext`. Các subclass thường có một static method `of` nhận `context` và trả về giá trị, dùng `dependOnInheritedWidgetOfExactType` để lấy giá trị đã lưu. Sách lưu ý widget này ít được dùng trực tiếp.

### Dấu hiệu đạt yêu cầu
- Có static `of(BuildContext context)` trả về instance.
- Dùng `dependOnInheritedWidgetOfExactType` để lấy state.
- Truyền state xuống cây mà không qua tham số.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Gọi `dependOnInheritedWidgetOfExactType` sai kiểu → trả về null, test đọc state fail.
- Vẫn truyền state qua tham số thủ công → không đúng mẫu InheritedWidget.

### API/Widget chính
`InheritedWidget`, `BuildContext`, `dependOnInheritedWidgetOfExactType`, static `of`

### Từ khóa
InheritedWidget, BuildContext, of method, dependOnInheritedWidgetOfExactType

### Nguồn: Mastering Flutter, Ch.6, tr. 175–176

---

## skill_code: `STATE_PROVIDER`
**skill_name:** Provider · **skill:** `STATE_MANAGEMENT`

### Khái niệm
`Provider` là package của Google (đang ở chế độ maintenance), được mô tả như một wrapper quanh `InheritedWidget` để widget con truy cập state định nghĩa ở trên mà không truyền qua tham số. Lớp cơ bản `Provider` có tham số `create` (tạo class một lần) và `child`. `ChangeNotifierProvider` dùng `ChangeNotifier` và gọi `notifyListeners()` để cập nhật widget khi giá trị đổi. Nhiều provider thì dùng `MultiProvider`.

### Dấu hiệu đạt yêu cầu
- Tạo dữ liệu một lần trong `create`, dùng lại qua `child`.
- Lớp state extends `ChangeNotifier`, gọi `notifyListeners()` khi đổi.
- Dùng `MultiProvider` khi có nhiều provider.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Quên `notifyListeners()` → UI không cập nhật khi giá trị đổi, test fail.
- Khởi tạo lại class trong mỗi build thay vì dùng `create` → state bị reset.

### API/Widget chính
`Provider`, `ChangeNotifierProvider`, `ChangeNotifier`, `notifyListeners`, `MultiProvider`, `create`, `child`

### Từ khóa
Provider, ChangeNotifier, ChangeNotifierProvider, notifyListeners, MultiProvider

### Nguồn: Mastering Flutter, Ch.6, tr. 177–178

---

## skill_code: `STATE_RIVERPOD`
**skill_name:** Riverpod · **skill:** `STATE_MANAGEMENT`

### Khái niệm
Riverpod là package cùng tác giả với Provider (anagram của "provider"), là reactive caching framework cho Flutter/Dart, xử lý bất đồng bộ kèm error handling và cache. Có generator dùng annotation `@riverpod` để sinh code. Bọc `MainApp` trong `ProviderScope` để lưu state mọi provider. Reference class chính là `WidgetRef` với `watch`, `listen`, `read`. Widget UI subclass `ConsumerWidget` hoặc `ConsumerStatefulWidget`/`ConsumerState`.

### Dấu hiệu đạt yêu cầu
- Bọc app trong `ProviderScope`; định nghĩa provider qua `@riverpod`.
- Dùng `ref.watch` trong `build` để theo dõi thay đổi; `ref.read` cho hành động ngoài build.
- Widget kế thừa `ConsumerWidget`/`ConsumerStatefulWidget`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Dùng `read` thay `watch` trong `build` → UI không cập nhật khi provider đổi.
- Quên chạy `build_runner` → thiếu file `.g.dart`, build fail.
- Không bọc `ProviderScope` → đọc provider lỗi runtime.

### API/Widget chính
`ProviderScope`, `@riverpod`, `WidgetRef`, `ref.watch`, `ref.listen`, `ref.read`, `ConsumerWidget`, `ConsumerStatefulWidget`, `ConsumerState`, `build_runner`

### Từ khóa
Riverpod, ProviderScope, WidgetRef, watch, read, ConsumerWidget, riverpod_annotation, build_runner

### Nguồn: Mastering Flutter, Ch.6, tr. 181–187

---

## skill_code: `STATE_BLOC_OTHER`
**skill_name:** BLoC, GetIt, Redux, MobX · **skill:** `STATE_MANAGEMENT`

### Khái niệm
Ngoài Provider/Riverpod còn nhiều package: BLoC tách UI khỏi business logic, action gửi tới một `cubit` lưu state rồi phát state mới cho UI (nhiều code). GetIt là service locator/dependency injection, đăng ký singleton qua `registerSingleton`. Redux theo ba nguyên tắc (single source of truth, state read-only, pure reducers) với `StoreProvider`, `StoreConnector`. MobX dùng observables, actions, reactions với annotation `@observable`, `@action` và widget `Observer`.

### Dấu hiệu đạt yêu cầu
- BLoC: action → cubit → state mới gửi cho UI, tách biệt logic và UI.
- GetIt: đăng ký `registerSingleton`, lấy lại bằng `getIt<T>()`.
- Redux/MobX: state thay đổi qua reducer/action, dùng `StoreConnector`/`Observer`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Trộn business logic vào UI khi dùng BLoC → vi phạm mẫu, khó test.
- Sửa state trực tiếp trong Redux thay vì qua reducer thuần → vi phạm state read-only.
- Quên `@observable`/`@action` trong MobX → reaction không chạy, UI không cập nhật.

### API/Widget chính
`bloc`, `cubit`, `GetIt.instance`, `registerSingleton`, `StoreProvider`, `StoreConnector`, `@observable`, `@action`, `Observer`

### Từ khóa
BLoC, cubit, GetIt, dependency injection, Redux, reducer, MobX, observable, action

### Nguồn: Mastering Flutter, Ch.6, tr. 178–181

---

## skill_code: `STATE_IMMUTABLE`
**skill_name:** Immutable state, local vs app state · **skill:** `STATE_MANAGEMENT`

### Khái niệm
Local state là dữ liệu riêng của một widget, quản lý trong class `StatefulWidget` (ví dụ lưu selection của `FilterChip`). App state là dữ liệu chia sẻ giữa nhiều phần của app (user preferences, authentication, giỏ hàng) và cần kỹ thuật state management như Riverpod. Immutability quan trọng vì state thay đổi được bởi nhiều class dễ sinh bug do các instance khác nhau; điểm yếu là phải tạo bản copy mới mỗi lần cập nhật.

### Dấu hiệu đạt yêu cầu
- Phân biệt đúng local state (trong widget) và app state (chia sẻ toàn app).
- Lưu selection cục bộ (vd `FilterChip`) trong `StatefulWidget`.
- State chia sẻ được giữ một nơi, tạo copy mới khi cập nhật immutable.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Để local state thành global không cần thiết → state khó kiểm soát, test cô lập fail.
- Sửa trực tiếp state dùng chung → các phần app thấy giá trị khác nhau, hành vi không nhất quán.

### API/Widget chính
`StatefulWidget`, `FilterChip`, local state, app state, immutable state, lifting state

### Từ khóa
local state, app state, immutable, immutability, lifting state, FilterChip, shared state

### Nguồn: Mastering Flutter, Ch.6, tr. 174–175, 181
