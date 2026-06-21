package com.example.grader.service.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Toàn bộ sản phẩm sinh ra cho 1 đề thi (qua 2 pha):
 *  PHA A (testcase): {@code examTest}, {@code skillsMatrix}, {@code solution} (lời giải mẫu để verify),
 *                    {@code assumptions}; backend chèn {@code graderDart} chuẩn.
 *  PHA B (bộ phát SV): {@code deBai} (đề bài phát học sinh) + {@code starter} (khung lib/ để SV code vào).
 *
 * Lưu thành đề chỉ gồm 3 file testcase ({@link #testcaseFiles()}); đề bài & starter là tài liệu PHÁT cho SV.
 */
public record GenArtifacts(
        String examTest,
        String graderDart,
        String skillsMatrix,
        Map<String, String> solution,
        Map<String, String> starter,
        String deBai,
        String assumptions) {

    public GenArtifacts withGrader(String grader) {
        return new GenArtifacts(examTest, grader, skillsMatrix, solution, starter, deBai, assumptions);
    }

    /** Gắn sản phẩm PHA B (đề bài + khung code) vào artifacts đã có. */
    public GenArtifacts withHandout(String deBai, Map<String, String> starter) {
        return new GenArtifacts(examTest, graderDart, skillsMatrix, solution, starter, deBai, assumptions);
    }

    public boolean hasCore()     { return notBlank(examTest) && notBlank(skillsMatrix); }
    public boolean hasSolution() { return solution != null && !solution.isEmpty(); }
    public boolean hasStarter()  { return starter != null && !starter.isEmpty(); }

    /** 3 file testcase để lưu thành đề (exam_test + grader + skills_matrix) — KHÔNG gồm lời giải/đề bài/starter. */
    public Map<String, String> testcaseFiles() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("exam_test.dart", examTest);
        m.put("grader.dart", graderDart);
        m.put("skills_matrix.json", skillsMatrix);
        return m;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
