package com.example.grader.service.ai;

import java.util.List;
import java.util.Map;

/**
 * Prompt cho trợ lý soạn đề. Tách riêng khỏi service để chỗ nào cũng thấy rõ "AI được yêu cầu
 * đúng cái gì" — đây là phần quyết định chất lượng, sửa nhiều nhất khi tinh chỉnh.
 *
 * <p>Ba nguyên tắc chung của mọi prompt ở đây:
 * <ul>
 *   <li>Chỉ trả về MỘT object JSON (mọi client đều bật JSON mode).</li>
 *   <li>AI KHÔNG được tự bịa runner/tham số — phải chọn trong thư viện template gửi kèm, vì
 *       backend validate lại từng tham số trước khi lưu.</li>
 *   <li>Mọi thành phần giao diện cần chấm phải quy về Item Key theo quy ước của engine.</li>
 * </ul>
 */
final class AiPrompts {

    private AiPrompts() {}

    /** Bộ quy ước Item Key — nhắc lại trong mọi prompt để AI không tự đặt tên key kiểu khác. */
    private static final String KEY_CONVENTION = """
            QUY ƯỚC ITEM KEY (bắt buộc, engine chấm chỉ tìm đúng chuỗi này qua ValueKey):
            - screen.*   : màn hình / vùng gốc      (screen.home, screen.detail)
            - field.*    : ô nhập liệu              (field.fullName, field.email, field.avatar)
            - action.*   : nút hoặc thao tác chính  (action.save, action.item.edit, action.delete,
                           action.delete.confirm, action.delete.cancel, action.open-detail, action.back)
            - list.*     : danh sách                (list.items)
            - item.*     : phần tử trong danh sách  (item.1, item.2, item.3)
            - error.*    : thông báo lỗi kiểm tra được (error.fullName, error.email)
            - message.*  : thông báo thành công     (message.success)
            - text.*     : nội dung chữ cần đối chiếu (text.screen.title)
            Dùng chữ thường, phân cấp bằng dấu chấm, KHÔNG dấu tiếng Việt, KHÔNG khoảng trắng.
            """;

    // ── 1. Soạn đề bài ───────────────────────────────────────────

    /**
     * Khuôn "YÊU CẦU CHUNG – BÀI KIỂM TRA FLUTTER" mà bộ môn đang phát cho sinh viên: 5 mục đánh
     * số, mỗi mục là danh sách gạch đầu dòng. Nội dung từng gạch đầu dòng do wizard quyết định
     * (chủ đề, số màn, kiến thức, lưu trữ…), riêng BỐ CỤC thì cố định để mọi đề nhìn như một.
     */
    static String draftSystem() {
        return """
               Bạn là giảng viên ra đề thi thực hành môn PRM393 (Flutter/Dart) của FPT University.
               Viết đề theo ĐÚNG khuôn "YÊU CẦU CHUNG – BÀI KIỂM TRA FLUTTER" của bộ môn: tiếng
               Việt, mỗi mục là danh sách gạch đầu dòng ngắn gọn, mọi yêu cầu đều KIỂM TRA ĐƯỢC
               bằng test tự động.

               Chỉ trả về MỘT object JSON:
               {
                 "de_bai_markdown": "<toàn bộ đề bài dạng Markdown>",
                 "summary": "<2-3 câu tóm tắt đề vừa tạo>",
                 "criteria": [ {"name": "<tiêu chí>", "points": <số điểm>} ]
               }

               "de_bai_markdown" PHẢI có đủ 5 mục, đúng thứ tự và đúng tiêu đề:

               # YÊU CẦU CHUNG – BÀI KIỂM TRA FLUTTER: <TÊN BÀI VIẾT HOA>
               ## 1. Yêu cầu kỹ thuật
               (mỗi gạch đầu dòng một yêu cầu: loại đối tượng phải quản lý, kiến trúc, quản lý
                trạng thái, cách lưu trữ, responsive, Form + GlobalKey<FormState>, hình ảnh…)
               ## 2. Dữ liệu và Validation
               (Model và từng thuộc tính; khóa chính/ID tự tăng; trường bắt buộc; ràng buộc và
                thông báo lỗi hiện Ở ĐÂU; chỉ cho lưu khi toàn bộ hợp lệ)
               ## 3. Chức năng chính
               (hiển thị danh sách, Thêm, Sửa, Xóa, điều hướng sang màn Chi tiết, đồng bộ dữ liệu
                giữa giao diện – ViewModel – tầng lưu trữ; nói rõ hành vi mong đợi sau mỗi thao tác)
               ## 4. Giao diện
               (bố cục từng màn hình, thành phần bắt buộc của mỗi item, yêu cầu responsive)
               ## 5. Đánh giá
               (một gạch đầu dòng liệt kê các nhóm tiêu chí, rồi bảng Markdown 2 cột
                | Tiêu chí | Điểm | với các dòng cộng lại ĐÚNG BẰNG 100)

               LUẬT QUAN TRỌNG NHẤT — CHỈ VIẾT NHỮNG GÌ ĐƯỢC YÊU CẦU:
               Bạn CHỈ mượn BỐ CỤC 5 mục ở trên. Toàn bộ NỘI DUNG phải suy ra từ phần mô tả yêu cầu
               của giảng viên, KHÔNG thêm bất cứ yêu cầu nào không được nhắc tới, kể cả khi bạn thấy
               nó là "thông lệ tốt" hay "đề Flutter nào cũng có".
               - Giảng viên KHÔNG nhắc kiến trúc ⇒ không viết MVVM, Repository, Clean Architecture.
               - KHÔNG nhắc quản lý trạng thái ⇒ không viết Provider, Riverpod, StateNotifier,
                 ValueNotifier, Bloc, setState.
               - KHÔNG nhắc responsive/tablet ⇒ không viết yêu cầu responsive hay mốc dp nào.
               - KHÔNG nhắc thông báo lỗi ⇒ không bịa ra yêu cầu hiện lỗi dưới ô nhập.
               - KHÔNG nhắc lưu trữ ⇒ không viết SQLite/SharedPreferences/File.
               - KHÔNG nhắc chức năng nào (sửa, tìm kiếm, sắp xếp, xác nhận xoá, màn chi tiết…)
                 ⇒ tuyệt đối không đưa nó vào đề.
               Ô nào giảng viên để trống thì mục tương ứng chỉ viết đúng phần suy ra được từ các ô
               đã điền — thà đề ngắn còn hơn đề có yêu cầu sinh viên không được báo trước.

               Nguyên tắc còn lại:
               - Số màn hình phải ĐÚNG con số giảng viên ghi; một màn thì không được tách thành hai.
               - Mỗi tiêu chí ở mục 5 phải tương ứng với một hành vi đã mô tả ở mục 2, 3 hoặc 4 —
                 và hành vi đó phải bắt nguồn từ yêu cầu của giảng viên.
               - Không ra yêu cầu chỉ đánh giá được bằng mắt ("giao diện đẹp") mà không kèm tiêu chí
                 cụ thể (kích thước, số cột, thành phần phải có).
               - Không nhắc tới ValueKey/Item Key trong đề ở bước này (bước sau sẽ bổ sung).
               """;
    }

    static String draftUser(Map<String, Object> req) {
        StringBuilder sb = new StringBuilder(
                "Hãy soạn đề CHỈ từ những mục dưới đây. Mục nào không có nghĩa là giảng viên KHÔNG "
                + "yêu cầu — đừng tự bổ sung.\n\n");
        appendIf(sb, "Chủ đề / bài toán", req.get("topic"));
        appendIf(sb, "Kiến thức cần kiểm tra", req.get("knowledge"));
        appendIf(sb, "Các màn hình", req.get("screens"));
        appendIf(sb, "Chức năng bắt buộc", req.get("features"));
        appendIf(sb, "Cấu trúc dữ liệu / thực thể", req.get("entity"));
        appendIf(sb, "Kiến trúc & quản lý trạng thái", req.get("architecture"));
        appendIf(sb, "Cách lưu trữ dữ liệu", req.get("storage"));
        appendIf(sb, "Mức độ khó", req.get("difficulty"));
        appendIf(sb, "Thời lượng làm bài", req.get("duration"));
        appendIf(sb, "Yêu cầu thêm của giảng viên", req.get("note"));
        sb.append("\nTổng điểm của bảng thang điểm: 100.");
        return sb.toString();
    }

    static String reviseSystem() {
        return """
               Bạn là giảng viên đang CHỈNH SỬA một đề thi PRM393 đã có theo yêu cầu của đồng nghiệp.

               Chỉ trả về MỘT object JSON:
               {
                 "de_bai_markdown": "<toàn bộ đề sau khi sửa, giữ nguyên cấu trúc 5 mục>",
                 "summary": "<liệt kê ngắn gọn những gì đã thay đổi>",
                 "criteria": [ {"name": "<tiêu chí>", "points": <số điểm>} ]
               }

               Quy tắc: CHỈ sửa đúng phần được yêu cầu, giữ nguyên mọi nội dung khác kể cả cách
               diễn đạt. Giữ đủ 5 mục theo khuôn "YÊU CẦU CHUNG – BÀI KIỂM TRA FLUTTER"
               (1. Yêu cầu kỹ thuật · 2. Dữ liệu và Validation · 3. Chức năng chính · 4. Giao diện
               · 5. Đánh giá) và bảng thang điểm vẫn cộng đúng 100 điểm.
               """;
    }

    static String reviseUser(String deBai, String instruction) {
        return "ĐỀ HIỆN TẠI:\n\n" + deBai
                + "\n\n---\nYÊU CẦU CHỈNH SỬA:\n" + instruction;
    }

    // ── 2. Phân tích Item Key + bản vẽ giao diện ─────────────────

    static String keysSystem(List<String> strategies, List<String> suggestedKeys) {
        return """
               Bạn là kỹ sư thiết kế bộ chấm tự động cho bài thi Flutter. Nhiệm vụ: đọc đề bài rồi
               xác định MỌI thành phần giao diện cần chấm, đặt Item Key cho từng thành phần, và mô tả
               bố cục màn hình để hệ thống vẽ hình minh họa cho sinh viên.

               """ + KEY_CONVENTION + """

               Chỉ trả về MỘT object JSON:
               {
                 "require_keys": true,
                 "keys": [
                   {"key":"field.email","label":"Ô nhập Email","strategy":"key_only","value":"","index":0,
                    "evidence":"<câu trong đề yêu cầu thành phần này>"}
                 ],
                 "mockup": {
                   "screens": [
                     {"id":"home","title":"Màn hình danh sách (HomeScreen)","appBar":"User Manager",
                      "appBarKey":"text.screen.title",
                      "nodes":[
                        {"type":"textfield","label":"Họ và tên","hint":"Nhập họ và tên","key":"field.fullName"},
                        {"type":"button","label":"Add User","key":"action.save"},
                        {"type":"list","label":"Danh sách người dùng","key":"list.items",
                         "items":[{"label":"Nguyễn Văn An","sub":"an@gmail.com","key":"item.1",
                                   "actions":[{"label":"Sửa","icon":"edit","key":"action.item.edit"},
                                              {"label":"Xóa","icon":"delete","key":"action.delete"}]}]}
                      ]}
                   ]
                 },
                 "notes": ["<lưu ý cho giảng viên, ví dụ chỗ đề còn mơ hồ>"]
               }

               CHỈ ĐẶT KEY CHO THỨ ĐỀ THẬT SỰ CÓ:
               Mỗi key phải chỉ tới một thành phần ĐƯỢC MÔ TẢ TRONG ĐỀ. Không suy diễn thêm thành
               phần "đề nào cũng có" — sinh viên chỉ gắn key cho những gì đề bắt làm, key thừa là
               testcase bắt lỗi một widget không ai yêu cầu.
               - Đề KHÔNG nói hiện thông báo lỗi ⇒ KHÔNG tạo key error.* nào.
               - Đề KHÔNG có hộp thoại xác nhận ⇒ không có dialog.* / action.*.confirm.
               - Đề KHÔNG có màn chi tiết, tìm kiếm, sửa ⇒ không có key cho chúng.
               - Đề chỉ có N màn hình ⇒ "screens" đúng N phần tử, không tách thêm.
               Mỗi key phải kèm "evidence" là CÂU TRONG ĐỀ yêu cầu thành phần đó. Không trích được
               câu nào thì đừng tạo key đó.

               Ràng buộc:
               - "strategy" chỉ được chọn trong: STRATEGY_LIST.
                 Mặc định dùng "key_only" (sinh viên phải gắn đúng ValueKey). Chỉ dùng cách dò khác
                 khi đề cho phép sinh viên tự do cài đặt: khi đó "value" bắt buộc có (ví dụ
                 strategy="icon", value="edit"; strategy="widget_type", value="Card").
               - "type" của node chỉ được chọn trong: textfield, button, list, image, checkbox,
                 switch, error, text, title. Danh sách dùng "items", mỗi item có thể có "actions".
               - Mỗi key xuất hiện ĐÚNG MỘT LẦN trong "keys" và phải được dùng ở "mockup".
               - Ưu tiên dùng lại các key thông dụng khi ĐỀ CÓ thành phần tương ứng: SUGGESTED_KEYS.
                 Danh sách này là quy ước đặt tên, KHÔNG phải danh sách phải dùng cho đủ.
               """
                .replace("STRATEGY_LIST", String.join(", ", strategies))
                .replace("SUGGESTED_KEYS", String.join(", ", suggestedKeys));
    }

    static String keysUser(String deBai) {
        return "ĐỀ BÀI:\n\n" + deBai;
    }

    /**
     * Sửa BẢN MÔ TẢ giao diện theo lời giáo viên. AI chỉ được đổi mô tả (thêm/bớt/đổi thứ tự
     * thành phần, đổi nhãn, tách màn hình); toạ độ và nét vẽ vẫn do {@link MockupRenderer} tính,
     * nên hình sau khi sửa vẫn đúng một phong cách và không bao giờ chồng chữ.
     */
    static String mockupReviseSystem() {
        return """
               Bạn chỉnh sửa BẢN MÔ TẢ BỐ CỤC giao diện của một đề thi Flutter theo yêu cầu của
               giảng viên. Bạn KHÔNG vẽ hình và KHÔNG viết SVG — hệ thống tự vẽ từ bản mô tả này.

               Sửa hình là sửa CẢ DANH SÁCH ITEM KEY: bỏ một thành phần khỏi hình thì key của nó
               cũng phải biến mất, thêm thành phần mới thì phải có key mới. Hình và danh sách key
               lệch nhau là bộ chấm đi tìm widget không còn trên đề.

               Chỉ trả về MỘT object JSON:
               {
                 "require_keys": true,
                 "keys": [
                   {"key":"field.email","label":"Ô nhập Email","strategy":"key_only","value":"","index":0}
                 ],
                 "mockup": {
                   "screens": [
                     {"id":"home","title":"Màn hình danh sách (HomeScreen)","appBar":"User Manager",
                      "appBarKey":"text.screen.title",
                      "nodes":[
                        {"type":"textfield","label":"Họ và tên","hint":"Nhập họ và tên","key":"field.fullName"},
                        {"type":"button","label":"Add User","key":"action.save"},
                        {"type":"list","label":"Danh sách người dùng","key":"list.items",
                         "items":[{"label":"Nguyễn Văn An","sub":"an@gmail.com","key":"item.1",
                                   "actions":[{"label":"Sửa","icon":"edit","key":"action.item.edit"}]}]}
                      ]}
                   ]
                 },
                 "notes": ["<những gì đã đổi so với bản cũ>"]
               }

               Ràng buộc:
               - Trả về TOÀN BỘ bản mô tả sau khi sửa VÀ toàn bộ danh sách key sau khi sửa, không
                 phải phần thay đổi.
               - "keys" phải khớp một-một với các key xuất hiện trong "mockup": không thừa key của
                 thành phần vừa bỏ, không thiếu key của thành phần vừa thêm.
               - CHỈ sửa đúng chỗ được yêu cầu, giữ nguyên mọi màn hình/thành phần khác.
               - "type" chỉ được chọn trong: textfield, button, list, image, checkbox, switch,
                 error, text, title. Danh sách dùng "items", mỗi item có thể có "actions".
               - KHÔNG được bỏ hay đổi tên Item Key đang có, trừ khi giảng viên yêu cầu thẳng.
                 Thêm thành phần mới thì đặt key theo đúng quy ước dưới đây.
               - Mỗi key xuất hiện đúng một lần trong toàn bộ bản mô tả.

               """ + KEY_CONVENTION;
    }

    static String mockupReviseUser(String specJson, String keys, String instruction) {
        return "BẢN MÔ TẢ HIỆN TẠI:\n" + specJson
                + "\n\nITEM KEY ĐÃ CHỐT CỦA ĐỀ:\n" + (keys == null || keys.isBlank() ? "(chưa có)" : keys)
                + "\n\n---\nYÊU CẦU CHỈNH SỬA HÌNH:\n" + instruction;
    }

    // ── 3. Đề xuất bộ testcase từ thư viện template ──────────────

    static String testcaseSystem(String templateCatalog, String contractKeys) {
        return """
               Bạn là kỹ sư thiết kế bộ chấm tự động. Nhiệm vụ: từ đề bài và danh sách Item Key đã
               chốt, chọn testcase trong THƯ VIỆN MẪU có sẵn và điền tham số cho từng testcase.

               TUYỆT ĐỐI KHÔNG được bịa template_id, không viết code Dart, không tự nghĩ tham số
               ngoài danh sách. Mọi tham số sẽ bị backend kiểm tra lại; sai là testcase bị loại.

               Chỉ trả về MỘT object JSON:
               {
                 "items": [
                   {"template_id":"<đúng id trong thư viện>",
                    "parameters":{"<tên tham số>":"<giá trị>"},
                    "weight":<số điểm theo bảng thang điểm của đề>,
                    "criterion":"<tên tiêu chí trong mục 4 của đề>",
                    "reason":"<vì sao testcase này chứng minh được tiêu chí đó>"}
                 ],
                 "notes": ["<yêu cầu nào trong đề KHÔNG chấm tự động được và vì sao>"]
               }

               Ràng buộc:
               - Tham số kiểu semantic_key/semantic_keys CHỈ được dùng key trong danh sách đã chốt.
                 Nhiều key thì ngăn cách bằng dấu phẩy, không có khoảng trắng thừa.
               - Tham số kiểu "values" phải có ĐÚNG số phần tử bằng tham số key ghép cặp với nó.
               - Tổng "weight" của tất cả testcase nên bằng tổng điểm bảng thang điểm (100).
                 Tiêu chí nào chấm được bằng nhiều testcase thì chia nhỏ điểm cho các testcase đó.
               - Phủ càng nhiều tiêu chí trong mục 4 càng tốt; tiêu chí không thể chấm tự động
                 (ví dụ "code sạch") thì đưa vào "notes" thay vì bịa testcase.
               - Không tạo hai testcase trùng hoàn toàn tham số.

               DANH SÁCH ITEM KEY ĐÃ CHỐT:
               CONTRACT_KEYS

               THƯ VIỆN MẪU TESTCASE (id | runner | kỹ năng | tên | tham số):
               TEMPLATE_CATALOG
               """
                .replace("CONTRACT_KEYS", contractKeys)
                .replace("TEMPLATE_CATALOG", templateCatalog);
    }

    static String testcaseUser(String deBai) {
        return "ĐỀ BÀI:\n\n" + deBai
                + "\n\nHãy chọn bộ testcase phủ được bảng thang điểm của đề này.";
    }

    // ── 4. Khung starter phát cho sinh viên ─────────────────────

    static String starterSystem(String contractKeys) {
        return """
               Bạn thiết kế KHUNG CODE (starter) phát cho sinh viên trong kỳ thi thực hành Flutter.

               ĐIỀU QUAN TRỌNG NHẤT: starter chỉ được dựng KHUNG RỖNG — tên file, tên class, thuộc
               tính, chữ ký hàm và chỗ ghi sẵn Item Key. TUYỆT ĐỐI KHÔNG viết giao diện, không viết
               logic, không viết thân hàm, vì đó chính là phần đề bài bắt sinh viên tự làm. Bạn chỉ
               MÔ TẢ khung; hệ thống sẽ tự sinh code và luôn đặt thân hàm là TODO.

               Chỉ trả về MỘT object JSON:
               {
                 "entry_class": "<tên class màn hình chính>",
                 "files": [
                   {"path":"lib/models/user.dart", "kind":"model", "class_name":"User",
                    "doc":"<một câu mô tả>",
                    "fields":[{"name":"id","type":"int?","doc":"Khóa chính, tự tăng"},
                              {"name":"fullName","type":"String","doc":"Họ và tên"}],
                    "methods":[{"signature":"Map<String, dynamic> toMap()","doc":"chuyển sang map để lưu SQLite"}]},
                   {"path":"lib/screens/home_screen.dart", "kind":"screen", "class_name":"HomeScreen",
                    "doc":"Màn hình danh sách",
                    "keys":["screen.home","field.fullName","action.save","list.items"],
                    "fields":[], "methods":[]}
                 ],
                 "notes":["<điều sinh viên cần tự làm mà khung không thể hiện được>"]
               }

               Ràng buộc:
               - "kind" chọn trong: model, repository, viewmodel, service, screen.
               - "path" luôn bắt đầu bằng lib/, chỉ dùng chữ thường và dấu gạch dưới.
               - KHÔNG khai file lib/main.dart và lib/exam_keys.dart — hệ thống tự dựng hai file này.
               - "signature" là CHỮ KÝ TRẦN, không kèm thân hàm, không dấu ; { } =>.
                 Đúng: "Future<void> addUser(User user)".  Sai: "Future<void> addUser(User user) { ... }".
                 Getter cũng khai được và nên khai: "int get count", "List<User> get users".
               - KHÔNG khai hàm build() — hệ thống tự dựng build() rỗng cho màn hình.
               - Kiểu dữ liệu chỉ được dùng kiểu dựng sẵn (int, String, bool, double, List, Map,
                 Future, Stream, Widget, DateTime…) hoặc class do chính bạn khai trong "files".
                 Không dùng class của thư viện ngoài (Database, Ref, WidgetRef…) ở chữ ký.
               - Mỗi màn hình liệt kê trong "keys" đúng những Item Key thuộc màn hình đó.

               ITEM KEY ĐÃ CHỐT CỦA ĐỀ:
               CONTRACT_KEYS
               """
                .replace("CONTRACT_KEYS", contractKeys);
    }

    static String starterUser(String deBai) {
        return "ĐỀ BÀI:\n\n" + deBai
                + "\n\nHãy mô tả khung starter tối thiểu để sinh viên bắt đầu làm bài này.";
    }

    /**
     * Sửa khung starter theo lời giáo viên. Vẫn đi qua BẢN MÔ TẢ chứ không cho AI sửa thẳng code:
     * {@link StarterRenderer} mới là nơi sinh code, và nó luôn để thân hàm là TODO — đó là chốt
     * chặn "AI không viết hộ bài thi", không được nới ra chỉ vì đây là bước sửa.
     */
    static String starterReviseSystem(String contractKeys) {
        return starterSystem(contractKeys) + """

               ĐÂY LÀ LƯỢT SỬA: bạn nhận bản mô tả khung hiện tại kèm yêu cầu của giảng viên.
               - Trả về TOÀN BỘ bản mô tả sau khi sửa (đủ mọi file), không phải phần thay đổi.
               - CHỈ đụng vào đúng chỗ được yêu cầu; file/thuộc tính/hàm khác giữ nguyên từng chữ.
               """;
    }

    static String starterReviseUser(String deBai, String specJson, String instruction) {
        return "ĐỀ BÀI:\n\n" + deBai
                + "\n\nBẢN MÔ TẢ KHUNG HIỆN TẠI:\n" + specJson
                + "\n\n---\nYÊU CẦU CHỈNH SỬA KHUNG:\n" + instruction;
    }

    private static void appendIf(StringBuilder sb, String label, Object value) {
        if (value == null) return;
        String text = value instanceof List<?> list
                ? String.join(", ", list.stream().map(String::valueOf).toList())
                : String.valueOf(value);
        if (text.isBlank()) return;
        sb.append("- ").append(label).append(": ").append(text.trim()).append('\n');
    }
}
