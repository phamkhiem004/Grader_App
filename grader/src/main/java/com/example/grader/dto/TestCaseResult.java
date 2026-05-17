package com.example.grader.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class TestCaseResult {
    private String name;
    private String status;   // PASS | FAILED
    private String message;
}
