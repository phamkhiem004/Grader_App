package com.example.grader.controller;

import com.example.grader.entity.ExamResult;
import com.example.grader.entity.GradingOutcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * HỒ SƠ KẾT QUẢ PHÁT CHO SINH VIÊN — mỗi em một thư mục, giải nén ra:
 *
 * <pre>
 * Result_of_&lt;đề&gt;/
 * └── HE180037/
 *     ├── HE180037.json      kết quả đầy đủ (đúng bản "Xuất JSON")
 *     ├── HE180037.xls       bảng tối giản: từng testcase passed/failed/not_run
 *     ├── feedback.txt       nhận xét của feedback bot; chưa chạy bot thì là placeholder
 *     └── logs/grading.log   bằng chứng chấm: chẩn đoán, lỗi runner, danh sách testcase hỏng
 * </pre>
 *
 * <p><b>Vì sao logs/ chỉ chứa bằng chứng ĐÃ LƯU, không chứa log thô của flutter test:</b> pipeline
 * hiện chỉ giữ lại JSON đã lắp ráp + chẩn đoán (log thô bị bỏ sau khi bóc). Muốn log thô phải sửa
 * đường chấm để chép từng file ra đĩa — đắt và phình dung lượng, trong khi zip bài nộp gốc đã được
 * giữ ở submissions/ cho tranh chấp sâu. File grading.log vì thế là bản TÓM TẮT truy vết đủ để
 * trả lời khiếu nại thường gặp, không phải dump.
 */
final class StudentReportArchiveBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss dd/M/yyyy").withZone(ZoneId.systemDefault());
    /** Ô Excel: viền + cỡ chữ pt (Excel đọc px sai — xem exportExcel bên frontend). */
    private static final String CELL = "border:1px solid #CBD5E1;font-size:12.0pt;";
    /** Ép ô dạng CHỮ — thiếu nó thì "12/30" bị Excel đổi thành ngày 30/12. */
    private static final String AS_TEXT = "mso-number-format:'\\@';";

    /** Chuẩn hoá JSON trước khi ghi (controller đưa {@code pretty} của nó vào để dùng chung). */
    private final UnaryOperator<String> jsonNormalizer;

    StudentReportArchiveBuilder(UnaryOperator<String> jsonNormalizer) {
        this.jsonNormalizer = jsonNormalizer;
    }

    byte[] build(String examId, List<ExamResult> rows) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String root = "Result_of_" + safe(examId) + "/";
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            dir(zip, root);
            for (ExamResult row : rows) {
                String json = row.getResultJson();
                if (json == null || json.isBlank()) continue;
                JsonNode result;
                try {
                    result = MAPPER.readTree(json);
                } catch (Exception broken) {
                    continue;   // JSON hỏng: bỏ qua em này thay vì hỏng cả gói
                }
                String sid = safe(row.getStudentId() == null ? "student" : row.getStudentId());
                String home = root + sid + "/";
                String studentJson = jsonNormalizer.apply(json);
                dir(zip, home);
                file(zip, home + sid + ".json", studentJson);
                file(zip, home + sid + ".xls", studentXls(row, result));
                file(zip, home + "feedback.txt", feedbackText(row));
                dir(zip, home + "logs/");
                file(zip, home + "logs/grading.log", gradingLog(examId, row, result, sha256(studentJson)));
            }
        }
        return bytes.toByteArray();
    }

    // ── Excel tối giản cho sinh viên ────────────────────────────────
    private String studentXls(ExamResult row, JsonNode result) {
        int[] manual = manualPassCounts(row.getManualJson());
        JsonNode grading = result.path("grading_result");
        int passed = grading.path("passed_tests").asInt(0);
        int total = grading.path("total_tests").asInt(0);
        boolean edited = row.getManualScore() != null;

        StringBuilder sb = new StringBuilder("﻿<html><head><meta charset=\"utf-8\"></head><body>");

        // Khối tóm tắt — cùng thông tin với nút "Xuất Excel" của trang Lịch sử.
        sb.append("<table style=\"border-collapse:collapse\">");
        summaryRow(sb, "Mã SV", row.getStudentId(), true);
        summaryRow(sb, "Bộ testcase", row.getExamId(), true);
        summaryRow(sb, "Trạng thái", edited ? "Edited" : "Đã xong", false);
        summaryRow(sb, "Điểm", edited
                ? fmt(row.getManualScore()) + "/" + fmt(row.getScore())
                : fmt(row.getScore()), true);
        summaryRow(sb, "TC RATE", manual != null && edited
                ? manual[0] + "/" + manual[1]
                : passed + "/" + total, true);
        summaryRow(sb, "Thời gian chấm", row.getUpdatedAt() == null ? "" : TIME.format(row.getUpdatedAt()), true);
        sb.append("</table><br/>");

        // Bảng testcase: mỗi dòng một test, màu theo trạng thái để sinh viên quét mắt là hiểu.
        sb.append("<table style=\"border-collapse:collapse\"><thead><tr>");
        for (String h : new String[]{"STT", "Test case", "Trạng thái", "Điểm", "Kết quả quan sát"}) {
            sb.append("<th style=\"").append(CELL).append("background:#EEF2FF\">").append(esc(h)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        int idx = 0;
        for (JsonNode tc : result.path("test_cases")) {
            idx++;
            String status = tc.path("status").asText("");
            String tone = switch (status) {
                case "passed" -> "background:#DCFCE7;";
                case "failed" -> "background:#FEE2E2;";
                default -> "background:#F1F5F9;";
            };
            String label = switch (status) {
                case "passed" -> "Passed";
                case "failed" -> "Failed";
                default -> "Not run";
            };
            String name = tc.path("name").asText(tc.path("test_id").asText(""));
            String score = trimNumber(tc.path("score").asText("0")) + "/" + trimNumber(tc.path("max_score").asText("0"));
            String actual = "passed".equals(status) ? "—" : tc.path("actual").asText("");
            sb.append("<tr>")
              .append(cell(String.valueOf(idx), tone, false))
              .append(cell(name, tone, true))
              .append(cell(label, tone, true))
              .append(cell(score, tone, true))
              .append(cell(actual, tone, true))
              .append("</tr>");
        }
        sb.append("</tbody></table></body></html>");
        return sb.toString();
    }

    private void summaryRow(StringBuilder sb, String key, String value, boolean asText) {
        sb.append("<tr><td style=\"").append(CELL).append("background:#F8FAFC;font-weight:bold\">")
          .append(esc(key)).append("</td>")
          .append(cell(value == null ? "" : value, "", asText)).append("</tr>");
    }

    private String cell(String value, String tone, boolean asText) {
        return "<td style=\"" + CELL + tone + (asText ? AS_TEXT : "") + "\">" + esc(value) + "</td>";
    }

    // ── feedback.txt ────────────────────────────────────────────────
    private String feedbackText(ExamResult row) {
        String cached = row.getFeedbackJson();
        if (cached == null || cached.isBlank()) {
            return "Chưa có nhận xét cho bài này.\n\n"
                    + "File này sẽ được điền tự động khi chạy chức năng \"Nhận xét AI\" (feedback bot)\n"
                    + "cho bộ testcase " + row.getExamId() + ".\n";
        }
        try {
            JsonNode fb = MAPPER.readTree(cached);
            StringBuilder sb = new StringBuilder();
            sb.append("NHẬN XÉT BÀI LÀM — ").append(row.getStudentId()).append("\n");
            String summary = fb.path("scoreSummary").asText("");
            if (!summary.isBlank()) sb.append("Điểm: ").append(summary).append("\n");
            sb.append("\n").append(fb.path("feedbackText").asText("")).append("\n");
            if (fb.path("teacherReviewRequired").asBoolean(false)) {
                sb.append("\n[Bot khuyến nghị giảng viên xem lại bài này]\n");
                for (JsonNode reason : fb.path("reviewReasons")) {
                    sb.append("  - ").append(reason.asText()).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception broken) {
            return cached;   // JSON lạ thì trả nguyên văn còn hơn nuốt mất
        }
    }

    // ── logs/grading.log ────────────────────────────────────────────
    private String gradingLog(String examId, ExamResult row, JsonNode result, String resultJsonHash) {
        JsonNode grading = result.path("grading_result");
        StringBuilder sb = new StringBuilder();
        sb.append("=== GRADING LOG ===\n");
        sb.append("Đề: ").append(examId)
          .append(" | SV: ").append(row.getStudentId())
          .append(" | Batch: ").append(nvl(row.getBatchId())).append("\n");
        sb.append("Thời gian chấm: ")
          .append(row.getUpdatedAt() == null ? "?" : TIME.format(row.getUpdatedAt())).append("\n");
        sb.append("Trạng thái: ").append(row.getStatus())
          .append(" (").append(GradingOutcome.of(row.getStatus())).append(")")
          .append(" | Điểm tự động: ").append(fmt(row.getScore()));
        if (row.getManualScore() != null) sb.append(" | Điểm chấm tay: ").append(fmt(row.getManualScore()));
        if (row.getPreviousScore() != null) sb.append(" | Điểm lần chấm trước: ").append(fmt(row.getPreviousScore()));
        sb.append("\n");
        sb.append("Engine: ").append(grading.path("engine_version").asText("?"))
          .append(" | Schema: ").append(result.path("schema_version").asText("1")).append("\n");

        // ĐỐI CHỨNG — hai chuỗi này là thứ duy nhất trong hồ sơ chứng minh được "bài nào đã được
        // chấm" và "file kết quả có bị sửa sau khi phát hay không".
        sb.append("\n--- Đối chứng ---\n");
        sb.append("SHA-256 bài nộp (.zip lúc chấm): ")
          .append(row.getSubmissionHash() == null ? "(không ghi được)" : row.getSubmissionHash()).append("\n");
        sb.append("SHA-256 file kết quả kèm theo:   ").append(resultJsonHash).append("\n");
        sb.append("Đối chiếu: băm lại file .zip bài nộp lưu ở kho gốc, khớp chuỗi trên nghĩa là\n")
          .append("đúng bản đã được chấm. Băm lại file .json cùng thư mục để kiểm tra toàn vẹn.\n");

        if (row.getDiagnosticCode() != null && !row.getDiagnosticCode().isBlank()) {
            sb.append("Chẩn đoán: [").append(row.getDiagnosticCode())
              .append("][").append(nvl(row.getDiagnosticOrigin()))
              .append("][").append(nvl(row.getDiagnosticStage())).append("]\n");
        }
        if (row.getErrorLog() != null && !row.getErrorLog().isBlank()) {
            sb.append("Error log: ").append(row.getErrorLog()).append("\n");
        }
        String runnerError = grading.path("runner_error").asText("");
        if (!runnerError.isBlank()) sb.append("Runner error: ").append(runnerError).append("\n");

        sb.append("\n--- Testcase không đạt ---\n");
        boolean any = false;
        for (JsonNode tc : result.path("test_cases")) {
            String status = tc.path("status").asText("");
            if ("passed".equals(status)) continue;
            any = true;
            sb.append("[").append(status.toUpperCase()).append("] ")
              .append(tc.path("test_id").asText("?"));
            String code = tc.path("error_code").asText("");
            if (!code.isBlank()) sb.append(" (").append(code).append(")");
            String actual = tc.path("actual").asText("");
            if (!actual.isBlank()) sb.append(": ").append(actual);
            sb.append("\n");
        }
        if (!any) sb.append("(không có — tất cả testcase đều đạt)\n");
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────
    /** Đếm tiêu chí đạt TRỌN điểm trong manual_json → {đạt, tổng}; null nếu chưa chấm tay. */
    static int[] manualPassCounts(String manualJson) {
        if (manualJson == null || manualJson.isBlank()) return null;
        try {
            JsonNode criteria = MAPPER.readTree(manualJson).path("criteria");
            if (!criteria.isArray() || criteria.isEmpty()) return null;
            int pass = 0;
            for (JsonNode c : criteria) {
                double max = c.path("maxPoints").asDouble(0);
                boolean ok = max > 0
                        ? c.path("points").asDouble(0) >= max - 1e-6
                        : c.path("passed").asBoolean(false);
                if (ok) pass++;
            }
            return new int[]{pass, criteria.size()};
        } catch (Exception e) {
            return null;
        }
    }

    private void dir(ZipOutputStream zip, String name) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.closeEntry();
    }

    private void file(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String safe(String value) {
        String s = value == null ? "x" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.isBlank() ? "x" : s;
    }

    private static String esc(String v) {
        return String.valueOf(v).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String fmt(Float v) {
        return v == null ? "—" : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String nvl(String v) {
        return v == null ? "?" : v;
    }

    /** SHA-256 dạng hex của nội dung file kết quả — băm ĐÚNG chuỗi được ghi vào zip. */
    private static String sha256(String content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "(không tính được)";
        }
    }

    /** "2.0" → "2", "0.5" giữ nguyên — điểm testcase trong JSON hay mang .0 thừa. */
    private static String trimNumber(String v) {
        return v.endsWith(".0") ? v.substring(0, v.length() - 2) : v;
    }
}
