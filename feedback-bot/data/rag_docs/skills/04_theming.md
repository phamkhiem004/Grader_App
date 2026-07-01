# Skill: Theme, Màu sắc & Font chữ (Themes, Colors & Fonts)

- **skill (lớn):** `THEMING`
- **Tên skill:** Chủ đề giao diện, màu sắc và font chữ
- **Nguồn:** *Mastering Flutter* (Kevin Moore, 2025), Chương 5 — "Themes, Colors and Fonts".
- **skill_code trong skill này:** `THEME_COLORS_COLORSCHEME`, `THEME_TYPOGRAPHY_FONTS`, `THEME_LIGHT_DARK`

---

## skill_code: `THEME_COLORS_COLORSCHEME`
**skill_name:** Màu sắc, ColorScheme và Material Design · **skill:** `THEMING`

### Khái niệm
Trong Flutter, màu là số hexadecimal gồm red, green, blue và alpha (độ mờ); `0xFF` là opaque hoàn toàn, `0x00` là trong suốt. Có thể dùng màu định sẵn (`Colors`), tạo `Color(0xFFC13437)`, hoặc `Color.fromARGB`/`fromRGBO`. Material Design là design system của Google, tích hợp sẵn trong Flutter và tùy biến qua theme; dùng nó bằng cách khởi chạy app với `MaterialApp`. `ColorScheme` định nghĩa bộ màu, dễ tạo nhất bằng `ColorScheme.fromSeed` với `seedColor`.

### Dấu hiệu đạt yêu cầu
- Dùng `Color(0xFF...)` hoặc `Colors.*` thay vì màu hard-code rải rác, đặt tên biến màu rõ nghĩa.
- Tạo `ColorScheme` qua `fromSeed` với `seedColor` và `brightness`, có thể set `primary`, `onPrimary`, `secondary`.
- App được bọc bởi `MaterialApp` để dùng Material Design.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Đặt `color` trực tiếp cho `Text` thay vì qua `TextStyle` → text không đổi màu, test màu chữ fail.
- Quên `seedColor` hoặc không set `brightness` → bảng màu sinh ra sai sắc độ.
- Widget cần parent `Material` nhưng thiếu → lỗi runtime "No Material widget found".

### API/Widget chính
`Color`, `Color.fromARGB`, `Color.fromRGBO`, `Colors`, `withOpacity`, `ColorScheme.fromSeed`, `seedColor`, `Brightness`, `MaterialApp`, `Material`, `Container`

### Từ khóa
Color, hex, alpha, ColorScheme, fromSeed, seedColor, Material Design, MaterialApp, primary, onPrimary

### Nguồn: Mastering Flutter, Ch.5, tr. 142–144, 151

---

## skill_code: `THEME_TYPOGRAPHY_FONTS`
**skill_name:** Typography và Google Fonts · **skill:** `THEMING`

### Khái niệm
Typography là cách sắp xếp text trên màn hình qua font, cỡ chữ, màu, line spacing và letter spacing; cài đặt qua lớp `TextStyle`. Nên định nghĩa style một lần rồi tái sử dụng thay vì viết tay từng lần. Package `google_fonts` cho phép dùng kho font Google, ví dụ `GoogleFonts.roboto()` trả về một `TextStyle`, rồi dùng `copyWith` để tạo các biến style (largeTitle, heading1, body1Regular...).

### Dấu hiệu đạt yêu cầu
- Thêm package `google_fonts` (`flutter pub add google_fonts`) và import trong file theme.
- Tạo TextStyle dùng chung bằng `GoogleFonts.roboto().copyWith(...)` với `fontSize`, `fontWeight`, `color`.
- Thay style hard-code trong widget bằng các biến style đã định nghĩa.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Lặp lại TextStyle dài ở nhiều nơi thay vì biến dùng chung → code trùng lặp, khó bảo trì.
- Dùng sai `fontWeight` (w400 normal, w700 bold, w600 semibold) → chữ hiển thị đậm/nhạt sai.
- Đặt cỡ chữ dưới 10px → chữ rất khó đọc.

### API/Widget chính
`TextStyle`, `fontSize`, `fontWeight`, `FontWeight.w100`–`w900`, `color`, `letterSpacing`, `GoogleFonts.roboto`, `copyWith`

### Từ khóa
Typography, TextStyle, google_fonts, GoogleFonts, Roboto, fontWeight, copyWith, font size

### Nguồn: Mastering Flutter, Ch.5, tr. 143, 145–149

---

## skill_code: `THEME_LIGHT_DARK`
**skill_name:** Tạo Theme và chế độ sáng/tối (Light/Dark) · **skill:** `THEMING`

### Khái niệm
Theme gồm colors, fonts, shapes, icons để mọi màn hình nhất quán, truyền qua tham số `theme` của `MaterialApp` dưới dạng `ThemeData`. Cách dễ nhất là `ThemeData.light(useMaterial3: true)` hoặc `ThemeData.dark(...)`. Theme tùy biến đầy đủ set `colorScheme`, `textTheme` (qua `Typography.material2021()`), `appBarTheme`, `bottomNavigationBarTheme` và các theme khác. Có thể đọc độ sáng thiết bị qua `View.of(context).platformDispatcher.platformBrightness` để chọn light hay dark.

### Dấu hiệu đạt yêu cầu
- Tạo hàm trả về `ThemeData` và gắn vào `theme: createTheme()` trong `MaterialApp`.
- Định nghĩa `textTheme` bằng `Typography.material2021().englishLike.copyWith(...)` ánh xạ các style.
- Chọn theme theo `Brightness.light`/`Brightness.dark` dựa trên `platformBrightness`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Đặt format trực tiếp trên widget → ghi đè theme, UI mất tính nhất quán.
- Quên gắn `theme` vào `MaterialApp` → app dùng theme mặc định, màu/font không áp dụng.
- Kỳ vọng theme đổi ngay khi brightness đổi → không cập nhật cho đến khi relaunch app.

### API/Widget chính
`ThemeData`, `ThemeData.light`, `ThemeData.dark`, `useMaterial3`, `colorScheme`, `textTheme`, `Typography.material2021`, `appBarTheme`, `AppBarTheme`, `bottomNavigationBarTheme`, `platformBrightness`, `Brightness`

### Từ khóa
ThemeData, theme, light, dark, useMaterial3, textTheme, Typography, appBarTheme, platformBrightness, brightness

### Nguồn: Mastering Flutter, Ch.5, tr. 149–162
