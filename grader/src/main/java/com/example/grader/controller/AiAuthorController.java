package com.example.grader.controller;

import com.example.grader.config.AppActor;
import com.example.grader.service.ai.AiExamAuthorService;
import com.example.grader.service.ai.AiSettingsService;
import com.example.grader.service.ai.ExamDocumentReader;
import com.example.grader.service.ai.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Trợ lý AI soạn đề & bộ testcase cho trang "Tạo bộ testcase".
 *
 * <p>Mọi endpoint ở đây chỉ TRẢ VỀ ĐỀ XUẤT. Không endpoint nào tự ghi vào đề đang chấm —
 * giáo viên sửa rồi bấm chấp nhận thì frontend mới gọi API lưu draft/publish sẵn có.
 */
@RestController
@RequestMapping("/api/ai")
@CrossOrigin(origins = "*")
public class AiAuthorController {

    @Autowired private AiSettingsService settings;
    @Autowired private LlmService llm;
    @Autowired private AiExamAuthorService author;
    @Autowired private ExamDocumentReader documents;

    // ── Cấu hình LLM ─────────────────────────────────────────────

    @GetMapping("/settings")
    public ResponseEntity<?> getSettings() {
        return handle(() -> settings.describe());
    }

    /** Body: { provider, apiKey?, model?, baseUrl?, timeoutSeconds?, clearApiKey? }. */
    @PostMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, Object> body) {
        return handle(() -> settings.update(body, AppActor.DEFAULT));
    }

    /**
     * Gọi thử một lượt ngắn để biết key/model dùng được không. Luôn trả 200 kèm {ok,message}.
     *
     * <p>Body {model, apiKey, baseUrl} là cấu hình ĐANG GÕ: thử trên bản nháp và KHÔNG lưu gì.
     * Bỏ trống body thì thử chính cấu hình đã lưu. Trước đây phép thử buộc phải lưu trước, nên
     * gõ nhầm một ký tự trong key là mất luôn key đang dùng được.
     */
    @PostMapping("/settings/test")
    public ResponseEntity<?> testConnection(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ResponseEntity.ok(settings.withDraft(
                    str(body, "model"), str(body, "apiKey"), str(body, "baseUrl"),
                    () -> llm.testConnection()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(Map.of("ok", false, "message", e.getMessage()));
        }
    }

    // ── Bước 1: đề bài ───────────────────────────────────────────

    /** Body: { topic, knowledge, screens, features, entity, difficulty, duration, note }. */
    @PostMapping("/exam/draft")
    public ResponseEntity<?> draftExam(@RequestBody Map<String, Object> body) {
        return handle(() -> author.draftExam(body));
    }

    /** Body: { de_bai, instruction } — giáo viên gõ yêu cầu sửa bằng lời. */
    @PostMapping("/exam/revise")
    public ResponseEntity<?> reviseExam(@RequestBody Map<String, Object> body) {
        return handle(() -> author.reviseExam(str(body, "de_bai"), str(body, "instruction")));
    }

    /**
     * Nhánh "đã có đề sẵn": tải file đề lên (.docx/.pdf/.txt/.md), bóc chữ ra rồi đi thẳng
     * sang bước phân tích Item Key — bỏ qua bước nhờ AI soạn đề.
     *
     * <p>KHÔNG gọi LLM ở đây: chỉ đọc file, để giáo viên xem lại chữ trước khi tốn một lượt AI.
     */
    @PostMapping("/exam/import")
    public ResponseEntity<?> importExam(@RequestParam("file") MultipartFile file) {
        return handle(() -> {
            if (file == null || file.isEmpty())
                throw new IllegalArgumentException("Chưa chọn file đề để tải lên.");
            try {
                Map<String, Object> read = documents.read(file.getOriginalFilename(), file.getBytes());
                Map<String, Object> out = new LinkedHashMap<>(read);
                out.put("de_bai", read.get("text"));
                out.put("file_name", file.getOriginalFilename());
                return out;
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Không đọc được file tải lên: " + e.getMessage());
            }
        });
    }

    // ── Bước 2: Item Key + hình minh họa ─────────────────────────

    /** Body: { de_bai } → { contract, mockup_spec, screens[{id,title,svg,keys}], notes }. */
    @PostMapping("/keys/analyze")
    public ResponseEntity<?> analyzeKeys(@RequestBody Map<String, Object> body) {
        return handle(() -> author.analyzeKeys(str(body, "de_bai")));
    }

    /** Body: { mockup_spec } → vẽ lại hình sau khi giáo viên sửa key. KHÔNG gọi AI, không tốn token. */
    @PostMapping("/keys/mockup")
    public ResponseEntity<?> renderMockup(@RequestBody Map<String, Object> body) {
        return handle(() -> author.renderMockup(
                body == null ? null : body.getOrDefault("mockup_spec", body)));
    }

    /**
     * Body: { mockup_spec, instruction, contract } → nhờ AI sửa hình bằng lời
     * ("bỏ ô số điện thoại", "thêm màn hình chi tiết"). Trả về bản mô tả mới + hình đã vẽ lại.
     */
    @PostMapping("/keys/mockup/revise")
    public ResponseEntity<?> reviseMockup(@RequestBody Map<String, Object> body) {
        return handle(() -> author.reviseMockup(
                body == null ? null : body.get("mockup_spec"),
                str(body, "instruction"),
                body == null ? null : body.get("contract")));
    }

    // ── Bước 3: bộ testcase ──────────────────────────────────────

    /** Body: { de_bai, contract } → { items[], rejected[], missing_keys[], notes[], total_weight }. */
    @PostMapping("/testcases/propose")
    public ResponseEntity<?> proposeTestcases(@RequestBody Map<String, Object> body) {
        return handle(() -> author.proposeTestcases(str(body, "de_bai"),
                body == null ? null : body.get("contract")));
    }

    // ── Bước 5: khung starter ────────────────────────────────────

    /**
     * Body: { de_bai, contract } → { files[{path,content,summary}], warnings[], notes[], syntax_ok }.
     * Khung chỉ có class + hằng số key; thân hàm luôn là TODO để sinh viên tự làm UI/logic.
     */
    @PostMapping("/starter/propose")
    public ResponseEntity<?> proposeStarter(@RequestBody Map<String, Object> body) {
        return handle(() -> author.proposeStarter(str(body, "de_bai"),
                body == null ? null : body.get("contract")));
    }

    /** Body: { files } → kiểm lại cú pháp sau khi giáo viên sửa tay. Không gọi AI. */
    @SuppressWarnings("unchecked")
    @PostMapping("/starter/check")
    public ResponseEntity<?> checkStarter(@RequestBody Map<String, Object> body) {
        return handle(() -> {
            Object files = body == null ? null : body.get("files");
            return author.checkStarterSyntax(files instanceof java.util.List
                    ? (java.util.List<Map<String, Object>>) files : java.util.List.of());
        });
    }

    /**
     * Body: { de_bai, spec, instruction, contract } → nhờ AI sửa khung bằng lời.
     * Lượt sửa SINH LẠI toàn bộ file (thân hàm vẫn luôn là TODO).
     */
    @PostMapping("/starter/revise")
    public ResponseEntity<?> reviseStarter(@RequestBody Map<String, Object> body) {
        return handle(() -> author.reviseStarter(
                str(body, "de_bai"),
                body == null ? null : body.get("spec"),
                str(body, "instruction"),
                body == null ? null : body.get("contract")));
    }

    /**
     * Body: { files: [{path, content}], exam_id? } → ZIP khung starter ĐANG SOẠN để tải về ngay,
     * không cần lưu vào bộ testcase trước. Giáo viên hay muốn cầm file đi thử build trên máy
     * trước khi chốt bộ.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/starter/download")
    public ResponseEntity<?> downloadStarter(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("files");
        java.util.List<Map<String, Object>> files = raw instanceof java.util.List
                ? (java.util.List<Map<String, Object>>) raw : java.util.List.of();
        if (files.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Chưa có file khung starter để tải."));

        String examId = str(body, "exam_id");
        String name = examId == null || examId.isBlank() ? "starter" : examId.trim() + "_starter";
        try {
            var bos = new java.io.ByteArrayOutputStream();
            try (var zos = new java.util.zip.ZipOutputStream(bos)) {
                for (Map<String, Object> file : files) {
                    String path = file == null ? null : String.valueOf(file.get("path"));
                    if (path == null || path.isBlank() || path.equals("null")) continue;
                    // Tên file đến từ trình duyệt: '..' hay '/' đầu dòng sẽ ghi ra ngoài thư mục
                    // giải nén trên máy người nhận (zip slip) → chặn ngay tại đây.
                    String clean = path.replace('\\', '/').trim();
                    if (clean.startsWith("/") || clean.contains("../"))
                        return ResponseEntity.badRequest().body(Map.of("error", "Tên file không hợp lệ: " + path));
                    zos.putNextEntry(new java.util.zip.ZipEntry(clean));
                    Object content = file.get("content");
                    zos.write((content == null ? "" : String.valueOf(content))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            return ResponseEntity.ok()
                    .header("Content-Type", "application/zip")
                    .header("Content-Disposition", "attachment; filename=\"" + name + ".zip\"")
                    .body(bos.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Không nén được khung starter."));
        }
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private ResponseEntity<?> handle(Supplier<Map<String, Object>> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e));
        } catch (IllegalStateException e) {
            // Chưa cắm key / AI trả về rác / gọi mạng lỗi: lỗi của phía AI, không phải request sai.
            return ResponseEntity.status(502).body(error(e));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(error(e));
        }
    }

    private Map<String, Object> error(Exception e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        return m;
    }
}
