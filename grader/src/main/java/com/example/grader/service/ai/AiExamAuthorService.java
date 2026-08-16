package com.example.grader.service.ai;

import com.example.grader.service.TestcaseTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trợ lý soạn đề: sinh/sửa đề bài → phân tích Item Key + vẽ giao diện → đề xuất bộ testcase.
 *
 * <p>Đây là trợ lý, KHÔNG phải bộ tạo đề tự động: mỗi bước chỉ trả về BẢN ĐỀ XUẤT cho giáo viên
 * sửa và bấm chấp nhận. Không bước nào tự ghi vào đề đang chấm.
 *
 * <p>Điểm khác căn bản so với bản AI đã bị gỡ trước đây: AI không sinh code Dart nữa mà chỉ chọn
 * template trong thư viện rồi điền tham số. Nhờ vậy mọi thứ AI đề xuất đều đi qua đúng bộ kiểm tra
 * của {@link TestcaseTemplateService} — testcase hỏng bị chặn ngay tại đây thay vì lộ ra lúc chấm.
 */
@Slf4j
@Service
public class AiExamAuthorService {

    @Autowired private LlmService llm;
    @Autowired private MockupRenderer mockupRenderer;
    @Autowired private StarterRenderer starterRenderer;
    @Autowired private TestcaseTemplateService templateService;
    @Autowired private com.example.grader.service.ExamService examService;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Bước 1: đề bài ───────────────────────────────────────────

    public Map<String, Object> draftExam(Map<String, Object> req) {
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.draftSystem()),
                LlmMessage.user(AiPrompts.draftUser(req == null ? Map.of() : req))));
        return examResult(res);
    }

    public Map<String, Object> reviseExam(String deBai, String instruction) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề để sửa.");
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("Hãy nhập yêu cầu chỉnh sửa cho AI.");
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.reviseSystem()),
                LlmMessage.user(AiPrompts.reviseUser(deBai, instruction))));
        return examResult(res);
    }

    private Map<String, Object> examResult(JsonNode res) {
        String markdown = text(res.path("de_bai_markdown"), "");
        if (markdown.isBlank())
            throw new IllegalStateException("AI không trả về nội dung đề bài. Hãy thử lại.");
        List<Map<String, Object>> criteria = new ArrayList<>();
        double total = 0;
        for (JsonNode c : res.path("criteria")) {
            double points = c.path("points").asDouble(0);
            total += points;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", text(c.path("name"), ""));
            row.put("points", points);
            criteria.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("de_bai", markdown.trim());
        out.put("summary", text(res.path("summary"), ""));
        out.put("criteria", criteria);
        out.put("total_points", Math.round(total * 10) / 10.0);
        return out;
    }

    // ── Bước 2: Item Key + hình minh họa ─────────────────────────

    public Map<String, Object> analyzeKeys(String deBai) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề bài để phân tích.");

        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.keysSystem(strategyCodes(), suggestedKeys())),
                LlmMessage.user(AiPrompts.keysUser(deBai))));

        List<Map<String, Object>> keys = parseKeys(res);
        if (keys.isEmpty())
            throw new IllegalStateException("AI không tìm được thành phần giao diện nào trong đề. "
                    + "Hãy bổ sung mô tả giao diện ở mục 3 rồi thử lại.");

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("require_keys", res.path("require_keys").asBoolean(true));
        contract.put("keys", keys);

        JsonNode spec = res.path("mockup");
        List<Map<String, Object>> screens = mockupRenderer.render(spec);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", contract);
        out.put("mockup_spec", plain(spec.isMissingNode() ? mapper.createObjectNode() : spec));
        out.put("screens", screens);
        out.put("notes", textList(res.path("notes")));
        out.put("unused_keys", unusedKeys(keys, screens));
        return out;
    }

    /**
     * Ép các tham số ĐI THEO CẶP về cùng số phần tử.
     *
     * <p>Nhiều runner nhận danh sách song song: {@code fieldKeys} ↔ {@code invalidValues} ↔
     * {@code errorKeys}, {@code fieldKeys} ↔ {@code values}… AI rất hay trả 2 ô nhập nhưng chỉ 1
     * giá trị sai, và lỗi chỉ nổ ra lúc BẤM LƯU cả bộ testcase — lúc đó người dùng phải tự dò
     * xem "PE_62_item_06" là testcase nào rồi tự đếm lại từng danh sách.
     *
     * <p>Thiếu thì bù bằng phần tử cuối (giá trị sai/khoá lỗi lặp lại vẫn kiểm được), thừa thì
     * cắt bớt theo danh sách gốc. Sửa ngay tại nguồn còn hơn để bộ testcase không lưu nổi.
     */
    private void alignPairedLists(Map<String, Object> params, List<Map<String, Object>> schema) {
        for (Map<String, Object> param : schema) {
            Object pairWith = param.get("pair_with");
            if (pairWith == null) continue;
            String name = String.valueOf(param.get("name"));
            String source = String.valueOf(pairWith);
            if (!params.containsKey(name) || !params.containsKey(source)) continue;

            List<String> master = splitCsv(params.get(source));
            List<String> paired = splitCsv(params.get(name));
            if (master.isEmpty() || paired.size() == master.size()) continue;

            List<String> fixed = new ArrayList<>();
            for (int i = 0; i < master.size(); i++) {
                fixed.add(i < paired.size() ? paired.get(i)
                        : (paired.isEmpty() ? "" : paired.get(paired.size() - 1)));
            }
            params.put(name, String.join(",", fixed));
        }
    }

    private List<String> splitCsv(Object raw) {
        List<String> out = new ArrayList<>();
        for (String part : String.valueOf(raw == null ? "" : raw).split(",", -1)) {
            String value = part.trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    /** Bóc danh sách Item Key từ câu trả lời của AI, chuẩn hoá về đúng bộ cách dò hợp lệ. */
    private List<Map<String, Object>> parseKeys(JsonNode res) {
        Set<String> allowedStrategies = new LinkedHashSet<>(strategyCodes());
        List<Map<String, Object>> keys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode k : res.path("keys")) {
            String key = text(k.path("key"), "");
            if (key.isBlank() || !seen.add(key)) continue;      // bỏ key rỗng/trùng
            String strategy = text(k.path("strategy"), "key_only");
            if (!allowedStrategies.contains(strategy)) strategy = "key_only";
            String value = text(k.path("value"), "");
            // Cách dò cần giá trị mà AI để trống → hạ về key_only còn hơn lưu một hợp đồng sai.
            if (value.isBlank() && !"key_only".equals(strategy) && !"auto".equals(strategy))
                strategy = "key_only";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("label", text(k.path("label"), key));
            row.put("strategy", strategy);
            row.put("value", value);
            row.put("index", Math.max(0, k.path("index").asInt(0)));
            row.put("evidence", text(k.path("evidence"), ""));
            keys.add(row);
        }
        return keys;
    }

    /** Vẽ lại hình từ bản mô tả đã có — dùng khi giáo viên sửa key xong, KHÔNG gọi lại AI. */
    public Map<String, Object> renderMockup(Object rawSpec) {
        JsonNode spec = mapper.valueToTree(rawSpec);
        List<Map<String, Object>> screens = mockupRenderer.render(spec);
        if (screens.isEmpty())
            throw new IllegalArgumentException("Bản mô tả giao diện trống hoặc sai định dạng.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("screens", screens);
        return out;
    }

    /**
     * Sửa hình theo lời giáo viên ("bỏ ô số điện thoại", "tách màn chi tiết ra riêng"…).
     *
     * <p>AI chỉ sửa BẢN MÔ TẢ, {@link MockupRenderer} vẫn là nơi tính toạ độ — nên hình sau khi
     * sửa giữ nguyên phong cách và không thể lệch/chồng chữ. Bản mô tả mới được trả về kèm để lần
     * sửa sau nối tiếp được từ đúng bản này.
     */
    public Map<String, Object> reviseMockup(Object rawSpec, String instruction, Object rawContract) {
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("Hãy mô tả bạn muốn sửa gì trên hình.");
        JsonNode current = mapper.valueToTree(rawSpec);
        if (current == null || current.isMissingNode() || current.isNull() || current.isEmpty())
            throw new IllegalArgumentException("Chưa có hình để sửa — hãy phân tích đề trước.");

        List<String> contractKeys = contractKeyList(rawContract);
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.mockupReviseSystem()),
                LlmMessage.user(AiPrompts.mockupReviseUser(current.toString(),
                        String.join(", ", contractKeys), instruction))));

        // AI hay trả thẳng {"screens": [...]} thay vì bọc trong "mockup" — nhận cả hai kiểu còn
        // hơn bắt giáo viên bấm lại và tốn thêm một lượt gọi.
        JsonNode spec = res.path("mockup");
        if (!spec.has("screens") && res.has("screens")) spec = res;

        List<Map<String, Object>> screens = mockupRenderer.render(spec);
        if (screens.isEmpty())
            throw new IllegalStateException("AI trả về bản mô tả giao diện không vẽ được. "
                    + "Hãy nói rõ hơn yêu cầu rồi thử lại; hình cũ vẫn còn nguyên.");

        // Sửa hình là sửa CẢ danh sách key: bỏ một thành phần khỏi hình mà key của nó còn nằm
        // trong hợp đồng thì bộ chấm đi tìm một widget không còn trên đề. AI trả danh sách key mới
        // thì dùng; model cũ/không trả thì giữ nguyên hợp đồng đang có.
        List<Map<String, Object>> revisedKeys = parseKeys(res);
        List<Map<String, Object>> keysForCheck = revisedKeys.isEmpty()
                ? keyRows(rawContract) : revisedKeys;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mockup_spec", plain(spec));
        out.put("screens", screens);
        out.put("notes", textList(res.path("notes")));
        out.put("unused_keys", unusedKeys(keysForCheck, screens));
        if (!revisedKeys.isEmpty()) {
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("require_keys", res.path("require_keys").asBoolean(true));
            contract.put("keys", revisedKeys);
            out.put("contract", contract);
        }
        return out;
    }

    /**
     * Đổi cây JsonNode (Jackson 2) sang Map/List thuần trước khi trả cho client.
     *
     * <p>Classpath có CẢ Jackson 2 lẫn Jackson 3 (xem CLAUDE.md). Bộ chuyển đổi HTTP của Spring
     * Boot 4 là Jackson 3, nó không nhận ra JsonNode của Jackson 2 nên serialize như một bean:
     * client nhận về {"array":false,"boolean":false,"nodeType":"OBJECT",…} thay vì nội dung thật.
     * Hậu quả là mọi thứ gửi bản mô tả ngược lên máy chủ (vẽ lại hình, nhờ AI sửa) đều gãy.
     */
    private Object plain(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        return mapper.convertValue(node, Object.class);
    }

    /** Hợp đồng key ở dạng hàng {key,label} — dùng lại được cho unusedKeys sau khi sửa hình. */
    private List<Map<String, Object>> keyRows(Object rawContract) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : contractKeyList(rawContract)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            out.add(row);
        }
        return out;
    }

    /** Key đã khai nhưng không xuất hiện trên bản vẽ → giáo viên biết chỗ AI mô tả thiếu. */
    private List<String> unusedKeys(List<Map<String, Object>> keys, List<Map<String, Object>> screens) {
        Set<String> drawn = new LinkedHashSet<>();
        for (Map<String, Object> screen : screens) {
            Object list = screen.get("keys");
            if (list instanceof List<?> l) l.forEach(k -> drawn.add(String.valueOf(k)));
        }
        List<String> out = new ArrayList<>();
        for (Map<String, Object> k : keys) {
            String key = String.valueOf(k.get("key"));
            if (!drawn.contains(key)) out.add(key);
        }
        return out;
    }

    // ── Bước 3: đề xuất bộ testcase ──────────────────────────────

    public Map<String, Object> proposeTestcases(String deBai, Object rawContract) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề bài để đề xuất testcase.");

        List<Map<String, Object>> templates = templateService.listTemplates(null, null, null, false);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> t : templates) byId.put(String.valueOf(t.get("template_id")), t);

        List<String> contractKeys = contractKeyList(rawContract);
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.testcaseSystem(
                        renderTemplateCatalog(templates),
                        contractKeys.isEmpty() ? "(chưa khai key nào)" : String.join(", ", contractKeys))),
                LlmMessage.user(AiPrompts.testcaseUser(deBai))));

        Map<String, List<Map<String, Object>>> runnerParams = runnerParameters();
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        Set<String> fingerprints = new LinkedHashSet<>();
        int index = 0;

        for (JsonNode raw : res.path("items")) {
            String templateId = text(raw.path("template_id"), "");
            Map<String, Object> template = byId.get(templateId);
            if (template == null) {
                rejected.add(reject(templateId, "Không có mẫu này trong thư viện testcase."));
                continue;
            }
            String runner = String.valueOf(template.get("runner"));
            List<Map<String, Object>> schema = runnerParams.getOrDefault(runner, List.of());

            Map<String, Object> params = new LinkedHashMap<>();
            String problem = null;
            for (Map<String, Object> param : schema) {
                String name = String.valueOf(param.get("name"));
                String type = String.valueOf(param.getOrDefault("type", "text"));
                boolean required = Boolean.TRUE.equals(param.get("required"));
                JsonNode supplied = raw.path("parameters").path(name);
                Object value = coerce(supplied, type, param.get("default"));
                boolean blank = value == null || String.valueOf(value).isBlank();
                if (blank && required) {
                    problem = "Thiếu tham số bắt buộc \"" + name + "\".";
                    break;
                }
                if (!blank) params.put(name, value);
                if (!blank && ("semantic_key".equals(type) || "semantic_keys".equals(type))) {
                    for (String k : String.valueOf(value).split(",")) {
                        String trimmed = k.trim();
                        if (!trimmed.isEmpty()) usedKeys.add(trimmed);
                    }
                }
            }
            if (problem != null) {
                rejected.add(reject(templateId, problem));
                continue;
            }
            alignPairedLists(params, schema);
            if (!fingerprints.add(templateId + "|" + params)) {
                rejected.add(reject(templateId, "Trùng hoàn toàn với một testcase khác đã đề xuất."));
                continue;
            }

            double weight = raw.path("weight").asDouble(number(template.get("weight_default"), 1));
            if (!Double.isFinite(weight) || weight <= 0) weight = number(template.get("weight_default"), 1);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instance_id", "AI_" + String.format("%02d", ++index));
            item.put("template_id", templateId);
            item.put("runner", runner);
            item.put("name", String.valueOf(template.get("name")));
            item.put("description", String.valueOf(template.getOrDefault("description", "")));
            item.put("skill_code", String.valueOf(template.get("skill_code")));
            item.put("layer", String.valueOf(template.getOrDefault("layer", "")));
            item.put("difficulty", String.valueOf(template.getOrDefault("difficulty", "basic")));
            item.put("weight", Math.round(weight * 100) / 100.0);
            item.put("parameters", params);
            item.put("enabled", true);
            item.put("criterion", text(raw.path("criterion"), ""));
            item.put("reason", text(raw.path("reason"), ""));
            items.add(item);
        }

        if (items.isEmpty())
            throw new IllegalStateException("AI không đề xuất được testcase hợp lệ nào"
                    + (rejected.isEmpty() ? "." : ": " + rejected.get(0).get("reason")));

        // Key testcase đang dùng nhưng chưa khai ở hợp đồng → phải bổ sung, nếu không bật
        // require_keys là backend từ chối lưu.
        List<String> missing = new ArrayList<>();
        for (String k : usedKeys) if (!contractKeys.contains(k)) missing.add(k);

        double totalWeight = items.stream().mapToDouble(i -> number(i.get("weight"), 0)).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("rejected", rejected);
        out.put("notes", textList(res.path("notes")));
        out.put("missing_keys", missing);
        out.put("total_weight", Math.round(totalWeight * 100) / 100.0);
        return out;
    }

    // ── Bước 5: khung starter phát cho sinh viên ─────────────────

    /**
     * Dựng khung starter: AI mô tả khung, {@link StarterRenderer} sinh code (thân hàm luôn là TODO),
     * rồi parse thật bằng Dart trong ảnh nền để chắc chắn sinh viên nhận được bộ code build được.
     */
    public Map<String, Object> proposeStarter(String deBai, Object rawContract) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề bài để dựng khung starter.");

        List<String> contractKeys = contractKeyList(rawContract);
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.starterSystem(
                        contractKeys.isEmpty() ? "(chưa khai key nào)" : String.join(", ", contractKeys))),
                LlmMessage.user(AiPrompts.starterUser(deBai))));

        return starterResult(res, rawContract);
    }

    /**
     * Sửa khung starter theo lời giáo viên ("thêm màn chi tiết", "bỏ repository", "đổi tên hàm").
     *
     * <p>Đi qua BẢN MÔ TẢ chứ không cho AI sửa thẳng code: {@link StarterRenderer} vẫn là nơi sinh
     * code và luôn để thân hàm là TODO, nên lượt sửa cũng không thể lén cài sẵn lời giải.
     *
     * <p>Hệ quả cần nói rõ trên giao diện: lượt sửa SINH LẠI toàn bộ file, phần giáo viên gõ tay
     * trong trình soạn thảo sẽ bị thay.
     */
    public Map<String, Object> reviseStarter(String deBai, Object rawSpec, String instruction,
                                             Object rawContract) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề bài để sửa khung starter.");
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("Hãy mô tả bạn muốn sửa gì trong khung starter.");
        JsonNode current = mapper.valueToTree(rawSpec);
        if (current == null || current.isMissingNode() || current.isNull() || !current.has("files"))
            throw new IllegalArgumentException("Chưa có khung starter để sửa — hãy sinh khung trước.");

        List<String> contractKeys = contractKeyList(rawContract);
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.starterReviseSystem(
                        contractKeys.isEmpty() ? "(chưa khai key nào)" : String.join(", ", contractKeys))),
                LlmMessage.user(AiPrompts.starterReviseUser(deBai, current.toString(), instruction))));
        return starterResult(res, rawContract);
    }

    /** Dựng code + kiểm cú pháp cho cả lượt sinh mới lẫn lượt sửa. */
    private Map<String, Object> starterResult(JsonNode res, Object rawContract) {
        StarterRenderer.Rendered rendered = starterRenderer.render(
                res, examKeysDart(rawContract), keyLabels(rawContract));
        if (rendered.files().isEmpty())
            throw new IllegalStateException("AI không mô tả được file nào hợp lệ cho khung starter.");

        // pubspec đi kèm khung: thiếu nó sinh viên không chạy nổi dự án, mà tự viết thì hay khai
        // package ảnh chấm không có → bài đúng vẫn 0 điểm.
        List<Map<String, Object>> files = new ArrayList<>();
        for (Map<String, String> project : examService.starterProjectFiles()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", project.get("name"));
            row.put("content", project.get("content"));
            row.put("summary", "pubspec.yaml".equals(project.get("name"))
                    ? "Khai thư viện đúng bằng môi trường chấm (không sửa)"
                    : "Phiên bản đã khoá của môi trường chấm");
            files.add(row);
        }
        files.addAll(rendered.files());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", files);
        out.put("warnings", rendered.warnings());
        out.put("notes", textList(res.path("notes")));
        // Trả kèm bản mô tả để lượt sửa sau nối tiếp từ đúng bản này thay vì dựng lại từ đầu.
        out.put("spec", plain(res));
        out.putAll(checkStarterSyntax(rendered.files()));
        return out;
    }

    /** Kiểm cú pháp toàn bộ khung bằng một lần gọi Docker (bỏ import để ghép được nhiều file). */
    public Map<String, Object> checkStarterSyntax(List<Map<String, Object>> files) {
        Map<String, Object> out = new LinkedHashMap<>();
        StringBuilder source = new StringBuilder();
        for (Map<String, Object> file : files) {
            // CHỈ ghép file .dart: pubspec.yaml đi kèm khung mà lọt vào đây thì trình phân tích
            // Dart báo sai cú pháp cho một file YAML hoàn toàn đúng.
            if (!String.valueOf(file.get("path")).toLowerCase().endsWith(".dart")) continue;
            for (String line : String.valueOf(file.get("content")).split("\n", -1)) {
                if (line.startsWith("import ") || line.startsWith("export ")) continue;
                source.append(line).append('\n');
            }
            source.append('\n');
        }
        try {
            String problem = examService.checkDartSyntax(source.toString());
            out.put("syntax_ok", problem == null);
            out.put("syntax_message", problem == null
                    ? "Đã parse bằng Dart trong ảnh chấm: khung starter đúng cú pháp."
                    : "Khung starter sai cú pháp: " + problem);
        } catch (Exception e) {
            // Không có Docker thì vẫn phát được starter, chỉ là chưa kiểm chứng — nói thẳng ra.
            out.put("syntax_ok", null);
            out.put("syntax_message", "Chưa kiểm tra được cú pháp (" + e.getMessage()
                    + "). Hãy mở Docker rồi sinh lại nếu muốn chắc chắn.");
        }
        return out;
    }

    /** Hằng số ExamKeys dựng từ hợp đồng đã chốt — không để AI viết file này. */
    private String examKeysDart(Object rawContract) {
        try {
            Map<String, Object> preview = templateService.contractPreview(rawContract);
            Object dart = preview.get("starter_dart");
            return dart == null ? null : String.valueOf(dart);
        } catch (Exception e) {
            log.warn("Không dựng được exam_keys.dart: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> keyLabels(Object rawContract) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode node = mapper.valueToTree(rawContract);
        JsonNode keys = node.has("keys") ? node.path("keys") : node;
        for (JsonNode k : keys) {
            String key = text(k.path("key"), "");
            if (!key.isBlank()) out.put(key, text(k.path("label"), ""));
        }
        return out;
    }

    private Map<String, Object> reject(String templateId, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("template_id", templateId);
        row.put("reason", reason);
        return row;
    }

    /**
     * Ép giá trị AI trả về đúng kiểu tham số. AI hay trả số dạng chuỗi hoặc "true"/"false" —
     * chuẩn hoá ở đây để backend không phải từ chối vì lý do kiểu dữ liệu.
     */
    private Object coerce(JsonNode supplied, String type, Object fallback) {
        if (supplied == null || supplied.isMissingNode() || supplied.isNull()) return fallback;
        return switch (type) {
            case "number" -> supplied.isNumber() ? supplied.asDouble()
                    : parseDouble(supplied.asText(""), fallback);
            case "bool" -> supplied.isBoolean() ? supplied.asBoolean()
                    : Boolean.parseBoolean(supplied.asText("false"));
            default -> {
                if (supplied.isArray()) {          // AI trả mảng key thay vì chuỗi CSV
                    List<String> parts = new ArrayList<>();
                    supplied.forEach(n -> parts.add(n.asText("").trim()));
                    yield String.join(",", parts);
                }
                yield supplied.asText("").trim();
            }
        };
    }

    private Object parseDouble(String raw, Object fallback) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ── Dữ liệu nền cho prompt ───────────────────────────────────

    /** Một dòng mỗi mẫu: đủ để AI chọn đúng nhưng không nhồi cả schema JSON vào prompt. */
    private String renderTemplateCatalog(List<Map<String, Object>> templates) {
        Map<String, List<Map<String, Object>>> runnerParams = runnerParameters();
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> t : templates) {
            String runner = String.valueOf(t.get("runner"));
            sb.append(t.get("template_id")).append(" | ").append(runner)
              .append(" | ").append(t.get("skill_code"))
              .append(" | ").append(t.get("name"));
            List<Map<String, Object>> params = runnerParams.getOrDefault(runner, List.of());
            if (!params.isEmpty()) {
                sb.append(" | ");
                List<String> parts = new ArrayList<>();
                for (Map<String, Object> p : params) {
                    StringBuilder one = new StringBuilder(String.valueOf(p.get("name")));
                    one.append('(').append(p.get("type"));
                    if (Boolean.TRUE.equals(p.get("required"))) one.append(",bắt buộc");
                    if (p.get("options") instanceof List<?> options && !options.isEmpty())
                        one.append(",chọn:").append(String.join("/",
                                options.stream().map(String::valueOf).toList()));
                    if (p.get("pair_with") != null) one.append(",ghép với ").append(p.get("pair_with"));
                    parts.add(one.append(')').toString());
                }
                sb.append(String.join(" ", parts));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> runnerParameters() {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        Object runners = templateService.runnerCatalog().get("runners");
        if (runners instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> map)) continue;
                Object params = map.get("parameters");
                out.put(String.valueOf(map.get("runner")),
                        params instanceof List ? (List<Map<String, Object>>) params : List.of());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> strategyCodes() {
        Object strategies = templateService.contractCatalog().get("strategies");
        List<String> out = new ArrayList<>();
        if (strategies instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?> map && map.get("code") != null)
                    out.add(String.valueOf(map.get("code")));
                else if (row instanceof String s) out.add(s);
            }
        }
        if (out.isEmpty()) out.addAll(List.of("auto", "key_only", "widget_type", "icon",
                "tooltip", "text", "button_text", "type_with_text"));
        return out;
    }

    private List<String> suggestedKeys() {
        Object keys = templateService.runnerCatalog().get("semantic_keys");
        List<String> out = new ArrayList<>();
        if (keys instanceof List<?> list) list.forEach(k -> out.add(String.valueOf(k)));
        return out;
    }

    private List<String> contractKeyList(Object rawContract) {
        List<String> out = new ArrayList<>();
        JsonNode node = mapper.valueToTree(rawContract);
        JsonNode keys = node.has("keys") ? node.path("keys") : node;
        for (JsonNode k : keys) {
            String key = k.isTextual() ? k.asText("") : text(k.path("key"), "");
            if (!key.isBlank()) out.add(key);
        }
        return out;
    }

    private List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            String s = n.asText("").trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private String text(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        String s = node.asText("").trim();
        return s.isEmpty() ? fallback : s;
    }

    private double number(Object v, double fallback) {
        if (v == null) return fallback;
        try {
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
