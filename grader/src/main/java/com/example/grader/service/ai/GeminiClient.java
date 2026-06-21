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
 * Cổng gọi Google Gemini (generativelanguage API). KHÔNG dùng SDK ngoài — chỉ {@code java.net.http}.
 *
 * <p>CẮM KEY: điền {@code grader.ai.gemini.api-key} trong application.properties (lấy ở
 * https://aistudio.google.com/app/apikey). Để trống → tính năng AI tắt với provider này.
 */
@Slf4j
@Component
public class GeminiClient implements LlmClient {

    @Value("${grader.ai.gemini.api-key:}")
    private String apiKey;

    @Value("${grader.ai.gemini.model:gemini-2.0-flash}")
    private String model;

    @Value("${grader.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String baseUrl;

    @Value("${grader.ai.timeout-seconds:120}")
    private int timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    @Override public String key()          { return "gemini"; }
    @Override public String model()        { return model; }
    @Override public boolean isConfigured(){ return apiKey != null && !apiKey.isBlank(); }

    @Override
    public String chat(List<LlmMessage> messages) throws Exception {
        if (!isConfigured())
            throw new IllegalStateException("Chưa cấu hình grader.ai.gemini.api-key");

        // Gemini tách system ra systemInstruction; hội thoại dùng role user/model.
        StringBuilder sys = new StringBuilder();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (LlmMessage m : messages) {
            if ("system".equals(m.role())) {
                if (sys.length() > 0) sys.append("\n\n");
                sys.append(m.content());
                continue;
            }
            String role = "assistant".equals(m.role()) ? "model" : "user";
            contents.add(Map.of("role", role, "parts", List.of(Map.of("text", m.content()))));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (sys.length() > 0)
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", sys.toString()))));
        body.put("contents", contents);
        body.put("generationConfig", Map.of(
                "temperature", 0.35,
                "maxOutputTokens", 8192,
                "responseMimeType", "application/json"));   // ép trả về 1 object JSON

        String url = baseUrl + "/models/" + model + ":generateContent?key=" + apiKey;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2)
            throw new RuntimeException("Gemini HTTP " + res.statusCode() + ": " + excerpt(res.body()));

        JsonNode root = mapper.readTree(res.body());
        JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        if (text.isMissingNode() || text.asText().isBlank()) {
            String fb = root.path("promptFeedback").toString();
            throw new RuntimeException("Gemini không trả nội dung (có thể bị chặn): " + excerpt(fb));
        }
        return text.asText();
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
