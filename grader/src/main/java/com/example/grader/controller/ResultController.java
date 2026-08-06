package com.example.grader.controller;

import com.example.grader.dto.ExamHistoryRow;
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

/** Cung cấp JSON kết quả đầy đủ cho lịch sử, năng lực và xuất dữ liệu. */
@RestController
@RequestMapping("/api/results")
@CrossOrigin(origins = "*")
public class ResultController {

    /** JSON luôn khai báo UTF-8 để client không tự đoán theo mã ký tự của máy. */
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json; charset=UTF-8");

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
        m.put("resultJson", normalizeJsonString(r.getResultJson())); // chuỗi JSON đầy đủ (chỉ có expected)
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
        List<ExamHistoryRow> rows = resultRepo.findHistoryRowsByExamIdAndMode(examId, "submit");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExamHistoryRow r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("studentId", r.studentId());
            m.put("studentName", r.studentName());
            m.put("score", r.score());
            m.put("manualScore", r.manualScore());
            m.put("status", r.status());
            m.put("batchId", r.batchId());
            m.put("submittedAt", r.submittedAt());
            m.put("updatedAt", r.updatedAt());
            m.put("details", r.details());     // JSON gon cua grader: soTestPass / tongSoTest
            m.put("errorLog", r.errorLog());
            m.put("hasJson", Boolean.TRUE.equals(r.hasJson()));
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
                results.add(normalizeResultNode(mapper.readTree(rj)));
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
        return ResponseEntity.ok().contentType(JSON_UTF8).body(out);
    }

    /** JSON đầy đủ của 1 bài (1 SV + 1 đề) — in đẹp. */
    @GetMapping("/{examId}/{studentId}")
    public ResponseEntity<String> getResult(@PathVariable String examId,
                                            @PathVariable String studentId) {
        return resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit")
                .map(ExamResult::getResultJson)
                .filter(json -> json != null && !json.isBlank())
                .map(json -> ResponseEntity.ok()
                        .contentType(JSON_UTF8)
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
                results.add(normalizeResultNode(mapper.readTree(rj)));
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
        return ResponseEntity.ok().contentType(JSON_UTF8).body(out);
    }

    private String pretty(String json) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(normalizeResultNode(mapper.readTree(json)));
        } catch (Exception e) {
            return json;   // không parse được thì trả nguyên văn
        }
    }

    /** Giữ schema JSON ổn định cho cả kết quả cũ chưa có expected/actual. */
    private JsonNode normalizeResultNode(JsonNode root) {
        if (!(root instanceof ObjectNode object)) return root;
        JsonNode rawCases = object.get("test_cases");
        if (!(rawCases instanceof ArrayNode cases)) return root;

        for (JsonNode rawCase : cases) {
            if (!(rawCase instanceof ObjectNode tc)) continue;

            String expected = textOrBlank(tc.get("expected"));
            if (expected.isBlank()) expected = textOrBlank(tc.get("expect"));
            if (expected.isBlank()) expected = "PASS";
            tc.put("expected", expected);
            tc.remove("expect"); // dữ liệu cũ vẫn đọc được nhưng không phát hành alias này nữa

            String actual = normalizeActual(textOrBlank(tc.get("actual")));
            if (actual.isBlank()) {
                String status = textOrBlank(tc.get("status")).toLowerCase();
                tc.put("actual", status.contains("pass") ? "Đã đáp ứng yêu cầu" : "Không đáp ứng yêu cầu");
            } else {
                tc.put("actual", actual);
            }

            normalizeErrorFields(tc);
            addContractKeysWithoutGuessing(tc);
        }
        return root;
    }

    /**
     * Bổ sung khoá hợp đồng cho JSON ĐÃ LƯU — chỉ những khoá suy được mà KHÔNG phải đoán.
     *
     * <p>Cố ý KHÔNG bơm `executed` và `schema_version`: dữ liệu chấm trước P4 ghi mọi test chưa
     * chạy thành `failed`, nên gán `executed = true` cho chúng là nói sai. Sự VẮNG MẶT của hai
     * khoá đó chính là dấu hiệu "dữ liệu bản 1" mà bên đọc dựa vào — bơm vào là xoá mất dấu hiệu.
     */
    private void addContractKeysWithoutGuessing(ObjectNode tc) {
        // Hoãn tới P4b nên luôn null; đặt khoá để bên đọc không phải đoán schema.
        if (!tc.has("blocked_by")) tc.putNull("blocked_by");
        // Mã lỗi phẳng: chép từ error.code đang có, không sinh giá trị mới.
        if (!tc.has("error_code")) {
            JsonNode error = tc.get("error");
            String code = error instanceof ObjectNode obj ? textOrBlank(obj.get("code")) : "";
            if (code.isBlank()) tc.putNull("error_code");
            else tc.put("error_code", code);
        }
    }

    /**
     * Dữ liệu cũ từng ghi cả prefix "Ném lỗi:" và nhầm dòng "The test description was: <id>"
     * vào actual. Khi tải JSON, sửa tại lớp biên để các kết quả đã lưu vẫn có schema sạch.
     */
    private String normalizeActual(String value) {
        String actual = value == null ? "" : value.trim();
        if (actual.isBlank()) return "";
        if (actual.matches("(?is)^Ném lỗi:\\s*The test description was:?\\s*.*$")
                || actual.matches("(?is)^The test description was:?\\s*.*$")) {
            return "Không có giá trị actual — testcase dừng do exception";
        }
        if (actual.regionMatches(true, 0, "Ném lỗi:", 0, "Ném lỗi:".length())) {
            actual = actual.substring("Ném lỗi:".length()).trim();
        }
        if (actual.equalsIgnoreCase("Test failed. See exception logs above.")
                || actual.equalsIgnoreCase("Test failed. See exception logs above")) {
            return "Không có giá trị actual — testcase dừng do exception";
        }
        return actual;
    }

    /** Tách hướng dẫn SV khỏi error.message cho các JSON cũ bị trùng hai field. */
    private void normalizeErrorFields(ObjectNode tc) {
        JsonNode errorNode = tc.get("error");
        if (!(errorNode instanceof ObjectNode error)) return;

        String code = textOrBlank(error.get("code"));
        String message = textOrBlank(error.get("message"));
        String summary = textOrBlank(tc.get("student_safe_summary"));
        if (summary.isBlank() || summary.equals(message)) {
            tc.put("student_safe_summary", safeSummaryForCode(code));
        }
    }

    private String safeSummaryForCode(String code) {
        return switch (code == null ? "" : code) {
            case "VALUE_MISMATCH" -> "Đối chiếu actual với expected của testcase và sửa giá trị/logic tương ứng.";
            case "WIDGET_NOT_FOUND" -> "Kiểm tra widget, text, key hoặc semantics mà đề yêu cầu; bảo đảm widget được render trong viewport.";
            case "WIDGET_UNEXPECTED" -> "Loại bỏ widget/nhãn xuất hiện ngoài yêu cầu hoặc sửa điều kiện render.";
            case "WIDGET_COUNT" -> "Kiểm tra số lượng widget thực tế và dữ liệu đầu vào của danh sách.";
            case "LAYOUT_OVERFLOW" -> "Sửa bố cục bằng cách giới hạn kích thước hoặc cho phép cuộn ở viewport đang được kiểm tra.";
            case "TIMEOUT" -> "Kiểm tra loading/animation và các thao tác async để chúng luôn kết thúc.";
            case "NULL_ERROR" -> "Khởi tạo dữ liệu bắt buộc và xử lý null trước khi truy cập thuộc tính/field.";
            case "TYPE_ERROR" -> "Kiểm tra kiểu dữ liệu và các phép ép kiểu trong luồng testcase.";
            case "RANGE_ERROR" -> "Kiểm tra index và điều kiện danh sách rỗng trước khi truy cập phần tử.";
            case "NO_SUCH_METHOD" -> "Kiểm tra tên hàm/thuộc tính và bảo đảm API được định nghĩa đúng trong lib/.";
            case "FORMAT_ERROR" -> "Kiểm tra dữ liệu đầu vào trước khi parse hoặc chuyển đổi định dạng.";
            case "STATE_ERROR" -> "Kiểm tra luồng cập nhật state/list và tránh thay đổi dữ liệu khi đang duyệt.";
            case "BUILD_ERROR" -> "Kiểm tra tham số bắt buộc và lỗi trong build method của widget.";
            case "COMPILE_ERROR" -> "Kiểm tra import, tên package và dependency trước khi chạy testcase.";
            case "EXCEPTION_THROWN" -> "Testcase dừng do exception; kiểm tra log runtime và sửa lỗi trong luồng được yêu cầu.";
            default -> "Đối chiếu yêu cầu testcase với cách triển khai và sửa phần chưa đáp ứng.";
        };
    }

    private String normalizeJsonString(String json) {
        if (json == null || json.isBlank()) return json;
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(normalizeResultNode(mapper.readTree(json)));
        } catch (Exception e) {
            return json;
        }
    }

    private String textOrBlank(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }
}
