package com.example.grader.service;

import java.util.Map;

/**
 * Câu tiếng Việt cho từng mã sự cố HỆ THỐNG — thứ duy nhất người chấm cần đọc.
 *
 * <p>Chỉ liệt kê mã có {@code origin != STUDENT}. Mã phía sinh viên cố ý KHÔNG có ở đây: bài làm
 * sai đã được diễn đạt bằng điểm số và bảng testcase, đưa lên cột sự cố nữa chỉ làm loãng đúng thứ
 * người chấm cần thấy.
 *
 * <p>Thiếu mã trong bảng không phải lỗi — {@link #label} rơi về câu theo {@code origin}, vì mã mới
 * vẫn phải hiện lên màn hình chứ không được im lặng biến mất.
 */
final class SystemIncidentCatalog {

    private SystemIncidentCatalog() {}

    private static final Map<String, String> LABELS = Map.ofEntries(
            // ── Bộ testcase ──
            Map.entry("TESTCASE_FILES_MISSING",      "Thiếu thư mục testcase đã cấu hình cho đề"),
            Map.entry("TESTCASE_COMPILE_ERROR",      "Bộ testcase không biên dịch được"),
            Map.entry("TESTCASE_RUNNER_ERROR",       "Bộ testcase không khởi động đúng"),
            Map.entry("TESTCASE_EXECUTION_TIMEOUT",  "Code trong testcase chạy quá thời gian"),
            // ── Môi trường chấm ──
            Map.entry("DOCKER_START_FAILED",         "Không khởi động được container chấm"),
            Map.entry("GRADING_ENVIRONMENT_ERROR",   "Môi trường chấm lỗi (Docker / hết đĩa / hết bộ nhớ)"),
            Map.entry("CONTAINER_WATCHDOG_TIMEOUT",  "Container chấm vượt watchdog, phải giết ngang"),
            Map.entry("GRADER_TOTAL_TIMEOUT",        "Bộ chấm hết ngân sách thời gian, còn testcase chưa chạy"),
            Map.entry("SUBMISSION_FILE_LOST",        "Mất file bài nộp trên đĩa"),
            // ── Chưa xác định được nguồn ──
            Map.entry("TEST_PROCESS_TIMEOUT",        "Tiến trình testcase bị timeout, chưa rõ do bài hay do máy"),
            Map.entry("GRADING_TIMEOUT_UNDETERMINED","Timeout khi chấm, chưa đủ bằng chứng quy nguồn"),
            Map.entry("SUITE_STARTUP_UNDETERMINED",  "Bộ test không khởi động, chưa rõ do bài hay do testcase"),
            Map.entry("COMPILE_ERROR_UNDETERMINED",  "Không biên dịch được nhưng log không chỉ rõ nguồn"),
            Map.entry("ZERO_SCORE_UNCLASSIFIED",     "Kết quả chấm thiếu dữ liệu để xác định nguyên nhân"),
            Map.entry("UNCLASSIFIED_GRADING_ERROR",  "Lỗi trong luồng chấm, chưa phân loại được"));

    static String label(String code, String origin) {
        String known = code == null ? null : LABELS.get(code.toUpperCase());
        if (known != null) return known;
        return switch (originLabel(origin)) {
            case "Bộ testcase"     -> "Sự cố bộ testcase";
            case "Môi trường chấm" -> "Sự cố môi trường chấm";
            default                -> "Sự cố chưa xác định nguồn";
        };
    }

    static String originLabel(String origin) {
        if (origin == null) return "Chưa xác định";
        return switch (origin.toUpperCase()) {
            case "TESTCASE"    -> "Bộ testcase";
            case "ENVIRONMENT" -> "Môi trường chấm";
            case "STUDENT"     -> "Bài sinh viên";
            default            -> "Chưa xác định";
        };
    }
}
