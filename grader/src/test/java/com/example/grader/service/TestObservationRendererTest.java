package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestObservationRendererTest {

    private static Map<String, Object> obs(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return m;
    }

    // ── câu ghép đúng mẫu "<tên yêu cầu>: <quan sát>" ─────────────
    @Test
    void putsTeacherWrittenNameAsSubjectOfSentence() {
        assertEquals("Báo lỗi từng ô nhập: không thấy thông báo lỗi nào sau khi thực hiện thao tác.",
                TestObservationRenderer.render("Báo lỗi từng ô nhập",
                        obs("kind", "MISSING", "subject", "error", "where", "after_action")));
    }

    @Test
    void printsTextTheStudentActuallyDisplays() {
        // Chữ sinh viên đang hiển thị là thứ em ấy TỰ KIỂM CHỨNG được trên máy mình.
        assertEquals("Tiêu đề màn hình: đang hiển thị \"Users\".",
                TestObservationRenderer.render("Tiêu đề màn hình",
                        obs("kind", "TEXT_MISMATCH", "subject", "text", "seen", "Users")));
    }

    @Test
    void printsCountsBecauseStudentCanRecount() {
        assertEquals("Số người dùng ban đầu: đếm được 3 mục trong danh sách, cần 2.",
                TestObservationRenderer.render("Số người dùng ban đầu",
                        obs("kind", "COUNT_MISMATCH", "subject", "item", "found", 3, "expected", 2)));
    }

    @Test
    void namesTheViewportWhereOverflowHappened() {
        assertEquals("Không tràn bố cục: giao diện bị tràn khung ở màn hình ngang.",
                TestObservationRenderer.render("Không tràn bố cục",
                        obs("kind", "OVERFLOW", "where", "landscape")));
    }

    @Test
    void distinguishesTheTwoNotRunCauses() {
        assertTrue(TestObservationRenderer.render("A", obs("kind", "NOT_RUN_BOOT"))
                .contains("ứng dụng không mở được"));
        assertTrue(TestObservationRenderer.render("A", obs("kind", "NOT_RUN_SUITE"))
                .contains("bộ test không khởi động được"));
    }

    // ── ba luật CẤM của SPEC mục 5.4 ──────────────────────────────
    private static final Pattern SEMANTIC_KEY = Pattern.compile("\\b[a-z]+\\.[a-z][a-z.\\-]*\\b");

    @Test
    void neverLeaksSemanticKeysEvenWhenPayloadIsPolluted() {
        // Payload bị nhét khoá nội bộ (lập trình viên tương lai vô tình truyền key vào subject)
        // thì câu ra vẫn không được chứa khoá đó — bảng `subject` là bảng ĐÓNG.
        String out = TestObservationRenderer.render("Ô nhập họ tên",
                obs("kind", "MISSING", "subject", "field.name"));
        assertNotNull(out);
        assertFalse(SEMANTIC_KEY.matcher(out).find(), out);
        assertFalse(out.contains("field.name"), out);
    }

    @Test
    void neverEmitsEnglishLogOrTestIdentifiers() {
        for (String kind : List.of("MISSING", "STILL_PRESENT", "OVERFLOW", "LAYOUT_ERROR",
                "BOOT_FAILED", "NOT_RUN_BOOT", "NOT_RUN_SUITE", "ENABLED_MISMATCH",
                "STYLE_MISMATCH", "LABEL_MISMATCH")) {
            String out = TestObservationRenderer.render("Yêu cầu X", obs("kind", kind));
            assertNotNull(out, kind);
            assertFalse(out.contains(".dart"), kind + ": " + out);
            assertFalse(out.toLowerCase().contains("expected"), kind + ": " + out);
            assertFalse(out.toLowerCase().contains("widget"), kind + ": " + out);
            assertFalse(out.contains("TC_"), kind + ": " + out);
        }
    }

    @Test
    void staysWithinTheLengthLimitOfRuleC5() {
        String longName = "Yêu cầu ".repeat(40);
        String out = TestObservationRenderer.render(longName,
                obs("kind", "TEXT_MISMATCH", "subject", "text", "seen", "x".repeat(200)));
        assertTrue(out.length() <= 160, "dài " + out.length());
    }

    // ── không diễn đạt được thì trả null để bên gọi giữ giá trị cũ ──
    @Test
    void returnsNullWhenItCannotSaySomethingTrue() {
        assertNull(TestObservationRenderer.render("A", null));
        assertNull(TestObservationRenderer.render("A", obs()));
        assertNull(TestObservationRenderer.render("A", obs("kind", "MOT_LOAI_CHUA_BIET")));
    }

    @Test
    void survivesWithoutTeacherNameWithoutFallingBackToTestId() {
        // Không có tên yêu cầu thì để câu quan sát đứng một mình — TUYỆT ĐỐI không lấy
        // test_id làm chủ ngữ, đó là định danh nội bộ (luật C2).
        String out = TestObservationRenderer.render("", obs("kind", "BOOT_FAILED"));
        assertEquals("Ứng dụng không mở được, chưa hiện được nội dung nào.", out);
    }
}
