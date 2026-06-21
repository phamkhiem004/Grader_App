package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cổng gọi OpenAI / GPT (Chat Completions API). KHÔNG dùng SDK ngoài — chỉ {@code java.net.http}.
 *
 * <p>CẮM KEY: điền {@code grader.ai.openai.api-key} (lấy ở https://platform.openai.com/api-keys).
 * Cũng dùng được cho mọi endpoint TƯƠNG THÍCH OpenAI (đổi {@code grader.ai.openai.base-url}):
 * Azure OpenAI, OpenRouter, Groq, hoặc model open-source chạy local qua Ollama/vLLM.
 */
@Slf4j
@Component
public class OpenAiClient implements LlmClient {

    @Value("${grader.ai.openai.api-key:}")
    private String apiKey;

    @Value("${grader.ai.openai.model:gpt-4o-mini}")
    private String model;

    @Value("${grader.ai.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${grader.ai.timeout-seconds:120}")
    private int timeoutSeconds;

    /** Với họ model REASONING (gpt-5*, o1/o3/o4*): mức suy luận. Bỏ trống = mặc định của API. */
    @Value("${grader.ai.openai.reasoning-effort:low}")
    private String reasoningEffort;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    @Override public String key()          { return "openai"; }
    @Override public String model()        { return model; }
    @Override public boolean isConfigured(){ return apiKey != null && !apiKey.isBlank(); }

    @Override
    public String chat(List<LlmMessage> messages) throws Exception {
        if (!isConfigured())
            throw new IllegalStateException("Chưa cấu hình grader.ai.openai.api-key");

        List<Map<String, Object>> msgs = new ArrayList<>();
        for (LlmMessage m : messages) msgs.add(Map.of("role", m.role(), "content", m.content()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", msgs);
        if (isReasoning(model)) {
            // Họ gpt-5 / o-series: KHÔNG nhận 'max_tokens'/'temperature' tùy biến.
            body.put("max_completion_tokens", 32768);   // gồm cả token suy luận → để rộng
            if (reasoningEffort != null && !reasoningEffort.isBlank())
                body.put("reasoning_effort", reasoningEffort.trim());   // minimal|low|medium|high
        } else {
            body.put("temperature", 0.35);
            body.put("max_tokens", 16384);                            // headroom cho bộ testcase lớn
        }
        body.put("response_format", Map.of("type", "json_object"));   // ép trả về 1 object JSON

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        // Gọi có RETRY: lỗi mạng tạm thời (connection reset/timeout) hoặc 429/5xx → thử lại tối đa 3 lần.
        // InterruptedException (do người dùng bấm Dừng) KHÔNG bắt → để lan ra ngoài cho nhánh huỷ.
        int maxTries = 3;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxTries; attempt++) {
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int sc = res.statusCode();
                if (sc / 100 == 2) {
                    JsonNode root = mapper.readTree(res.body());
                    JsonNode text = root.path("choices").path(0).path("message").path("content");
                    if (text.isMissingNode() || text.asText().isBlank())
                        throw new RuntimeException("OpenAI không trả nội dung: " + excerpt(res.body()));
                    return text.asText();
                }
                last = new RuntimeException("OpenAI HTTP " + sc + ": " + excerpt(res.body()));
                if (!(sc == 429 || sc / 100 == 5) || attempt == maxTries) throw last;   // lỗi không tạm thời → ném
            } catch (java.io.IOException ioe) {     // connection reset / read timeout → thử lại
                last = new RuntimeException("OpenAI lỗi mạng: " + ioe.getMessage());
                if (attempt == maxTries) throw last;
            }
            Thread.sleep(2000L * attempt);   // backoff 2s, 4s (ngắt khi huỷ → InterruptedException lan ra ngoài)
        }
        throw last != null ? last : new RuntimeException("OpenAI: lỗi không xác định");
    }

    /** Họ model "reasoning" (gpt-5*, o1/o3/o4*) cần tham số API khác (max_completion_tokens, không temperature). */
    private static boolean isReasoning(String m) {
        if (m == null) return false;
        String x = m.toLowerCase();
        return x.startsWith("gpt-5") || x.startsWith("o1") || x.startsWith("o3") || x.startsWith("o4") || x.startsWith("o5");
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
