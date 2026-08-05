package com.example.grader.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TRỤC PHÂN LOẠI TESTCASE cho `result.json` v2 — nguồn sự thật DUY NHẤT của {@code layer}
 * và {@code rubric}. Dùng chung cho lúc SINH đề ({@link TestcaseTemplateService}) và lúc
 * GHÉP kết quả ({@link BatchGradingService}) để hai bên không lệch nhau.
 *
 * <p><b>layer</b> — tầng kiểm thử. Tiêu chí phân tầng là ĐIỀU ĐƯỢC KHẲNG ĐỊNH, không phải
 * cách dựng widget: trong engine chung mọi runner đều khởi động ứng dụng thật nên không thể
 * phân tầng theo cách pump.
 * <ul>
 *   <li>{@code widget} — mở màn hình rồi khẳng định trạng thái TĨNH (có/không, thuộc tính, kích thước)</li>
 *   <li>{@code integration} — có THAO TÁC (chạm/nhập) rồi khẳng định HỆ QUẢ</li>
 *   <li>{@code responsive} — khẳng định bố cục theo KÍCH THƯỚC màn hình</li>
 * </ul>
 *
 * <p><b>rubric</b> — nhóm CHỨC NĂNG theo cách sinh viên hiểu bài ("Thêm người dùng"), lấy từ
 * nhóm giáo viên đã gom trên UI. Lưu ý {@code testcase_group} (LOGIC/WIDGET/BEHAVIOR) KHÔNG
 * phải rubric: nó phân theo bản chất kiểm tra, cùng trục với {@code layer} — chỉ dùng làm
 * phương án chống rỗng.
 *
 * <p>Hợp đồng đầy đủ: {@code SPEC_grader_result_json/SPEC_result_json.md} mục 3.1 và 3.3.
 */
public final class TestCaseTaxonomy {

    private TestCaseTaxonomy() {}

    /** Enum đầy đủ; engine chung chỉ sinh ra widget/integration/responsive. */
    public static final Set<String> LAYERS = Set.of(
            "contract", "unit", "widget", "integration", "responsive",
            "persist", "architecture", "visual");

    /** Thang so sánh tầng — dùng khi testcase GROUP lấy tầng cao nhất của các con. */
    private static final Map<String, Integer> LAYER_RANK = Map.of(
            "contract", 0, "unit", 1, "widget", 2,
            "integration", 3, "responsive", 4, "persist", 5);

    /** 23 runner của engine chung COMMON_V1. GROUP là dẫn xuất, xử lý riêng. */
    private static final Map<String, String> RUNNER_LAYER = Map.ofEntries(
            Map.entry("WIDGET_VISIBLE", "widget"),
            Map.entry("WIDGET_TYPE_VISIBLE", "widget"),
            Map.entry("WIDGET_TEXT_CONTENT", "widget"),
            Map.entry("WIDGET_TEXT_STYLE", "widget"),
            Map.entry("WIDGET_ENABLED", "widget"),
            Map.entry("WIDGET_SEMANTICS_LABEL", "widget"),
            Map.entry("WIDGET_DIMENSION", "widget"),
            Map.entry("WIDGET_PADDING", "widget"),
            Map.entry("WIDGET_GAP", "widget"),
            Map.entry("LIST_VISIBLE", "widget"),
            Map.entry("LIST_ITEM_COUNT", "widget"),
            // APP_BOOT chạy toàn tuyến main() → dữ liệu → khung hình đầu, không phải một màn hình.
            Map.entry("APP_BOOT", "integration"),
            Map.entry("BUTTON_ACTION", "integration"),
            Map.entry("NAVIGATION", "integration"),
            Map.entry("DIALOG_FLOW", "integration"),
            Map.entry("STATE_REACTIVE_FLOW", "integration"),
            Map.entry("FORM_REQUIRED_FIELDS", "integration"),
            Map.entry("FORM_VALIDATE_FIELDS", "integration"),
            Map.entry("FORM_PREFILL", "integration"),
            Map.entry("FORM_SUBMIT", "integration"),
            Map.entry("RESPONSIVE_NO_OVERFLOW", "responsive"),
            Map.entry("RESPONSIVE_TARGET", "responsive"));

    /**
     * Suy layer từ tiền tố test_id cho ĐỀ LEGACY (matrix không có runner).
     * Thứ tự quan trọng: UI_RESPONSIVE_/UI_LAYOUT_ phải đứng TRƯỚC luật UI_ chung.
     */
    private static final List<String[]> LEGACY_PREFIX_LAYER = List.of(
            new String[]{"CONTRACT_", "contract"},
            new String[]{"UI_RESPONSIVE_", "responsive"},
            new String[]{"UI_LAYOUT_", "responsive"},
            new String[]{"MODEL_", "unit"},
            new String[]{"REPOSITORY_", "unit"},
            new String[]{"VIEWMODEL_", "unit"},
            new String[]{"SQLITE_", "unit"},
            new String[]{"SCREEN_", "widget"},
            new String[]{"PERSIST_", "persist"},
            new String[]{"ARCH_", "architecture"},
            new String[]{"VISUAL_", "visual"},
            new String[]{"UI_", "integration"});

    /** Layer của một runner đơn lẻ; {@code null} nếu runner lạ hoặc là GROUP. */
    public static String layerForRunner(String runner) {
        return runner == null ? null : RUNNER_LAYER.get(runner.trim().toUpperCase());
    }

    /**
     * Layer của một testcase, theo thứ tự ưu tiên: giá trị đã ghi sẵn trong matrix →
     * suy từ {@code runner} (GROUP lấy tầng cao nhất của các con) → tiền tố test_id (legacy).
     *
     * @param row     dòng cấu hình trong skills_matrix.json; có thể null
     * @param testId  dùng cho nhánh legacy
     * @return một giá trị trong {@link #LAYERS}, hoặc null nếu không suy được
     */
    public static String layerOf(Map<String, ?> row, String testId) {
        if (row != null) {
            // Chỉ nhận giá trị đã theo enum của SPEC. Template cũ ghi layer dạng
            // SCREEN/BLACKBOX (trục khác) nên phải bỏ qua, không được nhận nhầm.
            String declared = text(row.get("layer"));
            if (declared != null && LAYERS.contains(declared.toLowerCase())) {
                return declared.toLowerCase();
            }
            String runner = text(row.get("runner"));
            if (runner != null) {
                if ("GROUP".equalsIgnoreCase(runner.trim())) return groupLayer(row);
                String byRunner = layerForRunner(runner);
                if (byRunner != null) return byRunner;
            }
        }
        return legacyLayer(testId);
    }

    /** Testcase GROUP mang tầng CAO NHẤT trong các testcase con. */
    private static String groupLayer(Map<String, ?> row) {
        Object children = row.get("children");
        if (!(children instanceof List<?> list)) return null;
        String best = null;
        int bestRank = -1;
        for (Object child : list) {
            if (!(child instanceof Map<?, ?> childRow)) continue;
            String layer = layerForRunner(text(childRow.get("runner")));
            Integer rank = layer == null ? null : LAYER_RANK.get(layer);
            if (rank != null && rank > bestRank) {
                bestRank = rank;
                best = layer;
            }
        }
        return best;
    }

    private static String legacyLayer(String testId) {
        if (testId == null) return null;
        String id = testId.trim().toUpperCase();
        for (String[] rule : LEGACY_PREFIX_LAYER) {
            if (id.startsWith(rule[0])) return rule[1];
        }
        return null;
    }

    /**
     * Rubric của một testcase: nhóm giáo viên đã gom → {@code testcase_group} (chống rỗng) →
     * field {@code rubric} có sẵn của đề legacy.
     */
    public static String rubricOf(Map<String, ?> row) {
        if (row == null) return null;
        for (String key : new String[]{"group_id", "testcase_group", "rubric"}) {
            String value = text(row.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
