package com.example.grader.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Hợp đồng bài làm (Khu vực 0): quy ước giữa đề thi và bài nộp về cách nhận diện
 * thành phần giao diện.
 *
 * Trước đây engine dò cứng (list = ListView, item = ListTile, nút = nút có chữ). Bài làm
 * đúng đề nhưng dùng GridView/Card/nút icon bị chấm trượt oan. Ở đây giáo viên khai rõ
 * từng semantic key được dò thế nào; engine đọc contract.json thay vì đoán.
 *
 * Danh sách chiến lược và tên nhóm icon PHẢI khớp _contractFinder/_iconGroups trong
 * common-testcase-engine/exam_test.dart.
 */
final class TestcaseContractSupport {

    private TestcaseContractSupport() {}

    static final int SCHEMA_VERSION = 1;
    private static final int MAX_KEYS = 200;
    private static final int MAX_PACKAGES = 60;
    private static final Pattern KEY_PATTERN =
            Pattern.compile("[a-z][a-zA-Z0-9_-]*(\\.[a-zA-Z0-9_-]+)+");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final List<String> DEFAULT_ALLOWED_PACKAGES = List.of(
            "flutter", "flutter_test", "flutter_riverpod", "riverpod", "riverpod_annotation",
            "path", "sqflite", "sqflite_common", "sqflite_common_ffi",
            "sqflite_common_ffi_web", "path_provider", "sembast_web", "image_picker", "intl");

    /** value = mô tả cho giáo viên; key = giá trị lưu trong contract.json. */
    private static final Map<String, String> STRATEGIES = new LinkedHashMap<>();
    /** Chiến lược cần điền ô "giá trị". */
    private static final Set<String> NEEDS_VALUE =
            Set.of("widget_type", "icon", "tooltip", "text", "button_text", "type_with_text");
    private static final Set<String> ICON_GROUPS = new LinkedHashSet<>(List.of(
            "edit", "delete", "add", "save", "back", "forward", "close",
            "search", "person", "email", "image", "menu"));

    static {
        STRATEGIES.put("auto", "Dò theo quy tắc mặc định của hệ thống");
        STRATEGIES.put("key_only", "Không dò thay thế — thiếu key là không tìm thấy");
        STRATEGIES.put("widget_type", "Theo loại widget (Scaffold, SliverGrid, Card, TextField…)");
        STRATEGIES.put("icon", "Theo icon (nút chỉ có icon, không có chữ)");
        STRATEGIES.put("tooltip", "Theo tooltip của nút");
        STRATEGIES.put("text", "Theo nội dung chữ hiển thị");
        STRATEGIES.put("button_text", "Theo nút chứa chữ");
        STRATEGIES.put("type_with_text", "Widget loại X có chứa chữ Y");
    }

    /** Bộ key gợi ý sẵn, kèm cách dò mặc định đúng với heuristic cũ của engine. */
    static List<Map<String, Object>> defaultKeys() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(key("screen.home", "Màn hình chính", "widget_type", "Scaffold", 0));
        out.add(key("screen.detail", "Màn hình chi tiết", "text", "User Detail", 0));
        out.add(key("field.name", "Ô nhập họ tên", "widget_type", "TextField", 0));
        out.add(key("field.email", "Ô nhập email", "widget_type", "TextField", 1));
        out.add(key("action.save", "Nút thêm/lưu", "button_text",
                "/^(add|create|save|submit|update|thêm|tạo|lưu|cập nhật)( user)?$/", 0));
        out.add(key("action.item.edit", "Nút sửa trên item", "icon", "edit", 0));
        out.add(key("action.delete", "Nút xóa trên item", "icon", "delete", 0));
        out.add(key("action.delete.cancel", "Nút hủy trong hộp thoại", "button_text",
                "/^(cancel|no|hủy|đóng)$/", 0));
        out.add(key("action.back", "Nút quay lại", "button_text", "/^(back|quay lại|trở về)$/", 0));
        out.add(key("action.open-detail", "Vùng bấm mở chi tiết", "widget_type", "ListTile", 0));
        out.add(key("list.items", "Danh sách", "widget_type", "ListView", 0));
        out.add(key("item.1", "Item thứ nhất", "widget_type", "ListTile", 0));
        out.add(key("item.2", "Item thứ hai", "widget_type", "ListTile", 1));
        out.add(key("dialog.delete", "Hộp thoại xác nhận xóa", "widget_type", "AlertDialog", 0));
        out.add(key("message.success", "Thông báo thành công", "text",
                "/success|thành công|đã thêm|đã cập nhật|đã lưu/", 0));
        out.add(key("state.empty", "Trạng thái danh sách rỗng", "text",
                "/no users|empty|chưa có|không có/", 0));
        out.add(key("state.loaded", "Trạng thái đã có dữ liệu", "widget_type", "ListTile", 0));
        out.add(key("error.name", "Lỗi ô họ tên", "text", "/name.*(required|bắt buộc|tối thiểu)/", 0));
        out.add(key("error.email", "Lỗi ô email", "text", "/email.*(required|invalid|bắt buộc|không hợp lệ)/", 0));
        out.add(key("text.screen.title", "Tiêu đề màn hình", "widget_type", "AppBar", 0));
        return out;
    }

    private static Map<String, Object> key(String key, String label, String strategy,
                                           String value, int index) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("required", false);
        row.put("strategy", strategy);
        row.put("value", value);
        row.put("index", index);
        return row;
    }

    static Map<String, Object> catalog() {
        List<Map<String, Object>> strategies = new ArrayList<>();
        STRATEGIES.forEach((code, label) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("label", label);
            row.put("needs_value", NEEDS_VALUE.contains(code));
            row.put("needs_text", "type_with_text".equals(code));
            row.put("uses_index", !"auto".equals(code) && !"key_only".equals(code));
            strategies.add(row);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", SCHEMA_VERSION);
        out.put("strategies", strategies);
        out.put("icon_groups", ICON_GROUPS);
        out.put("default_keys", defaultKeys());
        out.put("default_allowed_packages", DEFAULT_ALLOWED_PACKAGES);
        out.put("common_widget_types", List.of("Scaffold", "AppBar", "ListView", "GridView",
                "SliverList", "SliverGrid", "CustomScrollView", "ListTile", "Card", "InkWell",
                "TextField", "TextFormField", "Form", "ElevatedButton", "FilledButton",
                "OutlinedButton", "TextButton", "IconButton", "FloatingActionButton",
                "AlertDialog", "Image", "Icon", "Checkbox", "Switch", "Padding", "Text"));
        return out;
    }

    /**
     * Chuẩn hóa + kiểm tra hợp đồng trước khi lưu. Sai ở đây chỉ mất công sửa; sai lúc chấm
     * là cả lớp bị điểm oan, nên chặn chặt tay.
     */
    static Map<String, Object> normalize(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema_version", SCHEMA_VERSION);
        // Bộ mới đi theo contract hybrid: UI dùng ValueKey để trỏ đúng widget, logic dùng
        // public starter contract. Đề cũ đã lưu false vẫn giữ nguyên qua nhánh đọc bên dưới.
        out.put("require_keys", true);
        out.put("keys", List.of());
        out.put("allowed_packages", DEFAULT_ALLOWED_PACKAGES);
        out.put("local_package_names", List.of());
        if (!(raw instanceof Map<?, ?> map)) return out;

        Object requireKeys = map.get("require_keys");
        out.put("require_keys", requireKeys instanceof Boolean b ? b
                : Boolean.parseBoolean(String.valueOf(requireKeys)));

        out.put("allowed_packages", normalizePackages(
                map.get("allowed_packages"), DEFAULT_ALLOWED_PACKAGES, "package được phép"));
        out.put("local_package_names", normalizePackages(
                map.get("local_package_names"), List.of(), "tên package nội bộ"));

        Object rawKeys = map.get("keys");
        if (!(rawKeys instanceof List<?> list)) return out;
        if (list.size() > MAX_KEYS)
            throw new IllegalArgumentException("Hợp đồng bài làm tối đa " + MAX_KEYS + " key.");

        List<Map<String, Object>> keys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object rawRow : list) {
            if (!(rawRow instanceof Map<?, ?> rowMap)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            rowMap.forEach((k, v) -> row.put(String.valueOf(k), v));

            String key = text(row.get("key"));
            if (key == null || key.isBlank()) continue;
            key = key.trim();
            if (!KEY_PATTERN.matcher(key).matches())
                throw new IllegalArgumentException("Semantic key không hợp lệ: " + key
                        + " (dạng đúng: nhom.ten, ví dụ field.email)");
            if (!seen.add(key)) throw new IllegalArgumentException("Trùng semantic key: " + key);

            String strategy = text(row.get("strategy"), "auto").trim();
            if (!STRATEGIES.containsKey(strategy))
                throw new IllegalArgumentException("Cách dò không hợp lệ ở " + key + ": " + strategy);

            String value = text(row.get("value"), "").trim();
            if (NEEDS_VALUE.contains(strategy) && value.isEmpty())
                throw new IllegalArgumentException("Key " + key + " dùng cách dò \""
                        + STRATEGIES.get(strategy) + "\" nên phải nhập giá trị.");
            if ("icon".equals(strategy) && !ICON_GROUPS.contains(value) && !isNumber(value))
                throw new IllegalArgumentException("Key " + key + ": nhóm icon \"" + value
                        + "\" không có. Chọn một trong " + String.join(", ", ICON_GROUPS) + ".");

            String withText = text(row.get("text"), "").trim();
            if ("type_with_text".equals(strategy) && withText.isEmpty())
                throw new IllegalArgumentException("Key " + key + " phải nhập chữ cần chứa.");

            int index = (int) number(row.get("index"), 0);
            if (index < 0) throw new IllegalArgumentException("Thứ tự ở " + key + " không hợp lệ.");

            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("key", key);
            normalized.put("label", text(row.get("label"), key).trim());
            normalized.put("required", row.get("required") instanceof Boolean b ? b
                    : Boolean.parseBoolean(String.valueOf(row.get("required"))));
            normalized.put("strategy", strategy);
            if (!value.isEmpty()) normalized.put("value", value);
            if (!withText.isEmpty()) normalized.put("text", withText);
            normalized.put("index", index);
            // Cho phép một vài key vẫn được dò dự phòng dù đề bắt buộc gắn ValueKey.
            if (Boolean.parseBoolean(String.valueOf(row.get("allow_fallback"))))
                normalized.put("allow_fallback", true);
            keys.add(normalized);
        }
        out.put("keys", keys);
        return out;
    }

    private static List<String> normalizePackages(Object raw, List<String> fallback, String label) {
        if (raw == null) return List.copyOf(fallback);
        List<?> values = raw instanceof List<?> list
                ? list : List.of(String.valueOf(raw).split(","));
        if (values.size() > MAX_PACKAGES)
            throw new IllegalArgumentException("Tối đa " + MAX_PACKAGES + " " + label + ".");
        Set<String> unique = new LinkedHashSet<>();
        for (Object value : values) {
            String name = value == null ? "" : String.valueOf(value).trim();
            if (name.isEmpty()) continue;
            if (!PACKAGE_PATTERN.matcher(name).matches())
                throw new IllegalArgumentException("Tên package không hợp lệ: " + name);
            unique.add(name);
        }
        return List.copyOf(unique);
    }

    static boolean isEmpty(Map<String, Object> contract) {
        if (contract == null) return true;
        Object keys = contract.get("keys");
        return !(keys instanceof List<?> list) || list.isEmpty();
    }

    /** Đoạn "Yêu cầu kỹ thuật" để dán vào đề thi. */
    static String renderRequirements(Map<String, Object> contract) {
        List<Map<String, Object>> keys = keyRows(contract);
        StringBuilder out = new StringBuilder();
        out.append("## Quy ước định danh giao diện (bắt buộc đọc)\n\n");
        if (Boolean.TRUE.equals(contract.get("require_keys"))) {
            out.append("Bài làm **bắt buộc** gắn `ValueKey` đúng tên dưới đây cho từng thành phần. ")
               .append("Thiếu key thì phần chức năng tương ứng KHÔNG được tính điểm.\n\n");
        } else {
            out.append("Nên gắn `ValueKey` đúng tên dưới đây. Nếu không gắn, hệ thống sẽ nhận diện theo ")
               .append("cách mô tả ở cột \"Khi không gắn key\" — gắn key vẫn là cách chắc chắn nhất.\n\n");
        }
        out.append("| Key | Thành phần | Bắt buộc | Khi không gắn key |\n");
        out.append("|---|---|---|---|\n");
        for (Map<String, Object> row : keys) {
            out.append("| `").append(row.get("key")).append("` | ")
               .append(text(row.get("label"), "")).append(" | ")
               .append(Boolean.TRUE.equals(row.get("required")) ? "Có" : "Không").append(" | ")
               .append(describeStrategy(row)).append(" |\n");
        }
        out.append("\nVí dụ gắn key:\n\n```dart\n")
           .append("TextFormField(key: const ValueKey('field.email'), ...)\n")
           .append("ElevatedButton(key: const ValueKey('action.save'), ...)\n")
           .append("```\n\n")
           .append("Hệ thống chấm CHỈ nhìn vào chuỗi bên trong key. Bạn tự do đặt tên class, tên biến,\n")
           .append("cách chia widget và chọn loại widget — miễn là chuỗi key đúng như bảng trên.\n")
           .append("`const ValueKey('field.email')` và `const Key('field.email')` là như nhau;\n")
           .append("KHÔNG dùng `ObjectKey`, `GlobalKey` hay key ghép chuỗi động.\n");
        List<String> allowedPackages = stringRows(contract.get("allowed_packages"));
        List<String> localPackages = stringRows(contract.get("local_package_names"));
        out.append("\n## Quy ước package\n\n")
           .append("Bài chỉ được dùng các package sau: `")
           .append(String.join("`, `", allowedPackages)).append("`.\n")
           .append("Nếu dùng package khác, hệ thống sẽ dừng chấm tự động và chuyển bài sang **Cần chấm tay**, không tự cho 0 điểm.\n");
        if (!localPackages.isEmpty()) {
            out.append("Tên package nội bộ của starter được chấp nhận: `")
               .append(String.join("`, `", localPackages)).append("`.\n");
        }
        return out.toString();
    }

    /**
     * Đoạn Dart phát kèm starter — TÙY CHỌN. Engine chỉ so chuỗi trong key nên sinh viên
     * viết thẳng {@code const ValueKey('field.email')} cũng đúng. File này chỉ để tránh gõ
     * sai chính tả tên key, vì sai một ký tự là mất điểm dù chức năng làm đúng.
     */
    static String renderStarterDart(Map<String, Object> contract) {
        StringBuilder out = new StringBuilder();
        out.append("import 'package:flutter/widgets.dart';\n\n")
           .append("/// TÙY CHỌN — chỉ để tránh gõ sai tên key. Viết thẳng\n")
           .append("/// const ValueKey('field.email') trong widget cũng được tính điểm như nhau.\n")
           .append("/// Khóa định danh giao diện do đề thi quy định — KHÔNG đổi chuỗi bên trong.\n")
           .append("class ExamKeys {\n")
           .append("  const ExamKeys._();\n\n");
        for (Map<String, Object> row : keyRows(contract)) {
            String key = String.valueOf(row.get("key"));
            out.append("  /// ").append(text(row.get("label"), key)).append('\n')
               .append("  static const ").append(dartName(key))
               .append(" = ValueKey('").append(key).append("');\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static String describeStrategy(Map<String, Object> row) {
        String strategy = text(row.get("strategy"), "auto");
        String value = text(row.get("value"), "");
        int index = (int) number(row.get("index"), 0);
        String order = index > 0 ? " (thứ " + (index + 1) + ")" : "";
        return switch (strategy) {
            case "key_only" -> "Không dò thay thế — bắt buộc gắn ValueKey";
            case "widget_type" -> "Widget `" + value + "`" + order;
            case "icon" -> "Nút có icon nhóm \"" + value + "\"" + order;
            case "tooltip" -> "Nút có tooltip \"" + value + "\"" + order;
            case "text" -> "Chữ hiển thị `" + value + "`" + order;
            case "button_text" -> "Nút chứa chữ `" + value + "`" + order;
            case "type_with_text" -> "Widget `" + value + "` chứa chữ `"
                    + text(row.get("text"), "") + "`" + order;
            default -> "Dò theo quy tắc mặc định";
        };
    }

    /** field.email → fieldEmail; action.open-detail → actionOpenDetail. */
    private static String dartName(String key) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '.' || c == '-' || c == '_') { upper = true; continue; }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        String name = out.toString();
        return name.isEmpty() || Character.isDigit(name.charAt(0)) ? "k" + name : name;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> keyRows(Map<String, Object> contract) {
        Object keys = contract == null ? null : contract.get("keys");
        return keys instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static boolean isNumber(String value) {
        try { Integer.parseInt(value); return true; } catch (Exception e) { return false; }
    }

    private static List<String> stringRows(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }

    private static String text(Object value, String fallback) {
        String s = text(value);
        return s == null || s.isBlank() ? fallback : s;
    }

    private static double number(Object value, double fallback) {
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }
}
