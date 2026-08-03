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
}
