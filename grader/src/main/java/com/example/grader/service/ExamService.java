package com.example.grader.service;

import com.example.grader.dto.ExamSetupResponse;
import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import com.example.grader.repository.ExamRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Service
@Slf4j
public class ExamService {

    // Đường dẫn cố định trên server — admin lo 1 lần
    private static final String TEMPLATE_DIR = "D:/FPT/Capstone/Grader_App/grader-base";
    private static final String EXAMS_DIR    = "D:/FPT/Capstone/Grader_App/exams";

    @Autowired
    private ExamRepository examRepository;

    // ── GV chỉ gọi hàm này ──────────────────────────────────────
    public ExamSetupResponse setupExam(String examId,
                                       MultipartFile testcaseZip) throws Exception {
        // 1. Tạo thư mục cho đề thi này
        Path examDir     = Path.of(EXAMS_DIR, examId);
        Path testcaseDir = examDir.resolve("testcase");
        Path buildDir    = examDir.resolve("build");
        Files.createDirectories(testcaseDir);
        Files.createDirectories(buildDir);

        // 2. Giải nén testcase GV upload
        unzip(testcaseZip.getBytes(), testcaseDir);

        // 3. Validate đủ file chưa
        validateRequiredFiles(testcaseDir);

        // 4. Ghép build context:
        //    Dockerfile.template + run_grader.sh (từ server)
        //    + test/ của GV (từ zip vừa upload)
        assembleBuildContext(testcaseDir, buildDir);

        // 5. Docker build tự động
        String imageName = "grading-env-" + examId.toLowerCase();
        buildDockerImage(buildDir, imageName);

        // 6. Lưu DB
        Exam exam = examRepository.findByExamId(examId)
                .orElse(new Exam());
        exam.setExamId(examId);
        exam.setImageName(imageName);
        exam.setStatus(ExamStatus.READY);
        examRepository.save(exam);

        return new ExamSetupResponse(examId, imageName, "READY");
    }

    // ── Ghép Dockerfile + testcase vào 1 build context ──────────
    private void assembleBuildContext(Path testcaseDir, Path buildDir) throws Exception {

        // Copy Dockerfile.template → build/Dockerfile
        Files.copy(
                Path.of(TEMPLATE_DIR, "Dockerfile.template"),
                buildDir.resolve("Dockerfile"),
                StandardCopyOption.REPLACE_EXISTING
        );

        // Copy run_grader.sh — strip CRLF and BOM so Linux shebang is valid
        String shContent = Files.readString(
                Path.of(TEMPLATE_DIR, "run_grader.sh"), StandardCharsets.UTF_8)
                .replace("\uFEFF", "") // Remove UTF-8 BOM if present
                .replace("\r\n", "\n")
                .replace("\r", "\n");
        Files.writeString(buildDir.resolve("run_grader.sh"),
                shContent, StandardCharsets.UTF_8);

        // Copy scripts/merge_pubspec.dart
        Path scriptsDst = buildDir.resolve("scripts");
        Files.createDirectories(scriptsDst);
        Files.copy(
                Path.of(TEMPLATE_DIR, "scripts/merge_pubspec.dart"),
                scriptsDst.resolve("merge_pubspec.dart"),
                StandardCopyOption.REPLACE_EXISTING
        );

        // Copy pubspec base
        Files.copy(
                Path.of(TEMPLATE_DIR, "pubspec.base.yaml"),
                buildDir.resolve("pubspec.yaml"),
                StandardCopyOption.REPLACE_EXISTING
        );

        // Copy testcase GV → build/test/
        Path buildTestDir = buildDir.resolve("test");
        Files.createDirectories(buildTestDir);
        copyDirectory(testcaseDir, buildTestDir);

        log.info("📦 Build context:\n  Dockerfile ✓\n  run_grader.sh ✓\n  test/ ✓ ({} files)",
                Files.list(buildTestDir).count());
    }

    // ── Gọi docker build ─────────────────────────────────────────
    private void buildDockerImage(Path buildDir, String imageName) throws Exception {
        log.info("🔨 Building image: {}...", imageName);

        ProcessBuilder pb = new ProcessBuilder(
                "docker", "build", "-t", imageName,
                buildDir.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Log output docker build
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) log.debug("[docker build] {}", line);
        }

        int exit = process.waitFor();
        if (exit != 0) throw new RuntimeException(
                "docker build thất bại (exit " + exit + "). Kiểm tra lại testcase."
        );

        log.info("✅ Image {} đã sẵn sàng", imageName);
    }

    private void validateRequiredFiles(Path dir) throws Exception {
        for (String f : List.of("exam_test.dart", "grader.dart", "skills_matrix.json")) {
            if (!Files.exists(dir.resolve(f)))
                throw new IllegalArgumentException("Thiếu file bắt buộc: " + f);
        }
    }

    private void copyDirectory(Path src, Path dst) throws Exception {
        Files.walk(src).forEach(s -> {
            try {
                Files.copy(s, dst.resolve(src.relativize(s)),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {}
        });
    }

    private void unzip(byte[] bytes, Path dest) throws Exception {
        try (var zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = dest.resolve(entry.getName());
                if (entry.isDirectory()) { Files.createDirectories(out); }
                else {
                    Files.createDirectories(out.getParent());
                    Files.write(out, zis.readAllBytes());
                }
            }
        }
    }
}
