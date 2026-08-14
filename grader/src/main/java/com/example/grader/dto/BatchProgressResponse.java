package com.example.grader.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

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
    private long            cancelled;     // bài bị bỏ khi người dùng dừng/hủy phiên chấm
    private String          batchStatus;   // BatchStatus của phiên (FE biết phiên đã bị dừng)
    private List<ResultRow> results;   // NHẸ: không kèm cột LONGTEXT (xem ResultRow)
}
