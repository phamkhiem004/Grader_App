package com.example.grader.controller;

import com.example.grader.repository.ExamRepository;
import com.example.grader.service.ExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/exam-setup")
@CrossOrigin(origins = "*")
public class ExamSetupController {

    @Autowired
    private ExamService examService;
    private ExamRepository examRepo;

    @PostMapping("/upload-testcase")
    public ResponseEntity<?> uploadTestcase(
            @RequestParam("examId")   String examId,
            @RequestParam("testcase") MultipartFile zip) {
        try {
            return ResponseEntity.ok(examService.setupExam(examId, zip));

        } catch (IllegalArgumentException e) {
            // Thiếu file bắt buộc, sai format...
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            // docker build thất bại...
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Build image thất bại: " + e.getMessage()));
        }
    }

    @GetMapping("/status/{examId}")
    public ResponseEntity<?> getStatus(@PathVariable String examId) {
        return examRepo.findByExamId(examId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
