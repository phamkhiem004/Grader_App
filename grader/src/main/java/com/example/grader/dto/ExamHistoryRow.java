package com.example.grader.dto;

import com.example.grader.entity.GradingOutcome;
import com.example.grader.entity.GradingStatus;

import java.time.Instant;

public record ExamHistoryRow(
        Long id,
        String studentId,
        String studentName,
        Float score,
        Float manualScore,
        GradingStatus status,
        String batchId,
        Instant submittedAt,
        Instant updatedAt,
        String details,
        String errorLog,
        String diagnosticCode,
        String diagnosticOrigin,
        String diagnosticStage,
        boolean requiresManualReview,
        Boolean hasJson,
        /** Máy bắt đầu chấm bài này lúc nào; null = chưa tới lượt. */
        Instant gradingStartedAt,
        /** Máy chấm xong lúc nào; null = chưa xong hoặc bị dừng giữa chừng. */
        Instant gradingFinishedAt,
        /** Kết luận cho người chấm — xem {@link GradingOutcome}. Suy từ status, không lưu DB. */
        GradingOutcome outcome
) {
    /** Ctor cho JPQL (18 cột); {@code outcome} là dẫn xuất — xem {@link ResultRow}. */
    public ExamHistoryRow(Long id, String studentId, String studentName, Float score, Float manualScore,
                          GradingStatus status, String batchId, Instant submittedAt, Instant updatedAt,
                          String details, String errorLog, String diagnosticCode, String diagnosticOrigin,
                          String diagnosticStage, boolean requiresManualReview, Boolean hasJson,
                          Instant gradingStartedAt, Instant gradingFinishedAt) {
        this(id, studentId, studentName, score, manualScore, status, batchId, submittedAt, updatedAt,
                details, errorLog, diagnosticCode, diagnosticOrigin, diagnosticStage,
                requiresManualReview, hasJson, gradingStartedAt, gradingFinishedAt,
                GradingOutcome.of(status));
    }
}
