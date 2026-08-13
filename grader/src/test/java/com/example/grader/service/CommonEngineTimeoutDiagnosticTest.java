package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonEngineTimeoutDiagnosticTest {

    @Test
    void appBootRunsAsDedicatedPreflightAndTimeoutCarriesOrigin() throws Exception {
        String grader = resource("/common-testcase-engine/grader.dart");

        assertTrue(grader.contains("appBootId"));
        assertTrue(grader.contains("batches.add(<String>[appBootId])"));
        assertTrue(grader.contains("_timeoutDiagnostic("));
        assertTrue(grader.contains("'STUDENT_APP_BOOT'"));
        assertTrue(grader.contains("'origin': 'STUDENT'"));
        assertTrue(grader.contains("'TESTCASE_SOURCE_CHECK'"));
        assertTrue(grader.contains("'origin': 'TESTCASE'"));
        assertTrue(grader.contains("'manual_review': false"),
                "timeout có marker STUDENT phải là lỗi bài, không đẩy sang chấm tay");
        assertTrue(grader.contains("STUDENT_COMPILE_ERROR"));
        assertTrue(grader.contains("STUDENT_CONTRACT_COMPILE_ERROR"));
        assertTrue(grader.contains("TESTCASE_COMPILE_ERROR"));
    }

    @Test
    void generatedEngineCanReportAllRequiredStages() throws Exception {
        String exam = resource("/common-testcase-engine/exam_test.dart");

        assertTrue(exam.contains("_stage('STUDENT_APP_BOOT')"));
        assertTrue(exam.contains("_stage('STUDENT_UI_ACTION')"));
        assertTrue(exam.contains("_stage('STUDENT_ASYNC_SETTLE')"));
        assertTrue(exam.contains("_stage('TESTCASE_ASSERTION')"));
        assertTrue(exam.contains("bool _sourceContainsToken"));
    }

    private String resource(String path) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, "Missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
