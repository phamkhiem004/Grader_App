# Hợp đồng khâu soạn đề — những gì `result.json` BẮT BUỘC cần

**Gửi: nhóm phát triển Grader** (đang sắp xếp lại workflow tạo đề / tạo testcase phía giảng viên).
**Từ: nhóm làm `result.json` + bot nhận xét NLP.**

---

## 0. Đọc gì trước, và vì sao có tài liệu này

Bên tôi phụ trách **chất lượng file kết quả sau chấm** (`result.json`) — đầu vào **duy nhất** của bot
sinh nhận xét cho sinh viên. Trong lúc các bạn sửa workflow, chúng tôi đã thêm một số thứ vào khâu
soạn đề, vì **không có chúng thì `result.json` không đủ dữ liệu để sinh nhận xét đúng**.

Tài liệu này nói ba việc:

1. **Ba tính năng BẮT BUỘC phải còn** trong workflow mới của các bạn — kèm cơ chế chính xác (mục 2–4).
2. **Một khiếm khuyết đang chạy** mà chúng tôi đo được nhưng KHÔNG tự sửa vì nó thuộc phần các bạn
   đang thiết kế lại (mục 5). Đây là phần cần các bạn quyết.
3. **Những chỗ chúng tôi đã đụng vào code của các bạn** — liệt kê đủ, kèm mã commit, để các bạn giữ,
   sửa hay gỡ tuỳ ý (mục 6).

> **Nguyên tắc chúng tôi tự đặt từ nay:** khi chất lượng `result.json` đòi một thay đổi ở khâu soạn
> đề, chúng tôi **đặc tả và bàn giao**, không tự thi công. Tài liệu này là lần bàn giao đầu tiên.

**Bối cảnh vận hành để hiểu vì sao các yêu cầu dưới đây gắt:** nhận xét được sinh **hàng loạt** cho
toàn bộ bài nộp rồi gửi qua mail; **chỉ bài bị bot gắn cờ mới có người xem lại**. Một chữ sai không
dừng ở một sinh viên — nó tới hàng trăm người. Vì thế mọi thứ dưới đây đều là "chặn ở khâu nhập"
chứ không phải "sửa ở khâu xuất".

---

## 1. Ba thứ BẮT BUỘC — bảng tóm tắt

| # | Tính năng ở khâu soạn đề | Đổ vào trường nào của `result.json` | Không có thì sao |
|---|---|---|---|
| 1 | **Ô nhập "Yêu cầu của đề"** | `exam.requirements[]` | Bot **im lặng hoàn toàn** về yêu cầu đề — sinh viên không biết đề đòi gì |
| 2 | **Gán nhãn nhóm chức năng** (khác với gộp testcase) | `test_cases[].rubric` + `rubric_label` | Nhận xét không gom được theo chức năng, rơi về 3 nhãn kỹ thuật thô `LOGIC`/`WIDGET`/`BEHAVIOR` |
| 3 | **Ngữ pháp khoá chấm** | `expected`, `actual`, `name` | Khoá nội bộ (`action.delete.1`) lọt vào chữ gửi sinh viên |

Chi tiết từng cái ở dưới. Cách hiện thực là quyền của các bạn — phần **bắt buộc** là *hợp đồng dữ liệu*,
không phải mã nguồn cụ thể.

---

## 2. Yêu cầu của đề → `exam.requirements`

### Giảng viên thấy gì
Một ô văn bản nhiều dòng, **bắt buộc**, khi soạn đề. **Mỗi dòng là một yêu cầu.**

### Hợp đồng dữ liệu

```jsonc
"exam": {
  "code": "PE_01", "title": "...", "total_score": 10,
  "requirements": [
    "Xây ứng dụng quản lý người dùng: xem danh sách, thêm, sửa, xoá.",
    "Xoá người dùng phải có hộp thoại xác nhận trước khi xoá."
  ]
}
```

| Luật | Chi tiết |
|---|---|
| **Y NGUYÊN VĂN** | Không cắt, không đánh số, không chuẩn hoá, không sửa chính tả. Chữ này tới sinh viên **không đi qua mô hình ngôn ngữ** |
| Tách dòng | Mỗi dòng một phần tử. Chỉ bỏ `\r` cuối dòng và **dòng trắng**. Hệ quả: phần tử **không bao giờ rỗng, không bao giờ chứa ký tự xuống dòng** — bên đọc dựa vào điều này |
| Trần | **≤ 4000 ký tự và ≤ 40 dòng**, chặn **ở khâu nhập** (giảng viên thấy lỗi ngay). **Không được cắt lúc kết xuất** — cắt là phá "y nguyên văn" |
| Vì sao có trần | Chuỗi này được chép nguyên văn vào `result.json` của **từng bài**; một đợt 600–750 bài mà dán cả đề 20 KB là ~15 MB lặp vô nghĩa |
| Bắt buộc | Đề mới **không lưu được** nếu bỏ trống |
| `[]` nghĩa là gì | **Chỉ** có nghĩa *"đề tạo trước khi có tính năng này"*. Không bao giờ có nghĩa *"giảng viên cố ý để trống"* |

### Ràng buộc quan trọng nhất
Grader **không kiểm nội dung** trường này — đây là trường **duy nhất** trong `result.json` như vậy.
*"Y nguyên văn"* và *"Grader kiểm duyệt chữ của giảng viên"* loại trừ nhau. Nên **đừng thêm bộ lọc**
cho nó; đổi lại, phía chúng tôi không bao giờ đưa nó vào kho từ vựng hợp lệ của bot.

### Hiện thực hôm nay (tham khảo)
Cột `exams.requirements` (LONGTEXT) · tách ở `BatchGradingService.splitRequirements` (**nguồn tách
duy nhất**) · chặn ở `TestcaseTemplateService.validateRequirements` · bù `[]` cho dữ liệu cũ ở
`ResultController.normalizeResultNode`.
Test khoá: `ExamRequirementsTest` (8 test, ghim cả hai con số trần).

---

## 3. Gán nhãn nhóm chức năng ≠ gộp testcase

Đây là chỗ dễ hiểu nhầm nhất, nên nói kỹ. **Có HAI thao tác khác nhau**, đừng gộp làm một:

| | **Gán nhãn nhóm chức năng** | **Gộp thành testcase lớn** |
|---|---|---|
| Nghĩa | Metadata thuần: "mấy testcase này cùng phục vụ chức năng *Thêm người dùng*" | N testcase chạy chung, **một phần hỏng là cả cụm hỏng** |
| Ảnh hưởng điểm | **Không đụng gì** — từng testcase vẫn chấm và tính điểm riêng | All-or-nothing trên **tổng** điểm các con |
| Số thành viên | ≥ 1 | ≥ 2 |
| Trong `result.json` | N dòng `test_cases` độc lập, cùng `rubric`/`rubric_label` | **1** dòng `test_cases` duy nhất |

### Vì sao bên tôi cần cái thứ nhất
Bot gom nhận xét theo **chức năng**: *"phần Xoá người dùng chưa đạt"* thay vì *"kỹ năng setState chưa
đạt"*. Không có nhãn thì `rubric` rơi về `LOGIC`/`WIDGET`/`BEHAVIOR` — nhãn theo *bản chất kỹ thuật*,
sinh viên không hiểu bài theo trục đó.

### Hợp đồng dữ liệu
```jsonc
// nhãn: 2 testcase độc lập, cùng rubric
{ "test_id": "TC_TITLE", "rubric": "PE_01_rubric_01", "rubric_label": "Xem danh sách người dùng", "max_score": 15 }
{ "test_id": "TC_LIST",  "rubric": "PE_01_rubric_01", "rubric_label": "Xem danh sách người dùng", "max_score": 15 }
// gộp: 1 dòng duy nhất, điểm = tổng các con
{ "test_id": "PE_01_group_01", "rubric": "PE_01_group_01", "rubric_label": "Thêm người dùng hợp lệ", "max_score": 40 }
```

| Luật | Chi tiết |
|---|---|
| `rubric` | **MÃ máy sinh**, không hiển thị cho sinh viên |
| `rubric_label` | **Chữ giảng viên gõ**. `null` khi chưa đặt tên. Bên đọc dùng cái này, không parse mã |
| Không nhóm | `rubric` rơi về `testcase_group` (`LOGIC`/`WIDGET`/`BEHAVIOR`), `rubric_label = null` — hợp lệ nhưng thô |
| Một nhóm | chỉ được mang **một** nghĩa; không trộn nửa nhãn nửa gộp |
| Cụm gộp — bất biến 1 | `expected` phải mô tả **trọn hành vi cả cụm**, không phải câu của một con |
| Cụm gộp — bất biến 2 | `max_score` = **tổng** weight các con |
| Trần số con của cụm gộp | **CỐ Ý KHÔNG CÓ.** Đã đo ngân sách phía bot: cụm 8 con chiếm 11% ngân sách, không cần chặn. Đừng thêm trần |

### ⚠️ Điều giảng viên phải được cảnh báo trên giao diện
Gộp là **all-or-nothing thật**: sinh viên làm đúng phần lớn con trong cụm vẫn mất trọn điểm cụm, và
bot sẽ nói "chưa đạt" cả cụm, **không làm mềm được** vì không có dữ liệu con. Người ra đề cần biết
hệ quả này **ngay lúc chọn**, nếu không họ sẽ gộp vì tưởng nó chỉ là cách sắp xếp cho gọn.

### Hiện thực hôm nay (tham khảo)
Trường `group_mode` (`"label"` | `"merge"`) trong cấu hình testcase; thiếu field ⇒ hiểu là `"merge"`
để dữ liệu cũ không đổi nghĩa. Test khoá: `GroupModeAndKeyGrammarTest` (8 test, gồm cả hai bất biến
trên đo trực tiếp trên dữ liệu phát hành).

---

## 4. Ngữ pháp khoá chấm

### Vấn đề đã xảy ra thật
Engine chấm tìm widget qua **semantic key** (`action.delete.1`, `field.name`). Những khoá này từng
đi thẳng vào câu `expected` gửi sinh viên: *"Bấm `action.delete.1` phải mở `dialog.delete`"*. Sinh
viên đọc mã nội bộ và không hiểu gì; lưới phía bot **không bắt được** vì chuỗi đó tự nằm trong kho
từ vựng hợp lệ.

### Hợp đồng
Khoá hợp lệ khai ở **`common-key-grammar.json`** (đang có bản trong `grader/src/main/resources/`):

```
hình dạng : ^[a-z][a-z0-9-]*(\.[a-z0-9-]+)+$          (toàn chữ thường)
nhóm hợp lệ: action box dialog error field icon item list message padding screen state text
ví dụ      : field.name · action.save · dialog.delete · item.1
```

| Luật | Chi tiết |
|---|---|
| Chặn ở đâu | **Khâu nhập** — từ chối lưu đề, báo lỗi nêu rõ khoá sai + liệt kê nhóm hợp lệ |
| Vì sao không chặn ở khâu xuất | Khoá giáo viên tự đặt là chuỗi tới sinh viên mà **không bên nào bảo đảm**; bên đọc chỉ đỡ được bằng cờ "đáng ngờ", tức phụ thuộc có người mở bài ra xem hay không |
| Phạm vi | Chỉ đề soạn mới. Đề đã publish trước đó giữ nguyên |
| Nếu mở rộng | Thêm nhóm mới thì **phải báo bên tôi** — lưới của bot pin theo file này |

### Đánh đổi các bạn cần cân
Giảng viên **mất tự do đặt khoá** (ví dụ `nut.luu` bị từ chối). Chủ đồ án đã duyệt đánh đổi này trong
ngữ cảnh phục vụ bot; nếu workflow mới của các bạn cần khác, **cứ nói** — chúng tôi cần *khoá không
lọt ra chữ cho sinh viên*, còn cưỡng chế ở đâu là chuyện có thể bàn.

### Hiện thực hôm nay (tham khảo)
`TestcaseTemplateService.validateKeyGrammar` · test khoá `KeyGrammarContractTest` (pin file công bố
BẰNG nguồn thật, lệch là đỏ) + `GroupModeAndKeyGrammarTest`.

---

## 5. ⛔ KHIẾM KHUYẾT ĐANG CHẠY — cần các bạn quyết

**Triệu chứng: mọi bài do khảo thí chấm đều mất "Yêu cầu của đề".**

> ### 🔴 Vì sao chúng tôi xin xếp việc này ƯU TIÊN CAO
>
> Đây **không phải** một trường JSON bị rỗng. Đây là **một tính năng đã làm xong ở CẢ HAI phía mà
> không bao giờ tới được người dùng cuối** — chỉ vì đề đi qua ZIP giữa hai máy.
>
> Cụ thể thứ bị mất: bot có một khối *"Đề bài yêu cầu:"* in nguyên văn yêu cầu của giảng viên. Khối
> đó sinh ra để lấp đúng một lỗ — với **bài gần hoàn hảo**, bot gần như không có gì hợp lệ để nói:
> không được suy diễn về mã nguồn sinh viên, không được gợi ý nâng cao (đã bỏ vì hay bịa). Thứ duy
> nhất được phép nói là *đề đã yêu cầu những gì*, bằng **chữ của chính giảng viên**.
>
> Mất `requirements` ⇒ **bài 10 điểm nhận về một lời khen chung chung.** Đó là bài của sinh viên
> làm tốt nhất lớp, và cũng là nhận xét dễ bị đem ra so sánh nhất.
>
> Hôm nay trên bài thi thật, khối đó **chưa xuất hiện lần nào**.

### Đo được (chạy thật trên stack, không phải đọc code)

Luồng thật: giảng viên soạn đề ở **máy A** → tải ZIP → khảo thí ở **máy B** vào *Cấu hình đề thi*
tạo mã đề bằng ZIP đó → chấm bài.

| | Đề soạn tại chỗ (máy A) | Đề nhập bằng ZIP (máy B) |
|---|---|---|
| Điểm | 6.0 | 6.0 ✅ |
| `rubric` / `rubric_label` | đủ | đủ ✅ |
| `expected` sạch khoá | ✅ | ✅ |
| **`exam.requirements`** | **4 yêu cầu** | **0 — MẤT SẠCH** ⛔ |

### Nguyên nhân
* ZIP tải về chỉ đóng gói **3 file**: `exam_test.dart`, `grader.dart`, `skills_matrix.json`.
* `testcase-config.json` **không** vào ZIP — và bản thân nó cũng **chưa** chứa `requirements`.
* Đường nhập ZIP bỏ qua 4 cột DB: `requirements`, `testcase_config_json`, `created_by`,
  `allowed_packages`.

### Hai hệ quả
1. `exam.requirements = []` trên **mọi** bài khảo thí chấm ⇒ bot im lặng về yêu cầu đề. Tính năng ở
   mục 2 **chết trong luồng thật**.
2. `refreshCommonEngine` luôn bỏ qua đề nhập bằng ZIP (nó đọc `engine_type` từ cột đang null) ⇒
   **"Chấm lại đề" không bao giờ nâng được engine chấm** cho những đề này.

### Gợi ý phương án (các bạn quyết, chúng tôi không tự sửa)
1. Thêm `requirements` vào `testcase-config.json` khi lưu/publish.
2. ZIP tải về gồm **4 file** (thêm `testcase-config.json`).
3. Khi nhập: thấy `testcase-config.json` thì phục hồi `requirements` + `testcase_config_json` +
   version/status vào DB.
4. **Cố ý không phục hồi `created_by`** — để khảo thí vẫn không sửa được đề, nhưng
   `refreshCommonEngine` chạy được.
5. ZIP cũ 3 file vẫn nhập được như hôm nay.

> Ghi chú: `testcase-config.json` hiện **được ghi ra đĩa nhưng không code nào đọc lại** — nguồn đọc
> là cột DB. Nếu các bạn thiết kế lại, đây là chỗ đáng dọn.

### Một hệ quả cho hợp đồng dữ liệu, chúng tôi đã tự xử
Vì khiếm khuyết này, `[]` hiện **có hai nghĩa**: "đề cũ" *hoặc* "đề mới nhưng đi qua ZIP". Đã đính
chính hợp đồng và báo phía bot đừng suy `[]` = "đề cũ" nữa (2026-08-14). Phía bot đã soát: không có
chỗ nào suy như vậy, và họ thêm một test khoá để phiên sau **không dựng lại** nhánh đó.

### 📣 Xin báo lại chúng tôi khi sửa xong
Khi đường nhập đề chở được `requirements`, xin báo — chúng tôi cần **mốc** đó để nói với phía bot
*"từ đây bài thi thật có khối yêu cầu"*, và để `[]` quay lại **một** nghĩa. Trước mốc đó, tài liệu
bàn giao của phía bot phải ghi tính năng này là *"có, nhưng chết trong luồng hai máy"*.

---

## 6. Những chỗ chúng tôi đã đụng vào code của các bạn

Làm trong lúc các bạn refactor, nên **báo đủ để các bạn quyết giữ / sửa / gỡ**. Nhánh `chien1`.

| Commit | Đụng gì | Ghi chú cho các bạn |
|---|---|---|
| `a9c7aa5` | **P6a** — cột `requirements`, chặn nhập, tách dòng, bù `[]` đường đọc | Backend + hợp đồng. Mục 2 |
| `eff5c6f` | **P6b** — ô nhập "Yêu cầu của đề" + bộ đếm trên trang *Tạo testcase* | **UI của các bạn.** Nếu workflow mới đổi màn này, giữ lại *hành vi* ở mục 2 là đủ |
| `f133595` | **`group_mode`** (nhãn/gộp) + **cưỡng chế ngữ pháp khoá** | ⚠️ Thêm khái niệm mới vào màn soạn đề (2 nút, 2 modal) **và đổi hành vi lưu đề** — xem cảnh báo dưới |
| `7c54233` | Test pin `common-key-grammar.json` | Chỉ test, không đổi hành vi |
| `6f3d5dc` | `targetType` vào `parameters_schema` của `WIDGET_VISIBLE`/`BUTTON_ACTION`; sửa race trùng `instance_id` ở FE | Bug thuần phía các bạn, chúng tôi vấp phải nên sửa luôn — chi tiết dưới |

### ⚠️ Hai chỗ đổi hành vi, cần các bạn biết trước khi merge

1. **Cưỡng chế ngữ pháp khoá chặn lưu đề.** Đề dùng khoá kiểu `nut.luu` trước đây lưu được, **nay bị
   từ chối**. Đây là thay đổi hành vi sản phẩm — nếu không hợp workflow mới, cứ gỡ và báo chúng tôi
   để tìm cách khác.
2. **`group_mode` thêm một khái niệm vào màn soạn đề.** Nếu các bạn đang thiết kế lại đúng màn đó,
   hai thiết kế sẽ chồng nhau — nên xem mục 3 như *đặc tả cần đạt*, còn giao diện thì làm theo ý các bạn.

### Ba lỗi phía các bạn chúng tôi phát hiện khi đo (đã sửa, không phải bàn giao)

| Lỗi | Hệ quả nếu để nguyên |
|---|---|
| `WIDGET_VISIBLE`/`BUTTON_ACTION` thiếu `targetType` trong `parameters_schema`, dù engine và validator **đều đã hỗ trợ** | Nhánh validate là code không bao giờ chạy; đề khai `targetType` bị từ chối oan |
| Bấm "Thêm vào đề" hai lần trong cùng một tick ⇒ hai testcase **trùng `instance_id`** | Trùng id hỏng im lặng: tick một ô thì cả hai cùng tick, sửa/xoá một cái trúng cả hai; giáo viên chỉ biết lúc bấm Lưu |
| Tên nhóm mặc định đếm **số testcase có nhóm** thay vì **số nhóm** | 3 testcase trong 1 nhóm ⇒ tên gợi ý "Nhóm kiểm tra 04" |

---

## 7. Cách tự kiểm sau khi các bạn đổi workflow

Bộ test backend hiện có **97 test**, trong đó các lớp sau khoá đúng những gì tài liệu này mô tả —
**đừng xoá chúng, chúng đỏ là hợp đồng vỡ**:

| Lớp test | Khoá điều gì |
|---|---|
| `ExamRequirementsTest` | Tách dòng, trần 4000/40, `[]` nghĩa gì |
| `GroupModeAndKeyGrammarTest` | Nhãn ≠ gộp, hai bất biến cụm gộp, ngữ pháp khoá |
| `KeyGrammarContractTest` | File ngữ pháp khoá công bố phải khớp nguồn thật |
| `TemplateExpectedTextTest` | `expected` máy sinh không lộ khoá/từ vựng nội bộ |
| `FixtureIsProducibleTest` | Bộ đo phải **sản xuất được** từ khâu soạn đề |
| `FixtureResultAssemblyTest` | Toàn bộ `result.json` trên dữ liệu chấm thật |

Ngoài ra có bộ luật nghiệm thu chạy trực tiếp trên file kết quả:

```bash
python verify_result.py <result.json> --matrix <skills_matrix.json>
```

Hiện **0 luật FAIL** trên 9 mẫu phát hành. Nếu workflow mới của các bạn làm luật nào đỏ, đó là dấu
hiệu `result.json` mất dữ liệu bot cần.

---

## 8. Liên hệ

Mọi thứ liên quan tới **hình dạng và nội dung `result.json`** thì báo bên tôi trước khi đổi — đặc
biệt: `exam.requirements`, `rubric`/`rubric_label`, `expected`, `actual`, và danh sách nhóm khoá.
Những phần còn lại của workflow soạn đề là của các bạn, chúng tôi không đụng.
