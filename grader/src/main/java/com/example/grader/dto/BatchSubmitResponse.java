package com.example.grader.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class BatchSubmitResponse {
    private String       batchId;
    private int          totalQueued;
    private List<String> parseErrors;
}
