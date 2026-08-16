package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Khung starter phát cho sinh viên phải kèm {@code pubspec.yaml} — thiếu nó thì dự án không chạy
 * được, mà tự viết thì hay khai package ảnh chấm không có và bài đúng vẫn bị 0 điểm.
 *
 * <p>Điểm chốt của test: pubspec đi kèm phải lấy NGUYÊN VĂN từ {@code pubspec.base.yaml} — cùng
 * đúng một nguồn với môi trường chấm. Chép thành bản thứ hai là mở đường cho hai bên trôi lệch
 * nhau mà không ai phát hiện.
 */
class StarterProjectFilesTest {

    @TempDir
    Path tempDir;

    private static final String BASE_PUBSPEC = """
            name: exam_project
            description: Grading template
            publish_to: none

            environment:
              sdk: '>=3.0.0 <4.0.0'

            dependencies:
              flutter:
                sdk: flutter
              sqflite: '>=2.4.2+1 <2.4.3'

            flutter:
              uses-material-design: true
            """;

    private ExamService serviceWithBasePubspec(String content) throws Exception {
        Path templates = tempDir.resolve("grader-base");
        Files.createDirectories(templates);
        // locateTemplateDir chỉ nhận thư mục CÓ Dockerfile.base; thiếu nó là nó dò ngược lên và
        // vớ phải grader-base thật của repo — test hoá ra đang đo file thật, không đo bản dựng ở đây.
        Files.writeString(templates.resolve("Dockerfile.base"), "FROM scratch\n");
        if (content != null) Files.writeString(templates.resolve("pubspec.base.yaml"), content);

        ExamService service = new ExamService();
        ReflectionTestUtils.setField(service, "templateDir", templates.toString());
        // Ảnh nền không tồn tại trong test → nhánh lấy pubspec.lock tự bỏ qua, không gọi Docker thật.
        ReflectionTestUtils.setField(service, "baseImage", "grading-base-khong-ton-tai:test");
        return service;
    }

    @Test
    void kemPubspecLayNguyenVanTuMoiTruongCham() throws Exception {
        List<Map<String, String>> files = serviceWithBasePubspec(BASE_PUBSPEC).starterProjectFiles();

        Map<String, String> pubspec = files.stream()
                .filter(f -> "pubspec.yaml".equals(f.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("Khung starter phải kèm pubspec.yaml: " + files));

        assertTrue(pubspec.get("content").contains("name: exam_project"),
                "Tên dự án phải giữ nguyên, lệch là import package:exam_project/… gãy");
        assertTrue(pubspec.get("content").contains("sqflite: '>=2.4.2+1 <2.4.3'"),
                "Ràng buộc phiên bản phải y hệt môi trường chấm");
        assertTrue(pubspec.get("content").startsWith("#"),
                "Phải có dòng nhắc không thêm package ngoài danh sách");
    }

    /** Không có pubspec.base.yaml (bản cài thiếu file) thì im lặng bỏ qua, không làm hỏng cả lượt sinh khung. */
    @Test
    void thieuPubspecNenThiKhongKemFileNao() throws Exception {
        assertTrue(serviceWithBasePubspec(null).starterProjectFiles().isEmpty());
    }

    /**
     * pubspec.lock chỉ đi kèm khi lấy được bản ĐÃ RESOLVE trong ảnh chấm. Không có Docker/ảnh thì
     * phải vắng mặt chứ không được bịa: một lock sai phiên bản làm sinh viên không build nổi.
     */
    @Test
    void khongCoAnhChamThiKhongBiaPubspecLock() throws Exception {
        List<Map<String, String>> files = serviceWithBasePubspec(BASE_PUBSPEC).starterProjectFiles();
        assertTrue(files.stream().noneMatch(f -> "pubspec.lock".equals(f.get("name"))),
                "Không được phát lock bịa: " + files);
    }
}
