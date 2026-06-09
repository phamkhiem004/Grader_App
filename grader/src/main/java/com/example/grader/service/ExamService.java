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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    @Autowired
    private SyllabusService syllabusService;

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

    /** Thư mục gốc chứa các đề (exams/), nơi mỗi đề là <exams>/<id>/testcase/. */
    private Path examsRoot() {
        return resolveExamsDir(locateTemplateDir());
    }

    /**
     * Đọc nguyên văn skills_matrix.json của 1 đề. Ưu tiên testcasePath trong DB; nếu không
     * có thì đọc trực tiếp thư mục trên đĩa <exams>/<examId>/testcase/ (đề mẫu chưa upload).
     */
    public String readSkillsMatrixJson(String examId) throws Exception {
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        if (exam != null && exam.getTestcasePath() != null && !exam.getTestcasePath().isBlank()) {
            Path f = Path.of(exam.getTestcasePath()).resolve("skills_matrix.json");
            if (Files.exists(f)) return Files.readString(f);
        }
        Path disk = examsRoot().resolve(examId).resolve("testcase").resolve("skills_matrix.json");
        if (Files.exists(disk)) return Files.readString(disk);
        throw new IllegalArgumentException("Không tìm thấy skills_matrix.json của đề " + examId);
    }

    /**
     * Danh sách đề có testcase — gộp đề đã cấu hình trong DB và các thư mục đề trên đĩa
     * (exams/&lt;id&gt;/testcase/skills_matrix.json), để đánh giá độ phủ ngay cả khi chưa upload.
     */
    public List<Map<String, Object>> listExams() {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();

        // 1) Đề trong DB
        for (Exam e : examRepository.findAll()) {
            boolean hasTc = e.getTestcasePath() != null && !e.getTestcasePath().isBlank()
                    && Files.exists(Path.of(e.getTestcasePath()).resolve("skills_matrix.json"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("examId", e.getExamId());
            m.put("examName", e.getExamName() != null ? e.getExamName() : e.getExamId());
            m.put("status", e.getStatus() != null ? e.getStatus().name() : null);
            m.put("hasTestcase", hasTc);
            byId.put(e.getExamId(), m);
        }

        // 2) Thư mục đề trên đĩa
        try {
            Path root = examsRoot();
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.list(root)) {
                    for (Path d : s.filter(Files::isDirectory).toList()) {
                        if (!Files.exists(d.resolve("testcase").resolve("skills_matrix.json"))) continue;
                        String id = d.getFileName().toString();
                        Map<String, Object> existing = byId.get(id);
                        if (existing != null) { existing.put("hasTestcase", true); continue; }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("examId", id);
                        m.put("examName", id);
                        m.put("status", "ON_DISK");
                        m.put("hasTestcase", true);
                        byId.put(id, m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Quét thư mục exams lỗi: {}", e.getMessage());
        }

        List<Map<String, Object>> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> String.valueOf(a.get("examId")).compareTo(String.valueOf(b.get("examId"))));
        return out;
    }

    // ── Setup đề: chỉ lưu testcase (mount lúc chấm), KHÔNG build image ──
    public ExamSetupResponse setupExam(String examId, String examName,
                                       String teacherNote, MultipartFile testcaseZip) throws Exception {
        Path tmplDir = locateTemplateDir();
        ensureBaseImage(tmplDir);   // vẫn cần ảnh nền (container chạy từ đây)

        Path testcaseDir = resolveExamsDir(tmplDir).resolve(examId).resolve("testcase");
        if (Files.exists(testcaseDir)) archiveTestcase(examId, testcaseDir);   // giữ lịch sử thay vì xoá hẳn
        Files.createDirectories(testcaseDir);

        unzip(testcaseZip.getBytes(), testcaseDir);
        validateRequiredFiles(testcaseDir);
        validateTestcaseImports(testcaseDir);   // CHẶN package ngoài (vd intl) → tránh 0/0 oan cả lớp
        validateSkillCodes(testcaseDir);   // skill_code (nếu khai) phải nằm trong syllabus

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

    /**
     * CHẶN testcase import package KHÔNG có trong môi trường chấm (chỉ có những gì khai trong
     * pubspec.base.yaml: flutter, flutter_test...). Đây là nguyên nhân khiến CẢ LỚP bị 0/0 oan
     * (vd đề import 'package:intl/intl.dart' → exam_test.dart không biên dịch được). Bắt ngay lúc upload.
     */
    private void validateTestcaseImports(Path testcaseDir) throws Exception {
        Set<String> allowed = allowedPackages();
        java.util.regex.Pattern p =
                java.util.regex.Pattern.compile("import\\s+['\"]package:([A-Za-z0-9_]+)/");
        Set<String> bad = new LinkedHashSet<>();
        List<Path> dartFiles;
        try (Stream<Path> s = Files.walk(testcaseDir)) {
            dartFiles = s.filter(f -> f.toString().endsWith(".dart")).toList();
        }
        for (Path f : dartFiles) {
            for (String line : Files.readAllLines(f)) {
                java.util.regex.Matcher m = p.matcher(line);
                if (m.find() && !allowed.contains(m.group(1))) bad.add(m.group(1));
            }
        }
        if (!bad.isEmpty())
            throw new IllegalArgumentException(
                    "Testcase dùng package KHÔNG có trong môi trường chấm: " + String.join(", ", bad)
                  + ". Môi trường chỉ có: " + String.join(", ", allowed)
                  + ". Hãy bỏ các package này khỏi testcase (chỉ dùng flutter/flutter_test, định dạng/parse "
                  + "bằng Dart thuần), hoặc thêm chúng vào grader-base/pubspec.base.yaml rồi build lại ảnh nền.");
    }

    /** Tập package được phép = các dependency khai trong pubspec.base.yaml (nguồn sự thật của ảnh nền). */
    private Set<String> allowedPackages() {
        Set<String> allowed = new HashSet<>(Set.of("flutter", "flutter_test"));
        try {
            Path pubspec = locateTemplateDir().resolve("pubspec.base.yaml");
            if (Files.exists(pubspec)) {
                boolean inDeps = false;
                java.util.regex.Pattern dep = java.util.regex.Pattern.compile("^ {2}([A-Za-z0-9_]+)\\s*:");
                for (String raw : Files.readAllLines(pubspec)) {
                    String line = raw.replace("\t", "  ");
                    if (line.isBlank() || line.trim().startsWith("#")) continue;
                    if (!line.startsWith(" ")) {                       // header ở cột 0
                        String key = line.split(":")[0].trim();
                        inDeps = key.equals("dependencies") || key.equals("dev_dependencies");
                        continue;
                    }
                    java.util.regex.Matcher m = dep.matcher(line);     // entry "  name:" (2 space)
                    if (inDeps && m.find()) allowed.add(m.group(1));
                }
            }
        } catch (Exception e) {
            log.warn("Đọc pubspec.base.yaml để lấy danh sách package cho phép lỗi: {}", e.getMessage());
        }
        return allowed;
    }

    /**
     * Kiểm tra skill_code trong skills_matrix.json (nếu giảng viên có khai) phải nằm trong
     * syllabus và chưa bị deprecate. Testcase KHÔNG khai skill_code được bỏ qua (tương thích đề cũ).
     */
    private void validateSkillCodes(Path testcaseDir) throws Exception {
        Path f = testcaseDir.resolve("skills_matrix.json");
        if (!Files.exists(f)) return;
        List<Map<String, Object>> problems = syllabusService.validateSkillsMatrix(Files.readString(f));
        if (problems.isEmpty()) return;

        // "error" (skill_code sai/deprecated, difficulty không hợp lệ) → CHẶN upload.
        // "warning" (vd weight lệch độ khó) → chỉ ghi log, vẫn cho upload (grader tự suy weight từ difficulty).
        List<Map<String, Object>> errors   = problems.stream()
                .filter(p -> !"warning".equals(p.get("severity"))).toList();
        List<Map<String, Object>> warnings = problems.stream()
                .filter(p ->  "warning".equals(p.get("severity"))).toList();

        if (!warnings.isEmpty())
            log.warn("⚠️ skills_matrix.json có {} cảnh báo: {}", warnings.size(), describeProblems(warnings));
        if (!errors.isEmpty())
            throw new IllegalArgumentException(
                    "skills_matrix.json có skill_code không hợp lệ: " + describeProblems(errors));
    }

    private String describeProblems(List<Map<String, Object>> problems) {
        return problems.stream()
                .map(p -> p.get("testId") + " → " + p.get("skillCode") + " (" + p.get("issue") + ")")
                .collect(java.util.stream.Collectors.joining("; "));
    }

    /**
     * Lưu phiên bản testcase CŨ trước khi upload đè (di chuyển sang testcase-archive/&lt;epochMillis&gt;/)
     * để sau này còn tra lại đúng bộ đề đã từng dùng nếu nghi ngờ ra đề/chấm sai. Lỗi archive → xoá đè.
     */
    private void archiveTestcase(String examId, Path testcaseDir) {
        try {
            Path archiveDir = testcaseDir.resolveSibling("testcase-archive")
                    .resolve(String.valueOf(Instant.now().toEpochMilli()));
            Files.createDirectories(archiveDir.getParent());
            Files.move(testcaseDir, archiveDir);
            log.info("🗂️ Đã lưu phiên bản testcase cũ của {} → {}", examId, archiveDir);
        } catch (Exception e) {
            log.warn("Không lưu được phiên bản testcase cũ của {}: {} — chuyển sang xoá đè.",
                    examId, e.getMessage());
            try { deleteRecursively(testcaseDir); } catch (Exception ignored) {}
        }
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
