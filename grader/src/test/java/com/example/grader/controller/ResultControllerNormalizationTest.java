package com.example.grader.controller;

import com.example.grader.entity.ExamResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultControllerNormalizationTest {

    @Test
    void exportsOneJsonFilePerStudentInsideResultFolder() throws Exception {
        ResultController controller = new ResultController();
        ExamResult row = new ExamResult();
        row.setStudentId("HE186137");
        row.setStudentName("khiempghe186137");
        row.setResultJson("{\"student\":{\"id\":\"HE186137\"},\"optional\":null}");

        byte[] archive = controller.buildBatchResultsArchive("BATCH_01", List.of(row));
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry folder = zip.getNextEntry();
            assertEquals("BATCH_01_results/", folder.getName());
            assertTrue(folder.isDirectory());

            ZipEntry result = zip.getNextEntry();
            assertEquals("BATCH_01_results/khiempghe186137.json", result.getName());
            String json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("HE186137"));
            assertFalse(json.contains(": null"));
            assertEquals(null, zip.getNextEntry());
        }
    }

    @Test
    void cleansLegacyDownloadedResult() throws Exception {
        ResultController controller = new ResultController();
        Method normalize = ResultController.class.getDeclaredMethod("normalizeJsonString", String.class);
        normalize.setAccessible(true);

        String legacy = """
                {
                  "test_cases": [{
                    "test_id": "SCREEN_VALIDATE_EACH_FIELD",
                    "status": "failed",
                    "expected": "Form phải hiển thị lỗi",
                    "expect": "Form phải hiển thị lỗi",
                    "actual": "Ném lỗi: The test description was: SCREEN_VALIDATE_EACH_FIELD",
                    "error": {"code": "EXCEPTION_THROWN", "message": "Code ném lỗi khi chạy"},
                    "student_safe_summary": "Code ném lỗi khi chạy"
                  }]
                }
                """;

        String normalized = (String) normalize.invoke(controller, legacy);

        assertFalse(normalized.contains("The test description was"));
        assertFalse(normalized.contains("\"expect\""));
        assertTrue(normalized.contains("Không có giá trị actual"));

        // P2b — đường ĐỌC không được bơm lại hai trường đã gỡ, và phải lọc chúng khỏi cả dữ
        // liệu ĐÃ LƯU. Trước đây chỗ này tự sinh `student_safe_summary` bằng câu tra bảng theo
        // mã lỗi; gỡ ở nơi sinh mà bỏ chỗ này thì vô hiệu (bẫy số 1 trong sổ thi công).
        assertFalse(normalized.contains("student_safe_summary"), normalized);
        assertFalse(normalized.contains("\"error\""), normalized);
        // Nhưng `error.code` phải được rút sang `error_code` TRƯỚC khi xoá, không được mất.
        assertTrue(normalized.contains("EXCEPTION_THROWN"), normalized);
    }

    /**
     * Đường ĐỌC chỉ được thêm khoá suy ra chắc chắn. Dữ liệu chấm trước P4 ghi mọi test chưa
     * chạy thành `failed`, nên bơm `executed = true` vào là NÓI SAI; và sự vắng mặt của
     * `executed`/`schema_version` chính là dấu hiệu "dữ liệu bản 1" mà bên đọc dựa vào.
     */
    @Test
    void addsOnlyKeysItCanDeriveAndNeverInventsExecuted() throws Exception {
        ResultController controller = new ResultController();
        Method normalize = ResultController.class.getDeclaredMethod("normalizeJsonString", String.class);
        normalize.setAccessible(true);

        String stored = """
                {
                  "test_cases": [
                    {"test_id": "A", "status": "failed", "actual": "x",
                     "error": {"code": "WIDGET_NOT_FOUND", "message": "y"}},
                    {"test_id": "B", "status": "passed", "actual": "Đã đáp ứng yêu cầu"}
                  ]
                }
                """;

        String out = (String) normalize.invoke(controller, stored);

        assertTrue(out.contains("\"error_code\" : \"WIDGET_NOT_FOUND\"")
                || out.contains("\"error_code\":\"WIDGET_NOT_FOUND\""), out);
        assertFalse(out.contains("\"blocked_by\""), "field tùy chọn null không nên xuất hiện: " + out);
        assertFalse(out.contains(": null"), "JSON tải xuống không nên chứa field null: " + out);
        assertFalse(out.contains("\"executed\""), "không được tự sinh executed cho dữ liệu cũ");
        assertFalse(out.contains("\"schema_version\""), "không được tự gắn schema_version cho dữ liệu cũ");
    }
}
