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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class GradingService {

    @Value("${grader.image.prefix:grading-env}")
    private String imagePrefix;

    @Value("${grader.timeout.seconds:120}")
    private int timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Entry point ──────────────────────────────────────────────


    // ── Tìm root project chứa thư mục lib ──────────────────────
    private Path detectProjectRoot(Path extractDir) throws Exception {
        // Kiểm tra ngay ở thư mục gốc xem có thư mục lib không
        if (Files.exists(extractDir.resolve("lib"))) return extractDir;

        // Nếu không có, tìm sâu vào 1 cấp (trường hợp SV nén cả thư mục cha chứa lib)
        List<Path> subDirs = Files.list(extractDir).filter(Files::isDirectory).toList();
        for (Path sub : subDirs) {
            if (Files.exists(sub.resolve("lib"))) return sub;
        }

        // Tìm sâu hơn nữa
        return Files.walk(extractDir)
                .filter(p -> p.getFileName().toString().equals("lib") && Files.isDirectory(p))
                .map(Path::getParent)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thư mục lib/ trong file nộp"));
    }

    public String gradeSubmission(String studentId, String examId,
                                  Path tempDir, Path zipPath) throws Exception {
        Path extractDir = tempDir.resolve("extracted");
        Files.createDirectories(extractDir);

        try {
            unzip(zipPath.toFile(), extractDir.toFile());

            // Bây giờ hàm này sẽ trả về thư mục ngay sát bên ngoài thư mục lib/
            Path projectRoot = detectProjectRoot(extractDir);
            Path studentLib = projectRoot.resolve("lib");

            // Chỉ kiểm tra file .dart bên trong thư mục lib
            long dartCount = Files.walk(studentLib)
                    .filter(p -> p.toString().endsWith(".dart")).count();
            if (dartCount == 0)
                throw new IllegalArgumentException("Không có file .dart trong lib/");

            log.info("[{}] {} file dart, project root: {}", studentId, dartCount, projectRoot.getFileName());

            return runDockerGrader(
                    studentLib.toAbsolutePath().toString(),
                    examId
            );
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    // ── Gọi Docker ───────────────────────────────────────────────
    private String runDockerGrader(String libPath,// ← Bỏ param này
                                   String examId) throws Exception {
        String imageName = imagePrefix + "-" + examId.toLowerCase();

        String[] command = {
                "docker", "run", "--rm",
                "--memory", "1024m",
                "--cpus", "1.0",
                "-v", toDockerPath(libPath) + ":/app/lib",
                // ← Xóa dòng mount pubspec_student.yaml
                imageName
        };

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
            throw new RuntimeException("Timeout sau " + timeoutSeconds + "s");
        }

        t1.join();
        t2.join();

        String output = stdout.toString();
        if (process.exitValue() != 0 && !output.contains("GRADE_RESULT_START"))
            throw new RuntimeException("Lỗi biên dịch: " + stderr.toString().trim());

        return parseGraderOutput(output);
    }

    // ── Parse output ─────────────────────────────────────────────
    private String parseGraderOutput(String output) {
        try {
            int s = output.indexOf("--- GRADE_RESULT_START ---");
            int e = output.indexOf("--- GRADE_RESULT_END ---");
            if (s != -1 && e != -1) {
                String json = output.substring(s + "--- GRADE_RESULT_START ---".length(), e).trim();
                mapper.readTree(json);
                return json;
            }
        } catch (Exception ignored) {
        }
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
        if (dir.exists())
            Files.walk(dir.toPath()).sorted(Comparator.reverseOrder())
                    .map(Path::toFile).forEach(File::delete);
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
}
