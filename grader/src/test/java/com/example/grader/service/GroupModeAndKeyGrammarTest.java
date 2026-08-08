package com.example.grader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mốc (c-nợ-1) + cưỡng chế ngữ pháp khoá — chủ đồ án duyệt 2026-08-08, NLP đồng thuận.
 *
 * <p>`group_id` từng mang HAI nghĩa loại trừ nhau: khâu soạn đề gộp N testcase thành MỘT
 * (all-or-nothing), còn fixture/8 mẫu dùng nó làm NHÃN nhóm chức năng trên row độc lập —
 * hình mà đường soạn đề KHÔNG sản xuất được, dù SPEC 3.3 hứa đúng hình đó. Nay tách bằng
 * `group_mode`: "label" = metadata thuần (nguồn rubric); "merge" = gộp như cũ.
 *
 * <p>Kèm hai BẤT BIẾN phía NLP xin pin trước khi giải băng logic rubric của họ
 * (CHANGELOG_FOR_GRADER 2026-08-12 mục 3): row gộp phải mang expected trọn cụm, và
 * max_score của row gộp = tổng weight các con.
 */
class GroupModeAndKeyGrammarTest {

    private static final Path FIXTURE_MATRIX =
            Path.of("..", "fixtures", "result-json-v2", "exam", "skills_matrix.json");

    private static TestcaseTemplateService service() {
        TestcaseTemplateService s = new TestcaseTemplateService();
        s.loadTemplates();
        return s;
    }

    private static Map<String, Object> item(String id, String groupId, String mode, double weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instance_id", id);
        m.put("runner", "WIDGET_VISIBLE");
        m.put("name", "Testcase " + id);
        m.put("expected", "Thành phần được kiểm phải hiển thị trên màn hình.");
        m.put("weight", weight);
        m.put("enabled", true);
        m.put("parameters", Map.of("widgetKey", "field.name"));
        if (groupId != null) {
            m.put("group_id", groupId);
            m.put("group_name", "Nhóm " + groupId);
            if (mode != null) m.put("group_mode", mode);
        }
        return m;
    }

    // ── Hai nghĩa của nhóm ─────────────────────────────────────────────────────────

    @Test
    void labelGroupsStayIndependentRowsCarryingTheirRubric() {
        List<Map<String, Object>> items = List.of(
                item("TC_A", "XEM_DS", "label", 2),
                item("TC_B", "XEM_DS", "label", 3),
                item("TC_C", null, null, 1));
        Map<String, Object> matrix = service().toSkillsMatrix(new ArrayList<>(items), "COMMON_V1");

        assertEquals(List.of("TC_A", "TC_B", "TC_C"), new ArrayList<>(matrix.keySet()),
                "nhóm label KHÔNG được gộp — mỗi testcase một row độc lập, giữ thứ tự");
        @SuppressWarnings("unchecked")
        Map<String, Object> rowA = (Map<String, Object>) matrix.get("TC_A");
        assertEquals("XEM_DS", rowA.get("rubric"), "rubric = group_id của nhãn");
        assertEquals("Nhóm XEM_DS", rowA.get("rubric_label"));
        assertFalse(rowA.containsKey("children"), "row nhãn không có children");
    }

    @Test
    void mergeGroupsStillCollapseExactlyLikeBefore() {
        List<Map<String, Object>> items = List.of(
                item("TC_A", "CUM_LON", "merge", 2),
                item("TC_B", "CUM_LON", "merge", 3));
        Map<String, Object> matrix = service().toSkillsMatrix(new ArrayList<>(items), "COMMON_V1");

        assertEquals(List.of("CUM_LON"), new ArrayList<>(matrix.keySet()));
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) matrix.get("CUM_LON");
        assertEquals("GROUP", group.get("runner"));
        assertEquals(2, ((List<?>) group.get("children")).size());
    }

    /** Config lưu TRƯỚC mốc này không có group_mode — phải giữ nghĩa cũ (merge), không đổi ngầm. */
    @Test
    void missingGroupModeMeansMergeForBackwardCompatibility() {
        List<Map<String, Object>> items = List.of(
                item("TC_A", "CUM_CU", null, 2),
                item("TC_B", "CUM_CU", null, 3));
        Map<String, Object> matrix = service().toSkillsMatrix(new ArrayList<>(items), "COMMON_V1");
        assertEquals(List.of("CUM_CU"), new ArrayList<>(matrix.keySet()),
                "thiếu group_mode phải hiểu là merge — dữ liệu cũ không được đổi nghĩa ngầm");
    }

    @Test
    void validateGroupsAllowsSingleMemberLabelsButNotSingleMemberMerges() {
        TestcaseTemplateService s = service();
        assertDoesNotThrow(() -> s.validateGroups(List.of(item("TC_A", "NHAN_LE", "label", 1))),
                "nhãn 1 thành viên hợp lệ — fixture có ba nhóm như vậy");
        assertThrows(IllegalArgumentException.class,
                () -> s.validateGroups(List.of(item("TC_A", "CUM_LE", "merge", 1))),
                "gộp 1 thành viên vẫn phải bị chặn như cũ");
        assertThrows(IllegalArgumentException.class,
                () -> s.validateGroups(List.of(
                        item("TC_A", "TRON", "label", 1), item("TC_B", "TRON", "merge", 1))),
                "một group_id nửa label nửa merge phải bị chặn");
    }

    // ── Hai bất biến NLP xin pin (CHANGELOG_FOR_GRADER 2026-08-12 mục 3) ──────────

    /** (ii) max_score của row gộp = TỔNG weight các con — trên đường sinh thật. */
    @Test
    void mergedRowWeightIsTheSumOfItsChildren() {
        Map<String, Object> matrix = service().toSkillsMatrix(new ArrayList<>(List.of(
                item("TC_A", "CUM", "merge", 2.5),
                item("TC_B", "CUM", "merge", 3),
                item("TC_C", "CUM", "merge", 3.5))), "COMMON_V1");
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) matrix.get("CUM");
        assertEquals(9.0, ((Number) group.get("weight")).doubleValue(), 1e-9);
    }

    /** (i)+(ii) trên DỮ LIỆU PHÁT HÀNH: mọi GROUP row của fixture — nguồn của cả 8 mẫu. */
    @Test
    void everyFixtureGroupRowHonoursBothInvariants() throws Exception {
        JsonNode matrix = new ObjectMapper().readTree(
                Files.readString(FIXTURE_MATRIX, StandardCharsets.UTF_8));
        int groups = 0;
        for (Map.Entry<String, JsonNode> row : matrix.properties()) {
            if (!"GROUP".equals(row.getValue().path("runner").asText())) continue;
            groups++;
            // (ii) weight = tổng weight con.
            double sum = 0;
            for (JsonNode child : row.getValue().path("children")) sum += child.path("weight").asDouble();
            assertEquals(row.getValue().path("weight").asDouble(), sum, 1e-9,
                    row.getKey() + ": weight của cụm phải bằng tổng weight các con");
            // (i) phần đo được bằng máy: expected trọn cụm — khác rỗng, không phải câu
            // đếm-assert nội bộ đời cũ, và không trùng nguyên văn expected của một con.
            String expected = row.getValue().path("expected").asText("");
            assertFalse(expected.isBlank(), row.getKey() + ": GROUP row thiếu expected");
            assertFalse(expected.toLowerCase().contains("assert"),
                    row.getKey() + ": expected còn từ vựng nội bộ — " + expected);
            for (JsonNode child : row.getValue().path("children")) {
                assertFalse(expected.equals(child.path("expected").asText()),
                        row.getKey() + ": expected của cụm trùng nguyên văn một con — chưa mô tả TRỌN cụm");
            }
        }
        assertTrue(groups >= 1, "Fixture phải có ít nhất một GROUP row để pin hai bất biến");
    }

    // ── Cưỡng chế ngữ pháp khoá ở khâu nhập ───────────────────────────────────────

    @Test
    void keyGrammarRejectsForeignKeysWithATeacherReadableMessage() throws Exception {
        TestcaseTemplateService s = service();
        Method validate = TestcaseTemplateService.class
                .getDeclaredMethod("validateKeyGrammar", Map.class, String.class);
        validate.setAccessible(true);

        // Hợp lệ: đúng namespace, chữ thường, CSV nhiều khoá, tham số không phải khoá bỏ qua.
        assertDoesNotThrow(() -> validate.invoke(s, Map.of(
                "widgetKey", "field.name",
                "errorKeys", "error.name,error.email",
                "expectedText", "User Manager"), "TC_OK"));

        for (String bad : List.of("nut.luu", "Action.delete", "action.Delete", "btn_save",
                "state.pathParameters")) {
            var e = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> validate.invoke(s, Map.of("widgetKey", bad), "TC_BAD"));
            String message = e.getCause().getMessage();
            assertTrue(message.contains(bad) && message.contains("action"),
                    "Thông báo phải nêu khoá sai và liệt kê namespace hợp lệ: " + message);
        }

        // rootKey trống là hợp lệ (APP_BOOT cho phép bỏ trống) — cưỡng chế không đụng ô trống.
        Map<String, Object> optional = new LinkedHashMap<>();
        optional.put("rootKey", "");
        assertDoesNotThrow(() -> validate.invoke(s, optional, "TC_OPTIONAL"));
    }

    /** Mọi khoá mặc định của thư viện template phải qua được chính cổng cưỡng chế. */
    @Test
    void everyTemplateDefaultKeyPassesTheGrammarGate() throws Exception {
        TestcaseTemplateService s = service();
        Method validate = TestcaseTemplateService.class
                .getDeclaredMethod("validateKeyGrammar", Map.class, String.class);
        validate.setAccessible(true);
        JsonNode templates;
        try (var in = getClass().getResourceAsStream("/common-testcase-templates.json")) {
            assertNotNull(in);
            templates = new ObjectMapper().readTree(in);
        }
        int checked = 0;
        for (JsonNode t : templates) {
            Map<String, Object> params = new LinkedHashMap<>();
            t.path("parameters_schema").properties()
                    .forEach(e -> params.put(e.getKey(), e.getValue().asText("")));
            String id = t.path("template_id").asText();
            assertDoesNotThrow(() -> validate.invoke(s, params, id),
                    "Khoá mặc định của " + id + " bị chính cổng ngữ pháp chặn");
            checked++;
        }
        assertTrue(checked >= 20, "Chỉ kiểm được " + checked + " template");
    }
}
