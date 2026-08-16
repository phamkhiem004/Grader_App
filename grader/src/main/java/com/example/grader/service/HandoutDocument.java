package com.example.grader.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Bộ đề phát cho sinh viên dưới dạng MỘT tài liệu: chữ đề bài + hình minh họa nằm chung.
 *
 * <p>Đề bài lưu bằng Markdown (dễ sửa tay), hình lưu bằng SVG. Lớp này ghép hai thứ đó thành:
 * <ul>
 *   <li>một file HTML tự chứa (SVG nhúng thẳng vào, mở là xem được, không cần file rời);</li>
 *   <li>một danh sách khối ({@link Block}) để {@link DocxWriter} dựng bản .docx tải về.</li>
 * </ul>
 *
 * <p>Markdown ở đây cố tình chỉ hiểu vài cú pháp hay dùng trong đề thi (tiêu đề, gạch đầu dòng,
 * danh sách đánh số, in đậm, mã inline). Đề bài không phải trang web — thêm cú pháp lạ chỉ làm
 * bản .docx và bản HTML lệch nhau.
 */
public final class HandoutDocument {

    /** Một khối nội dung. type: h1 | h2 | h3 | p | li | ol | code | image. */
    public record Block(String type, String text) {}

    /** Một hình minh họa: id (tên file), tiêu đề, nội dung SVG. */
    public record Mockup(String id, String title, String svg) {}

    private HandoutDocument() {}

    // ── Markdown → khối ──────────────────────────────────────────

    public static List<Block> parse(String markdown) {
        List<Block> blocks = new ArrayList<>();
        if (markdown == null) return blocks;
        boolean inCode = false;
        StringBuilder code = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();

        for (String raw : markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw.stripTrailing();
            if (line.stripLeading().startsWith("```")) {
                if (inCode) { blocks.add(new Block("code", code.toString())); code.setLength(0); }
                else flushParagraph(blocks, paragraph);
                inCode = !inCode;
                continue;
            }
            if (inCode) { code.append(line).append('\n'); continue; }

            String trimmed = line.strip();
            if (trimmed.isEmpty()) { flushParagraph(blocks, paragraph); continue; }
            if (trimmed.startsWith("#")) {
                flushParagraph(blocks, paragraph);
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                blocks.add(new Block("h" + Math.min(level, 3), trimmed.substring(level).strip()));
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                flushParagraph(blocks, paragraph);
                blocks.add(new Block("li", trimmed.substring(2).strip()));
                continue;
            }
            if (trimmed.matches("^\\d+[.)]\\s+.*")) {
                flushParagraph(blocks, paragraph);
                blocks.add(new Block("ol", trimmed.replaceFirst("^\\d+[.)]\\s+", "")));
                continue;
            }
            if (paragraph.length() > 0) paragraph.append(' ');
            paragraph.append(trimmed);
        }
        if (inCode && code.length() > 0) blocks.add(new Block("code", code.toString()));
        flushParagraph(blocks, paragraph);
        return blocks;
    }

    private static void flushParagraph(List<Block> blocks, StringBuilder paragraph) {
        if (paragraph.length() == 0) return;
        blocks.add(new Block("p", paragraph.toString()));
        paragraph.setLength(0);
    }

    // ── Khối → HTML một file ─────────────────────────────────────

    /**
     * @param examId mã bộ testcase, hiện ở đầu tài liệu
     * @param markdown đề bài
     * @param mockups hình minh họa, nhúng thẳng SVG nên file HTML này tự chứa
     */
    public static String toHtml(String examId, String examName, String markdown, List<Mockup> mockups) {
        StringBuilder html = new StringBuilder();
        String title = (examName == null || examName.isBlank() ? examId : examName);
        html.append("<!DOCTYPE html>\n<html lang=\"vi\">\n<head>\n<meta charset=\"utf-8\">\n")
            .append("<title>").append(esc(examId)).append(" — Đề bài</title>\n")
            .append("<style>\n")
            .append("body{max-width:860px;margin:32px auto;padding:0 20px;"
                    + "font-family:'Segoe UI',Roboto,Arial,sans-serif;color:#0f172a;line-height:1.65}\n")
            .append("h1{font-size:24px;margin:0 0 4px}h2{font-size:19px;margin:28px 0 8px}\n")
            .append("h3{font-size:16px;margin:22px 0 6px}p{margin:8px 0}li{margin:4px 0}\n")
            .append("code{background:#f1f5f9;padding:1px 5px;border-radius:4px;font-size:.92em}\n")
            .append("pre{background:#0f172a;color:#e2e8f0;padding:14px;border-radius:10px;overflow:auto}\n")
            .append(".exam-id{font:600 12px/1 ui-monospace,Consolas,monospace;color:#6366f1;"
                    + "letter-spacing:.08em;text-transform:uppercase}\n")
            .append(".mockup{margin:20px 0;padding:14px;border:1px solid #e2e8f0;border-radius:12px}\n")
            .append(".mockup h3{margin:0 0 10px}.mockup svg{max-width:100%;height:auto}\n")
            .append("@media print{body{margin:0}.mockup{break-inside:avoid}}\n")
            .append("</style>\n</head>\n<body>\n")
            .append("<p class=\"exam-id\">").append(esc(examId)).append("</p>\n")
            .append("<h1>").append(esc(title)).append("</h1>\n");

        String listOpen = null;
        for (Block block : parse(markdown)) {
            String wanted = "li".equals(block.type()) ? "ul" : "ol".equals(block.type()) ? "ol" : null;
            if (!java.util.Objects.equals(listOpen, wanted)) {
                if (listOpen != null) html.append("</").append(listOpen).append(">\n");
                if (wanted != null) html.append('<').append(wanted).append(">\n");
                listOpen = wanted;
            }
            switch (block.type()) {
                case "h1" -> html.append("<h2>").append(inline(block.text())).append("</h2>\n");
                case "h2" -> html.append("<h2>").append(inline(block.text())).append("</h2>\n");
                case "h3" -> html.append("<h3>").append(inline(block.text())).append("</h3>\n");
                case "li", "ol" -> html.append("<li>").append(inline(block.text())).append("</li>\n");
                case "code" -> html.append("<pre><code>").append(esc(block.text())).append("</code></pre>\n");
                default -> html.append("<p>").append(inline(block.text())).append("</p>\n");
            }
        }
        if (listOpen != null) html.append("</").append(listOpen).append(">\n");

        if (mockups != null && !mockups.isEmpty()) {
            html.append("<h2>Hình minh họa giao diện</h2>\n");
            for (Mockup m : mockups) {
                if (m == null || m.svg() == null || m.svg().isBlank()) continue;
                html.append("<div class=\"mockup\"><h3>")
                    .append(esc(m.title() == null || m.title().isBlank() ? m.id() : m.title()))
                    .append("</h3>\n")
                    // SVG do MockupRenderer sinh (không phải chữ người dùng dán vào) nên nhúng thẳng.
                    .append(stripXmlDeclaration(m.svg()))
                    .append("\n</div>\n");
            }
        }
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    /** In đậm **x**, in nghiêng *x* và mã `x` — đủ cho đề thi, phần còn lại giữ nguyên chữ. */
    private static String inline(String text) {
        String s = esc(text);
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
        return s;
    }

    private static String stripXmlDeclaration(String svg) {
        return svg.replaceFirst("^\\s*<\\?xml[^>]*\\?>\\s*", "");
    }

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
