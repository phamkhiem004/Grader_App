package com.example.grader.service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Classifies a failed Flutter compile from concrete source-path evidence. */
final class GradingCompileDiagnostic {

    private static final Pattern STUDENT_SOURCE = Pattern.compile(
            "(?im)(?:^|[\\s'\"])(?:/app/|\\.\\./)?lib/[^\\s:'\"]+\\.dart(?::\\d+:\\d+)?");
    private static final Pattern TESTCASE_SOURCE = Pattern.compile(
            "(?im)(?:^|[\\s'\"])(?:/app/)?test/(?:exam_test|grader)\\.dart(?::\\d+:\\d+)?");

    private GradingCompileDiagnostic() {}

    static GradingDiagnosticException classify(String rawLog) {
        String log = rawLog == null ? "" : rawLog.trim();
        String low = log.toLowerCase(Locale.ROOT);
        String evidence = TestErrorClassifier.shorten(log, 4_000);

        if (low.contains("cannot connect to the docker daemon")
                || low.contains("error response from daemon")
                || low.contains("no space left on device")
                || low.contains("cannot allocate memory")) {
            return new GradingDiagnosticException(
                    "GRADING_ENVIRONMENT_ERROR",
                    GradingDiagnosticException.Origin.ENVIRONMENT,
                    "SOURCE_COMPILE",
                    true,
                    "Môi trường chấm không thể biên dịch/chạy Flutter: " + evidence);
        }

        boolean studentEvidence = STUDENT_SOURCE.matcher(log).find()
                || low.contains("package:exam_project/")
                || low.contains("error when reading '../lib/")
                || low.contains("error when reading 'lib/");
        boolean testcaseEvidence = TESTCASE_SOURCE.matcher(log).find();
        boolean publicContractEvidence = low.contains("student_app.")
                && (low.contains("method not found")
                    || low.contains("getter not found")
                    || low.contains("undefined name")
                    || low.contains("isn't defined"));

        // A compiler often reports test/exam_test.dart because it imports the
        // student app, then gives the real missing/invalid source under lib/.
        // Student-source evidence therefore wins when both paths are present.
        if (studentEvidence) {
            return new GradingDiagnosticException(
                    "STUDENT_COMPILE_ERROR",
                    GradingDiagnosticException.Origin.STUDENT,
                    "SOURCE_COMPILE",
                    false,
                    "Mã nguồn bài sinh viên không biên dịch được: " + evidence);
        }
        if (publicContractEvidence) {
            return new GradingDiagnosticException(
                    "STUDENT_CONTRACT_COMPILE_ERROR",
                    GradingDiagnosticException.Origin.STUDENT,
                    "SOURCE_CONTRACT",
                    false,
                    "Bài sinh viên thiếu hoặc đổi tên symbol public mà contract yêu cầu: " + evidence);
        }
        if (testcaseEvidence) {
            return new GradingDiagnosticException(
                    "TESTCASE_COMPILE_ERROR",
                    GradingDiagnosticException.Origin.TESTCASE,
                    "SOURCE_COMPILE",
                    true,
                    "Bộ testcase không biên dịch được; không quy thành 0 điểm sinh viên: " + evidence);
        }
        return new GradingDiagnosticException(
                "COMPILE_ERROR_UNDETERMINED",
                GradingDiagnosticException.Origin.UNDETERMINED,
                "SOURCE_COMPILE",
                true,
                "Không biên dịch được nhưng log không chỉ ra chắc chắn lỗi nằm trong lib/ hay testcase: "
                        + evidence);
    }
}
