package com.example.grader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCaseTaxonomyTest {

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return m;
    }

    // ── layer suy từ runner ────────────────────────────────────────
    @Test
    void mapsRunnerToLayer() {
        assertEquals("widget", TestCaseTaxonomy.layerOf(row("runner", "LIST_VISIBLE"), "TC_1"));
        assertEquals("widget", TestCaseTaxonomy.layerOf(row("runner", "WIDGET_TEXT_CONTENT"), "TC_2"));
        assertEquals("integration", TestCaseTaxonomy.layerOf(row("runner", "APP_BOOT"), "TC_3"));
        assertEquals("integration", TestCaseTaxonomy.layerOf(row("runner", "FORM_SUBMIT"), "TC_4"));
        assertEquals("responsive", TestCaseTaxonomy.layerOf(row("runner", "RESPONSIVE_TARGET"), "TC_5"));
    }

    @Test
    void groupTakesHighestLayerOfChildren() {
        Map<String, Object> group = row(
                "runner", "GROUP",
                "children", List.of(row("runner", "WIDGET_VISIBLE"), row("runner", "FORM_SUBMIT")));
        assertEquals("integration", TestCaseTaxonomy.layerOf(group, "TC_GROUP"));
    }

    @Test
    void groupWithoutUsableChildrenHasNoLayer() {
        assertNull(TestCaseTaxonomy.layerOf(row("runner", "GROUP"), "TC_GROUP"));
        assertNull(TestCaseTaxonomy.layerOf(
                row("runner", "GROUP", "children", List.of(row("runner", "RUNNER_LA"))), "TC_GROUP"));
    }

    // ── layer đã ghi sẵn trong matrix ──────────────────────────────
    @Test
    void keepsLayerAlreadyDeclaredInMatrix() {
        assertEquals("persist", TestCaseTaxonomy.layerOf(
                row("layer", "persist", "runner", "APP_BOOT"), "TC_1"));
    }

    @Test
    void ignoresTemplateLayerOfOtherAxis() {
        // Template cũ khai layer dạng SCREEN/BLACKBOX/RESPONSIVE — trục khác với SPEC.
        // SCREEN không có trong enum nên phải rơi xuống suy theo runner, không nhận bừa.
        assertEquals("widget", TestCaseTaxonomy.layerOf(
                row("layer", "SCREEN", "runner", "WIDGET_VISIBLE"), "TC_1"));
        assertEquals("integration", TestCaseTaxonomy.layerOf(
                row("layer", "BLACKBOX", "runner", "NAVIGATION"), "TC_2"));
    }

    // ── layer của đề legacy suy từ tiền tố test_id ─────────────────
    @Test
    void fallsBackToLegacyPrefix() {
        assertEquals("contract", TestCaseTaxonomy.layerOf(null, "CONTRACT_MODEL_SYMBOLS"));
        assertEquals("unit", TestCaseTaxonomy.layerOf(null, "REPOSITORY_GRANULAR_DELETE"));
        assertEquals("widget", TestCaseTaxonomy.layerOf(null, "SCREEN_VALIDATE_EACH_FIELD"));
        assertEquals("persist", TestCaseTaxonomy.layerOf(null, "PERSIST_ADD_RELOAD"));
        assertEquals("architecture", TestCaseTaxonomy.layerOf(null, "ARCH_MVVM"));
        assertEquals("visual", TestCaseTaxonomy.layerOf(null, "VISUAL_HOME_PORTRAIT"));
        assertNull(TestCaseTaxonomy.layerOf(null, "KHONG_CO_TIEN_TO"));
    }

    @Test
    void responsivePrefixWinsOverGenericUiPrefix() {
        // UI_RESPONSIVE_/UI_LAYOUT_ phải được xét TRƯỚC luật UI_ chung.
        assertEquals("responsive", TestCaseTaxonomy.layerOf(null, "UI_RESPONSIVE_LANDSCAPE"));
        assertEquals("responsive", TestCaseTaxonomy.layerOf(null, "UI_LAYOUT_OVERFLOW"));
        assertEquals("integration", TestCaseTaxonomy.layerOf(null, "UI_CREATE_VALID"));
    }

    @Test
    void everyLayerProducedIsInTheEnum() {
        for (String testId : List.of("CONTRACT_A", "MODEL_A", "SCREEN_A", "UI_A",
                "UI_RESPONSIVE_A", "PERSIST_A", "ARCH_A", "VISUAL_A")) {
            String layer = TestCaseTaxonomy.layerOf(null, testId);
            assertTrue(TestCaseTaxonomy.LAYERS.contains(layer), testId + " → " + layer);
        }
    }

    // ── rubric ────────────────────────────────────────────────────
    @Test
    void rubricPrefersTeacherGroup() {
        assertEquals("THEM_USER", TestCaseTaxonomy.rubricOf(
                row("group_id", "THEM_USER", "testcase_group", "BEHAVIOR", "rubric", "CU")));
        assertEquals("BEHAVIOR", TestCaseTaxonomy.rubricOf(
                row("testcase_group", "BEHAVIOR", "rubric", "CU")));
        assertEquals("CU", TestCaseTaxonomy.rubricOf(row("rubric", "CU")));
        assertNull(TestCaseTaxonomy.rubricOf(row()));
        assertNull(TestCaseTaxonomy.rubricOf(null));
    }

    // ── rubric_label: nhãn hiển thị của rubric ────────────────────
    @Test
    void rubricLabelComesFromTeacherGroupName() {
        assertEquals("Thêm người dùng", TestCaseTaxonomy.rubricLabelOf(
                row("group_id", "THEM_USER", "group_name", "Thêm người dùng")));
    }

    @Test
    void rubricLabelIsNullRatherThanEchoingTheCode() {
        // UI để group_name = group_id khi nhóm chưa được đặt tên. Trả về mã đó làm "nhãn" là
        // đưa định danh nội bộ cho sinh viên đọc — thà không có nhãn, vì mã đã ở `rubric`.
        assertNull(TestCaseTaxonomy.rubricLabelOf(row("group_id", "THEM_USER", "group_name", "THEM_USER")));
        assertNull(TestCaseTaxonomy.rubricLabelOf(row("group_id", "THEM_USER")));
        assertNull(TestCaseTaxonomy.rubricLabelOf(row("group_id", "THEM_USER", "group_name", "  ")));
        assertNull(TestCaseTaxonomy.rubricLabelOf(null));
    }

    @Test
    void rubricSkipsBlankValues() {
        assertEquals("BEHAVIOR", TestCaseTaxonomy.rubricOf(
                row("group_id", "   ", "testcase_group", "BEHAVIOR")));
    }

    // ── expected của testcase GROUP ───────────────────────────────
    private static Map<String, Object> group(String name, String... childExpected) {
        List<Map<String, Object>> children = new java.util.ArrayList<>();
        for (String e : childExpected) children.add(row("runner", "WIDGET_VISIBLE", "expected", e));
        return row("runner", "GROUP", "group_id", "THEM_USER", "name", name, "children", children);
    }

    @Test
    void groupExpectedJoinsChildrenUnderGroupName() {
        assertEquals("Thêm người dùng: Form phải có ô nhập họ tên. Sau khi lưu, người dùng mới "
                        + "phải nằm trong danh sách.",
                TestCaseTaxonomy.groupExpected(group("Thêm người dùng",
                        "Form phải có ô nhập họ tên.",
                        "Sau khi lưu, người dùng mới phải nằm trong danh sách.")));
    }

    @Test
    void groupExpectedNeverLeaksTestCountOrInternalVocabulary() {
        // Bản cũ sinh "Tất cả N assert trong nhóm phải đạt." — đếm testcase và dùng chữ
        // "assert", cả hai đều là từ vựng nội bộ mà sinh viên không kiểm chứng được.
        String out = TestCaseTaxonomy.groupExpected(group("Thêm người dùng",
                "Form phải có ô nhập họ tên.", "Form phải có ô nhập email.", "Phải có nút lưu."));
        assertTrue(out.contains("ô nhập email"), out);
        assertFalse(out.toLowerCase().contains("assert"), out);
        assertFalse(Pattern.compile("\\b\\d+\\s+(assert|testcase|test)\\b").matcher(out).find(), out);
    }

    @Test
    void groupExpectedKeepsWhatTeacherWrote() {
        Map<String, Object> g = group("Thêm người dùng", "Form phải có ô nhập họ tên.");
        g.put("expected", "Nhập hợp lệ rồi lưu thì người dùng mới xuất hiện trong danh sách.");
        assertNull(TestCaseTaxonomy.groupExpected(g), "Không được ghi đè câu giáo viên viết tay");
    }

    @Test
    void groupExpectedReplacesLegacyGeneratedSentence() {
        // Đề đã publish trước bản sửa: phải dựng lại khi ĐỌC, không cần publish lại đề.
        Map<String, Object> g = group("Thêm người dùng", "Form phải có ô nhập họ tên.");
        g.put("expected", "Tất cả 2 assert trong nhóm phải đạt.");
        assertEquals("Thêm người dùng: Form phải có ô nhập họ tên.", TestCaseTaxonomy.groupExpected(g));
    }

    @Test
    void groupExpectedNormalizesChildSentences() {
        // Giáo viên nhập không dấu chấm cuối, và hai con trùng nội dung.
        assertEquals("Thêm người dùng: Phải có nút lưu.",
                TestCaseTaxonomy.groupExpected(group("Thêm người dùng",
                        "Phải có nút lưu", "Phải có nút lưu.")));
    }

    @Test
    void groupExpectedOmitsTechnicalGroupIdAsTitle() {
        // name chưa đặt thì bằng group_id — mã kỹ thuật, không được ghép vào câu cho SV đọc.
        assertEquals("Form phải có ô nhập họ tên.",
                TestCaseTaxonomy.groupExpected(group("THEM_USER", "Form phải có ô nhập họ tên.")));
    }

    @Test
    void groupExpectedFallsBackToGroupNameWhenChildrenSaySilent() {
        assertEquals("Phải thực hiện đúng yêu cầu \"Thêm người dùng\".",
                TestCaseTaxonomy.groupExpected(group("Thêm người dùng")));
        assertNull(TestCaseTaxonomy.groupExpected(group("THEM_USER")));
    }

    @Test
    void groupExpectedIgnoresNonGroupRows() {
        assertNull(TestCaseTaxonomy.groupExpected(null));
        assertNull(TestCaseTaxonomy.groupExpected(row("runner", "WIDGET_VISIBLE", "expected", "x")));
        assertNull(TestCaseTaxonomy.groupExpected(row("expected", "x")));
    }

    /**
     * CHỐT CHẶN LỆCH NGÔN NGỮ: mọi runner khai trong engine Dart phải có layer ở bảng Java.
     * Thêm runner mới vào exam_test.dart mà quên cập nhật TestCaseTaxonomy thì test này đỏ,
     * thay vì âm thầm sinh ra testcase không có layer.
     */
    @Test
    void everyEngineRunnerHasALayer() throws Exception {
        String source;
        try (InputStream in = getClass().getResourceAsStream("/common-testcase-engine/exam_test.dart")) {
            assertNotNull(in, "Không tìm thấy engine chung trên classpath");
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Set<String> runners = new LinkedHashSet<>();
        Matcher m = Pattern.compile("case '([A-Z][A-Z_]*)':").matcher(source);
        while (m.find()) runners.add(m.group(1));
        runners.remove("GROUP");   // GROUP là dẫn xuất từ các con, không có layer riêng

        assertTrue(runners.size() >= 20, "Chỉ bóc được " + runners.size() + " runner — regex có thể đã hỏng");
        for (String runner : runners) {
            assertNotNull(TestCaseTaxonomy.layerForRunner(runner),
                    "Runner " + runner + " chưa có layer trong TestCaseTaxonomy");
        }
    }

    /**
     * CHIỀU CÒN LẠI: mọi runner GIÁO VIÊN CHỌN ĐƯỢC phải là runner engine CHẠY ĐƯỢC.
     *
     * <p>{@link #everyEngineRunnerHasALayer} canh chiều *engine → bảng layer*. Nhưng thư viện
     * template là thứ giáo viên bấm chọn trên UI, và nó là một danh sách RIÊNG — nếu một template
     * khai `runner` mà {@code exam_test.dart} không có nhánh, thì không gì đỏ cả: lỗi chỉ lộ ra
     * **lúc chấm bài thật**, khi engine rơi vào {@code default: fail('… chưa có common runner')}.
     * Tức là một lỗi CẤU HÌNH, nhưng người chịu hậu quả là **sinh viên** — testcase đó hỏng trên
     * bài của em ấy. Bắt ở đây thì nó chỉ là một test đỏ lúc build.
     *
     * <p>Hôm nay hai danh sách khớp nhau (22 template ⊆ 23 runner engine, chênh đúng `GROUP` —
     * nhóm được tạo bằng cách gom trên UI, không có template riêng). Test này giữ cho nó khớp.
     */
    @Test
    void everyTemplateRunnerIsImplementedByTheEngine() throws Exception {
        JsonNode templates;
        try (InputStream in = getClass().getResourceAsStream("/common-testcase-templates.json")) {
            assertNotNull(in, "Không tìm thấy thư viện template trên classpath");
            templates = new ObjectMapper().readTree(in);
        }
        Set<String> pickable = new LinkedHashSet<>();
        for (JsonNode t : templates) {
            String runner = t.path("runner").asText(null);
            if (runner != null && !runner.isBlank()) pickable.add(runner);
        }
        // Cùng lý do như test trên: đọc hỏng ⇒ tập rỗng ⇒ mọi phép kiểm dưới đạt vô nghĩa.
        assertTrue(pickable.size() >= 20,
                "Chỉ đọc được " + pickable.size() + " template — file có thể đã đổi hình dạng");

        String source;
        try (InputStream in = getClass().getResourceAsStream("/common-testcase-engine/exam_test.dart")) {
            assertNotNull(in, "Không tìm thấy engine chung trên classpath");
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Set<String> implemented = new LinkedHashSet<>();
        Matcher m = Pattern.compile("case '([A-Z][A-Z_]*)':").matcher(source);
        while (m.find()) implemented.add(m.group(1));

        Set<String> orphan = new LinkedHashSet<>(pickable);
        orphan.removeAll(implemented);
        assertTrue(orphan.isEmpty(),
                "Template cho giáo viên chọn nhưng engine không chạy được: " + orphan
                        + " — sinh viên sẽ nhận testcase hỏng lúc chấm");
    }
}
