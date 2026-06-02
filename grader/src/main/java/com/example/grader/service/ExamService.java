package com.example.grader.service;

import com.example.grader.dto.ExamSetupResponse;
import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Setup đề theo cơ chế MOUNT: KHÔNG build image riêng cho từng đề.
 * Chỉ lưu testcase lên đĩa rồi mount vào ảnh nền grading-base lúc chấm.
 *  → upload gần như tức thì, không tích tụ image.
 */
@Service
@Slf4j
public class ExamService {

    @Value("${grader.template-dir:grader-base}")
    private String templateDir;

    @Value("${grader.exams-dir:exams}")
    private String examsDir;

    @Value("${grader.base-image:grading-base:latest}")
    private String baseImage;

    @Value("${grader.image.prefix:grading-env}")
    private String imagePrefix;

    private final AtomicBoolean baseImageReady = new AtomicBoolean(false);
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private ExamRepository examRepository;

    // ── Rubric chấm tay: đọc tiêu chí từ skills_matrix.json của đề ──
    public List<Map<String, Object>> getCriteria(String examId) throws Exception {
        Exam exam = examRepository.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề: " + examId));
        String tc = exam.getTestcasePath();
        List<Map<String, Object>> out = new ArrayList<>();
        if (tc == null || tc.isBlank()) return out;
        Path f = Path.of(tc).resolve("skills_matrix.json");
        if (!Files.exists(f)) return out;

        JsonNode root = mapper.readTree(Files.readString(f));
        root.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("testId", e.getKey());
            m.put("name", v.path("name").asText(""));
            m.put("skill", v.path("skill").asText(""));
            m.put("weight", v.path("weight").asDouble(0));
            m.put("description", v.path("description").asText(""));
            m.put("expected", v.path("expected").asText(""));
            out.add(m);
        });
        return out;
    }

    // ── Setup đề: chỉ lưu testcase (mount lúc chấm), KHÔNG build image ──
    public ExamSetupResponse setupExam(String examId, String examName,
                                       String teacherNote, MultipartFile testcaseZip) throws Exception {
        Path tmplDir = locateTemplateDir();
        ensureBaseImage(tmplDir);   // vẫn cần ảnh nền (container chạy từ đây)

        Path testcaseDir = resolveExamsDir(tmplDir).resolve(examId).resolve("testcase");
        if (Files.exists(testcaseDir)) deleteRecursively(testcaseDir);
        Files.createDirectories(testcaseDir);

        unzip(testcaseZip.getBytes(), testcaseDir);
        validateRequiredFiles(testcaseDir);

        Exam exam = examRepository.findByExamId(examId).orElse(new Exam());
        exam.setExamId(examId);
        exam.setImageName(baseImage);
        exam.setTestcasePath(testcaseDir.toAbsolutePath().normalize().toString());
        exam.setStatus(ExamStatus.READY);
        if (examName    != null && !examName.isBlank())    exam.setExamName(examName.trim());
        if (teacherNote != null && !teacherNote.isBlank()) exam.setTeacherNote(teacherNote.trim());
        examRepository.save(exam);

        log.info("✅ Đề {} sẵn sàng (mount testcase, không build image): {}", examId, testcaseDir);
        return new ExamSetupResponse(examId, baseImage, "READY");
    }

    // ── Xóa đề: gỡ ảnh per-exam cũ (legacy) nếu còn + testcase + DB ──
    public Map<String, Object> deleteExam(String examId) {
        boolean imageRemoved = false;
        try {
            imageRemoved = runDocker(
                    List.of("docker", "rmi", "-f", imagePrefix + "-" + examId.toLowerCase()),
                    "rmi-" + examId) == 0;
        } catch (Exception e) {
            log.warn("Không gỡ được ảnh legacy của {}: {}", examId, e.getMessage());
        }
        try {
            Path examDir = resolveExamsDir(locateTemplateDir()).resolve(examId);
            if (Files.exists(examDir)) deleteRecursively(examDir);
        } catch (Exception ignored) {}

        boolean dbRemoved = examRepository.findByExamId(examId)
                .map(e -> { examRepository.delete(e); return true; })
                .orElse(false);

        log.info("🗑️ Đã xóa đề {} (ảnh legacy: {}, DB: {})", examId, imageRemoved, dbRemoved);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("examId", examId);
        r.put("imageRemoved", imageRemoved);
        r.put("dbRecordRemoved", dbRemoved);
        return r;
    }

    /** Thư mục cạnh grader-base (gốc repo)/<name>, dùng chung cho exams/ và submissions/. */
    public Path resolveSibling(String name) {
        Path p = Path.of(name);
        if (p.isAbsolute()) return p.normalize();
        Path root = locateTemplateDir().getParent();
        return (root != null ? root.resolve(name) : p.toAbsolutePath()).normalize();
    }

    // ── Ảnh nền dùng chung: build 1 lần duy nhất ────────────────
    private synchronized void ensureBaseImage(Path base) throws Exception {
        if (baseImageReady.get()) return;

        if (dockerImageExists(baseImage)) {
            log.info("✅ Ảnh nền {} đã có sẵn", baseImage);
            baseImageReady.set(true);
            return;
        }
        if (!Files.exists(base.resolve("Dockerfile.base")))
            throw new IllegalStateException(
                    "Không tìm thấy Dockerfile.base trong " + base.toAbsolutePath()
                  + ". Đặt biến môi trường GRADER_TEMPLATE_DIR trỏ tới thư mục grader-base "
                  + "(vd <repo>/grader-base) hoặc chạy backend từ thư mục gốc repo.");

        log.info("🏗️  Chưa có ảnh nền {} — build lần đầu (có thể mất vài phút)...", baseImage);
        int exit = runDocker(List.of(
                "docker", "build",
                "-f", base.resolve("Dockerfile.base").toAbsolutePath().toString(),
                "-t", baseImage,
                base.toAbsolutePath().toString()
        ), "base-build");
        if (exit != 0)
            throw new RuntimeException("Build ảnh nền thất bại (exit " + exit + ").");

        log.info("✅ Ảnh nền {} đã sẵn sàng", baseImage);
        baseImageReady.set(true);
    }

    // ── Định vị grader-base (dò ngược lên từ CWD) ───────────────
    private Path locateTemplateDir() {
        Path configured = Path.of(templateDir);
        String name = configured.getFileName() != null
                ? configured.getFileName().toString() : "grader-base";

        List<Path> tried = new ArrayList<>();
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            for (Path c : new Path[]{ p.resolve(configured), p.resolve(name) }) {
                tried.add(c);
                if (Files.exists(c.resolve("Dockerfile.base")))
                    return c.toAbsolutePath().normalize();
            }
            p = p.getParent();
        }
        log.warn("⚠️ Không định vị được grader-base (Dockerfile.base). Đã thử: {}", tried);
        return configured.toAbsolutePath().normalize();
    }

    private Path resolveExamsDir(Path tmplDir) {
        Path configured = Path.of(examsDir);
        if (configured.isAbsolute()) return configured.normalize();
        Path root = tmplDir.getParent();
        return (root != null ? root.resolve(examsDir) : configured.toAbsolutePath()).normalize();
    }

    // ── Helpers docker ──────────────────────────────────────────
    private boolean dockerImageExists(String image) {
        try {
            Process pr = new ProcessBuilder("docker", "image", "inspect", image)
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                while (r.readLine() != null) { /* drain */ }
            }
            return pr.waitFor(30, TimeUnit.SECONDS) && pr.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int runDocker(List<String> command, String tag) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("DOCKER_BUILDKIT", "1");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) log.debug("[{}] {}", tag, line);
        }
        return process.waitFor();
    }

    // ── Validate + unzip + xóa đệ quy ───────────────────────────
    private void validateRequiredFiles(Path dir) throws Exception {
        for (String f : List.of("exam_test.dart", "grader.dart", "skills_matrix.json")) {
            Path p = dir.resolve(f);
            if (!Files.exists(p))
                throw new IllegalArgumentException("Thiếu file bắt buộc: " + f);
            if (Files.size(p) == 0)
                throw new IllegalArgumentException(
                        "File bắt buộc bị RỖNG (0 byte): " + f
                      + " — kiểm tra lại nội dung file trước khi nén zip.");
        }
        if (!Files.readString(dir.resolve("grader.dart")).contains("main"))
            throw new IllegalArgumentException(
                    "grader.dart không có hàm main() — file có thể sai nội dung/encoding.");
    }

    private void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    private void unzip(byte[] bytes, Path dest) throws Exception {
        try (var zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = dest.resolve(entry.getName()).normalize();
                if (!out.startsWith(dest))
                    throw new IllegalArgumentException("Zip Slip: " + entry.getName());
                if (entry.isDirectory()) { Files.createDirectories(out); }
                else {
                    Files.createDirectories(out.getParent());
                    Files.write(out, zis.readAllBytes());
                }
            }
        }
    }
}
