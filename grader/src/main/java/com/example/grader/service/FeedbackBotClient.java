package com.example.grader.service;

import com.example.grader.dto.FeedbackRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Cổng gọi sang AI agent <b>prm393-feedback-bot</b> (FastAPI, mặc định http://localhost:8000)
 * để sinh LỜI NHẬN XÉT cho bài làm của SV từ {@code result_json} (đã có sẵn dạng student/exam/
 * test_cases/competency mà backend dựng trong {@code BatchGradingService#assembleResultJson}).
 *
 * <p>KHÔNG dùng SDK ngoài — chỉ {@code java.net.http} như các LLM client sẵn có.
 *
 * <p>Trước khi gửi, {@link #normalizeForBot} chuẩn hoá JSON cho khớp ràng buộc Pydantic của bot
 * (skill_code bắt buộc, status thuộc enum, total_score &gt; 0, đếm test nhất quán…) → tránh 422
 * khi dữ liệu chấm có chỗ thiếu/khác kiểu.
 */
@Slf4j
@Service
public class FeedbackBotClient {

    @Value("${feedback.api.base:http://localhost:8000}")
    private String baseUrl;

    @Value("${feedback.timeout-seconds:150}")
    private int timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    // Ép HTTP/1.1: bot (uvicorn/h11) chỉ nói HTTP/1.1. Mặc định HttpClient là HTTP/2 → với http://
    // (cleartext) nó thử nâng cấp h2c và LÀM RỖNG body POST → FastAPI báo 422 "body required".
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    public String baseUrl() { return trimBase(); }

    /** Thông tin bot (GET /) gồm provider/model — null nếu bot không chạy. */
    public JsonNode info() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimBase() + "/"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 == 2) return mapper.readTree(res.body());
        } catch (Exception ignored) {}
        return null;
    }

    /** Bot có đang chạy không. Dùng để FE cảnh báo trước khi chạy cả lớp. */
    public boolean isUp() { return info() != null; }

    /**
     * Gửi 1 bài (result_json) sang bot để sinh nhận xét.
     * Không bao giờ ném ra ngoài — lỗi được gói vào {@link FeedbackRow#error()} để bảng vẫn hiển thị.
     */
    public FeedbackRow generate(String studentId, String studentName, Float score, String resultJson) {
        try {
            JsonNode parsed = mapper.readTree(resultJson);
            ObjectNode body = normalizeForBot(parsed);

            HttpRequest req = HttpRequest.newBuilder(URI.create(trimBase() + "/feedback/generate"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2)
                return err(studentId, studentName, score,
                        "Bot trả HTTP " + res.statusCode() + ": " + excerpt(res.body()));

            JsonNode r = mapper.readTree(res.body());
            return new FeedbackRow(
                    studentId, studentName, score,
                    r.path("score_summary").asText(""),
                    r.path("feedback_text").asText(""),
                    r.path("teacher_review_required").asBoolean(false),
                    toStringList(r.path("review_reasons")),
                    toStringList(r.path("sources")),
                    null
            );
        } catch (java.net.http.HttpConnectTimeoutException | java.net.ConnectException ce) {
            return err(studentId, studentName, score,
                    "Không kết nối được feedback bot (" + trimBase() + "). Hãy chắc chắn bot đang chạy.");
        } catch (Exception e) {
            String m = e.getMessage() != null ? e.getMessage() : e.toString();
            return err(studentId, studentName, score, "Lỗi khi gọi bot: " + m);
        }
    }

    // ── Chuẩn hoá result_json cho khớp schema Pydantic của bot ──────────────
    private ObjectNode normalizeForBot(JsonNode src) {
        ObjectNode root = src.isObject() ? src.deepCopy() : mapper.createObjectNode();

        // exam.total_score phải > 0
        double totalScore = root.path("exam").path("total_score").asDouble(0);
        if (totalScore <= 0) totalScore = 10;
        if (root.path("exam").isObject()) ((ObjectNode) root.get("exam")).put("total_score", totalScore);

        // grading_result: score trong [0, total_score]; passed+failed <= total
        JsonNode grNode = root.path("grading_result");
        if (grNode.isObject()) {
            ObjectNode gr = (ObjectNode) grNode;
            double sc = gr.path("score").asDouble(0);
            sc = Math.max(0, Math.min(totalScore, sc));
            int passed = Math.max(0, gr.path("passed_tests").asInt(0));
            int failed = Math.max(0, gr.path("failed_tests").asInt(0));
            int total  = Math.max(0, gr.path("total_tests").asInt(0));
            if (passed + failed > total) total = passed + failed;
            gr.put("score", sc);
            gr.put("passed_tests", passed);
            gr.put("failed_tests", failed);
            gr.put("total_tests", total);

            // test_cases: bảo đảm các field BẮT BUỘC (test_id/name/status/skill_code) hợp lệ
            int tcCount = 0;
            JsonNode tcs = root.path("test_cases");
            if (tcs.isArray()) {
                for (JsonNode n : tcs) {
                    if (n.isObject()) { normalizeTestCase((ObjectNode) n); tcCount++; }
                }
            }
            // Bot yêu cầu total_tests == số test_cases khi KHÔNG partial → lệch thì đánh dấu partial.
            root.put("test_cases_are_partial", tcCount > 0 && total != tcCount);
        } else {
            // Không có grading_result → tạo tối thiểu để bot không lỗi.
            ObjectNode gr = root.putObject("grading_result");
            gr.put("score", 0);
            gr.put("passed_tests", 0);
            gr.put("failed_tests", 0);
            gr.put("total_tests", 0);
            root.put("test_cases_are_partial", true);
        }

        return root;
    }

    private void normalizeTestCase(ObjectNode tc) {
        // test_id (min_length 1)
        String testId = firstNonBlank(tc.path("test_id").asText(""),
                tc.path("testId").asText(""), tc.path("name").asText(""));
        if (testId.isBlank()) testId = "T";
        tc.put("test_id", testId);

        // name (min_length 1)
        String name = tc.path("name").asText("");
        if (name.isBlank()) name = testId;
        tc.put("name", name);

        // status thuộc enum passed|failed|skipped|error
        tc.put("status", normStatus(tc.path("status").asText("")));

        // skill_code BẮT BUỘC (min_length 1)
        String skillCode = firstNonBlank(tc.path("skill_code").asText(""),
                tc.path("skill").asText(""), tc.path("category").asText(""));
        if (skillCode.isBlank()) skillCode = "unknown_skill";
        tc.put("skill_code", skillCode);

        // weight/max_score: nếu có thì phải > 0 (gt=0) — không hợp lệ thì bỏ field
        dropIfNotPositive(tc, "weight");
        dropIfNotPositive(tc, "max_score");
        // score (ge=0): âm thì bỏ
        if (tc.has("score") && !tc.path("score").isNull() && tc.path("score").asDouble(-1) < 0)
            tc.remove("score");
    }

    private static String normStatus(String s) {
        String x = s == null ? "" : s.toLowerCase();
        if (x.contains("pass")) return "passed";
        if (x.contains("fail")) return "failed";
        if (x.contains("skip")) return "skipped";
        if (x.contains("error")) return "error";
        return "failed";   // mặc định an toàn nếu không nhận diện được
    }

    private static void dropIfNotPositive(ObjectNode tc, String field) {
        if (!tc.has(field) || tc.path(field).isNull()) return;
        if (tc.path(field).asDouble(0) <= 0) tc.remove(field);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private FeedbackRow err(String id, String name, Float score, String msg) {
        return new FeedbackRow(id, name, score, null, null, true, List.of(), List.of(), msg);
    }

    private List<String> toStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) for (JsonNode n : arr) out.add(n.asText());
        return out;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v.trim();
        return "";
    }

    private String trimBase() {
        String b = baseUrl == null ? "http://localhost:8000" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
