package com.example.grader.service.ai;

import java.util.List;

/**
 * Tham số GV nhập ở wizard để sinh đề:
 *  - {@code examId/examName} : mã & tên đề (để lưu sau khi duyệt).
 *  - {@code topic}           : ĐỀ BÀI / ý tưởng thô (bắt buộc) — càng rõ tên file/class/text UI càng tốt.
 *  - {@code numTestcases}    : số testcase mong muốn.
 *  - {@code difficulty}      : "mixed" | "basic" | "intermediate" | "advanced".
 *  - {@code categories}      : các mảng kiến thức ưu tiên (mã category/skill, tùy chọn).
 *  - {@code extra}           : yêu cầu thêm tự do.
 */
public record GenRequest(
        String examId,
        String examName,
        String topic,
        Integer numTestcases,
        String difficulty,
        List<String> categories,
        String extra) {

    public int testcases() { return numTestcases == null || numTestcases < 1 ? 6 : Math.min(numTestcases, 80); }
    public String difficultyOr() { return difficulty == null || difficulty.isBlank() ? "mixed" : difficulty.trim(); }
}
