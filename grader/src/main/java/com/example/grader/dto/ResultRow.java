package com.example.grader.dto;

import com.example.grader.entity.GradingOutcome;
import com.example.grader.entity.GradingStatus;

import java.time.Instant;

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
        String errorLog,     // tóm tắt lỗi khi máy chấm không cho ra điểm
        String diagnosticCode,
        String diagnosticOrigin,
        String diagnosticStage,
        boolean requiresManualReview,
        /** Máy bắt đầu chấm bài này lúc nào; null = chưa tới lượt. */
        Instant gradingStartedAt,
        /** Máy chấm xong lúc nào; null = chưa xong hoặc bị dừng giữa chừng. */
        Instant gradingFinishedAt,
        /** Kết luận cho người chấm — xem {@link GradingOutcome}. Suy từ status, không lưu DB. */
        GradingOutcome outcome
) {
    /**
     * Ctor cho JPQL {@code select new ...ResultRow(13 cột)} — {@code outcome} là dẫn xuất nên
     * KHÔNG đọc từ DB. Tính ở đây để mọi nơi phát hành cùng một kết luận, thay vì để màn hình
     * tự suy lại từ status/origin/code như trước.
     */
    public ResultRow(Long id, String studentId, String studentName, GradingStatus status, Float score,
                     String details, String errorLog, String diagnosticCode, String diagnosticOrigin,
                     String diagnosticStage, boolean requiresManualReview,
                     Instant gradingStartedAt, Instant gradingFinishedAt) {
        this(id, studentId, studentName, status, score, details, errorLog, diagnosticCode,
                diagnosticOrigin, diagnosticStage, requiresManualReview,
                gradingStartedAt, gradingFinishedAt, GradingOutcome.of(status));
    }
}
