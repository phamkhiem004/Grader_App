package com.example.grader.service.ai;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExamDocumentReaderTest {

    private final ExamDocumentReader reader = new ExamDocumentReader();

    @Test
    void readsPlainTextKeepingVietnameseAndParagraphs() {
        String de = "PE_PRM393 — Quản lý chi tiêu\n\nYêu cầu 1: màn hình nhập khoản chi.\n"
                + "Yêu cầu 2: danh sách khoản chi có nút sửa và xoá.";
        Map<String, Object> out = reader.read("de-bai.txt", de.getBytes(StandardCharsets.UTF_8));

        assertEquals("text", out.get("format"));
        assertTrue(String.valueOf(out.get("text")).contains("Quản lý chi tiêu"));
        assertTrue(String.valueOf(out.get("text")).contains("Yêu cầu 2"));
    }

    /** .docx là ZIP chứa word/document.xml — dựng đúng cấu trúc đó rồi đọc lại. */
    @Test
    void readsDocxParagraphsInOrder() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="x"><w:body>
                  <w:p><w:r><w:t>ĐỀ BÀI: PE_PRM393</w:t></w:r></w:p>
                  <w:p><w:r><w:t xml:space="preserve">Câu 1. </w:t></w:r><w:r><w:t>Tạo màn hình nhập liệu</w:t></w:r></w:p>
                  <w:p><w:r><w:t>Câu 2. Hiển thị danh sách &amp; tổng tiền</w:t></w:r></w:p>
                </w:body></w:document>
                """;
        Map<String, Object> out = reader.read("de.docx", docx(xml));

        assertEquals("docx", out.get("format"));
        String text = String.valueOf(out.get("text"));
        assertTrue(text.startsWith("ĐỀ BÀI: PE_PRM393"), text);
        assertTrue(text.contains("Câu 1. Tạo màn hình nhập liệu"), "hai run trong cùng đoạn phải nối liền: " + text);
        assertTrue(text.contains("Câu 2. Hiển thị danh sách & tổng tiền"), "phải giải mã &amp;: " + text);
    }

    @Test
    void rejectsUnsupportedAndLegacyFormats() {
        byte[] any = "noi dung".repeat(20).getBytes(StandardCharsets.UTF_8);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> reader.read("de.doc", any)).getMessage().contains(".docx"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> reader.read("de.rtf", any)).getMessage().contains("Chỉ đọc được"));
        assertThrows(IllegalArgumentException.class, () -> reader.read("de.txt", new byte[0]));
    }

    /** PDF scan không có lớp chữ → phải nói thẳng là đổi sang .docx, không trả về chuỗi rác. */
    @Test
    void tellsUserToUseDocxWhenPdfHasNoTextLayer() {
        byte[] fakePdf = ("%PDF-1.7\n1 0 obj<</Type/Page>>endobj\ntrailer\n%%EOF")
                .getBytes(StandardCharsets.ISO_8859_1);
        String message = assertThrows(IllegalArgumentException.class,
                () -> reader.read("scan.pdf", fakePdf)).getMessage();
        assertTrue(message.contains(".docx"), message);
    }

    /**
     * Chữ tiếng Việt có dấu KHÔNG phải chữ rác. Rác thật là ký tự vùng dùng riêng (uF0xx) —
     * thứ mà font nhúng mã hóa riêng trong PDF nhả ra khi bóc chữ thiếu bảng ToUnicode.
     */
    @Test
    void warnsWhenTextLooksGarbled() {
        String garbled = "  ".repeat(12);
        Map<String, Object> out = reader.read("de.txt", garbled.getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) out.get("warnings");
        assertFalse(warnings.isEmpty(), "chữ rác phải kèm cảnh báo");
    }

    private byte[] docx(String documentXml) throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(sink)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return sink.toByteArray();
    }
}
