# Engine testcase theo contract của từng đề

`TEMPLATE_CONTRACT_V1` tái sử dụng **cách kiểm tra**, không tái sử dụng một bộ đề cố định.
Engine này dành cho starter có sẵn public contract và các vị trí TODO để sinh viên hoàn thiện. Nó không yêu cầu `Widget Key` hoặc `grading_adapter.dart`.

## Quy trình tạo bộ chấm

1. Giảng viên tạo starter cho đề và công bố tên file, class, field, method, label/hint và nội dung nút cần giữ nguyên.
2. Trong màn hình **Tạo testcase**, chọn **Bộ testcase chấm theo khung template mẫu**.
3. Có thể mở **Thiết lập contract gợi ý cho đề** và nhập model, SQLite, repository cùng label UI một lần. Bước này không bắt buộc.
4. Chỉ kéo các mẫu kiểm tra cần thiết vào đề. Testcase mới tự nhận phần contract phù hợp; không có thao tác nạp toàn bộ một pack.
5. Mở **Cấu hình** của từng testcase để kiểm tra hoặc thay giá trị riêng.
6. Nếu thư viện chưa có biến thể cần dùng, bấm **Tạo testcase mới**, chọn runner được hỗ trợ và đặt schema mặc định. Không nhập Dart code tùy ý ở đây.
7. Lưu Draft để sinh `exam_test.dart`, `grader.dart` và `skills_matrix.json` riêng cho đề đó.

## Các mẫu dùng chung hiện có

| Mẫu | Tham số thay đổi theo đề |
|---|---|
| File có symbol | `sourcePath`, `symbols` |
| Model fields | `sourcePath`, `className`, `fields` dạng `uid:String,age:int` |
| Model mapping | file, class, tên hai method và danh sách cột |
| SQLite schema | file database, tên bảng và danh sách cột |
| Repository methods | file, class và danh sách method |
| Form fields | label/hint của từng ô nhập |
| Buttons | nội dung chữ của từng nút |
| Form action | label các ô, dữ liệu thử, nút bấm và nội dung phải xuất hiện |
| Visible text | các chuỗi phải hiển thị |
| Responsive | kích thước portrait và landscape |

Các danh sách được phân tách bằng dấu phẩy. `fieldLabels` và `inputValues` phải có cùng số phần tử và cùng thứ tự.

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
