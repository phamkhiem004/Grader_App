package com.example.grader.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
public class BatchProgressResponse {
    private String          batchId;
    private long            total;
    private long            done;
    private long            grading;
    private long            queued;
    private long            error;
    private long            manualReview;
    private long            cancelled;
    private String          status;
    private List<ResultRow> results;   // NHẸ: không kèm cột LONGTEXT (xem ResultRow)

    /** Số bài máy chấm KHÔNG cho ra điểm (= error + manualReview, kể cả dữ liệu cũ). */
    private long            blocked;

    /**
     * Sự cố hệ thống đã GOM THEO NGUYÊN NHÂN, không phải theo bài.
     *
     * <p>Lỗi hạ tầng gần như luôn trúng cả loạt (Docker chết → 20 bài cùng lỗi), nên người chấm
     * cần đọc "Docker chết — 20 bài" chứ không phải cuộn 20 dòng giống nhau. Mỗi phần tử:
     * {@code {code, origin, originLabel, label, message, count, studentIds}}.
     */
    private List<Map<String, Object>> incidents;

    /** Đề của phiên chấm — để màn hình chấm lại đúng nhóm bài hỏng mà không phải đoán từ state. */
    private String            examId;
}
