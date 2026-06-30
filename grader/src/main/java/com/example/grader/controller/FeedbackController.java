package com.example.grader.controller;

import com.example.grader.dto.FeedbackRow;
import com.example.grader.entity.ExamResult;
import com.example.grader.repository.ExamResultRepository;
import com.example.grader.service.FeedbackBotClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Đọc & nhận xét bài làm bằng AI": cầu nối giữa kết quả chấm (result_json) và AI agent
 * prm393-feedback-bot. FE nhập MÃ ĐỀ → gọi lần lượt từng SV để dựng bảng nhận xét + xuất Excel.
 */
@RestController
@RequestMapping("/api/feedback")
@CrossOrigin(origins = "*")
public class FeedbackController {

    private static final String FEEDBACK_CACHE_VERSION = "feedback-v3";

    @Autowired private ExamResultRepository resultRepo;
    @Autowired private FeedbackBotClient bot;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Bot có đang chạy không + URL bot (để FE cảnh báo trước khi chạy). GET → không cần token. */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        var info = bot.info();
        m.put("up", info != null);
        m.put("base", bot.baseUrl());
        if (info != null) {
            m.put("provider", info.path("provider").asText("ollama"));   // ollama | openai
            m.put("model", info.path("model").asText(""));
        }
        return ResponseEntity.ok(m);
    }

    /**
     * Sinh nhận xét cho 1 SV của 1 đề. FE gọi tuần tự/song song nhẹ từng SV để hiển thị tiến độ
     * (model local có thể chậm vài chục giây/bài). POST → AuthFilter yêu cầu token GV.
     */
    @PostMapping("/exam/{examId}/{studentId}")
    public ResponseEntity<?> one(@PathVariable String examId, @PathVariable String studentId,
                                 @RequestParam(value = "force", defaultValue = "false") boolean force) {
        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit").orElse(null);
        if (r == null)
            return ResponseEntity.status(404).body(Map.of("error", "Không tìm thấy bài nộp của " + studentId));

        String rj = r.getResultJson();
        if (rj == null || rj.isBlank()) {
            // Bài lỗi / chưa chấm xong → không có JSON để AI đọc. Trả 1 dòng có error để bảng vẫn hiện.
            return ResponseEntity.ok(new FeedbackRow(
                    studentId, r.getStudentName(), r.getScore(),
                    null, null, true,
                    List.of("Bài chưa chấm xong hoặc bị lỗi nên không có JSON kết quả."),
                    List.of(),
                    "Chưa có result_json (bài lỗi/chưa chấm xong) — không thể nhận xét."));
        }

        // CACHE: nếu đã sinh nhận xét cho ĐÚNG result_json này (hash khớp) thì trả lại ngay,
        // không gọi lại model → chấm hàng loạt / mở lại trang không phải sinh lại. force=true để bắt sinh lại.
        String hash = sha256(FEEDBACK_CACHE_VERSION + ":" + rj);
        if (!force && r.getFeedbackJson() != null && hash.equals(r.getFeedbackSrcHash())) {
            try {
                FeedbackRow cached = mapper.readValue(r.getFeedbackJson(), FeedbackRow.class);
                return ResponseEntity.ok(cached);
            } catch (Exception ignored) { /* cache hỏng → sinh lại */ }
        }

        FeedbackRow row = bot.generate(studentId, r.getStudentName(), r.getScore(), rj);

        // Chỉ cache khi sinh THÀNH CÔNG (error == null) → lần lỗi (bot tắt...) không bị lưu lại.
        if (row != null && row.error() == null) {
            try {
                r.setFeedbackJson(mapper.writeValueAsString(row));
                r.setFeedbackSrcHash(hash);
                resultRepo.save(r);
            } catch (Exception ignored) { /* không cache được cũng không sao */ }
        }
        return ResponseEntity.ok(row);
    }

    private String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Không tính được SHA-256 cho result_json", e);
        }
    }
}
