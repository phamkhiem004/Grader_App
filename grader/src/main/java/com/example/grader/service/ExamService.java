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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @Value("${grader.submissions-dir:submissions}")
    private String submissionsDir;

    /** Giới hạn mỗi process Flutter để một testcase treo không chiếm cả lượt chấm. */
    @Value("${grader.runner.process-timeout-seconds:60}")
    private int runnerProcessTimeoutSeconds;

    private final AtomicBoolean baseImageReady = new AtomicBoolean(false);
    private final ObjectMapper mapper = new ObjectMapper();

    // ── Trạng thái build môi trường chấm (thêm/xóa thư viện) — chỉ 1 build tại 1 thời điểm ──
    private final Object envLock = new Object();
    private volatile boolean envBuilding = false;
    private volatile String envStatus  = "IDLE";   // IDLE|RESOLVING|BUILDING|READY|FAILED
    private volatile String envMessage = "";
    private volatile String envLog     = "";
    private volatile long   envAt      = 0;

    @Autowired
    private ExamRepository examRepository;
    @Autowired
    private SyllabusService syllabusService;
    @Autowired
    private com.example.grader.repository.ExamResultRepository resultRepository;

    /**
     * Chặn PATH TRAVERSAL: mã đề/SV chỉ được chứa chữ/số/_/- (vd PE_50, FLUTTER_PE_01, HE123456).
     * Mọi nơi ghép id vào đường dẫn file/lệnh docker PHẢI gọi hàm này trước.
     */
    public static String safeId(String id, String what) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,60}"))
            throw new IllegalArgumentException(
                    "Mã " + what + " không hợp lệ: '" + id + "' (chỉ gồm a-z, A-Z, 0-9, _, -).");
        return id;
    }

    // ── Rubric chấm tay: đọc tiêu chí từ skills_matrix.json của đề ──
    public List<Map<String, Object>> getCriteria(String examId) throws Exception {
        Exam exam = examRepository.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề: " + examId));
        String tc = exam.getTestcasePath();
        List<Map<String, Object>> out = new ArrayList<>();
        if (tc == null || tc.isBlank()) return out;
        Path f = Path.of(tc).resolve("skills_matrix.json");
        if (!Files.exists(f)) return out;

        JsonNode root = mapper.readTree(Files.readString(f, StandardCharsets.UTF_8));
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
        safeId(examId, "đề");
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        if (exam != null && exam.getTestcasePath() != null && !exam.getTestcasePath().isBlank()) {
            Path f = Path.of(exam.getTestcasePath()).resolve("skills_matrix.json");
            if (Files.exists(f)) return Files.readString(f, StandardCharsets.UTF_8);
        }
        Path disk = examsRoot().resolve(examId).resolve("testcase").resolve("skills_matrix.json");
        if (Files.exists(disk)) return Files.readString(disk, StandardCharsets.UTF_8);
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
            m.put("testcaseStatus", e.getTestcaseStatus() != null ? e.getTestcaseStatus() : "DRAFT");
            m.put("testcaseVersion", e.getTestcaseVersion());
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
                        m.put("testcaseStatus", "PUBLISHED");
                        m.put("hasTestcase", true);
                        byId.put(id, m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Quét thư mục exams lỗi: {}", e.getMessage());
        }

        // Bổ sung số bài đã nộp + có sẵn đề bài/starter để tải hay không (cho trang Kho đề)
        for (Map<String, Object> m : byId.values()) {
            String id = String.valueOf(m.get("examId"));
            try { m.put("resultCount", resultRepository.findSubmitStudentIds(id).size()); }
            catch (Exception e) { m.put("resultCount", 0); }
            Path h = handoutDirOf(id);
            m.put("hasDeBai", Files.exists(h.resolve("de_bai.md")));
            m.put("hasStarter", Files.isDirectory(h.resolve("starter")));
            m.put("hasSolution", Files.isDirectory(h.resolve("solution")));
        }

        List<Map<String, Object>> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> String.valueOf(a.get("examId")).compareTo(String.valueOf(b.get("examId"))));
        return out;
    }

    /**
     * Đọc đúng ba file testcase công khai của một đề. File metadata/module nội bộ
     * (nếu còn trong Draft cũ) không thuộc contract chấm và không được lộ ra UI.
     */
    public List<Map<String, String>> readExamTestcaseFiles(String examId) {
        safeId(examId, "đề");
        List<Map<String, String>> out = new ArrayList<>();
        Path dir = previewTestcaseDirOf(examId);
        if (dir == null) return out;

        final int MAX_BYTES = 200_000;
        try {
            for (String name : List.of("exam_test.dart", "skills_matrix.json", "grader.dart")) {
                Path p = dir.resolve(name);
                if (!Files.isRegularFile(p)) continue;
                String content = Files.readString(p, StandardCharsets.UTF_8);
                if (content.length() > MAX_BYTES)
                    content = content.substring(0, MAX_BYTES) + "\n… (đã cắt bớt)";
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("content", content);
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("Đọc testcase đề {} lỗi: {}", examId, e.getMessage());
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  BỘ PHÁT CHO SV (handout = đề bài + khung starter) — LƯU RIÊNG ngoài testcase/
    //  testcase/ được MOUNT vào container lúc chấm → nhét file lạ vào sẽ lẫn vào bài chấm,
    //  nên đề bài & starter lưu ở <exams>/<examId>/handout/ (KHÔNG mount).
    // ════════════════════════════════════════════════════════════════════════════

    /** Thư mục testcase của 1 đề (ưu tiên DB, fallback đĩa); null nếu chưa có. */
    private Path testcaseDirOf(String examId) {
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        if (exam != null && exam.getTestcasePath() != null && !exam.getTestcasePath().isBlank()
                && Files.exists(Path.of(exam.getTestcasePath()))) return Path.of(exam.getTestcasePath());
        Path disk = examsRoot().resolve(examId).resolve("testcase");
        return Files.exists(disk) ? disk : null;
    }

    /** Thư mục testcase để bộ dựng template ghi skills_matrix.json, không phụ thuộc đã upload ZIP hay chưa. */
    public Path testcaseDirectoryForConfiguration(String examId) {
        safeId(examId, "đề");
        Path existing = testcaseDirOf(examId);
        return existing != null ? existing : examsRoot().resolve(examId).resolve("testcase");
    }

    /**
     * Mỗi lần dựng template ghi vào một thư mục bất biến mới. Draft không được chạm vào
     * testcasePath đang dùng để chấm; Publish chỉ đổi con trỏ DB sau khi dựng đủ file.
     */
    public Path testcaseBuildDirectory(String examId, int version, boolean publish) {
        safeId(examId, "đề");
        String folder = publish ? "testcase-versions" : "testcase-drafts";
        String build = "v" + version + "-" + Instant.now().toEpochMilli()
                + "-" + Long.toUnsignedString(System.nanoTime(), 36);
        return examsRoot().resolve(examId).resolve(folder).resolve(build)
                .toAbsolutePath().normalize();
    }

    /** Draft mới nhất dùng cho màn hình preview/tải xuống, không dùng cho worker chấm. */
    private Path previewTestcaseDirOf(String examId) {
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        if (exam != null && exam.getTestcaseConfigJson() != null
                && !exam.getTestcaseConfigJson().isBlank()) {
            try {
                String raw = mapper.readTree(exam.getTestcaseConfigJson())
                        .path("materialized_path").asText("");
                if (!raw.isBlank()) {
                    Path candidate = Path.of(raw).toAbsolutePath().normalize();
                    Path examRoot = examsRoot().resolve(examId).toAbsolutePath().normalize();
                    if (candidate.startsWith(examRoot) && Files.isDirectory(candidate)) {
                        return candidate;
                    }
                }
            } catch (Exception e) {
                log.warn("Không đọc được đường dẫn preview testcase của {}: {}", examId, e.getMessage());
            }
        }
        return testcaseDirOf(examId);
    }

    /** Sao chép snapshot testcase hiện tại trước khi Publish để đề cũ vẫn đối chiếu được. */
    public boolean snapshotCurrentTestcase(String examId) {
        safeId(examId, "đề");
        Path source = testcaseDirOf(examId);
        if (source == null || !Files.isDirectory(source)) return false;
        Path target = source.resolveSibling("testcase-archive")
                .resolve(String.valueOf(Instant.now().toEpochMilli()));
        try {
            Files.createDirectories(target);
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    Path out = target.resolve(source.relativize(p));
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out);
                }
            }
            log.info("🧊 Đã snapshot testcase trước Publish của {} → {}", examId, target);
            return true;
        } catch (Exception e) {
            log.warn("Không snapshot được testcase của {}: {}", examId, e.getMessage());
            return false;
        }
    }

    /** Thư mục handout (đề bài + starter) của 1 đề — luôn nằm CẠNH testcase/ (<exams>/<id>/handout). */
    private Path handoutDirOf(String examId) {
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        if (exam != null && exam.getTestcasePath() != null && !exam.getTestcasePath().isBlank()) {
            Path parent = Path.of(exam.getTestcasePath()).getParent();
            if (parent != null) return parent.resolve("handout");
        }
        return examsRoot().resolve(examId).resolve("handout");
    }

    /**
     * Lưu BỘ PHÁT CHO SV của 1 đề: đề bài (de_bai.md) + khung starter (lib/…). Gọi SAU khi
     * {@link #setupExamFromZipBytes} thành công. Ghi đè bản cũ. Lỗi ghi handout KHÔNG làm hỏng đề
     * (testcase đã lưu xong) — chỉ log cảnh báo.
     *   <exams>/<examId>/handout/de_bai.md
     *   <exams>/<examId>/handout/starter/<lib/...>
     *   <exams>/<examId>/handout/solution/<lib/...>   (lời giải mẫu để GV tải về tham khảo)
     */
    public void saveHandout(String examId, String deBai, List<Map<String, String>> starter,
                            List<Map<String, String>> solution) {
        safeId(examId, "đề");
        try {
            Path handout = handoutDirOf(examId);
            if (Files.exists(handout)) deleteRecursively(handout);   // làm mới (đề có thể lưu lại nhiều lần)

            boolean any = false;
            if (deBai != null && !deBai.isBlank()) {
                Files.createDirectories(handout);
                Files.writeString(handout.resolve("de_bai.md"), deBai, StandardCharsets.UTF_8);
                any = true;
            }
            any |= writeHandoutFiles(handout.resolve("starter"), starter);     // khung code phát SV
            any |= writeHandoutFiles(handout.resolve("solution"), solution);   // lời giải mẫu (KHÔNG phát SV)
            if (any) log.info("📄 Đã lưu bộ phát SV (đề bài + starter + lời giải mẫu) cho đề {} → {}", examId, handout);
        } catch (IllegalArgumentException e) {
            throw e;   // tên file xấu → để caller biết (không nuốt lỗi bảo mật)
        } catch (Exception e) {
            log.warn("Lưu handout cho đề {} lỗi: {} — bỏ qua (testcase vẫn lưu được).", examId, e.getMessage());
        }
    }

    /**
     * Ghi danh sách file {name, content} (lib/…) vào 1 thư mục con của handout (starter/ hoặc solution/).
     * Chặn path traversal ('../') trong tên file. Trả {@code true} nếu có ghi ít nhất 1 file.
     */
    private boolean writeHandoutFiles(Path subRoot, List<Map<String, String>> files) throws Exception {
        if (files == null) return false;
        boolean any = false;
        for (Map<String, String> f : files) {
            String name = f == null ? null : f.get("name");
            if (name == null || name.isBlank()) continue;
            Path out = subRoot.resolve(name).normalize();
            if (!out.startsWith(subRoot))   // chặn path traversal ('../') trong tên file
                throw new IllegalArgumentException("Tên file không hợp lệ: " + name);
            Files.createDirectories(out.getParent());
            Files.writeString(out, f.getOrDefault("content", ""), StandardCharsets.UTF_8);
            any = true;
        }
        return any;
    }

    /** ZIP đúng ba file testcase công khai để tải về và upload lại. */
    public byte[] zipTestcase(String examId) throws Exception {
        safeId(examId, "đề");
        Path dir = previewTestcaseDirOf(examId);
        if (dir == null) return null;
        Map<String, String> files = new LinkedHashMap<>();
        for (String f : List.of("exam_test.dart", "grader.dart", "skills_matrix.json")) {
            Path p = dir.resolve(f);
            if (Files.exists(p)) files.put(f, Files.readString(p, StandardCharsets.UTF_8));
        }
        return files.isEmpty() ? null : zipBytes(files);
    }

    /** Đọc đề bài (de_bai.md) đã lưu trong handout/; null nếu đề chưa có (đề cũ / upload tay). */
    public String readDeBai(String examId) throws Exception {
        safeId(examId, "đề");
        Path f = handoutDirOf(examId).resolve("de_bai.md");
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /** ZIP khung starter (handout/starter/<lib/…>) để phát SV; null nếu đề chưa có starter. */
    public byte[] zipStarter(String examId) throws Exception {
        return zipHandoutSubdir(examId, "starter");
    }

    /** ZIP lời giải mẫu (handout/solution/<lib/…>) để GV tải về tham khảo; null nếu đề chưa có. */
    public byte[] zipSolution(String examId) throws Exception {
        return zipHandoutSubdir(examId, "solution");
    }

    /** ZIP toàn bộ file trong 1 thư mục con của handout (starter/ hoặc solution/); null nếu thư mục trống/không có. */
    private byte[] zipHandoutSubdir(String examId, String sub) throws Exception {
        safeId(examId, "đề");
        Path root = handoutDirOf(examId).resolve(sub);
        if (!Files.isDirectory(root)) return null;
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList())
                files.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
        }
        return files.isEmpty() ? null : zipBytes(files);
    }

    private byte[] zipBytes(Map<String, String> files) throws Exception {
        var bos = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(bos)) {
            for (var e : files.entrySet()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zos.write((e.getValue() == null ? "" : e.getValue()).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    // ── Setup đề: chỉ lưu testcase (mount lúc chấm), KHÔNG build image ──
    public ExamSetupResponse setupExam(String examId, String examName,
                                       String teacherNote, MultipartFile testcaseZip) throws Exception {
        return setupExamFromZipBytes(examId, examName, teacherNote, testcaseZip.getBytes());
    }

    /** Như {@link #setupExam} nhưng nhận thẳng bytes của file zip để tái dùng pipeline validate hiện có. */
    public ExamSetupResponse setupExamFromZipBytes(String examId, String examName,
                                                   String teacherNote, byte[] zipBytes) throws Exception {
        safeId(examId, "đề");                 // chặn path traversal khi tạo exams/<examId>/testcase
        Path tmplDir = locateTemplateDir();
        ensureBaseImage(tmplDir);   // vẫn cần ảnh nền (container chạy từ đây)

        Path testcaseDir = resolveExamsDir(tmplDir).resolve(examId).resolve("testcase");
        if (Files.exists(testcaseDir)) archiveTestcase(examId, testcaseDir);   // giữ lịch sử thay vì xoá hẳn
        Files.createDirectories(testcaseDir);

        unzip(zipBytes, testcaseDir);
        validateRequiredFiles(testcaseDir);
        normalizeExamTestNames(testcaseDir);   // Bỏ group wrapper để Flutter trả test name đúng TC_...
        normalizeGraderRubricKeys(testcaseDir); // Tránh Flutter thêm group prefix làm lệch key rubric.
        normalizeGraderExecution(testcaseDir); // Tránh retry hàng loạt làm một bài chạm timeout Docker.
        ensureTestcaseImportsAvailable(testcaseDir); // Tự bổ sung package thiếu vào môi trường chấm nếu có thể
        validateTestcaseImports(testcaseDir);   // CHẶN package ngoài còn thiếu → tránh 0/0 oan cả lớp
        validateSkillCodes(testcaseDir);   // skill_code (nếu khai) phải nằm trong syllabus

        Exam exam = examRepository.findByExamId(examId).orElse(new Exam());
        exam.setExamId(examId);
        exam.setImageName(baseImage);
        exam.setTestcasePath(testcaseDir.toAbsolutePath().normalize().toString());
        exam.setStatus(ExamStatus.READY);
        exam.setTestcaseStatus("PUBLISHED");
        if (exam.getTestcaseVersion() == null) exam.setTestcaseVersion(1);
        if (exam.getTestcasePublishedAt() == null) exam.setTestcasePublishedAt(Instant.now());
        if (examName    != null && !examName.isBlank())    exam.setExamName(examName.trim());
        if (teacherNote != null && !teacherNote.isBlank()) exam.setTeacherNote(teacherNote.trim());
        examRepository.save(exam);

        log.info("✅ Đề {} sẵn sàng (mount testcase, không build image): {}", examId, testcaseDir);
        return new ExamSetupResponse(examId, baseImage, "READY");
    }

    // ── Xóa đề: gỡ ảnh legacy + testcase + TOÀN BỘ bài nộp (submissions) + bản ghi đề (DB) ──
    public Map<String, Object> deleteExam(String examId) {
        safeId(examId, "đề");
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

        // Xóa CẢ thư mục bài nộp submissions/<đề>/ (zip + snapshot testcase) → giải phóng dung lượng.
        boolean submissionsRemoved = false;
        try {
            Path subDir = resolveSibling(submissionsDir).resolve(examId);
            if (Files.exists(subDir)) { deleteRecursively(subDir); submissionsRemoved = true; }
        } catch (Exception e) {
            log.warn("Không xóa được submissions của {}: {}", examId, e.getMessage());
        }

        boolean dbRemoved = examRepository.findByExamId(examId)
                .map(e -> { examRepository.delete(e); return true; })
                .orElse(false);

        log.info("🗑️ Đã xóa đề {} (ảnh legacy: {}, submissions: {}, DB: {})",
                examId, imageRemoved, submissionsRemoved, dbRemoved);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("examId", examId);
        r.put("imageRemoved", imageRemoved);
        r.put("submissionsRemoved", submissionsRemoved);
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
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
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

    // ════════════════════════════════════════════════════════════════════════════
    //  QUẢN LÝ THƯ VIỆN MÔI TRƯỜNG CHẤM (thêm/xóa package trong pubspec.base.yaml)
    // ════════════════════════════════════════════════════════════════════════════

    private Path basePubspec() { return locateTemplateDir().resolve("pubspec.base.yaml"); }

    /** Danh sách package trong khối dependencies: [{name, version, protected}]. `flutter` = lõi (không xóa). */
    public List<Map<String, Object>> listManagedPackages() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Path pubspec = basePubspec();
            if (!Files.exists(pubspec)) return out;
            boolean inDeps = false;
            java.util.regex.Pattern entry = java.util.regex.Pattern.compile("^ {2}([A-Za-z0-9_]+):\\s*(.*)$");
            for (String raw : Files.readAllLines(pubspec, StandardCharsets.UTF_8)) {
                String line = raw.replace("\t", "  ");
                if (line.isBlank() || line.trim().startsWith("#")) continue;
                if (!line.startsWith(" ")) { inDeps = line.split(":")[0].trim().equals("dependencies"); continue; }
                if (!inDeps) continue;
                java.util.regex.Matcher m = entry.matcher(line);
                if (!m.find()) continue;
                String name = m.group(1), val = m.group(2).trim();
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", name);
                boolean isBlock = val.isEmpty();                 // vd flutter: → sdk block ở dòng dưới
                p.put("version", isBlock ? "(flutter sdk)" : val);
                p.put("protected", isBlock || name.equals("flutter") || name.equals("flutter_test"));
                out.add(p);
            }
        } catch (Exception e) {
            log.warn("listManagedPackages lỗi: {}", e.getMessage());
        }
        return out;
    }

    public Map<String, Object> buildStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", envStatus);
        m.put("message", envMessage);
        m.put("log", envLog);
        m.put("at", envAt);
        m.put("building", envBuilding);
        return m;
    }

    private void setEnv(String status, String message, String logTail) {
        envStatus = status; envMessage = message;
        if (logTail != null) envLog = logTail;
        envAt = System.currentTimeMillis();
    }

    /**
     * Ghi danh sách package (desired = các package SỬA ĐƯỢC, không gồm flutter lõi) vào
     * pubspec.base.yaml rồi CẬP NHẬT thư viện vào ảnh nền HIỆN CÓ (docker commit, KHÔNG build
     * ảnh mới) — chạy nền. Lỗi resolve/pub get → HOÀN TÁC pubspec, ảnh nền giữ nguyên.
     */
    public Map<String, Object> applyPackages(List<Map<String, Object>> desired) {
        synchronized (envLock) {
            if (envBuilding) throw new IllegalStateException("Đang build môi trường, vui lòng đợi build xong.");
            envBuilding = true;
            setEnv("RESOLVING", "Đang chuẩn bị môi trường...", "");
        }
        new Thread(() -> {
            try { doApplyPackages(desired); }
            catch (Exception e) { log.warn("applyPackages thread lỗi: {}", e.getMessage()); }
            finally { envBuilding = false; }
        }, "env-build").start();
        return buildStatus();
    }

    private void doApplyPackages(List<Map<String, Object>> desired) {
        try {
            doApplyPackagesBlocking(desired);
        } catch (Exception e) {
            setEnv("FAILED", "Lỗi: " + e.getMessage() + " — đã hoàn tác thay đổi.", envLog);
            ExamService.log.warn("❌ Cập nhật thư viện vào ảnh nền thất bại: {}", e.getMessage());
        }
    }

    private void doApplyPackagesBlocking(List<Map<String, Object>> desired) throws Exception {
        Path pubspec = basePubspec();
        String snapshot = null;
        StringBuilder logBuf = new StringBuilder();
        try {
            // Đảm bảo ĐÃ CÓ ảnh nền (build 1 lần duy nhất nếu máy chưa có); sau đó chỉ CẬP NHẬT tại chỗ.
            ensureBaseImage(locateTemplateDir());

            snapshot = Files.readString(pubspec, StandardCharsets.UTF_8);

            // 1) Resolve version cho package chưa khai version (flutter pub add trong container ephemeral)
            List<String[]> resolved = resolveVersions(desired);

            // 2) Ghi khối dependencies vào pubspec.base.yaml (nguồn sự thật để validate khi upload đề)
            writeDependenciesBlock(pubspec, resolved);

            // 3) CẬP NHẬT thư viện vào ẢNH NỀN HIỆN CÓ bằng docker commit — KHÔNG build/tạo ảnh mới,
            //    nên không tích tụ image dangling, không phình dung lượng đĩa.
            setEnv("BUILDING", "Đang cập nhật thư viện vào ảnh nền chấm...", envLog);
            updateBaseImageInPlace(pubspec, logBuf);

            baseImageReady.set(true);
            setEnv("READY", "Đã cập nhật môi trường chấm (" + resolved.size() + " thư viện).", tail(logBuf.toString()));
            ExamService.log.info("✅ Cập nhật thư viện vào ảnh nền xong: {} thư viện", resolved.size());

        } catch (Exception e) {
            if (snapshot != null) {
                try { Files.writeString(pubspec, snapshot, StandardCharsets.UTF_8); } // HOÀN TÁC để không kẹt pubspec hỏng
                catch (Exception ex) { log.warn("Revert pubspec lỗi: {}", ex.getMessage()); }
            }
            throw e;
        }
    }

    /**
     * Cập nhật danh sách thư viện vào CHÍNH ảnh nền {@code grading-base:latest} đang có, thay vì
     * build một ảnh mới. Cách làm:
     *   1) chạy 1 container "giữ-sống" từ chính ảnh nền hiện có,
     *   2) ghi pubspec mới vào /app rồi {@code flutter pub get} (tải gói về pub-cache + cập nhật
     *      pubspec.lock và .dart_tool/package_config.json) — y như bước build gốc,
     *   3) {@code docker commit} container đó NGƯỢC LẠI đúng tag {@code grading-base:latest}.
     * Layer nền (~4.5GB) được DÙNG CHUNG nên chỉ phát sinh 1 lớp diff nhỏ; ảnh cũ trở thành lớp cha
     * của ảnh mới (không tốn dung lượng riêng) → "tạo image 1 lần, dùng mãi mãi".
     */
    private void updateBaseImageInPlace(Path pubspec, StringBuilder out) throws Exception {
        String content = Files.readString(pubspec, StandardCharsets.UTF_8);
        String cid = "grader-env-update-" + System.currentTimeMillis();
        try {
            // 1) Container giữ-sống từ CHÍNH ảnh nền hiện có (ghi đè CMD = sleep, ENTRYPOINT vẫn null).
            if (runDockerCapture(List.of(
                    "docker", "run", "-d", "--name", cid, baseImage, "sleep", "infinity"), out) != 0)
                throw new RuntimeException("Không khởi động được container cập nhật môi trường.");

            // 2) Ghi pubspec mới vào /app (qua stdin để khỏi phụ thuộc mount/đường dẫn Windows) rồi pub get.
            String script = "cat > /app/pubspec.yaml"
                    + " && cp /app/pubspec.yaml /app/pubspec_base.yaml"
                    + " && cd /app && flutter pub get";
            if (runDockerWithStdin(List.of("docker", "exec", "-i", "-w", "/app", cid, "bash", "-c", script),
                    content, out) != 0)
                throw new RuntimeException("flutter pub get thất bại trong ảnh nền (xem log).");

            // 3) Đóng băng thay đổi vào ĐÚNG tag ảnh nền. --change khôi phục CMD chấm bài (vì CMD đã bị
            //    ghi đè thành "sleep infinity" ở bước 1). Dùng CMD dạng SHELL (không có dấu nháy kép)
            //    vì dạng JSON exec ["..."] không sống sót qua cách quote tham số của Windows.
            //    ENTRYPOINT/WORKDIR/ENV giữ nguyên.
            if (runDockerCapture(List.of("docker", "commit",
                    "--change", "CMD ./run_grader.sh", cid, baseImage), out) != 0)
                throw new RuntimeException("docker commit thất bại.");
        } finally {
            try { runDocker(List.of("docker", "rm", "-f", cid), "rm-env-update"); }
            catch (Exception ignored) {}
        }
        // Best-effort: dọn các ảnh <none> rác còn sót lại (vd từ cơ chế build cũ) để thu hồi dung lượng.
        try { runDocker(List.of("docker", "image", "prune", "-f"), "img-prune"); }
        catch (Exception ignored) {}
    }

    /** Trả [name, versionConstraint] theo thứ tự desired; resolve version qua `flutter pub add` khi để trống. */
    private List<String[]> resolveVersions(List<Map<String, Object>> desired) throws Exception {
        List<String> toResolve = new ArrayList<>();
        Map<String, String> given = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Map<String, Object> p : desired) {
            String name = p.get("name") == null ? "" : p.get("name").toString().trim();
            if (name.isEmpty()) continue;
            if (name.equals("flutter") || name.equals("flutter_test")) continue;   // lõi, bỏ qua
            if (!name.matches("[a-z][a-z0-9_]*"))
                throw new IllegalArgumentException("Tên package không hợp lệ: '" + name + "' (chỉ a-z, 0-9, _).");
            if (order.contains(name)) continue;
            order.add(name);
            String ver = p.get("version") == null ? "" : p.get("version").toString().trim();
            if (ver.isEmpty() || ver.equals("(flutter sdk)")) toResolve.add(name); else given.put(name, ver);
        }
        Map<String, String> resolved = toResolve.isEmpty() ? Map.of() : runPubAddResolve(toResolve);
        List<String[]> out = new ArrayList<>();
        for (String name : order) out.add(new String[]{ name, given.getOrDefault(name, resolved.getOrDefault(name, "any")) });
        return out;
    }

    /** Chạy `flutter pub add` trong container để lấy version tương thích (đồng thời validate package tồn tại). */
    private Map<String, String> runPubAddResolve(List<String> names) throws Exception {
        setEnv("RESOLVING", "Đang resolve version: " + String.join(", ", names) + " ...", envLog);
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "--rm", "--entrypoint", "bash", baseImage,
                "-c", "cd /app && flutter pub add " + String.join(" ", names)
                    + " 2>&1; echo '---PUBSPEC---'; cat pubspec.yaml"));
        StringBuilder out = new StringBuilder();
        runDockerCapture(cmd, out);
        String s = out.toString();
        int idx = s.indexOf("---PUBSPEC---");
        String pubspecPart = idx >= 0 ? s.substring(idx) : "";
        Map<String, String> res = new LinkedHashMap<>();
        for (String name : names) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)^ {2}" + java.util.regex.Pattern.quote(name) + ":\\s*(\\S+)").matcher(pubspecPart);
            if (m.find()) res.put(name, m.group(1));
            else throw new IllegalArgumentException(
                    "Không thêm được thư viện '" + name + "' (sai tên hoặc không tương thích Flutter SDK). "
                  + "Chi tiết: " + firstError(s));
        }
        return res;
    }

    private String firstError(String out) {
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.contains("not found") || t.contains("version solving failed")
                    || t.toLowerCase().contains("error") || t.contains("Because")) {
                return t.length() > 200 ? t.substring(0, 200) : t;
            }
        }
        return "không rõ (xem log).";
    }

    /** Ghi lại khối `dependencies:` của pubspec.base.yaml (giữ nguyên các phần khác của file). */
    private void writeDependenciesBlock(Path pubspec, List<String[]> deps) throws Exception {
        List<String> lines = Files.readAllLines(pubspec, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        boolean done = false;
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (!done && line.stripTrailing().equals("dependencies:")) {
                out.add("dependencies:");
                out.add("  # Quản lý qua trang \"Thư viện chấm\". 'flutter' là lõi (không xóa).");
                out.add("  flutter:");
                out.add("    sdk: flutter");
                for (String[] d : deps) out.add("  " + d[0] + ": " + d[1]);
                out.add("");
                // bỏ qua khối dependencies cũ tới section kế (dòng ở cột 0) hoặc hết file
                i++;
                while (i < lines.size()) {
                    String l = lines.get(i);
                    if (!l.isEmpty() && !l.startsWith(" ") && !l.startsWith("\t")) break;
                    i++;
                }
                done = true;
                continue;
            }
            out.add(line);
            i++;
        }
        Files.writeString(pubspec, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
    }

    /** Như runDocker nhưng GOM output (giữ ~120 dòng cuối) để hiển thị tiến độ build cho GV. */
    private int runDockerCapture(List<String> command, StringBuilder out) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("DOCKER_BUILDKIT", "1");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        java.util.Deque<String> ring = new java.util.ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append("\n");
                ring.addLast(line);
                while (ring.size() > 120) ring.removeFirst();
                if ("BUILDING".equals(envStatus)) envLog = String.join("\n", ring);
            }
        }
        return process.waitFor();
    }

    /** Như runDockerCapture nhưng ĐẨY {@code stdin} vào tiến trình (vd ghi pubspec qua `cat > file`). */
    private int runDockerWithStdin(List<String> command, String stdin, StringBuilder out) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("DOCKER_BUILDKIT", "1");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Ghi & ĐÓNG stdin trước (gửi EOF cho `cat`) — nội dung nhỏ nên không nghẽn pipe.
        try (var w = process.getOutputStream()) {
            w.write(stdin.getBytes(StandardCharsets.UTF_8));
            w.flush();
        } catch (Exception ignored) {}
        java.util.Deque<String> ring = new java.util.ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append("\n");
                ring.addLast(line);
                while (ring.size() > 120) ring.removeFirst();
                if ("BUILDING".equals(envStatus)) envLog = String.join("\n", ring);
            }
        }
        return process.waitFor();
    }

    private String tail(String s) {
        String[] arr = s.split("\n");
        int from = Math.max(0, arr.length - 40);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < arr.length; i++) sb.append(arr[i]).append("\n");
        return sb.toString().trim();
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
        if (!Files.readString(dir.resolve("grader.dart"), StandardCharsets.UTF_8).contains("main"))
            throw new IllegalArgumentException(
                    "grader.dart không có hàm main() — file có thể sai nội dung/encoding.");
    }

    /**
     * Bỏ wrapper group(...) trong exam_test.dart để flutter test --machine trả đúng tên test
     * dạng "TC_MODEL_01" thay vì "A. User model TC_MODEL_01".
     */
    private void normalizeExamTestNames(Path testcaseDir) {
        Path f = testcaseDir.resolve("exam_test.dart");
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.stream().noneMatch(s -> s.trim().startsWith("group("))) return;

            List<String> out = new ArrayList<>();
            List<Integer> groupDepths = new ArrayList<>();
            int depth = 0;
            int removed = 0;
            for (String line : lines) {
                int delta = braceDelta(line);
                String trim = line.trim();
                if (trim.startsWith("group(")) {
                    depth += delta;
                    groupDepths.add(depth);
                    removed++;
                    continue;
                }

                if (!groupDepths.isEmpty()
                        && trim.equals("});")
                        && depth == groupDepths.get(groupDepths.size() - 1)
                        && depth + delta == groupDepths.get(groupDepths.size() - 1) - 1) {
                    depth += delta;
                    groupDepths.remove(groupDepths.size() - 1);
                    continue;
                }

                out.add(line);
                depth += delta;
            }

            if (!groupDepths.isEmpty() || depth != 0) {
                log.warn("Không bỏ group exam_test.dart vì brace không cân bằng: {}", f);
                return;
            }
            Files.write(f, out, StandardCharsets.UTF_8);
            log.info("Đã bỏ {} group wrapper trong exam_test.dart để tên test khớp rubric: {}", removed, f);
        } catch (Exception e) {
            log.warn("Tự bỏ group trong exam_test.dart lỗi: {}", e.getMessage());
        }
    }

    private int braceDelta(String line) {
        int delta = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') delta++;
            else if (c == '}') delta--;
        }
        return delta;
    }

    /**
     * Dart test machine output trả tên đầy đủ gồm cả group, vd "A. User model TC_MODEL_01".
     * Rubric thường chỉ khai key "TC_MODEL_01"; tự vá grader cũ để lookup theo key rubric thật.
     */
    private void normalizeGraderRubricKeys(Path testcaseDir) {
        Path f = testcaseDir.resolve("grader.dart");
        try {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            if (src.contains("rubricKeyFor(") || !src.contains("testDone") || !src.contains("matrix")) return;

            String patched = src;
            boolean changed = false;
            if (patched.contains("final name = idToName[id] ?? '';")) {
                patched = patched.replace(
                        "final name = idToName[id] ?? '';",
                        "final rawName = idToName[id] ?? '';\n          final name = rubricKeyFor(rawName);");
                changed = true;
            }
            if (patched.contains("final name = testIdToName[id] ?? '';")) {
                patched = patched.replace(
                        "final name = testIdToName[id] ?? '';",
                        "final rawName = testIdToName[id] ?? '';\n        final name = rubricKeyFor(rawName);");
                changed = true;
            }
            if (!changed) return;

            patched = patched
                    .replace("if (name.isEmpty || name.startsWith('loading ') || hidden)",
                             "if (rawName.isEmpty || rawName.startsWith('loading ') || hidden)")
                    .replace("if (name.isEmpty || name.startsWith('loading ') || hidden) continue;",
                             "if (rawName.isEmpty || rawName.startsWith('loading ') || hidden) continue;");

            String withHelper = insertRubricKeyHelper(patched);
            if (withHelper.equals(patched)) {
                log.warn("Không tự vá được grader.dart để normalize rubric key: {}", f);
                return;
            }
            Files.writeString(f, withHelper, StandardCharsets.UTF_8);
            log.info("Đã tự vá grader.dart để normalize tên testcase theo skills_matrix.json: {}", f);
        } catch (Exception e) {
            log.warn("Tự vá grader.dart để normalize rubric key lỗi: {}", e.getMessage());
        }
    }

    private String insertRubricKeyHelper(String src) {
        String helper = """

  String rubricKeyFor(String rawName) {
    if (matrix.containsKey(rawName)) return rawName;

    // Flutter machine output ghép tên group vào tên test, vd:
    // "A. User model TC_MODEL_01". Rubric chỉ dùng key "TC_MODEL_01".
    final matches = RegExp(r'TC_[A-Za-z0-9_]+').allMatches(rawName).toList();
    for (final m in matches.reversed) {
      final key = m.group(0)!;
      if (matrix.containsKey(key)) return key;
    }
    return rawName;
  }
""";
        for (String marker : List.of("  // 3)", "  final lines = result.stdout")) {
            int i = src.indexOf(marker);
            if (i >= 0) return src.substring(0, i) + helper + "\n" + src.substring(i);
        }
        return src;
    }

    /**
     * Tối ưu runner layered-v9 ngay lúc upload để mọi batch dùng cùng chính sách an toàn.
     *
     * Runner cũ đã gom test theo nhóm nhưng lại chạy lại TỪNG testcase fail. Với bài thiếu
     * nhiều file, hàng chục lần khởi động Flutter nối tiếp nhau làm vượt timeout của Docker.
     * Kết quả nhóm đã đủ để chấm; retry từng ID chỉ dành cho chẩn đoán nên bỏ khỏi đường nóng.
     * Đồng thời thay Process.run bằng wrapper có timeout để một process Flutter bị treo tự kết thúc.
     */
    private void normalizeGraderExecution(Path testcaseDir) {
        Path f = testcaseDir.resolve("grader.dart");
        try {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            String patched = src;
            if (patched.contains("failedNames")
                    && patched.contains("Future<Map<String, _TestRun>> _runFlutterGroup")) {
                Pattern retryBlock = Pattern.compile(
                        "(?s)\\n    var runs = await _runWidgetGroup\\(names\\);.*?\\n    for \\(final id in names\\) \\{");
                Matcher matcher = retryBlock.matcher(patched);
                if (matcher.find()) {
                    patched = matcher.replaceFirst(Matcher.quoteReplacement(
                            "\n    final runs = await _runWidgetGroup(names);\n"
                                    + "    for (final id in names) {"));
                }
            }

            if (patched.contains("Process.run(")
                    && patched.contains("Future<Map<String, _TestRun>> _runFlutterGroup")
                    && !patched.contains("Future<ProcessResult> _runProcess(")) {
                if (patched.contains("import 'dart:io';")) {
                    patched = patched.replace("import 'dart:io';",
                            "import 'dart:async';\nimport 'dart:io';");
                }
                patched = patched.replace("Process.run(", "_runProcess(");

                String helper = """

Future<ProcessResult> _runProcess(
  String executable,
  List<String> arguments, {
  Map<String, String>? environment,
  String? workingDirectory,
  bool runInShell = false,
}) async {
  const limit = Duration(seconds: %d);
  final process = await Process.start(
    executable,
    arguments,
    environment: environment,
    workingDirectory: workingDirectory,
    runInShell: runInShell,
  );
  final stdoutFuture = process.stdout.transform(utf8.decoder).join();
  final stderrFuture = process.stderr.transform(utf8.decoder).join();
  try {
    final code = await process.exitCode.timeout(limit);
    return ProcessResult(
      process.pid,
      code,
      await stdoutFuture,
      await stderrFuture,
    );
  } on TimeoutException {
    process.kill();
    final out = await stdoutFuture.timeout(
      const Duration(seconds: 2),
      onTimeout: () => '',
    );
    final err = await stderrFuture.timeout(
      const Duration(seconds: 2),
      onTimeout: () => '',
    );
    return ProcessResult(
      process.pid,
      -1,
      out,
      '${err}\\nGRADER_PROCESS_TIMEOUT after %ds',
    );
  }
}
""".formatted(runnerProcessTimeoutSeconds, runnerProcessTimeoutSeconds);

                int marker = patched.indexOf("Future<void> main()");
                if (marker >= 0) {
                    patched = patched.substring(0, marker) + helper + "\n" + patched.substring(marker);
                }
            }

            if (!patched.equals(src)) {
                Files.writeString(f, patched, StandardCharsets.UTF_8);
                log.info("Đã tối ưu grader.dart: bỏ retry hàng loạt, timeout process {}s: {}",
                        runnerProcessTimeoutSeconds, f);
            }
        } catch (Exception e) {
            log.warn("Tự tối ưu execution của grader.dart lỗi: {}", e.getMessage());
        }
    }

    /**
     * CHẶN testcase import package KHÔNG có trong môi trường chấm (chỉ có những gì khai trong
     * pubspec.base.yaml: flutter, flutter_test...). Đây là nguyên nhân khiến CẢ LỚP bị 0/0 oan
     * (vd đề import 'package:intl/intl.dart' → exam_test.dart không biên dịch được). Bắt ngay lúc upload.
     */
    private void validateTestcaseImports(Path testcaseDir) throws Exception {
        Set<String> allowed = allowedPackages();
        Set<String> bad = missingTestcasePackages(testcaseDir, allowed);
        if (!bad.isEmpty())
            throw new IllegalArgumentException(missingPackageMessage(bad, allowed, null));
    }

    /**
     * Khi upload testcase, nếu đề import package chưa có trong môi trường chấm thì tự thêm vào
     * pubspec.base.yaml và cập nhật ảnh grading-base ngay. Nếu Docker/pub get lỗi, báo rõ tên package
     * để GV thêm thủ công ở trang "Thư viện chấm".
     */
    private void ensureTestcaseImportsAvailable(Path testcaseDir) throws Exception {
        Set<String> allowed = allowedPackages();
        Set<String> missing = missingTestcasePackages(testcaseDir, allowed);
        if (missing.isEmpty()) return;

        synchronized (envLock) {
            if (envBuilding)
                throw new IllegalStateException("Môi trường chấm đang cập nhật thư viện, vui lòng đợi build xong rồi upload lại testcase.");
            envBuilding = true;
            setEnv("RESOLVING", "Testcase cần thêm thư viện: " + String.join(", ", missing), "");
        }
        try {
            List<Map<String, Object>> desired = editablePackagesWithMissing(missing);
            doApplyPackagesBlocking(desired);
        } catch (Exception e) {
            throw new IllegalArgumentException(missingPackageMessage(missing, allowed, e.getMessage()));
        } finally {
            envBuilding = false;
        }

        Set<String> stillMissing = missingTestcasePackages(testcaseDir, allowedPackages());
        if (!stillMissing.isEmpty())
            throw new IllegalArgumentException(missingPackageMessage(stillMissing, allowedPackages(), null));
    }

    private Set<String> missingTestcasePackages(Path testcaseDir, Set<String> allowed) throws Exception {
        java.util.regex.Pattern p =
                java.util.regex.Pattern.compile("import\\s+['\"]package:([A-Za-z0-9_]+)/");
        Set<String> bad = new LinkedHashSet<>();
        List<Path> dartFiles;
        try (Stream<Path> s = Files.walk(testcaseDir)) {
            dartFiles = s.filter(f -> f.toString().endsWith(".dart")).toList();
        }
        for (Path f : dartFiles) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                java.util.regex.Matcher m = p.matcher(line);
                if (m.find() && !allowed.contains(m.group(1))) bad.add(m.group(1));
            }
        }
        return bad;
    }

    private List<Map<String, Object>> editablePackagesWithMissing(Set<String> missing) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> p : listManagedPackages()) {
            if (Boolean.TRUE.equals(p.get("protected"))) continue;
            String name = String.valueOf(p.getOrDefault("name", "")).trim();
            if (name.isBlank()) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("name", name);
            Object version = p.get("version");
            if (version != null && !String.valueOf(version).isBlank()) copy.put("version", version);
            byName.put(name, copy);
        }
        for (String name : missing) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", name);
            p.put("version", ""); // để flutter pub add resolve version tương thích SDK trong ảnh nền
            byName.putIfAbsent(name, p);
        }
        return new ArrayList<>(byName.values());
    }

    private String missingPackageMessage(Set<String> missing, Set<String> allowed, String cause) {
        StringBuilder msg = new StringBuilder();
        msg.append("Testcase dùng package chưa có trong môi trường chấm: ")
           .append(String.join(", ", missing))
           .append(". Backend đã thử tự thêm vào grading-base nhưng chưa thành công.");
        if (cause != null && !cause.isBlank()) msg.append(" Chi tiết: ").append(cause);
        msg.append(" Hãy vào trang \"Thư viện chấm\" thêm package: ")
           .append(String.join(", ", missing))
           .append(" rồi đợi trạng thái READY, hoặc thêm vào grader-base/pubspec.base.yaml và build/cập nhật lại ảnh nền. ")
           .append("Môi trường hiện có: ").append(String.join(", ", allowed)).append(".");
        return msg.toString();
    }

    /** Tập package được phép = các dependency khai trong pubspec.base.yaml (nguồn sự thật của ảnh nền). */
    private Set<String> allowedPackages() {
        Set<String> allowed = new HashSet<>(Set.of("flutter", "flutter_test"));
        try {
            Path pubspec = locateTemplateDir().resolve("pubspec.base.yaml");
            if (Files.exists(pubspec)) {
                boolean inDeps = false;
                java.util.regex.Pattern dep = java.util.regex.Pattern.compile("^ {2}([A-Za-z0-9_]+)\\s*:");
                for (String raw : Files.readAllLines(pubspec, StandardCharsets.UTF_8)) {
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
        List<Map<String, Object>> problems = syllabusService.validateSkillsMatrix(
                Files.readString(f, StandardCharsets.UTF_8));
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
