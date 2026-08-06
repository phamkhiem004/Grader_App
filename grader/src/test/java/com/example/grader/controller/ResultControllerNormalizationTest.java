package com.example.grader.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultControllerNormalizationTest {

    @Test
    void cleansLegacyDownloadedResult() throws Exception {
        ResultController controller = new ResultController();
        Method normalize = ResultController.class.getDeclaredMethod("normalizeJsonString", String.class);
        normalize.setAccessible(true);

        String legacy = """
                {
                  "test_cases": [{
                    "test_id": "SCREEN_VALIDATE_EACH_FIELD",
                    "status": "failed",
                    "expected": "Form phải hiển thị lỗi",
                    "expect": "Form phải hiển thị lỗi",
                    "actual": "Ném lỗi: The test description was: SCREEN_VALIDATE_EACH_FIELD",
                    "error": {"code": "EXCEPTION_THROWN", "message": "Code ném lỗi khi chạy"},
                    "student_safe_summary": "Code ném lỗi khi chạy"
                  }]
                }
                """;

        String normalized = (String) normalize.invoke(controller, legacy);

        assertFalse(normalized.contains("The test description was"));
        assertFalse(normalized.contains("\"expect\""));
        assertTrue(normalized.contains("Không có giá trị actual"));
        assertTrue(normalized.contains("Testcase dừng do exception;"));
    }

    /**
     * Đường ĐỌC chỉ được thêm khoá suy ra chắc chắn. Dữ liệu chấm trước P4 ghi mọi test chưa
     * chạy thành `failed`, nên bơm `executed = true` vào là NÓI SAI; và sự vắng mặt của
     * `executed`/`schema_version` chính là dấu hiệu "dữ liệu bản 1" mà bên đọc dựa vào.
     */
    @Test
    void addsOnlyKeysItCanDeriveAndNeverInventsExecuted() throws Exception {
        ResultController controller = new ResultController();
        Method normalize = ResultController.class.getDeclaredMethod("normalizeJsonString", String.class);
        normalize.setAccessible(true);

        String stored = """
                {
                  "test_cases": [
                    {"test_id": "A", "status": "failed", "actual": "x",
                     "error": {"code": "WIDGET_NOT_FOUND", "message": "y"}},
                    {"test_id": "B", "status": "passed", "actual": "Đã đáp ứng yêu cầu"}
                  ]
                }
                """;

        String out = (String) normalize.invoke(controller, stored);

        assertTrue(out.contains("\"error_code\" : \"WIDGET_NOT_FOUND\"")
                || out.contains("\"error_code\":\"WIDGET_NOT_FOUND\""), out);
        assertTrue(out.contains("\"blocked_by\""), out);
        assertFalse(out.contains("\"executed\""), "không được tự sinh executed cho dữ liệu cũ");
        assertFalse(out.contains("\"schema_version\""), "không được tự gắn schema_version cho dữ liệu cũ");
    }
}
