package com.example.grader.dto;

import java.util.List;

/** Số liệu tổng hợp cho trang Thống kê — tính từ bảng exam_results. */
public record StatisticsResponse(
        String examId,             // đề đang xem ("ALL" = tất cả)
        long totalStudents,        // SỐ THÍ SINH thật (distinct student_id)
        long totalSubmissions,     // tổng số lượt chấm (số dòng)
        long graded,               // đã chấm xong (DONE)
        long errors,               // lỗi (ERROR)
        long pending,              // còn chờ/đang chấm (QUEUED + GRADING)
        long passCount,            // số bài đạt (score >= ngưỡng)
        long failCount,            // số bài trượt
        double passRate,           // % đạt trên số bài đã chấm
        double avgScore,           // điểm trung bình (trên bài đã chấm)
        double progressPct,        // % tiến độ chấm
        int passThreshold,         // ngưỡng đạt (hệ 10)
        List<Bucket> scoreDistribution,  // phổ điểm 5 khoảng
        List<TrendPoint> trend           // tiến độ 7 ngày gần nhất
) {
    /** Một cột phổ điểm, ví dụ {range:"4-6", count:12}. */
    public record Bucket(String range, long count) {}

    /** Một điểm trên biểu đồ tiến độ theo ngày. */
    public record TrendPoint(String date, long graded, long errors) {}
}
