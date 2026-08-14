package com.example.grader.service;

import com.example.grader.entity.GradingRuntimeSetting;
import com.example.grader.repository.GradingRuntimeSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Cấu hình HIỆU NĂNG chấm bài chỉnh được lúc đang chạy: CPU/RAM mỗi container Docker và số bài
 * chấm song song.
 *
 * <p>Trước đây các giá trị này chỉ nằm trong application.yml, muốn đổi phải sửa file + khởi động
 * lại backend. Ở đây chúng được lưu DB (1 hàng duy nhất) và đọc lại ngay tại thời điểm bật
 * container, nên:
 * <ul>
 *   <li>CPU/RAM có hiệu lực từ BÀI TIẾP THEO (bài đang chạy giữ nguyên quota của container nó).</li>
 *   <li>Số bài song song có hiệu lực NGAY, kể cả khi đang chấm dở — xem
 *       {@code BatchGradingService.applyConcurrency}.</li>
 * </ul>
 *
 * <p>Giá trị trong application.yml trở thành MẶC ĐỊNH (nút "Khôi phục mặc định").
 */
@Slf4j
@Service
public class GradingRuntimeSettingsService {

    @Value("${grader.run.cpus:2.0}")
    private String defaultCpusRaw;
    @Value("${grader.run.memory:2048m}")
    private String defaultMemoryRaw;
    @Value("${grader.max.concurrent:3}")
    private int defaultMaxConcurrent;
    @Value("${grader.timeout.seconds:240}")
    private int defaultTimeoutSeconds;

    /** Trần cứng số bài song song — chặn gõ nhầm 999 làm treo máy. */
    @Value("${grader.max.concurrent-limit:16}")
    private int maxConcurrentLimit;

    @Autowired
    private GradingRuntimeSettingRepository repo;

    // Giá trị ĐANG có hiệu lực. volatile: worker/luồng chấm đọc mà không cần khoá.
    private volatile double cpus;
    private volatile int memoryMb;
    private volatile int maxConcurrent;
    private volatile int timeoutSeconds;
    private volatile Instant updatedAt;
    private volatile String updatedBy;

    /**
     * Cầu nối sang bộ worker chấm bài. Dùng callback thay vì inject thẳng BatchGradingService
     * để tránh phụ thuộc vòng (BatchGradingService → GradingService → service này).
     */
    private volatile IntConsumer concurrencyApplier;

    // Cache năng lực máy (docker info) — mỗi lần gọi tốn ~1 giây nên không hỏi lại liên tục.
    private volatile HostCapacity hostCache;
    private volatile long hostCacheAt;
    private static final long HOST_CACHE_MS = 5 * 60 * 1000L;

    private record HostCapacity(boolean dockerAvailable, Integer cpus, Integer memoryMb, String error) {}

    // ── Nạp cấu hình ─────────────────────────────────────────────

    @PostConstruct
    public void load() {
        applyToMemory(defaultCpus(), defaultMemoryMb(), defaultMaxConcurrent, defaultTimeoutSeconds, null, null);
        try {
            repo.findById(GradingRuntimeSetting.SINGLETON_ID).ifPresent(saved -> applyToMemory(
                    saved.getCpus()           != null ? saved.getCpus()           : defaultCpus(),
                    saved.getMemoryMb()       != null ? saved.getMemoryMb()       : defaultMemoryMb(),
                    saved.getMaxConcurrent()  != null ? saved.getMaxConcurrent()  : defaultMaxConcurrent,
                    saved.getTimeoutSeconds() != null ? saved.getTimeoutSeconds() : defaultTimeoutSeconds,
                    saved.getUpdatedAt(), saved.getUpdatedBy()));
        } catch (Exception e) {
            // DB lỗi/thiếu bảng KHÔNG được chặn chấm bài — vẫn còn mặc định trong application.yml.
            log.warn("Không đọc được cấu hình hiệu năng chấm từ DB, dùng mặc định: {}", e.getMessage());
        }
        log.info("Cấu hình chấm: {} CPU · {} MB · {} bài song song · watchdog {}s",
                cpus, memoryMb, maxConcurrent, timeoutSeconds);
    }

    private void applyToMemory(double c, int mem, int concurrent, int timeout, Instant at, String by) {
        this.cpus = c;
        this.memoryMb = mem;
        this.maxConcurrent = concurrent;
        this.timeoutSeconds = timeout;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    /** BatchGradingService tự đăng ký ở đây để nhận mức song song mới ngay khi người dùng lưu. */
    public void bindConcurrencyApplier(IntConsumer applier) {
        this.concurrencyApplier = applier;
    }

    // ── Giá trị cho lượt chấm ────────────────────────────────────

    /** Tham số `--cpus` (chuỗi vì docker nhận số thập phân: "1.5"). */
    public String cpusArg() {
        return String.valueOf(cpus);
    }

    /** Tham số `--memory` dạng docker hiểu: "2048m". */
    public String memoryArg() {
        return memoryMb + "m";
    }

    public double cpus()          { return cpus; }
    public int memoryMb()         { return memoryMb; }
    public int maxConcurrent()    { return maxConcurrent; }
    public int timeoutSeconds()   { return timeoutSeconds; }

    // ── Đọc/ghi từ API ───────────────────────────────────────────

    /** Toàn bộ dữ liệu trang cấu hình cần: giá trị hiện tại, mặc định, giới hạn, máy, gợi ý. */
    public Map<String, Object> describe() {
        HostCapacity host = hostCapacity();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("settings", settingsMap(cpus, memoryMb, maxConcurrent, timeoutSeconds));
        res.put("defaults", settingsMap(defaultCpus(), defaultMemoryMb(), defaultMaxConcurrent, defaultTimeoutSeconds));
        res.put("limits", limitsMap(host));
        res.put("host", hostMap(host));
        res.put("presets", presets(host));
        res.put("warnings", warnings(cpus, memoryMb, maxConcurrent, host));
        res.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        res.put("updatedBy", updatedBy);
        return res;
    }

    /**
     * Lưu cấu hình mới. Chỉ nhận đủ 4 giá trị đã hợp lệ — sai thì ném IllegalArgumentException để
     * controller trả 400 kèm lý do bằng tiếng Việt, KHÔNG tự ý cắt gọt cho vừa (người dùng phải
     * biết giá trị mình gõ đã bị đổi).
     */
    public Map<String, Object> update(Map<String, Object> body, String actor) {
        HostCapacity host = hostCapacity();
        Map<String, Object> limits = limitsMap(host);

        double newCpus = round1(readDouble(body, "cpus", cpus));
        int newMemory = readInt(body, "memoryMb", memoryMb);
        int newConcurrent = readInt(body, "maxConcurrent", maxConcurrent);
        int newTimeout = readInt(body, "timeoutSeconds", timeoutSeconds);

        double cpusMin = num(limits, "cpusMin").doubleValue(), cpusMax = num(limits, "cpusMax").doubleValue();
        int memMin = num(limits, "memoryMbMin").intValue(), memMax = num(limits, "memoryMbMax").intValue();
        int conMin = num(limits, "maxConcurrentMin").intValue(), conMax = num(limits, "maxConcurrentMax").intValue();
        int toMin = num(limits, "timeoutSecondsMin").intValue(), toMax = num(limits, "timeoutSecondsMax").intValue();

        if (newCpus < cpusMin || newCpus > cpusMax)
            throw new IllegalArgumentException("Số CPU mỗi bài phải trong khoảng " + cpusMin + " – " + cpusMax);
        if (newMemory < memMin || newMemory > memMax)
            throw new IllegalArgumentException("RAM mỗi bài phải trong khoảng " + memMin + " – " + memMax + " MB");
        if (newConcurrent < conMin || newConcurrent > conMax)
            throw new IllegalArgumentException("Số bài chấm song song phải trong khoảng " + conMin + " – " + conMax);
        if (newTimeout < toMin || newTimeout > toMax)
            throw new IllegalArgumentException("Thời gian tối đa mỗi bài phải trong khoảng " + toMin + " – " + toMax + " giây");

        persist(newCpus, newMemory, newConcurrent, newTimeout, actor);
        return describe();
    }

    /** Trả về đúng các giá trị trong application.yml (dùng cho nút "Khôi phục mặc định"). */
    public Map<String, Object> resetToDefaults(String actor) {
        persist(defaultCpus(), defaultMemoryMb(), defaultMaxConcurrent, defaultTimeoutSeconds, actor);
        return describe();
    }

    private void persist(double newCpus, int newMemory, int newConcurrent, int newTimeout, String actor) {
        Instant now = Instant.now();
        try {
            GradingRuntimeSetting row = repo.findById(GradingRuntimeSetting.SINGLETON_ID)
                    .orElseGet(GradingRuntimeSetting::new);
            row.setId(GradingRuntimeSetting.SINGLETON_ID);
            row.setCpus(newCpus);
            row.setMemoryMb(newMemory);
            row.setMaxConcurrent(newConcurrent);
            row.setTimeoutSeconds(newTimeout);
            row.setUpdatedAt(now);
            row.setUpdatedBy(actor);
            repo.save(row);
        } catch (Exception e) {
            // Ghi DB hỏng → vẫn áp dụng cho phiên đang chạy nhưng phải nói rõ là không giữ được
            // sau khi khởi động lại, tránh giáo viên tưởng đã lưu.
            throw new IllegalStateException("Không lưu được cấu hình vào cơ sở dữ liệu: " + e.getMessage(), e);
        }

        applyToMemory(newCpus, newMemory, newConcurrent, newTimeout, now, actor);

        IntConsumer applier = concurrencyApplier;
        if (applier != null) {
            try { applier.accept(newConcurrent); }
            catch (Exception e) { log.warn("Không đổi được số worker chấm: {}", e.getMessage()); }
        }
        log.info("Cấu hình chấm cập nhật: {} CPU · {} MB · {} bài song song · watchdog {}s (bởi {})",
                newCpus, newMemory, newConcurrent, newTimeout, actor);
    }

    // ── Giới hạn & cảnh báo ──────────────────────────────────────

    private Map<String, Object> settingsMap(double c, int mem, int concurrent, int timeout) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpus", c);
        m.put("memoryMb", mem);
        m.put("maxConcurrent", concurrent);
        m.put("timeoutSeconds", timeout);
        return m;
    }

    private Map<String, Object> limitsMap(HostCapacity host) {
        int hostCpus = host.cpus() != null && host.cpus() > 0
                ? host.cpus() : Runtime.getRuntime().availableProcessors();
        int hostMem = host.memoryMb() != null && host.memoryMb() > 0 ? host.memoryMb() : 32768;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpusMin", 0.5);
        // Không cho đặt quá số CPU Docker thấy: docker chấp nhận nhưng chỉ tổ tranh CPU, chấm chậm hơn.
        m.put("cpusMax", (double) Math.max(1, hostCpus));
        m.put("cpusStep", 0.5);
        m.put("memoryMbMin", 512);
        m.put("memoryMbMax", Math.max(1024, hostMem));
        m.put("memoryMbStep", 256);
        m.put("maxConcurrentMin", 1);
        m.put("maxConcurrentMax", Math.max(1, Math.min(maxConcurrentLimit, hostCpus * 2)));
        m.put("timeoutSecondsMin", 60);
        m.put("timeoutSecondsMax", 1800);
        m.put("timeoutSecondsStep", 30);
        return m;
    }

    private Map<String, Object> hostMap(HostCapacity host) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dockerAvailable", host.dockerAvailable());
        m.put("cpus", host.cpus());
        m.put("memoryMb", host.memoryMb());
        m.put("error", host.error());
        m.put("backendCpus", Runtime.getRuntime().availableProcessors());
        return m;
    }

    /**
     * Cảnh báo (KHÔNG chặn lưu): các cấu hình vẫn chạy được nhưng hay tạo ra kết quả sai lệch —
     * thiếu RAM thì bài đúng cũng báo lỗi biên dịch, thiếu CPU thì chạm watchdog.
     */
    private List<String> warnings(double c, int mem, int concurrent, HostCapacity host) {
        List<String> out = new ArrayList<>();
        double totalCpus = c * concurrent;
        long totalMem = (long) mem * concurrent;

        if (host.cpus() != null && totalCpus > host.cpus()) {
            out.add(String.format(Locale.ROOT,
                    "Tổng CPU đặt trước %.1f vượt %d CPU mà Docker nhìn thấy — các container sẽ giành CPU "
                            + "của nhau, chấm CHẬM hơn chứ không nhanh hơn.", totalCpus, host.cpus()));
        }
        if (host.memoryMb() != null && totalMem > host.memoryMb() * 0.85) {
            out.add(String.format(Locale.ROOT,
                    "Tổng RAM đặt trước %d MB gần chạm %d MB của Docker — container có thể bị giết vì hết bộ nhớ, "
                            + "bài đúng vẫn bị báo lỗi biên dịch.", totalMem, host.memoryMb()));
        }
        if (mem < 1536) {
            out.add("RAM dưới 1536 MB thường không đủ để compile Flutter: bài làm đúng vẫn có thể ra 0/0.");
        }
        if (c < 1) {
            out.add("Dưới 1 CPU mỗi bài, compile Flutter rất chậm và dễ chạm watchdog — nên tăng thời gian tối đa mỗi bài.");
        }
        if (!host.dockerAvailable()) {
            out.add("Chưa đọc được thông tin Docker (Docker Desktop có đang chạy không?). "
                    + "Giới hạn hiển thị đang lấy tạm theo CPU của máy.");
        }
        return out;
    }

    /** Ba mức dựng sẵn tính theo đúng năng lực máy đang chạy, để không phải tự đoán con số. */
    private List<Map<String, Object>> presets(HostCapacity host) {
        int hostCpus = host.cpus() != null && host.cpus() > 0
                ? host.cpus() : Runtime.getRuntime().availableProcessors();
        int hostMem = host.memoryMb() != null && host.memoryMb() > 0 ? host.memoryMb() : 8192;
        int concurrentMax = Math.max(1, Math.min(maxConcurrentLimit, hostCpus * 2));

        List<Map<String, Object>> out = new ArrayList<>();
        out.add(preset("eco", "Tiết kiệm", "Chấm từng bài một, chừa máy cho việc khác",
                Math.min(1.0, Math.max(1, hostCpus)), 1536, 1, concurrentMax, hostMem));
        out.add(preset("balanced", "Cân bằng", "Mặc định khuyên dùng cho máy 8 CPU / 16 GB",
                Math.min(2.0, hostCpus), 2048, Math.max(1, Math.min(3, hostCpus / 2)), concurrentMax, hostMem));
        out.add(preset("turbo", "Tối đa tốc độ", "Dùng gần hết máy — chỉ chạy khi không làm việc khác",
                Math.max(2.0, Math.min(4.0, Math.max(1, hostCpus / 2.0))), 3072,
                Math.max(1, hostCpus / 2), concurrentMax, hostMem));
        return out;
    }

    private Map<String, Object> preset(String key, String label, String description,
                                       double c, int mem, int concurrent, int concurrentMax, int hostMem) {
        // Không để preset tự đề xuất mức vượt RAM: giữ tổng dưới 70% RAM Docker.
        int memBound = Math.max(1, (int) (hostMem * 0.7 / mem));
        int safeConcurrent = Math.max(1, Math.min(Math.min(concurrent, memBound), concurrentMax));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("description", description);
        m.put("cpus", round1(Math.max(0.5, c)));
        m.put("memoryMb", mem);
        m.put("maxConcurrent", safeConcurrent);
        return m;
    }

    // ── Năng lực máy (docker info) ───────────────────────────────

    private HostCapacity hostCapacity() {
        HostCapacity cached = hostCache;
        if (cached != null && System.currentTimeMillis() - hostCacheAt < HOST_CACHE_MS) return cached;
        HostCapacity fresh = queryDockerInfo();
        hostCache = fresh;
        hostCacheAt = System.currentTimeMillis();
        return fresh;
    }

    /** Hỏi Docker xem máy có bao nhiêu CPU/RAM để dựng giới hạn thật thay vì con số đoán. */
    private HostCapacity queryDockerInfo() {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "info", "--format", "{{.NCPU}};{{.MemTotal}}");
            pb.redirectErrorStream(true);
            p = pb.start();
            String output;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                output = sb.toString().trim();
            }
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new HostCapacity(false, null, null, "docker info không phản hồi trong 10 giây");
            }
            if (p.exitValue() != 0)
                return new HostCapacity(false, null, null, firstLine(output));

            for (String line : output.split("\\R")) {
                String[] parts = line.trim().split(";");
                if (parts.length != 2) continue;
                try {
                    int ncpu = Integer.parseInt(parts[0].trim());
                    long memBytes = Long.parseLong(parts[1].trim());
                    return new HostCapacity(true, ncpu, (int) (memBytes / 1024 / 1024), null);
                } catch (NumberFormatException ignored) {
                    // dòng log của docker lẫn vào output — thử dòng kế tiếp
                }
            }
            return new HostCapacity(false, null, null, "Không đọc được số CPU/RAM từ docker info");
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return new HostCapacity(false, null, null, e.getMessage());
        }
    }

    private String firstLine(String s) {
        if (s == null || s.isBlank()) return "docker info lỗi";
        String[] lines = s.split("\\R");
        return lines[lines.length - 1].trim();   // dòng cuối thường là thông báo lỗi thật
    }

    // ── Helpers ──────────────────────────────────────────────────

    private double defaultCpus() {
        try {
            return round1(Double.parseDouble(defaultCpusRaw.trim()));
        } catch (Exception ignored) {
            return 2.0;
        }
    }

    /** application.yml ghi RAM theo cú pháp docker ("2048m", "2g") — quy hết về MB. */
    private int defaultMemoryMb() {
        String raw = defaultMemoryRaw == null ? "" : defaultMemoryRaw.trim().toLowerCase(Locale.ROOT);
        try {
            if (raw.endsWith("g"))  return (int) (Double.parseDouble(raw.substring(0, raw.length() - 1)) * 1024);
            if (raw.endsWith("gb")) return (int) (Double.parseDouble(raw.substring(0, raw.length() - 2)) * 1024);
            if (raw.endsWith("m"))  return (int) Double.parseDouble(raw.substring(0, raw.length() - 1));
            if (raw.endsWith("mb")) return (int) Double.parseDouble(raw.substring(0, raw.length() - 2));
            long bytes = Long.parseLong(raw);
            return (int) (bytes / 1024 / 1024);
        } catch (Exception ignored) {
            return 2048;
        }
    }

    private double readDouble(Map<String, Object> body, String key, double fallback) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return fallback;
        try {
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Giá trị không hợp lệ cho " + key + ": " + v);
        }
    }

    private int readInt(Map<String, Object> body, String key, int fallback) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return fallback;
        try {
            return v instanceof Number n ? n.intValue()
                    : (int) Math.round(Double.parseDouble(String.valueOf(v).trim()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Giá trị không hợp lệ cho " + key + ": " + v);
        }
    }

    private Number num(Map<String, Object> map, String key) {
        return (Number) map.get(key);
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
