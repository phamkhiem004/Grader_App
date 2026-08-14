package com.example.grader.entity;



public enum GradingStatus {
    // CANCELLED = người dùng bấm Dừng/Hủy phiên chấm. KHÔNG phải ERROR: bài chưa được chấm
    // nên không có bằng chứng nào để quy lỗi cho sinh viên.
    QUEUED, GRADING, DONE, ERROR, MANUAL_REVIEW, CANCELLED
}
