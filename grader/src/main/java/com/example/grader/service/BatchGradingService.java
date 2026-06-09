package com.example.grader.service;

import com.example.grader.dto.BatchProgressResponse;
import com.example.grader.dto.BatchSubmitResponse;
import com.example.grader.entity.*;
import com.example.grader.repository.ExamRepository;
import com.example.grader.repository.ExamResultRepository;
import com.example.grader.repository.GradingBatchRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.Stream;

@Slf4j
@Service
public class BatchGradingService {

    @Value("${grader.max.concurrent:3}")
    private int maxConcurrent;

    /** Lưu lại file zip bài nộp để audit/chấm lại khi tranh chấp. */
    @Value("${grader.save-submissions:true}")
    private boolean saveSubmissions;
    @Value("${grader.submissions-dir:submissions}")
    private String submissionsDir;
    /** Số ngày giữ bài nộp (<=0 = giữ mãi). Dọn tự động hằng ngày. */
    @Value("${grader.submissions-retention-days:30}")
    private int submissionsRetentionDays;

    @Autowired
    private GradingService           gradingService;
    @Autowired private ExamService          examService;
    @Autowired private ExamResultRepository resultRepo;
    @Autowired private GradingBatchRepository batchRepo;
    @Autowired private ExamRepository examRepo;
    @Autowired private SyllabusService    syllabusService;
    @Autowired private CompetencyService  competencyService;

    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutorService executor;
    private final BlockingQueue<GradingJob> jobQueue = new LinkedBlockingQueue<>();

    record GradingJob(String studentId, String studentName,
                      String batchId, String examId, String zipPath) {}
    record StudentInfo(String studentId, String studentName) {}

    @PostConstruct
    public void startWorkers() {
        executor = Executors.newFixedThreadPool(maxConcurrent);
        for (int i = 0; i < maxConcurrent; i++) {
            final int idx = i;
            executor.submit(() -> {
                Thread.currentThread().setName("grading-worker-" + idx);
                while (!Thread.currentThread().isInterrupted()) {
                    GradingJob job = null;
                    try {
                        job = jobQueue.take();
                        processJob(job);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable t) {
                        // QUAN TRỌNG: KHÔNG để 1 job lỗi giết chết worker (nếu chết thì các bài
                        // sau sẽ kẹt QUEUED vĩnh viễn). Ghi log rồi tiếp tục nhận job kế tiếp.
                        log.error("Worker gặp lỗi ngoài dự kiến khi xử lý job {}: {}",
                                job != null ? job.studentId() : "?", t.toString(), t);
                    }
                }
            });
        }
        log.info("Grading workers started (concurrent: {})", maxConcurrent);
        recoverPendingJobs();   // hàng đợi bền: nạp lại job QUEUED/GRADING sau restart
        try {                   // Flag Pattern: bật has_results cho dữ liệu cũ (chạy 1 lần)
            int n = examRepo.backfillHasResults();
            if (n > 0) log.info("Đã backfill cờ has_results cho {} đề có sẵn bài chấm", n);
        } catch (Exception e) {
            log.warn("Backfill has_results lỗi: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }

    // ── GV upload hàng loạt ──────────────────────────────────────
    public BatchSubmitResponse enqueueBatch(List<MultipartFile> files,
                                            String examId, String createdBy) throws Exception {
        Exam exam = examRepo.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề thi: " + examId));
        if (exam.getStatus() != ExamStatus.READY)
            throw new IllegalStateException("Đề thi chưa READY: " + exam.getStatus());

        String batchId = "BATCH_" + System.currentTimeMillis();
        GradingBatch batch = new GradingBatch();
        batch.setBatchId(batchId);
        batch.setExamId(examId);
        batch.setTotalFiles(files.size());
        batch.setCreatedBy(createdBy);
        batchRepo.save(batch);

        List<String> queued      = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();
        List<GradingJob> pendingJobs = new ArrayList<>();   // enqueue SAU khi chốt totalFiles

        for (MultipartFile file : files) {
            String filename = Objects.requireNonNull(file.getOriginalFilename());
            try {
                validateZip(file, filename);
                StudentInfo info = parseStudentInfo(filename);

                // Chấm lại = GHI ĐÈ: tái sử dụng bản ghi cũ (cùng SV + đề + mode) nếu có
                ExamResult placeholder = resultRepo
                        .findByStudentIdAndExamIdAndMode(info.studentId(), examId, "submit")
                        .orElseGet(ExamResult::new);

                // Vẫn chặn TRÙNG mã SV trong CÙNG một lần upload
                if (batchId.equals(placeholder.getBatchId()))
                    throw new IllegalArgumentException(
                            "Trùng mã SV " + info.studentId() + " trong cùng lần upload");

                // Stream zip ra ĐĨA ngay (KHÔNG giữ bytes trong RAM) → tránh OOM khi nhiều bài
                Path zip = stageZip(file, examId, batchId, info.studentId());

                placeholder.setStudentId(info.studentId());
                placeholder.setStudentName(info.studentName());
                placeholder.setExamId(examId);
                placeholder.setBatchId(batchId);
                placeholder.setStatus(GradingStatus.QUEUED);
                placeholder.setScore(null);     // reset kết quả lần chấm trước
                placeholder.setDetails(null);
                placeholder.setErrorLog(null);
                resultRepo.save(placeholder);

                pendingJobs.add(new GradingJob(
                        info.studentId(), info.studentName(),
                        batchId, examId, zip.toString()
                ));
                queued.add(info.studentId() + " — " + info.studentName());

            } catch (Exception e) {
                parseErrors.add(filename + ": " + e.getMessage());
                log.warn("Skip {}: {}", filename, e.getMessage());
            }
        }

        // totalFiles = SỐ BÀI THỰC SỰ ĐƯA VÀO CHẤM (không tính file bị loại) → batch hoàn tất đúng
        batch.setTotalFiles(pendingJobs.size());
        batchRepo.save(batch);

        // Lưu lại đúng testcase đã dùng cho đợt chấm này (audit / đối chiếu khi nghi ngờ chấm sai)
        if (!pendingJobs.isEmpty()) snapshotTestcase(examId, batchId, exam.getTestcasePath());

        // Đưa vào hàng đợi SAU khi đã chốt totalFiles (để worker không hoàn tất trước khi totalFiles đúng)
        pendingJobs.forEach(jobQueue::add);

        // Tất cả file đều bị loại → đóng batch ngay (không có job nào để kích hoạt checkBatchComplete)
        if (pendingJobs.isEmpty()) {
            batch.setStatus(BatchStatus.COMPLETED);
            batch.setCompletedAt(Instant.now());
            batchRepo.save(batch);
        }

        log.info("[{}] Enqueued {}/{}", batchId, pendingJobs.size(), files.size());
        return new BatchSubmitResponse(batchId, pendingJobs.size(), parseErrors);
    }

    // ── Xử lý 1 job (TUYỆT ĐỐI không ném exception ra ngoài → worker không bao giờ chết) ──
    private void processJob(GradingJob job) {
        boolean success = false;
        try {
            log.info("[{}] Chấm: {}", job.batchId(), job.studentId());
            updateStatus(job, GradingStatus.GRADING, null, null, null);

            Path zipPath = Path.of(job.zipPath());     // file đã staged trên đĩa
            if (!Files.exists(zipPath))
                throw new IllegalStateException("Mất file bài nộp: " + zipPath);

            Path tempDir = Files.createTempDirectory("grading_" + job.studentId() + "_");
            String testcasePath = examRepo.findByExamId(job.examId())
                    .map(Exam::getTestcasePath).orElse(null);
            String resultJson = gradingService.gradeSubmission(
                    job.studentId(), job.examId(), testcasePath, tempDir, zipPath);

            float score = parseScore(resultJson);
            String fullJson = assembleResultJson(job, resultJson);   // JSON đầy đủ cho AI
            updateStatus(job, GradingStatus.DONE, score, resultJson, fullJson);
            examRepo.markHasResults(job.examId());   // Flag Pattern: đề này đã có bài chấm xong
            log.info("[{}] DONE {} → {} đ", job.batchId(), job.studentId(), score);
            success = true;

        } catch (Exception e) {
            // errorLog KHÔNG được rỗng: nếu exception thiếu message thì dùng e.toString() (tên lớp)
            String emsg = (e.getMessage() != null && !e.getMessage().isBlank()) ? e.getMessage() : e.toString();
            log.error("[{}] ERROR {} → {}", job.batchId(), job.studentId(), emsg);
            try { updateStatus(job, GradingStatus.ERROR, 0f, null, null); }
            catch (Exception ex) { log.warn("[{}] Không ghi được ERROR cho {}: {}", job.batchId(), job.studentId(), ex.getMessage()); }
            updateErrorLog(job, emsg);               // đã tự bọc try/catch + cắt ngắn bên trong
        } finally {
            // Bookkeeping luôn chạy & có bọc lỗi → 1 lỗi ghi DB không làm kẹt batch
            try { if (!saveSubmissions) deleteQuietly(Path.of(job.zipPath())); } catch (Exception ignored) {}
            try { batchRepo.incrementCounts(job.batchId(), success ? 1 : 0, success ? 0 : 1); }
            catch (Exception ex) { log.warn("[{}] incrementCounts lỗi: {}", job.batchId(), ex.getMessage()); }
            try { checkBatchComplete(job.batchId()); }
            catch (Exception ex) { log.warn("[{}] checkBatchComplete lỗi: {}", job.batchId(), ex.getMessage()); }
        }
    }

    private void updateStatus(GradingJob job, GradingStatus status, Float score, String details, String fullJson) {
        resultRepo.findByStudentIdAndBatchId(job.studentId(), job.batchId()).ifPresent(r -> {
            r.setStatus(status);
            if (score    != null) r.setScore(score);
            if (details  != null) r.setDetails(details);
            if (fullJson != null) r.setResultJson(fullJson);
            resultRepo.save(r);
        });
    }

    // ── Ghép JSON đầy đủ cho AI đọc: student + exam + kết quả chấm ─
    private String assembleResultJson(GradingJob job, String graderJson) {
        try {
            JsonNode g = mapper.readTree(graderJson);
            Exam exam = examRepo.findByExamId(job.examId()).orElse(null);
            String title = (exam != null && exam.getExamName() != null) ? exam.getExamName() : job.examId();

            Map<String, Object> root = new LinkedHashMap<>();

            Map<String, Object> student = new LinkedHashMap<>();
            student.put("id", job.studentId());
            student.put("name", job.studentName() != null ? job.studentName() : "");
            root.put("student", student);

            Map<String, Object> examNode = new LinkedHashMap<>();
            examNode.put("code", job.examId());
            examNode.put("title", title);
            examNode.put("total_score", 10);
            root.put("exam", examNode);

            // grading_result: ưu tiên từ grader (mới); fallback dựng từ field cũ
            if (g.has("grading_result")) {
                root.put("grading_result", mapper.convertValue(g.get("grading_result"), Object.class));
            } else {
                int pass  = g.path("soTestPass").asInt(0);
                int total = g.path("tongSoTest").asInt(0);
                Map<String, Object> gr = new LinkedHashMap<>();
                gr.put("score", g.path("diem").asDouble(0));
                gr.put("passed_tests", pass);
                gr.put("failed_tests", total - pass);
                gr.put("total_tests", total);
                root.put("grading_result", gr);
            }

            // test_cases: ưu tiên từ grader (mới, có skill/expected/actual); fallback từ chiTiet
            List<Map<String, Object>> testCases = new ArrayList<>();
            if (g.has("test_cases")) {
                testCases = toListOfMap(g.get("test_cases"));
            } else if (g.has("chiTiet")) {
                for (JsonNode c : g.get("chiTiet")) {
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("test_id", c.path("name").asText());
                    tc.put("name", c.path("name").asText());
                    tc.put("status", "PASS".equals(c.path("status").asText()) ? "passed" : "failed");
                    testCases.add(tc);
                }
            }
            // Bổ sung skill_code/difficulty từ skills_matrix.json của đề (nguồn sự thật) cho mỗi testcase
            enrichTestCases(testCases, exam);

            // Gắn nhãn KIẾN THỨC (skill_name/category/category_label) + ĐỘ KHÓ cho từng testcase,
            // rồi tính NĂNG LỰC theo category — dùng chung 1 resolver.
            try {
                SyllabusService.Resolver resolver = syllabusService.resolver();
                competencyService.annotateTestCases(testCases, resolver);
                if (!testCases.isEmpty()) root.put("test_cases", testCases);
                List<Map<String, Object>> comp = competencyService.assess(testCases, resolver);
                if (!comp.isEmpty()) root.put("competency_assessment", comp);
            } catch (Exception ce) {
                if (!testCases.isEmpty()) root.put("test_cases", testCases);
                log.warn("Tính competency/annotate lỗi cho {}: {}", job.studentId(), ce.getMessage());
            }

            if (g.has("analyze_result"))
                root.put("analyze_result", mapper.convertValue(g.get("analyze_result"), Object.class));

            root.put("teacher_note", (exam != null && exam.getTeacherNote() != null) ? exam.getTeacherNote() : "");

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Không dựng được result_json cho {}: {}", job.studentId(), e.getMessage());
            return null;
        }
    }

    /** Chuyển JsonNode mảng test_cases → List&lt;Map&gt; để enrich & tính năng lực. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toListOfMap(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            try { out.add(mapper.convertValue(n, Map.class)); } catch (Exception ignored) {}
        }
        return out;
    }

    /**
     * Bổ sung skill_code / difficulty / skill (tên hiển thị) cho mỗi testcase, đọc từ
     * skills_matrix.json của đề. Chỉ điền khi testcase CHƯA có (không ghi đè dữ liệu grader).
     */
    @SuppressWarnings("unchecked")
    private void enrichTestCases(List<Map<String, Object>> tcs, Exam exam) {
        if (tcs.isEmpty() || exam == null || exam.getTestcasePath() == null) return;
        Map<String, Object> matrix;
        try {
            Path f = Path.of(exam.getTestcasePath()).resolve("skills_matrix.json");
            if (!Files.exists(f)) return;
            matrix = mapper.convertValue(mapper.readTree(Files.readString(f)), Map.class);
        } catch (Exception e) {
            return;
        }
        for (Map<String, Object> tc : tcs) {
            Object meta = matrix.get(String.valueOf(tc.get("test_id")));
            if (meta instanceof Map<?, ?> m) {
                putIfAbsent(tc, "skill_code", m.get("skill_code"));
                putIfAbsent(tc, "difficulty", m.get("difficulty"));
                putIfAbsent(tc, "skill",      m.get("skill"));
            }
        }
    }

    private void putIfAbsent(Map<String, Object> tc, String key, Object val) {
        if (val == null) return;
        Object cur = tc.get(key);
        if (cur == null || String.valueOf(cur).isBlank()) tc.put(key, val);
    }

    /** Giới hạn độ dài log lỗi để không phình DB / không tràn cột. */
    private static final int MAX_ERROR_LOG = 60_000;

    private void updateErrorLog(GradingJob job, String msg) {
        try {
            String text = msg == null ? "" : msg;
            if (text.length() > MAX_ERROR_LOG)
                text = text.substring(0, MAX_ERROR_LOG) + "\n…(đã cắt bớt log lỗi)";
            final String finalText = text;
            resultRepo.findByStudentIdAndBatchId(job.studentId(), job.batchId())
                    .ifPresent(r -> { r.setErrorLog(finalText); resultRepo.save(r); });
        } catch (Exception e) {
            // Tuyệt đối KHÔNG để việc ghi log lỗi (vd cột error_log quá nhỏ) làm chết worker
            log.warn("[{}] Không ghi được error_log cho {}: {}", job.batchId(), job.studentId(), e.getMessage());
        }
    }

    private void checkBatchComplete(String batchId) {
        batchRepo.findByBatchId(batchId).ifPresent(b -> {
            int done  = b.getDoneCount()  != null ? b.getDoneCount()  : 0;
            int error = b.getErrorCount() != null ? b.getErrorCount() : 0;
            int total = b.getTotalFiles() != null ? b.getTotalFiles() : 0;
            if (done + error >= total) {
                b.setStatus(error == 0 ? BatchStatus.COMPLETED : BatchStatus.PARTIAL);
                b.setCompletedAt(Instant.now());
                batchRepo.save(b);
                log.info("[{}] Batch hoàn tất {}/{}", batchId, done, total);
            }
        });
    }

    // ── Staging zip ra đĩa (cũng là nơi lưu audit) ──────────────
    /** Thư mục của 1 batch: submissions/&lt;đề&gt;/&lt;batch&gt;/ (chứa zip bài nộp + snapshot testcase). */
    private Path batchDir(String examId, String batchId) {
        return examService.resolveSibling(submissionsDir).resolve(examId).resolve(batchId);
    }

    private Path stagedZipPath(String examId, String batchId, String studentId) {
        return batchDir(examId, batchId).resolve(studentId + ".zip");
    }

    /**
     * Snapshot testcase ĐANG dùng vào folder batch (submissions/&lt;đề&gt;/&lt;batch&gt;/_testcase/) để
     * sau này đối chiếu / tra lại đúng bộ đề đã chấm, kể cả khi GV upload đè testcase mới. Rất nhẹ.
     */
    private void snapshotTestcase(String examId, String batchId, String testcasePath) {
        try {
            if (testcasePath == null || testcasePath.isBlank()) return;
            Path src = Path.of(testcasePath);
            if (!Files.exists(src)) return;
            Path dst = batchDir(examId, batchId).resolve("_testcase");
            Files.createDirectories(dst);
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.toList()) {
                    Path target = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else {
                        if (target.getParent() != null) Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[{}] Snapshot testcase lỗi: {}", batchId, e.getMessage());
        }
    }

    /** Stream zip ra đĩa (KHÔNG nạp toàn bộ vào RAM) → trả về đường dẫn. */
    private Path stageZip(MultipartFile file, String examId, String batchId, String studentId) throws Exception {
        Path zip = stagedZipPath(examId, batchId, studentId);
        Files.createDirectories(zip.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
        }
        return zip;
    }

    private void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    // ── Hàng đợi bền: nạp lại job QUEUED/GRADING từ DB sau restart ─
    private void recoverPendingJobs() {
        try {
            List<ExamResult> pending = resultRepo.findByStatusIn(
                    List.of(GradingStatus.QUEUED, GradingStatus.GRADING));
            int recovered = 0;
            for (ExamResult r : pending) {
                Path zip = stagedZipPath(r.getExamId(), r.getBatchId(), r.getStudentId());
                if (Files.exists(zip)) {
                    r.setStatus(GradingStatus.QUEUED);
                    resultRepo.save(r);
                    jobQueue.add(new GradingJob(r.getStudentId(), r.getStudentName(),
                            r.getBatchId(), r.getExamId(), zip.toString()));
                    recovered++;
                } else {
                    r.setStatus(GradingStatus.ERROR);
                    r.setErrorLog("Mất file bài nộp khi khôi phục sau restart");
                    resultRepo.save(r);
                }
            }
            if (recovered > 0) log.info("♻️ Khôi phục {} bài đang chờ sau restart", recovered);
        } catch (Exception e) {
            log.warn("Khôi phục hàng đợi lỗi: {}", e.getMessage());
        }
    }

    // ── Dọn bài nộp cũ hơn N ngày (chạy 3h sáng mỗi ngày) ───────
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldSubmissions() {
        if (!saveSubmissions || submissionsRetentionDays <= 0) return;
        try {
            Path base = examService.resolveSibling(submissionsDir);
            if (!Files.exists(base)) return;
            Instant cutoff = Instant.now().minus(Duration.ofDays(submissionsRetentionDays));
            int[] deleted = {0};
            try (var walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                            Files.delete(p);
                            deleted[0]++;
                        }
                    } catch (Exception ignored) {}
                });
            }
            if (deleted[0] > 0)
                log.info("🧹 Đã dọn {} bài nộp cũ hơn {} ngày", deleted[0], submissionsRetentionDays);
        } catch (Exception e) {
            log.warn("Dọn bài nộp cũ lỗi: {}", e.getMessage());
        }
    }

    // ── Progress cho GV ──────────────────────────────────────────
    /** Thông báo: các phiên chấm gần đây (của GV nếu có email, không thì toàn hệ thống). */
    public List<GradingBatch> recentBatches(String createdBy) {
        return (createdBy == null || createdBy.isBlank())
                ? batchRepo.findTop10ByOrderByCreatedAtDesc()
                : batchRepo.findTop10ByCreatedByOrderByCreatedAtDesc(createdBy);
    }

    /** Đọc các file mã nguồn trong bài nộp (zip đã staged) để hiển thị cho GV chấm tay. */
    public List<Map<String, String>> readSubmissionFiles(String examId, String studentId) {
        List<Map<String, String>> out = new ArrayList<>();
        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit").orElse(null);
        if (r == null || r.getBatchId() == null) return out;
        Path zip = stagedZipPath(examId, r.getBatchId(), studentId);
        if (!Files.exists(zip)) return out;

        final int MAX_FILES = 40, MAX_BYTES = 80_000;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null && out.size() < MAX_FILES) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                String lower = name.toLowerCase();
                if (!(lower.endsWith(".dart") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                        || lower.endsWith(".json") || lower.endsWith(".md") || lower.endsWith(".txt"))) continue;
                if (lower.contains("/.dart_tool/") || lower.contains("/build/") || lower.contains("/.git/")) continue;
                // Đọc CÓ GIỚI HẠN (readNBytes) → 1 entry khổng lồ không thể làm OOM
                byte[] buf = zis.readNBytes(MAX_BYTES + 1);
                String content = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                if (buf.length > MAX_BYTES) content = content + "\n… (đã cắt bớt)";
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("content", content);
                out.add(m);
            }
        } catch (Exception ex) {
            log.warn("Đọc file bài nộp {}/{} lỗi: {}", examId, studentId, ex.getMessage());
        }
        // .dart lên đầu, rồi theo tên
        out.sort((a, b) -> {
            boolean ad = a.get("name").toLowerCase().endsWith(".dart");
            boolean bd = b.get("name").toLowerCase().endsWith(".dart");
            if (ad != bd) return ad ? -1 : 1;
            return a.get("name").compareTo(b.get("name"));
        });
        return out;
    }

    /**
     * Đọc các file TESTCASE đã dùng để chấm 1 SV (để đối chiếu khi bài bị 0/0). Ưu tiên snapshot
     * của batch đã chấm (_testcase/); nếu chưa có (batch cũ) thì đọc testcase HIỆN TẠI của đề.
     */
    public List<Map<String, String>> readTestcaseFiles(String examId, String studentId) {
        List<Map<String, String>> out = new ArrayList<>();
        Path dir = null;

        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit").orElse(null);
        if (r != null && r.getBatchId() != null) {
            Path snap = batchDir(examId, r.getBatchId()).resolve("_testcase");
            if (Files.exists(snap)) dir = snap;
        }
        if (dir == null) {
            String tc = examRepo.findByExamId(examId).map(Exam::getTestcasePath).orElse(null);
            if (tc != null && !tc.isBlank() && Files.exists(Path.of(tc))) dir = Path.of(tc);
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
                String content = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
                if (content.length() > MAX_BYTES) content = content.substring(0, MAX_BYTES) + "\n… (đã cắt bớt)";
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("content", content);
                out.add(m);
            }
        } catch (Exception ex) {
            log.warn("Đọc testcase {}/{} lỗi: {}", examId, studentId, ex.getMessage());
        }
        out.sort((a, b) -> rankTestcaseFile(a.get("name")) - rankTestcaseFile(b.get("name")));
        return out;
    }

    /** exam_test.dart → skills_matrix.json → grader.dart → còn lại (cho dễ đối chiếu). */
    private int rankTestcaseFile(String name) {
        String n = name.toLowerCase();
        if (n.endsWith("exam_test.dart"))     return 0;
        if (n.endsWith("skills_matrix.json")) return 1;
        if (n.endsWith("grader.dart"))        return 2;
        return 3;
    }

    /**
     * CHẤM LẠI 1 bài từ zip ĐÃ LƯU (không cần upload lại). Tạo batch mới (số liệu sạch + để lại
     * dấu vết chấm lại), copy zip + snapshot testcase hiện tại, rồi đưa vào hàng đợi. Trả batchId mới.
     */
    public String regradeStudent(String examId, String studentId) throws Exception {
        Exam exam = examRepo.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề thi: " + examId));
        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit")
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bài nộp của " + studentId));
        if (r.getBatchId() == null)
            throw new IllegalArgumentException("Bài nộp thiếu batchId — không thể chấm lại.");

        Path oldZip = stagedZipPath(examId, r.getBatchId(), studentId);
        if (!Files.exists(oldZip))
            throw new IllegalArgumentException("Bài nộp không còn được lưu trên đĩa — không thể chấm lại.");

        String newBatchId = "BATCH_" + System.currentTimeMillis();
        GradingBatch batch = new GradingBatch();
        batch.setBatchId(newBatchId);
        batch.setExamId(examId);
        batch.setTotalFiles(1);
        batch.setCreatedBy("regrade");
        batchRepo.save(batch);

        // Copy zip sang folder batch mới + snapshot testcase đang dùng (audit lần chấm lại)
        Path newZip = stagedZipPath(examId, newBatchId, studentId);
        Files.createDirectories(newZip.getParent());
        Files.copy(oldZip, newZip, StandardCopyOption.REPLACE_EXISTING);
        snapshotTestcase(examId, newBatchId, exam.getTestcasePath());

        // Ghi đè chính bản ghi này: trỏ sang batch mới, reset kết quả lần chấm trước
        r.setBatchId(newBatchId);
        r.setStatus(GradingStatus.QUEUED);
        r.setScore(null);
        r.setDetails(null);
        r.setErrorLog(null);
        r.setResultJson(null);
        resultRepo.save(r);

        jobQueue.add(new GradingJob(studentId, r.getStudentName(), newBatchId, examId, newZip.toString()));
        log.info("[{}] Chấm lại {} (đề {})", newBatchId, studentId, examId);
        return newBatchId;
    }

    public BatchProgressResponse getBatchProgress(String batchId) {
        List<ExamResult> all = resultRepo.findByBatchIdOrderByStudentId(batchId);
        long done    = all.stream().filter(r -> r.getStatus() == GradingStatus.DONE).count();
        long grading = all.stream().filter(r -> r.getStatus() == GradingStatus.GRADING).count();
        long queued  = all.stream().filter(r -> r.getStatus() == GradingStatus.QUEUED).count();
        long error   = all.stream().filter(r -> r.getStatus() == GradingStatus.ERROR).count();
        return new BatchProgressResponse(batchId, all.size(), done, grading, queued, error, all);
    }

    // ── Helpers ──────────────────────────────────────────────────
    private float parseScore(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            if (n.has("diem"))  return (float) n.get("diem").asDouble();
            if (n.has("score")) return (float) n.get("score").asDouble();
        } catch (Exception ignored) {}
        return 0f;
    }

    private StudentInfo parseStudentInfo(String filename) {
        String name = filename.replace(".zip","").trim();
        int sep = name.indexOf('_');
        if (sep < 1) throw new IllegalArgumentException("Sai format — cần: MaSV_Ten.zip");
        return new StudentInfo(
                name.substring(0, sep).trim().toUpperCase(),
                name.substring(sep + 1).replace("_", " ").trim()
        );
    }

    private void validateZip(MultipartFile f, String name) throws Exception {
        if (!name.toLowerCase().endsWith(".zip")) throw new IllegalArgumentException("Chỉ nhận .zip");
        if (f.isEmpty())                          throw new IllegalArgumentException("File rỗng");
        if (f.getSize() > 50L * 1024 * 1024)     throw new IllegalArgumentException("Quá 50MB");
    }
}
