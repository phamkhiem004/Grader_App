package com.example.grader.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradingCompileDiagnosticTest {

    @Test
    void studentLibErrorWinsOverImportSiteInExamTest() {
        GradingDiagnosticException result = GradingCompileDiagnostic.classify("""
                test/exam_test.dart:10:8: Error: Error when reading '../lib/main.dart': No such file
                import '../lib/main.dart' as student_app;
                ../lib/screens/home.dart:12:7: Error: Type 'MissingStudentClass' not found.
                """);

        assertEquals("STUDENT_COMPILE_ERROR", result.code());
        assertEquals(GradingDiagnosticException.Origin.STUDENT, result.origin());
        assertFalse(result.manualReview());
    }

    @Test
    void pureTestcaseCompileErrorRequestsManualReview() {
        GradingDiagnosticException result = GradingCompileDiagnostic.classify(
                "test/grader.dart:309:20: Error: Method not found: '_asList'.");

        assertEquals("TESTCASE_COMPILE_ERROR", result.code());
        assertEquals(GradingDiagnosticException.Origin.TESTCASE, result.origin());
        assertTrue(result.manualReview());
    }

    @Test
    void dockerFailureIsEnvironmentError() {
        GradingDiagnosticException result = GradingCompileDiagnostic.classify(
                "Cannot connect to the Docker daemon at unix:///var/run/docker.sock");

        assertEquals("GRADING_ENVIRONMENT_ERROR", result.code());
        assertEquals(GradingDiagnosticException.Origin.ENVIRONMENT, result.origin());
        assertTrue(result.manualReview());
    }

    @Test
    void missingPublishedStudentCallableIsStudentContractViolation() {
        GradingDiagnosticException result = GradingCompileDiagnostic.classify("""
                test/exam_test.dart:200:31: Error: Method not found: 'calculateTotal'.
                final actual = student_app.calculateTotal();
                """);

        assertEquals("STUDENT_CONTRACT_COMPILE_ERROR", result.code());
        assertEquals(GradingDiagnosticException.Origin.STUDENT, result.origin());
        assertEquals("SOURCE_CONTRACT", result.stage());
        assertFalse(result.manualReview());
    }

    @Test
    void compileFailureWithoutOwnershipEvidenceNeedsManualReview() {
        GradingDiagnosticException result = GradingCompileDiagnostic.classify(
                "Compilation failed with an internal frontend exception");

        assertEquals("COMPILE_ERROR_UNDETERMINED", result.code());
        assertEquals(GradingDiagnosticException.Origin.UNDETERMINED, result.origin());
        assertTrue(result.manualReview());
    }
}
