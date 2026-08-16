package com.example.grader.service;

import com.example.grader.dto.ResultRow;
import com.example.grader.entity.GradingStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chốt chặn cho trục quyết định mới: người chấm chỉ được báo về sự cố HỆ THỐNG, và sự cố phải
 * gom theo nguyên nhân chứ không rải theo từng bài.
 */
class SystemIncidentReportTest {

    @Test
    void studentFaultIsNeverAnIncidentEvenWhenItAsksForManualReview() throws Exception {
        // Đúng ca EXTERNAL_PACKAGE cũ: origin STUDENT nhưng cờ manualReview lại bật. Trước đây
        // cờ đó quyết định trạng thái nên bài sai hướng dẫn đi thẳng vào danh sách chấm tay.
        assertFalse(systemFault(new GradingDiagnosticException(
                "EXTERNAL_PACKAGE", GradingDiagnosticException.Origin.STUDENT,
                "DEPENDENCY_PREFLIGHT", true, "dùng package ngoài")));
    }

    @Test
    void nonStudentFaultIsAnIncidentEvenWhenTheFlagIsOff() throws Exception {
        assertTrue(systemFault(new GradingDiagnosticException(
                "GRADER_TOTAL_TIMEOUT", GradingDiagnosticException.Origin.ENVIRONMENT,
                "GRADER_TOTAL", false, "hết ngân sách thời gian")));
        assertTrue(systemFault(new GradingDiagnosticException(
                "TEST_PROCESS_TIMEOUT", GradingDiagnosticException.Origin.UNDETERMINED,
                "TESTCASE_EXECUTION", false, "tiến trình treo")));
    }

    @Test
    void noDiagnosticMeansNoIncident() throws Exception {
        assertFalse(systemFault(null));
    }

    @Test
    void incidentsAreGroupedByCauseWithTheBiggestGroupFirst() throws Exception {
        List<Map<String, Object>> incidents = incidents(List.of(
                row("SE001", GradingStatus.DONE, 0f, null, null),
                row("SE002", GradingStatus.MANUAL_REVIEW, null, "GRADER_TOTAL_TIMEOUT", "ENVIRONMENT"),
                row("SE003", GradingStatus.MANUAL_REVIEW, null, "TESTCASE_RUNNER_ERROR", "TESTCASE"),
                row("SE004", GradingStatus.MANUAL_REVIEW, null, "GRADER_TOTAL_TIMEOUT", "ENVIRONMENT"),
                row("SE005", GradingStatus.CANCELLED, null, null, null)));

        assertEquals(2, incidents.size());
        Map<String, Object> first = incidents.get(0);
        assertEquals("GRADER_TOTAL_TIMEOUT", first.get("code"));
        assertEquals(2, first.get("count"));
        assertEquals(List.of("SE002", "SE004"), first.get("studentIds"));
        assertEquals("Môi trường chấm", first.get("originLabel"));
        // Nhãn phải là câu người chấm đọc được, không phải mã trần.
        assertEquals("Bộ chấm hết ngân sách thời gian, còn testcase chưa chạy", first.get("label"));
        assertEquals("TESTCASE_RUNNER_ERROR", incidents.get(1).get("code"));
    }

    @Test
    void aZeroScoredSubmissionNeverReachesTheGraderScreen() throws Exception {
        // Bài sai hết: DONE 0 điểm kèm chẩn đoán REQUIREMENTS_NOT_MET. Đó là kết quả, không
        // phải sự cố — nếu nó lọt vào đây thì cảnh báo sẽ ngập bài sinh viên làm sai.
        assertTrue(incidents(List.of(
                row("SE010", GradingStatus.DONE, 0f, "REQUIREMENTS_NOT_MET", "STUDENT"))).isEmpty());
    }

    private static ResultRow row(String studentId, GradingStatus status, Float score,
                                 String code, String origin) {
        return new ResultRow(1L, studentId, studentId, status, score, null, "log",
                code, origin, "TESTCASE_EXECUTION", false, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> incidents(List<ResultRow> rows) throws Exception {
        Method method = BatchGradingService.class.getDeclaredMethod("systemIncidents", List.class);
        method.setAccessible(true);
        return (List<Map<String, Object>>) method.invoke(new BatchGradingService(), rows);
    }

    private static boolean systemFault(GradingDiagnosticException diagnostic) throws Exception {
        Method method = BatchGradingService.class.getDeclaredMethod(
                "isSystemFault", GradingDiagnosticException.class);
        method.setAccessible(true);
        return (boolean) method.invoke(new BatchGradingService(), diagnostic);
    }
}
