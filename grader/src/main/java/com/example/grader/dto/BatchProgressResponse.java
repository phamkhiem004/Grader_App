package com.example.grader.dto;

import com.example.grader.entity.ExamResult;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BatchProgressResponse {
    private String           batchId;
    private long             total;
    private long             done;
    private long             grading;
    private long             queued;
    private long             error;
    private List<ExamResult> results;
}
