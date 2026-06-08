package com.example.grader.service;

import com.example.grader.entity.Skill;
import com.example.grader.entity.SkillCategory;
import com.example.grader.service.SyllabusService.Resolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tính ĐÁNH GIÁ NĂNG LỰC theo category từ danh sách test_cases (đã enrich skill_code/difficulty).
 * Thuần Java (không phụ thuộc Jackson) → nhận/ trả List&lt;Map&gt; để gọi từ nơi nào cũng được.
 *
 * Mỗi phần tử competency_assessment:
 *  { category, label, passed_weight, total_weight, ratio, level,
 *    by_difficulty: {basic:"x/y",...}, weak_skills:[...], total_tests, passed_tests }
 */
@Service
public class CompetencyService {

    @Autowired private SyllabusService syllabusService;

    private static final String[] DIFFS = {"basic", "intermediate", "advanced"};

    public List<Map<String, Object>> assess(List<Map<String, Object>> testCases) {
        Resolver resolver = syllabusService.resolver();
        return assess(testCases, resolver);
    }

    /**
     * Gắn nhãn KIẾN THỨC + ĐỘ KHÓ cho từng testcase (mutate tại chỗ) để JSON kết quả nói rõ
     * "testcase này đang test kiến thức gì, khó cỡ nào". Thêm: skill_code (chuẩn hóa về code mới),
     * skill_name, category, category_label, difficulty.
     */
    public void annotateTestCases(List<Map<String, Object>> testCases, Resolver resolver) {
        if (testCases == null) return;
        for (Map<String, Object> tc : testCases) {
            if (tc == null) continue;
            String testId    = str(tc.get("test_id"));
            String skillCode = str(tc.get("skill_code"));
            String legacy    = str(tc.get("skill"));

            String resolved = resolver.resolveSkillCode(testId, skillCode, legacy);
            Skill s = resolver.skill(resolved);
            SkillCategory c = resolver.resolveCategory(testId, skillCode, legacy);

            String diff = normDiff(str(tc.get("difficulty")));
            if (diff == null && s != null) diff = normDiff(s.getDefaultDifficulty());
            if (diff == null) diff = "basic";
            tc.put("difficulty", diff);

            if (resolved != null) tc.put("skill_code", resolved);   // chuẩn hóa code
            if (s != null)        tc.put("skill_name", s.getName());
            if (c != null) {
                tc.put("category", c.getCode());
                String label = c.getCompetencyLabel() != null && !c.getCompetencyLabel().isBlank()
                        ? c.getCompetencyLabel() : c.getName();
                tc.put("category_label", label);
            }
        }
    }

    public List<Map<String, Object>> assess(List<Map<String, Object>> testCases, Resolver resolver) {
        if (testCases == null) return List.of();

        // categoryCode → tích lũy (giữ thứ tự xuất hiện)
        Map<String, Agg> groups = new LinkedHashMap<>();

        for (Map<String, Object> tc : testCases) {
            if (tc == null) continue;
            String testId    = str(tc.get("test_id"));
            String skillCode = str(tc.get("skill_code"));
            String legacy    = str(tc.get("skill"));
            double weight     = toDouble(tc.get("weight"), 1.0);
            boolean passed    = "passed".equalsIgnoreCase(str(tc.get("status")));

            SkillCategory cat = resolver.resolveCategory(testId, skillCode, legacy);
            String catCode  = cat != null ? cat.getCode() : "OTHER";
            String catLabel = cat != null
                    ? (cat.getCompetencyLabel() != null && !cat.getCompetencyLabel().isBlank()
                        ? cat.getCompetencyLabel() : cat.getName())
                    : "Khác";

            Agg agg = groups.computeIfAbsent(catCode, k -> new Agg(catCode, catLabel, cat));

            String diff = normDiff(str(tc.get("difficulty")));
            if (diff == null) {                          // không khai → lấy default của skill nếu giải được
                String resolved = resolver.resolveSkillCode(testId, skillCode, legacy);
                var sk = resolver.skill(resolved);
                diff = sk != null ? normDiff(sk.getDefaultDifficulty()) : null;
            }
            if (diff == null) diff = "basic";

            agg.totalWeight += weight;
            agg.totalTests++;
            agg.diffTotal.merge(diff, 1, Integer::sum);
            if (passed) {
                agg.passedWeight += weight;
                agg.passedTests++;
                agg.diffPass.merge(diff, 1, Integer::sum);
            } else {
                // skill yếu: ưu tiên skill_code chuẩn, fallback tên skill cũ / mã test
                String resolved = resolver.resolveSkillCode(testId, skillCode, legacy);
                String weak = resolved != null ? resolved
                        : (legacy != null && !legacy.isBlank() ? legacy : testId);
                if (weak != null && !agg.weakSkills.contains(weak)) agg.weakSkills.add(weak);
            }
        }

        // Xuất kết quả, sắp theo display_order của category (OTHER xuống cuối)
        List<Agg> ordered = new ArrayList<>(groups.values());
        ordered.sort((a, b) -> Integer.compare(order(a), order(b)));

        List<Map<String, Object>> out = new ArrayList<>();
        for (Agg a : ordered) {
            double ratio = a.totalWeight > 0 ? a.passedWeight / a.totalWeight : 0;
            double weak = a.cat != null && a.cat.getWeakThreshold() != null ? a.cat.getWeakThreshold() : 0.4;
            double good = a.cat != null && a.cat.getGoodThreshold() != null ? a.cat.getGoodThreshold() : 0.7;
            String level = ratio < weak ? "YẾU" : (ratio < good ? "TRUNG BÌNH" : "TỐT");

            Map<String, String> byDiff = new LinkedHashMap<>();
            for (String d : DIFFS) {
                int t = a.diffTotal.getOrDefault(d, 0);
                if (t > 0) byDiff.put(d, a.diffPass.getOrDefault(d, 0) + "/" + t);
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", a.code);
            m.put("label", a.label);
            m.put("passed_weight", round2(a.passedWeight));
            m.put("total_weight", round2(a.totalWeight));
            m.put("ratio", round2(ratio));
            m.put("level", level);
            m.put("passed_tests", a.passedTests);
            m.put("total_tests", a.totalTests);
            m.put("by_difficulty", byDiff);
            m.put("weak_skills", a.weakSkills);
            out.add(m);
        }
        return out;
    }

    private int order(Agg a) {
        if (a.cat == null) return Integer.MAX_VALUE;          // OTHER cuối cùng
        return a.cat.getDisplayOrder() != null ? a.cat.getDisplayOrder() : 0;
    }

    // ── Tích lũy theo category ──────────────────────────────────
    private static class Agg {
        final String code, label;
        final SkillCategory cat;
        double totalWeight = 0, passedWeight = 0;
        int totalTests = 0, passedTests = 0;
        final Map<String, Integer> diffTotal = new LinkedHashMap<>();
        final Map<String, Integer> diffPass  = new LinkedHashMap<>();
        final List<String> weakSkills = new ArrayList<>();
        Agg(String code, String label, SkillCategory cat) { this.code = code; this.label = label; this.cat = cat; }
    }

    // ── Helpers ──────────────────────────────────────────────────
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static double toDouble(Object o, double def) {
        if (o == null) return def;
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return def; }
    }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static String normDiff(String d) {
        if (d == null) return null;
        switch (d.trim().toLowerCase()) {
            case "basic": return "basic";
            case "intermediate": return "intermediate";
            case "advanced": return "advanced";
            default: return null;
        }
    }
}
