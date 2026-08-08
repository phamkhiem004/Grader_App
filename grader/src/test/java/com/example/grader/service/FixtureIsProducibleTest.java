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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CỔNG: mọi dòng của fixture phải SẢN XUẤT ĐƯỢC từ đường soạn đề.
 *
 * <p>Fixture là bộ đo của cả hai bên và là nguồn của 9 mẫu phát hành, nhưng nó được GÕ TAY từ P0
 * — nên nó lặng lẽ trôi khỏi thứ khâu soạn đề thật sinh ra được. Đã trả giá ba lần:
 * <ol>
 *   <li>{@code group_id} mang hai nghĩa — hình 24 row nhãn không sản xuất được (đóng ở GMODE);</li>
 *   <li>trọng số con của cụm gộp 4+8 ≠ 9 — bất biến phía NLP bắt được;</li>
 *   <li>5 dòng khai {@code targetType} ngoài {@code parameters_schema} — chỉ lộ ra khi tôi soạn
 *       đề E2E bằng tay và bắt chước fixture, rồi bị chính backend từ chối.</li>
 * </ol>
 * Cả ba đều là "bộ đo trôi khỏi sản phẩm", và cả ba đều KHÔNG cổng nào bắt. Đây là cổng đó.
 *
 * <p>Chỉ kiểm điều máy khẳng định được: tham số của mỗi dòng phải nằm trong schema của template
 * cùng runner — đúng luật {@code parameters()} dùng khi giáo viên lưu đề.
 */
class FixtureIsProducibleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURE_MATRIX =
            Path.of("..", "fixtures", "result-json-v2", "exam", "skills_matrix.json");

    @Test
    void everyFixtureRowUsesOnlyParametersItsTemplateDeclares() throws Exception {
        Map<String, Set<String>> schemaByRunner = new LinkedHashMap<>();
        try (InputStream in = getClass().getResourceAsStream("/common-testcase-templates.json")) {
            assertNotNull(in, "Không tìm thấy thư viện template trên classpath");
            for (JsonNode t : MAPPER.readTree(in)) {
                Set<String> keys = new LinkedHashSet<>();
                t.path("parameters_schema").fieldNames().forEachRemaining(keys::add);
                schemaByRunner.put(t.path("runner").asText(), keys);
            }
        }
        assertTrue(schemaByRunner.size() >= 20,
                "Chỉ đọc được " + schemaByRunner.size() + " template — nguồn có thể đã đổi hình dạng");

        JsonNode matrix = MAPPER.readTree(Files.readString(FIXTURE_MATRIX, StandardCharsets.UTF_8));
        List<String> bad = new ArrayList<>();
        int checked = 0;

        List<Map.Entry<String, JsonNode>> queue = new ArrayList<>();
        matrix.properties().forEach(queue::add);
        for (int i = 0; i < queue.size(); i++) {
            Map.Entry<String, JsonNode> entry = queue.get(i);
            JsonNode row = entry.getValue();
            row.path("children").forEach(child ->
                    queue.add(Map.entry(entry.getKey() + "/" + child.path("instance_id").asText(), child)));

            Set<String> schema = schemaByRunner.get(row.path("runner").asText());
            if (schema == null) continue;   // GROUP: không có template riêng
            checked++;
            row.path("parameters").fieldNames().forEachRemaining(param -> {
                if (!schema.contains(param))
                    bad.add(entry.getKey() + " (" + row.path("runner").asText() + "): '" + param + "'");
            });
        }

        assertTrue(checked >= 20, "Chỉ soi được " + checked + " dòng — fixture có thể đã đổi hình dạng");
        assertTrue(bad.isEmpty(), "Dòng fixture dùng tham số template KHÔNG khai — đường soạn đề sẽ"
                + " từ chối chính bộ đo của mình:\n  " + String.join("\n  ", bad));
    }

    /**
     * Thông báo "Trùng instance_id" là lưới cuối cùng chặn một khiếm khuyết CÓ THẬT phía FE
     * (hai lần thêm testcase trong cùng một tick sinh trùng id). Ghim lại vì phía FE không có
     * bộ test nào, nên đây là chỗ duy nhất khẳng định giáo viên được BÁO thay vì mất dữ liệu.
     */
    @Test
    void duplicateInstanceIdIsRejectedWithAReadableMessage() throws Exception {
        TestcaseTemplateService service = new TestcaseTemplateService();
        service.loadTemplates();
        injectSyllabusStub(service);

        Map<String, Object> one = new LinkedHashMap<>();
        one.put("instance_id", "TC_TRUNG");
        one.put("template_id", "COMMON_WIDGET_VISIBLE");
        one.put("parameters", Map.of("widgetKey", "field.name"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> invokeNormalize(service, List.of(one, new LinkedHashMap<>(one))));
        assertTrue(e.getMessage().contains("Trùng instance_id"), e.getMessage());
    }

    /** normalizeItems tra syllabus để chặn skill_code chết; test đơn vị không có DB nên cắm bản giả. */
    private static void injectSyllabusStub(TestcaseTemplateService service) throws Exception {
        com.example.grader.entity.Skill skill = new com.example.grader.entity.Skill();
        skill.setCode("UI_SCAFFOLD_APPBAR");
        skill.setName("Khung ứng dụng");
        SyllabusService stub = new SyllabusService() {
            @Override public List<com.example.grader.entity.Skill> skills() { return List.of(skill); }
        };
        var field = TestcaseTemplateService.class.getDeclaredField("syllabusService");
        field.setAccessible(true);
        field.set(service, stub);
    }

    private static void invokeNormalize(TestcaseTemplateService service, List<Map<String, Object>> items)
            throws Exception {
        var method = TestcaseTemplateService.class.getDeclaredMethod(
                "normalizeItems", String.class, Object.class, Map.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(service, "PE_TEST", items, Map.of(), "giaovien@fpt.edu.vn");
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            throw wrapped;
        }
    }
}
