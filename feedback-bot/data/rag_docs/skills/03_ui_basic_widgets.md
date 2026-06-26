# Skill: Widget UI cơ bản & Bố cục (Basic UI & Layout Widgets)

- **skill (lớn):** `ui_basic_widgets`
- **Tên skill:** Widget giao diện cơ bản và bố cục
- **Nguồn:** *Mastering Flutter* (Kevin Moore, 2025), Chương 4 — "Basic Widgets".
- **skill_code trong skill này:** `ui_scaffold_appbar`, `ui_container_row_column`, `ui_text_image_icon`, `ui_buttons_selection`, `ui_text_input`, `ui_drawer_snackbar`

---

## skill_code: `ui_scaffold_appbar`
**skill_name:** Scaffold, AppBar và NavigationBar · **skill:** `ui_basic_widgets`

### Khái niệm
`Scaffold` là widget Material Design dựng layout cho cả một màn hình. Các tham số chính: `appBar` (thanh tiêu đề, actions, menu), `body` (nội dung giữa màn hình), `floatingActionButton`, `bottomNavigationBar` và `drawer`. Mọi tham số đều tùy chọn trừ `body`. `AppBar` dùng để hiển thị tiêu đề trang, menu tùy chọn và nút back; hữu ích trên thiết bị di động. `BottomNavigationBar` phù hợp khi app có ba hoặc bốn màn hình để chuyển qua lại.

### Dấu hiệu đạt yêu cầu
- Màn hình bọc trong `Scaffold` với `body` là nội dung chính.
- `appBar` đặt đúng tiêu đề; `bottomNavigationBar` cho phép chuyển màn hình.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Thiếu `Scaffold` bọc ngoài → test tìm `Scaffold`/`AppBar` fail, snackbar không hiển thị.
- Đặt nội dung ngoài `body` → test tìm widget trong thân màn hình fail.

### API/Widget chính
`Scaffold`, `AppBar`, `BottomNavigationBar`, `NavigationBar`, `FloatingActionButton`, `body`, `drawer`

### Từ khóa
Scaffold, AppBar, BottomNavigationBar, body, floatingActionButton

### Nguồn: Mastering Flutter, Ch.4, tr. 102–104

---

## skill_code: `ui_container_row_column`
**skill_name:** Container, Row và Column · **skill:** `ui_basic_widgets`

### Khái niệm
`Container` chứa một widget con và cho phép trang trí: màu nền, `padding`, `alignment`, `decoration`, `width`/`height`, `transform`. `Column` xếp danh sách widget theo chiều dọc, `Row` xếp theo chiều ngang; cả hai dùng `mainAxisAlignment`, `crossAxisAlignment`, `mainAxisSize` và `children`. Lưu ý đặt `mainAxisSize` vì `Column` mặc định là `max`.

### Dấu hiệu đạt yêu cầu
- `Column`/`Row` truyền danh sách `children` đúng, đặt `mainAxisSize` hợp lý.
- `Container` đặt `color`/`decoration`/`padding` đúng để trang trí widget con.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Không đặt `mainAxisSize` khi cần `min` → bố cục chiếm hết màn hình, test layout fail.
- `children` vượt không gian, thiếu `Spacer`/căn chỉnh → cảnh báo overflow, test fail.

### API/Widget chính
`Container`, `Row`, `Column`, `Spacer`, `BoxDecoration`, `MainAxisAlignment`, `CrossAxisAlignment`, `MainAxisSize`, `EdgeInsets`

### Từ khóa
Container, Row, Column, mainAxisAlignment, crossAxisAlignment, Spacer

### Nguồn: Mastering Flutter, Ch.4, tr. 104–108

---

## skill_code: `ui_text_image_icon`
**skill_name:** Text, Image và Icon · **skill:** `ui_basic_widgets`

### Khái niệm
`Text` hiển thị văn bản tĩnh với các tham số như `data`, `style` (`TextStyle` về color, font, size), `textAlign`, `overflow` (clip, fade, ellipsis, visible), `maxLines`. `Image` hiển thị ảnh từ nhiều nguồn qua `Image.asset`, `Image.network`, `Image.file`; tham số quan trọng là `width`, `height`, `fit` (`BoxFit`). `Icon` hiển thị biểu tượng chuẩn (material/cupertino), thường đặt trong `IconButton`.

### Dấu hiệu đạt yêu cầu
- `Text` đặt đúng nội dung và `style`; dùng `overflow`/`maxLines` khi cần.
- Ảnh dùng constructor đúng nguồn (`Image.asset`/`Image.network`) và `fit` (`BoxFit`).

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Sai chuỗi trong `Text` → test tìm widget theo text fail.
- Thiếu `fit`/`overflow` → ảnh méo tỉ lệ hoặc text bị tràn, test fail.

### API/Widget chính
`Text`, `TextStyle`, `Image.asset`, `Image.network`, `Image.file`, `BoxFit`, `Icon`, `IconButton`, `Icons`

### Từ khóa
Text, TextStyle, Image.network, BoxFit, Icon, IconButton, overflow

### Nguồn: Mastering Flutter, Ch.4, tr. 108–111

---

## skill_code: `ui_buttons_selection`
**skill_name:** Buttons, Chips và widget lựa chọn · **skill:** `ui_basic_widgets`

### Khái niệm
Có nhiều loại button: `IconButton`, `TextButton`, `ElevatedButton`, `FilledButton`, `OutlinedButton`, `FloatingActionButton`, `SegmentedButton`. Widget lựa chọn gồm `Checkbox`, `Radio`, `Slider`, `Switch`. `Chip` có các loại `InputChip`, `ChoiceChip`, `FilterChip`, `ActionChip`. Ngoài ra có `DatePickerDialog`, `TimePicker` và `PopupMenuButton` (menu gắn với một button).

### Dấu hiệu đạt yêu cầu
- Button truyền `onPressed` để xử lý hành động; chọn đúng loại theo thiết kế.
- `PopupMenuButton` dùng `itemBuilder` trả về danh sách `PopupMenuItem`; `FilterChip` dùng để chọn lọc.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- `onPressed`/`onSelected` để null không xử lý → test nhấn nút không đổi trạng thái, fail.
- Dùng sai loại chip/button so với yêu cầu → test tìm widget theo loại fail.

### API/Widget chính
`IconButton`, `TextButton`, `ElevatedButton`, `FilledButton`, `OutlinedButton`, `SegmentedButton`, `Checkbox`, `Radio`, `Slider`, `Switch`, `FilterChip`, `ChoiceChip`, `DatePickerDialog`, `TimePicker`, `PopupMenuButton`, `PopupMenuItem`

### Từ khóa
Button, Checkbox, Radio, Slider, Switch, FilterChip, DatePicker, PopupMenuButton

### Nguồn: Mastering Flutter, Ch.4, tr. 112–117

---

## skill_code: `ui_text_input`
**skill_name:** Nhập liệu với TextField · **skill:** `ui_basic_widgets`

### Khái niệm
`TextField` cho phép người dùng nhập văn bản. Nó cần một `TextEditingController` để giữ giá trị, nghĩa là phải đặt trong `StatefulWidget`. Cách dễ nhất là tạo controller trong `initState` và giải phóng trong `dispose`. Các tham số chính: `controller`, `focusNode`, `decoration` (`InputDecoration`), `keyboardType`, `maxLength`, `onChanged`, `onSubmitted`.

### Dấu hiệu đạt yêu cầu
- Tạo `TextEditingController` trong `initState`, gọi `dispose()` trong `dispose`.
- Gán `controller` cho `TextField`; đặt `keyboardType` phù hợp (email, number...).

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Không `dispose()` controller → rò rỉ tài nguyên, cảnh báo khi test.
- Không gắn `controller` hoặc đọc sai giá trị → test nhập liệu/đọc text fail.

### API/Widget chính
`TextField`, `TextEditingController`, `InputDecoration`, `keyboardType`, `TextInputType`, `onChanged`, `onSubmitted`, `initState`, `dispose`

### Từ khóa
TextField, TextEditingController, keyboardType, onSubmitted, initState, dispose

### Nguồn: Mastering Flutter, Ch.4, tr. 117–119

---

## skill_code: `ui_drawer_snackbar`
**skill_name:** Drawer và Snackbar · **skill:** `ui_basic_widgets`

### Khái niệm
`Drawer` là menu trượt vào từ trái hoặc phải, gắn với `AppBar`, dùng để hiển thị menu các tùy chọn khác. `Snackbar` là cửa sổ nhỏ nổi lên hiển thị thông điệp trong thời gian ngắn, thường dùng cho lỗi. Để hiển thị snackbar bắt buộc phải có `Scaffold` làm widget cha.

### Dấu hiệu đạt yêu cầu
- `Drawer` được gán vào tham số `drawer` của `Scaffold`.
- Snackbar hiển thị trong cây có `Scaffold` cha.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Gọi snackbar khi không có `Scaffold` cha → snackbar không hiện, test tìm thông báo fail.
- Quên gắn `Drawer` vào `Scaffold` → menu không mở được, test fail.

### API/Widget chính
`Drawer`, `Scaffold.drawer`, `SnackBar`, `Scaffold`

### Từ khóa
Drawer, SnackBar, Scaffold, menu trượt, thông báo

### Nguồn: Mastering Flutter, Ch.4, tr. 103–105
