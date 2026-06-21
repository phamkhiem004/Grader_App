package com.example.grader.controller;

import com.example.grader.service.ai.AiExamGenService;
import com.example.grader.service.ai.GenRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * API cho TRANG TẠO ĐỀ BẰNG AI (wizard).
 *  - GET  /status           : provider/model/đã cắm key chưa (cho UI biết bật/tắt).
 *  - POST /generate         : tạo job sinh đề (chạy nền) → trả jobId.
 *  - GET  /job/{jobId}       : poll tiến trình vòng compile-fix + artifacts khi xong.
 *  - POST /save             : lưu artifacts đã duyệt thành đề thi.
 *
 * Thao tác GHI (generate/save) cần token (AuthFilter chặn); GET (status/job) để mở cho UI poll.
 */
@RestController
@RequestMapping("/api/ai-generator")
@CrossOrigin(origins = "*")
public class AiGeneratorController {

    @Autowired
    private AiExamGenService aiService;

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(aiService.status());
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody GenRequest req) {
        try {
            String jobId = aiService.startGenerate(req);
            return ResponseEntity.ok(Map.of("jobId", jobId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/job/{jobId}")
    public ResponseEntity<?> job(@PathVariable String jobId) {
        Map<String, Object> snap = aiService.jobSnapshot(jobId);
        if (snap == null) return ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy job: " + jobId));
        return ResponseEntity.ok(snap);
    }

    /** Dừng 1 job đang chạy (huỷ hợp tác — sẽ thoát ở checkpoint gần nhất). */
    @PostMapping("/job/{jobId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String jobId) {
        boolean ok = aiService.cancel(jobId);
        return ok ? ResponseEntity.ok(Map.of("cancelled", true))
                  : ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy job: " + jobId));
    }

    /** Chỉnh sửa đề đã sinh theo prompt tự do của GV (chạy nền, cập nhật cùng job — poll lại như cũ). */
    @PostMapping("/job/{jobId}/refine")
    public ResponseEntity<?> refine(@PathVariable String jobId, @RequestBody RefineRequest req) {
        if (req == null || req.instruction() == null || req.instruction().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Thiếu nội dung yêu cầu chỉnh sửa."));
        boolean ok = aiService.refine(jobId, req.instruction());
        return ok ? ResponseEntity.ok(Map.of("refining", true))
                  : ResponseEntity.status(409).body(Map.of("error",
                        "Không chỉnh được: job không tồn tại, đang chạy, hoặc chưa có kết quả để chỉnh."));
    }

    /** Body của /refine: yêu cầu chỉnh sửa bằng ngôn ngữ tự nhiên. */
    public record RefineRequest(String instruction) {}

    /** Tải sản phẩm dạng ZIP. kind = testcase | handout (đề + khung phát SV) | all. */
    @GetMapping("/job/{jobId}/zip")
    public ResponseEntity<?> zip(@PathVariable String jobId,
                                 @RequestParam(defaultValue = "testcase") String kind) {
        byte[] data = aiService.buildZip(jobId, kind);
        if (data == null)
            return ResponseEntity.status(404).body(Map.of("error", "Không có dữ liệu để tải (job chưa xong hoặc kind sai)."));
        String fname = "ai_" + kind.toLowerCase() + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(data);
    }

    @PostMapping("/save")
    public ResponseEntity<?> save(@RequestBody SaveRequest req) {
        try {
            if (req == null || req.examId() == null || req.examId().isBlank())
                return ResponseEntity.badRequest().body(Map.of("error", "Thiếu mã đề (examId)."));
            if (req.examTest() == null || req.skillsMatrix() == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Thiếu exam_test.dart hoặc skills_matrix.json."));
            return ResponseEntity.ok(aiService.saveAsExam(
                    req.examId(), req.examName(), req.teacherNote(),
                    req.examTest(), req.graderDart(), req.skillsMatrix(),
                    req.deBai(), req.starter(), req.solution()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Body của /save: artifacts đã duyệt + thông tin đề. grader.dart có thể bỏ trống (backend dùng bản chuẩn).
     * deBai (đề bài) + starter (khung lib/) + solution (lời giải mẫu lib/, mỗi phần tử {name, content})
     * được lưu kèm vào handout/ để Kho đề tải về.
     */
    public record SaveRequest(
            String examId, String examName, String teacherNote,
            String examTest, String graderDart, String skillsMatrix,
            String deBai, java.util.List<Map<String, String>> starter,
            java.util.List<Map<String, String>> solution) {}
}
