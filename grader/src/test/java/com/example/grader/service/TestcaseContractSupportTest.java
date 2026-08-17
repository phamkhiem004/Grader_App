package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestcaseContractSupportTest {

    @Test
    void newContractRequiresSemanticKeysButLegacyExplicitFalseIsPreserved() {
        assertEquals(true, TestcaseContractSupport.normalize(null).get("require_keys"),
                "Bộ mới phải trỏ UI bằng ValueKey để không rơi vào heuristic theo vị trí");
        assertEquals(false, TestcaseContractSupport.normalize(Map.of(
                "require_keys", false,
                "keys", java.util.List.of())).get("require_keys"),
                "Không được tự đổi contract của đề legacy đã lưu");
    }

    @Test
    void normalizeKeepsDependencyPolicyAndRejectsInvalidPackageNames() {
        Map<String, Object> normalized = TestcaseContractSupport.normalize(Map.of(
                "require_keys", false,
                "keys", List.of(),
                "allowed_packages", List.of("flutter", "sqflite", "flutter_riverpod"),
                "local_package_names", List.of("user_manager_exam_standard")));

        assertEquals(List.of("flutter", "sqflite", "flutter_riverpod"),
                normalized.get("allowed_packages"));
        assertEquals(List.of("user_manager_exam_standard"),
                normalized.get("local_package_names"));
        assertThrows(IllegalArgumentException.class, () -> TestcaseContractSupport.normalize(Map.of(
                "keys", List.of(), "allowed_packages", List.of("bad-package"))));
    }

    @Test
    void requiredKeyContractRejectsASelectedRunnerWhoseAddressWasNotPublished() throws Exception {
        TestcaseTemplateService service = new TestcaseTemplateService();
        Method validate = TestcaseTemplateService.class.getDeclaredMethod(
                "validateContractCoversSelectedKeys", List.class, Map.class);
        validate.setAccessible(true);
        List<Map<String, Object>> items = List.of(Map.of(
                "runner", "WIDGET_VISIBLE",
                "enabled", true,
                "parameters", Map.of("widgetKey", "screen.home")));
        Map<String, Object> missing = Map.of("require_keys", true, "keys", List.of());

        try {
            validate.invoke(service, items, missing);
            throw new AssertionError("Contract thiếu screen.home phải bị chặn");
        } catch (InvocationTargetException error) {
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
        }

        validate.invoke(service, items, Map.of(
                "require_keys", true,
                "keys", List.of(Map.of("key", "screen.home"))));
        validate.invoke(service, items, Map.of("require_keys", false, "keys", List.of()));
    }

    /**
     * Câu báo lỗi phải liệt kê nguyên vẹn key còn thiếu: màn hình soạn testcase đọc chính câu này
     * để khai hộ key vào Khu vực 0. Đổi cách viết thì phải sửa cả chỗ đọc bên frontend.
     */
    @Test
    void missingKeyErrorNamesEveryKeySoTheEditorCanDeclareThem() throws Exception {
        TestcaseTemplateService service = new TestcaseTemplateService();
        Method validate = TestcaseTemplateService.class.getDeclaredMethod(
                "validateContractCoversSelectedKeys", List.class, Map.class);
        validate.setAccessible(true);
        // Testcase xóa item: key nằm ở deleteKey — một tham số mà trước đây frontend không dò tới.
        List<Map<String, Object>> items = List.of(Map.of(
                "runner", "CRUD_DELETE_FLOW",
                "enabled", true,
                "parameters", Map.of(
                        "listKey", "screen.list",
                        "deleteKey", "action.item.delete")));

        try {
            validate.invoke(service, items, Map.of("require_keys", true,
                    "keys", List.of(Map.of("key", "screen.list"))));
            throw new AssertionError("Contract thiếu action.item.delete phải bị chặn");
        } catch (InvocationTargetException error) {
            assertInstanceOf(IllegalArgumentException.class, error.getCause());
            String message = error.getCause().getMessage();
            assertEquals(true, message.contains("key testcase đang dùng: action.item.delete."),
                    "frontend cắt danh sách key theo đúng cụm này: " + message);
        }

        validate.invoke(service, items, Map.of("require_keys", true, "keys", List.of(
                Map.of("key", "screen.list"), Map.of("key", "action.item.delete"))));
    }
}
