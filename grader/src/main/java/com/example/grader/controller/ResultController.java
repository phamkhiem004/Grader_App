package com.example.grader.controller;

import com.example.grader.entity.ExamResult;
import com.example.grader.repository.ExamResultRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cung cấp JSON kết quả đầy đủ (student/exam/test_cases/analyze/teacher_note) cho AI đọc & nhận xét.
 */
@RestController
@RequestMapping("/api/results")
@CrossOrigin(origins = "*")
public class ResultController {

    @Autowired
    private ExamResultRepository resultRepo;
    @Autowired
    private com.example.grader.service.AuthService authService;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Tìm kiếm nhanh (thanh search header) theo mã SV / tên / mã đề — trả tối đa 8 kết quả. */
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("q") String q) {
        if (q == null || q.trim().length() < 1) return ResponseEntity.ok(List.of());
        List<ExamResult> rows = resultRepo.searchSubmissions(
                q.trim(), org.springframework.data.domain.PageRequest.of(0, 8));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExamResult r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("examId", r.getExamId());
            m.put("studentId", r.getStudentId());
            m.put("studentName", r.getStudentName());
            m.put("score", r.getScore());
            m.put("status", r.getStatus());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** Chi tiết 1 bài cho trang chấm tay: result_json (test_cases) + điểm tự động + điểm tay đã lưu. */
    @GetMapping("/{examId}/{studentId}/detail")
    public ResponseEntity<?> getDetail(@PathVariable String examId, @PathVariable String studentId) {
        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit").orElse(null);
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy bài nộp"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("studentId", r.getStudentId());
        m.put("studentName", r.getStudentName());
        m.put("examId", r.getExamId());
        m.put("status", r.getStatus());
        m.put("autoScore", r.getScore());
        m.put("resultJson", r.getResultJson());   // chuỗi JSON đầy đủ (có test_cases)
        m.put("details", r.getDetails());
        m.put("errorLog", r.getErrorLog());
        m.put("manualScore", r.getManualScore());
        m.put("manualJson", r.getManualJson());
        m.put("manualBy", r.getManualBy());
        m.put("manualAt", r.getManualAt());
        m.put("batchId", r.getBatchId());
        return ResponseEntity.ok(m);
    }

    /** Lưu điểm chấm TAY theo tiêu chí. Body: { score, criteria:[...], note, gradedBy }. */
    @PostMapping("/{examId}/{studentId}/manual")
    public ResponseEntity<?> saveManual(@RequestHeader(value = "Authorization", required = false) String authz,
                                        @PathVariable String examId, @PathVariable String studentId,
                                        @RequestBody Map<String, Object> body) {
        // GV chấm tay phải đăng nhập; tên người chấm LẤY TỪ TOKEN (không tin "gradedBy" trong body
        // → tránh mạo danh GV khác khi sửa điểm).
        String token = (authz != null && authz.startsWith("Bearer ")) ? authz.substring(7).trim() : null;
        String gradedBy;
        try { gradedBy = authService.me(token).email(); }
        catch (Exception e) { return ResponseEntity.status(401).body(Map.of("error", "Cần đăng nhập để lưu điểm chấm tay.")); }

        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit").orElse(null);
        if (r == null) return ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy bài nộp"));
        try {
            Object score = body.get("score");
            if (score != null) r.setManualScore(Float.parseFloat(score.toString()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("criteria", body.get("criteria"));
            payload.put("note", body.get("note"));
            r.setManualJson(mapper.writeValueAsString(payload));
            r.setManualBy(gradedBy);                 // từ token, KHÔNG từ body
            r.setManualAt(java.time.Instant.now());
            resultRepo.save(r);
            return ResponseEntity.ok(Map.of("ok", true, "manualScore",
                    r.getManualScore() == null ? 0f : r.getManualScore()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Lịch sử chấm theo ĐỀ: danh sách bài đã chấm (gọn nhẹ, KHÔNG kèm result_json lớn). */
    @GetMapping("/exam/{examId}")
    public ResponseEntity<?> getExamHistory(@PathVariable String examId) {
        List<ExamResult> rows = resultRepo.findByExamIdAndModeOrderByUpdatedAtDesc(examId, "submit");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExamResult r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("studentId", r.getStudentId());
            m.put("studentName", r.getStudentName());
            m.put("score", r.getScore());
            m.put("manualScore", r.getManualScore());
            m.put("status", r.getStatus());
            m.put("batchId", r.getBatchId());
            m.put("updatedAt", r.getUpdatedAt());
            m.put("details", r.getDetails());     // JSON gọn của grader: soTestPass / tongSoTest
            m.put("errorLog", r.getErrorLog());
            m.put("hasJson", r.getResultJson() != null && !r.getResultJson().isBlank());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** JSON GỘP toàn bộ bài đã chấm xong của 1 ĐỀ: { examId, count, results:[...] } — in đẹp. */
    @GetMapping("/exam/{examId}/full")
    public ResponseEntity<String> getExamResultsFull(@PathVariable String examId) {
        List<ExamResult> rows = resultRepo.findByExamIdAndModeOrderByUpdatedAtDesc(examId, "submit");

        ArrayNode results = mapper.createArrayNode();
        for (ExamResult r : rows) {
            String rj = r.getResultJson();
            if (rj == null || rj.isBlank()) continue;   // bài chưa chấm xong/ lỗi → bỏ qua
            try {
                results.add(mapper.readTree(rj));
            } catch (Exception ignored) { /* JSON hỏng → bỏ qua phần tử này */ }
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("examId", examId);
        root.put("count", results.size());
        root.set("results", results);

        String out;
        try {
            out = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            out = "{\"examId\":\"" + examId + "\",\"count\":0,\"results\":[]}";
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out);
    }

    /** JSON đầy đủ của 1 bài (1 SV + 1 đề) — in đẹp. */
    @GetMapping("/{examId}/{studentId}")
    public ResponseEntity<String> getResult(@PathVariable String examId,
                                            @PathVariable String studentId) {
        return resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit")
                .map(ExamResult::getResultJson)
                .filter(json -> json != null && !json.isBlank())
                .map(json -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(pretty(json)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Kết quả CẢ batch: { batchId, count, results: [ ... ] } — in đẹp, gồm TẤT CẢ bài. */
    @GetMapping("/batch/{batchId}")
    public ResponseEntity<String> getBatchResults(@PathVariable String batchId) {
        List<ExamResult> rows = resultRepo.findByBatchIdOrderByStudentId(batchId);

        ArrayNode results = mapper.createArrayNode();
        for (ExamResult r : rows) {
            String rj = r.getResultJson();
            if (rj == null || rj.isBlank()) continue;   // bài chưa chấm xong/ lỗi → bỏ qua
            try {
                results.add(mapper.readTree(rj));
            } catch (Exception ignored) { /* JSON hỏng → bỏ qua phần tử này */ }
        }

        ObjectNode root = mapper.createObjectNode();
        root.put("batchId", batchId);
        root.put("count", results.size());
        root.set("results", results);

        String out;
        try {
            out = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            out = "{\"batchId\":\"" + batchId + "\",\"count\":0,\"results\":[]}";
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out);
    }

    private String pretty(String json) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(mapper.readTree(json));
        } catch (Exception e) {
            return json;   // không parse được thì trả nguyên văn
        }
    }
}
