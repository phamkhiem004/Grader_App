package com.example.grader.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Danh mục runner của engine COMMON_V1 kèm mô tả tham số.
 *
 * Dùng cho form "Thêm/Sửa testcase" ở Khu vực 2: frontend dựng ô nhập theo `type` và
 * `options` ở đây thay vì hardcode từng runner. Danh sách này PHẢI khớp với switch trong
 * common-testcase-engine/exam_test.dart và validateCommonParameters của
 * {@link TestcaseTemplateService} — thêm runner mới thì sửa cả ba chỗ.
 */
final class TestcaseRunnerCatalog {

    private TestcaseRunnerCatalog() {}

    /** Loại widget mà `targetType` chấp nhận; trùng danh sách trong _assertTargetType. */
    static final List<String> TARGET_TYPES = List.of("any", "form", "image", "text", "input",
            "button", "dialog", "icon", "checkbox", "switch", "dropdown", "padding", "container");

    private static final List<String> FIELD_TYPES = List.of("input", "text");
    private static final List<String> MATCH_MODES = List.of("equals", "contains");
    private static final List<String> COMPARISONS = List.of("equals", "at_least", "at_most");
    private static final List<String> FONT_WEIGHTS = List.of("w100", "w200", "w300", "w400",
            "w500", "w600", "w700", "w800", "w900");

    /** Semantic key gợi ý cho ô nhập key; khớp fallback trong _byKey của engine. */
    static final List<String> SEMANTIC_KEYS = List.of(
            "screen.home", "screen.list", "screen.detail",
            "field.name", "field.fullName", "field.title", "field.email", "field.avatar",
            "action.save", "action.item.edit", "action.delete", "action.delete.cancel",
            "action.back", "action.open-detail", "action.load", "action.menu",
            "list.items", "item.1", "item.2", "item.3",
            "dialog.delete", "message.success", "state.empty", "state.loaded",
            "text.screen.title", "error.name", "error.email", "error.title");

    static List<Map<String, Object>> runners() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(runner("APP_BOOT", "Mở ứng dụng không bị lỗi", "SCREEN",
                "Chạy main() của bài và kiểm tra không có exception.",
                List.of(key("rootKey", "Key vùng gốc (tùy chọn)", false, "screen.home",
                        "Bỏ trống nếu chỉ cần app khởi động được."))));
        out.add(runner("WIDGET_VISIBLE", "Thành phần có key đang hiển thị", "SCREEN",
                "Xác nhận widget gắn key đang được render.",
                List.of(key("widgetKey", "Key của widget", true, "screen.home", null),
                        enums("targetType", "Loại widget (tùy chọn)", false, TARGET_TYPES, "",
                                "Khai loại để tránh key gắn nhầm vào widget bao ngoài."))));
        out.add(runner("WIDGET_TYPE_VISIBLE", "Thành phần đúng loại widget", "SCREEN",
                "Vừa kiểm tra key tồn tại, vừa kiểm tra key gắn đúng loại widget.",
                List.of(key("targetKey", "Key của widget", true, "screen.home", null),
                        enums("targetType", "Loại widget", true, TARGET_TYPES, "container", null))));
        out.add(runner("WIDGET_TEXT_CONTENT", "Nội dung chữ hiển thị đúng", "SCREEN",
                "Đọc nội dung Text theo key và so với chuỗi mong đợi.",
                List.of(key("targetKey", "Key của Text", true, "text.screen.title", null),
                        enums("targetType", "Loại widget", true, List.of("text"), "text", null),
                        text("expectedText", "Nội dung mong đợi", true, "Danh sách", null),
                        enums("matchMode", "Cách so khớp", true, MATCH_MODES, "equals",
                                "equals = khớp toàn bộ; contains = chỉ cần chứa."))));
        out.add(runner("WIDGET_ENABLED", "Control bật/tắt đúng trạng thái", "BLACKBOX",
                "Kiểm tra nút hoặc ô nhập đang enabled hay disabled.",
                List.of(key("targetKey", "Key của control", true, "action.save", null),
                        enums("targetType", "Loại control", true,
                                List.of("button", "input", "checkbox", "switch", "dropdown"), "button", null),
                        bool("expectedEnabled", "Trạng thái mong đợi", true, true, null))));
        out.add(runner("WIDGET_SEMANTICS_LABEL", "Nhãn trợ năng của thành phần", "SCREEN",
                "Đọc semantics label để kiểm tra accessibility.",
                List.of(key("targetKey", "Key của widget", true, "action.menu", null),
                        enums("targetType", "Loại widget", true, TARGET_TYPES, "button", null),
                        text("expectedLabel", "Nhãn mong đợi", true, "Mở menu", null),
                        enums("matchMode", "Cách so khớp", true, MATCH_MODES, "equals", null))));
        out.add(runner("FORM_REQUIRED_FIELDS", "Kiểm tra các ô bắt buộc", "SCREEN",
                "Bấm submit khi form đang trống và kiểm tra từng lỗi xuất hiện.",
                List.of(keys("fieldKeys", "Các ô nhập", true, "field.name,field.email", null),
                        key("submitKey", "Nút submit", true, "action.save", null),
                        keys("errorKeys", "Key lỗi tương ứng", true, "error.name,error.email",
                                "Phải cùng số phần tử và cùng thứ tự với các ô nhập."))));
        out.add(runner("FORM_VALIDATE_FIELDS", "Form báo lỗi khi nhập sai", "SCREEN",
                "Nhập giá trị sai vào từng ô rồi kiểm tra lỗi tương ứng hiện ra.",
                List.of(keys("fieldKeys", "Các ô nhập", true, "field.name,field.email", null),
                        enums("fieldType", "Loại ô nhập", true, FIELD_TYPES, "input", null),
                        values("invalidValues", "Giá trị sai cho từng ô", true, "fieldKeys",
                                "__EMPTY__,email-sai", "Dùng __EMPTY__ cho ô để trống."),
                        key("submitKey", "Nút submit", true, "action.save", null),
                        keys("errorKeys", "Key lỗi tương ứng", true, "error.name,error.email", null))));
        out.add(runner("FORM_SUBMIT", "Gửi form hợp lệ thành công", "SCREEN",
                "Nhập dữ liệu hợp lệ, bấm submit và kiểm tra kết quả.",
                List.of(keys("fieldKeys", "Các ô nhập", true, "field.name,field.email", null),
                        enums("fieldType", "Loại ô nhập", true, FIELD_TYPES, "input", null),
                        values("values", "Giá trị hợp lệ cho từng ô", true, "fieldKeys",
                                "Nguyen Van A,a@gmail.com", null),
                        key("submitKey", "Nút submit", true, "action.save", null),
                        key("resultKey", "Key kết quả sau khi submit", false, "message.success",
                                "Bài không hiện thông báo thì trỏ vào item mới thay vì message.success."),
                        keys("errorKeys", "Key lỗi KHÔNG được xuất hiện", false,
                                "error.name,error.email", null))));
        out.add(runner("FORM_PREFILL", "Form tự điền dữ liệu khi sửa", "SCREEN",
                "Bấm nút sửa rồi đọc nội dung từng ô nhập.",
                List.of(key("editKey", "Nút sửa trên item", true, "action.item.edit", null),
                        keys("fieldKeys", "Các ô nhập", true, "field.name,field.email", null),
                        enums("fieldType", "Loại ô nhập", true, FIELD_TYPES, "input", null),
                        values("expectedValues", "Giá trị mong đợi từng ô", true, "fieldKeys",
                                "Nguyen Van A,a@gmail.com", null))));
        out.add(runner("LIST_VISIBLE", "Danh sách và các mục hiển thị", "SCREEN",
                "Kiểm tra danh sách tồn tại và các item được render.",
                List.of(key("listKey", "Key của danh sách", true, "list.items", null),
                        keys("itemKeys", "Key của các item", true, "item.1,item.2", null))));
        out.add(runner("LIST_ITEM_COUNT", "Danh sách đúng số lượng mục", "SCREEN",
                "Đếm item nằm trong danh sách và so với số mong đợi.",
                List.of(key("listKey", "Key của danh sách", true, "list.items", null),
                        keys("itemKeys", "Key của các item", true, "item.1,item.2,item.3", null),
                        number("expectedCount", "Số item mong đợi", true, 3, 0, null))));
        out.add(runner("BUTTON_ACTION", "Nút bấm tạo ra kết quả", "BLACKBOX",
                "Bấm một nút rồi kiểm tra kết quả xuất hiện.",
                List.of(key("buttonKey", "Nút cần bấm", true, "action.save", null),
                        key("resultKey", "Kết quả mong đợi", true, "message.success", null))));
        out.add(runner("DIALOG_FLOW", "Hộp thoại xác nhận hoạt động đúng", "BLACKBOX",
                "Mở hộp thoại, chọn một lựa chọn rồi kiểm tra kết quả.",
                List.of(key("actionKey", "Nút mở hộp thoại", true, "action.delete", null),
                        key("dialogKey", "Key hộp thoại", true, "dialog.delete", null),
                        key("decisionKey", "Nút quyết định trong hộp thoại", true,
                                "action.delete.cancel", null),
                        key("resultKey", "Kết quả sau quyết định", false, "screen.list", null),
                        key("absentKey", "Key phải biến mất", false, "dialog.delete", null))));
        out.add(runner("NAVIGATION", "Mở đúng màn hình và quay lại", "SCREEN",
                "Bấm mở màn hình đích rồi (tùy chọn) quay lại màn hình gốc.",
                List.of(key("openKey", "Nút/vùng mở màn hình", true, "action.open-detail", null),
                        key("destinationKey", "Key màn hình đích", true, "screen.detail", null),
                        key("backKey", "Nút quay lại", false, "action.back", null),
                        key("homeKey", "Key màn hình gốc", false, "screen.home", null))));
        out.add(runner("STATE_REACTIVE_FLOW", "Giao diện cập nhật sau thao tác", "BLACKBOX",
                "Kiểm tra trạng thái trước và sau một thao tác; không phụ thuộc thư viện state.",
                List.of(key("initialKey", "Trạng thái ban đầu", true, "state.empty", null),
                        key("actionKey", "Thao tác làm đổi state", true, "action.save", null),
                        key("updatedKey", "Trạng thái sau thao tác", true, "state.loaded", null),
                        key("absentKey", "Trạng thái cũ phải mất", true, "state.empty", null))));
        out.add(runner("RESPONSIVE_NO_OVERFLOW", "Không tràn ở mọi kích thước", "RESPONSIVE",
                "Render ở hai kích thước và kiểm tra không có lỗi layout.",
                responsiveSizes(List.of())));
        out.add(runner("RESPONSIVE_TARGET", "Thành phần còn ở cả dọc và ngang", "RESPONSIVE",
                "Ngoài kiểm tra tràn, xác nhận một vùng vẫn tồn tại ở cả hai hướng.",
                responsiveSizes(List.of(
                        key("targetKey", "Key cần còn ở cả hai hướng", true, "screen.home", null),
                        enums("targetType", "Loại widget", true, TARGET_TYPES, "container", null)))));
        out.add(runner("WIDGET_DIMENSION", "Kích thước thành phần đúng yêu cầu", "RESPONSIVE",
                "Đo width hoặc height render thực tế theo logical pixel.",
                List.of(key("targetKey", "Key cần đo", true, "field.avatar", null),
                        enums("targetType", "Loại widget", true, TARGET_TYPES, "image", null),
                        enums("dimension", "Chiều cần đo", true, List.of("width", "height"), "height", null),
                        number("expected", "Giá trị mong đợi (px)", true, 80, 0, null),
                        enums("comparison", "Cách so sánh", true, COMPARISONS, "equals",
                                "equals cho giá trị cố định; at_least/at_most cho giao diện co giãn."),
                        number("tolerance", "Sai số cho phép (px)", true, 0.5, 0, null))));
        out.add(runner("WIDGET_PADDING", "Khoảng cách bên trong đúng yêu cầu", "RESPONSIVE",
                "Đo EdgeInsets của widget Padding có key.",
                List.of(key("targetKey", "Key của Padding", true, "screen.card.padding", null),
                        enums("targetType", "Loại widget", true, List.of("padding"), "padding", null),
                        number("left", "Trái (px)", true, 16, 0, null),
                        number("top", "Trên (px)", true, 12, 0, null),
                        number("right", "Phải (px)", true, 16, 0, null),
                        number("bottom", "Dưới (px)", true, 12, 0, null),
                        number("tolerance", "Sai số cho phép (px)", true, 0.5, 0, null))));
        out.add(runner("WIDGET_TEXT_STYLE", "Cỡ chữ và kiểu chữ đúng yêu cầu", "SCREEN",
                "Đọc fontSize và fontWeight đã resolve của một Text.",
                List.of(key("targetKey", "Key của Text", true, "text.screen.title", null),
                        enums("targetType", "Loại widget", true, List.of("text"), "text", null),
                        number("fontSize", "Cỡ chữ (px)", true, 20, 0.01, null),
                        enums("fontWeight", "Độ đậm", true, FONT_WEIGHTS, "w700", null),
                        number("tolerance", "Sai số cho phép (px)", true, 0.5, 0, null))));
        out.add(runner("WIDGET_GAP", "Khoảng cách giữa hai thành phần", "RESPONSIVE",
                "Đo khoảng cách render thật; dùng thay cho việc ép margin hay padding.",
                List.of(key("fromKey", "Từ widget", true, "field.name", null),
                        enums("fromType", "Loại widget bắt đầu", false, TARGET_TYPES, "input", null),
                        key("toKey", "Đến widget", true, "field.email", null),
                        enums("toType", "Loại widget kết thúc", false, TARGET_TYPES, "input", null),
                        enums("axis", "Trục đo", true, List.of("vertical", "horizontal"), "vertical", null),
                        number("expectedGap", "Khoảng cách mong đợi (px)", true, 12, 0, null),
                        number("tolerance", "Sai số cho phép (px)", true, 0.5, 0, null))));
        return out;
    }

    private static List<Map<String, Object>> responsiveSizes(List<Map<String, Object>> extra) {
        List<Map<String, Object>> params = new ArrayList<>(List.of(
                number("portraitWidth", "Rộng khi dọc (px)", true, 390, 1, null),
                number("portraitHeight", "Cao khi dọc (px)", true, 844, 1, null),
                number("landscapeWidth", "Rộng khi ngang (px)", true, 1024, 1, null),
                number("landscapeHeight", "Cao khi ngang (px)", true, 768, 1, null)));
        params.addAll(extra);
        return params;
    }

    private static Map<String, Object> runner(String runner, String label, String layer,
                                              String description, List<Map<String, Object>> params) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("runner", runner);
        row.put("label", label);
        row.put("layer_default", layer);
        row.put("description", description);
        row.put("parameters", params);
        Map<String, Object> schema = new LinkedHashMap<>();
        for (Map<String, Object> param : params) schema.put(String.valueOf(param.get("name")), param.get("default"));
        row.put("parameters_schema", schema);
        return row;
    }

    private static Map<String, Object> param(String name, String label, String type, boolean required,
                                             Object defaultValue, String hint) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("label", label);
        row.put("type", type);
        row.put("required", required);
        row.put("default", defaultValue);
        if (hint != null) row.put("hint", hint);
        return row;
    }

    private static Map<String, Object> text(String name, String label, boolean required,
                                            String defaultValue, String hint) {
        return param(name, label, "text", required, defaultValue, hint);
    }

    /** Một semantic key duy nhất. */
    private static Map<String, Object> key(String name, String label, boolean required,
                                           String defaultValue, String hint) {
        return param(name, label, "semantic_key", required, defaultValue, hint);
    }

    /** Danh sách semantic key ngăn cách bằng dấu phẩy. */
    private static Map<String, Object> keys(String name, String label, boolean required,
                                            String defaultValue, String hint) {
        return param(name, label, "semantic_keys", required, defaultValue, hint);
    }

    /** Danh sách giá trị ghép cặp 1-1 với một tham số danh sách key khác. */
    private static Map<String, Object> values(String name, String label, boolean required,
                                              String pairWith, String defaultValue, String hint) {
        Map<String, Object> row = param(name, label, "values", required, defaultValue, hint);
        row.put("pair_with", pairWith);
        return row;
    }

    private static Map<String, Object> number(String name, String label, boolean required,
                                              double defaultValue, double min, String hint) {
        Map<String, Object> row = param(name, label, "number", required, defaultValue, hint);
        row.put("min", min);
        return row;
    }

    private static Map<String, Object> bool(String name, String label, boolean required,
                                            boolean defaultValue, String hint) {
        return param(name, label, "bool", required, defaultValue, hint);
    }

    private static Map<String, Object> enums(String name, String label, boolean required,
                                             List<String> options, String defaultValue, String hint) {
        Map<String, Object> row = param(name, label, "enum", required, defaultValue, hint);
        row.put("options", options);
        return row;
    }
}
