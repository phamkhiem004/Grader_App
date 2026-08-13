package com.example.grader.dto;

import com.example.grader.entity.GradingStatus;

/**
 * Dòng kết quả NHẸ cho /batch/progress (poll mỗi 3s) — KHÔNG kèm cột LONGTEXT result_json/manual_json
 * (vài chục KB/bài) để tránh tốn RAM/băng thông. Chỉ giữ field mà bảng tiến độ thực sự dùng.
 */
public record ResultRow(
        Long id,
        String studentId,
        String studentName,
        GradingStatus status,
        Float score,
        String details,      // JSON gọn (soTestPass/tongSoTest) — bảng hiển thị tỉ lệ pass
        String errorLog,     // tóm tắt lỗi khi ERROR/MANUAL_REVIEW
        String diagnosticCode,
        String diagnosticOrigin,
        String diagnosticStage,
        boolean requiresManualReview
) {}
