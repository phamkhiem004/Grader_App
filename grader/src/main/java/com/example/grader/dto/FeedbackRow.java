package com.example.grader.dto;

import java.util.List;

/**
 * Một dòng kết quả "AI nhận xét bài làm" cho 1 sinh viên.
 * FE gọi lần lượt từng SV (để hiển thị tiến độ) rồi gộp lại thành bảng + xuất Excel.
 *
 * @param error null nếu sinh nhận xét thành công; có giá trị nếu gọi bot lỗi (bot tắt, JSON sai…).
 */
public record FeedbackRow(
        String studentId,
        String studentName,
        Float score,
        String scoreSummary,          // "7.5/10"
        String feedbackText,          // lời nhận xét tự nhiên do bot sinh
        boolean teacherReviewRequired,// bot khuyến nghị GV xem lại
        List<String> reviewReasons,   // lý do cần GV xem lại
        List<String> sources,         // nguồn RAG đã dùng
        String error                  // null = OK
) {}
