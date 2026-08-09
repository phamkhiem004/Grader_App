package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bảo vệ hai chỗ dễ vỡ của testcase "tự viết code": chèn code vào engine (thao tác chuỗi,
 * sai một ký tự là hỏng cả exam_test.dart) và kiểm tra tĩnh trước khi chèn.
 */
class CustomTestcaseCodeTest {

    private final TestcaseTemplateService service = new TestcaseTemplateService();

    private String inject(String engine, List<Map<String, Object>> items) throws Exception {
        Method method = TestcaseTemplateService.class
                .getDeclaredMethod("injectCustomTestcases", String.class, List.class);
        method.setAccessible(true);
        return (String) method.invoke(service, engine, items);
    }

    private Map<String, Object> item(String instanceId, String name, String code) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("instance_id", instanceId);
        item.put("name", name);
        item.put("custom_code", code);
        item.put("runner", "CUSTOM_CODE");
        item.put("enabled", true);
        return item;
    }

    private String engineStub() {
        return """
                void main() {
                  _registerCustomTestcase(testId);
                }

                // ─────────────────── CUSTOM_TESTCASES_BEGIN ───────────────────
                // ghi chú cũ
                void _registerCustomTestcase(String testId) {}
                // ──────────────────── CUSTOM_TESTCASES_END ────────────────────

                Future<void> _boot(WidgetTester tester) async {}
                """;
    }

    @Test
    void injectsTestWidgetsAndKeepsCodeAroundTheMarkers() throws Exception {
        String out = inject(engineStub(), List.of(
                item("PE01_custom_01", "Kiểm tra tiêu đề", "await _boot(tester);\nexpect(1, 1);")));

        assertTrue(out.startsWith("void main() {"), "phần đầu engine phải giữ nguyên");
        assertTrue(out.endsWith("Future<void> _boot(WidgetTester tester) async {}\n"),
                "phần sau vùng chèn phải giữ nguyên, thực tế:\n" + out);
        assertTrue(out.contains("testWidgets('PE01_custom_01', (tester) async {"));
        assertTrue(out.contains("        await _boot(tester);"), "code phải được thụt vào 8 khoảng trắng");
        assertFalse(out.contains("ghi chú cũ"), "vùng cũ phải bị thay hoàn toàn");
        assertEquals(1, countMarkers(out, "CUSTOM_TESTCASES_BEGIN"));
        assertEquals(1, countMarkers(out, "CUSTOM_TESTCASES_END"));
        assertBalanced(out);
    }

    /** Mỗi testcase phải nằm trong một nhánh case riêng để đăng ký đúng vị trí trong đề. */
    @Test
    void registersEachTestcaseUnderItsOwnCaseLabel() throws Exception {
        String out = inject(engineStub(), List.of(
                item("PE01_custom_01", "Nạp dữ liệu", "expect(1, 1);"),
                item("PE01_custom_02", "Kiểm tra danh sách", "expect(2, 2);")));

        assertTrue(out.contains("switch (testId) {"));
        assertTrue(out.contains("    case 'PE01_custom_01':"));
        assertTrue(out.contains("    case 'PE01_custom_02':"));
        assertTrue(out.indexOf("PE01_custom_01") < out.indexOf("PE01_custom_02"),
                "thứ tự trong đề phải được giữ nguyên");
        assertEquals(2, countMarkers(out, "      return;"));
        assertBalanced(out);
    }

    @Test
    void emptyListStillLeavesCompilableRegisterFunction() throws Exception {
        String out = inject(engineStub(), List.of());
        assertTrue(out.contains("void _registerCustomTestcase(String testId) {}"));
        assertBalanced(out);
    }

    @Test
    void injectionCanRunTwiceWithoutDuplicatingRegion() throws Exception {
        String once = inject(engineStub(), List.of(item("A_custom_01", "A", "expect(1, 1);")));
        String twice = inject(once, List.of(item("B_custom_01", "B", "expect(2, 2);")));

        assertEquals(1, countMarkers(twice, "CUSTOM_TESTCASES_BEGIN"));
        assertFalse(twice.contains("A_custom_01"), "lần chèn sau phải thay sạch lần trước");
        assertTrue(twice.contains("B_custom_01"));
        assertBalanced(twice);
    }

    @Test
    void rejectsCodeThatWouldBreakTheGeneratedFile() {
        assertThrows(IllegalArgumentException.class,
                () -> service.validateCustomCode("import 'dart:io';\nexpect(1, 1);", "T"));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateCustomCode("testWidgets('X', (t) async {});", "T"));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateCustomCode("group('X', () {});", "T"));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateCustomCode("expect(1, 1);\nif (true) {", "T"));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateCustomCode("expect('chua dong, 1);", "T"));
        assertThrows(IllegalArgumentException.class, () -> service.validateCustomCode("   ", "T"));
    }

    @Test
    void acceptsRealisticTestBodies() {
        service.validateCustomCode("""
                await _boot(tester);
                await tester.enterText(_byKey('field.email'), 'a@b.com');
                await tester.tap(_byKey('action.save'));
                await _settle(tester);
                expect(_byKey('message.success'), findsOneWidget);
                """, "T");
        // Chuỗi lồng trong interpolation và raw string không được coi là lỗi ngoặc.
        service.validateCustomCode("""
                final map = {'k': 'v'};
                expect('${map['k']}', 'v');
                expect(RegExp(r'^[a-z]+$').hasMatch('abc'), isTrue);
                // dấu ) trong chú thích không tính
                """, "T");
        service.validateCustomCode("""
                addTearDown(() => debugPrint('done'));
                expect('''
                nhieu dong
                ''', isNotEmpty);
                """, "T");
    }

    private int countMarkers(String source, String marker) {
        int count = 0;
        int at = source.indexOf(marker);
        while (at >= 0) {
            count++;
            at = source.indexOf(marker, at + marker.length());
        }
        return count;
    }

    /** File sinh ra phải cân bằng ngoặc nhọn, nếu không Dart sẽ không biên dịch được. */
    private void assertBalanced(String source) {
        int depth = 0;
        for (char c : source.toCharArray()) {
            if (c == '{') depth++;
            else if (c == '}') depth--;
            assertTrue(depth >= 0, "thừa dấu } trong file sinh ra");
        }
        assertEquals(0, depth, "file sinh ra lệch ngoặc nhọn");
    }
}
