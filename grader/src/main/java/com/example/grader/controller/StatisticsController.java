package com.example.grader.controller;

import com.example.grader.service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/statistics")
@CrossOrigin(origins = "*")
public class StatisticsController {

    @Autowired
    private StatisticsService statisticsService;

    /** Danh sách đề thi cho dropdown lọc. */
    @GetMapping("/exams")
    public ResponseEntity<?> exams() {
        return ResponseEntity.ok(statisticsService.getExamOptions());
    }

    /** Số liệu tổng hợp. examId rỗng hoặc "ALL" = toàn bộ đề. */
    @GetMapping
    public ResponseEntity<?> stats(@RequestParam(value = "examId", required = false) String examId) {
        try {
            return ResponseEntity.ok(statisticsService.getStatistics(examId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
