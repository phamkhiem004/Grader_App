package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bóc tách phản hồi LLM (1 object JSON) cho 2 pha sinh đề:
 *  PHA A {@link #parse}        : { "exam_test.dart", "skills_matrix.json"(obj|str), "solution"{lib/..}, "assumptions" }
 *  PHA B {@link #parseHandout} : { "de_bai", "starter"{lib/..} }
 * grader.dart KHÔNG do LLM sinh (backend chèn bản chuẩn). Khoan dung với vài biến thể tên khóa.
 */
@Component
public class ArtifactParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public static class ParseException extends Exception {
        public ParseException(String m) { super(m); }
    }

    /** Sản phẩm PHA B: đề bài phát SV + khung code starter. */
    public record Handout(String deBai, Map<String, String> starter) {}

    /**
     * Lô THÊM (delta) ở pha A: chỉ phần MỚI để backend ghép vào bộ đang có.
     *  - {@code newTests}     : chuỗi các hàm test()/testWidgets() MỚI (ghép vào trong main()).
     *  - {@code newTopLevel}  : (hiếm) class phụ MỚI ở top-level (ghép trước main()).
     *  - {@code newImports}   : (hiếm) import file lib mới.
     *  - {@code matrixEntries}: các entry MỚI cho skills_matrix.json.
     *  - {@code solutionPatch}: các file lib/ cần thêm/sửa để test mới PASS.
     */
    public record Delta(String newTests, String newTopLevel, java.util.List<String> newImports,
                        JsonNode matrixEntries, Map<String, String> solutionPatch) {}

    /** Tổng quan skills_matrix (dùng cho sinh theo lô): số testcase, danh sách mã, skill_code đã phủ. */
    public record MatrixInfo(int count, java.util.List<String> ids, java.util.Set<String> skills) {}

    /** Đếm số testcase trong skills_matrix.json (số khóa cấp 1). 0 nếu lỗi. */
    public int countTestcases(String skillsMatrix) {
        try {
            JsonNode n = mapper.readTree(skillsMatrix);
            return n != null && n.isObject() ? n.size() : 0;
        } catch (Exception e) { return 0; }
    }

    public MatrixInfo matrixInfo(String skillsMatrix) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.Set<String> skills = new java.util.LinkedHashSet<>();
        try {
            JsonNode n = mapper.readTree(skillsMatrix);
            if (n != null && n.isObject())
                n.fields().forEachRemaining(e -> {
                    ids.add(e.getKey());
                    JsonNode sc = e.getValue().get("skill_code");
                    if (sc != null && !sc.asText().isBlank()) skills.add(sc.asText());
                });
        } catch (Exception ignored) {}
        return new MatrixInfo(ids.size(), ids, skills);
    }

    // ── PHA A: testcase + lời giải mẫu ───────────────────────────
    public GenArtifacts parse(String raw) throws ParseException {
        JsonNode root = readObject(raw);

        String examTest = text(root, "exam_test.dart", "exam_test", "examTest");
        String matrix   = jsonToString(firstNode(root, "skills_matrix.json", "skills_matrix", "skillsMatrix"));
        String assumptions = text(root, "assumptions", "notes", "contract");
        Map<String, String> solution = libMap(firstNode(root, "solution", "solution_lib", "lib"));

        if (examTest == null || examTest.isBlank())
            throw new ParseException("Thiếu 'exam_test.dart' trong phản hồi.");
        if (matrix == null || matrix.isBlank())
            throw new ParseException("Thiếu 'skills_matrix.json' trong phản hồi.");

        return new GenArtifacts(examTest.strip(), null, matrix.strip(), solution, null, null,
                assumptions == null ? "" : assumptions.strip());
    }

    // ── PHA A (lô thêm): bóc delta + gộp matrix ──────────────────
    public Delta parseDelta(String raw) throws ParseException {
        JsonNode root = readObject(raw);
        String newTests = text(root, "new_tests", "newTests", "tests");
        if (newTests == null || newTests.isBlank())
            throw new ParseException("Thiếu 'new_tests' (các test mới) trong lô thêm.");
        String newTop = text(root, "new_top_level", "newTopLevel", "top_level");
        java.util.List<String> imports = stringList(firstNode(root, "new_imports", "newImports", "imports"));
        JsonNode matrix = asObjectNode(firstNode(root, "skills_matrix.json", "skills_matrix", "new_matrix", "matrix"));
        Map<String, String> sol = libMap(firstNode(root, "solution_patch", "solution", "solution_lib", "solutionPatch"));
        return new Delta(newTests.strip(), newTop, imports, matrix, sol);
    }

    /** Gộp các entry MỚI vào skills_matrix.json hiện có (ghi đè nếu trùng mã). Hỏng thì giữ nguyên. */
    public String mergeMatrix(String existing, JsonNode delta) {
        try {
            JsonNode baseNode = mapper.readTree(existing == null || existing.isBlank() ? "{}" : existing);
            com.fasterxml.jackson.databind.node.ObjectNode base = baseNode.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) baseNode : mapper.createObjectNode();
            if (delta != null && delta.isObject())
                delta.fields().forEachRemaining(e -> base.set(e.getKey(), e.getValue()));
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(base);
        } catch (Exception e) {
            return existing;
        }
    }

    /** Node object (parse nếu bị JSON-hóa thành chuỗi); null nếu không phải object. */
    private JsonNode asObjectNode(JsonNode n) {
        if (n == null) return null;
        if (n.isTextual()) { try { return mapper.readTree(n.asText()); } catch (Exception e) { return null; } }
        return n.isObject() ? n : null;
    }

    private java.util.List<String> stringList(JsonNode n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (n != null && n.isArray()) n.forEach(x -> { if (x.isTextual() && !x.asText().isBlank()) out.add(x.asText()); });
        else if (n != null && n.isTextual() && !n.asText().isBlank()) out.add(n.asText());
        return out;
    }

    // ── PHA B: đề bài + khung code ───────────────────────────────
    public Handout parseHandout(String raw) throws ParseException {
        JsonNode root = readObject(raw);
        String deBai = text(root, "de_bai", "đề_bài", "problem", "exam_brief", "deBai");
        Map<String, String> starter = libMap(firstNode(root, "starter", "starter_lib", "lib", "skeleton"));
        if (starter.isEmpty())
            throw new ParseException("Thiếu 'starter' (khung code lib/) trong phản hồi.");
        return new Handout(deBai == null ? "" : deBai.strip(), starter);
    }

    // ── Helpers ──────────────────────────────────────────────────
    private JsonNode readObject(String raw) throws ParseException {
        JsonNode root;
        try { root = mapper.readTree(stripFences(raw)); }
        catch (Exception e) { throw new ParseException("Phản hồi KHÔNG phải JSON hợp lệ: " + e.getMessage()); }
        if (root == null || !root.isObject())
            throw new ParseException("Phản hồi không phải object JSON.");
        return root;
    }

    /** Map các file lib/ (giá trị là nội dung file); chuẩn hóa key về dạng "lib/...". */
    private Map<String, String> libMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String name = e.getKey().replace('\\', '/').trim();
                if (!name.startsWith("lib/")) name = "lib/" + name.replaceFirst("^/+", "");
                out.put(name, e.getValue().asText(""));
            });
        }
        return out;
    }

    /** Object → chuỗi JSON pretty; nếu là chuỗi đã JSON-hóa thì parse rồi pretty lại. */
    private String jsonToString(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        try {
            if (n.isTextual()) {
                JsonNode reparsed = mapper.readTree(n.asText());
                return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(reparsed);
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (Exception e) {
            return n.isTextual() ? n.asText() : n.toString();
        }
    }

    private String text(JsonNode root, String... keys) {
        JsonNode n = firstNode(root, keys);
        return n == null ? null : n.asText(null);
    }

    private JsonNode firstNode(JsonNode root, String... keys) {
        for (String k : keys) {
            JsonNode n = root.get(k);
            if (n != null && !n.isNull()) return n;
        }
        return null;
    }

    /** Gỡ rào ```json ... ``` nếu model lỡ bọc; cắt phần thừa ngoài { ... }. */
    private String stripFences(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);
        }
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a >= 0 && b > a) return t.substring(a, b + 1);
        return t.trim();
    }
}
