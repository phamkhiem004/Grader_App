package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P6a — `exam.requirements`: hợp đồng "mỗi dòng một yêu cầu, y nguyên văn" nằm trọn trong
 * MỘT hàm tách ({@link BatchGradingService#splitRequirements}) và MỘT hàm chặn nhập
 * ({@link TestcaseTemplateService#validateRequirements}). Test này khoá ngữ nghĩa của cả hai,
 * vì phía NLP xây khối tất định trên đúng các bảo đảm này (phần tử không rỗng, không chứa
 * xuống dòng, giữ thứ tự, không bị chuẩn hoá).
 */
class ExamRequirementsTest {

    // ── splitRequirements: tách đúng, không sửa chữ ────────────────────────────────

    @Test
    void splitsOneElementPerLineKeepingOrderAndContentVerbatim() {
        List<String> out = BatchGradingService.splitRequirements(
                "Yêu cầu  đầu   tiên.\nYêu cầu thứ hai: dùng  hai   khoảng trắng.\nDòng chót");
        assertEquals(List.of(
                "Yêu cầu  đầu   tiên.",
                "Yêu cầu thứ hai: dùng  hai   khoảng trắng.",
                "Dòng chót"), out, "phải giữ thứ tự và giữ nguyên khoảng trắng TRONG dòng");
    }

    @Test
    void dropsCarriageReturnsAndBlankLinesButNothingElse() {
        // CRLF của Windows + dòng trắng giữa chừng + dòng chỉ có khoảng trắng.
        List<String> out = BatchGradingService.splitRequirements(
                "Dòng một.\r\n\r\n   \r\nDòng hai.\r\n");
        assertEquals(List.of("Dòng một.", "Dòng hai."), out);
        for (String item : out) {
            assertFalse(item.contains("\r") || item.contains("\n"),
                    "phần tử không được chứa ký tự xuống dòng: " + item);
            assertFalse(item.isBlank(), "phần tử không được rỗng");
        }
    }

    @Test
    void keepsLeadingWhitespaceBecauseVerbatimMeansVerbatim() {
        // Giảng viên thụt đầu dòng có chủ đích (ý con của một yêu cầu) — không phải việc của
        // Grader để "sửa". Chỉ dòng TRẮNG HOÀN TOÀN mới bị bỏ.
        assertEquals(List.of("  a) ý con thụt đầu dòng"),
                BatchGradingService.splitRequirements("  a) ý con thụt đầu dòng"));
    }

    @Test
    void nullAndBlankMeanPreP6ExamSoTheArrayIsEmpty() {
        assertTrue(BatchGradingService.splitRequirements(null).isEmpty());
        assertTrue(BatchGradingService.splitRequirements("").isEmpty());
        assertTrue(BatchGradingService.splitRequirements("\n \n\r\n").isEmpty());
    }

    // ── validateRequirements: trần ở khâu NHẬP ─────────────────────────────────────

    @Test
    void rejectsMissingRequirementsWithATeacherReadableMessage() {
        for (String bad : new String[]{null, "", "   ", "\n\n"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> TestcaseTemplateService.validateRequirements(bad));
            assertTrue(e.getMessage().contains("Yêu cầu của đề"), e.getMessage());
        }
    }

    @Test
    void enforcesTheCapsPromisedToTheNlpSide() {
        // Đây là hai con số đã công bố trong CHANGELOG — đổi là phải báo phía NLP.
        assertEquals(4000, TestcaseTemplateService.REQUIREMENTS_MAX_CHARS);
        assertEquals(40, TestcaseTemplateService.REQUIREMENTS_MAX_LINES);

        assertThrows(IllegalArgumentException.class, () ->
                TestcaseTemplateService.validateRequirements("x".repeat(4001)));
        assertDoesNotThrow(() ->
                TestcaseTemplateService.validateRequirements("x".repeat(4000)));

        String manyLines = "yêu cầu\n".repeat(41);
        assertThrows(IllegalArgumentException.class, () ->
                TestcaseTemplateService.validateRequirements(manyLines));
        assertDoesNotThrow(() ->
                TestcaseTemplateService.validateRequirements("yêu cầu\n".repeat(40)));
    }

    @Test
    void blankLinesDoNotCountTowardTheLineCap() {
        // 40 yêu cầu thật + dòng trắng xen giữa vẫn hợp lệ — trần đếm YÊU CẦU, không đếm phím Enter.
        assertDoesNotThrow(() ->
                TestcaseTemplateService.validateRequirements("yêu cầu\n\n".repeat(40)));
    }

    // ── Chính file fixture cũng phải hợp lệ theo đúng hai hàm trên ────────────────

    @Test
    void fixtureRequirementsFileIsValidAndSplitsCleanly() throws Exception {
        Path file = Path.of("..", "fixtures", "result-json-v2", "exam", "requirements.txt");
        assertTrue(Files.exists(file), "Thiếu " + file);
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TestcaseTemplateService.validateRequirements(raw));

        List<String> out = BatchGradingService.splitRequirements(raw);
        assertTrue(out.size() >= 3, "Fixture nên có ít nhất 3 yêu cầu, đang có " + out.size());
        // Pin hai ca cố ý cấy vào fixture: yêu cầu nhắc đường dẫn (đề hợp lệ được phép nhắc
        // tên tệp) và yêu cầu kiến trúc (thứ engine KHÔNG kiểm được — bên đọc chỉ liệt kê).
        assertTrue(out.stream().anyMatch(r -> r.contains("lib/")),
                "Fixture phải giữ một yêu cầu nhắc đường dẫn để pin ca này trên dữ liệu thật");
        assertTrue(out.stream().anyMatch(r -> r.contains("mô hình")),
                "Fixture phải giữ một yêu cầu kiến trúc (không kiểm được bằng testcase)");
    }
}
