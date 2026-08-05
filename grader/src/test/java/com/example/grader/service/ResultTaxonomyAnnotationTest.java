package com.example.grader.service;

import com.example.grader.entity.SkillCategory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm ĐIỂM GHÉP THẬT của nhãn phân loại vào result.json, không chỉ kiểm hàm tra bảng.
 * `annotateTaxonomy` là private nên gọi qua reflection — cùng lối với
 * {@code ResultControllerNormalizationTest}.
 */
class ResultTaxonomyAnnotationTest {

    private static List<Map<String, Object>> annotate(List<Map<String, Object>> tcs,
                                                      Map<String, Object> matrix) throws Exception {
        Method m = BatchGradingService.class
                .getDeclaredMethod("annotateTaxonomy", List.class, Map.class);
        m.setAccessible(true);
        m.invoke(new BatchGradingService(), tcs, matrix);
        return tcs;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return m;
    }

    @SafeVarargs
    private static List<Map<String, Object>> cases(Map<String, Object>... tcs) {
        return new ArrayList<>(List.of(tcs));
    }

    @Test
    void annotatesCommonEngineExam() throws Exception {
        Map<String, Object> matrix = map(
                "TC_LIST", map("runner", "LIST_VISIBLE", "group_id", "XEM_DS"),
                "TC_ADD", map("runner", "GROUP", "group_id", "THEM_USER",
                        "children", List.of(map("runner", "WIDGET_VISIBLE"),
                                            map("runner", "FORM_SUBMIT"))));

        List<Map<String, Object>> tcs = annotate(
                cases(map("test_id", "TC_LIST"), map("test_id", "TC_ADD")), matrix);

        assertEquals("widget", tcs.get(0).get("layer"));
        assertEquals("XEM_DS", tcs.get(0).get("rubric"));
        assertEquals("integration", tcs.get(1).get("layer"));   // nhóm lấy tầng cao nhất
        assertEquals("THEM_USER", tcs.get(1).get("rubric"));
    }

    @Test
    void annotatesLegacyExamWithoutMatrix() throws Exception {
        List<Map<String, Object>> tcs = annotate(
                cases(map("test_id", "CONTRACT_MODEL_SYMBOLS"),
                      map("test_id", "UI_RESPONSIVE_LANDSCAPE")), null);

        assertEquals("contract", tcs.get(0).get("layer"));
        assertEquals("responsive", tcs.get(1).get("layer"));
    }

    @Test
    void readsLegacyRubricFieldFromMatrix() throws Exception {
        Map<String, Object> matrix = map(
                "SCREEN_VALIDATE_EACH_FIELD", map("rubric", "ADD_USER"));
        List<Map<String, Object>> tcs = annotate(
                cases(map("test_id", "SCREEN_VALIDATE_EACH_FIELD")), matrix);

        assertEquals("ADD_USER", tcs.get(0).get("rubric"));
        assertEquals("widget", tcs.get(0).get("layer"));   // matrix legacy không có runner
    }

    @Test
    void alwaysEmitsBothKeysEvenWhenUnknown() throws Exception {
        // Bên đọc file không phải đoán schema: khoá luôn có mặt, giá trị null là hợp lệ.
        List<Map<String, Object>> tcs = annotate(cases(map("test_id", "KHONG_RO")), null);

        assertTrue(tcs.get(0).containsKey("layer"));
        assertTrue(tcs.get(0).containsKey("rubric"));
        assertNull(tcs.get(0).get("layer"));
        assertNull(tcs.get(0).get("rubric"));
    }

    @Test
    void doesNotOverwriteValuesGraderAlreadySent() throws Exception {
        Map<String, Object> matrix = map("TC_1", map("runner", "APP_BOOT", "group_id", "KHOI_DONG"));
        List<Map<String, Object>> tcs = annotate(
                cases(map("test_id", "TC_1", "layer", "persist", "rubric", "GIU_NGUYEN")), matrix);

        assertEquals("persist", tcs.get(0).get("layer"));
        assertEquals("GIU_NGUYEN", tcs.get(0).get("rubric"));
    }

    // ── expected của testcase GROUP tại điểm ghép result.json ─────
    private static void enrich(List<Map<String, Object>> tcs, Map<String, Object> matrix) throws Exception {
        Method m = BatchGradingService.class
                .getDeclaredMethod("enrichTestCases", List.class, Map.class);
        m.setAccessible(true);
        m.invoke(new BatchGradingService(), tcs, matrix);
    }

    private static Map<String, Object> groupRow(String storedExpected) {
        return map("runner", "GROUP", "group_id", "THEM_USER", "name", "Thêm người dùng",
                "expected", storedExpected,
                "children", List.of(
                        map("runner", "WIDGET_VISIBLE", "expected", "Form phải có nút lưu."),
                        map("runner", "FORM_SUBMIT",
                                "expected", "Sau khi lưu, người dùng mới phải nằm trong danh sách.")));
    }

    @Test
    void rebuildsLegacyGroupExpectedWhenAssemblingResult() throws Exception {
        // Đề publish TRƯỚC bản sửa: skills_matrix.json trên đĩa còn câu đếm số assert. Phải
        // dựng lại lúc ghép, nếu không câu đó đi thẳng tới sinh viên qua bản nhận xét.
        Map<String, Object> matrix = map("TC_ADD", groupRow("Tất cả 2 assert trong nhóm phải đạt."));
        List<Map<String, Object>> tcs = cases(
                map("test_id", "TC_ADD", "expected", "Tất cả 2 assert trong nhóm phải đạt."));

        enrich(tcs, matrix);

        assertEquals("Thêm người dùng: Form phải có nút lưu. "
                + "Sau khi lưu, người dùng mới phải nằm trong danh sách.", tcs.get(0).get("expected"));
    }

    @Test
    void keepsTeacherWrittenGroupExpectedWhenAssemblingResult() throws Exception {
        String written = "Nhập hợp lệ rồi lưu thì người dùng mới xuất hiện trong danh sách.";
        Map<String, Object> matrix = map("TC_ADD", groupRow(written));
        List<Map<String, Object>> tcs = cases(map("test_id", "TC_ADD"));

        enrich(tcs, matrix);

        assertEquals(written, tcs.get(0).get("expected"));
    }

    // ── chapter ───────────────────────────────────────────────────
    @Test
    void chapterPrefersExplicitColumnThenDerivesFromOrder() {
        SkillCategory explicit = new SkillCategory();
        explicit.setChapter(6);
        explicit.setDisplayOrder(99);
        assertEquals(6, explicit.resolveChapter());

        // Hàng cũ trong DB chưa có cột chapter → suy từ display_order + 1.
        SkillCategory legacy = new SkillCategory();
        legacy.setDisplayOrder(5);
        assertEquals(6, legacy.resolveChapter());

        SkillCategory unknown = new SkillCategory();
        unknown.setDisplayOrder(null);
        assertNull(unknown.resolveChapter());
    }
}
