package com.example.grader.dto;

import com.example.grader.entity.GradingStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

/**
 * Bản ghi NHẸ cho thống kê — KHÔNG kèm cột LONGTEXT (result_json/details/errorLog/manual_json) vì
 * trang Thống kê chỉ cần điểm/trạng thái/mốc thời gian. Tránh nạp cả bảng vào heap (nguy cơ OOM).
 * Dùng @Getter (getX) để StatisticsService giữ nguyên cách gọi r.getScore()/getStatus()...
 */
@Getter
@AllArgsConstructor
public class ResultStat {
    private String studentId;
    private String mode;
    private Float score;
    private GradingStatus status;
    private Instant submittedAt;
    private Instant updatedAt;
}
