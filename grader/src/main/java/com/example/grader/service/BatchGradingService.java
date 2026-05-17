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
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

@Slf4j
@Service
public class BatchGradingService {

    @Value("${grader.max.concurrent:3}")
    private int maxConcurrent;

    @Autowired
    private GradingService           gradingService;
    @Autowired private ExamResultRepository resultRepo;
    @Autowired private GradingBatchRepository batchRepo;
    @Autowired private ExamRepository examRepo;

    private final ObjectMapper mapper = new ObjectMapper();
    private Semaphore semaphore;
    private ExecutorService executor;
    private final BlockingQueue<GradingJob> jobQueue = new LinkedBlockingQueue<>();

    record GradingJob(String studentId, String studentName,
                      String batchId, String examId, byte[] zipBytes) {}
    record StudentInfo(String studentId, String studentName) {}

    @PostConstruct
    public void startWorkers() {
        semaphore = new Semaphore(maxConcurrent);
        executor  = Executors.newFixedThreadPool(maxConcurrent + 1);
        executor.submit(() -> {
            Thread.currentThread().setName("grading-dispatcher");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    GradingJob job = jobQueue.take();
                    semaphore.acquire();
                    executor.submit(() -> {
                        try { processJob(job); } finally { semaphore.release(); }
                    });
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        });
        log.info("Grading workers started (concurrent: {})", maxConcurrent);
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

                ExamResult placeholder = new ExamResult();
                placeholder.setStudentId(info.studentId());
                placeholder.setStudentName(info.studentName());
                placeholder.setExamId(examId);
                placeholder.setBatchId(batchId);
                placeholder.setStatus(GradingStatus.QUEUED);
                resultRepo.save(placeholder);

                jobQueue.add(new GradingJob(
                        info.studentId(), info.studentName(),
                        batchId, examId, file.getBytes()
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
        updateStatus(job, GradingStatus.GRADING, null, null);

        boolean success = false;
        try {
            Path tempDir = Files.createTempDirectory("grading_" + job.studentId() + "_");
            Path zipPath = tempDir.resolve("submission.zip");
            Files.write(zipPath, job.zipBytes());

            String resultJson = gradingService.gradeSubmission(
                    job.studentId(), job.examId(), tempDir, zipPath);

            float score = parseScore(resultJson);
            updateStatus(job, GradingStatus.DONE, score, resultJson);
            log.info("[{}] DONE {} → {} đ", job.batchId(), job.studentId(), score);
            success = true;

        } catch (Exception e) {
            log.error("[{}] ERROR {} → {}", job.batchId(), job.studentId(), e.getMessage());
            updateStatus(job, GradingStatus.ERROR, 0f, null);
            updateErrorLog(job, e.getMessage());
        }

        batchRepo.incrementCounts(job.batchId(), success ? 1 : 0, success ? 0 : 1);
        checkBatchComplete(job.batchId());
    }

    private void updateStatus(GradingJob job, GradingStatus status, Float score, String details) {
        resultRepo.findByStudentIdAndBatchId(job.studentId(), job.batchId()).ifPresent(r -> {
            r.setStatus(status);
            if (score   != null) r.setScore(score);
            if (details != null) r.setDetails(details);
            resultRepo.save(r);
        });
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
