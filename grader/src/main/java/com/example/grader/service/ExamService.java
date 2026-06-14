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

    @Value("${grader.submissions-dir:submissions}")
    private String submissionsDir;

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
        safeId(examId, "đề");
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

        // Bổ sung số bài đã nộp của mỗi đề (cho trang Kho đề biết đề nào còn dữ liệu)
        for (Map<String, Object> m : byId.values()) {
            try { m.put("resultCount", resultRepository.findSubmitStudentIds(String.valueOf(m.get("examId"))).size()); }
            catch (Exception e) { m.put("resultCount", 0); }
        }

        List<Map<String, Object>> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> String.valueOf(a.get("examId")).compareTo(String.valueOf(b.get("examId"))));
        return out;
    }

    /**
     * Đọc các file testcase của 1 ĐỀ (theo examId) để xem/đối chiếu trong trang Kho đề:
     * exam_test.dart, skills_matrix.json, grader.dart... Ưu tiên testcasePath trong DB, fallback đĩa.
     */
    public List<Map<String, String>> readExamTestcaseFiles(String examId) {
        safeId(examId, "đề");
        List<Map<String, String>> out = new ArrayList<>();
        Path dir = null;
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        if (exam != null && exam.getTestcasePath() != null && !exam.getTestcasePath().isBlank()
                && Files.exists(Path.of(exam.getTestcasePath()))) {
            dir = Path.of(exam.getTestcasePath());
        } else {
            Path disk = examsRoot().resolve(examId).resolve("testcase");
            if (Files.exists(disk)) dir = disk;
        }
        if (dir == null) return out;

        final Path base = dir;
        final int MAX_BYTES = 200_000;
        try (Stream<Path> s = Files.walk(base)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String name = base.relativize(p).toString().replace('\\', '/');
                String lower = name.toLowerCase();
                if (!(lower.endsWith(".dart") || lower.endsWith(".json") || lower.endsWith(".yaml")
                        || lower.endsWith(".yml") || lower.endsWith(".md") || lower.endsWith(".txt"))) continue;
                String content = Files.readString(p);
                if (content.length() > MAX_BYTES) content = content.substring(0, MAX_BYTES) + "\n… (đã cắt bớt)";
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("content", content);
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("Đọc testcase đề {} lỗi: {}", examId, e.getMessage());
        }
        out.sort((a, b) -> rankTestcaseFile(a.get("name")) - rankTestcaseFile(b.get("name")));
        return out;
    }

    /** exam_test.dart → skills_matrix.json → grader.dart → còn lại (cho dễ đọc trên UI). */
    private int rankTestcaseFile(String name) {
        String n = name.toLowerCase();
        if (n.endsWith("exam_test.dart"))     return 0;
        if (n.endsWith("skills_matrix.json")) return 1;
        if (n.endsWith("grader.dart"))        return 2;
        return 3;
    }

    // ── Setup đề: chỉ lưu testcase (mount lúc chấm), KHÔNG build image ──
    public ExamSetupResponse setupExam(String examId, String examName,
                                       String teacherNote, MultipartFile testcaseZip) throws Exception {
        safeId(examId, "đề");                 // chặn path traversal khi tạo exams/<examId>/testcase
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
            for (String raw : Files.readAllLines(pubspec)) {
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
        Path pubspec = basePubspec();
        String snapshot = null;
        StringBuilder logBuf = new StringBuilder();
        try {
            // Đảm bảo ĐÃ CÓ ảnh nền (build 1 lần duy nhất nếu máy chưa có); sau đó chỉ CẬP NHẬT tại chỗ.
            ensureBaseImage(locateTemplateDir());

            snapshot = Files.readString(pubspec);

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
                try { Files.writeString(pubspec, snapshot); }     // HOÀN TÁC để không kẹt pubspec hỏng
                catch (Exception ex) { log.warn("Revert pubspec lỗi: {}", ex.getMessage()); }
            }
            setEnv("FAILED", "Lỗi: " + e.getMessage() + " — đã hoàn tác thay đổi.", envLog);
            ExamService.log.warn("❌ Cập nhật thư viện vào ảnh nền thất bại: {}", e.getMessage());
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
        String content = Files.readString(pubspec);
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
        List<String> lines = Files.readAllLines(pubspec);
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
        Files.writeString(pubspec, String.join("\n", out) + "\n");
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
