package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bộ định tuyến LLM: chọn client theo provider đang cấu hình rồi ủy thác. Thêm nhà cung cấp mới
 * = thêm một bean cài {@link LlmClient}, không phải sửa ở đây.
 *
 * <p>Cũng là nơi DUY NHẤT ép "câu trả lời phải là JSON": model đôi khi bọc JSON trong ```json …```
 * hoặc kèm lời dẫn, nên {@link #chatJson} tự bóc trước khi parse thay vì để cả tính năng chết vì
 * một dấu backtick.
 */
@Slf4j
@Service
public class LlmService {

    @Autowired private AiSettingsService settings;

    private final Map<String, LlmClient> clients = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmService(List<LlmClient> beans) {
        for (LlmClient c : beans) clients.put(c.key(), c);
    }

    public boolean isReady() {
        return settings.hasApiKey() && clients.containsKey(settings.provider());
    }

    private LlmClient active() {
        LlmClient c = clients.get(settings.provider());
        if (c == null)
            throw new IllegalStateException("Nhà cung cấp AI không hợp lệ: " + settings.provider());
        return c;
    }

    /** Gọi LLM và trả về JSON đã parse. Ném lỗi RÕ RÀNG (tiếng Việt) để hiện thẳng lên giao diện. */
    public JsonNode chatJson(List<LlmMessage> messages) {
        if (!settings.hasApiKey())
            throw new IllegalStateException(
                    "Chưa nhập API key. Mở phần \"Trợ lý AI\" → Cấu hình để dán key trước khi dùng.");
        String raw;
        try {
            raw = active().chat(messages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Đã hủy lời gọi AI.");
        } catch (Exception e) {
            throw new IllegalStateException("Gọi AI thất bại: " + e.getMessage(), e);
        }
        try {
            return mapper.readTree(stripFence(raw));
        } catch (Exception e) {
            throw new IllegalStateException("AI trả về nội dung không phải JSON hợp lệ. "
                    + "Thử lại hoặc đổi sang model mạnh hơn. Trích đoạn: " + excerpt(raw));
        }
    }

    /** Bóc rào ```json … ``` và phần chữ thừa hai đầu để lấy đúng object JSON. */
    private String stripFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstBreak = s.indexOf('\n');
            if (firstBreak > 0) s = s.substring(firstBreak + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }

    /** Gọi thử một lượt ngắn để người dùng biết key/model có dùng được không. */
    public Map<String, Object> testConnection() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", settings.provider());
        out.put("model", settings.model());
        long started = System.currentTimeMillis();
        try {
            JsonNode res = chatJson(List.of(
                    LlmMessage.system("Bạn trả lời bằng JSON. Chỉ trả về {\"ok\":true}."),
                    LlmMessage.user("Trả về đúng {\"ok\":true}")));
            out.put("ok", res.path("ok").asBoolean(true));
            out.put("elapsedMs", System.currentTimeMillis() - started);
            out.put("message", "Kết nối thành công.");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("elapsedMs", System.currentTimeMillis() - started);
            out.put("message", e.getMessage());
        }
        return out;
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }
}
