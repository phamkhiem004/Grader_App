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
                    try {
                        GradingJob job = jobQueue.take();
                        processJob(job);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }
        log.info("Grading workers started (concurrent: {})", maxConcurrent);
        recoverPendingJobs();   // hàng đợi bền: nạp lại job QUEUED/GRADING sau restart
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

                jobQueue.add(new GradingJob(
                        info.studentId(), info.studentName(),
                        batchId, examId, zip.toString()
                ));
                queued.add(info.studentId() + " — " + info.studentName());

            } catch (Exception e) {
                parseErrors.add(filename + ": " + e.getMessage());
                log.warn("Skip {}: {}", filename, e.getMessage());
            }
        }

        log.info("[{}] Enqueued {}/{}", batchId, queued.size(), files.size());
        return new BatchSubmitResponse(batchId, queued.size(), parseErrors);
    }

    // ── Xử lý 1 job ─────────────────────────────────────────────
    private void processJob(GradingJob job) {
        log.info("[{}] Chấm: {}", job.batchId(), job.studentId());
        updateStatus(job, GradingStatus.GRADING, null, null, null);

        boolean success = false;
        try {
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
            log.info("[{}] DONE {} → {} đ", job.batchId(), job.studentId(), score);
            success = true;

        } catch (Exception e) {
            log.error("[{}] ERROR {} → {}", job.batchId(), job.studentId(), e.getMessage());
            updateStatus(job, GradingStatus.ERROR, 0f, null, null);
            updateErrorLog(job, e.getMessage());
        }

        // Không lưu audit → xóa file staged sau khi đã ghi trạng thái cuối
        if (!saveSubmissions) deleteQuietly(Path.of(job.zipPath()));

        batchRepo.incrementCounts(job.batchId(), success ? 1 : 0, success ? 0 : 1);
        checkBatchComplete(job.batchId());
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
            if (g.has("test_cases")) {
                root.put("test_cases", mapper.convertValue(g.get("test_cases"), Object.class));
            } else if (g.has("chiTiet")) {
                List<Map<String, Object>> tcs = new ArrayList<>();
                for (JsonNode c : g.get("chiTiet")) {
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("test_id", c.path("name").asText());
                    tc.put("name", c.path("name").asText());
                    tc.put("status", "PASS".equals(c.path("status").asText()) ? "passed" : "failed");
                    tcs.add(tc);
                }
                root.put("test_cases", tcs);
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

    private void updateErrorLog(GradingJob job, String msg) {
        resultRepo.findByStudentIdAndBatchId(job.studentId(), job.batchId())
                .ifPresent(r -> { r.setErrorLog(msg); resultRepo.save(r); });
    }

    private void checkBatchComplete(String batchId) {
        batchRepo.findByBatchId(batchId).ifPresent(b -> {
            if (b.getDoneCount() + b.getErrorCount() >= b.getTotalFiles()) {
                b.setStatus(b.getErrorCount() == 0 ? BatchStatus.COMPLETED : BatchStatus.PARTIAL);
                b.setCompletedAt(Instant.now());
                batchRepo.save(b);
                log.info("[{}] Batch hoàn tất {}/{}", batchId, b.getDoneCount(), b.getTotalFiles());
            }
        });
    }

    // ── Staging zip ra đĩa (cũng là nơi lưu audit) ──────────────
    private Path stagedZipPath(String examId, String batchId, String studentId) {
        return examService.resolveSibling(submissionsDir)
                .resolve(examId).resolve(batchId).resolve(studentId + ".zip");
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
