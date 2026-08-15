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
        /** Kết luận cho người chấm — xem {@link GradingOutcome}. Suy từ status, không lưu DB. */
        GradingOutcome outcome
) {
    /** Ctor cho JPQL (16 cột); {@code outcome} là dẫn xuất — xem {@link ResultRow}. */
    public ExamHistoryRow(Long id, String studentId, String studentName, Float score, Float manualScore,
                          GradingStatus status, String batchId, Instant submittedAt, Instant updatedAt,
                          String details, String errorLog, String diagnosticCode, String diagnosticOrigin,
                          String diagnosticStage, boolean requiresManualReview, Boolean hasJson) {
        this(id, studentId, studentName, score, manualScore, status, batchId, submittedAt, updatedAt,
                details, errorLog, diagnosticCode, diagnosticOrigin, diagnosticStage,
                requiresManualReview, hasJson, GradingOutcome.of(status));
    }
}
