# Skill: Widget nâng cao (Advanced Widgets)

- **skill (lớn):** `ADVANCED_WIDGETS`
- **Tên skill:** Widget bố cục và hiển thị nâng cao
- **Nguồn:** *Mastering Flutter* (Kevin Moore, 2025), Chương 7 — "Advanced Widgets".
- **skill_code trong skill này:** `ADVUI_LISTVIEW`, `ADVUI_GRIDVIEW`, `ADVUI_STACK_INDEXEDSTACK`, `ADVUI_EXPANDED_LAYOUTBUILDER`, `ADVUI_TABLE_CARD`, `ADVUI_BOTTOMSHEET`, `ADVUI_SLIVERS`

---

## skill_code: `ADVUI_LISTVIEW`
**skill_name:** ListView và danh sách cuộn · **skill:** `ADVANCED_WIDGETS`

### Khái niệm
`ListView` hiển thị danh sách item cuộn được theo chiều dọc (mặc định) hoặc ngang. Khi nội dung vượt quá vùng vẽ, một `Column` cố định sẽ báo overflow; `ListView` giải quyết bằng khả năng cuộn. Có các constructor: mặc định (children cố định), `ListView.builder` (`itemCount` + `itemBuilder`), `ListView.separated` (thêm divider giữa các dòng), và `ListView.custom` (dùng `childrenDelegate`).

### Dấu hiệu đạt yêu cầu
- Dùng `ListView.builder` với `itemCount` và `itemBuilder(context, index)` trả về widget theo index.
- Danh sách dài cuộn được, không có sọc overflow.
- Đặt `scrollDirection: Axis.horizontal` khi cần danh sách ngang.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Dùng `Column` cho danh sách dài → xuất hiện sọc overflow, test không tìm thấy nội dung cuộn.
- `itemCount` sai so với độ dài list → thiếu/thừa item khi test đếm.

### API/Widget chính
`ListView`, `ListView.builder`, `ListView.separated`, `ListView.custom`, `itemCount`, `itemBuilder`, `ScrollController`, `scrollDirection`, `Axis.horizontal`

### Từ khóa
ListView, builder, separated, itemBuilder, itemCount, scrollDirection, overflow, scroll

### Nguồn: Mastering Flutter, Ch.7, tr. 220–225

---

## skill_code: `ADVUI_GRIDVIEW`
**skill_name:** GridView và lưới item · **skill:** `ADVANCED_WIDGETS`

### Khái niệm
`GridView` hiển thị item theo cả chiều ngang lẫn dọc dưới dạng lưới. Lưới nhận một `gridDelegate` kiểu `SliverGridDelegate` mô tả cách bố trí hàng và cột. Các constructor gồm: mặc định, `GridView.builder`, `GridView.count` (số cột cố định trên trục cross-axis), `GridView.extend` (mỗi item có kích thước cross-axis tối đa), và `GridView.custom`.

### Dấu hiệu đạt yêu cầu
- Dùng `GridView.builder` với `itemCount`, `gridDelegate` và `itemBuilder`.
- Cấu hình `SliverGridDelegateWithMaxCrossAxisExtent` (`maxCrossAxisExtent`, `crossAxisSpacing`, `childAspectRatio`, `mainAxisSpacing`).
- Lưới tự giãn để lấp đầy vùng khi kích thước màn hình thay đổi.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Thiếu `gridDelegate` → lỗi compile, không dựng được lưới.
- Quên `shrinkWrap: true` khi lồng trong vùng có chiều cao không xác định → lỗi unbounded/overflow.
- Hot reload thay vì hot restart sau khi đổi dữ liệu `initState` → lưới không cập nhật.

### API/Widget chính
`GridView`, `GridView.builder`, `GridView.count`, `GridView.extend`, `GridView.custom`, `SliverGridDelegate`, `SliverGridDelegateWithMaxCrossAxisExtent`, `shrinkWrap`, `childAspectRatio`

### Từ khóa
GridView, builder, gridDelegate, maxCrossAxisExtent, childAspectRatio, shrinkWrap, lưới

### Nguồn: Mastering Flutter, Ch.7, tr. 227–230

---

## skill_code: `ADVUI_STACK_INDEXEDSTACK`
**skill_name:** Stack và IndexedStack · **skill:** `ADVANCED_WIDGETS`

### Khái niệm
`Stack` hiển thị các children chồng lên nhau, item đầu danh sách nằm dưới cùng. Dùng `Positioned` (top, left, bottom, right tính bằng pixel) hoặc `Align` (alignment như `topCenter`, `topLeft`, `bottomXXX`) để đặt vị trí item. `IndexedStack` giống Stack nhưng chỉ hiển thị một child tại một thời điểm theo `index`, hữu ích như widget phân trang khi đổi `index`.

### Dấu hiệu đạt yêu cầu
- Dùng `Stack` với `Align`/`Positioned` để chồng text lên ảnh.
- `IndexedStack` có `index` điều khiển child đang hiển thị và danh sách `children`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Đặt sai thứ tự children → widget bị che, test không thấy phần tử trên cùng.
- Không cập nhật `index` của `IndexedStack` → màn hình không đổi trang khi test tương tác.

### API/Widget chính
`Stack`, `Positioned`, `Align`, `Alignment`, `IndexedStack`, `index`, `children`

### Từ khóa
Stack, IndexedStack, Positioned, Align, Alignment, index, chồng widget, paging

### Nguồn: Mastering Flutter, Ch.7, tr. 225–226

---

## skill_code: `ADVUI_EXPANDED_LAYOUTBUILDER`
**skill_name:** Expanded và LayoutBuilder · **skill:** `ADVANCED_WIDGETS`

### Khái niệm
`Expanded` chiếm tối đa chiều rộng/cao mà `Row` hoặc `Column` cho phép, và chỉ hoạt động trong hai widget này. Bọc một `ListView` trong `Expanded` để khắc phục lỗi "Vertical viewport was given unbounded height". `LayoutBuilder` cung cấp `BoxConstraints` của parent qua `builder`, dựa trên min/max width/height để chọn layout khác nhau (vd `maxWidth > 600` cho tablet, ngược lại cho phone).

### Dấu hiệu đạt yêu cầu
- Bọc danh sách/widget vô hạn bằng `Expanded` trong `Row`/`Column`.
- Dùng `LayoutBuilder` đọc `constraints.maxWidth` để chọn layout responsive.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Không dùng `Expanded` → lỗi "unbounded height", màn hình trắng kèm lỗi console.
- Đặt `Expanded` ngoài `Row`/`Column` → lỗi runtime.

### API/Widget chính
`Expanded`, `LayoutBuilder`, `BoxConstraints`, `constraints.maxWidth`, `SizedBox`, `Row`, `Column`

### Từ khóa
Expanded, LayoutBuilder, BoxConstraints, maxWidth, unbounded height, responsive

### Nguồn: Mastering Flutter, Ch.7, tr. 222–227

---

## skill_code: `ADVUI_TABLE_CARD`
**skill_name:** Table và Card · **skill:** `ADVANCED_WIDGETS`

### Khái niệm
`Table` hiển thị dữ liệu theo hàng và cột; children là danh sách `TableRow`, mỗi ô có thể là widget thường hoặc `TableCell` (kiểm soát alignment). Khác với GridView, Table ép widget vừa kích thước cột; có thể đặt `columnWidths` (`IntrinsicColumnWidth`, `FlexColumnWidth`, `FixedColumnWidth`) và border. `Card` có viền bo tròn và độ nâng (elevation), giúp một vùng nổi bật; cấu hình `color`, `shadowColor`, `surfaceTintColor`, `elevation`, `shape`. Có `Card.filled` và `Card.outlined`.

### Dấu hiệu đạt yêu cầu
- `Table` với danh sách `TableRow`, số widget mỗi hàng khớp số cột.
- Dùng `columnWidths` và `TableBorder.all()` khi cần.
- `Card` bọc nội dung với elevation hoặc dùng `Card.filled`/`Card.outlined`.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Số widget trong `TableRow` không khớp số cột → lỗi dựng bảng.
- Quên `TableCell` khi cần căn chỉnh → ô không đúng alignment trong kết quả.

### API/Widget chính
`Table`, `TableRow`, `TableCell`, `TableBorder`, `columnWidths`, `IntrinsicColumnWidth`, `FlexColumnWidth`, `FixedColumnWidth`, `Card`, `Card.filled`, `Card.outlined`, `elevation`

### Từ khóa
Table, TableRow, TableCell, columnWidths, TableBorder, Card, elevation, outlined, filled

### Nguồn: Mastering Flutter, Ch.7, tr. 231–234

---

## skill_code: `ADVUI_BOTTOMSHEET`
**skill_name:** BottomSheets · **skill:** `ADVANCED_WIDGETS`

### Khái niệm
`BottomSheet` là vùng thông tin nằm ở đáy màn hình, gồm hai loại: persistent (giữ nguyên tại chỗ) và modal (buộc người dùng tương tác đến khi đóng). Sheet có thể có animation và drag handle. Dùng `showBottomSheet` (hoặc `showModalBottomSheet`) với `builder` trả về một widget; đổi nền qua `backgroundColor`.

### Dấu hiệu đạt yêu cầu
- Gọi `showModalBottomSheet(context:..., builder:...)` trả về widget nội dung.
- Phân biệt được persistent và modal bottom sheet.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Thiếu `context` hoặc `builder` → lỗi compile, sheet không hiện.
- Builder không trả widget hợp lệ → test không tìm thấy nội dung sheet.

### API/Widget chính
`BottomSheet`, `showBottomSheet`, `showModalBottomSheet`, `builder`, `backgroundColor`

### Từ khóa
BottomSheet, showModalBottomSheet, showBottomSheet, persistent, modal, builder

### Nguồn: Mastering Flutter, Ch.7, tr. 234–237

---

## skill_code: `ADVUI_SLIVERS`
**skill_name:** Slivers và CustomScrollView · **skill:** `ADVANCED_WIDGETS`

### Khái niệm
Slivers là các widget cuộn được, dùng trong danh sách slivers của một `CustomScrollView` (parent) qua tham số `slivers`. Các loại gồm: `SliverList`, `SliverGrid`, `SliverAppBar` (app bar đổi khi cuộn), `SliverToBoxAdapter` (chứa một box widget, chuyển widget thường thành sliver), và `SliverPadding`. `SliverList` dùng `SliverChildListDelegate` hoặc `SliverChildBuilderDelegate`.

### Dấu hiệu đạt yêu cầu
- Dùng `CustomScrollView` với danh sách `slivers`.
- Chuyển widget thường thành sliver bằng `SliverToBoxAdapter`.
- `SliverList` dùng đúng delegate (`SliverChildListDelegate`/`SliverChildBuilderDelegate`).

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Đưa widget không phải sliver vào `slivers` → lỗi "RenderViewport expected a child of type RenderSliver".
- Dùng `Expanded`/`ListView` trong danh sách slivers → lỗi runtime, màn hình không dựng.
- Sai số ngoặc khi lồng nhiều sliver → lỗi compile.

### API/Widget chính
`CustomScrollView`, `slivers`, `SliverList`, `SliverGrid`, `SliverAppBar`, `SliverToBoxAdapter`, `SliverPadding`, `SliverChildListDelegate`, `SliverChildBuilderDelegate`

### Từ khóa
Sliver, CustomScrollView, SliverList, SliverToBoxAdapter, SliverPadding, SliverAppBar, delegate

### Nguồn: Mastering Flutter, Ch.7, tr. 237–241
