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
 * Cấu hình LLM đang có hiệu lực.
 *
 * <p>MODEL là thứ duy nhất người dùng chọn; nhà cung cấp và endpoint được suy ra từ mã model
 * ({@link AiModelCatalog#providerFor}). Bắt chọn provider rồi gõ endpoint của hãng là thừa —
 * người dùng chỉ cần biết mình muốn dùng Claude, GPT hay Gemini.
 *
 * <p>Thứ tự ưu tiên: giá trị nhập trên web (DB) → biến môi trường/application.properties.
 * Nhờ vậy máy đã cắm key qua env vẫn chạy như cũ.
 */
@Slf4j
@Service
public class AiSettingsService {

    public static final List<String> PROVIDERS = AiModelCatalog.PROVIDERS;

    /** Model mặc định cho từng provider khi người dùng để trống. */
    private static final Map<String, String> DEFAULT_MODEL = Map.of(
            AiModelCatalog.ANTHROPIC, "claude-opus-5",
            AiModelCatalog.GEMINI, "gemini-2.0-flash",
            AiModelCatalog.OPENAI, "gpt-4o-mini");

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
    @Value("${grader.ai.anthropic.api-key:}")
    private String envAnthropicKey;
    @Value("${grader.ai.anthropic.model:}")
    private String envAnthropicModel;
    @Value("${grader.ai.timeout-seconds:180}")
    private int envTimeoutSeconds;

    @Autowired
    private AiSettingRepository repo;

    /** Mã model chỉ gồm ký tự an toàn — nó được ghép thẳng vào URL của Gemini. */
    private static final java.util.regex.Pattern SAFE_MODEL =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,80}$");

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
        log.info("Cấu hình AI: model={} ({}) {}", model(), provider(),
                hasApiKey() ? "(đã có API key)" : "(CHƯA có API key)");
    }

    /** Máy đã cắm key qua biến môi trường vẫn chạy y như trước; provider lấy theo cấu hình cũ. */
    private void applyEnvDefaults() {
        String envSide = normalizeProvider(envProvider);
        timeoutSeconds = envTimeoutSeconds > 0 ? envTimeoutSeconds : 180;
        switch (envSide) {
            case AiModelCatalog.ANTHROPIC -> {
                apiKey = blankToNull(envAnthropicKey);
                model = blankToNull(envAnthropicModel);
                baseUrl = null;                       // dùng endpoint chính thức
            }
            case AiModelCatalog.OPENAI -> {
                apiKey = blankToNull(envOpenAiKey);
                model = blankToNull(envOpenAiModel);
                baseUrl = blankToNull(envOpenAiBaseUrl);
            }
            default -> {
                apiKey = blankToNull(envGeminiKey);
                model = blankToNull(envGeminiModel);
                baseUrl = blankToNull(envGeminiBaseUrl);
            }
        }
        // Không có model trong env thì lấy mặc định của chính nhà cung cấp đó, để provider()
        // (vốn suy từ model) không nhảy sang hãng khác.
        if (model == null) model = DEFAULT_MODEL.get(envSide);
    }

    // ── Giá trị đang dùng ────────────────────────────────────────

    /** Suy từ mã model — không lưu riêng, nên không bao giờ lệch nhau. */
    public String provider()  { return AiModelCatalog.providerFor(model()); }
    public String apiKey()    { return apiKey; }
    public boolean hasApiKey(){ return apiKey != null && !apiKey.isBlank(); }
    public int timeoutSeconds() { return timeoutSeconds > 0 ? timeoutSeconds : 180; }

    public String model() {
        return model != null && !model.isBlank() ? model : "claude-opus-5";
    }

    public String baseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) return baseUrl.trim().replaceAll("/+$", "");
        return AiModelCatalog.defaultBaseUrl(provider());
    }

    // ── API cho controller ───────────────────────────────────────

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model());
        m.put("provider", provider());
        m.put("vendor", AiModelCatalog.vendorLabel(provider()));
        m.put("keyUrl", AiModelCatalog.keyUrl(provider()));   // chỗ lấy key của đúng hãng đang chọn
        m.put("hasApiKey", hasApiKey());
        m.put("apiKeyMasked", mask(apiKey));                  // KHÔNG bao giờ trả key nguyên vẹn
        m.put("timeoutSeconds", timeoutSeconds());
        m.put("models", AiModelCatalog.models());
        m.put("baseUrl", baseUrl());
        m.put("customBaseUrl", baseUrl != null && !baseUrl.isBlank());
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
        String newModel = text(body, "model", model());
        if (newModel == null || newModel.isBlank())
            throw new IllegalArgumentException("Hãy chọn model AI muốn dùng.");
        if (!SAFE_MODEL.matcher(newModel).matches())
            throw new IllegalArgumentException("Mã model không hợp lệ: " + newModel
                    + ". Chỉ gồm chữ, số và các ký tự . _ - :");

        String newBaseUrl = text(body, "baseUrl", null);
        String suppliedKey = text(body, "apiKey", null);
        boolean clearKey = Boolean.TRUE.equals(body.get("clearApiKey"));
        int newTimeout = (int) number(body.get("timeoutSeconds"), timeoutSeconds());
        if (newTimeout < 30 || newTimeout > 900)
            throw new IllegalArgumentException("Thời gian chờ mỗi lần gọi AI phải trong khoảng 30 – 900 giây");

        // Đổi sang model của HÃNG KHÁC mà không nhập key mới: key cũ chắc chắn không dùng được
        // (key Claude không phải key OpenAI). Xoá luôn để giao diện báo "chưa có key" ngay, thay vì
        // để người dùng đâm vào lỗi 401 khó hiểu ở lần sinh đề đầu tiên.
        String newProvider = AiModelCatalog.providerFor(newModel);
        boolean vendorChanged = !newProvider.equals(provider());
        String finalKey = clearKey || (vendorChanged && suppliedKey == null)
                ? null
                : (suppliedKey != null ? suppliedKey : apiKey);

        // Endpoint riêng chỉ có nghĩa với các bản tương thích OpenAI (Ollama, OpenRouter, Groq).
        if (newBaseUrl != null && !newBaseUrl.isBlank()) {
            if (!newBaseUrl.startsWith("http://") && !newBaseUrl.startsWith("https://"))
                throw new IllegalArgumentException("Endpoint phải bắt đầu bằng http:// hoặc https://");
            if (!AiModelCatalog.OPENAI.equals(newProvider))
                throw new IllegalArgumentException(
                        "Chỉ model tương thích OpenAI mới đặt được endpoint riêng; "
                                + AiModelCatalog.vendorLabel(newProvider) + " luôn dùng endpoint chính thức.");
        }

        Instant now = Instant.now();
        try {
            AiSetting row = repo.findById(AiSetting.SINGLETON_ID).orElseGet(AiSetting::new);
            row.setId(AiSetting.SINGLETON_ID);
            row.setProvider(newProvider);          // lưu để tra cứu/audit; khi đọc vẫn suy từ model
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

        model = newModel;
        baseUrl = newBaseUrl;
        apiKey = finalKey;
        timeoutSeconds = newTimeout;
        updatedAt = now;
        updatedBy = actor;
        log.info("Cấu hình AI cập nhật: model={} ({}) (bởi {})", model(), provider(), actor);
        return describe();
    }

    /** Che key khi trả về UI: giữ 4 ký tự cuối để người dùng nhận ra mình đang dùng key nào. */
    private String mask(String key) {
        if (key == null || key.isBlank()) return null;
        String trimmed = key.trim();
        if (trimmed.length() <= 8) return "••••";
        return trimmed.substring(0, 3) + "••••" + trimmed.substring(trimmed.length() - 4);
    }

    /** Chỉ dùng để đọc cấu hình MÔI TRƯỜNG cũ; giá trị lạ rơi về gemini như hành vi trước đây. */
    private String normalizeProvider(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return PROVIDERS.contains(value) ? value : AiModelCatalog.GEMINI;
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
