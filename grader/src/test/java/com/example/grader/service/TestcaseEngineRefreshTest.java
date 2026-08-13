package com.example.grader.service;

import com.example.grader.entity.Exam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Engine dùng chung bị chép ĐÓNG BĂNG vào thư mục testcase của đề lúc publish, mà chấm lại thì
 * ưu tiên đúng thư mục đó ⇒ không nâng thì mọi bản sửa engine vô hiệu với đề đã publish.
 *
 * <p>Test này khoá hai điều: đề {@code COMMON_V1} PHẢI được nâng, và đề legacy TUYỆT ĐỐI không
 * bị ghi đè — grader của đề legacy là do giáo viên nộp, ghi đè là phá đề của họ.
 */
class TestcaseEngineRefreshTest {

    private static final String COMMON_CONFIG = "{\"engine_type\":\"COMMON_V1\"}";

    private static Exam exam(Path dir, String configJson) {
        Exam exam = new Exam();
        exam.setExamId("PE_TEST");
        exam.setTestcasePath(dir.toAbsolutePath().toString());
        exam.setTestcaseConfigJson(configJson);
        return exam;
    }

    @Test
    void upgradesCommonEngineInPlace(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("exam_test.dart"), "// engine cũ", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("grader.dart"), "// engine cũ", StandardCharsets.UTF_8);

        assertTrue(new TestcaseTemplateService().refreshCommonEngine(exam(dir, COMMON_CONFIG)));

        String grader = Files.readString(dir.resolve("grader.dart"), StandardCharsets.UTF_8);
        assertTrue(grader.contains("kEngineVersion"),
                "grader.dart chưa được thay bằng bản trên classpath");
        assertTrue(Files.readString(dir.resolve("exam_test.dart"), StandardCharsets.UTF_8)
                .contains("_runCase"), "exam_test.dart chưa được thay");
    }

    @Test
    void refreshKeepsEnabledCustomTestcases(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("exam_test.dart"), "// engine cũ", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("grader.dart"), "// engine cũ", StandardCharsets.UTF_8);
        String config = """
                {
                  "engine_type": "COMMON_V1",
                  "items": [{
                    "instance_id": "PE_TEST_custom_01",
                    "runner": "CUSTOM_CODE",
                    "enabled": true,
                    "name": "Kiểm tra code tay",
                    "custom_code": "expect(1, 1);"
                  }]
                }
                """;

        assertTrue(new TestcaseTemplateService().refreshCommonEngine(exam(dir, config)));

        String generated = Files.readString(dir.resolve("exam_test.dart"), StandardCharsets.UTF_8);
        assertTrue(generated.contains("case 'PE_TEST_custom_01':"));
        assertTrue(generated.contains("_stage('TESTCASE_CUSTOM_CODE');"));
        assertTrue(generated.contains("expect(1, 1);"));
    }

    @Test
    void leavesLegacyExamAlone(@TempDir Path dir) throws Exception {
        // Đề legacy: giáo viên upload ZIP nên không có testcase-config, engine là của họ.
        Files.writeString(dir.resolve("grader.dart"), "// grader của giáo viên", StandardCharsets.UTF_8);

        assertFalse(new TestcaseTemplateService().refreshCommonEngine(exam(dir, null)));
        assertFalse(new TestcaseTemplateService().refreshCommonEngine(exam(dir, "{\"engine_type\":\"LEGACY\"}")));

        assertEquals("// grader của giáo viên",
                Files.readString(dir.resolve("grader.dart"), StandardCharsets.UTF_8));
    }

    @Test
    void skipsWhenTestcaseDirectoryIsGone(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("da-bi-xoa");
        assertFalse(new TestcaseTemplateService().refreshCommonEngine(exam(missing, COMMON_CONFIG)));
        assertFalse(Files.exists(missing), "Không được tự tạo lại thư mục testcase đã bị xoá");
    }

    @Test
    void skipsExamWithoutTestcasePath() throws Exception {
        Exam exam = new Exam();
        exam.setExamId("PE_TEST");
        exam.setTestcaseConfigJson(COMMON_CONFIG);
        assertFalse(new TestcaseTemplateService().refreshCommonEngine(exam));
    }
}
