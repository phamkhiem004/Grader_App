package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        assertEquals("Số người dùng ban đầu: đếm được 3 mục, cần 2.",
                TestObservationRenderer.render("Số người dùng ban đầu",
                        obs("kind", "COUNT_MISMATCH", "subject", "item", "found", 3, "expected", 2)));
    }

    /**
     * Danh từ trong bảng `subject` phải là danh từ ĐƠN, vì cùng một giá trị đi qua nhiều khuôn câu
     * khác nhau. A2 đo được câu sai ngữ pháp *"không thấy mục trong danh sách nào"* — cụm dài rơi
     * vào khuôn "không thấy &lt;X&gt; nào" là hỏng. Test này chặn kiểu hỏng đó cho MỌI `subject`.
     */
    @Test
    void everySubjectFitsTheMissingSentenceFrame() {
        for (String subject : List.of("field", "input", "button", "list", "item", "dialog",
                "screen", "error", "text", "image", "icon", "checkbox", "widget")) {
            String out = TestObservationRenderer.render("Yêu cầu X",
                    obs("kind", "MISSING", "subject", subject));
            assertNotNull(out, subject);
            // Danh từ đơn thì "nào" đứng ngay sau nó; cụm có giới từ thì "nào" bị đẩy ra sau
            // bổ ngữ và câu đọc sai. Bắt bằng chính các giới từ hay dùng trong bảng.
            assertFalse(out.matches(".*\\b(trong|của|trên|ở)\\b.*nào.*"),
                    subject + " là cụm có giới từ, không ghép được vào khuôn câu: " + out);
        }
    }

    @Test
    void saysSomethingIsThereButOfTheWrongKind() {
        // Không được nói "không thấy" — sinh viên sẽ đi thêm một thành phần nữa vào chỗ đã có sẵn.
        String out = TestObservationRenderer.render("Biểu tượng đầu form",
                obs("kind", "TYPE_MISMATCH", "subject", "icon"));
        assertEquals("Biểu tượng đầu form: chỗ này đang là thành phần khác, "
                + "không phải biểu tượng như đề yêu cầu.", out);
        assertFalse(out.contains("không thấy"), out);
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

    // ── A1: error_code suy từ kind ────────────────────────────────
    @Test
    void mapsKindToErrorCode() {
        assertEquals("WIDGET_NOT_FOUND", TestObservationRenderer.errorCodeOf(obs("kind", "MISSING")));
        assertEquals("LAYOUT_OVERFLOW", TestObservationRenderer.errorCodeOf(obs("kind", "OVERFLOW")));
        assertEquals("VALUE_MISMATCH", TestObservationRenderer.errorCodeOf(obs("kind", "TEXT_MISMATCH")));
        assertEquals("EXCEPTION_THROWN", TestObservationRenderer.errorCodeOf(obs("kind", "BOOT_FAILED")));
    }

    @Test
    void givesNoCodeForNotRunOrUnknownKind() {
        // Chưa chạy thì không quan sát được gì để phân loại. Và `kind` lạ phải trả null để bên gọi
        // giữ giá trị của classifier — một mã SAI còn tệ hơn không có mã, vì bên đọc gom nhóm theo nó.
        assertNull(TestObservationRenderer.errorCodeOf(obs("kind", "NOT_RUN_BOOT")));
        assertNull(TestObservationRenderer.errorCodeOf(obs("kind", "NOT_RUN_SUITE")));
        assertNull(TestObservationRenderer.errorCodeOf(obs("kind", "MOT_LOAI_CHUA_BIET")));
        assertNull(TestObservationRenderer.errorCodeOf(null));
    }

    /**
     * ĐIỀU KIỆN CỦA PHÍA NLP: không được ép nhiều `kind` vào cùng một `error_code` khi cách sửa của
     * sinh viên khác nhau — trùng mã thì bên đọc gộp làm một đoạn góp ý, sinh viên sửa một nửa.
     *
     * <p>Năm ca dưới đây có năm cách sửa khác nhau: sửa chuỗi · sửa số đo · sửa kiểu chữ · thêm
     * nhãn trợ năng · sửa logic bật-tắt. Thêm `kind` mới mà dồn vào mã có sẵn thì test này đỏ.
     */
    @Test
    void neverCollapsesKindsThatNeedDifferentFixes() {
        List<String> kinds = List.of("TEXT_MISMATCH", "NUMBER_MISMATCH", "STYLE_MISMATCH",
                "LABEL_MISMATCH", "ENABLED_MISMATCH", "MISSING", "TYPE_MISMATCH");
        Set<String> codes = new LinkedHashSet<>();
        for (String kind : kinds) {
            String code = TestObservationRenderer.errorCodeOf(obs("kind", kind));
            assertNotNull(code, kind);
            assertTrue(codes.add(code), kind + " bị dồn vào mã đã dùng: " + code);
        }
        assertEquals(kinds.size(), codes.size());
    }

    @Test
    void everyRenderableKindExceptNotRunHasACode() {
        // Chốt chặn lệch bảng: hai bảng (`kind`→câu và `kind`→mã) nằm cùng class, nhưng vẫn có thể
        // thêm vào một bên mà quên bên kia. Mọi kind diễn đạt được đều phải có mã, trừ NOT_RUN_*.
        //
        // Danh sách lấy TỪ CHÍNH lớp đó (`renderableKinds`), không chép tay: chép tay thì thêm
        // `kind` mới mà quên sửa test là test vẫn xanh — đúng lỗ hổng đang muốn bịt.
        Set<String> kinds = TestObservationRenderer.renderableKinds();
        assertEquals(14, kinds.size(), "SPEC 5.5 khai 14 kind: " + kinds);
        for (String kind : kinds) {
            assertNotNull(TestObservationRenderer.render("Yêu cầu X", obs("kind", kind)), kind);
            if (kind.startsWith("NOT_RUN_")) {
                assertNull(TestObservationRenderer.errorCodeOf(obs("kind", kind)),
                        kind + " chưa chạy nên KHÔNG được có error_code");
            } else {
                assertNotNull(TestObservationRenderer.errorCodeOf(obs("kind", kind)),
                        kind + " diễn đạt được nhưng chưa có error_code");
            }
        }
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
