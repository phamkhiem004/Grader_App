package com.example.grader.service;

import com.example.grader.dto.ExamOption;
import com.example.grader.dto.StatisticsResponse;
import com.example.grader.dto.StatisticsResponse.Bucket;
import com.example.grader.dto.StatisticsResponse.TrendPoint;
import com.example.grader.entity.ExamResult;
import com.example.grader.entity.GradingStatus;
import com.example.grader.repository.ExamRepository;
import com.example.grader.repository.ExamResultRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class StatisticsService {

    /** Ngưỡng điểm đạt (hệ 10) — cấu hình qua GRADER_PASS_THRESHOLD. */
    @Value("${grader.pass-threshold:5}")
    private int passThreshold;

    @Autowired private ExamResultRepository resultRepo;
    @Autowired private ExamRepository examRepo;

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd/MM");
    private static final String[] RANGES = {"0-2", "2-4", "4-6", "6-8", "8-10"};

    // ── Danh sách đề cho dropdown lọc (CHỈ đề đã có bài chấm xong) ──
    // Flag Pattern: đọc thẳng cờ has_results trên bảng exams (index) → O(log N),
    // không quét/đếm bảng exam_results như trước.
    public List<ExamOption> getExamOptions() {
        List<ExamOption> out = new ArrayList<>();
        examRepo.findByHasResultsTrueOrderByExamNameAsc().forEach(e -> out.add(new ExamOption(
                e.getExamId(),
                e.getExamName() != null ? e.getExamName() : e.getExamId())));
        return out;
    }

    // ── Tổng hợp số liệu ────────────────────────────────────────
    public StatisticsResponse getStatistics(String examId) {
        boolean all = examId == null || examId.isBlank() || examId.equalsIgnoreCase("ALL");
        List<ExamResult> results = all ? resultRepo.findAll() : resultRepo.findByExamId(examId);

        // Chỉ tính bài NỘP CHÍNH THỨC (mode submit), bỏ các lần chạy thử (mode test) → số liệu chính xác
        results = results.stream()
                .filter(r -> r.getMode() == null || "submit".equalsIgnoreCase(r.getMode()))
                .toList();

        long submissions = results.size();                       // số lượt chấm (dòng)
        long total = results.stream()                            // SỐ THÍ SINH thật (distinct)
                .map(ExamResult::getStudentId).distinct().count();
        long graded  = countStatus(results, GradingStatus.DONE);
        long errors  = countStatus(results, GradingStatus.ERROR);
        long pending = countStatus(results, GradingStatus.QUEUED)
                     + countStatus(results, GradingStatus.GRADING);

        // Chỉ tính điểm trên bài đã chấm xong & có điểm
        List<ExamResult> done = results.stream()
                .filter(r -> r.getStatus() == GradingStatus.DONE && r.getScore() != null)
                .toList();

        long passCount = done.stream().filter(r -> r.getScore() >= passThreshold).count();
        long failCount = done.size() - passCount;

        double avgScore = done.stream().mapToDouble(r -> r.getScore()).average().orElse(0);
        double passRate = done.isEmpty() ? 0 : (passCount * 100.0 / done.size());
        double progressPct = submissions == 0 ? 0 : (graded * 100.0 / submissions);

        return new StatisticsResponse(
                all ? "ALL" : examId,
                total, submissions, graded, errors, pending,
                passCount, failCount,
                round1(passRate), round1(avgScore), round1(progressPct),
                passThreshold,
                buildDistribution(done),
                buildTrend(results)
        );
    }

    // ── Phổ điểm 5 khoảng ───────────────────────────────────────
    private List<Bucket> buildDistribution(List<ExamResult> done) {
        long[] counts = new long[RANGES.length];
        for (ExamResult r : done) {
            int idx = (int) Math.floor(r.getScore() / 2.0);
            if (idx < 0) idx = 0;
            if (idx >= RANGES.length) idx = RANGES.length - 1;
            counts[idx]++;
        }
        List<Bucket> out = new ArrayList<>();
        for (int i = 0; i < RANGES.length; i++) out.add(new Bucket(RANGES[i], counts[i]));
        return out;
    }

    // ── Tiến độ chấm 7 ngày gần nhất ────────────────────────────
    private List<TrendPoint> buildTrend(List<ExamResult> results) {
        LocalDate today = LocalDate.now(ZONE);
        Map<LocalDate, long[]> byDay = new HashMap<>(); // [graded, errors]
        for (int i = 6; i >= 0; i--) byDay.put(today.minusDays(i), new long[2]);

        for (ExamResult r : results) {
            Instant ts = r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getSubmittedAt();
            if (ts == null) continue;
            long[] cell = byDay.get(ts.atZone(ZONE).toLocalDate());
            if (cell == null) continue; // ngoài 7 ngày
            if (r.getStatus() == GradingStatus.DONE)       cell[0]++;
            else if (r.getStatus() == GradingStatus.ERROR) cell[1]++;
        }

        List<TrendPoint> out = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long[] cell = byDay.get(d);
            out.add(new TrendPoint(d.format(DAY_FMT), cell[0], cell[1]));
        }
        return out;
    }

    private long countStatus(List<ExamResult> rs, GradingStatus s) {
        return rs.stream().filter(r -> r.getStatus() == s).count();
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
