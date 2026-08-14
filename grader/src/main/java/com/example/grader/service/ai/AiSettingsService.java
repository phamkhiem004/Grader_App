package com.example.grader.service.ai;

import com.example.grader.entity.AiSetting;
import com.example.grader.repository.AiSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cấu hình LLM đang có hiệu lực: nhà cung cấp, key, model, endpoint.
 *
 * <p>Thứ tự ưu tiên: giá trị nhập trên web (DB) → biến môi trường/application.properties.
 * Nhờ vậy máy đã cắm key qua env vẫn chạy như cũ, còn người dùng bình thường chỉ cần dán key
 * vào trang tạo testcase là xong.
 */
@Slf4j
@Service
public class AiSettingsService {

    public static final List<String> PROVIDERS = List.of("gemini", "openai");

    /** Model mặc định cho từng provider khi người dùng để trống. */
    private static final Map<String, String> DEFAULT_MODEL = Map.of(
            "gemini", "gemini-2.0-flash",
            "openai", "gpt-4o-mini");

    @Value("${grader.ai.provider:gemini}")
    private String envProvider;
    @Value("${grader.ai.gemini.api-key:}")
    private String envGeminiKey;
    @Value("${grader.ai.gemini.model:}")
    private String envGeminiModel;
    @Value("${grader.ai.openai.api-key:}")
    private String envOpenAiKey;
    @Value("${grader.ai.openai.model:}")
    private String envOpenAiModel;
    @Value("${grader.ai.openai.base-url:https://api.openai.com/v1}")
    private String envOpenAiBaseUrl;
    @Value("${grader.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String envGeminiBaseUrl;
    @Value("${grader.ai.timeout-seconds:180}")
    private int envTimeoutSeconds;

    @Autowired
    private AiSettingRepository repo;

    private volatile String provider;
    private volatile String model;
    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile int timeoutSeconds;
    private volatile Instant updatedAt;
    private volatile String updatedBy;

    @PostConstruct
    public void load() {
        applyEnvDefaults();
        try {
            repo.findById(AiSetting.SINGLETON_ID).ifPresent(row -> {
                provider = normalizeProvider(row.getProvider());
                model = blankToNull(row.getModel());
                baseUrl = blankToNull(row.getBaseUrl());
                apiKey = blankToNull(row.getApiKey()) != null ? row.getApiKey() : apiKey;
                timeoutSeconds = row.getTimeoutSeconds() != null ? row.getTimeoutSeconds() : timeoutSeconds;
                updatedAt = row.getUpdatedAt();
                updatedBy = row.getUpdatedBy();
            });
        } catch (Exception e) {
            log.warn("Không đọc được cấu hình AI từ DB, dùng cấu hình môi trường: {}", e.getMessage());
        }
        log.info("Cấu hình AI: provider={} model={} {}", provider(), model(),
                hasApiKey() ? "(đã có API key)" : "(CHƯA có API key)");
    }

    private void applyEnvDefaults() {
        provider = normalizeProvider(envProvider);
        timeoutSeconds = envTimeoutSeconds > 0 ? envTimeoutSeconds : 180;
        if ("openai".equals(provider)) {
            apiKey = blankToNull(envOpenAiKey);
            model = blankToNull(envOpenAiModel);
            baseUrl = blankToNull(envOpenAiBaseUrl);
        } else {
            apiKey = blankToNull(envGeminiKey);
            model = blankToNull(envGeminiModel);
            baseUrl = blankToNull(envGeminiBaseUrl);
        }
    }

    // ── Giá trị đang dùng ────────────────────────────────────────

    public String provider()  { return provider == null ? "gemini" : provider; }
    public String apiKey()    { return apiKey; }
    public boolean hasApiKey(){ return apiKey != null && !apiKey.isBlank(); }
    public int timeoutSeconds() { return timeoutSeconds > 0 ? timeoutSeconds : 180; }

    public String model() {
        if (model != null && !model.isBlank()) return model;
        return DEFAULT_MODEL.getOrDefault(provider(), "gemini-2.0-flash");
    }

    public String baseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) return baseUrl.trim().replaceAll("/+$", "");
        return "openai".equals(provider())
                ? "https://api.openai.com/v1"
                : "https://generativelanguage.googleapis.com/v1beta";
    }

    // ── API cho controller ───────────────────────────────────────

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider());
        m.put("model", model());
        m.put("baseUrl", baseUrl());
        m.put("hasApiKey", hasApiKey());
        m.put("apiKeyMasked", mask(apiKey));          // KHÔNG bao giờ trả key nguyên vẹn
        m.put("timeoutSeconds", timeoutSeconds());
        m.put("providers", PROVIDERS);
        m.put("defaultModels", DEFAULT_MODEL);
        m.put("ready", hasApiKey());
        m.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        m.put("updatedBy", updatedBy);
        return m;
    }

    /**
     * Lưu cấu hình mới. Bỏ trống {@code apiKey} = GIỮ NGUYÊN key cũ (giao diện chỉ hiện key che,
     * không thể gửi lại key thật) — muốn xoá key thì gửi {@code apiKey: ""} kèm
     * {@code clearApiKey: true}.
     */
    public Map<String, Object> update(Map<String, Object> body, String actor) {
        // KHÔNG chuẩn hoá trước rồi mới kiểm tra: normalizeProvider vốn rơi về "gemini" cho mọi
        // giá trị lạ, làm phép kiểm tra không bao giờ chạm tới và người dùng gõ sai vẫn lưu êm.
        String rawProvider = text(body, "provider", null);
        String newProvider = rawProvider == null ? provider() : rawProvider.toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(newProvider))
            throw new IllegalArgumentException("Nhà cung cấp không hợp lệ: " + rawProvider
                    + ". Hợp lệ: " + PROVIDERS);

        String newModel = text(body, "model", null);
        String newBaseUrl = text(body, "baseUrl", null);
        String suppliedKey = text(body, "apiKey", null);
        boolean clearKey = Boolean.TRUE.equals(body.get("clearApiKey"));
        int newTimeout = (int) number(body.get("timeoutSeconds"), timeoutSeconds());
        if (newTimeout < 30 || newTimeout > 900)
            throw new IllegalArgumentException("Thời gian chờ mỗi lần gọi AI phải trong khoảng 30 – 900 giây");

        // Đổi nhà cung cấp mà không nhập key mới: key cũ chắc chắn không dùng được (key Gemini
        // không phải key OpenAI). Xoá luôn để giao diện báo "chưa có key" ngay, thay vì để người
        // dùng đâm vào lỗi 401 khó hiểu ở lần sinh đề đầu tiên.
        boolean providerChanged = !newProvider.equals(provider());
        String finalKey = clearKey || (providerChanged && suppliedKey == null)
                ? null
                : (suppliedKey != null ? suppliedKey : apiKey);
        if (newBaseUrl != null && !newBaseUrl.isBlank()
                && !newBaseUrl.startsWith("http://") && !newBaseUrl.startsWith("https://"))
            throw new IllegalArgumentException("Endpoint phải bắt đầu bằng http:// hoặc https://");

        Instant now = Instant.now();
        try {
            AiSetting row = repo.findById(AiSetting.SINGLETON_ID).orElseGet(AiSetting::new);
            row.setId(AiSetting.SINGLETON_ID);
            row.setProvider(newProvider);
            row.setModel(newModel);
            row.setBaseUrl(newBaseUrl);
            row.setApiKey(finalKey);
            row.setTimeoutSeconds(newTimeout);
            row.setUpdatedAt(now);
            row.setUpdatedBy(actor);
            repo.save(row);
        } catch (Exception e) {
            throw new IllegalStateException("Không lưu được cấu hình AI: " + e.getMessage(), e);
        }

        provider = newProvider;
        model = newModel;
        baseUrl = newBaseUrl;
        apiKey = finalKey;
        timeoutSeconds = newTimeout;
        updatedAt = now;
        updatedBy = actor;
        log.info("Cấu hình AI cập nhật: provider={} model={} (bởi {})", provider(), model(), actor);
        return describe();
    }

    /** Che key khi trả về UI: giữ 4 ký tự cuối để người dùng nhận ra mình đang dùng key nào. */
    private String mask(String key) {
        if (key == null || key.isBlank()) return null;
        String trimmed = key.trim();
        if (trimmed.length() <= 8) return "••••";
        return trimmed.substring(0, 3) + "••••" + trimmed.substring(trimmed.length() - 4);
    }

    private String normalizeProvider(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return PROVIDERS.contains(value) ? value : "gemini";
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String text(Map<String, Object> body, String key, String fallback) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    private double number(Object v, double fallback) {
        if (v == null) return fallback;
        try {
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
