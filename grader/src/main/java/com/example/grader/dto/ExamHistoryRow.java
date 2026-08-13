package com.example.grader.dto;

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
        Boolean hasJson
) {}
