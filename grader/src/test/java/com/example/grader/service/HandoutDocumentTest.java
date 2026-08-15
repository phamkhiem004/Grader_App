package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandoutDocumentTest {

    private static final String MARKDOWN = """
            # Đề bài PE_PRM393
            Xây dựng ứng dụng **quản lý chi tiêu** bằng Flutter.

            ## Yêu cầu
            - Màn hình nhập khoản chi
            - Danh sách khoản chi

            1. Tạo model `Expense`
            2. Lưu bằng sqflite
            """;

    @Test
    void mergesExamTextAndMockupIntoOneSelfContainedFile() {
        String svg = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>";
        String html = HandoutDocument.toHtml("PE_01", "Quản lý chi tiêu", MARKDOWN,
                List.of(new HandoutDocument.Mockup("home", "Màn hình chính", svg)));

        assertTrue(html.contains("<strong>quản lý chi tiêu</strong>"), "phải hiểu **in đậm**");
        assertTrue(html.contains("<code>Expense</code>"), "phải hiểu `mã inline`");
        assertTrue(html.contains("<li>Màn hình nhập khoản chi</li>"));
        assertTrue(html.contains("<svg xmlns"), "SVG phải nhúng thẳng vào file");
        assertFalse(html.contains("<?xml"), "khai báo XML của SVG phải bị bỏ khi nhúng");
        assertTrue(html.contains("Màn hình chính"));
        // Không tham chiếu file rời nào: đúng nghĩa "đề và hình trong cùng một file".
        assertFalse(html.contains("<img"), html.substring(0, Math.min(200, html.length())));
    }

    @Test
    void escapesTeacherTextSoLayoutCannotBeBroken() {
        String html = HandoutDocument.toHtml("PE_02", null, "Điểm < 5 & > 0 </script>", List.of());
        assertTrue(html.contains("&lt; 5 &amp; &gt; 0"));
        assertFalse(html.contains("</script>"));
    }

    @Test
    void parsesHeadingsListsAndCodeBlocks() {
        List<HandoutDocument.Block> blocks = HandoutDocument.parse(MARKDOWN + "\n```dart\nvoid main() {}\n```\n");
        Map<String, Integer> count = new LinkedHashMap<>();
        blocks.forEach(b -> count.merge(b.type(), 1, Integer::sum));

        assertEquals(1, count.get("h1"));
        assertEquals(1, count.get("h2"));
        assertEquals(2, count.get("li"));
        assertEquals(2, count.get("ol"));
        assertEquals(1, count.get("code"));
    }

    /** File .docx phải là ZIP OOXML hợp lệ: đủ 4 phần bắt buộc + ảnh + quan hệ ảnh. */
    @Test
    void writesValidDocxPackageWithImage() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        byte[] docx = new DocxWriter()
                .heading("Đề bài PE_PRM393", 1)
                .paragraph("Nội dung có ký tự đặc biệt < & >")
                .bullet("Gạch đầu dòng", false, 0)
                .bullet("Mục đánh số", true, 1)
                .code("void main() {}")
                .image(png, 800, 600)
                .build();

        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null)
                parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }

        assertTrue(parts.containsKey("[Content_Types].xml"));
        assertTrue(parts.containsKey("_rels/.rels"));
        assertTrue(parts.containsKey("word/document.xml"));
        assertTrue(parts.containsKey("word/media/image1.png"));
        assertTrue(parts.get("word/_rels/document.xml.rels").contains("media/image1.png"));

        String document = parts.get("word/document.xml");
        assertTrue(document.contains("Đề bài PE_PRM393"));
        assertTrue(document.contains("&lt; &amp; &gt;"), "ký tự đặc biệt phải được escape");
        assertTrue(document.contains("<w:drawing>"));
        assertTrue(document.contains("r:embed=\"rId11\""), "ảnh đầu tiên phải trỏ đúng quan hệ rId11");
        // Ảnh 800px rộng hơn khổ in → phải thu về 640px (640 * 9525 EMU).
        assertTrue(document.contains("cx=\"6096000\""), document.substring(document.indexOf("<wp:extent")));
    }
}
