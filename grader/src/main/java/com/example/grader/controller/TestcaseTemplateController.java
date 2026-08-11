package com.example.grader.controller;

import com.example.grader.config.AppActor;
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
            @RequestParam(value = "layer", required = false) String layer,
            @RequestParam(value = "includeHidden", required = false, defaultValue = "false") boolean includeHidden) {
        return ResponseEntity.ok(templateService.listTemplates(category, skillCode, layer, includeHidden));
    }

    /** Danh mục runner + mô tả tham số; frontend dùng để dựng form thêm/sửa testcase. */
    @GetMapping("/runners")
    public ResponseEntity<?> runners() {
        return ResponseEntity.ok(templateService.runnerCatalog());
    }

    /** Danh mục cách dò + bộ semantic key gợi ý cho Khu vực 0 (hợp đồng bài làm). */
    @GetMapping("/contract-catalog")
    public ResponseEntity<?> contractCatalog() {
        return ResponseEntity.ok(templateService.contractCatalog());
    }

    /** Xem trước yêu cầu dán vào đề và đoạn code phát cho sinh viên. */
    @PostMapping("/contract/preview")
    public ResponseEntity<?> contractPreview(@RequestBody Map<String, Object> body) {
        return handle(() -> templateService.contractPreview(
                body == null ? null : body.getOrDefault("contract", body)));
    }

    @GetMapping("/{templateId}")
    public ResponseEntity<?> detail(@PathVariable String templateId) {
        try {
            return ResponseEntity.ok(templateService.getTemplate(templateId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        return handle(() -> templateService.createTemplate(body, AppActor.DEFAULT));
    }

    @PutMapping("/{templateId}")
    public ResponseEntity<?> update(@PathVariable String templateId,
                                    @RequestBody Map<String, Object> body) {
        return handle(() -> templateService.updateTemplate(templateId, body, AppActor.DEFAULT));
    }

    /** Ẩn khỏi thư viện (không xóa cứng) để đề đã lưu vẫn resolve được template_id. */
    @DeleteMapping("/{templateId}")
    public ResponseEntity<?> hide(@PathVariable String templateId) {
        return handle(() -> templateService.hideTemplate(templateId, AppActor.DEFAULT));
    }

    @PostMapping("/{templateId}/restore")
    public ResponseEntity<?> restore(@PathVariable String templateId) {
        return handle(() -> templateService.restoreTemplate(templateId, AppActor.DEFAULT));
    }

    private ResponseEntity<?> handle(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Kiểm tra cú pháp đoạn code tay trước khi giáo viên thêm vào đề. Trả 200 kèm
     * {ok,message} kể cả khi code sai — đây là kết quả kiểm tra, không phải lỗi request.
     */
    @PostMapping("/custom-code/validate")
    public ResponseEntity<?> validateCustomCode(@RequestBody Map<String, Object> body) {
        Object code = body == null ? null : body.get("custom_code");
        return ResponseEntity.ok(templateService.checkCustomCode(code == null ? "" : String.valueOf(code)));
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
