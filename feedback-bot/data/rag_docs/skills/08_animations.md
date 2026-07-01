# Skill: Hoạt ảnh & Chuyển cảnh (Animations & Transitions)

- **skill (lớn):** `ANIMATIONS`
- **Tên skill:** Hoạt ảnh và hiệu ứng chuyển cảnh
- **Nguồn:** *Mastering Flutter* (Kevin Moore, 2025), Chương 9 — "Animations and Transitions".
- **skill_code trong skill này:** `ANIM_IMPLICIT`, `ANIM_TWEEN`, `ANIM_EXPLICIT_CONTROLLER`, `ANIM_HERO`, `ANIM_PACKAGES`

---

## skill_code: `ANIM_IMPLICIT`
**skill_name:** Hoạt ảnh ẩn (Implicit animations) · **skill:** `ANIMATIONS`

### Khái niệm
Implicit animations là các widget dạng `AnimatedXXXX` tự động chạy hoạt ảnh khi giá trị thuộc tính thay đổi, dễ dùng nhưng ít kiểm soát hơn explicit. Tất cả kế thừa `ImplicitlyAnimatedWidget`. Ví dụ `AnimatedContainer` có hai tham số bắt buộc là `duration` và `child`; chỉ cần đổi giá trị (như `color`) là widget tự chuyển động theo `curve`.

### Dấu hiệu đạt yêu cầu
- Dùng đúng widget `AnimatedXXXX` cho thuộc tính cần đổi (color, opacity, size, position).
- Có khai báo `duration` và thay đổi giá trị thuộc tính để kích hoạt hoạt ảnh.
- Dùng `curve` (vd `Curves.easeInOut`) cho chuyển động tự nhiên.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Thiếu `duration` bắt buộc → lỗi biên dịch / hoạt ảnh không chạy.
- Đặt `AnimatedPositioned` ngoài `Stack` → widget không di chuyển, lỗi runtime.
- Đổi giá trị nhưng không qua `setState` → UI đứng yên, không thấy hiệu ứng.

### API/Widget chính
`AnimatedContainer`, `AnimatedOpacity`, `AnimatedAlign`, `AnimatedPadding`, `AnimatedPositioned`, `AnimatedSize`, `AnimatedScale`, `AnimatedRotation`, `AnimatedSlide`, `AnimatedIcon`, `AnimatedCrossFade`, `AnimatedSwitcher`, `ImplicitlyAnimatedWidget`, `Curves`

### Từ khóa
implicit animation, AnimatedContainer, AnimatedXXXX, duration, curve, ImplicitlyAnimatedWidget

### Nguồn: Mastering Flutter, Ch.9, tr. 312–315

---

## skill_code: `ANIM_TWEEN`
**skill_name:** Hoạt ảnh Tween (Tween animations) · **skill:** `ANIMATIONS`

### Khái niệm
Khi không có widget `AnimatedXXXX` phù hợp, dùng `TweenAnimationBuilder` với một `Tween` (in-between) có `begin` và `end` để chuyển một thuộc tính từ giá trị này sang giá trị khác. `builder` nhận `(context, value, child)` và dựng widget theo `value`. Có thể dùng `ColorTween` (cần kiểu nullable) hoặc `Tween<double>` cho dịch chuyển.

### Dấu hiệu đạt yêu cầu
- Khai báo `tween` với `begin`/`end` đúng kiểu dữ liệu thuộc tính.
- Dùng `value` trong `builder` để áp vào widget.
- Truyền và tái dùng `child` trong builder để tối ưu hiệu năng.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Dùng `ColorTween` mà không khai báo kiểu nullable `Color?` → lỗi kiểu.
- Không dùng `child` truyền vào builder → toàn bộ cây widget rebuild mỗi frame, lag.
- Quên `duration` → hoạt ảnh không có thời lượng.

### API/Widget chính
`TweenAnimationBuilder`, `Tween`, `ColorTween`, `Transform.translate`, `Offset`, `curve`, `duration`, `builder`

### Từ khóa
TweenAnimationBuilder, Tween, ColorTween, begin end, builder value child

### Nguồn: Mastering Flutter, Ch.9, tr. 315–317

---

## skill_code: `ANIM_EXPLICIT_CONTROLLER`
**skill_name:** Hoạt ảnh tường minh (Explicit: AnimationController, Animation, Curves, AnimatedBuilder) · **skill:** `ANIMATIONS`

### Khái niệm
Explicit animations cho phép tự điều khiển thời điểm chạy bằng `AnimationController`, lớp sinh dải giá trị begin–end theo `duration`, có thể `forward`, `reverse`, `stop`. Controller cần một `Ticker` qua mixin `SingleTickerProviderStateMixin` (một controller) hoặc `TickerProviderStateMixin` (nhiều controller), với `vsync: this`. `Animation` (vd `CurvedAnimation`) áp `Curve` lên controller; `AnimatedBuilder` lắng nghe và rebuild widget.

### Dấu hiệu đạt yêu cầu
- State class có mixin ticker và `vsync: this` khi tạo controller.
- Gọi `_controller.dispose()` trong `dispose()`; khởi động bằng `forward()`.
- Dùng `Animation`/`CurvedAnimation` hoặc `controller.drive(...)` và transition (vd `FadeTransition`) hoặc `AnimatedBuilder`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Quên `dispose()` controller → cảnh báo memory leak / Ticker leak khi rời màn hình.
- Thiếu ticker mixin hoặc `vsync` → lỗi runtime "no TickerProvider".
- Không gọi `forward()` → hoạt ảnh không bao giờ chạy.

### API/Widget chính
`AnimationController`, `Animation`, `CurvedAnimation`, `CurveTween`, `Curves`, `Tween.animate`, `Interval`, `SingleTickerProviderStateMixin`, `TickerProviderStateMixin`, `vsync`, `AnimatedBuilder`, `FadeTransition`, `forward`, `reverse`, `orCancel`

### Từ khóa
AnimationController, vsync, TickerProviderStateMixin, dispose, CurvedAnimation, Curves, AnimatedBuilder, staggered, Interval

### Nguồn: Mastering Flutter, Ch.9, tr. 317–328

---

## skill_code: `ANIM_HERO`
**skill_name:** Hoạt ảnh Hero (Hero animations) · **skill:** `ANIMATIONS`

### Khái niệm
`Hero` tạo hiệu ứng chuyển tiếp một widget (thường là ảnh) giữa hai trang: widget bay từ vị trí trang nguồn sang vị trí trang đích. Cách dùng: bọc widget trong `Hero` và gán `tag` giống nhau, duy nhất ở cả hai trang. Trong movie app, tag duy nhất được tạo từ `movieUrl + movieType` để tránh trùng giữa các section.

### Dấu hiệu đạt yêu cầu
- Bọc widget bằng `Hero` ở cả trang nguồn và trang đích.
- `tag` ở hai trang trùng nhau và duy nhất (vd ghép URL + loại).
- Đồng bộ tag qua state/provider (vd `heroTagProvider`) khi tag động.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Tag trùng nhau trong cùng một trang → lỗi runtime "multiple heroes share the same tag".
- Tag hai trang không khớp → không có hoạt ảnh chuyển tiếp.
- Quên đặt tag ở trang đích → ảnh nhảy đột ngột, không "bay".

### API/Widget chính
`Hero`, `tag`, `CachedNetworkImage`, `StateProvider`, `heroTagProvider`, `context.router.push`

### Từ khóa
Hero animation, tag, unique tag, page transition, hero flight, StateProvider

### Nguồn: Mastering Flutter, Ch.9, tr. 330–337

---

## skill_code: `ANIM_PACKAGES`
**skill_name:** Gói hoạt ảnh, flutter_animate & custom routes · **skill:** `ANIMATIONS`

### Khái niệm
Ngoài widget dựng sẵn còn có gói bên thứ ba: cấp cao như Lottie (JSON) và Rive (cần công cụ riêng); và gói hiệu ứng như `flutter_animate`, `flutter_spinkit`, `flutter_staggered_animations`, `simple_animations`, `Spring`. `flutter_animate` (tác giả Wonderous) thêm hiệu ứng qua chuỗi `.animate().scaleXY().then()`. Vẽ tùy biến dùng `CustomPaint`/`CustomPainter` với `Canvas`. AutoRoute có `CustomRoute` thêm hoạt ảnh chuyển trang.

### Dấu hiệu đạt yêu cầu
- Thêm gói vào `pubspec.yaml` và chạy pub get trước khi dùng.
- Dùng `.animate()` với `controller`/`autoPlay` đúng, hoặc `CustomPainter` override `paint`/`shouldRepaint`.
- Dùng `CustomRoute` với `transitionsBuilder` cho chuyển trang.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Dùng `flutter_animate` với controller nhưng thiếu ticker mixin → lỗi runtime.
- `shouldRepaint` trả về sai → vẽ lại liên tục gây lag, hoặc không cập nhật.
- Quên pub get sau khi thêm gói → lỗi import không tìm thấy package.

### API/Widget chính
`flutter_animate`, `.animate()`, `.scaleXY()`, `.then()`, `Animate`, `CustomPaint`, `CustomPainter`, `Canvas`, `Paint`, `RotationTransition`, `ScaleTransition`, `SizeTransition`, `SlideTransition`, `AnimatedList`, `CustomRoute`, `TransitionsBuilders`, Lottie, Rive

### Từ khóa
flutter_animate, Lottie, Rive, CustomPaint, CustomPainter, Canvas, CustomRoute, transitionsBuilder, AnimatedList

### Nguồn: Mastering Flutter, Ch.9, tr. 329–350
