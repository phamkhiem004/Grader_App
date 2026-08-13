package com.example.grader.dto;

import java.util.List;

/** Số liệu tổng hợp cho trang Thống kê, tính từ bảng exam_results. */
public record StatisticsResponse(
        String examId,
        long totalStudents,
        long totalSubmissions,
        long graded,
        long errors,
        long pending,
        long manualReview,
        long passCount,
        long failCount,
        double passRate,
        double avgScore,
        double progressPct,
        int passThreshold,
        List<Bucket> scoreDistribution,
        List<TrendPoint> trend
) {
    public record Bucket(String range, long count) {}

    public record TrendPoint(String date, long graded, long errors) {}
}
