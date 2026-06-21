package com.example.grader.service.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kết quả 1 lần compile-check bộ testcase sinh ra (chạy thử trong ảnh nền với LỜI GIẢI MẪU):
 *  - {@code compiled}  : exam_test.dart + lời giải BIÊN DỊCH SẠCH (không lỗi cú pháp/thiếu symbol).
 *  - {@code allPassed} : biên dịch được VÀ mọi test PASS với lời giải mẫu (→ bộ testcase nhất quán).
 *  - {@code passed/total} : số test pass / tổng.
 *  - {@code errorsExcerpt} : vài dòng lỗi cô đọng để FEED NGƯỢC cho LLM sửa.
 *  - {@code failedTests} : TÊN các test FAIL (từ reporter json) — để backend tự BỎ test fail, giữ phần PASS.
 */
public record CompileResult(
        boolean compiled,
        boolean allPassed,
        int passed,
        int total,
        String summary,
        String errorsExcerpt,
        String rawLog,
        List<String> failedTests) {

    public boolean ok() { return compiled && allPassed; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("compiled", compiled);
        m.put("allPassed", allPassed);
        m.put("passed", passed);
        m.put("total", total);
        m.put("summary", summary);
        m.put("errorsExcerpt", errorsExcerpt);
        return m;
    }

    public static CompileResult fail(String summary, String errors, String raw) {
        return new CompileResult(false, false, 0, 0, summary, errors, raw, List.of());
    }
}
