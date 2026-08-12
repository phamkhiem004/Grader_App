package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chốt các cơ chế an toàn tối thiểu của engine Dart được chép vào từng đề. */
class CommonEngineExecutionTest {

    @Test
    void graderRunsSmallBatchesInTimeLimitedProcesses() throws Exception {
        String grader = resource("/common-testcase-engine/grader.dart");

        assertTrue(grader.contains("Process.start("),
                "grader phải dùng Process.start để có thể dừng process bị treo");
        assertTrue(grader.contains("GRADER_BATCH_SIZE"));
        assertTrue(grader.contains("GRADER_BATCH_TIMEOUT_SECONDS"));
        assertTrue(grader.contains("GRADER_TOTAL_TIMEOUT_SECONDS"));
        assertTrue(grader.contains("GRADER_CASE_MODE"));
        assertTrue(grader.contains("GRADER_CASE_IDS"));
        assertTrue(grader.contains("--concurrency=1"));
        assertTrue(grader.contains("process.exitCode.timeout("));
        assertFalse(grader.contains("final process = await Process.run("),
                "không được quay lại chạy cả suite bằng một Process.run không timeout");
    }

    @Test
    void examRegistersOnlyTheRequestedBatchInIsolatedMode() throws Exception {
        String exam = resource("/common-testcase-engine/exam_test.dart");

        assertTrue(exam.contains("Platform.environment['GRADER_CASE_MODE']"));
        assertTrue(exam.contains("Platform.environment['GRADER_CASE_IDS']"));
        assertTrue(exam.contains("!selectedBatch.contains(testId)"));
    }

    private String resource(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "Không tìm thấy resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
