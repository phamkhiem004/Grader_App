# Phụ lục Semantic Key cho đề PE PRM393 — đề nghị bổ sung vào đề thi

**Gửi: bộ môn / người ra đề PRM393.**
**Từ: nhóm làm hệ thống chấm tự động + sinh nhận xét.**
**Trạng thái: ĐỀ NGHỊ — cần bộ môn duyệt trước khi áp dụng cho kỳ thi.**

---

## 1. Vấn đề, nói ngắn

Hệ thống chấm mới **tìm từng thành phần giao diện bằng một cái "nhãn định danh"** gắn trong code
(gọi là *semantic key*). Không có nhãn, máy không biết ô nào là ô Email, nút nào là nút Xoá — nên
**không chấm được**.

Đề PE hiện hành **không yêu cầu sinh viên gắn nhãn**, và bản `README_STUDENT.md` trong starter còn
cam kết ngược lại:

> *"Testcase tìm control theo hành vi/semantics và **không ép sinh viên dùng một layout, Key hoặc thư
> viện UI cụ thể**."*

Đo trên 3 bài nộp thật (HE190012/13/14): **0 nhãn** trong toàn bộ `lib/` — đúng như đề đã hứa.

⇒ **Muốn dùng hệ thống chấm mới cho kỳ thi thật, đề phải bổ sung phụ lục này VÀ sửa câu cam kết
trên.** Chỉ làm một trong hai là vô ích.

---

## 2. Bộ môn được gì — bảng thật, không tô hồng

Áp dụng phụ lục này thì **60/100 điểm chấm được tự động và khách quan**, phần còn lại vẫn chấm tay.

| # | Tiêu chí rubric hiện hành | Điểm | Máy chấm được? |
|---|---|---|---|
| 1 | Giao diện đúng yêu cầu trang Quản lý người dùng | 20 | ⚠️ **một phần** — kiểm được *có đủ thành phần không*, không kiểm được *đẹp/đúng bố cục* |
| 2 | Dùng Riverpod, Riverpod generator | 10 | ❌ **không** — thuộc kiến trúc mã nguồn |
| 3 | Đáp ứng Responsive | 5 | ✅ có |
| 4 | Đúng MVVM | 10 | ❌ **không** — thuộc kiến trúc mã nguồn |
| 5 | Hiển thị được danh sách | 10 | ✅ có |
| 6 | Verify được dữ liệu (validate) | 5 | ✅ có |
| 7 | Thêm được người dùng | 10 | ✅ có |
| 8 | Sửa được người dùng | 10 | ✅ có |
| 9 | Xoá người dùng | 10 | ✅ có |
| 10 | Điều hướng + truyền thông tin sang User Detail | 10 | ✅ có |
| | **Tự động hoàn toàn** | **60** | |
| | **Chấm tay hoặc hỗ trợ một phần** | **40** | |

⚠️ **Điều quan trọng nhất bộ môn cần biết:** máy chấm **hoàn toàn theo hành vi hiển thị**, không đọc
mã nguồn sinh viên. Nên **20 điểm MVVM + Riverpod là thứ máy KHÔNG BAO GIỜ kiểm được**, kể cả khi
sinh viên gắn đủ nhãn. Bài đạt 100% phần máy chấm **không** đồng nghĩa "đúng kiến trúc". Bot sinh
nhận xét cũng bị cấm phát biểu về hai tiêu chí đó — nó chỉ được **liệt kê yêu cầu của đề**, không
được khẳng định sinh viên đã đáp ứng.

---

## 3. Chi phí cho sinh viên — nhỏ và không đụng kiến trúc

Gắn nhãn là **thêm một tham số `key:`** vào widget đã có. Không đổi layout, không đổi thư viện,
không đổi kiến trúc, không thêm file:

```dart
TextFormField(
  key: const ValueKey('field.email'),      // <-- chỉ thêm dòng này
  decoration: const InputDecoration(labelText: 'Email'),
)
```

Toàn bài khoảng **20–25 dòng** như trên. Ước lượng 10–15 phút cho sinh viên đã làm xong bài.

**Kèm theo phải cấp cho sinh viên:** bảng nhãn ở mục 4 (đưa nguyên vào đề) — sinh viên **không được
tự đặt tên nhãn**, vì máy tìm theo đúng chuỗi.

---

## 4. Bảng nhãn bắt buộc — đưa nguyên khối này vào đề thi

> Quy tắc chung: nhãn viết **thường**, dạng `nhóm.tên`, gắn bằng `key: const ValueKey('...')`.
> Chỗ nào có `<id>` thì thay bằng **id của người dùng trong CSDL** (ví dụ người dùng id 3 →
> `item.3`, `action.edit.3`, `action.delete.3`).

### 4.1 Màn hình

| Nhãn | Gắn vào |
|---|---|
| `screen.home` | Widget bọc ngoài cùng của màn Quản lý người dùng |
| `screen.detail` | Widget bọc ngoài cùng của màn User Detail |
| `text.title` | Chữ tiêu đề của màn Quản lý người dùng |

### 4.2 Biểu mẫu nhập (phần trên màn Home)

| Nhãn | Gắn vào |
|---|---|
| `field.fullname` | Ô nhập Họ tên |
| `field.email` | Ô nhập Email |
| `field.avatar` | Ô chọn/nhập Avatar |
| `error.fullname` | Chữ báo lỗi hiển thị dưới ô Họ tên |
| `error.email` | Chữ báo lỗi hiển thị dưới ô Email |
| `error.avatar` | Chữ báo lỗi hiển thị dưới ô Avatar |
| `action.save` | Nút **Add user** — cũng chính nút này khi đổi thành **Update user** |
| `message.editing` | Chữ/nhãn báo "đang sửa" hiện lên khi bấm Edit *(nếu đề yêu cầu hiển thị)* |

### 4.3 Danh sách và từng dòng

| Nhãn | Gắn vào |
|---|---|
| `list.items` | Widget danh sách (ListView/GridView) |
| `item.<id>` | Widget bọc **một dòng** người dùng |
| `action.edit.<id>` | Nút/icon Edit của dòng đó |
| `action.delete.<id>` | Nút/icon Delete của dòng đó |
| `state.empty` | Chữ/vùng hiển thị khi danh sách trống *(nếu đề yêu cầu)* |

### 4.4 Hộp thoại xác nhận xoá

| Nhãn | Gắn vào |
|---|---|
| `dialog.delete` | Hộp thoại xác nhận |
| `action.delete.confirm` | Nút Đồng ý/Xoá trong hộp thoại |
| `action.delete.cancel` | Nút Huỷ trong hộp thoại |

### 4.5 Màn User Detail

| Nhãn | Gắn vào |
|---|---|
| `text.detail.fullname` | Chữ hiển thị Họ tên |
| `text.detail.email` | Chữ hiển thị Email |
| `box.avatar` | Khung ảnh đại diện |
| `action.back` | Nút quay lại |

> **Chỉ dùng đúng các nhóm sau**, hệ thống từ chối nhãn ngoài danh sách:
> `action` `box` `dialog` `error` `field` `icon` `item` `list` `message` `padding` `screen` `state` `text`

---

## 5. Câu phải sửa trong starter

Trong `README_STUDENT.md` của project nền, câu hiện tại **phủ định trực tiếp** phụ lục này:

| | |
|---|---|
| **Hiện tại** | *"Testcase tìm control theo hành vi/semantics và không ép sinh viên dùng một layout, **Key** hoặc thư viện UI cụ thể."* |
| **Đề nghị thay bằng** | *"Testcase tìm control theo **semantic key** — sinh viên **bắt buộc** gắn đúng các nhãn trong Phụ lục Semantic Key của đề. Ngoài nhãn, testcase **không** ép layout, bố cục hay thư viện UI cụ thể."* |

Nên bổ sung thêm một dòng vào mục *"Việc sinh viên phải tự hoàn thành"*:

> *"Gắn semantic key theo Phụ lục của đề (khoảng 20 nhãn) — thiếu nhãn thì phần chấm tự động của
> tiêu chí liên quan không tính điểm."*

---

## 6. Rủi ro và cách chặn

| Rủi ro | Mức | Cách chặn |
|---|---|---|
| Sinh viên gõ sai/thiếu nhãn ⇒ mất điểm oan phần tự động | **cao** | (a) In bảng nhãn ngay trong đề, không để phụ lục rời; (b) phát **bài mẫu tham chiếu** đã gắn đủ nhãn cho sinh viên xem trước kỳ thi; (c) kỳ đầu áp dụng, **chấm tay đối chiếu** phần tự động trước khi công bố điểm |
| Sinh viên gắn nhãn nhưng đặt sai chỗ (ví dụ gắn `field.email` vào widget bọc ngoài) | trung bình | Đề ghi rõ "gắn vào chính widget đó, không gắn vào widget bọc" — bảng mục 4 đã ghi cột *"Gắn vào"* |
| Máy chấm sai ⇒ oan cho sinh viên | trung bình | Hệ thống đã có 97 test tự kiểm và bộ luật nghiệm thu; nhưng **kỳ đầu vẫn nên chấm tay đối chiếu** |
| Bộ môn tưởng 100% điểm là tự động | **cao** | Mục 2 nói rõ: **60 tự động / 40 chấm tay**, và MVVM + Riverpod máy không kiểm được |

### Bài mẫu tham chiếu đã có sẵn
Trong hệ thống có một app User Manager hoàn chỉnh **đã gắn đủ nhãn**, dùng làm chuẩn đối chiếu:
`Grader_App/fixtures/result-json-v2/submissions/high/`. Bộ môn có thể lấy nguyên phần gắn nhãn của
bài này làm ví dụ phát cho sinh viên.

---

## 7. Đề nghị lộ trình

1. **Bộ môn duyệt** bảng nhãn mục 4 (sửa tên nhãn tuỳ ý — miễn giữ đúng 13 nhóm ở cuối mục 4).
2. Bổ sung phụ lục vào đề + sửa câu trong `README_STUDENT.md` (mục 5).
3. Phát bài mẫu tham chiếu cho sinh viên xem trước kỳ thi.
4. **Kỳ đầu: chấm tay đối chiếu** với kết quả máy, không công bố thẳng.
5. Đối chiếu khớp thì kỳ sau dùng kết quả máy làm chính, chấm tay chỉ 40 điểm còn lại.

---

## 8. Nếu bộ môn KHÔNG duyệt

Hoàn toàn hợp lệ — nhưng cần biết hệ quả: hệ thống chấm mới **không dùng được cho đề này**, và bài
thi vẫn chấm bằng bộ testcase cũ (`PRM393_layered_testcase_v9`) như hiện nay. Bộ cũ tìm control
theo văn bản/nhãn trợ năng nên không cần semantic key, đổi lại nó **không** sinh được `result.json`
đời mới — tức **không có nhận xét tự động cho sinh viên**.

Nói gọn: **nhận xét tự động cho sinh viên đang phụ thuộc vào quyết định ở tài liệu này.**
