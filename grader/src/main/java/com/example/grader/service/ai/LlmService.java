package com.example.grader.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bộ định tuyến LLM: chọn provider theo {@code grader.ai.provider} ("gemini" | "openai") rồi
 * ủy thác cho {@link LlmClient} tương ứng. Tất cả client được Spring tiêm vào → thêm provider mới
 * = thêm 1 bean cài {@link LlmClient}, không phải sửa ở đây.
 */
@Slf4j
@Service
public class LlmService {

    @Value("${grader.ai.enabled:true}")
    private boolean enabled;

    @Value("${grader.ai.provider:gemini}")
    private String provider;

    private final Map<String, LlmClient> clients = new LinkedHashMap<>();

    public LlmService(List<LlmClient> beans) {
        for (LlmClient c : beans) clients.put(c.key(), c);
    }

    /** Client đang được chọn (theo provider). Ném nếu provider không hợp lệ. */
    public LlmClient active() {
        LlmClient c = clients.get(provider == null ? "" : provider.trim().toLowerCase());
        if (c == null)
            throw new IllegalStateException("grader.ai.provider không hợp lệ: '" + provider
                    + "'. Hợp lệ: " + clients.keySet());
        return c;
    }

    /** Bật/tắt toàn cục tính năng AI. */
    public boolean isEnabled() { return enabled; }

    /** Tính năng sẵn sàng = đã bật + provider hợp lệ + đã cắm API key. */
    public boolean isReady() {
        if (!enabled) return false;
        try { return active().isConfigured(); } catch (Exception e) { return false; }
    }

    public String chat(List<LlmMessage> messages) throws Exception {
        if (!enabled) throw new IllegalStateException("Tính năng AI đang tắt (grader.ai.enabled=false).");
        return active().chat(messages);
    }

    /** Trạng thái cho UI: provider nào, model gì, đã cắm key chưa. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("provider", provider);
        m.put("ready", isReady());
        try {
            LlmClient c = active();
            m.put("model", c.model());
            m.put("configured", c.isConfigured());
        } catch (Exception e) {
            m.put("model", null);
            m.put("configured", false);
            m.put("error", e.getMessage());
        }
        Map<String, Boolean> providers = new LinkedHashMap<>();
        clients.forEach((k, c) -> providers.put(k, c.isConfigured()));
        m.put("providers", providers);
        return m;
    }
}
