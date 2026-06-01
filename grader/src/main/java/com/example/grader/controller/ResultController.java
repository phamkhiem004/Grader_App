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

import java.util.List;

/**
 * Cung cấp JSON kết quả đầy đủ (student/exam/test_cases/analyze/teacher_note) cho AI đọc & nhận xét.
 */
@RestController
@RequestMapping("/api/results")
@CrossOrigin(origins = "*")
public class ResultController {

    @Autowired
    private ExamResultRepository resultRepo;

    private final ObjectMapper mapper = new ObjectMapper();

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
