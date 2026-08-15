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

    /** Gọi thử một lượt ngắn để biết key/model dùng được không. Luôn trả 200 kèm {ok,message}. */
    @PostMapping("/settings/test")
    public ResponseEntity<?> testConnection() {
        return ResponseEntity.ok(llm.testConnection());
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
