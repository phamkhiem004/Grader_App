package com.example.grader.controller;

import com.example.grader.entity.ExamResult;
import com.example.grader.entity.GradingStatus;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudentReportArchiveBuilderTest {

    private static final String RESULT_JSON = """
            {"schema_version":"2",
             "student":{"id":"HE180037"},
             "grading_result":{"score":6.5,"passed_tests":2,"failed_tests":1,"total_tests":4,
                               "engine_version":"v9"},
             "test_cases":[
               {"test_id":"TC_01","name":"App khởi động","status":"passed","score":2,"max_score":2},
               {"test_id":"TC_02","name":"Thêm sinh viên","status":"failed","score":0,"max_score":2,
                "error_code":"WIDGET_NOT_FOUND","actual":"không thấy nút nào"},
               {"test_id":"TC_03","name":"Xoá sinh viên","status":"not_run","score":0,"max_score":2,
                "actual":"chưa chạy vì bộ test không khởi động được"},
               {"test_id":"TC_04","name":"Sửa sinh viên","status":"passed","score":2,"max_score":2}
             ]}
            """;

    @Test
    void buildsOneFolderPerStudentWithAllFourArtifacts() throws Exception {
        ExamResult row = new ExamResult();
        row.setStudentId("HE180037");
        row.setExamId("PE_PRM393_FA26");
        row.setBatchId("BATCH_9");
        row.setStatus(GradingStatus.DONE);
        row.setScore(6.5f);
        row.setResultJson(RESULT_JSON);
        row.setSubmissionHash("a".repeat(64));

        byte[] archive = new StudentReportArchiveBuilder(s -> s).build("PE_PRM393_FA26", List.of(row));

        Map<String, String> entries = readAll(archive);
        assertTrue(entries.containsKey("Result_of_PE_PRM393_FA26/"));
        assertTrue(entries.containsKey("Result_of_PE_PRM393_FA26/HE180037/"));
        String home = "Result_of_PE_PRM393_FA26/HE180037/";
        assertNotNull(entries.get(home + "HE180037.json"), "thiếu file JSON cá nhân");
        assertNotNull(entries.get(home + "HE180037.xls"), "thiếu file Excel cá nhân");
        assertNotNull(entries.get(home + "feedback.txt"), "thiếu feedback.txt");
        assertNotNull(entries.get(home + "logs/grading.log"), "thiếu logs/grading.log");

        // Excel: đủ 3 trạng thái với 3 màu nền khác nhau, số dạng "x/y" phải ép kiểu chữ.
        String xls = entries.get(home + "HE180037.xls");
        assertTrue(xls.contains("background:#DCFCE7"), "passed phải nền xanh");
        assertTrue(xls.contains("background:#FEE2E2"), "failed phải nền đỏ");
        assertTrue(xls.contains("background:#F1F5F9"), "not_run phải nền xám");
        assertTrue(xls.contains(">Not run<") && xls.contains(">Failed<") && xls.contains(">Passed<"));
        assertTrue(xls.contains("2/4"), "TC RATE tóm tắt");
        assertTrue(xls.contains("mso-number-format"), "chuỗi x/y phải ép dạng chữ");
        assertFalse(xls.contains("font-size:12px"), "cỡ chữ phải khai bằng pt, không phải px");

        // Chưa sinh nhận xét → feedback.txt phải TRỐNG (hồ sơ phát cho SV, không nhét câu
        // giải thích cơ chế nội bộ vào file của họ).
        String feedback = entries.get(home + "feedback.txt");
        assertEquals("", feedback, "chưa sinh feedback thì file phải bỏ trống");

        // Log: đủ định danh + liệt kê testcase không đạt kèm mã lỗi.
        String log = entries.get(home + "logs/grading.log");
        assertTrue(log.contains("HE180037") && log.contains("BATCH_9"));
        assertTrue(log.contains("[FAILED] TC_02 (WIDGET_NOT_FOUND)"), log);
        assertTrue(log.contains("[NOT_RUN] TC_03"), log);
        assertFalse(log.contains("TC_01"), "testcase passed không cần vào log lỗi");

        // Đối chứng: hash bài nộp lấy từ DB, hash kết quả phải băm ĐÚNG file .json cùng thư mục.
        assertTrue(log.contains("a".repeat(64)), "thiếu SHA-256 bài nộp");
        String expectedJsonHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(entries.get(home + "HE180037.json").getBytes(StandardCharsets.UTF_8)));
        assertTrue(log.contains(expectedJsonHash), "hash file kết quả không khớp nội dung thật");
    }

    @Test
    void saysSoWhenSubmissionHashIsMissing() throws Exception {
        // Dữ liệu chấm TRƯỚC khi có cột hash: phải nói rõ "không ghi được", không được bịa chuỗi.
        ExamResult legacy = new ExamResult();
        legacy.setStudentId("HE000009");
        legacy.setExamId("PE_OLD");
        legacy.setStatus(GradingStatus.DONE);
        legacy.setScore(5f);
        legacy.setResultJson(RESULT_JSON);

        Map<String, String> entries = readAll(
                new StudentReportArchiveBuilder(s -> s).build("PE_OLD", List.of(legacy)));
        assertTrue(entries.get("Result_of_PE_OLD/HE000009/logs/grading.log").contains("(không ghi được)"));
    }

    @Test
    void rendersCachedBotFeedbackWhenAvailable() throws Exception {
        ExamResult row = new ExamResult();
        row.setStudentId("HE000001");
        row.setExamId("PE_X");
        row.setStatus(GradingStatus.DONE);
        row.setScore(7f);
        row.setResultJson(RESULT_JSON);
        row.setFeedbackJson("""
                {"studentId":"HE000001","scoreSummary":"7.0/10",
                 "feedbackText":"Bài làm tốt phần CRUD, cần xem lại validate.",
                 "teacherReviewRequired":true,"reviewReasons":["Điểm lệch giữa 2 lần chấm"]}
                """);

        Map<String, String> entries = readAll(
                new StudentReportArchiveBuilder(s -> s).build("PE_X", List.of(row)));
        String feedback = entries.get("Result_of_PE_X/HE000001/feedback.txt");
        assertTrue(feedback.contains("7.0/10"));
        assertTrue(feedback.contains("cần xem lại validate"));
        assertTrue(feedback.contains("giảng viên xem lại"));
        assertTrue(feedback.contains("Điểm lệch giữa 2 lần chấm"));
    }

    private static Map<String, String> readAll(byte[] archive) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                out.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(false, out.isEmpty());
        return out;
    }
}
