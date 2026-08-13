package com.example.grader.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestErrorClassifierTest {

    private final TestErrorClassifier classifier = new TestErrorClassifier();

    @Test
    void doesNotExposeTestDescriptionAsActual() {
        TestErrorClassifier.Result result = classifier.classify("""
                Test failed. See exception logs above.
                The test description was:
                  SCREEN_VALIDATE_EACH_FIELD
                """);

        assertEquals("EXCEPTION_THROWN", result.code());
        assertTrue(result.actual().contains("Không có giá trị actual"));
        assertFalse(result.actual().contains("SCREEN_VALIDATE_EACH_FIELD"));
    }

    @Test
    void keepsScalarActualSeparateFromReason() {
        TestErrorClassifier.Result result = classifier.classify("""
                Expected: <true>
                Actual: <false>
                Which: is not true
                Form phải hợp lệ
                The test description was: SCREEN_FORM
                """);

        assertEquals("VALUE_MISMATCH", result.code());
        assertEquals("false", result.actual());
        assertFalse(result.message().contains("SCREEN_FORM"));
    }

    @Test
    void classifiesDependencyResolutionAsCompileError() {
        TestErrorClassifier.Result result = classifier.classify(
                "Error: Couldn't resolve the package 'sqflite_common_ffi' in 'package:sqflite_common_ffi/sqflite_ffi.dart'.");

        assertEquals("COMPILE_ERROR", result.code());
        assertTrue(result.actual().contains("sqflite_common_ffi"));
    }

    @Test
    void classifiesPublishedSourceContractViolationSeparately() {
        TestErrorClassifier.Result result = classifier.classify(
                "Khong tim thay source contract lib/repositories/user_repository.dart");

        assertEquals("CONTRACT_VIOLATION", result.code());
    }

    @Test
    void classifiesForbiddenSourcePolicySeparatelyFromMissingContract() {
        TestErrorClassifier.Result result = classifier.classify(
                "Source lib/models/user.dart chứa token bị cấm: class LegacyUser");

        assertEquals("SOURCE_POLICY_VIOLATION", result.code());
    }
}
