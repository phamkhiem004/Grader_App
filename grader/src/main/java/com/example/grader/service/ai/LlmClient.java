package com.example.grader.service.ai;

import java.util.List;

/**
 * Cổng gọi một mô hình ngôn ngữ (LLM). Mỗi nhà cung cấp (Gemini, OpenAI/GPT...) là một bean
 * cài đặt interface này → đổi provider chỉ là đổi cấu hình {@code grader.ai.provider}, KHÔNG sửa code.
 *
 * <p>CHỖ CẮM API KEY: để trống {@code grader.ai.<provider>.api-key} trong application.properties.
 * Khi key trống, {@link #isConfigured()} = false → AiExamGenService báo "chưa cấu hình" thay vì gọi mạng.
 */
public interface LlmClient {

    /** Khóa định danh provider, khớp giá trị {@code grader.ai.provider} (vd "gemini", "openai"). */
    String key();

    /** Đã cắm API key chưa (chuỗi không rỗng)? */
    boolean isConfigured();

    /** Tên model đang dùng (để hiển thị trên UI). */
    String model();

    /**
     * Gửi cả đoạn hội thoại (system + các lượt user/assistant) và nhận về VĂN BẢN trả lời.
     * Cài JSON-mode để model trả về đúng 1 object JSON (hợp đồng đầu ra của bộ sinh đề).
     */
    String chat(List<LlmMessage> messages) throws Exception;
}
