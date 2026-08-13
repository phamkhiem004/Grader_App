package com.example.grader.service;

import com.example.grader.dto.ExamOption;
import com.example.grader.dto.StatisticsResponse;
import com.example.grader.dto.StatisticsResponse.Bucket;
import com.example.grader.dto.StatisticsResponse.TrendPoint;
import com.example.grader.dto.ResultStat;
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
        String filterExam = all ? null : examId;
        Object[] totals = unwrapRow(resultRepo.aggregateStats(
                filterExam,
                GradingStatus.DONE,
                GradingStatus.ERROR,
                GradingStatus.QUEUED,
                GradingStatus.GRADING,
                GradingStatus.MANUAL_REVIEW,
                passThreshold
        ));

        long submissions = asLong(totals, 0);              // số lượt chấm (dòng)
        long total       = asLong(totals, 1);              // SỐ THÍ SINH thật (distinct)
        long graded      = asLong(totals, 2);
        long errors      = asLong(totals, 3);
        long pending     = asLong(totals, 4) + asLong(totals, 5);
        long manualReview = asLong(totals, 6);
        long passCount   = asLong(totals, 7);
        long failCount   = asLong(totals, 8);
        double avgScore  = asDouble(totals, 9);

        double passRate = graded == 0 ? 0 : (passCount * 100.0 / graded);
        double progressPct = submissions == 0 ? 0
                : ((graded + errors + manualReview) * 100.0 / submissions);
        Instant since = LocalDate.now(ZONE).minusDays(6).atStartOfDay(ZONE).toInstant();
        List<ResultStat> trendRows = resultRepo.findTrendStatsSince(filterExam, since);

        return new StatisticsResponse(
                all ? "ALL" : examId,
                total, submissions, graded, errors, pending, manualReview,
                passCount, failCount,
                round1(passRate), round1(avgScore), round1(progressPct),
                passThreshold,
                buildDistribution(filterExam),
                buildTrend(trendRows)
        );
    }

    // ── Phổ điểm 5 khoảng ───────────────────────────────────────
    private List<Bucket> buildDistribution(String examId) {
        Object[] raw = unwrapRow(resultRepo.scoreBuckets(examId, GradingStatus.DONE));
        List<Bucket> out = new ArrayList<>();
        for (int i = 0; i < RANGES.length; i++) out.add(new Bucket(RANGES[i], asLong(raw, i)));
        return out;
    }

    // ── Tiến độ chấm 7 ngày gần nhất ────────────────────────────
    private List<TrendPoint> buildTrend(List<ResultStat> results) {
        LocalDate today = LocalDate.now(ZONE);
        Map<LocalDate, long[]> byDay = new HashMap<>(); // [graded, errors]
        for (int i = 6; i >= 0; i--) byDay.put(today.minusDays(i), new long[2]);

        for (ResultStat r : results) {
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

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private Object[] unwrapRow(Object raw) {
        if (raw instanceof Object[] arr && arr.length == 1 && arr[0] instanceof Object[] nested) return nested;
        if (raw instanceof Object[] arr) return arr;
        return new Object[0];
    }

    private long asLong(Object[] row, int idx) {
        if (row == null || idx >= row.length || row[idx] == null) return 0;
        if (row[idx] instanceof Number n) return n.longValue();
        return Long.parseLong(row[idx].toString());
    }

    private double asDouble(Object[] row, int idx) {
        if (row == null || idx >= row.length || row[idx] == null) return 0;
        if (row[idx] instanceof Number n) return n.doubleValue();
        return Double.parseDouble(row[idx].toString());
    }
}
