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
   sửa hay gỡ tuỳ ý (mục 6); kèm **ba chỗ đang dùng danh tính** cần xử khi bỏ đăng nhập (mục 6.4).

> **Nguyên tắc chúng tôi tự đặt từ nay:** khi chất lượng `result.json` đòi một thay đổi ở khâu soạn
> đề, chúng tôi **đặc tả và bàn giao**, không tự thi công. Tài liệu này là lần bàn giao đầu tiên.

**Bối cảnh vận hành để hiểu vì sao các yêu cầu dưới đây gắt:** nhận xét được sinh **hàng loạt** cho
toàn bộ bài nộp rồi gửi qua mail; **chỉ bài bị bot gắn cờ mới có người xem lại**. Một chữ sai không
dừng ở một sinh viên — nó tới hàng trăm người. Vì thế mọi thứ dưới đây đều là "chặn ở khâu nhập"
chứ không phải "sửa ở khâu xuất".

### Luồng vận hành đã chốt (mentor, 2026-08-15) — **bỏ tài khoản đăng nhập**

```
MÁY A — giảng viên            MÁY B — khảo thí (chỉ mở phần mềm SAU khi thi xong)
────────────────────          ──────────────────────────────────────────────────
soạn đề + testcase                 tạo mã đề bằng ZIP nhận được
        │                                      │
        └──────── file ZIP ────────────────────┘
                                               ↓
                                    ném bài làm sinh viên vào, chấm
```

Hai máy cá nhân riêng, **không dùng chung máy chủ, không còn đăng nhập** — ai làm việc nấy. Hai hệ
quả cho tài liệu này:

1. **ZIP là kênh vận chuyển chính thức và DUY NHẤT** giữa hai vai. Đo được: toàn hệ thống chỉ có
   **một** đường nhập đề (`POST /api/exam-setup/upload-testcase`); chép tay thư mục `exams/` sang
   máy B **không chấm được** vì thiếu bản ghi trong CSDL. ⇒ Mục 5 nằm trên con đường đó.
2. **Bỏ tài khoản KHÔNG chạm `result.json`.** Đã đo: không trường nào trong file kết quả bắt nguồn
   từ người đăng nhập (`teacher_note` lấy từ ô nhập lúc soạn đề, không phải từ tài khoản). Hợp đồng
   với bot **không đổi một chữ**. Ba chỗ các bạn cần tự quyết khi gỡ — xem mục 6.4.

---

## 1. Hai thứ BẮT BUỘC — bảng tóm tắt

| # | Tính năng ở khâu soạn đề | Đổ vào trường nào của `result.json` | Không có thì sao |
|---|---|---|---|
| 1 | **Gán nhãn nhóm chức năng** (khác với gộp testcase) | `test_cases[].rubric` + `rubric_label` | Nhận xét không gom được theo chức năng, rơi về 3 nhãn kỹ thuật thô `LOGIC`/`WIDGET`/`BEHAVIOR` |
| 2 | **Ngữ pháp khoá chấm** | `expected`, `actual`, `name` | Khoá nội bộ (`action.delete.1`) lọt vào chữ gửi sinh viên |

> Trước đây bảng này có ba dòng; dòng **"Ô nhập Yêu cầu của đề"** đã **bị bỏ hẳn** — xem mục 2.

Chi tiết từng cái ở dưới. Cách hiện thực là quyền của các bạn — phần **bắt buộc** là *hợp đồng dữ liệu*,
không phải mã nguồn cụ thể.

---

## 2. ~~Yêu cầu của đề → `exam.requirements`~~ — ❌ **ĐÃ BỎ HẲN, KHÔNG CÒN LÀ YÊU CẦU**

**Chủ đồ án quyết bỏ toàn bộ tính năng này.** Nó không còn tồn tại trong dự án và **các bạn không
phải làm gì cho nó nữa** — kể cả những chỗ tài liệu này từng nhờ (đáng chú ý: mục 5 từng xin các
bạn sửa đường nhập ZIP để chở `requirements`; **lời nhờ đó đã huỷ**).

### Vì sao bỏ

Trường này chỉ có **một** phía tiêu thụ: khối "Đề bài yêu cầu" trong bản nhận xét gửi sinh viên do
phía bot NLP dựng. Phía đó đã **bỏ hẳn khối ấy** sau khi chấm tay 13 bài trên nhận xét sinh thật —
người chấm chê khối này ở 6/13 bài (dài dòng, lặp lại), và cái lỗ mà nó sinh ra để lấp hoá ra không
tồn tại. Không còn ai đọc thì giữ lại chỉ là chở dữ liệu chết vào `result.json` của từng bài.

### Đã gỡ những gì

Ô nhập trên màn soạn đề · phép chặn lúc lưu và hai con số trần (4000 ký tự / 40 dòng) ·
`BatchGradingService.splitRequirements` · `TestcaseTemplateService.validateRequirements` ·
cột `exams.requirements` trong entity · phần bù `[]` ở `ResultController` · trường
`exam.requirements` trong `result.json` · cả file test `ExamRequirementsTest`.

Cột `requirements` trong DB các máy đang chạy **không migrate** — nó thành cột thừa, không ai đọc.

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

> **Mục này đã THU HẸP.** Trước đây nó xin ưu tiên cao vì đường ZIP làm mất "Yêu cầu của đề".
> Tính năng đó nay **bỏ hẳn** (mục 2), nên **lý do ưu tiên ấy không còn** và chúng tôi rút lại lời
> nhờ đó. Phần dưới là chỗ **vẫn hỏng thật**, độc lập với requirements.

**Triệu chứng: đề nhập bằng ZIP không bao giờ nâng được engine chấm.**

### Nguyên nhân
* ZIP tải về chỉ đóng gói **3 file**: `exam_test.dart`, `grader.dart`, `skills_matrix.json`.
* `testcase-config.json` **không** vào ZIP.
* Đường nhập ZIP bỏ qua 3 cột DB: `testcase_config_json`, `created_by`, `allowed_packages`.

### Hệ quả
`refreshCommonEngine` luôn bỏ qua đề nhập bằng ZIP (nó đọc `engine_type` từ cột đang null) ⇒
**"Chấm lại đề" không bao giờ nâng được engine chấm** cho những đề này. Nghĩa là đề đã phát cho
khảo thí sẽ đứng yên ở bản engine lúc xuất ZIP, kể cả khi engine sau đó sửa được lỗi chấm sai.

Mức nghiêm trọng phụ thuộc quyết định của mentor: ZIP là **kênh vận chuyển chính thức DUY NHẤT**
giữa máy giảng viên và máy khảo thí (đã đo: toàn hệ thống chỉ có một đường nhập đề; chép tay thư
mục `exams/` không chấm được vì thiếu bản ghi CSDL). Nên mọi đề khảo thí chấm đều đi qua đường này.

### Gợi ý phương án (các bạn quyết, chúng tôi không tự sửa)
1. ZIP tải về gồm **4 file** (thêm `testcase-config.json`).
2. Khi nhập: thấy `testcase-config.json` thì phục hồi `testcase_config_json` + version/status vào DB.
3. **Cố ý không phục hồi `created_by`** — để khảo thí vẫn không sửa được đề, nhưng
   `refreshCommonEngine` chạy được.
4. ZIP cũ 3 file vẫn nhập được như hôm nay.

> Ghi chú: `testcase-config.json` hiện **được ghi ra đĩa nhưng không code nào đọc lại** — nguồn đọc
> là cột DB. Nếu các bạn thiết kế lại, đây là chỗ đáng dọn.

---

## 5b. 🔴 KHIẾM KHUYẾT NẶNG NHẤT: ảnh chấm tải thư viện SQLite **qua mạng lúc đang chấm**

**Triệu chứng: cùng một bài nộp, chấm hai lần ra hai điểm khác nhau — chênh tới 6.7 điểm.**

Đây là khiếm khuyết ăn thẳng vào điểm số sinh viên, và **nó im lặng**: nhìn `result.json` thì cả
hai lần đều là dữ liệu hợp lệ, không có cờ nào báo "lần này đo hỏng".

### Đo được (chạy thật, không phải đọc code)

Bộ fixture `fixtures/result-json-v2`, bài `medium`, cùng ảnh `grading-base:latest`, cùng lệnh,
hai lần chạy cách nhau vài phút:

| | điểm | đạt | trạng thái các testcase |
|---|---|---|---|
| lần 1 | **0.0** | 0/25 | cả 25 testcase = `not_run` |
| lần 2 | **6.7** | 19/25 | khớp kỳ vọng |

### Nguyên nhân

`runner_error` của lần hỏng nói thẳng:

```
Unhandled exception: By default, this package downloads a pre-compiled SQLite library.
This failed (attempted to download
  .../sqlite3.dart/releases/download/sqlite3-3.5.1/libsqlite3.x64.linux.so)
Original cause: SocketException: Connection refused, address = github.com
```

`grader-base/pubspec.base.yaml` khai `sqflite_common_ffi`; gói này kéo theo package `sqlite3`, và
package đó **tải thư viện native từ GitHub vào LẦN CHẠY ĐẦU TIÊN** — tức lúc chấm bài, không phải
lúc dựng ảnh. `flutter pub get` trong `Dockerfile.base` chỉ kéo mã Dart, **không** nướng được file
`.so` đó vào ảnh.

⇒ Mỗi lần chấm là một lần phụ thuộc mạng. Mạng hụt đúng một nhịp thì **cả bộ test không khởi
động**, và sinh viên nhận **0 điểm vì lý do không liên quan gì tới mã nguồn của em ấy**.

### Vì sao chúng tôi xin xếp việc này ưu tiên cao nhất

- Quy mô: mọi đề có testcase chạm SQLite đều dính. Một đợt 100–200 bài mà mạng chập một lúc là
  cả loạt bài đó sai điểm cùng nhau.
- Không ai phát hiện được sau đó: điểm 0 trông y hệt điểm 0 do bài làm hỏng thật.
- Phía bot nhận xét **không đỡ được**: họ chỉ thấy `not_run` hợp lệ, nên sẽ viết nhận xét theo
  hướng "bài chưa chạy được" — nói sai với sinh viên làm đúng.

### Cách tái hiện (chưa tới một phút)

```bash
cd fixtures/result-json-v2
./run-fixture.sh medium      # chạy vài lần; lần nào mạng tới GitHub trục trặc thì ra 0.0
```

Muốn ép ra lỗi ngay: chạy `docker run` với `--network none` rồi chấm lại bài `medium`.

### Gợi ý phương án (các bạn quyết, chúng tôi không tự sửa)

1. **Trỏ sang thư viện hệ thống** — ảnh **đã cài sẵn** `libsqlite3-0` và `libsqlite3-dev` qua apt.
   Package `sqlite3` cho phép chỉ định thư viện thay vì tải; xem `hook-topic` trong chính thông
   báo lỗi. Đây là hướng gọn nhất và bỏ hẳn phụ thuộc mạng.
2. **Nướng sẵn file `.so` vào ảnh** lúc build (build có mạng, chấm thì không cần).
3. Dù chọn hướng nào, nên **chạy chấm với `--network none`** để mọi phụ thuộc mạng còn sót lại
   biến thành lỗi build ồn ào thay vì sai điểm im lặng.

### 📣 Xin báo lại chúng tôi khi sửa xong

Chúng tôi cần **mốc** đó để nói với phía bot nhận xét *"từ đây điểm không còn phụ thuộc mạng"*, và
để đánh giá lại các đợt chấm đã thực hiện trước mốc.

---

## 6. Những chỗ chúng tôi đã đụng vào code của các bạn

Làm trong lúc các bạn refactor, nên **báo đủ để các bạn quyết giữ / sửa / gỡ**. Nhánh `chien1`.

| Commit | Đụng gì | Ghi chú cho các bạn |
|---|---|---|
| ~~`a9c7aa5`~~ | ~~**P6a** — cột `requirements`, chặn nhập, tách dòng, bù `[]` đường đọc~~ | ❌ **ĐÃ GỠ HẾT** — tính năng bị bỏ, xem mục 2. Không còn gì để các bạn giữ/sửa |
| ~~`eff5c6f`~~ | ~~**P6b** — ô nhập "Yêu cầu của đề" + bộ đếm trên trang *Tạo testcase*~~ | ❌ **ĐÃ GỠ HẾT** — ô nhập đã xoá khỏi màn *Tạo testcase*, các bạn không phải giữ hành vi nào |
| `f133595` | **`group_mode`** (nhãn/gộp) + **cưỡng chế ngữ pháp khoá** | ⚠️ Thêm khái niệm mới vào màn soạn đề (2 nút, 2 modal) **và đổi hành vi lưu đề** — xem cảnh báo dưới |
| `7c54233` | Test pin `common-key-grammar.json` | Chỉ test, không đổi hành vi |
| `6f3d5dc` | `targetType` vào `parameters_schema` của `WIDGET_VISIBLE`/`BUTTON_ACTION`; sửa race trùng `instance_id` ở FE | Bug thuần phía các bạn, chúng tôi vấp phải nên sửa luôn — chi tiết dưới |

### ⚠️ Hai chỗ đổi hành vi, cần các bạn biết trước khi merge

1. **Cưỡng chế ngữ pháp khoá chặn lưu đề.** Đề dùng khoá kiểu `nut.luu` trước đây lưu được, **nay bị
   từ chối**. Đây là thay đổi hành vi sản phẩm — nếu không hợp workflow mới, cứ gỡ và báo chúng tôi
   để tìm cách khác.
2. **`group_mode` thêm một khái niệm vào màn soạn đề.** Nếu các bạn đang thiết kế lại đúng màn đó,
   hai thiết kế sẽ chồng nhau — nên xem mục 3 như *đặc tả cần đạt*, còn giao diện thì làm theo ý các bạn.

### 6.4 Bỏ tài khoản đăng nhập — ba chỗ đang dùng danh tính, cần các bạn quyết

Không phải việc của chúng tôi (và **không chạm `result.json`**), nhưng đo được nên báo để các bạn
không sót — cả ba đều hỏng **lúc chạy** chứ không lúc biên dịch:

| Chỗ | Đang dùng danh tính làm gì | Cần quyết |
|---|---|---|
| `created_by`/`updated_by` trong `testcase-config.json` + cột `exams.created_by` | ghi ai soạn đề | bỏ hẳn, hay ghi hằng/tên máy |
| `TestcaseTemplateService.isTemplateCreatedExam` | cổng *"ai được sửa lại đề"* (`created_by == email` đăng nhập) | bỏ tài khoản thì cổng mất nghĩa — thay bằng gì, hay bỏ |
| `GET /api/batch/recent` | lọc danh sách theo `teacherEmail` | trả gì khi không còn tài khoản |

**Một điều chốt lại cho rõ, không phải phản đối:** bỏ đăng nhập ⇒ hệ thống **sẽ không bao giờ** ghi
được *"ai đã chấm bài này"*. Hôm nay nó **cũng không ghi**, nên đây không phải mất mát — chỉ là từ
nay điều đó thành vĩnh viễn. Nếu sau này nhà trường cần vết truy đó thì phải **thêm trường vào
`result.json`**, và đó là **đổi hợp đồng ⇒ báo chúng tôi trước**.

### Bốn lỗi phía các bạn chúng tôi phát hiện khi đo (đã sửa, không phải bàn giao)

| Lỗi | Hệ quả nếu để nguyên |
|---|---|
| `WIDGET_VISIBLE`/`BUTTON_ACTION` thiếu `targetType` trong `parameters_schema`, dù engine và validator **đều đã hỗ trợ** | Nhánh validate là code không bao giờ chạy; đề khai `targetType` bị từ chối oan |
| Bấm "Thêm vào đề" hai lần trong cùng một tick ⇒ hai testcase **trùng `instance_id`** | Trùng id hỏng im lặng: tick một ô thì cả hai cùng tick, sửa/xoá một cái trúng cả hai; giáo viên chỉ biết lúc bấm Lưu |
| Tên nhóm mặc định đếm **số testcase có nhóm** thay vì **số nhóm** | 3 testcase trong 1 nhóm ⇒ tên gợi ý "Nhóm kiểm tra 04" |
| **`syllabus.json` 2026.5 không nạp nổi vào DB**: cột `skill.testable` khai `length=10` mà từ vựng mới dài tới 28 ký tự (`pipeline_and_manual_evidence`) ⇒ RE-SEED chết ngay dòng đầu với `Data truncation` | **Tính năng 52 template curriculum của các bạn chết ngay từ đầu.** Bảng `skill` đứng ở 52 skill cũ, thiếu 19 skill mới; **10 skill_code** mà template curriculum cần (`API_HTTP_REST`, `STORAGE_SQLITE_CRUD`, `AUTH_LOGIN_SIGNUP`…) không có trong DB ⇒ `ExamService.validateSkillCodes` ném lỗi ⇒ **giáo viên chọn trúng nhóm đó là không lưu được đề**. Lỗi này **không test nào bắt được** vì nó chỉ xảy ra lúc seed vào MySQL thật — chúng tôi chỉ thấy khi bật stack lên chạy |

> Về dòng cuối: chúng tôi nới cột lên 32 (`Skill.java`). `ddl-auto: update` nên DB tự sửa lúc khởi
> động lại. Nếu sau này các bạn thêm giá trị `testable` dài hơn nữa thì phải nới tiếp — cân nhắc
> đổi hẳn sang `VARCHAR(64)` hoặc bỏ trần, vì từ vựng này do `syllabus.json` định nghĩa chứ không
> phải enum đóng trong code.

---

## 7. Cách tự kiểm sau khi các bạn đổi workflow

Bộ test backend hiện có **119 test**, trong đó các lớp sau khoá đúng những gì tài liệu này mô tả —
**đừng xoá chúng, chúng đỏ là hợp đồng vỡ**:

| Lớp test | Khoá điều gì |
|---|---|
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
biệt: `rubric`/`rubric_label`, `expected`, `actual`, và danh sách nhóm khoá.
Những phần còn lại của workflow soạn đề là của các bạn, chúng tôi không đụng.
