package com.example.grader.controller;

import com.example.grader.repository.ExamRepository;
import com.example.grader.service.ExamService;
import com.example.grader.service.SyllabusService;
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
    @Autowired
    private ExamRepository examRepo;
    @Autowired
    private SyllabusService syllabusService;

    @PostMapping("/upload-testcase")
    public ResponseEntity<?> uploadTestcase(
            @RequestParam("examId")   String examId,
            @RequestParam(value = "examName",    required = false) String examName,
            @RequestParam(value = "teacherNote", required = false) String teacherNote,
            @RequestParam("testcase") MultipartFile zip) {
        try {
            return ResponseEntity.ok(examService.setupExam(examId, examName, teacherNote, zip));

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

    /** Danh sách đề đã cấu hình — cho bộ chọn đánh giá độ phủ theo syllabus. */
    @GetMapping("/list")
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(examService.listExams());
    }

    /**
     * ĐÁNH GIÁ ĐỘ PHỦ của đề theo SYLLABUS hiện tại (resolve trực tiếp → sửa syllabus là
     * phản chiếu ngay). Trả: testcase ↔ kiến thức/độ khó, độ phủ theo category & độ khó,
     * skill chưa phủ (gaps), issues.
     */
    @GetMapping("/coverage/{examId}")
    public ResponseEntity<?> coverage(@PathVariable String examId) {
        try {
            String matrix = examService.readSkillsMatrixJson(examId);
            return ResponseEntity.ok(syllabusService.evaluateCoverage(matrix));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Rubric (danh sách tiêu chí) của đề — cho trang chấm tay. */
    @GetMapping("/criteria/{examId}")
    public ResponseEntity<?> getCriteria(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.getCriteria(examId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Đọc các file testcase của 1 đề (exam_test.dart, skills_matrix.json, grader.dart) — cho trang Kho đề. */
    @GetMapping("/{examId}/testcase")
    public ResponseEntity<?> testcaseFiles(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.readExamTestcaseFiles(examId));
        } catch (IllegalArgumentException e) {     // mã đề không hợp lệ (allowlist)
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ"));
        }
    }

    /** Xóa 1 đề không dùng nữa: gỡ ảnh Docker + bản ghi DB (giải phóng dung lượng). */
    @DeleteMapping("/{examId}")
    public ResponseEntity<?> deleteExam(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(examService.deleteExam(examId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
