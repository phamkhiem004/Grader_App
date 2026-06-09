package com.example.grader.controller;

import com.example.grader.service.BatchGradingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/batch")
@CrossOrigin(origins = "*")
public class BatchController {

    @Autowired
    private BatchGradingService batchService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "examId",     defaultValue = "FLUTTER_PE_01") String examId,
            @RequestParam(value = "createdBy",  defaultValue = "admin") String createdBy) {

        if (files == null || files.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Không có file"));

        try {
            return ResponseEntity.ok(batchService.enqueueBatch(files, examId, createdBy));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/progress/{batchId}")
    public ResponseEntity<?> getProgress(@PathVariable String batchId) {
        try {
            return ResponseEntity.ok(batchService.getBatchProgress(batchId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Thông báo: các phiên chấm gần đây (cho icon chuông ở header). */
    @GetMapping("/recent")
    public ResponseEntity<?> recent(@RequestParam(value = "createdBy", required = false) String createdBy) {
        try {
            return ResponseEntity.ok(batchService.recentBatches(createdBy));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Đọc mã nguồn bài nộp của 1 SV (cho trang chấm tay xem "bài làm"). */
    @GetMapping("/submission/{examId}/{studentId}/files")
    public ResponseEntity<?> submissionFiles(@PathVariable String examId, @PathVariable String studentId) {
        try {
            return ResponseEntity.ok(batchService.readSubmissionFiles(examId, studentId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Đọc testcase đã dùng để chấm 1 SV (đối chiếu khi bài bị 0/0): exam_test.dart, skills_matrix.json, grader.dart. */
    @GetMapping("/testcase/{examId}/{studentId}/files")
    public ResponseEntity<?> testcaseFiles(@PathVariable String examId, @PathVariable String studentId) {
        try {
            return ResponseEntity.ok(batchService.readTestcaseFiles(examId, studentId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Chấm lại 1 bài từ zip đã lưu (không cần upload lại). Trả batchId mới để theo dõi tiến độ. */
    @PostMapping("/regrade/{examId}/{studentId}")
    public ResponseEntity<?> regrade(@PathVariable String examId, @PathVariable String studentId) {
        try {
            String batchId = batchService.regradeStudent(examId, studentId);
            return ResponseEntity.ok(Map.of("batchId", batchId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Chấm lại NHIỀU bài cùng lúc (gộp 1 batch). Body: { studentIds:[...] }. */
    @SuppressWarnings("unchecked")
    @PostMapping("/regrade-batch/{examId}")
    public ResponseEntity<?> regradeBatch(@PathVariable String examId, @RequestBody Map<String, Object> body) {
        try {
            Object ids = body.get("studentIds");
            List<String> studentIds = (ids instanceof List)
                    ? ((List<Object>) ids).stream().map(String::valueOf).toList() : List.of();
            return ResponseEntity.ok(batchService.regradeStudents(examId, studentIds));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
