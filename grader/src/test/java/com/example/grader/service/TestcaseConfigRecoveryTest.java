package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ testcase nhập bằng ZIP chỉ còn file trên đĩa. Dựng lại SAI cấu hình từ đó nguy hiểm hơn là
 * không dựng: giáo viên bấm Lưu là bộ đang chấm bị thay bằng bản khác. Các test dưới đây chốt
 * từng phần dựng lại phải khớp đúng nội dung file gốc.
 */
class TestcaseConfigRecoveryTest {

    private static final String MATRIX = """
            {
              "PE_item_01": {
                "instance_id": "PE_item_01",
                "template_id": "COMMON_WIDGET_VISIBLE",
                "runner": "WIDGET_VISIBLE",
                "skill_code": "UI_SCAFFOLD_APPBAR",
                "testcase_group": "SCREEN_STRUCTURE",
                "name": "Màn hình chính hiển thị",
                "description": "Kiểm tra screen.home được render",
                "expected": "Thấy widget screen.home",
                "difficulty": "basic",
                "weight": 20,
                "parameters": {"widgetKey": "screen.home", "targetType": "container"}
              },
              "PE_group_01": {
                "instance_id": "PE_group_01",
                "runner": "GROUP",
                "group_id": "PE_group_01",
                "group_name": "Thêm người dùng",
                "name": "Thêm người dùng",
                "weight": 30,
                "children": [
                  {"instance_id": "PE_item_02", "template_id": "COMMON_FORM_SUBMIT",
                   "runner": "FORM_SUBMIT", "skill_code": "FORM_VALIDATION",
                   "name": "Gửi form hợp lệ", "expected": "Thêm được người dùng",
                   "difficulty": "intermediate", "weight": 30,
                   "parameters": {"submitKey": "action.save"}}
                ]
              }
            }
            """;

    private static final String CONTRACT = """
            {"require_keys": true,
             "keys": [{"key": "screen.home", "label": "Màn hình chính", "strategy": "key_only",
                       "value": "", "index": 0}]}
            """;

    /** Đúng khuôn mà TestcaseTemplateService sinh ra cho testcase tự viết code. */
    private static final String EXAM_TEST = """
            // ─────────────────── CUSTOM_TESTCASES_BEGIN ───────────────────
            void _registerCustomTestcase(String testId) {
              switch (testId) {
                // Kiểm tra tay
                case 'PE_item_03':
                  testWidgets('PE_item_03', (tester) async {
                    _stage('TESTCASE_CUSTOM_CODE');
                    await _boot(tester);
                    expect(_byKey('list.items'), findsOneWidget);
                  });
                  return;
              }
            }
            // ──────────────────── CUSTOM_TESTCASES_END ────────────────────
            """;

    private Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(TestcaseConfigRecovery.Recovered r) {
        return (List<Map<String, Object>>) r.config().get("items");
    }

    private Map<String, Object> itemById(TestcaseConfigRecovery.Recovered r, String id) {
        return items(r).stream().filter(i -> id.equals(i.get("instance_id"))).findFirst()
                .orElseThrow(() -> new AssertionError("Thiếu item " + id + " trong " + items(r)));
    }

    @Test
    void dungLaiDuTestcaseThamSoVaHopDong(@TempDir Path dir) throws Exception {
        write(dir, "skills_matrix.json", MATRIX);
        write(dir, "contract.json", CONTRACT);

        TestcaseConfigRecovery.Recovered r = TestcaseConfigRecovery.recover(dir);
        assertNotNull(r, "Có skills_matrix.json thì phải dựng lại được");
        assertEquals(2, items(r).size(), "Nhóm phải được trải thành testcase con: " + items(r));

        Map<String, Object> first = itemById(r, "PE_item_01");
        assertEquals("COMMON_WIDGET_VISIBLE", first.get("template_id"));
        assertEquals("UI_SCAFFOLD_APPBAR", first.get("skill_code"));
        assertEquals(20.0, first.get("weight"));
        assertEquals("Thấy widget screen.home", first.get("expected"));
        assertEquals(Boolean.TRUE, first.get("expected_custom"),
                "Giữ nguyên expected đã phát hành, không để bị sinh đè lúc lưu");
        assertEquals(Map.of("widgetKey", "screen.home", "targetType", "container"), first.get("parameters"));
        assertEquals(Boolean.TRUE, first.get("enabled"));

        // Testcase con của nhóm phải mang theo nhãn nhóm, nếu không màn Sửa mất luôn nhóm.
        Map<String, Object> child = itemById(r, "PE_item_02");
        assertEquals("PE_group_01", child.get("group_id"));
        assertEquals("Thêm người dùng", child.get("group_name"));
        assertEquals("intermediate", child.get("difficulty"));

        @SuppressWarnings("unchecked")
        Map<String, Object> contract = (Map<String, Object>) r.config().get("contract");
        assertEquals(Boolean.TRUE, contract.get("require_keys"));
        assertEquals(1, ((List<?>) contract.get("keys")).size());
        assertTrue(r.warnings().isEmpty(), "Dữ liệu đủ thì không cảnh báo: " + r.warnings());
    }

    @Test
    void bocLaiDuocThanCodeCuaTestcaseTuViet(@TempDir Path dir) throws Exception {
        write(dir, "skills_matrix.json", """
                {"PE_item_03": {"instance_id": "PE_item_03", "template_id": "CUSTOM_CODE",
                  "runner": "CUSTOM_CODE", "skill_code": "UI_SCAFFOLD_APPBAR",
                  "name": "Kiểm tra tay", "weight": 10, "parameters": {}}}
                """);
        write(dir, "exam_test.dart", EXAM_TEST);

        TestcaseConfigRecovery.Recovered r = TestcaseConfigRecovery.recover(dir);
        assertNotNull(r);
        String code = String.valueOf(itemById(r, "PE_item_03").get("custom_code"));

        // Phải bóc đúng thân code và trả lại đúng mức thụt đầu dòng ban đầu.
        assertEquals("await _boot(tester);\nexpect(_byKey('list.items'), findsOneWidget);", code);
        assertTrue(r.warnings().isEmpty(), "Bóc được code thì không cảnh báo: " + r.warnings());
    }

    @Test
    void baoRoKhiTestcaseTuVietMatCode(@TempDir Path dir) throws Exception {
        write(dir, "skills_matrix.json", """
                {"PE_item_03": {"instance_id": "PE_item_03", "template_id": "CUSTOM_CODE",
                  "runner": "CUSTOM_CODE", "name": "Kiểm tra tay", "weight": 10}}
                """);
        // Không có exam_test.dart → không lấy lại được code.

        TestcaseConfigRecovery.Recovered r = TestcaseConfigRecovery.recover(dir);
        assertNotNull(r);
        assertNull(itemById(r, "PE_item_03").get("custom_code"));
        assertEquals(1, r.warnings().size(), "Phải nói rõ testcase nào mất code: " + r.warnings());
        assertTrue(r.warnings().get(0).contains("PE_item_03"));
    }

    @Test
    void thieuFileHoacFileHongThiKhongDungBua(@TempDir Path dir) throws Exception {
        assertNull(TestcaseConfigRecovery.recover(dir), "Không có skills_matrix.json thì không dựng");
        assertNull(TestcaseConfigRecovery.recover(null));

        write(dir, "skills_matrix.json", "{ day khong phai json }");
        assertNull(TestcaseConfigRecovery.recover(dir), "File hỏng thì thà không dựng còn hơn dựng sai");

        write(dir, "skills_matrix.json", "{}");
        assertNull(TestcaseConfigRecovery.recover(dir), "Matrix rỗng = không có gì để sửa");
    }

    @Test
    void thieuTemplateIdThiBoQuaVaBaoLai(@TempDir Path dir) throws Exception {
        write(dir, "skills_matrix.json", """
                {"A": {"instance_id": "A", "runner": "WIDGET_VISIBLE", "weight": 5},
                 "B": {"instance_id": "B", "template_id": "COMMON_WIDGET_VISIBLE",
                       "runner": "WIDGET_VISIBLE", "weight": 5, "parameters": {}}}
                """);

        TestcaseConfigRecovery.Recovered r = TestcaseConfigRecovery.recover(dir);
        assertNotNull(r);
        assertEquals(1, items(r).size(), "Dòng không có template_id phải bị bỏ, không đoán bừa");
        assertEquals("B", items(r).get(0).get("instance_id"));
        assertTrue(r.warnings().get(0).contains("A"), "Phải nói rõ đã bỏ dòng nào: " + r.warnings());
    }
}
