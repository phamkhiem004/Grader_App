# Chấm kết hợp starter TODO và semantic Key

`STARTER_KEY_HYBRID_V1` là hướng chấm khuyến nghị khi muốn sinh viên tự thiết kế giao diện
nhưng vẫn cần kiểm tra Logic và SQLite theo một hợp đồng thống nhất. Chế độ này không dùng
`grading_adapter.dart`.

## Phân chia trách nhiệm

| Phần cần chấm | Contract dùng để định vị | Sinh viên được tự do gì? |
|---|---|---|
| Model, mapping, Repository, SQLite | Đường dẫn file, tên class, field, method, bảng và cột đã có trong starter | Hoàn thiện các vị trí TODO và được chọn cách viết thân hàm |
| Hàm nghiệp vụ/CRUD thực thi | Hàm top-level công khai đã có trong starter; một testcase có thể gọi một hàm hoặc một chuỗi hàm | Hoàn thiện TODO trong chính API nghiệp vụ, không viết API riêng cho máy chấm |
| Widget, validation, navigation, CRUD qua UI, responsive | `Key('...')` được công bố trong đề/starter | Tự xây widget tree, bố cục, theme và state management |
| Điểm và dữ liệu thử | `skills_matrix.json` của từng đề | Không phải contract sinh viên sửa |

Key chỉ là bộ chọn để máy chấm tìm đúng widget. Key không chứng minh logic đúng: testcase hành vi
vẫn phải nhập dữ liệu, bấm action rồi kiểm tra trạng thái hoặc kết quả mới. Ngược lại, kiểm tra
source của starter chỉ xác nhận cấu trúc contract; nó không thay thế testcase chạy CRUD thật.

### Danh mục Key của starter

Mỗi Draft có một `key_contract` dùng cho màn hình biên tập:

```json
{
  "source_path": "lib/grading/app_keys.dart",
  "class_name": "AppKeys",
  "keys": [
    {
      "symbol": "uidField",
      "value": "person.form.uid",
      "group": "Form",
      "description": "Ô nhập UID"
    }
  ]
}
```

`symbol` là tên constant trong starter; `value` là chuỗi semantic Key mà máy chấm dùng. Danh
mục được hiện cạnh ba file kỹ thuật và làm nguồn chọn cho tham số Key/setup step. Nó được lưu
trong Draft nhưng không xuất thành file thứ tư và không làm dài `skills_matrix.json`. Key được gõ
trực tiếp trong testcase cũng xuất hiện với trạng thái “Đang dùng”, giúp phát hiện Key chưa khai báo
trong starter. Nút **Nhập Key đang dùng** đưa toàn bộ các Key này vào contract và tự gợi ý tên
constant; giảng viên chỉ cần rà lại tên, nhóm và mô tả.

## Quy trình dành cho giảng viên

1. Tạo starter có các file và public symbol bắt buộc; để TODO tại thân hàm sinh viên cần làm.
2. Khai báo contract Logic/SQLite trên màn hình tạo testcase: file, class, field, method, bảng và cột.
3. Công bố semantic Key cho các điểm UI cần chấm, ví dụ `person.uid`, `person.add` và `person.list`.
4. Chỉ thêm từng testcase đề thực sự cần. Chọn mẫu **Starter TODO** cho Logic/SQLite và mẫu
   **Semantic Key** cho Widget/Behavior.
5. Cấu hình tham số, dữ liệu đầu vào và điều kiện pass riêng của từng testcase.
6. Lưu Draft và xem lại ba file phát hành: `exam_test.dart`, `skills_matrix.json`, `grader.dart`.
7. Trước khi dùng chính thức, chạy bộ chấm với ít nhất một bài đúng, một bài sai Logic và một bài
   gắn Key đúng nhưng hành vi sai để kiểm tra khả năng chống pass giả.

## Quy tắc thiết kế starter

- Public contract chỉ khóa phần máy chấm cần gọi hoặc kiểm tra; không khóa toàn bộ kiến trúc bài làm.
- Một Key có một ý nghĩa nghiệp vụ và xuất hiện duy nhất trên route hiện tại.
- Key cho item động nên có quy tắc ổn định theo ID nghiệp vụ, không theo vị trí danh sách.
- Không yêu cầu sinh viên tự viết file trung gian dành riêng cho máy chấm.
- Backend từ chối mọi `functionPath`/`sourcePath` trỏ tới `grading_adapter.dart` trong engine hybrid.
- SQLite headless phải dùng cách khởi tạo đã được starter cung cấp và tương thích môi trường test.
- Không chờ `pumpAndSettle()` vô hạn; runner dùng timeout hữu hạn và báo lỗi gốc.

## Khả năng tái sử dụng và giới hạn hiện tại

Thư viện tái sử dụng **runner**, không tái sử dụng nguyên bộ testcase của một đề. Đề Person,
Product hoặc Todo có thể dùng cùng runner nhưng phải thay contract, Key, dữ liệu thử và expected.

Các runner starter kiểm tra được symbol, source wiring, model fields, copy/mapping, SQLite schema
và repository methods. `DIRECT_FUNCTION` gọi một hàm top-level; `STARTER_CALL_SEQUENCE` gọi tuần
tự nhiều hàm trong cùng testcase nên dùng được cho chuỗi reset/add/read/update/remove. Cả hai đều
import code nghiệp vụ của starter trực tiếp và không dùng adapter. Vì Dart không có reflection
runtime tổng quát, engine chưa tự khởi tạo được một class Repository tùy ý nếu starter không công
bố API gọi được. Persistence qua hai process cũng chỉ nên bật khi starter có cơ chế database riêng
cho từng testcase và runner reload tương ứng.

## Catalog tái chế từ V9 và V8

Thư viện hybrid hiện gồm 89 blueprint, trong đó 52 alias tái sử dụng được bóc từ các mục tiêu kiểm
tra của V9/V8. Alias kế thừa runner và schema của blueprint nền, chỉ thay tên, mục đích và tham số
mặc định. Vì vậy khi runner được sửa lỗi, mọi alias cùng nhận bản sửa thay vì sao chép thêm một khối
Dart cố định.

Catalog bao phủ contract, model, mapping, SQLite schema, repository/state API, hàm hợp lệ/sai/biên,
chuỗi CRUD, widget hierarchy, validation, list, add/edit/delete/detail và responsive phone/tablet.
Những mục phụ thuộc adapter của V8 không được mang sang. Golden và persistence qua process chưa
được quảng cáo là “sẵn sàng” khi engine chưa có artifact/runner cách ly tương ứng.

Artifact chấm vẫn chỉ gồm ba file. `common_testcase_engine.dart` là module nguồn nội bộ của
backend; khi lưu Draft, backend chỉ ghép các runner đã chọn vào `exam_test.dart` độc lập.
