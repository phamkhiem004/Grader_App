package com.example.grader.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dựng lại cấu hình builder ({@code contract} + {@code items}) từ CHÍNH các file testcase trên đĩa.
 *
 * <p>Vì sao cần: bộ testcase nhập bằng ZIP không có {@code testcase_config_json}, nên trước đây
 * không Sửa được và không Clone được — dù nội dung của nó vẫn do đúng engine này sinh ra. Ba file
 * đã lưu chứa đủ thông tin để dựng lại:
 * <ul>
 *   <li>{@code skills_matrix.json} — template_id, tham số, điểm, kỹ năng của từng testcase;</li>
 *   <li>{@code contract.json} — hợp đồng Khu vực 0 (cách nhận diện widget);</li>
 *   <li>{@code exam_test.dart} — thân code của testcase "Tự viết code" (matrix không chứa).</li>
 * </ul>
 *
 * <p>Nguyên tắc: KHÔNG đoán. Thiếu gì thì báo bằng cảnh báo để giáo viên biết phần nào chưa khôi
 * phục được, thay vì lặng lẽ tạo ra một bộ testcase khác với bộ đang chấm.
 */
final class TestcaseConfigRecovery {

    private TestcaseConfigRecovery() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Khớp đúng khối code mà {@code TestcaseTemplateService} sinh cho từng testcase tự viết. */
    private static final Pattern CUSTOM_BLOCK = Pattern.compile(
            "case '([^']+)':\\s*\\R\\s*testWidgets\\('\\1', \\(tester\\) async \\{\\s*\\R"
                    + "\\s*_stage\\('TESTCASE_CUSTOM_CODE'\\);\\s*\\R"
                    + "(.*?)\\R\\s*\\}\\);\\s*\\R\\s*return;",
            Pattern.DOTALL);

    record Recovered(Map<String, Object> config, List<String> warnings) {}

    /**
     * Có dựng lại được cấu hình builder từ thư mục này không? Dùng cho danh sách Kho đề, nơi phải
     * trả lời cho hàng chục bộ mỗi lần tải trang nên không chạy hẳn quy trình dựng lại.
     *
     * <p>Dấu hiệu quyết định là {@code template_id}: matrix do engine này sinh luôn có, còn
     * testcase viết tay thì không — và không có nó thì không thể dựng lại instance nào.
     */
    static boolean canRecover(Path dir) {
        if (dir == null) return false;
        Path matrixFile = dir.resolve("skills_matrix.json");
        if (!Files.exists(matrixFile)) return false;
        try {
            return Files.readString(matrixFile, StandardCharsets.UTF_8).contains("\"template_id\"");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param dir thư mục testcase của đề (nơi chứa skills_matrix.json)
     * @return null nếu không có gì để khôi phục (không có skills_matrix.json hoặc file hỏng)
     */
    static Recovered recover(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        Path matrixFile = dir.resolve("skills_matrix.json");
        if (!Files.exists(matrixFile)) return null;

        Map<String, Object> matrix;
        try {
            matrix = MAPPER.readValue(Files.readString(matrixFile, StandardCharsets.UTF_8),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return null;   // file không đọc được → coi như không khôi phục được
        }

        List<String> warnings = new ArrayList<>();
        Map<String, String> customCode = readCustomCode(dir, warnings);

        List<Map<String, Object>> items = new ArrayList<>();
        int order = 1;
        for (Map.Entry<String, Object> entry : matrix.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> raw)) continue;
            Map<String, Object> row = cast(raw);
            // Nhóm testcase: matrix lưu cha + các con; cấu hình builder chỉ giữ các CON,
            // gắn group_id/group_name để màn Sửa dựng lại đúng nhóm.
            if (row.get("children") instanceof List<?> children && !children.isEmpty()) {
                String groupId = text(row.get("group_id"), entry.getKey());
                String groupName = text(row.get("group_name"), text(row.get("name"), groupId));
                for (Object child : children) {
                    if (!(child instanceof Map<?, ?> childRow)) continue;
                    Map<String, Object> item = toItem(cast(childRow), order++, customCode, warnings);
                    if (item == null) continue;
                    item.put("group_id", groupId);
                    item.put("group_name", groupName);
                    items.add(item);
                }
                continue;
            }
            Map<String, Object> item = toItem(row, order++, customCode, warnings);
            if (item != null) items.add(item);
        }

        if (items.isEmpty()) return null;

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("contract", readContract(dir, warnings));
        config.put("items", items);
        return new Recovered(config, warnings);
    }

    /** Một dòng matrix → một item của cấu hình builder. */
    private static Map<String, Object> toItem(Map<String, Object> row, int order,
                                              Map<String, String> customCode, List<String> warnings) {
        String instanceId = text(row.get("instance_id"), null);
        String templateId = text(row.get("template_id"), null);
        if (instanceId == null) return null;
        if (templateId == null) {
            warnings.add("Bỏ qua \"" + instanceId + "\": dòng trong skills_matrix.json không ghi template_id.");
            return null;
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("instance_id", instanceId);
        item.put("template_id", templateId);
        item.put("runner", text(row.get("runner"), ""));
        item.put("skill_code", text(row.get("skill_code"), ""));
        item.put("testcase_group", text(row.get("testcase_group"), null));
        item.put("name", text(row.get("name"), instanceId));
        item.put("description", text(row.get("description"), ""));
        item.put("difficulty", text(row.get("difficulty"), "basic"));
        item.put("weight", number(row.get("weight"), 1));
        item.put("parameters", row.get("parameters") instanceof Map<?, ?> p
                ? cast(p) : new LinkedHashMap<String, Object>());
        item.put("enabled", true);       // matrix chỉ chứa testcase đang bật
        item.put("order", order);
        // Giữ NGUYÊN VĂN expected đã phát hành: đánh dấu custom để lúc lưu lại không bị
        // sinh đè bằng expected mặc định của template.
        String expected = text(row.get("expected"), null);
        if (expected != null) {
            item.put("expected", expected);
            item.put("expected_custom", true);
        }

        if (Boolean.TRUE.equals(row.get("generated_custom"))) item.put("generated_custom", true);
        String code = customCode.get(instanceId);
        if (code != null) {
            item.put("custom_code", code);
        } else if ("CUSTOM_CODE".equals(item.get("runner")) && !Boolean.TRUE.equals(row.get("generated_custom"))) {
            warnings.add("Testcase tự viết code \"" + instanceId
                    + "\" không đọc lại được phần code từ exam_test.dart — hãy dán lại code trước khi lưu.");
        }
        return item;
    }

    /** Hợp đồng Khu vực 0; thiếu file thì trả hợp đồng rỗng chứ không chặn khôi phục. */
    private static Map<String, Object> readContract(Path dir, List<String> warnings) {
        Path file = dir.resolve("contract.json");
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            warnings.add("Không đọc được contract.json nên phần Cấu hình bài làm đang để trống.");
            return new LinkedHashMap<>();
        }
    }

    /**
     * Bóc thân code của các testcase "Tự viết code" từ exam_test.dart. Khối code do chính hệ thống
     * sinh theo khuôn cố định nên bóc được chính xác; mỗi dòng bị thụt vào 8 khoảng trắng lúc sinh.
     */
    private static Map<String, String> readCustomCode(Path dir, List<String> warnings) {
        Map<String, String> out = new LinkedHashMap<>();
        Path file = dir.resolve("exam_test.dart");
        if (!Files.exists(file)) return out;
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            warnings.add("Không đọc được exam_test.dart nên chưa lấy lại được code testcase tự viết.");
            return out;
        }
        Matcher m = CUSTOM_BLOCK.matcher(source);
        while (m.find()) {
            String body = m.group(2);
            StringBuilder code = new StringBuilder();
            for (String line : body.split("\\R", -1)) {
                if (code.length() > 0) code.append('\n');
                code.append(line.startsWith("        ") ? line.substring(8) : line.stripLeading());
            }
            out.put(m.group(1), code.toString().stripTrailing());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private static String text(Object value, String fallback) {
        if (value == null) return fallback;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? fallback : s;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
