package com.example.grader.service;


import com.example.grader.dto.TestCaseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class GradingService {

    @Value("${grader.image.prefix:grading-env}")
    private String imagePrefix;

    /** Ảnh nền dùng chung — chạy container chấm và mount testcase + lib vào. */
    @Value("${grader.base-image:grading-base:latest}")
    private String baseImage;

    @Value("${grader.timeout.seconds:120}")
    private int timeoutSeconds;

    /** RAM mỗi container chấm. Tăng giúp tránh OOM khi compile project lớn. */
    @Value("${grader.run.memory:2048m}")
    private String runMemory;

    /** Số CPU mỗi container chấm. Tăng giúp `flutter test` compile nhanh hơn. */
    @Value("${grader.run.cpus:2.0}")
    private String runCpus;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Entry point ──────────────────────────────────────────────


    // ── Tìm root project chứa thư mục lib ──────────────────────
    private Path detectProjectRoot(Path extractDir) throws Exception {
        // Kiểm tra ngay ở thư mục gốc xem có thư mục lib không
        if (Files.exists(extractDir.resolve("lib"))) return extractDir;

        // Nếu không có, tìm sâu vào 1 cấp (trường hợp SV nén cả thư mục cha chứa lib)
        List<Path> subDirs;
        try (Stream<Path> s = Files.list(extractDir)) {
            subDirs = s.filter(Files::isDirectory).toList();
        }
        for (Path sub : subDirs) {
            if (Files.exists(sub.resolve("lib"))) return sub;
        }

        // Tìm sâu hơn nữa (đóng Stream để không rò rỉ file descriptor)
        try (Stream<Path> walk = Files.walk(extractDir)) {
            return walk
                    .filter(p -> p.getFileName().toString().equals("lib") && Files.isDirectory(p))
                    .map(Path::getParent)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thư mục lib/ trong file nộp"));
        }
    }

    public String gradeSubmission(String studentId, String examId, String testcasePath,
                                  Path tempDir, Path zipPath) throws Exception {
        Path extractDir = tempDir.resolve("extracted");
        Files.createDirectories(extractDir);

        try {
            unzip(zipPath.toFile(), extractDir.toFile());

            // Bây giờ hàm này sẽ trả về thư mục ngay sát bên ngoài thư mục lib/
            Path projectRoot = detectProjectRoot(extractDir);
            Path studentLib = projectRoot.resolve("lib");

            // Chỉ kiểm tra file .dart bên trong thư mục lib (đóng Stream)
            long dartCount;
            try (Stream<Path> s = Files.walk(studentLib)) {
                dartCount = s.filter(p -> p.toString().endsWith(".dart")).count();
            }
            if (dartCount == 0)
                throw new IllegalArgumentException("Không có file .dart trong lib/");

            log.info("[{}] {} file dart, project root: {}", studentId, dartCount, projectRoot.getFileName());

            return runDockerGrader(
                    studentId, studentLib.toAbsolutePath().toString(),
                    examId, testcasePath
            );
        } finally {
            // QUAN TRỌNG: dọn thư mục tạm KHÔNG được ném lỗi đè lên lý do chấm thật (vd probe
            // mount thư mục này → Windows khóa file → Files.walk có thể ném và che mất lỗi 0/0).
            try { deleteDirectory(tempDir.toFile()); }
            catch (Exception cleanup) {
                log.warn("Dọn thư mục tạm {} lỗi (bỏ qua): {}", tempDir, cleanup.toString());
            }
        }
    }

    // ── Gọi Docker ───────────────────────────────────────────────
    private String runDockerGrader(String studentId, String libPath, String examId, String testcasePath) throws Exception {
        // Tách phần mount + ảnh ra để vừa chạy chấm, vừa tái dùng cho probe chẩn đoán khi 0/0.
        List<String> mounts = new ArrayList<>(List.of(
                "-v", toDockerPath(libPath) + ":/app/lib"));

        // MOUNT mode: mount testcase vào ảnh nền (không cần image riêng cho đề).
        // Fallback (đề cũ): dùng image grading-env-<đề> đã build sẵn.
        boolean mountMode = testcasePath != null && !testcasePath.isBlank()
                && Files.exists(Path.of(testcasePath));
        String image;
        if (mountMode) {
            mounts.add("-v");
            mounts.add(toDockerPath(testcasePath) + ":/app/test");
            image = baseImage;
        } else {
            image = imagePrefix + "-" + examId.toLowerCase();
        }

        String containerName = dockerContainerName("grader-run", examId, studentId);
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--name", containerName, "--rm", "--memory", runMemory, "--cpus", runCpus));
        command.addAll(mounts);
        command.add(image);
        command.add("./run_grader.sh");   // chạy chấm tường minh — không phụ thuộc CMD baked trong ảnh

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread t1 = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) stdout.append(line).append("\n");
            } catch (IOException ignored) {
            }
        });
        Thread t2 = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) stderr.append(line).append("\n");
            } catch (IOException ignored) {
            }
        });
        t1.start();
        t2.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            removeContainerQuietly(containerName);
            throw new RuntimeException("Timeout sau " + timeoutSeconds + "s");
        }

        t1.join();
        t2.join();

        String output = stdout.toString();
        if (process.exitValue() != 0 && !output.contains("GRADE_RESULT_START"))
            throw new RuntimeException("Lỗi biên dịch: " + stderr.toString().trim());

        String resultJson = parseGraderOutput(output);
        // 0/0 (không test nào chạy) → coi là LỖI, không phải DONE; chạy probe để lấy LÝ DO THẬT.
        ensureTestsRan(resultJson, output, mounts, image);
        return resultJson;
    }

    /**
     * Nếu không có testcase nào chạy (tongSoTest == 0) thì gần như chắc chắn bài nộp KHÔNG
     * biên dịch được với testcase (thiếu file/sai tên class) → ném lỗi RÕ RÀNG thay vì để
     * hệ thống hiểu nhầm là "DONE 0 điểm".
     */
    private void ensureTestsRan(String resultJson, String output, List<String> mounts, String image) {
        try {
            JsonNode n = mapper.readTree(resultJson);
            if (n.path("tongSoTest").asInt(-1) == 0) {
                throw new RuntimeException(diagnoseZeroTests(mounts, image, output));
            }
        } catch (RuntimeException re) {
            throw re;                       // ném tiếp lỗi 0/0
        } catch (Exception ignored) {       // JSON lạ → bỏ qua, giữ luồng cũ
        }
    }

    /**
     * Dựng thông điệp lỗi RÕ RÀNG cho trường hợp 0/0. `grader.dart` chạy `flutter test` rồi chỉ đọc
     * stdout nên lý do biên dịch thật (ở stderr của tiến trình con) bị nuốt mất → ta chạy lại
     * `flutter test test/exam_test.dart` (gộp stderr) để moi đúng file/class/hàm sai.
     */
    private String diagnoseZeroTests(List<String> mounts, String image, String mainOutput) {
        String generic = "Bài nộp không chạy được testcase nào (0/0) — thường do thiếu file/sai tên "
                + "class so với đề, hoặc lỗi biên dịch.";
        String probe = runCompileProbe(mounts, image);
        String scan  = probe.isEmpty() ? mainOutput : probe;
        String hint  = extractCompileErrors(scan);

        if (!hint.isEmpty()) {
            return "Bài nộp KHÔNG biên dịch được với đề (thiếu/sai tên file trong lib/, sai tên "
                 + "class/hàm, hoặc lỗi cú pháp). Chi tiết: " + hint;
        }
        // Probe compile sạch & chạy được test nhưng grader tính 0 → tên test ≠ key skills_matrix.json.
        if (probeRanTests(probe)) {
            return "Test biên dịch & chạy được nhưng KHÔNG khớp rubric: tên test trong exam_test.dart "
                 + "không trùng key nào trong skills_matrix.json (phân biệt hoa/thường).";
        }
        return generic;
    }

    /** Chạy lại flutter test (gộp stderr) chỉ để CHẨN ĐOÁN khi đã 0/0 — không tính điểm. */
    private String runCompileProbe(List<String> mounts, String image) {
        String containerName = dockerContainerName("grader-probe", "compile", "zero-tests");
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    "docker", "run", "--name", containerName, "--rm", "--memory", runMemory, "--cpus", runCpus));
            cmd.addAll(mounts);
            cmd.add("--entrypoint");
            cmd.add("bash");
            cmd.add(image);
            cmd.add("-c");
            cmd.add("cd /app && flutter test --no-pub test/exam_test.dart 2>&1");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
            }
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                removeContainerQuietly(containerName);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Probe chẩn đoán 0/0 lỗi: {}", e.getMessage());
            return "";
        }
    }

    /** Output của probe cho thấy test ĐÃ chạy (pass/fail) chứ không phải lỗi biên dịch. */
    private boolean probeRanTests(String probe) {
        if (probe == null || probe.isEmpty()) return false;
        return probe.contains("All tests passed")
            || probe.contains("Some tests failed")
            || probe.matches("(?s).*\\+\\d+.*")     // "+5: ..." dòng tiến độ của flutter test
            || probe.matches("(?s).*-\\d+:.*");
    }

    /** Trích vài dòng lỗi biên dịch của Dart/Flutter từ output để báo cho người chấm. */
    private String extractCompileErrors(String output) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("{")) continue;   // bỏ dòng JSON machine
            if (t.contains("Error:") || t.contains("Error when reading")
                    || t.contains("Compilation failed")) {
                if (count > 0) sb.append(" | ");
                sb.append(t.length() > 200 ? t.substring(0, 200) : t);
                if (++count >= 4) break;
            }
        }
        return sb.toString();
    }

    // ── Parse output ─────────────────────────────────────────────
    private String parseGraderOutput(String output) {
        final String START = "--- GRADE_RESULT_START ---", END = "--- GRADE_RESULT_END ---";
        int s = output.indexOf(START);
        int e = output.indexOf(END);
        if (s != -1 && e != -1 && e > s) {
            String json = output.substring(s + START.length(), e).trim();
            try {
                mapper.readTree(json);             // validate JSON kết quả chấm
                return json;
            } catch (Exception ex) {
                // CÓ marker nhưng JSON HỎNG → KHÔNG đoán điểm bằng trọng số ĐỀU (sẽ ra điểm SAI mà
                // vẫn lưu DONE, không ai phát hiện). Ném lỗi để bài vào ERROR cho GV chấm lại.
                throw new RuntimeException("grader.dart xuất JSON kết quả HỎNG — không đọc được điểm chính xác.");
            }
        }
        // Hoàn toàn KHÔNG có marker (đề rất cũ / grader lỗi nặng) → mới dùng cơ chế ước lượng cũ.
        return fallbackParse(output);
    }

    private String fallbackParse(String raw) {
        List<TestCaseResult> details = new ArrayList<>();
        Map<Integer, String> names = new HashMap<>();
        Map<Integer, String> errors = new HashMap<>();
        int pass = 0, fail = 0;
        double earned = 0, max = 0;

        for (String line : raw.split("\n")) {
            if (!line.startsWith("{")) continue;
            try {
                JsonNode n = mapper.readTree(line);
                String type = n.path("type").asText();
                if ("testStart".equals(type))
                    names.put(n.path("test").path("id").asInt(), n.path("test").path("name").asText());
                if ("error".equals(type))
                    errors.put(n.path("testID").asInt(), n.path("error").asText());
                if ("testDone".equals(type)) {
                    int id = n.path("testID").asInt();
                    String nm = names.getOrDefault(id, "");
                    String res = n.path("result").asText();
                    if (nm.isBlank() || nm.startsWith("loading ")) continue;
                    max += 1;
                    if ("success".equals(res)) {
                        pass++;
                        earned++;
                        details.add(new TestCaseResult(nm, "PASS", "+1"));
                    } else {
                        fail++;
                        details.add(new TestCaseResult(nm, "FAILED", errors.getOrDefault(id, "Sai kết quả")));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        double score = max > 0 ? (earned / max) * 10 : 0;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("diem", Math.round(score * 100.0) / 100.0);
        r.put("soTestPass", pass);
        r.put("tongSoTest", pass + fail);
        r.put("chiTiet", details);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r);
        } catch (Exception e) {
            return "{\"error\":\"parse error\"}";
        }
    }

    // ── Helpers ──────────────────────────────────────────────────
    private void unzip(File zip, File dest) throws Exception {
        byte[] buf = new byte[4096];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File f = safeFile(dest, entry);
                if (entry.isDirectory()) {
                    f.mkdirs();
                } else {
                    f.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        int len;
                        while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private File safeFile(File dest, ZipEntry entry) throws Exception {
        File f = new File(dest, entry.getName());
        if (!f.getCanonicalPath().startsWith(dest.getCanonicalPath() + File.separator))
            throw new Exception("Zip Slip: " + entry.getName());
        return f;
    }

    private void deleteDirectory(File dir) throws Exception {
        if (!dir.exists()) return;
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile).forEach(File::delete);
        }
    }

    private String toDockerPath(String path) {
        // Chuyển đường dẫn Windows C:\foo\bar → /c/foo/bar cho Docker
        if (path.length() >= 2 && path.charAt(1) == ':') {
            return "/" + Character.toLowerCase(path.charAt(0))
                    + path.substring(2).replace("\\", "/");
        }
        // Linux/Mac giữ nguyên
        return path.replace("\\", "/");
    }

    private String dockerContainerName(String prefix, String examId, String studentId) {
        String raw = prefix + "-" + examId + "-" + studentId + "-"
                + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
    }

    private void removeContainerQuietly(String containerName) {
        if (containerName == null || containerName.isBlank()) return;
        try {
            Process p = new ProcessBuilder("docker", "rm", "-f", containerName).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception e) {
            log.warn("Không xóa được container Docker {} sau timeout: {}", containerName, e.getMessage());
        }
    }
}
