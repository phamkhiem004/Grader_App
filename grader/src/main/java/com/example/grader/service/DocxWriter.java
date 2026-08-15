package com.example.grader.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Ghi file .docx (Word) BẰNG TAY: một .docx chỉ là file ZIP chứa vài XML theo chuẩn OOXML.
 *
 * <p>Repo build offline nên không thêm được Apache POI; mà bản đề tải về chỉ cần tiêu đề, đoạn
 * văn, gạch đầu dòng và ảnh minh họa — chừng đó tự dựng được, không đáng để phá vỡ luật build.
 *
 * <p>KHÔNG dùng {@code styles.xml}: mọi định dạng đặt thẳng trên từng run (đậm/cỡ chữ), nên
 * Word/WPS/Google Docs mở lên đều thấy đúng mà không cần phần style đi kèm.
 *
 * <p>Ảnh phải là PNG. SVG do máy chủ vẽ được trình duyệt đổi sang PNG rồi gửi lên — Word không
 * hiển thị SVG ổn định nếu thiếu ảnh nền thay thế, còn máy chủ thì không có thư viện rasterize.
 */
public final class DocxWriter {

    /** 1 pixel (96 DPI) = 9525 EMU — đơn vị đo của OOXML. */
    private static final int EMU_PER_PX = 9525;
    /** Bề rộng vùng in của khổ A4 lề 2cm, tính theo pixel: ảnh rộng hơn sẽ bị thu nhỏ vừa trang. */
    private static final int PAGE_WIDTH_PX = 640;

    private final List<String> body = new ArrayList<>();
    private final List<byte[]> images = new ArrayList<>();

    public DocxWriter heading(String text, int level) {
        int size = switch (level) { case 1 -> 32; case 2 -> 26; default -> 24; };   // nửa-point
        body.add("<w:p><w:pPr><w:spacing w:before=\"" + (level == 1 ? 0 : 240) + "\" w:after=\"80\"/></w:pPr>"
                + run(text, true, size, null) + "</w:p>");
        return this;
    }

    public DocxWriter paragraph(String text) {
        body.add("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr>" + run(text, false, 22, null) + "</w:p>");
        return this;
    }

    /** Gạch đầu dòng "thủ công": không cần numbering.xml, mà Word nào cũng hiện đúng. */
    public DocxWriter bullet(String text, boolean ordered, int index) {
        String marker = ordered ? index + ". " : "• ";
        body.add("<w:p><w:pPr><w:ind w:left=\"420\" w:hanging=\"220\"/><w:spacing w:after=\"60\"/></w:pPr>"
                + run(marker + text, false, 22, null) + "</w:p>");
        return this;
    }

    public DocxWriter code(String text) {
        for (String line : text.split("\n", -1))
            body.add("<w:p><w:pPr><w:spacing w:after=\"0\"/><w:ind w:left=\"280\"/></w:pPr>"
                    + run(line.isEmpty() ? " " : line, false, 18, "Consolas") + "</w:p>");
        return this;
    }

    /** Thêm ảnh PNG. Ảnh rộng quá khổ giấy được thu nhỏ theo tỉ lệ. */
    public DocxWriter image(byte[] png, int widthPx, int heightPx) {
        if (png == null || png.length == 0 || widthPx <= 0 || heightPx <= 0) return this;
        images.add(png);
        int index = images.size();
        double scale = widthPx > PAGE_WIDTH_PX ? (double) PAGE_WIDTH_PX / widthPx : 1.0;
        long cx = Math.round(widthPx * scale) * (long) EMU_PER_PX;
        long cy = Math.round(heightPx * scale) * (long) EMU_PER_PX;
        body.add("<w:p><w:pPr><w:spacing w:after=\"160\"/></w:pPr><w:r><w:drawing>"
                + "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<wp:docPr id=\"" + index + "\" name=\"Hinh" + index + "\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"" + index + "\" name=\"image" + index + ".png\"/>"
                + "<pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"rId" + (index + 10) + "\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>"
                + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
        return this;
    }

    public byte[] build() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(sink)) {
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/"
                    + "2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            put(zip, "word/_rels/document.xml.rels", documentRels());
            put(zip, "word/document.xml", document());
            for (int i = 0; i < images.size(); i++) {
                zip.putNextEntry(new ZipEntry("word/media/image" + (i + 1) + ".png"));
                zip.write(images.get(i));
                zip.closeEntry();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Không dựng được file .docx: " + e.getMessage(), e);
        }
        return sink.toByteArray();
    }

    // ── Ruột file ────────────────────────────────────────────────

    private String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package."
                + "relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd."
                + "openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>";
    }

    private String documentRels() {
        StringBuilder rels = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < images.size(); i++) {
            rels.append("<Relationship Id=\"rId").append(i + 11)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\""
                        + " Target=\"media/image").append(i + 1).append(".png\"/>");
        }
        return rels.append("</Relationships>").toString();
    }

    private String document() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document "
                + "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
                + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
                + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
                + String.join("", body)
                + "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr>"
                + "</w:body></w:document>";
    }

    private String run(String text, boolean bold, int halfPoints, String font) {
        StringBuilder run = new StringBuilder("<w:r><w:rPr>");
        if (bold) run.append("<w:b/>");
        if (font != null) run.append("<w:rFonts w:ascii=\"").append(font)
                .append("\" w:hAnsi=\"").append(font).append("\"/>");
        run.append("<w:sz w:val=\"").append(halfPoints).append("\"/>")
           .append("<w:szCs w:val=\"").append(halfPoints).append("\"/></w:rPr>")
           // xml:space giữ nguyên khoảng trắng đầu/cuối (thụt lề trong code, marker gạch đầu dòng).
           .append("<w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r>");
        return run.toString();
    }

    private void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
