package com.example.grader.config;

/**
 * App chạy KHÔNG đăng nhập (một người dùng duy nhất trên máy cục bộ).
 * Các cột audit sẵn có (exam.created_by, grading_batch.created_by,
 * testcase_template.created_by, exam_result.manual_by) vẫn được điền
 * bằng tên cố định này để dữ liệu cũ/mới cùng schema và UI vẫn có gì để hiện.
 */
public final class AppActor {

    /** Tên người thao tác mặc định, thay cho email lấy từ token trước đây. */
    public static final String DEFAULT = "teacher";

    private AppActor() { }
}
