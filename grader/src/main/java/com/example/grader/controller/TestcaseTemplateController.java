package com.example.grader.controller;

import com.example.grader.service.TestcaseTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** API thư viện testcase chung và cấu hình instance cho từng đề. */
@RestController
@RequestMapping("/api/testcase-templates")
@CrossOrigin(origins = "*")
public class TestcaseTemplateController {

    @Autowired private TestcaseTemplateService templateService;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "skillCode", required = false) String skillCode,
            @RequestParam(value = "layer", required = false) String layer) {
        return ResponseEntity.ok(templateService.listTemplates(category, skillCode, layer));
    }

    @GetMapping("/packs")
    public ResponseEntity<?> packs() {
        return ResponseEntity.ok(templateService.listTemplatePacks());
    }

    /** Tạo template tái sử dụng mới từ runner đã có trong common engine. */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "teacherEmail", required = false) String teacherEmail) {
        try {
            return ResponseEntity.ok(templateService.createTemplate(body,
                    teacherEmail != null ? teacherEmail : "unknown"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không lưu được template testcase"));
        }
    }
    @GetMapping("/{templateId}")
    public ResponseEntity<?> detail(@PathVariable String templateId) {
        try {
            return ResponseEntity.ok(templateService.getTemplate(templateId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/exam/{examId}")
    public ResponseEntity<?> examConfig(@PathVariable String examId) {
        try {
            return ResponseEntity.ok(templateService.getExamConfig(examId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không đọc được cấu hình testcase"));
        }
    }
}
