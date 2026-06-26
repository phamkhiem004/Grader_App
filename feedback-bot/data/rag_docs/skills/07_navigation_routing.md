# Skill: Điều hướng & Định tuyến (Navigation & Routing)

- **skill (lớn):** `navigation_routing`
- **Tên skill:** Điều hướng và định tuyến màn hình
- **Nguồn:** *Mastering Flutter* (Kevin Moore, 2025), Chương 8 — "Navigation and Routing".
- **skill_code trong skill này:** `nav_navigator_push_pop`, `nav_named_routes`, `nav_gorouter_autoroute`, `nav_bottom_navigation`, `nav_deep_linking`

---

## skill_code: `nav_navigator_push_pop`
**skill_name:** Navigator và push/pop · **skill:** `navigation_routing`

### Khái niệm
Điều hướng là việc đi từ màn hình này sang màn hình khác. Flutter dùng widget `Navigator` quản lý một stack các trang (gọi là route); `push` đẩy trang mới lên đỉnh stack, `pop` lấy trang trên cùng ra để hiện trang phía dưới. `Navigator` có mảng `pages` chứa các `Page` (thường là `MaterialPage`).

### Dấu hiệu đạt yêu cầu
- Dùng `Navigator.push`/`pop` (hoặc `Navigator.of(context)`) để chuyển và quay lại trang.
- Mỗi `MaterialPage` có `key` và `child` rõ ràng; `onPopPage` gọi `route.didPop` rồi cập nhật danh sách pages.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Không gọi `pop` để quay lại → test nhấn back thấy vẫn ở màn hình cũ, fail.
- `onPopPage` không gọi `route.didPop` hoặc không bỏ trang khỏi list → stack sai, test điều hướng fail.

### API/Widget chính
`Navigator`, `MaterialPage`, `Page`, `push(context, route)`, `pop(context)`, `onPopPage`, `route.didPop`, `Navigator.of(context)`

### Từ khóa
Navigator, push, pop, stack, MaterialPage, onPopPage

### Nguồn: Mastering Flutter, Ch.8, tr. 250–252

---

## skill_code: `nav_named_routes`
**skill_name:** Named routes và MaterialApp routes · **skill:** `navigation_routing`

### Khái niệm
Named routes là route được đặt tên bằng chuỗi; `Navigator.pushNamed(context, routeName)` tìm route theo tên và đẩy lên stack. Có thể khai báo qua tham số `routes` của `MaterialApp` (map tên route sang `WidgetBuilder`). Truyền dữ liệu qua `arguments` và đọc lại bằng `ModalRoute.of(context)!.settings.arguments`; trả dữ liệu về bằng tham số thứ hai của `pop`.

### Dấu hiệu đạt yêu cầu
- Khai báo map `routes` trong `MaterialApp` và điều hướng bằng `pushNamed`.
- Truyền `arguments` và nhận lại đúng qua `ModalRoute`; `await` kết quả khi cần dữ liệu trả về.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Tên route không khớp key trong map → exception khi push, test mở trang fail.
- Ép kiểu `arguments` sai hoặc đọc khi `null` → crash, test đọc dữ liệu fail.

### API/Widget chính
`Navigator.pushNamed`, `MaterialApp(routes:)`, `WidgetBuilder`, `arguments`, `ModalRoute.of(context)`, `RouteSettings`, `Navigator.pop(context, result)`

### Từ khóa
named routes, pushNamed, routes map, arguments, ModalRoute

### Nguồn: Mastering Flutter, Ch.8, tr. 255–259

---

## skill_code: `nav_gorouter_autoroute`
**skill_name:** GoRouter và AutoRoute · **skill:** `navigation_routing`

### Khái niệm
Vì dùng Router API trực tiếp khá rối, có các package hỗ trợ: GoRouter (do Google bảo trì, được khuyến nghị) và AutoRoute (dùng code generation). Cả hai dùng `MaterialApp.router` với tham số `routerConfig`. GoRouter khai báo `GoRoute` với `path` kiểu URL, có route con và tham số (`state.pathParameters`). AutoRoute dùng annotation `@AutoRouterConfig`, `@RoutePage` và chạy `build_runner` để sinh code.

### Dấu hiệu đạt yêu cầu
- Cấu hình `MaterialApp.router(routerConfig: ...)` với GoRouter hoặc AutoRoute.
- AutoRoute: gắn `@RoutePage`, khai báo `AutoRoute(path/page)`, chạy `dart run build_runner build`, push qua `context.router.push`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Quên chạy `build_runner` → file `.gr.dart` thiếu, build fail, route không tồn tại.
- `path` hoặc tên `@RoutePage` không khớp route khai báo → push route không tìm thấy, test fail.

### API/Widget chính
`GoRouter`, `GoRoute`, `MaterialApp.router`, `routerConfig`, `state.pathParameters`, `@AutoRouterConfig`, `@RoutePage`, `AutoRoute`, `context.router.push`, `AutoTabsScaffold`, `build_runner`

### Từ khóa
GoRouter, AutoRoute, routerConfig, GoRoute, RoutePage, build_runner

### Nguồn: Mastering Flutter, Ch.8, tr. 272–279

---

## skill_code: `nav_bottom_navigation`
**skill_name:** Bottom navigation · **skill:** `navigation_routing`

### Khái niệm
Cách điều hướng đơn giản là dùng thanh dưới màn hình chứa ba đến năm mục. `BottomNavigationBar` dùng các `BottomNavigationBarItem` (icon + label), với `currentIndex` và callback `onTap`. Material 3 có widget mới `NavigationBar` dùng `destinations` là các `NavigationDestination`, với `selectedIndex` và `onDestinationSelected`. Material còn cung cấp `NavigationRail` đặt ở cạnh trái/phải.

### Dấu hiệu đạt yêu cầu
- Khai báo 3–5 mục; cập nhật `index` trong `onTap`/`onDestinationSelected` qua `setState`.
- `currentIndex`/`selectedIndex` phản ánh đúng tab đang chọn; đổi màu qua theme khi dùng `NavigationBar`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Không gọi `setState` khi đổi index → tab không đổi, test chạm tab fail.
- Dùng `NavigationBar` nhưng vẫn để `currentIndex`/`onTap` (sai API) → không build hoặc tab không phản hồi.

### API/Widget chính
`BottomNavigationBar`, `BottomNavigationBarItem`, `currentIndex`, `onTap`, `NavigationBar`, `NavigationDestination`, `selectedIndex`, `onDestinationSelected`, `NavigationRail`

### Từ khóa
BottomNavigationBar, NavigationBar, NavigationDestination, selectedIndex, onDestinationSelected

### Nguồn: Mastering Flutter, Ch.8, tr. 270–272

---

## skill_code: `nav_deep_linking`
**skill_name:** Deep linking và custom schemes · **skill:** `navigation_routing`

### Khái niệm
Deep link là một URL trỏ tới trang cụ thể trong app; khi nhận link, router phải xác định trang cần đến. URL gồm scheme (thường `https`), host name, path và query parameters. Ngoài `https`, trên mobile có thể tạo custom scheme (vd `movieapp://moviedetails/?id=1234`). Android gọi là deep link, iOS gọi là custom URL; phải khai báo trong `AndroidManifest.xml` và `info.plist`.

### Dấu hiệu đạt yêu cầu
- Khai báo `intent-filter` (Android) và `URL types`/`FlutterDeepLinkingEnabled` (iOS) cho scheme/host đúng.
- Với https App Links/Universal Links: có file xác minh domain (`assetlinks.json`, `apple-app-site-association`) đặt tại `.well-known`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Thiếu `intent-filter`/`URL types` hoặc sai scheme/host → link không mở đúng trang, test deep link fail.
- Thiếu xác minh domain (`autoVerify`, assetlinks/site-association) → https link không vào app, test fail.

### API/Widget chính
custom scheme, `intent-filter`, `AndroidManifest.xml`, `android:autoVerify`, `flutter_deeplinking_enabled`, `info.plist`, `FlutterDeepLinkingEnabled`, `assetlinks.json`, `apple-app-site-association`, App Links, Universal Links

### Từ khóa
deep link, custom scheme, App Links, Universal Links, intent-filter, assetlinks.json

### Nguồn: Mastering Flutter, Ch.8, tr. 259–269
