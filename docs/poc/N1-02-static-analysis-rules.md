# N1-02 — Bộ luật phân tích tĩnh nền

## Phạm vi

`grader-base/analysis_options.yaml` là cấu hình mặc định dành cho mã trong
`lib/` của bài sinh viên. Danh sách chỉ có 10 luật để kết quả dễ giải thích và
không biến tiêu chí phân tích tĩnh thành kiểm tra định dạng hoặc sở thích code.
Các file sinh tự động `*.g.dart`, `*.freezed.dart`, thư mục build và
`.dart_tool` được loại trừ để không quy lỗi của code generator cho sinh viên.

## Danh sách luật

| Nhóm | Luật | Lý do chọn |
|---|---|---|
| Hợp đồng mã nguồn | `always_declare_return_types` | Hàm public và hàm nghiệp vụ có kiểu trả về rõ ràng. |
| Hợp đồng mã nguồn | `annotate_overrides` | Phân biệt rõ method override, giảm lỗi viết nhầm method mới. |
| Luồng điều khiển | `avoid_empty_else` | Phát hiện nhánh `else` rỗng hoặc TODO chưa hoàn thiện. |
| Luồng điều khiển | `empty_catches` | Không được nuốt exception mà không xử lý hoặc giải thích. |
| Luồng điều khiển | `unnecessary_statements` | Phát hiện biểu thức đứng riêng không tạo ra tác dụng. |
| Đúng đắn | `unrelated_type_equality_checks` | Ngăn so sánh hai kiểu không liên quan luôn cho kết quả sai. |
| Đúng đắn | `valid_regexps` | Bắt biểu thức chính quy sai trước khi ứng dụng chạy. |
| Flutter async | `use_build_context_synchronously` | Tránh dùng `BuildContext` đã mất hiệu lực sau `await`. |
| Tài nguyên | `cancel_subscriptions` | Nhắc hủy `StreamSubscription` do lớp sở hữu. |
| Tài nguyên | `close_sinks` | Nhắc đóng `Sink`/controller do lớp sở hữu. |

Không bật `prefer_const_constructors`, `avoid_print`, luật đặt tên hoặc độ dài
dòng vì chúng chủ yếu phản ánh phong cách và dễ tạo nhiều cảnh báo không liên
quan tới kết quả bài thi.

## Hợp đồng chạy

N1-05 sẽ gọi analyzer riêng trên mã sinh viên:

```text
dart analyze lib
```

Lint là dữ liệu đầu vào để tính tiêu chí, không được tự làm dừng behavior hoặc
unit test. N1-03 chịu trách nhiệm copy file này vào gốc `/app` của image và cho
phép từng đề mount đè một cấu hình riêng.

## Tiêu chí nghiệm thu

1. Analyzer nhận đủ 10 luật và không báo luật không tồn tại.
2. Mã sạch không phát sinh cảnh báo do file được generator tạo ra.
3. Fixture cố ý vi phạm tạo đúng cảnh báo ở các nhóm đã khai báo.
4. Cấu hình không thêm package hoặc plugin analyzer ngoài image hiện tại.

## Kết quả kiểm thử

Đã chạy bằng Dart/Flutter thật trong image `grading-base:latest`, với cấu hình
được mount vào đúng gốc project:

- Fixture sạch: analyzer thoát với mã `0`, không có warning hoặc lint.
- Fixture cố ý vi phạm: phát hiện đủ `10/10` luật đã chọn.
- File sinh tự động đặt trực tiếp trong `lib/` và trong thư mục con đều được
  loại trừ đúng cho cả `*.g.dart` và `*.freezed.dart`.
- Bốn fixture bài làm hiện có không phát sinh lint phong cách gây nhiễu; những
  warning còn lại là cảnh báo lõi hợp lệ như import hoặc phần tử không sử dụng.

Cấu hình này chỉ đánh giá chất lượng tĩnh của mã nguồn. Sai Widget Key, sai kết
quả CRUD, sai validation hoặc sai giao diện vẫn được đánh giá bởi bộ chấm Golden
và các kiểm tra hành vi tương ứng.
