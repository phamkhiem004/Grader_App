package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BỎ các testcase FAIL khỏi 1 bộ artifacts để giữ lại phần PASS (thay vì bỏ cả lô):
 *   - cắt block {@code test('<name>', ...)} / {@code testWidgets('<name>', ...)} khỏi exam_test.dart,
 *   - xoá entry tương ứng trong skills_matrix.json.
 * Cắt bằng thao tác chuỗi có CÂN BẰNG NGOẶC (bỏ qua chuỗi/chú thích). Nếu cắt ra mã sai → vòng biên dịch
 * lại ở tầng trên sẽ loại bỏ (an toàn: tệ nhất = quay về hành vi cũ là bỏ cả lô).
 */
@Component
public class TestPruner {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern TEST_KW = Pattern.compile("(?<![A-Za-z0-9_])(testWidgets|test)\\s*\\(");

    /**
     * ĐỐI SOÁT exam_test ↔ skills_matrix: bỏ entry matrix KHÔNG có test tương ứng (tránh "mồ côi" làm SV
     * mất điểm cho test không tồn tại). Chỉ động vào matrix; giữ nguyên exam_test/solution/đề.
     */
    public GenArtifacts reconcile(GenArtifacts art) {
        if (art == null) return art;
        Set<String> names = testNames(art.examTest());
        if (names.isEmpty()) return art;            // không trích được tên → để nguyên cho an toàn
        try {
            JsonNode root = mapper.readTree(art.skillsMatrix() == null || art.skillsMatrix().isBlank() ? "{}" : art.skillsMatrix());
            if (!root.isObject()) return art;
            ObjectNode obj = (ObjectNode) root;
            List<String> orphans = new ArrayList<>();
            Iterator<String> it = obj.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                boolean has = false;
                for (String n : names) if (matchesKey(n, key)) { has = true; break; }
                if (!has) orphans.add(key);
            }
            if (orphans.isEmpty()) return art;
            for (String k : orphans) obj.remove(k);
            String matrix = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
            return new GenArtifacts(art.examTest(), art.graderDart(), matrix, art.solution(),
                    art.starter(), art.deBai(), art.assumptions());
        } catch (Exception e) {
            return art;
        }
    }

    /** Trích tên (đối số chuỗi đầu tiên) của mọi test()/testWidgets() trong exam_test. */
    public Set<String> testNames(String src) {
        Set<String> out = new LinkedHashSet<>();
        if (src == null) return out;
        Matcher m = TEST_KW.matcher(src);
        while (m.find()) {
            int i = m.end();                                  // ngay sau '('
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i++;
            if (i < src.length() && src.charAt(i) == 'r') i++;   // raw string
            if (i >= src.length()) continue;
            char q = src.charAt(i);
            if (q != '\'' && q != '"') continue;
            String name = readStringContent(src, i);
            if (name != null && !name.isBlank()) out.add(name);
        }
        return out;
    }

    private String readStringContent(String s, int i) {
        char q = s.charAt(i);
        boolean triple = i + 2 < s.length() && s.charAt(i + 1) == q && s.charAt(i + 2) == q;
        if (triple) {
            int e = s.indexOf("" + q + q + q, i + 3);
            return e < 0 ? null : s.substring(i + 3, e);
        }
        StringBuilder sb = new StringBuilder();
        int j = i + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\' && j + 1 < s.length()) { sb.append(s.charAt(j + 1)); j += 2; continue; }
            if (c == q) return sb.toString();
            if (c == '\n') return null;
            sb.append(c); j++;
        }
        return null;
    }

    /** Trả artifacts mới đã bỏ các test có tên trong {@code failedNames}; giữ nguyên grader/solution/starter/đề. */
    public GenArtifacts prune(GenArtifacts art, Collection<String> failedNames) {
        if (art == null || failedNames == null || failedNames.isEmpty()) return art;
        Set<String> names = new LinkedHashSet<>();
        for (String n : failedNames) if (n != null && !n.isBlank()) names.add(n.trim());
        if (names.isEmpty()) return art;
        String examTest = removeTestBlocks(art.examTest(), names);
        String matrix   = removeMatrixEntries(art.skillsMatrix(), names);
        return new GenArtifacts(examTest, art.graderDart(), matrix, art.solution(),
                art.starter(), art.deBai(), art.assumptions());
    }

    // ── skills_matrix.json: xoá key khớp tên test fail ───────────
    private String removeMatrixEntries(String matrix, Set<String> failedNames) {
        try {
            JsonNode root = mapper.readTree(matrix == null || matrix.isBlank() ? "{}" : matrix);
            if (!root.isObject()) return matrix;
            ObjectNode obj = (ObjectNode) root;
            List<String> toRemove = new ArrayList<>();
            Iterator<String> it = obj.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                for (String fn : failedNames)
                    if (matchesKey(fn, key)) { toRemove.add(key); break; }
            }
            for (String k : toRemove) obj.remove(k);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return matrix;
        }
    }

    /** Tên test flutter (vd "TC_X - mô tả") khớp key matrix (vd "TC_X") theo BIÊN từ. */
    private boolean matchesKey(String failName, String key) {
        if (failName.equals(key)) return true;
        return failName.startsWith(key + " ") || failName.startsWith(key + "-")
                || failName.startsWith(key + ":") || failName.startsWith(key + "_-");
    }

    // ── exam_test.dart: cắt block test('<name>', ...) ────────────
    private String removeTestBlocks(String examTest, Set<String> failedNames) {
        if (examTest == null) return null;
        String t = examTest;
        for (String name : failedNames) t = removeOneByName(t, name);
        return t;
    }

    private String removeOneByName(String src, String name) {
        int from = 0;
        while (true) {
            int idx = src.indexOf(name, from);
            if (idx < 0) return src;
            int p = idx - 1;                       // ngay trước name phải là nháy mở
            if (p < 0) { from = idx + 1; continue; }
            char q = src.charAt(p);
            if (q != '\'' && q != '"') { from = idx + 1; continue; }
            int after = idx + name.length();       // ngay sau name phải là nháy đóng cùng loại
            if (after >= src.length() || src.charAt(after) != q) { from = idx + 1; continue; }
            p--;                                   // qua nháy mở
            if (p >= 0 && src.charAt(p) == 'r') p--;   // raw string prefix
            while (p >= 0 && Character.isWhitespace(src.charAt(p))) p--;
            if (p < 0 || src.charAt(p) != '(') { from = idx + 1; continue; }
            int open = p;
            p--;
            while (p >= 0 && Character.isWhitespace(src.charAt(p))) p--;
            int idEnd = p + 1;
            while (p >= 0 && isIdentChar(src.charAt(p))) p--;
            String kw = src.substring(p + 1, idEnd);
            if (!kw.equals("test") && !kw.equals("testWidgets")) { from = idx + 1; continue; }
            int kwStart = p + 1;
            int close = matchParen(src, open);
            if (close < 0) { from = idx + 1; continue; }
            int end = close + 1;
            while (end < src.length() && (src.charAt(end) == ' ' || src.charAt(end) == '\t')) end++;
            if (end < src.length() && src.charAt(end) == ';') end++;
            if (end < src.length() && src.charAt(end) == '\r') end++;
            if (end < src.length() && src.charAt(end) == '\n') end++;
            int lineStart = kwStart;               // nuốt khoảng trắng đầu dòng trước kw
            while (lineStart > 0 && (src.charAt(lineStart - 1) == ' ' || src.charAt(lineStart - 1) == '\t')) lineStart--;
            return src.substring(0, lineStart) + src.substring(end);
        }
    }

    /** Tìm ')' khớp với '(' tại {@code open}, BỎ QUA ngoặc nằm trong chuỗi / chú thích. -1 nếu không thấy. */
    private int matchParen(String s, int open) {
        int depth = 0, i = open;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') { i = skipString(s, i); continue; }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int nl = s.indexOf('\n', i); if (nl < 0) return -1; i = nl + 1; continue;
            }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i); if (e < 0) return -1; i = e + 2; continue;
            }
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) return i; }
            i++;
        }
        return -1;
    }

    /** Bỏ qua 1 literal chuỗi bắt đầu tại {@code i} (hỗ trợ ' " và triple-quoted). Trả index NGAY SAU chuỗi. */
    private int skipString(String s, int i) {
        char q = s.charAt(i);
        boolean triple = i + 2 < s.length() && s.charAt(i + 1) == q && s.charAt(i + 2) == q;
        if (triple) {
            String close = "" + q + q + q;
            int e = s.indexOf(close, i + 3);
            return e < 0 ? s.length() : e + 3;
        }
        i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') { i += 2; continue; }   // escape
            if (c == q) return i + 1;
            if (c == '\n') return i;               // chuỗi không đóng (mã lỗi) → dừng an toàn
            i++;
        }
        return i;
    }

    private boolean isIdentChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }
}
