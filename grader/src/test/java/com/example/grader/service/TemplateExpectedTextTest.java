package com.example.grader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ĐƯỜNG SOẠN ĐỀ, không phải đường chấm.
 *
 * <p>Bộ fixture đo `actual` — thứ engine phát ra lúc chấm. Nhưng `expected` của fixture là JSON
 * GÕ TAY, nên đường MÁY SINH (`expected_template` + tham số → câu tiếng Việt) chưa từng có một
 * phép đo nào: 20/22 template nhét thẳng semantic key vào `expected` suốt thời gian đó mà không
 * gì đỏ. Giáo viên để trống ô expected là dính bản mặc định ấy, và `expected` đi vào prompt của
 * bot sinh nhận xét — gửi hàng loạt.
 *
 * <p>Nên test này áp cho `expected` máy sinh đúng những luật ACCEPTANCE áp cho `actual`:
 * C3 (không lộ khoá nội bộ) và C4 (không có từ vựng của bộ chấm).
 *
 * <p>Phạm vi đã đo, nói rõ để không phát biểu rộng hơn phép đo: chỉ phần MÁY SINH. Câu giảng viên
 * tự gõ vào ô expected đi thẳng, Grader chỉ bảo đảm nguyên văn — xem SPEC.
 */
class TemplateExpectedTextTest {

    private static final Path FIXTURE = Path.of("..", "fixtures", "result-json-v2");
    private static final Path FRONTEND_PAGE =
            Path.of("..", "frontend", "app", "teacher", "testcases", "page.tsx");

    /** Tham số mang nội dung GIẢNG VIÊN GÕ — không thuộc phạm vi bảo đảm của phần máy sinh. */
    static final Set<String> FREE_TEXT_PARAMS = Set.of(
            "expectedText", "expectedLabel", "expectedValues", "values", "invalidValues");
    private static final String FREE_TEXT_MASK = "«nội dung giảng viên nhập»";

    /**
     * C3 cho `expected`: semantic key có dạng chấm-phân-cách (`action.delete.1`, `screen.home`).
     * Luật C3 của verify_result.py bắt hình dạng log Flutter (`key [<…>]`); ở đây khoá tới thẳng
     * từ tham số nên phải bắt đúng hình dạng tham số.
     */
    private static final Pattern DOTTED_KEY = Pattern.compile(
            "(?<![\\w@.])[a-zA-Z][\\w-]*(\\.[\\w-]+)+(?![\\w@])");

    /**
     * MIỄN TRỪ HẸP của C3 — tên file mã nguồn. `public_contract.dart` khớp hình dạng "chữ.chữ"
     * của DOTTED_KEY nhưng KHÔNG phải khoá chấm: nói tên file cho sinh viên là ngôn ngữ bình
     * thường của đề bài ("cài `validateInput` trong `lib/domain/public_contract.dart`"), khác hẳn
     * việc để lọt `action.save` — thứ sinh viên không có cách nào hiểu.
     *
     * <p>Chỉ miễn đúng các đuôi file liệt kê dưới đây; mọi hình dạng chấm khác vẫn bị bắt như cũ.
     * Không có khoá nào trong ngữ pháp khoá kết thúc bằng các đuôi này nên miễn trừ không mở
     * đường cho khoá thật lọt qua.
     */
    private static final Pattern SOURCE_FILE_NAME = Pattern.compile(
            "(?i)[\\w-]+\\.(dart|json|ya?ml|md|txt)");

    /** C4 cho `expected`: từ vựng của bộ chấm/Flutter. `px` là ký hiệu đơn vị, không tính. */
    private static final List<String> ENGLISH_MARKERS = List.of(
            "widget", "target", "key", "render", "overflow", "renderflex", "portrait", "landscape",
            "submit", "prefill", "semantics", "label", "enabled", "equals", "contains", "state",
            "fontsize", "fontweight", "padding", "exception", "main()", "__empty__", "control",
            "field", "item", "list", "dialog", "form", "text", "button", "container", "at_least",
            "at_most", "match", "assert", "test");

    private static List<JsonNode> templates() throws Exception {
        try (InputStream in = TemplateExpectedTextTest.class
                .getResourceAsStream("/common-testcase-templates.json")) {
            assertNotNull(in, "Không tìm thấy thư viện template trên classpath");
            List<JsonNode> out = new ArrayList<>();
            new ObjectMapper().readTree(in).forEach(out::add);
            // Đọc hỏng ⇒ tập rỗng ⇒ mọi phép kiểm dưới đạt vô nghĩa (bài học của A2b).
            assertTrue(out.size() >= 20, "Chỉ đọc được " + out.size() + " template");
            return out;
        }
    }

    private static TestcaseTemplateService loadedService() {
        TestcaseTemplateService service = new TestcaseTemplateService();
        service.loadTemplates();   // nạp cả thư viện template lẫn từ điển giá trị
        return service;
    }

    private static Map<String, Object> paramsOf(JsonNode template) {
        Map<String, Object> out = new LinkedHashMap<>();
        template.path("parameters_schema").properties().forEach(e -> out.put(e.getKey(),
                e.getValue().isNumber() ? e.getValue().numberValue()
                        : e.getValue().isBoolean() ? e.getValue().booleanValue()
                        : e.getValue().asText()));
        return out;
    }

    /** Che nội dung giảng viên gõ để chỉ soi phần Grader tự dựng. */
    private static Map<String, Object> masked(Map<String, Object> params) {
        Map<String, Object> out = new LinkedHashMap<>(params);
        for (String key : FREE_TEXT_PARAMS) if (out.containsKey(key)) out.put(key, FREE_TEXT_MASK);
        return out;
    }

    /** Luật C3/C4 áp cho `expected` — dùng chung với {@link MachineExpectedSampleTest}. */
    static List<String> violations(String id, String text) {
        List<String> bad = new ArrayList<>();
        Matcher key = DOTTED_KEY.matcher(text);
        while (key.find()) {
            if (SOURCE_FILE_NAME.matcher(key.group()).matches()) continue;   // tên file, không phải khoá
            bad.add(id + ": lộ khoá '" + key.group() + "' — " + text);
        }
        String lower = text.toLowerCase();
        for (String marker : ENGLISH_MARKERS) {
            if (lower.contains(marker)) bad.add(id + ": từ vựng bộ chấm '" + marker + "' — " + text);
        }
        if (text.contains("{") || text.contains("}"))
            bad.add(id + ": còn chỗ thay thế chưa điền — " + text);
        return bad;
    }

    /**
     * (1) Bản thân 22 chuỗi template: cấm chỗ thay thế `{…Key}` và cấm từ vựng tiếng Anh.
     *
     * <p>Đây là phép kiểm TĨNH — bắt lỗi ngay lúc ai đó thêm template mới, không cần render.
     */
    @Test
    void noExpectedTemplateLeaksAKeyPlaceholderOrEnglishWord() throws Exception {
        List<String> bad = new ArrayList<>();
        for (JsonNode t : templates()) {
            String id = t.path("template_id").asText();
            String template = t.path("expected_template").asText("");
            assertTrue(!template.isBlank(), id + " thiếu expected_template");

            Matcher m = Pattern.compile("\\{(\\w+)}").matcher(template);
            Set<String> placeholders = new LinkedHashSet<>();
            while (m.find()) placeholders.add(m.group(1));
            for (String name : placeholders) {
                if (name.toLowerCase().endsWith("key") || name.toLowerCase().endsWith("keys"))
                    bad.add(id + ": chỗ thay thế khoá {" + name + "} — khoá sẽ đi thẳng vào expected");
                if (!t.path("parameters_schema").has(name))
                    bad.add(id + ": {" + name + "} không có trong parameters_schema — sẽ in ra nguyên xi");
            }
            // Chữ ngoài chỗ thay thế phải là tiếng Việt: bỏ chỗ thay thế rồi mới soi.
            String prose = template.replaceAll("\\{\\w+}", " ");
            String lower = prose.toLowerCase();
            for (String marker : ENGLISH_MARKERS) {
                if (lower.contains(marker)) bad.add(id + ": từ vựng bộ chấm '" + marker + "' — " + template);
            }
        }
        assertTrue(bad.isEmpty(), "expected_template chưa sạch:\n  " + String.join("\n  ", bad));
    }

    /**
     * (2) Chạy ĐÚNG hàm dựng câu của đường soạn đề, với tham số mặc định mà giáo viên nhận được
     * khi kéo template vào đề — rồi áp C3/C4 lên kết quả.
     */
    @Test
    void renderingEveryTemplateWithItsDefaultParametersStaysClean() throws Exception {
        TestcaseTemplateService service = loadedService();
        List<String> bad = new ArrayList<>();
        for (JsonNode t : templates()) {
            String id = t.path("template_id").asText();
            String rendered = service.renderExpected(t.path("expected_template").asText(""),
                    masked(paramsOf(t)));
            bad.addAll(violations(id, rendered.replace(FREE_TEXT_MASK, "")));
        }
        assertTrue(bad.isEmpty(), "expected máy sinh còn rác:\n  " + String.join("\n  ", bad));
    }

    /**
     * (3) Tham số THẬT của một đề thật: 25 dòng trong `skills_matrix.json` của fixture. Tham số
     * mặc định là bản đẹp nhất — giáo viên thật đặt khoá riêng (`action.delete.1`, `icon.header`),
     * và chính những giá trị đó mới là thứ từng lọt vào báo cáo.
     */
    @Test
    void renderingWithRealExamParametersStaysClean() throws Exception {
        Path matrix = FIXTURE.resolve("exam").resolve("skills_matrix.json");
        assertTrue(Files.exists(matrix), "Không thấy skills_matrix.json của fixture: " + matrix);
        JsonNode rows = new ObjectMapper().readTree(Files.readString(matrix, StandardCharsets.UTF_8));

        Map<String, JsonNode> byRunner = new LinkedHashMap<>();
        for (JsonNode t : templates()) byRunner.put(t.path("runner").asText(), t);

        TestcaseTemplateService service = loadedService();
        List<String> bad = new ArrayList<>();
        int measured = 0;
        for (Map.Entry<String, JsonNode> row : rows.properties()) {
            JsonNode template = byRunner.get(row.getValue().path("runner").asText());
            if (template == null) continue;      // GROUP: câu dựng từ các con, không có template
            Map<String, Object> params = new LinkedHashMap<>(paramsOf(template));
            row.getValue().path("parameters").properties()
                    .forEach(e -> params.put(e.getKey(), e.getValue().isNumber()
                            ? e.getValue().numberValue()
                            : e.getValue().isBoolean() ? e.getValue().booleanValue()
                            : e.getValue().asText()));
            String rendered = service.renderExpected(
                    template.path("expected_template").asText(""), masked(params));
            bad.addAll(violations(row.getKey(), rendered.replace(FREE_TEXT_MASK, "")));
            measured++;
        }
        assertTrue(measured >= 20, "Chỉ đo được " + measured + " dòng — fixture có thể đã đổi hình dạng");
        assertTrue(bad.isEmpty(), "expected máy sinh còn rác trên tham số thật:\n  "
                + String.join("\n  ", bad));
    }

    /**
     * (4) Bản SONG SINH ở frontend phải tra cùng từ điển.
     *
     * <p>Chuỗi FE dựng mới là chuỗi được lưu — backend chỉ dùng bản của nó khi FE gửi rỗng. Hai
     * bên lệch thì bản máy sinh của FE bị ghi nhận nhầm thành "giáo viên tự gõ" (`expected_custom`)
     * và không ai thấy. Đúng bẫy "sửa một nơi là vô hiệu" của `student_safe_summary`.
     */
    @Test
    void frontendRenderExpectedStillPassesTheVocabulary() throws Exception {
        assertTrue(Files.exists(FRONTEND_PAGE), "Không thấy trang soạn testcase: " + FRONTEND_PAGE);
        String source = Files.readString(FRONTEND_PAGE, StandardCharsets.UTF_8);

        assertTrue(source.contains("function renderExpected(template: string, params: JsonMap,"),
                "renderExpected phía FE đã đổi chữ ký — kiểm lại xem còn nhận từ điển không");

        List<String> callsWithoutVocabulary = new ArrayList<>();
        int calls = 0;
        for (String args : callArguments(source, "renderExpected(")) {
            if (args.startsWith("template: string")) continue;   // dòng khai báo hàm
            calls++;
            if (!args.contains("value_labels")) callsWithoutVocabulary.add(args);
        }
        assertTrue(calls >= 3, "Chỉ bóc được " + calls + " lời gọi renderExpected — regex có thể đã hỏng");
        assertTrue(callsWithoutVocabulary.isEmpty(),
                "Lời gọi renderExpected phía FE không truyền value_labels ⇒ FE và backend dựng câu"
                        + " khác nhau, im lặng: " + callsWithoutVocabulary);
    }

    /**
     * (5) Từ điển phải phủ mọi giá trị mà đường soạn đề CHẤP NHẬN.
     *
     * <p>Không chép tay danh sách giá trị vào test — chính đó là lỗi lưới song ánh ở A2c. Ở đây
     * hợp đồng đã được siết bằng cấu trúc: {@code enumUniverse} lấy tập giá trị hợp lệ TỪ từ điển,
     * nên test chỉ cần khoá phần còn lại — bản lùi trong code không được rộng hơn từ điển.
     */
    @Test
    void everyFallbackEnumValueHasAVietnameseLabel() throws Exception {
        Map<String, Map<String, String>> vocabulary = vocabulary();
        String source = Files.readString(
                Path.of("src", "main", "java", "com", "example", "grader", "service",
                        "TestcaseTemplateService.java"), StandardCharsets.UTF_8);

        List<String> bad = new ArrayList<>();
        Matcher m = Pattern.compile("enumUniverse\\(\"(\\w+)\",\\s*(?:TARGET_TYPES|Set\\.of\\(([^)]*)\\))")
                .matcher(source);
        int checked = 0;
        while (m.find()) {
            String param = m.group(1);
            if (!vocabulary.containsKey(param)) {
                bad.add("tham số " + param + " không có trong từ điển");
                continue;
            }
            if (m.group(2) == null) continue;     // TARGET_TYPES: kiểm riêng dưới
            for (String literal : m.group(2).split(",")) {
                String value = literal.trim().replace("\"", "").toLowerCase();
                if (value.isBlank()) continue;
                checked++;
                if (!vocabulary.get(param).containsKey(value))
                    bad.add(param + "=" + value + " có trong bản lùi nhưng thiếu nhãn tiếng Việt");
            }
        }
        Matcher targets = Pattern.compile("TARGET_TYPES = Set\\.of\\(([^;]*?)\\);").matcher(source);
        assertTrue(targets.find(), "Không đọc được TARGET_TYPES — regex có thể đã hỏng");
        for (String literal : targets.group(1).split(",")) {
            String value = literal.trim().replace("\"", "").replace("\n", "").toLowerCase();
            if (value.isBlank()) continue;
            checked++;
            if (!vocabulary.get("targetType").containsKey(value))
                bad.add("targetType=" + value + " có trong bản lùi nhưng thiếu nhãn tiếng Việt");
        }

        assertTrue(checked >= 25, "Chỉ soi được " + checked + " giá trị — regex có thể đã hỏng");
        assertTrue(bad.isEmpty(), "Từ điển expected thủng:\n  " + String.join("\n  ", bad));
    }

    /** Nhãn nào cũng phải là tiếng Việt — nhãn tiếng Anh thì có từ điển cũng bằng thừa. */
    @Test
    void everyLabelInTheVocabularyIsVietnamese() throws Exception {
        List<String> bad = new ArrayList<>();
        vocabulary().forEach((param, labels) -> labels.forEach((value, label) -> {
            if (label.isBlank()) bad.add(param + "=" + value + ": nhãn rỗng");
            String lower = label.toLowerCase();
            for (String marker : ENGLISH_MARKERS) {
                if (lower.contains(marker)) bad.add(param + "=" + value + ": nhãn còn '" + marker + "'");
            }
        }));
        assertTrue(bad.isEmpty(), "Nhãn chưa sạch:\n  " + String.join("\n  ", bad));
    }

    /** Bóc đối số của từng lời gọi {@code prefix} — đếm ngoặc, vì đối số có lồng lời gọi khác. */
    private static List<String> callArguments(String source, String prefix) {
        List<String> out = new ArrayList<>();
        int at = source.indexOf(prefix);
        while (at >= 0) {
            int depth = 0;
            int start = at + prefix.length();
            for (int i = start - 1; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) { out.add(source.substring(start, i)); break; }
            }
            at = source.indexOf(prefix, at + prefix.length());
        }
        return out;
    }

    private static Map<String, Map<String, String>> vocabulary() throws Exception {
        try (InputStream in = TemplateExpectedTextTest.class
                .getResourceAsStream("/common-expected-vocabulary.json")) {
            assertNotNull(in, "Không tìm thấy từ điển expected trên classpath");
            JsonNode root = new ObjectMapper().readTree(in);
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            root.properties().forEach(entry -> {
                if (entry.getKey().startsWith("_") || !entry.getValue().isObject()) return;
                Map<String, String> labels = new LinkedHashMap<>();
                entry.getValue().properties()
                        .forEach(v -> labels.put(v.getKey().toLowerCase(), v.getValue().asText()));
                out.put(entry.getKey(), labels);
            });
            assertTrue(out.size() >= 5, "Từ điển chỉ có " + out.size() + " tham số — đọc hỏng?");
            return out;
        }
    }
}
