package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Chạy thử bộ testcase sinh ra trong ẢNH NỀN chấm để VERIFY trước khi đưa cho giảng viên.
 *
 * <p>TỐI ƯU TỐC ĐỘ: mở 1 {@link CompileSession} cho CẢ phiên tạo đề — giữ MỘT container "ấm"
 * (sleep infinity) và {@code docker exec} vào đó cho mỗi lần kiểm thử. Nhờ đó:
 *   • không tốn chi phí tạo/destroy container mỗi lần,
 *   • Flutter GIỮ cache biên dịch (.dart_tool/build) giữa các lần ⇒ các lần sau nhanh hơn nhiều
 *     (rất hợp với sinh-theo-lô gọi compile nhiều vòng).
 * Nếu không mở được container ấm (hoặc lỗi runtime) → TỰ fallback về cách cũ (docker run mỗi lần).
 */
@Slf4j
@Component
public class TestcaseCompiler {

    @Value("${grader.base-image:grading-base:latest}")
    private String baseImage;

    /** CPU/RAM cho khâu compile-check — để CAO hơn lúc chấm để biên dịch nhanh hơn. */
    @Value("${grader.ai.compile-cpus:4.0}")
    private String compileCpus;

    @Value("${grader.ai.compile-memory:3072m}")
    private String compileMemory;

    @Value("${grader.ai.compile-timeout-seconds:150}")
    private int compileTimeout;

    private static final Pattern PLUS  = Pattern.compile("\\+(\\d+)");
    private static final Pattern MINUS = Pattern.compile("-(\\d+)");
    private static final AtomicLong SEQ = new AtomicLong();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Dọn container compile "ai-compile-*" còn sót từ lần chạy trước (vd backend bị kill giữa chừng). */
    @PostConstruct
    void cleanupStale() {
        try {
            Process p = new ProcessBuilder("docker", "ps", "-aq", "--filter", "name=ai-compile")
                    .redirectErrorStream(true).start();
            List<String> ids = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) if (!l.isBlank()) ids.add(l.trim());
            }
            p.waitFor(30, TimeUnit.SECONDS);
            for (String id : ids) runQuiet(List.of("docker", "rm", "-f", id));
            if (!ids.isEmpty()) log.info("Đã dọn {} container compile sót từ lần chạy trước", ids.size());
        } catch (Exception e) {
            log.debug("cleanupStale bỏ qua: {}", e.getMessage());
        }
    }

    // ── Phiên compile (mở 1 lần cho cả lần tạo đề) ───────────────
    public CompileSession openSession() {
        String cid = "ai-compile-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet();
        boolean warm = false;
        try {
            int rc = runQuiet(List.of("docker", "run", "-d", "--name", cid,
                    "--memory", compileMemory, "--cpus", compileCpus,
                    baseImage, "sleep", "infinity"));
            warm = rc == 0;
            if (warm) log.info("AI compile: container ấm {} sẵn sàng (tăng tốc compile-check)", cid);
        } catch (Exception e) {
            log.warn("Không mở được container ấm (fallback docker run mỗi lần): {}", e.getMessage());
        }
        return new CompileSession(warm ? cid : null);
    }

    public class CompileSession implements AutoCloseable {
        private final String cid;   // null = không có container ấm → fallback docker run

        CompileSession(String cid) { this.cid = cid; }

        public CompileResult checkSolution(GenArtifacts art) {
            if (!art.hasSolution())
                return CompileResult.fail("Thiếu LỜI GIẢI MẪU (solution/lib).",
                        "Hãy kèm 'solution' (lib/*.dart) là lời giải đúng để mọi test PASS.", "");
            return run(art.solution(), art);
        }

        public CompileResult checkStarter(GenArtifacts art) {
            if (!art.hasStarter())
                return CompileResult.fail("Thiếu KHUNG CODE (starter/lib).",
                        "Hãy kèm 'starter' (lib/*.dart) biên dịch sạch để phát SV.", "");
            return run(art.starter(), art);
        }

        private CompileResult run(Map<String, String> libFiles, GenArtifacts art) {
            if (cid != null) {
                try { return runWarm(cid, libFiles, art); }
                catch (Exception e) { log.warn("warm compile lỗi → fallback cold: {}", e.getMessage()); }
            }
            try { return runCold(libFiles, art); }
            catch (Exception e) { return CompileResult.fail("Lỗi khi chạy thử: " + e.getMessage(), e.toString(), ""); }
        }

        @Override public void close() {
            if (cid == null) return;
            Thread.interrupted();   // xoá cờ ngắt (nếu job bị Dừng) để lệnh dọn container không bị bỏ giữa chừng
            try { runQuiet(List.of("docker", "rm", "-f", cid)); } catch (Exception ignored) {}
        }
    }

    // ── WARM: exec vào container đang sống ───────────────────────
    private CompileResult runWarm(String cid, Map<String, String> libFiles, GenArtifacts art) throws Exception {
        // Xoá lib/test cũ nhưng GIỮ .dart_tool/build để compile lần sau tăng tốc.
        execCapture(cid, "rm -rf /app/lib /app/test && mkdir -p /app/lib /app/test", 30);

        for (var e : libFiles.entrySet()) {
            String rel = sanitizeRel(e.getKey());
            if (rel != null) writeFile(cid, rel, e.getValue());
        }
        writeFile(cid, "test/exam_test.dart", art.examTest());
        writeFile(cid, "test/grader.dart", art.graderDart());
        writeFile(cid, "test/skills_matrix.json", art.skillsMatrix());

        List<String> cmd = List.of("docker", "exec", cid, "bash", "-c",
                "cd /app && flutter test --no-pub --reporter json test/exam_test.dart 2>&1");
        return execTestcase(cmd);
    }

    // ── COLD: docker run mới mỗi lần (fallback) ──────────────────
    private CompileResult runCold(Map<String, String> libFiles, GenArtifacts art) throws Exception {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("ai-gen-");
            Path libDir  = Files.createDirectories(tmp.resolve("lib"));
            Path testDir = Files.createDirectories(tmp.resolve("test"));
            for (var e : libFiles.entrySet()) {
                String rel = sanitizeRel(e.getKey());
                if (rel == null) continue;
                Path f = libDir.resolve(rel.substring(4)).normalize();   // bỏ "lib/"
                if (!f.startsWith(libDir)) continue;
                Files.createDirectories(f.getParent());
                Files.writeString(f, e.getValue() == null ? "" : e.getValue());
            }
            Files.writeString(testDir.resolve("exam_test.dart"), art.examTest());
            Files.writeString(testDir.resolve("grader.dart"), art.graderDart());
            Files.writeString(testDir.resolve("skills_matrix.json"), art.skillsMatrix());

            List<String> cmd = new ArrayList<>(List.of(
                    "docker", "run", "--rm", "--memory", compileMemory, "--cpus", compileCpus,
                    "-v", dockerPath(libDir)  + ":/app/lib",
                    "-v", dockerPath(testDir) + ":/app/test",
                    "--entrypoint", "bash", baseImage,
                    "-c", "cd /app && flutter test --no-pub --reporter json test/exam_test.dart 2>&1"));
            return execTestcase(cmd);
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** Chạy lệnh `flutter test`, đọc output ở thread riêng để áp được timeout (tránh treo). */
    private CompileResult execTestcase(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            } catch (Exception ignored) {}
        });
        reader.start();
        boolean finished = p.waitFor(compileTimeout, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            reader.join(2000);
            return CompileResult.fail("Chạy thử quá thời gian (" + compileTimeout + "s).",
                    "Test có thể treo (animation/timer vô hạn, pumpAndSettle không dừng).", out.toString());
        }
        reader.join();
        return parse(out.toString());
    }

    // ── Parse output flutter test (reporter json; fallback compact) ──
    private CompileResult parse(String log) {
        boolean compiled = !looksLikeCompileError(log);
        int passed = 0, failed = 0;
        List<String> failedNames = new ArrayList<>();
        boolean allPassed;

        if (!compiled) {
            allPassed = false;
        } else {
            int[] pf = new int[2];
            boolean sawJson = parseJsonResults(log, failedNames, pf);
            if (sawJson) {
                passed = pf[0]; failed = pf[1];
                allPassed = failed == 0 && passed > 0;
            } else {
                // Fallback: reporter compact (phòng khi json không hỗ trợ) — không có tên test fail.
                allPassed = log.contains("All tests passed!");
                passed = lastMax(PLUS, log);
                failed = lastMax(MINUS, log);
                if (allPassed && failed == 0 && passed == 0) passed = Math.max(passed, 0);
            }
        }
        int total = passed + failed;

        String summary;
        if (!compiled)      summary = "Lỗi biên dịch (exam_test.dart hoặc lib không compile).";
        else if (allPassed) summary = "Biên dịch OK, " + passed + "/" + Math.max(total, passed) + " test PASS.";
        else                summary = "Biên dịch OK nhưng còn " + failed + " test FAIL (chưa nhất quán).";

        String errors = compiled && allPassed ? "" : extractErrors(log);
        return new CompileResult(compiled, allPassed, passed, total, summary, errors, log, failedNames);
    }

    /**
     * Đọc các event JSON của reporter (package:test): testStart (id→name), testDone (result, hidden).
     * Điền {@code failedNames} + passed/failed vào {@code pf[0]/pf[1]}. Trả false nếu KHÔNG có event nào
     * (→ caller fallback compact). Bỏ qua test ẩn và test "loading ...".
     */
    private boolean parseJsonResults(String log, List<String> failedNames, int[] pf) {
        Map<Integer, String> id2name = new HashMap<>();
        int passed = 0, failed = 0;
        boolean saw = false;
        for (String line : log.split("\n")) {
            String s = line.trim();
            if (s.length() < 2 || s.charAt(0) != '{') continue;
            JsonNode n;
            try { n = mapper.readTree(s); } catch (Exception e) { continue; }
            String type = n.path("type").asText("");
            if ("testStart".equals(type)) {
                JsonNode t = n.path("test");
                int id = t.path("id").asInt(-1);
                if (id >= 0) id2name.put(id, t.path("name").asText(""));
            } else if ("testDone".equals(type)) {
                if (n.path("hidden").asBoolean(false)) continue;
                String name = id2name.getOrDefault(n.path("testID").asInt(-1), "");
                if (name.startsWith("loading ")) continue;
                saw = true;
                if ("success".equals(n.path("result").asText(""))) passed++;
                else { failed++; if (!name.isBlank()) failedNames.add(name); }
            }
        }
        pf[0] = passed; pf[1] = failed;
        return saw;
    }

    private boolean looksLikeCompileError(String log) {
        String[] markers = {
                "Error: ", "Compilation failed", "Failed to load", "compilation failed",
                "Error when reading", "errors found", "Test file failed to compile",
                "isn't defined", "Undefined name", "isn't a function", "No named parameter", "Expected to find"
        };
        for (String m : markers) if (log.contains(m)) return true;
        return false;
    }

    private String extractErrors(String log) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String raw : log.split("\n")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            boolean meaningful = t.contains("Error:") || t.contains("Exception")
                    || t.contains("Expected:") || t.contains("Actual:")
                    || t.contains("isn't defined") || t.contains("isn't a")
                    || t.contains("Undefined") || t.contains("No named parameter")
                    || t.contains("error •") || t.contains(".dart:")
                    || t.contains("Some tests failed") || t.contains("EXCEPTION");
            if (!meaningful) continue;
            sb.append(t.length() > 240 ? t.substring(0, 240) : t).append('\n');
            if (++n >= 25) break;
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) {
            String[] arr = log.split("\n");
            int from = Math.max(0, arr.length - 20);
            StringBuilder tail = new StringBuilder();
            for (int i = from; i < arr.length; i++) tail.append(arr[i]).append('\n');
            s = tail.toString().trim();
        }
        return s.length() > 3000 ? s.substring(0, 3000) + "\n…(cắt bớt)" : s;
    }

    private int lastMax(Pattern p, String log) {
        Matcher m = p.matcher(log);
        int max = 0;
        while (m.find()) { try { max = Math.max(max, Integer.parseInt(m.group(1))); } catch (Exception ignored) {} }
        return max;
    }

    // ── Ghi file vào container qua stdin (né lỗi quoting/path Windows) ──
    private void writeFile(String cid, String rel, String content) throws Exception {
        String full = "/app/" + rel;
        String dir = full.substring(0, full.lastIndexOf('/'));
        List<String> cmd = List.of("docker", "exec", "-i", cid, "bash", "-c",
                "mkdir -p '" + dir + "' && cat > '" + full + "'");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var w = p.getOutputStream()) {
            w.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            w.flush();
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (r.readLine() != null) { /* drain */ }
        }
        p.waitFor(30, TimeUnit.SECONDS);
    }

    /** rel an toàn dạng "lib/<path>" (chỉ chữ/số/_/./-, không '..'); null nếu không hợp lệ. */
    private String sanitizeRel(String key) {
        if (key == null) return null;
        String k = key.replace('\\', '/').replaceFirst("^/+", "");
        if (!k.startsWith("lib/")) k = "lib/" + k;
        if (k.contains("..") || !k.matches("lib/[A-Za-z0-9_./-]+")) return null;
        return k;
    }

    // ── Docker low-level ─────────────────────────────────────────
    private String execCapture(String cid, String script, int timeoutSec) throws Exception {
        List<String> cmd = List.of("docker", "exec", cid, "bash", "-c", script);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        p.waitFor(timeoutSec, TimeUnit.SECONDS);
        return out.toString();
    }

    private int runQuiet(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (r.readLine() != null) { /* drain */ }
        }
        return p.waitFor(60, TimeUnit.SECONDS) ? p.exitValue() : -1;
    }

    private String dockerPath(Path path) {
        String s = path.toAbsolutePath().toString();
        if (s.length() >= 2 && s.charAt(1) == ':')
            return "/" + Character.toLowerCase(s.charAt(0)) + s.substring(2).replace("\\", "/");
        return s.replace("\\", "/");
    }

    private void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }
}
