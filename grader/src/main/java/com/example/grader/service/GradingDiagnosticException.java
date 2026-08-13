package com.example.grader.service;

/**
 * Lỗi có cấu trúc của một lượt chấm. Khác RuntimeException thông thường ở chỗ
 * nó nói rõ lỗi phát sinh ở đâu, thuộc phía nào và có cần chuyển sang chấm tay hay không.
 */
public final class GradingDiagnosticException extends RuntimeException {

    public enum Origin {
        STUDENT,
        TESTCASE,
        ENVIRONMENT,
        UNDETERMINED
    }

    private final String code;
    private final Origin origin;
    private final String stage;
    private final boolean manualReview;

    public GradingDiagnosticException(String code, Origin origin, String stage,
                                      boolean manualReview, String message) {
        super(message);
        this.code = code;
        this.origin = origin;
        this.stage = stage;
        this.manualReview = manualReview;
    }

    public GradingDiagnosticException(String code, Origin origin, String stage,
                                      boolean manualReview, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.origin = origin;
        this.stage = stage;
        this.manualReview = manualReview;
    }

    public String code() {
        return code;
    }

    public Origin origin() {
        return origin;
    }

    public String stage() {
        return stage;
    }

    public boolean manualReview() {
        return manualReview;
    }

    /** Chuỗi ngắn, ổn định để lưu DB và hiển thị cho giảng viên. */
    public String teacherMessage() {
        return "[" + code + "][" + origin + "][" + stage + "] " + getMessage();
    }
}
