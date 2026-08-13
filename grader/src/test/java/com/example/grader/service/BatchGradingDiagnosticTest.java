package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchGradingDiagnosticTest {

    @Test
    void trustedStudentTimeoutRemainsAStudentResult() throws Exception {
        GradingDiagnosticException diagnostic = diagnose("""
                {"grading_result":{
                  "diagnostic_code":"STUDENT_APP_BOOT_TIMEOUT",
                  "diagnostic_origin":"STUDENT",
                  "diagnostic_stage":"APP_BOOT",
                  "diagnostic_message":"main() không hoàn tất",
                  "requires_manual_review":false
                }}
                """, 0f);

        assertEquals("STUDENT_APP_BOOT_TIMEOUT", diagnostic.code());
        assertEquals(GradingDiagnosticException.Origin.STUDENT, diagnostic.origin());
        assertFalse(diagnostic.manualReview());
    }

    @Test
    void testcaseTimeoutCannotBecomeStudentZero() throws Exception {
        GradingDiagnosticException diagnostic = diagnose("""
                {"grading_result":{
                  "diagnostic_code":"TESTCASE_EXECUTION_TIMEOUT",
                  "diagnostic_origin":"TESTCASE",
                  "diagnostic_stage":"TESTCASE_ASSERTION",
                  "diagnostic_message":"runner bị kẹt",
                  "requires_manual_review":true
                }}
                """, 0f);

        assertEquals(GradingDiagnosticException.Origin.TESTCASE, diagnostic.origin());
        assertTrue(diagnostic.manualReview());
    }

    @Test
    void compileDiagnosticIncludesTheFirstConcreteCompilerError() throws Exception {
        GradingDiagnosticException diagnostic = diagnose("""
                {"grading_result":{
                  "diagnostic_code":"STUDENT_COMPILE_ERROR",
                  "diagnostic_origin":"STUDENT",
                  "diagnostic_stage":"SOURCE_COMPILE",
                  "diagnostic_message":"Mã nguồn bài sinh viên không biên dịch được.",
                  "runner_error":"lib/viewmodels/user_view_model.dart:83:31: Error: A value of type SqliteUserRepository cannot be returned as UserRepository",
                  "requires_manual_review":false
                }}
                """, 0f);

        assertEquals("STUDENT_COMPILE_ERROR", diagnostic.code());
        assertTrue(diagnostic.teacherMessage().contains("user_view_model.dart:83:31"));
        assertTrue(diagnostic.teacherMessage().contains("SqliteUserRepository"));
    }

    @Test
    void sourceContractObservationIsReportedEvenWithPartialScore() throws Exception {
        GradingDiagnosticException diagnostic = diagnose("""
                {
                  "grading_result":{},
                  "test_cases":[{
                    "name":"Model đúng contract",
                    "status":"failed",
                    "actual":"Thiếu symbol bắt buộc",
                    "observation":{"kind":"SOURCE_CONTRACT_VIOLATION"}
                  }]
                }
                """, 9f);

        assertEquals("CONTRACT_VIOLATION", diagnostic.code());
        assertEquals(GradingDiagnosticException.Origin.STUDENT, diagnostic.origin());
        assertEquals("SOURCE_CONTRACT", diagnostic.stage());
        assertFalse(diagnostic.manualReview());
    }

    @Test
    void forbiddenSourcePolicyGetsItsOwnBlockingDiagnostic() throws Exception {
        GradingDiagnosticException diagnostic = diagnose("""
                {
                  "grading_result":{},
                  "test_cases":[{
                    "name":"Không thêm class bị cấm",
                    "status":"failed",
                    "actual":"Source lib/models/user.dart chứa token bị cấm: class LegacyUser",
                    "observation":{"kind":"SOURCE_POLICY_VIOLATION"}
                  }]
                }
                """, 8f);

        assertEquals("SOURCE_POLICY_VIOLATION", diagnostic.code());
        assertEquals(GradingDiagnosticException.Origin.STUDENT, diagnostic.origin());
        assertEquals("SOURCE_POLICY", diagnostic.stage());
    }

    @Test
    void ordinaryZeroContainsExecutedAndFirstFailureEvidence() throws Exception {
        GradingDiagnosticException diagnostic = diagnose("""
                {
                  "grading_result":{},
                  "test_cases":[{
                    "name":"Hiển thị danh sách",
                    "status":"failed",
                    "executed":true,
                    "actual":"không thấy danh sách nào",
                    "observation":{"kind":"MISSING"}
                  }]
                }
                """, 0f);

        assertEquals("REQUIREMENTS_NOT_MET", diagnostic.code());
        assertEquals(GradingDiagnosticException.Origin.STUDENT, diagnostic.origin());
        assertFalse(diagnostic.manualReview());
        assertTrue(diagnostic.teacherMessage().contains("Hiển thị danh sách"));
    }

    @Test
    void bootOverflowIsReportedAsLayoutProblemInsteadOfGenericCrash() throws Exception {
        GradingDiagnosticException diagnostic = diagnose("""
                {
                  "grading_result":{},
                  "test_cases":[{
                    "name":"Ứng dụng khởi động không lỗi",
                    "status":"failed",
                    "executed":true,
                    "actual":"Expected: null Actual: FlutterError:<A RenderFlex overflowed by 90 pixels on the bottom.>",
                    "observation":{"kind":"BOOT_FAILED"}
                  }]
                }
                """, 0f);

        assertEquals("LAYOUT_OVERFLOW", diagnostic.code());
        assertEquals(GradingDiagnosticException.Origin.STUDENT, diagnostic.origin());
        assertEquals("UI_LAYOUT", diagnostic.stage());
        assertFalse(diagnostic.manualReview());
    }

    @Test
    void caseContractDiagnosticUsesStudentSourceStage() throws Exception {
        Map<String, Object> testcase = new LinkedHashMap<>();
        testcase.put("error_code", "CONTRACT_VIOLATION");
        Method method = BatchGradingService.class.getDeclaredMethod(
                "attachCaseDiagnostic", Map.class, String.class);
        method.setAccessible(true);
        method.invoke(new BatchGradingService(), testcase, "failed");

        assertEquals("STUDENT", testcase.get("error_origin"));
        assertEquals("SOURCE_CONTRACT_OR_COMPILE", testcase.get("error_stage"));
        assertEquals(false, testcase.get("requires_manual_review"));
    }

    private GradingDiagnosticException diagnose(String json, float score) throws Exception {
        Method method = BatchGradingService.class.getDeclaredMethod(
                "diagnoseGraderResult", String.class, float.class);
        method.setAccessible(true);
        return (GradingDiagnosticException) method.invoke(new BatchGradingService(), json, score);
    }
}
