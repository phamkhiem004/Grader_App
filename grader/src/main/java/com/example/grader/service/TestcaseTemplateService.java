package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import com.example.grader.entity.Skill;
import com.example.grader.entity.SkillCategory;
import com.example.grader.entity.TestcaseTemplate;
import com.example.grader.repository.ExamRepository;
import com.example.grader.repository.TestcaseTemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thư viện testcase dạng template-instance.
 *
 * Template là dữ liệu dùng chung, không bị sửa khi giáo viên chỉnh một đề. Instance được
 * chuẩn hóa tại đây rồi mới sinh skills_matrix.json để giữ taxonomy và expected nhất quán.
 */
@Service
@Slf4j
public class TestcaseTemplateService {

    private static final String TEMPLATE_VERSION = "2026.4";
    private static final String TEMPLATE_CREATED_BY = "system";
    private static final String TEMPLATE_CREATED_AT = "2026-08-02T00:00:00Z";
    private static final String COMMON_ENGINE = "COMMON_V1";
    private static final Pattern SAFE_INSTANCE_ID = Pattern.compile("[A-Za-z0-9_-]{1,60}");
    private static final Pattern TEMPLATE_ID_PATTERN = Pattern.compile("[A-Z0-9_]{3,80}");
    private static final Pattern DART_CALLABLE = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern SAFE_CONTRACT_PATH = Pattern.compile(
            "lib/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.dart");
    private static final Pattern SAFE_SOURCE_PATH = Pattern.compile(
            "(?:lib|test)/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\\.dart|"
                    + "pubspec\\.yaml|analysis_options\\.yaml");
    private static final Set<String> DIFFICULTIES = Set.of("basic", "intermediate", "advanced");
    private static final Set<String> TEMPLATE_LAYERS = Set.of("SCREEN", "BLACKBOX", "RESPONSIVE");

    // ── Testcase "tự viết code": giáo viên gõ thân testWidgets, hệ thống bọc và chèn vào engine ──
    /** template_id quy ước cho testcase code tay; không nằm trong thư viện template dùng chung. */
    public static final String CUSTOM_TEMPLATE_ID = "CUSTOM_CODE";
    private static final String CUSTOM_RUNNER = TestCaseTaxonomy.CUSTOM_RUNNER;
    /** Lấy từ TestCaseTaxonomy để soạn đề và chấm không lệch layer (trước đây ghi "CUSTOM" —
     *  không có trong enum của SPEC nên bị loại, testcase tay ra layer rỗng). */
    private static final String CUSTOM_LAYER = TestCaseTaxonomy.layerForRunner(CUSTOM_RUNNER);
    private static final int CUSTOM_CODE_MAX_CHARS = 20000;
    /** Khai báo chỉ hợp lệ ở cấp file — nếu nằm trong thân test sẽ làm hỏng cả exam_test.dart. */
    private static final List<Map.Entry<Pattern, String>> CUSTOM_CODE_BANNED = List.of(
            Map.entry(Pattern.compile("(?m)^\\s*(import|export|part|library)\\s"),
                    "không được khai báo import/export/part/library trong thân testcase "
                            + "(engine đã import sẵn material, flutter_test và app của sinh viên)"),
            Map.entry(Pattern.compile("(?m)^\\s*(void\\s+)?main\\s*\\("),
                    "không được định nghĩa hàm main()"),
            Map.entry(Pattern.compile("(?<![A-Za-z0-9_$])testWidgets\\s*\\("),
                    "chỉ cần viết phần THÂN test; hệ thống tự bọc testWidgets('<mã testcase>', ...) bên ngoài"),
            Map.entry(Pattern.compile("(?<![A-Za-z0-9_$])(group|setUp|setUpAll|tearDown|tearDownAll)\\s*\\("),
                    "không dùng được group/setUp/tearDown bên trong một test (dùng addTearDown nếu cần dọn dẹp)"));
    private static final String CUSTOM_BEGIN_MARK = "CUSTOM_TESTCASES_BEGIN";
    private static final String CUSTOM_END_MARK = "CUSTOM_TESTCASES_END";
    /** Nhóm lọc hiển thị ở Khu vực 1; không thay thế category/skill của syllabus. */
    private static final Map<String, String> TESTCASE_GROUP_LABELS = Map.of(
            "LOGIC", "Testcase Logic",
            "WIDGET", "Testcase Widget",
            "BEHAVIOR", "Testcase Behavior");
    private static final Set<String> BEHAVIOR_RUNNERS = Set.of(
            "APP_BOOT", "NAVIGATION", "BUTTON_ACTION", "WIDGET_ENABLED",
            "DIALOG_FLOW", "FORM_PREFILL", "FORM_SUBMIT");
    private static final Set<String> LOGIC_RUNNERS = Set.of(
            "FORM_REQUIRED_FIELDS", "FORM_VALIDATE_FIELDS", "LIST_ITEM_COUNT",
            "STATE_REACTIVE_FLOW");
    // Tên/mô tả template nằm trong common-testcase-templates.json hoặc bản ghi DB.

    private final ObjectMapper mapper = new ObjectMapper();
    /** Thư viện hiệu lực = template gốc + bản sửa đè + template giáo viên tự thêm. */
    private final Map<String, Map<String, Object>> templates = new LinkedHashMap<>();
    /** Bản gốc từ classpath, giữ nguyên để "Khôi phục mặc định" quay về được. */
    private final Map<String, Map<String, Object>> builtinTemplates = new LinkedHashMap<>();
    /** Template bị ẩn khỏi Khu vực 2 nhưng vẫn resolve được cho đề cũ. */
    private final Set<String> hiddenTemplateIds = new LinkedHashSet<>();

    @Autowired private ExamRepository examRepository;
    @Autowired private SyllabusService syllabusService;
    @Autowired private ExamService examService;
    @Autowired private TestcaseTemplateRepository templateRepository;

    @PostConstruct
    public void loadTemplates() {
        templates.clear();
        builtinTemplates.clear();
        hiddenTemplateIds.clear();
        boolean commonLoaded = loadClasspathTemplates("common-testcase-templates.json", COMMON_ENGINE);
        int commonCount = templates.size();
        boolean curriculumLoaded = loadClasspathTemplates(
                "prm393-curriculum-testcase-templates.json", COMMON_ENGINE);
        if (commonLoaded) {
            log.info("✅ Nạp {} testcase engine chung + {} testcase curriculum tham số hóa",
                    commonCount, templates.size() - commonCount);
        } else {
            log.error("Không nạp được thư viện testcase dùng chung.");
        }
        if (!curriculumLoaded) {
            log.error("Không nạp được thư viện testcase curriculum PRM393.");
        }
        builtinTemplates.putAll(templates);
        applyStoredTemplates();
    }

    /**
     * Chồng bản sửa/bổ sung trong DB lên thư viện gốc. Lỗi ở đây chỉ ghi log: mất kết nối DB
     * không được làm sập cả chức năng tạo testcase, chỉ là tạm thời thiếu template tự thêm.
     */
    private void applyStoredTemplates() {
        try {
            for (TestcaseTemplate stored : templateRepository.findAllByOrderByCreatedAtAsc()) {
                Map<String, Object> row = readTemplatePayload(stored);
                if (row == null) continue;
                templates.put(stored.getTemplateId(), row);
                if (stored.isHidden()) hiddenTemplateIds.add(stored.getTemplateId());
            }
        } catch (Exception e) {
            log.warn("Không đọc được template testcase trong DB: {}", e.getMessage());
        }
    }

    private Map<String, Object> readTemplatePayload(TestcaseTemplate stored) {
        try {
            Map<String, Object> row = mapper.readValue(stored.getPayloadJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            row.put("template_id", stored.getTemplateId());
            row.put("origin", stored.getOrigin());
            row.put("created_by", text(stored.getCreatedBy(), TEMPLATE_CREATED_BY));
            row.put("created_at", stored.getCreatedAt() == null
                    ? TEMPLATE_CREATED_AT : stored.getCreatedAt().toString());
            enrichGeneratedTemplateSchema(row);
            return row;
        } catch (Exception e) {
            log.warn("Template {} trong DB bị hỏng, bỏ qua: {}", stored.getTemplateId(), e.getMessage());
            return null;
        }
    }

    private boolean loadClasspathTemplates(String resourceName, String engineType) {
        try (InputStream in = new ClassPathResource(resourceName).getInputStream()) {
            List<Map<String, Object>> rows = mapper.readValue(in,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> source : rows) {
                String id = text(source.get("template_id"));
                if (id == null || id.isBlank() || templates.containsKey(id)) continue;
                Map<String, Object> row = new LinkedHashMap<>(source);
                row.putIfAbsent("engine_type", engineType);
                row.putIfAbsent("name", id);
                enrichGeneratedTemplateSchema(row);
                templates.put(id, row);
            }
            return !rows.isEmpty();
        } catch (Exception e) {
            log.warn("Không nạp được {}: {}", resourceName, e.getMessage());
            return false;
        }
    }

    /** Danh sách template kèm skill/category để frontend dựng 3 khu vực kéo-thả. */
    public List<Map<String, Object>> listTemplates(String category, String skillCode, String layer) {
        return listTemplates(category, skillCode, layer, false);
    }

    /** {@code includeHidden} dùng cho màn "thùng rác" để khôi phục template đã ẩn. */
    public List<Map<String, Object>> listTemplates(String category, String skillCode, String layer,
                                                   boolean includeHidden) {
        ensureReferenceTemplatesLoaded();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> source : templates.values()) {
            Map<String, Object> row = enrichTemplate(source);
            if (!includeHidden && Boolean.TRUE.equals(row.get("hidden"))) continue;
            if (category != null && !category.isBlank()
                    && !category.equalsIgnoreCase(text(row.get("category")))) continue;
            if (skillCode != null && !skillCode.isBlank()
                    && !skillCode.equalsIgnoreCase(text(row.get("skill_code")))) continue;
            if (layer != null && !layer.isBlank()
                    && !layer.equalsIgnoreCase(text(row.get("layer")))) continue;
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> getTemplate(String templateId) {
        ensureReferenceTemplatesLoaded();
        Map<String, Object> source = templates.get(templateId);
        if (source == null) throw new IllegalArgumentException("Không tìm thấy testcase template: " + templateId);
        return enrichTemplate(source);
    }

    /** Đọc cấu hình instance hiện tại; đề cũ không có config vẫn trả danh sách rỗng. */
    public Map<String, Object> getExamConfig(String examId) {
        Exam exam = examRepository.findByExamId(ExamService.safeId(examId, "đề")).orElse(null);
        if (exam == null || exam.getTestcaseConfigJson() == null || exam.getTestcaseConfigJson().isBlank()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("exam_id", examId);
            empty.put("status", exam != null && exam.getTestcaseStatus() != null
                    ? exam.getTestcaseStatus() : "UNSAVED");
            empty.put("version", exam != null ? exam.getTestcaseVersion() : null);
            empty.put("template_version", TEMPLATE_VERSION);
            empty.put("items", List.of());
            empty.put("total_weight", 0);
            empty.put("exam_name", exam != null ? exam.getExamName() : null);
            empty.put("teacher_note", exam != null ? exam.getTeacherNote() : null);
            return empty;
        }
        try {
            Map<String, Object> config = mapper.readValue(exam.getTestcaseConfigJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            List<Map<String, Object>> items = normalizeExistingItems(config.get("items"));
            config.put("items", items);
            config.put("total_weight", totalWeight(items));
            // Tên/ghi chú nằm ở bảng exam chứ không trong config → gửi kèm để màn Sửa
            // nạp lại được đủ form, khỏi phải gọi thêm một API nữa.
            config.put("exam_name", exam.getExamName());
            config.put("teacher_note", exam.getTeacherNote());
            return config;
        } catch (Exception e) {
            throw new IllegalStateException("Cấu hình testcase của đề bị hỏng: " + e.getMessage());
        }
    }

    public Map<String, Object> saveDraft(String examId, Map<String, Object> body, String actor) {
        return save(examId, body, actor, false);
    }

    public Map<String, Object> publish(String examId, Map<String, Object> body, String actor) {
        return save(examId, body, actor, true);
    }

    /**
     * Clone một bộ được tạo bằng builder sang mã mới. Không sao chép bản ghi DB hay file một cách mù quáng:
     * config nguồn được chuẩn hóa và materialize lại để bộ mới luôn dùng engine hiện hành.
     */
    public synchronized Map<String, Object> cloneExam(String rawSourceExamId,
                                                       Map<String, Object> body,
                                                       String actor) {
        String sourceExamId = ExamService.safeId(rawSourceExamId, "bộ testcase nguồn");
        if (body == null) throw new IllegalArgumentException("Thiếu thông tin bộ testcase bản sao.");
        String targetExamId = firstText(body.get("exam_id"), body.get("examId"));
        targetExamId = ExamService.safeId(targetExamId, "bộ testcase mới");
        if (targetExamId.length() > 50)
            throw new IllegalArgumentException("Mã bộ testcase mới không được dài quá 50 ký tự.");
        if (sourceExamId.equalsIgnoreCase(targetExamId))
            throw new IllegalArgumentException("Mã bộ testcase bản sao phải khác mã bộ nguồn.");
        if (examRepository.existsByExamId(targetExamId))
            throw new IllegalStateException("Mã bộ testcase " + targetExamId + " đã tồn tại.");

        Path targetRoot = examService.testcaseDirectoryForConfiguration(targetExamId).getParent();
        if (targetRoot != null && Files.exists(targetRoot))
            throw new IllegalStateException("Thư mục của bộ testcase " + targetExamId + " đã tồn tại.");

        Exam source = examRepository.findByExamId(sourceExamId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bộ testcase nguồn: " + sourceExamId));
        if (!isTemplateCreatedExam(source)) {
            throw new IllegalStateException("Bộ " + sourceExamId
                    + " được nhập từ ZIP nên không có cấu hình builder để clone.");
        }
        String examName = firstText(body.get("exam_name"), body.get("examName"));
        if (examName == null || examName.isBlank())
            throw new IllegalArgumentException("Vui lòng nhập tên bộ testcase bản sao.");

        Map<String, Object> sourceConfig = parseConfig(source.getTestcaseConfigJson());
        Map<String, Object> cloneBody = new LinkedHashMap<>();
        cloneBody.put("exam_name", examName.trim());
        String teacherNote = firstText(body.get("teacher_note"), body.get("teacherNote"));
        cloneBody.put("teacher_note", teacherNote == null ? "" : teacherNote.trim());
        cloneBody.put("items", normalizeExistingItems(sourceConfig.get("items")));
        cloneBody.put("contract", sourceConfig.getOrDefault("contract", Map.of()));

        // Clone is already a complete copy of a builder configuration. Publish it
        // immediately so the archive can build its sandbox without forcing the
        // instructor through an otherwise redundant Edit -> Save round trip.
        Map<String, Object> result = publish(targetExamId, cloneBody, actor);
        try {
            examService.cloneHandout(sourceExamId, targetExamId);
        } catch (Exception copyError) {
            // Bộ đích vừa được tạo mới hoàn toàn nên có thể dọn sạch an toàn nếu clone tài liệu thất bại.
            try { examService.deleteExam(targetExamId); }
            catch (Exception cleanupError) { copyError.addSuppressed(cleanupError); }
            throw new IllegalStateException("Không sao chép được toàn bộ khung của bộ nguồn: "
                    + copyError.getMessage(), copyError);
        }
        result.put("source_exam_id", sourceExamId);
        result.put("exam_name", examName.trim());
        result.put("teacher_note", teacherNote == null ? "" : teacherNote.trim());
        result.put("cloned", true);
        return result;
    }

    /**
     * Sinh trước đúng các file chấm từ trạng thái form hiện tại nhưng không ghi file và không sửa DB.
     * Frontend gọi API này theo debounce để giảng viên theo dõi exam_test.dart/skills_matrix theo thời gian thực.
     */
    public Map<String, Object> preview(String rawExamId, Map<String, Object> body, String actor) {
        ensureReferenceTemplatesLoaded();
        String examId = ExamService.safeId(rawExamId, "bộ testcase");
        if (body == null) throw new IllegalArgumentException("Thiếu cấu hình testcase để xem trước.");

        Exam exam = examRepository.findByExamId(examId).orElse(null);
        Map<String, Object> oldConfig = parseConfig(exam == null ? null : exam.getTestcaseConfigJson());
        List<Map<String, Object>> items;
        try {
            items = normalizeItems(examId, body.get("items"), indexItems(oldConfig.get("items")), actor);
        } catch (IllegalArgumentException normalizationError) {
            // Một số bộ đã publish giữ snapshot testcase đầy đủ nhưng template gốc có thể đã bị
            // loại khỏi thư viện ở phiên bản sau. Khi đó không thể sinh live an toàn bằng engine
            // mới; vẫn phải cho giảng viên xem đúng ba file đang được dùng để chấm thay vì modal rỗng.
            String message = normalizationError.getMessage();
            List<Map<String, String>> storedFiles = exam == null || message == null
                    || !message.startsWith("Template không tồn tại:")
                    ? List.of() : examService.readExamTestcaseFiles(examId);
            if (storedFiles == null || storedFiles.isEmpty()) throw normalizationError;

            List<Map<String, Object>> storedItems = normalizeExistingItems(body.get("items"));
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("exam_id", examId);
            fallback.put("files", storedFiles);
            fallback.put("items", storedItems);
            fallback.put("total_weight", totalWeight(storedItems));
            fallback.put("live_preview", false);
            fallback.put("warning", "Bộ testcase dùng template cũ không còn trong thư viện; "
                    + "đang hiển thị chính xác bộ file đã lưu và đang dùng để chấm.");
            return fallback;
        }
        String engineType = engineType(items);
        if (!COMMON_ENGINE.equals(engineType))
            throw new IllegalStateException("Chưa hỗ trợ xem trước engine: " + engineType);

        try {
            String examTest = renderEngine(engineType, items);
            String skillsMatrix = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(toSkillsMatrix(items, engineType));
            validateGeneratedMatrix(skillsMatrix);
            String grader = readClasspathEngine("common-testcase-engine/grader.dart");

            List<Map<String, String>> files = new ArrayList<>();
            files.add(Map.of("name", "exam_test.dart", "content", examTest));
            files.add(Map.of("name", "skills_matrix.json", "content", skillsMatrix));
            files.add(Map.of("name", "grader.dart", "content", grader));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exam_id", examId);
            out.put("files", files);
            out.put("items", items);
            out.put("total_weight", totalWeight(items));
            out.put("live_preview", true);
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không sinh được code xem trước: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> save(String rawExamId, Map<String, Object> body,
                                     String actor, boolean publish) {
        ensureReferenceTemplatesLoaded();
        String examId = ExamService.safeId(rawExamId, "đề");
        if (body == null) throw new IllegalArgumentException("Thiếu cấu hình testcase");

        Exam exam = examRepository.findByExamId(examId).orElseGet(Exam::new);
        boolean isNew = exam.getId() == null;
        if (!isNew && !isTemplateCreatedExam(exam)) {
            throw new IllegalStateException("Mã đề " + examId
                    + " đã tồn tại. Hãy dùng một mã đề mới để tạo testcase.");
        }
        String examName = firstText(body.get("exam_name"), body.get("examName"));
        if (isNew && (examName == null || examName.isBlank()))
            throw new IllegalArgumentException("Vui lòng nhập tên đề thi khi tạo đề mới");
        Map<String, Object> oldConfig = parseConfig(exam.getTestcaseConfigJson());
        Map<String, Map<String, Object>> oldById = indexItems(oldConfig.get("items"));
        List<Map<String, Object>> items = normalizeItems(examId, body.get("items"), oldById, actor);
        String engineType = engineType(items);
        // Hợp đồng bài làm (Khu vực 0): giữ bản cũ nếu request không gửi kèm, để lưu Draft
        // từ màn hình khác không vô tình xóa mất cấu hình nhận diện của đề.
        Map<String, Object> contract = TestcaseContractSupport.normalize(
                body.containsKey("contract") ? body.get("contract") : oldConfig.get("contract"));
        validateContractCoversSelectedKeys(items, contract);

        int currentVersion = exam.getTestcaseVersion() == null ? 0 : exam.getTestcaseVersion();
        // Draft cũng là một bản cấu hình materialize được, nên không dùng version 0 sau lần lưu đầu.
        int version = currentVersion + 1;
        Instant now = Instant.now();
        String firstCreatedAt = text(oldConfig.get("created_at"));
        if (firstCreatedAt == null) firstCreatedAt = now.toString();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schema_version", 1);
        config.put("exam_id", examId);
        config.put("status", publish ? "PUBLISHED" : "DRAFT");
        config.put("template_version", TEMPLATE_VERSION);
        config.put("engine_type", engineType);
        config.put("profile_id", profileId(engineType));
        config.put("version", version);
        config.put("created_by", text(oldConfig.get("created_by")) != null
                ? oldConfig.get("created_by") : actor);
        config.put("created_at", firstCreatedAt);
        config.put("updated_by", actor);
        config.put("updated_at", now.toString());
        if (publish) config.put("published_at", now.toString());
        else if (oldConfig.get("published_at") != null) config.put("published_at", oldConfig.get("published_at"));
        config.put("contract", contract);
        config.put("items", items);

        try {
            String skillsMatrixJson = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(toSkillsMatrix(items, engineType));
            validateGeneratedMatrix(skillsMatrixJson);
            Map<String, Object> generatedMatrix = mapper.readValue(skillsMatrixJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            // Bộ mới chỉ chứa rubric trong skills_matrix hiện tại, không ghép lại dữ liệu cũ.
            Map<String, Object> publishedMatrix = generatedMatrix;

            // Publish là bản đem đi chấm: một đoạn code tay sai cú pháp làm hỏng cả
            // exam_test.dart → cả lớp 0 điểm, nên bắt buộc parse thật trước khi ghi file.
            String syntaxWarning = publish ? verifyCustomCodeBeforePublish(items) : null;

            // Draft cũng materialize thành bộ code để giáo viên tải xuống kiểm tra ngay;
            // chỉ Publish mới chuyển ExamStatus sang READY để cho phép chấm.
            if (publish) examService.snapshotCurrentTestcase(examId);
            Path dir = examService.testcaseDirectoryForConfiguration(examId);
            Files.createDirectories(dir);
            materializeEngine(dir, engineType, items);
            materializeContract(dir, contract);
            Files.writeString(dir.resolve("skills_matrix.json"), mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(publishedMatrix), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("testcase-config.json"), mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config), StandardCharsets.UTF_8);
            exam.setTestcasePath(dir.toAbsolutePath().normalize().toString());
            // Publish mới chỉ sinh file. Sandbox chỉ READY sau khi người dùng bấm Build Sandbox
            // và backend đã kiểm tra file + bảo đảm ảnh nền Docker dùng chung.
            exam.setStatus(ExamStatus.BUILDING);

            exam.setExamId(examId);
            if (isNew || exam.getCreatedBy() == null || exam.getCreatedBy().isBlank()) exam.setCreatedBy(actor);
            if (examName != null && !examName.isBlank()) exam.setExamName(examName.trim());
            String teacherNote = firstText(body.get("teacher_note"), body.get("teacherNote"));
            if (teacherNote != null) exam.setTeacherNote(teacherNote.trim());
            exam.setTestcaseConfigJson(mapper.writeValueAsString(config));
            exam.setTestcaseVersion(version);
            exam.setTestcaseStatus(publish ? "PUBLISHED" : "DRAFT");
            if (publish) exam.setTestcasePublishedAt(now);
            examRepository.save(exam);
            return response(exam, config, items, publish, syntaxWarning);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không lưu được cấu hình testcase: " + e.getMessage(), e);
        }
    }

    /**
     * Nâng engine dùng chung trong thư mục testcase của đề lên bản MỚI NHẤT trên classpath.
     *
     * <p>Cần có vì {@link #materializeEngine} chép engine ĐÓNG BĂNG vào {@code exams/<đề>/} lúc
     * publish, còn lúc chấm lại thì {@code BatchGradingService.resolveTestcasePath} lại ưu tiên
     * đúng thư mục đó. Hệ quả: sửa engine trong {@code resources} KHÔNG tới được đề đã publish,
     * bản sửa im lặng không có hiệu lực.
     *
     * <p>Chỉ áp cho đề {@code COMMON_V1}. Đề legacy giữ nguyên grader/testcase giáo viên đã nộp —
     * ghi đè là phá đề của họ.
     *
     * @return true nếu đã ghi lại engine
     */
    public boolean refreshCommonEngine(String examId) throws Exception {
        return refreshCommonEngine(examRepository.findByExamId(examId).orElse(null));
    }

    boolean refreshCommonEngine(Exam exam) throws Exception {
        if (exam == null) return false;
        String path = exam.getTestcasePath();
        if (path == null || path.isBlank()) return false;
        if (!COMMON_ENGINE.equals(configEngineType(exam))) return false;
        Path dir = Path.of(path);
        if (!Files.isDirectory(dir)) return false;
        // Phải nạp lại items từ config đã lưu: engine mới CHÈN testcase tay vào exam_test.dart,
        // refresh mà bỏ items sẽ ghi đè mất phần code tay của đề.
        materializeEngine(dir, COMMON_ENGINE,
                normalizeExistingItems(parseConfig(exam.getTestcaseConfigJson()).get("items")));
        log.info("Đã nâng engine dùng chung của đề {} lên bản mới nhất", exam.getExamId());
        return true;
    }

    /** engine_type đã lưu trong testcase-config; null với đề legacy (upload ZIP, không có config). */
    private String configEngineType(Exam exam) {
        String json = exam.getTestcaseConfigJson();
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Object> config = mapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            return text(config.get("engine_type"));
        } catch (Exception e) {
            log.warn("Không đọc được engine_type của đề {}: {}", exam.getExamId(), e.getMessage());
            return null;
        }
    }

    /** Chọn engine theo profile, không dùng grader gắn chặt với một đề cho testcase chung. */
    private void materializeEngine(Path dir, String engineType, List<Map<String, Object>> items) throws Exception {
        if (!COMMON_ENGINE.equals(engineType)) return;
        String generated = renderEngine(engineType, items);
        // Chỉ ghi hai file sau khi source đã qua chốt kiểm tra, tránh refresh nửa vời:
        // grader.dart mới nhưng exam_test.dart vẫn là bản cũ.
        copyClasspathEngine(dir, "common-testcase-engine/grader.dart", "grader.dart");
        Files.writeString(dir.resolve("exam_test.dart"), generated, StandardCharsets.UTF_8);
    }

    /** Sinh source exam_test.dart trong bộ nhớ để Save và Preview dùng đúng cùng một đường code. */
    private String renderEngine(String engineType, List<Map<String, Object>> items) throws Exception {
        if (!COMMON_ENGINE.equals(engineType))
            throw new IllegalStateException("Engine testcase không được hỗ trợ: " + engineType);
        String engine = readClasspathEngine("common-testcase-engine/exam_test.dart");
        String generated = injectCustomTestcases(engine, enabledCustomItems(items));
        String delimiterError = delimiterProblem(generated);
        if (delimiterError != null)
            throw new IllegalStateException("Engine exam_test.dart không hợp lệ: " + delimiterError + ".");
        return generated;
    }

    /**
     * Ghi hợp đồng ra cạnh engine: contract.json cho engine đọc lúc chấm, contract.md cho
     * giáo viên dán vào đề. Hợp đồng rỗng thì XÓA file cũ, nếu không đề đã gỡ hợp đồng vẫn
     * bị chấm theo bản cũ còn sót lại trong thư mục.
     */
    private void materializeContract(Path dir, Map<String, Object> contract) throws Exception {
        Path json = dir.resolve("contract.json");
        Path doc = dir.resolve("contract.md");
        if (TestcaseContractSupport.isEmpty(contract)) {
            Files.deleteIfExists(json);
            Files.deleteIfExists(doc);
            return;
        }
        Files.writeString(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(contract),
                StandardCharsets.UTF_8);
        Files.writeString(doc, TestcaseContractSupport.renderRequirements(contract)
                + "\n## Đoạn code phát cho sinh viên\n\n```dart\n"
                + TestcaseContractSupport.renderStarterDart(contract) + "```\n",
                StandardCharsets.UTF_8);
    }

    /** Danh mục cách dò + bộ key gợi ý để frontend dựng Khu vực 0. */
    public Map<String, Object> contractCatalog() {
        return TestcaseContractSupport.catalog();
    }

    /** Xem trước hai thứ giáo viên cần: yêu cầu dán vào đề và code phát cho sinh viên. */
    public Map<String, Object> contractPreview(Object rawContract) {
        Map<String, Object> contract = TestcaseContractSupport.normalize(rawContract);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", contract);
        out.put("requirements_text", TestcaseContractSupport.renderRequirements(contract));
        out.put("starter_dart", TestcaseContractSupport.renderStarterDart(contract));
        return out;
    }

    private void copyClasspathEngine(Path dir, String resourceName, String targetName) throws Exception {
        ClassPathResource resource = new ClassPathResource(resourceName);
        if (!resource.exists()) throw new IllegalStateException("Thiếu engine testcase: " + resourceName);
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, dir.resolve(targetName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readClasspathEngine(String resourceName) throws Exception {
        ClassPathResource resource = new ClassPathResource(resourceName);
        if (!resource.exists()) throw new IllegalStateException("Thiếu engine testcase: " + resourceName);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Testcase code tay đang bật — đúng những mục sẽ có mặt trong skills_matrix. */
    private List<Map<String, Object>> enabledCustomItems(List<Map<String, Object>> items) {
        return items.stream()
                .filter(item -> CUSTOM_RUNNER.equals(text(item.get("runner"))))
                .filter(item -> bool(item.get("enabled"), true))
                .toList();
    }

    /**
     * Thay vùng CUSTOM_TESTCASES của engine bằng các testWidgets sinh từ code giáo viên.
     * Tên test = instance_id (đã lọc theo {@link #SAFE_INSTANCE_ID}) nên khớp đúng key rubric
     * mà grader.dart dùng để tra điểm.
     */
    private String injectCustomTestcases(String engine, List<Map<String, Object>> customItems) {
        int begin = engine.indexOf(CUSTOM_BEGIN_MARK);
        int end = engine.indexOf(CUSTOM_END_MARK);
        if (begin < 0 || end < 0 || end < begin)
            throw new IllegalStateException("Engine testcase thiếu vùng " + CUSTOM_BEGIN_MARK + ".");
        int from = engine.lastIndexOf('\n', begin) + 1;
        int to = engine.indexOf('\n', end);
        if (to < 0) to = engine.length() - 1;

        StringBuilder block = new StringBuilder();
        block.append("// ─────────────────── ").append(CUSTOM_BEGIN_MARK).append(" ───────────────────\n");
        block.append("// Sinh tự động từ các testcase \"Tự viết code\" của đề. Sửa tay ở đây sẽ bị ghi đè.\n");
        block.append("void _registerCustomTestcase(String testId) {");
        if (customItems.isEmpty()) block.append("}\n");
        else {
            block.append("\n  switch (testId) {\n");
            for (Map<String, Object> item : customItems) {
                block.append("    // ").append(singleLine(text(item.get("name"), ""))).append('\n');
                block.append("    case '").append(item.get("instance_id")).append("':\n");
                block.append("      testWidgets('").append(item.get("instance_id")).append("', (tester) async {\n");
                for (String line : normalizeNewlines(text(item.get("custom_code"), "")).split("\n", -1)) {
                    if (line.isBlank()) block.append('\n');
                    else block.append("        ").append(line.stripTrailing()).append('\n');
                }
                block.append("      });\n");
                block.append("      return;\n");
            }
            block.append("  }\n}\n");
        }
        block.append("// ──────────────────── ").append(CUSTOM_END_MARK).append(" ────────────────────");
        return engine.substring(0, from) + block + engine.substring(to);
    }

    /** Tên testcase nằm trong comment một dòng nên không được chứa xuống dòng. */
    private String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    /** Đảm bảo thư viện testcase dùng chung luôn có sẵn trước mỗi request. */
    private synchronized void ensureReferenceTemplatesLoaded() {
        if (templates.isEmpty() && !loadClasspathTemplates("common-testcase-templates.json", COMMON_ENGINE))
            log.error("Không nạp được thư viện testcase dùng chung.");
    }

    /**
     * Chỉ cho phép tiếp tục đúng đề Draft/Publish được tạo bởi chức năng template này.
     * KHÔNG so created_by nữa: app bỏ đăng nhập nên mọi đề (kể cả đề cũ do tài khoản GV
     * trước đây tạo) đều phải sửa/publish lại được.
     */
    private boolean isTemplateCreatedExam(Exam exam) {
        return exam.getTestcaseConfigJson() != null && !exam.getTestcaseConfigJson().isBlank();
    }

    private Map<String, Object> response(Exam exam, Map<String, Object> config,
                                         List<Map<String, Object>> items, boolean publish,
                                         String syntaxWarning) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exam_id", exam.getExamId());
        out.put("status", config.get("status"));
        out.put("version", config.get("version"));
        out.put("template_version", config.get("template_version"));
        out.put("engine_type", config.get("engine_type"));
        out.put("profile_id", config.get("profile_id"));
        out.put("items", items);
        out.put("total_weight", totalWeight(items));
        boolean engineReady = exam.getTestcasePath() != null
                && Files.exists(Path.of(exam.getTestcasePath()).resolve("exam_test.dart"))
                && Files.exists(Path.of(exam.getTestcasePath()).resolve("grader.dart"));
        out.put("engine_ready", engineReady);
        if (publish && !engineReady) {
            out.put("warning", "Đã Publish cấu hình, nhưng đề chưa có exam_test.dart và grader.dart để chạy chấm.");
        } else if (syntaxWarning != null) {
            out.put("warning", syntaxWarning);
        }
        return out;
    }

    private List<Map<String, Object>> normalizeItems(String examId, Object rawItems,
                                                       Map<String, Map<String, Object>> oldById,
                                                       String actor) {
        if (!(rawItems instanceof List<?> list)) throw new IllegalArgumentException("items phải là một mảng testcase");
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int index = 1;
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?>)) throw new IllegalArgumentException("Mỗi testcase phải là object");
            Map<String, Object> input = castMap(raw);
            String templateId = text(input.get("template_id"));
            if (isCustomItem(input)) {
                out.add(normalizeCustomItem(examId, input, index++, ids, oldById, actor));
                continue;
            }
            Map<String, Object> template = templates.get(templateId);
            if (template == null) throw new IllegalArgumentException("Template không tồn tại: " + templateId);
            String templateEngine = text(template.get("engine_type"), COMMON_ENGINE);
            Skill skill = findSkill(text(template.get("skill_code")));
            if (skill == null || Boolean.TRUE.equals(skill.getDeprecated()))
                throw new IllegalArgumentException("skill_code không còn hợp lệ trong syllabus: " + template.get("skill_code"));

            String instanceId = text(input.get("instance_id"));
            if (instanceId == null || instanceId.isBlank())
                instanceId = examId + "_item_" + String.format("%02d", index);
            if (!SAFE_INSTANCE_ID.matcher(instanceId).matches())
                throw new IllegalArgumentException("instance_id không hợp lệ: " + instanceId);
            if (!ids.add(instanceId)) throw new IllegalArgumentException("Trùng instance_id: " + instanceId);

            Map<String, Object> params = parameters(template, input.get("parameters"));
            boolean generatedCustom = isGeneratedCustomTemplate(template);
            if (generatedCustom) {
                validateGeneratedCustomParameters(template, params, instanceId);
            } else if (COMMON_ENGINE.equals(templateEngine)) {
                validateCommonParameters(text(template.get("runner"), ""), params, instanceId);
            }
            String difficulty = text(input.get("difficulty"));
            if (difficulty == null || difficulty.isBlank()) difficulty = text(template.get("difficulty"));
            if (difficulty == null || !DIFFICULTIES.contains(difficulty.toLowerCase()))
                throw new IllegalArgumentException("difficulty không hợp lệ ở " + instanceId);
            difficulty = difficulty.toLowerCase();
            double weight = number(input.get("weight"), number(template.get("weight_default"), 1));
            if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("weight không hợp lệ ở " + instanceId);

            Map<String, Object> previous = oldById.get(instanceId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instance_id", instanceId);
            item.put("template_id", templateId);
            item.put("template_version", text(template.get("template_version"), "1.0"));
            item.put("engine_type", templateEngine);
            item.put("runner", text(template.get("runner"), ""));
            item.put("skill_code", text(template.get("skill_code")));
            item.put("layer", text(template.get("layer")));
            item.put("testcase_group", text(template.get("testcase_group"),
                    testcaseGroup(template.get("runner"), template.get("layer"))));
            item.put("name", text(template.get("name")));
            item.put("description", text(template.get("description")));
            item.put("difficulty", difficulty);
            item.put("enabled", bool(input.get("enabled"), true));
            item.put("order", index++);
            item.put("weight", weight);
            item.put("parameters", params);
            String generatedExpected = renderExpected(text(template.get("expected_template")), params);
            String configuredExpected = text(input.get("expected"));
            boolean expectedCustom = bool(input.get("expected_custom"), false)
                    || (configuredExpected != null && !configuredExpected.equals(generatedExpected));
            // Expected nhập từ UI là metadata hiển thị trong skills_matrix và result_json;
            // chỉ dùng bản tự sinh khi giáo viên chưa nhập nội dung riêng.
            item.put("expected", configuredExpected == null || configuredExpected.isBlank()
                    ? generatedExpected : configuredExpected);
            item.put("expected_custom", expectedCustom);
            item.put("execution_key", text(template.get("execution_key"), templateId));
            if (generatedCustom) {
                item.put("generated_custom", true);
                item.put("custom_code", generateCustomCode(template, params));
            }
            String groupId = text(input.get("group_id"));
            if (groupId != null && !groupId.isBlank()) {
                if (!COMMON_ENGINE.equals(templateEngine))
                    throw new IllegalArgumentException("Testcase này không thuộc thư viện dùng chung: " + instanceId);
                if (generatedCustom)
                    throw new IllegalArgumentException("Testcase public contract sinh code phải chạy độc lập: "
                            + instanceId);
                if (!SAFE_INSTANCE_ID.matcher(groupId).matches())
                    throw new IllegalArgumentException("group_id không hợp lệ ở " + instanceId);
                item.put("group_id", groupId);
                String groupName = text(input.get("group_name"));
                item.put("group_name", groupName == null || groupName.isBlank() ? groupId : groupName.trim());
            }
            item.put("created_by", previous != null && previous.get("created_by") != null
                    ? previous.get("created_by") : actor);
            item.put("created_at", previous != null && previous.get("created_at") != null
                    ? previous.get("created_at") : Instant.now().toString());
            out.add(item);
        }
        validateGroups(out);
        return out;
    }

    /** Mỗi group phải có từ hai testcase con và chỉ gom các testcase common. */
    private void validateGroups(List<Map<String, Object>> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> itemIds = new LinkedHashSet<>();
        for (Map<String, Object> item : items) itemIds.add(text(item.get("instance_id")));
        for (Map<String, Object> item : items) {
            String groupId = text(item.get("group_id"));
            if (groupId == null || groupId.isBlank()) continue;
            if (groupId.equals(item.get("instance_id")) || itemIds.contains(groupId))
                throw new IllegalArgumentException("group_id không được trùng instance_id: " + groupId);
            counts.put(groupId, counts.getOrDefault(groupId, 0) + 1);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() < 2)
                throw new IllegalArgumentException("Nhóm testcase " + entry.getKey() + " phải có ít nhất 2 testcase con.");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  QUẢN LÝ THƯ VIỆN TESTCASE (KHU VỰC 2)
    //  Giáo viên thêm template mới, sửa template có sẵn và ẩn template không dùng.
    //  Bản gốc trong classpath không bị ghi đè: DB chỉ lưu phần khác biệt.
    // ════════════════════════════════════════════════════════════════════════════

    /** Danh mục runner + mô tả tham số để frontend dựng form thêm/sửa testcase. */
    public Map<String, Object> runnerCatalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runners", TestcaseRunnerCatalog.runners());
        out.put("semantic_keys", TestcaseRunnerCatalog.SEMANTIC_KEYS);
        out.put("target_types", TestcaseRunnerCatalog.TARGET_TYPES);
        out.put("layers", List.of("SCREEN", "BLACKBOX", "RESPONSIVE"));
        out.put("difficulties", List.of("basic", "intermediate", "advanced"));
        out.put("testcase_groups", TESTCASE_GROUP_LABELS);
        return out;
    }

    /**
     * Khi bật require_keys, mọi key mà runner thật sự dùng phải xuất hiện trong
     * contract phát cho sinh viên. Nếu không, testcase có thể trỏ đúng key nhưng đề
     * lại không công bố key đó — một lỗi contract chứ không phải lỗi sinh viên.
     */
    private void validateContractCoversSelectedKeys(List<Map<String, Object>> items,
                                                    Map<String, Object> contract) {
        if (!Boolean.TRUE.equals(contract.get("require_keys"))) return;
        Set<String> declared = new LinkedHashSet<>();
        Object rawRows = contract.get("keys");
        if (rawRows instanceof List<?> rows) {
            for (Object raw : rows) {
                if (!(raw instanceof Map<?, ?>)) continue;
                String key = text(castMap(raw).get("key"), "");
                if (!key.isBlank()) declared.add(key);
            }
        }

        Set<String> used = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            if (!bool(item.get("enabled"), true)) continue;
            Map<String, Object> definition = runnerDefinition(text(item.get("runner"), ""));
            if (definition == null) continue; // public/source contract không định vị UI bằng key
            Map<String, Object> parameters = map(item.get("parameters"));
            Object rawParameters = definition.get("parameters");
            if (!(rawParameters instanceof List<?> parameterRows)) continue;
            for (Object rawParameter : parameterRows) {
                if (!(rawParameter instanceof Map<?, ?>)) continue;
                Map<String, Object> parameter = castMap(rawParameter);
                String type = text(parameter.get("type"), "");
                String name = text(parameter.get("name"), "");
                if ("semantic_key".equals(type)) {
                    String key = text(parameters.get(name), "");
                    if (!key.isBlank()) used.add(key);
                } else if ("semantic_keys".equals(type)) {
                    String value = text(parameters.get(name), "");
                    if (value.isBlank()) continue;
                    for (String key : value.split(",")) {
                        if (!key.trim().isEmpty()) used.add(key.trim());
                    }
                }
            }
        }
        used.removeAll(declared);
        if (!used.isEmpty()) {
            throw new IllegalArgumentException("Contract bật require_keys nhưng chưa công bố "
                    + "các key testcase đang dùng: " + String.join(", ", used)
                    + ". Hãy thêm chúng vào Khu vực 0 trước khi lưu.");
        }
    }

    /**
     * Bổ sung schema mới theo kiểu tương thích ngược. Các template source cũ vẫn
     * dùng ba trường paths/tokens; đề mới có thể dùng sourceChecksJson để gắn token
     * vào đúng từng file, tránh pass nhầm do token nằm ở file khác.
     */
    private void enrichGeneratedTemplateSchema(Map<String, Object> row) {
        if (!"SOURCE_CONTAINS".equals(text(row.get("code_generator"), ""))) return;
        Map<String, Object> schema = new LinkedHashMap<>(map(row.get("parameters_schema")));
        schema.putIfAbsent("sourceChecksJson", "[]");
        row.put("parameters_schema", schema);
    }

    /**
     * Nguồn đối chiếu học liệu → kỹ năng → template tái sử dụng. Đây là dữ liệu hướng dẫn
     * chọn testcase, không phải một pack để nạp hàng loạt vào mọi đề.
     */
    public Map<String, Object> curriculumSource() {
        try (InputStream in = new ClassPathResource(
                "prm393-curriculum-testcase-source.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Không đọc được nguồn testcase từ học liệu: "
                    + e.getMessage(), e);
        }
    }

    public Map<String, Object> createTemplate(Map<String, Object> body, String actor) {
        ensureReferenceTemplatesLoaded();
        String templateId = text(body == null ? null : body.get("template_id"));
        if (templateId == null || templateId.isBlank())
            throw new IllegalArgumentException("Vui lòng nhập mã testcase (template_id).");
        templateId = templateId.trim().toUpperCase();
        if (!TEMPLATE_ID_PATTERN.matcher(templateId).matches())
            throw new IllegalArgumentException("Mã testcase chỉ gồm chữ, số và _ (3-80 ký tự): " + templateId);
        if (templates.containsKey(templateId))
            throw new IllegalArgumentException("Mã testcase đã tồn tại: " + templateId);
        if (CUSTOM_TEMPLATE_ID.equals(templateId))
            throw new IllegalArgumentException(CUSTOM_TEMPLATE_ID + " là mã dành riêng cho testcase tự viết code.");

        Map<String, Object> row = buildTemplateRow(templateId, body, null);
        TestcaseTemplate stored = new TestcaseTemplate();
        stored.setTemplateId(templateId);
        stored.setOrigin("CUSTOM");
        stored.setCreatedBy(actor);
        stored.setUpdatedBy(actor);
        stored.setPayloadJson(writeJson(row));
        templateRepository.save(stored);
        loadTemplates();
        return getTemplate(templateId);
    }

    public Map<String, Object> updateTemplate(String rawId, Map<String, Object> body, String actor) {
        ensureReferenceTemplatesLoaded();
        String templateId = rawId == null ? "" : rawId.trim();
        Map<String, Object> current = templates.get(templateId);
        if (current == null) throw new IllegalArgumentException("Không tìm thấy testcase template: " + templateId);

        Map<String, Object> row = buildTemplateRow(templateId, body, current);
        TestcaseTemplate stored = templateRepository.findById(templateId).orElseGet(() -> {
            // Sửa template gốc lần đầu: tạo bản đè, file classpath vẫn nguyên vẹn.
            TestcaseTemplate fresh = new TestcaseTemplate();
            fresh.setTemplateId(templateId);
            fresh.setOrigin(builtinTemplates.containsKey(templateId) ? "OVERRIDE" : "CUSTOM");
            fresh.setCreatedBy(actor);
            return fresh;
        });
        stored.setPayloadJson(writeJson(row));
        stored.setUpdatedBy(actor);
        templateRepository.save(stored);
        loadTemplates();
        return getTemplate(templateId);
    }

    /**
     * "Xóa" testcase khỏi Khu vực 2 = ẩn đi, KHÔNG xóa cứng. Đề đã lưu chỉ giữ template_id;
     * xóa hẳn sẽ làm những đề đó không mở/lưu lại được nữa.
     */
    public Map<String, Object> hideTemplate(String rawId, String actor) {
        ensureReferenceTemplatesLoaded();
        String templateId = rawId == null ? "" : rawId.trim();
        Map<String, Object> current = templates.get(templateId);
        if (current == null) throw new IllegalArgumentException("Không tìm thấy testcase template: " + templateId);

        TestcaseTemplate stored = templateRepository.findById(templateId).orElseGet(() -> {
            TestcaseTemplate fresh = new TestcaseTemplate();
            fresh.setTemplateId(templateId);
            fresh.setOrigin(builtinTemplates.containsKey(templateId) ? "OVERRIDE" : "CUSTOM");
            fresh.setCreatedBy(actor);
            fresh.setPayloadJson(writeJson(current));
            return fresh;
        });
        stored.setHidden(true);
        stored.setUpdatedBy(actor);
        templateRepository.save(stored);
        loadTemplates();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("template_id", templateId);
        out.put("hidden", true);
        out.put("usage", countUsage(templateId));
        out.put("message", "Đã ẩn khỏi thư viện. Các đề đang dùng testcase này vẫn chấm bình thường.");
        return out;
    }

    /** Bỏ ẩn, và với template gốc thì trả luôn nội dung về đúng bản trong classpath. */
    public Map<String, Object> restoreTemplate(String rawId, String actor) {
        ensureReferenceTemplatesLoaded();
        String templateId = rawId == null ? "" : rawId.trim();
        TestcaseTemplate stored = templateRepository.findById(templateId).orElse(null);
        if (stored == null) {
            if (!templates.containsKey(templateId))
                throw new IllegalArgumentException("Không tìm thấy testcase template: " + templateId);
            return getTemplate(templateId);
        }
        if (builtinTemplates.containsKey(templateId)) {
            templateRepository.delete(stored);   // quay về đúng bản gốc
        } else {
            stored.setHidden(false);
            stored.setUpdatedBy(actor);
            templateRepository.save(stored);
        }
        loadTemplates();
        return getTemplate(templateId);
    }

    /** Số đề đang dùng template — hiển thị cảnh báo trước khi ẩn. */
    private int countUsage(String templateId) {
        try {
            String needle = "\"template_id\":\"" + templateId + "\"";
            return (int) examRepository.findAll().stream()
                    .map(Exam::getTestcaseConfigJson)
                    .filter(json -> json != null && json.replace(" ", "").contains(needle))
                    .count();
        } catch (Exception e) {
            log.warn("Không đếm được số đề dùng template {}: {}", templateId, e.getMessage());
            return 0;
        }
    }

    /**
     * Dựng và kiểm tra một template trước khi lưu. Tham số mặc định phải qua đúng bộ
     * validate dùng khi lưu đề, nếu không giáo viên sẽ chỉ thấy lỗi lúc kéo vào Khu vực 3.
     */
    private Map<String, Object> buildTemplateRow(String templateId, Map<String, Object> body,
                                                 Map<String, Object> current) {
        if (body == null) throw new IllegalArgumentException("Thiếu nội dung testcase");
        Map<String, Object> base = current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);

        String runner = text(body.get("runner"), text(base.get("runner")));
        Map<String, Object> catalog = runnerDefinition(runner);
        if (catalog == null && isGeneratedCustomTemplate(base)) {
            catalog = new LinkedHashMap<>();
            catalog.put("runner", CUSTOM_RUNNER);
            catalog.put("label", text(base.get("name"), "Public contract logic"));
            catalog.put("description", text(base.get("description"), ""));
            catalog.put("layer_default", text(base.get("layer"), "BLACKBOX"));
            catalog.put("parameters_schema", map(base.get("parameters_schema")));
        }
        if (catalog == null)
            throw new IllegalArgumentException("Runner không tồn tại trong engine: " + runner);

        String name = text(body.get("name"), text(base.get("name")));
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Vui lòng nhập tên testcase.");
        if (name.length() > 200) throw new IllegalArgumentException("Tên testcase quá dài (tối đa 200 ký tự).");

        String skillCode = text(body.get("skill_code"), text(base.get("skill_code")));
        Skill skill = findSkill(skillCode);
        if (skill == null || Boolean.TRUE.equals(skill.getDeprecated()))
            throw new IllegalArgumentException("Chủ đề (skill_code) không có trong syllabus: " + skillCode);

        String layer = text(body.get("layer"), text(base.get("layer"),
                text(catalog.get("layer_default"), "SCREEN"))).toUpperCase();
        if (!TEMPLATE_LAYERS.contains(layer))
            throw new IllegalArgumentException("layer không hợp lệ: " + layer);

        String difficulty = text(body.get("difficulty"), text(base.get("difficulty"), "basic")).toLowerCase();
        if (!DIFFICULTIES.contains(difficulty))
            throw new IllegalArgumentException("difficulty không hợp lệ: " + difficulty);

        double weight = number(body.get("weight_default"), number(base.get("weight_default"), 1));
        if (!Double.isFinite(weight) || weight < 0)
            throw new IllegalArgumentException("Điểm mặc định không hợp lệ.");

        Map<String, Object> schema = map(catalog.get("parameters_schema"));
        Map<String, Object> supplied = body.get("parameters_schema") instanceof Map<?, ?>
                ? castMap(body.get("parameters_schema")) : map(base.get("parameters_schema"));
        for (String suppliedKey : supplied.keySet()) {
            if (!schema.containsKey(suppliedKey))
                throw new IllegalArgumentException("Runner " + runner + " không có tham số: " + suppliedKey);
        }
        Map<String, Object> parameters = new LinkedHashMap<>(schema);
        parameters.putAll(supplied);
        if (isGeneratedCustomTemplate(base)) {
            validateGeneratedCustomParameters(base, parameters, templateId);
        } else {
            validateCommonParameters(runner, parameters, templateId);
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("template_id", templateId);
        row.put("template_version", text(base.get("template_version"), "custom-tpl-v1"));
        row.put("engine_type", COMMON_ENGINE);
        row.put("profile_id", "COMMON_SEMANTIC_V1");
        row.put("runner", runner);
        if (base.get("code_generator") != null) {
            row.put("code_generator", base.get("code_generator"));
        }
        row.put("skill_code", skill.getCode());
        row.put("layer", layer);
        row.put("name", name.trim());
        row.put("description", text(body.get("description"),
                text(base.get("description"), text(catalog.get("description"), ""))));
        row.put("difficulty", difficulty);
        row.put("weight_default", weight);
        row.put("parameters_schema", parameters);
        String expected = text(body.get("expected_template"), text(base.get("expected_template")));
        row.put("expected_template", expected == null || expected.isBlank()
                ? defaultExpectedTemplate(catalog, parameters) : expected.trim());
        String group = text(body.get("testcase_group"), text(base.get("testcase_group"),
                testcaseGroup(runner, layer))).toUpperCase();
        row.put("testcase_group", TESTCASE_GROUP_LABELS.containsKey(group)
                ? group : testcaseGroup(runner, layer));
        return row;
    }

    /** Expected mặc định liệt kê tham số để giáo viên thấy ngay testcase kiểm tra cái gì. */
    private String defaultExpectedTemplate(Map<String, Object> catalog, Map<String, Object> parameters) {
        StringBuilder out = new StringBuilder(text(catalog.get("label"), "Testcase"));
        List<String> parts = new ArrayList<>();
        for (String key : parameters.keySet()) parts.add(key + "={" + key + "}");
        if (!parts.isEmpty()) out.append(" — ").append(String.join(", ", parts));
        return out.append('.').toString();
    }

    private Map<String, Object> runnerDefinition(String runner) {
        if (runner == null || runner.isBlank()) return null;
        for (Map<String, Object> row : TestcaseRunnerCatalog.runners()) {
            if (runner.equals(row.get("runner"))) return row;
        }
        return null;
    }

    private String writeJson(Map<String, Object> row) {
        try {
            return mapper.writeValueAsString(row);
        } catch (Exception e) {
            throw new IllegalStateException("Không lưu được testcase template: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  TESTCASE TỰ VIẾT CODE
    //  Dùng khi yêu cầu của đề không diễn đạt được bằng runner dữ liệu ở thư viện chung.
    //  Giáo viên chỉ gõ THÂN test; hệ thống bọc testWidgets('<instance_id>', ...) rồi chèn
    //  vào vùng CUSTOM_TESTCASES của engine, nên tên test luôn khớp key trong skills_matrix.
    // ════════════════════════════════════════════════════════════════════════════

    private boolean isCustomItem(Map<String, Object> input) {
        String templateId = text(input.get("template_id"));
        // Template sinh code vẫn dùng runner CUSTOM_CODE ở file cuối, nhưng bản thân nó là
        // template có schema tham số — không được đẩy nhầm sang form code tay của giáo viên.
        return CUSTOM_TEMPLATE_ID.equals(templateId)
                || (CUSTOM_RUNNER.equals(text(input.get("runner")))
                && (templateId == null || !templates.containsKey(templateId)));
    }

    private boolean isGeneratedCustomTemplate(Map<String, Object> template) {
        return CUSTOM_RUNNER.equals(text(template.get("runner")))
                && text(template.get("code_generator")) != null;
    }

    /**
     * Kiểm tra contract của ba mẫu logic sinh tự động. Chúng chỉ gọi public API đã phát sẵn
     * trong starter qua {@code main.dart}; không cho nhập biểu thức Dart tự do và không tạo
     * grading adapter.
     */
    private void validateGeneratedCustomParameters(Map<String, Object> template,
                                                     Map<String, Object> params,
                                                     String instanceId) {
        String generator = text(template.get("code_generator"), "");
        if ("SOURCE_CONTAINS".equals(generator)) {
            Object caseSensitive = params.get("caseSensitive");
            if (!(caseSensitive instanceof Boolean)
                    && !Set.of("true", "false").contains(
                    String.valueOf(caseSensitive).toLowerCase())) {
                throw new IllegalArgumentException(
                        "caseSensitive phải là boolean ở " + instanceId);
            }
            List<Object> exactChecks = parseJsonList(
                    params.getOrDefault("sourceChecksJson", "[]"),
                    "sourceChecksJson", instanceId);
            if (exactChecks.size() > 12) {
                throw new IllegalArgumentException(
                        "sourceChecksJson không được quá 12 file ở " + instanceId);
            }
            for (int index = 0; index < exactChecks.size(); index++) {
                Object raw = exactChecks.get(index);
                if (!(raw instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException(
                            "sourceChecksJson[" + index + "] phải là object ở " + instanceId);
                }
                Map<String, Object> check = castMap(raw);
                validateSafeSourcePath(check.get("path"),
                        "sourceChecksJson[" + index + "].path", instanceId);
                List<Object> requiredForFile = nestedSourceTokens(check.get("requiredTokens"),
                        "sourceChecksJson[" + index + "].requiredTokens", instanceId);
                List<Object> forbiddenForFile = nestedSourceTokens(check.get("forbiddenTokens"),
                        "sourceChecksJson[" + index + "].forbiddenTokens", instanceId);
                if (requiredForFile.isEmpty() && forbiddenForFile.isEmpty()) {
                    throw new IllegalArgumentException("sourceChecksJson[" + index
                            + "] phải có requiredTokens hoặc forbiddenTokens ở " + instanceId);
                }
            }
            if (!exactChecks.isEmpty()) return;
            List<Object> paths = parseJsonList(params.get("sourcePathsJson"),
                    "sourcePathsJson", instanceId);
            if (paths.isEmpty() || paths.size() > 12) {
                throw new IllegalArgumentException(
                        "sourcePathsJson phải có 1-12 file ở " + instanceId);
            }
            for (Object raw : paths) {
                validateSafeSourcePath(raw, "sourcePathsJson", instanceId);
            }
            List<Object> required = parseJsonList(params.get("requiredTokensJson"),
                    "requiredTokensJson", instanceId);
            List<Object> forbidden = parseJsonList(params.get("forbiddenTokensJson"),
                    "forbiddenTokensJson", instanceId);
            if (required.isEmpty()) {
                throw new IllegalArgumentException(
                        "requiredTokensJson phải có ít nhất một token ở " + instanceId);
            }
            validateSourceTokens(required, "requiredTokensJson", instanceId);
            validateSourceTokens(forbidden, "forbiddenTokensJson", instanceId);
            return;
        }
        String contractPath = text(params.get("contractPath"), "").replace('\\', '/');
        if (!SAFE_CONTRACT_PATH.matcher(contractPath).matches() || contractPath.contains("..")) {
            throw new IllegalArgumentException("contractPath phải là file .dart an toàn dưới lib/ ở "
                    + instanceId);
        }
        String callable = text(params.get("callable"), "");
        if (!DART_CALLABLE.matcher(callable).matches()) {
            throw new IllegalArgumentException("callable không phải tên hàm/static method Dart hợp lệ ở "
                    + instanceId);
        }
        List<Object> arguments = parseJsonList(params.get("argumentsJson"), "argumentsJson", instanceId);
        if (arguments.size() > 12) {
            throw new IllegalArgumentException("argumentsJson không được quá 12 đối số ở " + instanceId);
        }
        double timeout = number(params.get("timeoutMs"), Double.NaN);
        if (!Double.isFinite(timeout) || timeout < 100 || timeout > 10000 || timeout != Math.rint(timeout)) {
            throw new IllegalArgumentException("timeoutMs phải là số nguyên 100-10000 ở " + instanceId);
        }
        switch (generator) {
            case "PUBLIC_FUNCTION_RESULT" -> parseJsonValue(
                    params.get("expectedJson"), "expectedJson", instanceId);
            case "PUBLIC_FUNCTION_THROWS" -> {
                String exceptionType = text(params.get("exceptionType"), "");
                if (!DART_CALLABLE.matcher(exceptionType).matches()) {
                    throw new IllegalArgumentException("exceptionType không hợp lệ ở " + instanceId);
                }
            }
            case "PUBLIC_STREAM_EVENTS" -> parseJsonList(
                    params.get("expectedEventsJson"), "expectedEventsJson", instanceId);
            default -> throw new IllegalArgumentException(
                    "code_generator không được hỗ trợ ở " + instanceId + ": " + generator);
        }
    }

    private void validateSafeSourcePath(Object raw, String field, String instanceId) {
        String path = raw instanceof String ? ((String) raw).replace('\\', '/') : "";
        if (!SAFE_SOURCE_PATH.matcher(path).matches() || path.contains("..")) {
            throw new IllegalArgumentException(
                    field + " chứa đường dẫn source không an toàn ở " + instanceId + ": " + raw);
        }
    }

    private List<Object> nestedSourceTokens(Object raw, String field, String instanceId) {
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " phải là JSON array ở " + instanceId);
        }
        List<Object> tokens = new ArrayList<>(list);
        validateSourceTokens(tokens, field, instanceId);
        return tokens;
    }

    private void validateSourceTokens(List<Object> tokens, String field, String instanceId) {
        if (tokens.size() > 24) {
            throw new IllegalArgumentException(field + " không được quá 24 token ở " + instanceId);
        }
        for (Object raw : tokens) {
            if (!(raw instanceof String token) || token.isBlank() || token.length() > 200) {
                throw new IllegalArgumentException(
                        field + " chỉ nhận chuỗi 1-200 ký tự ở " + instanceId);
            }
        }
    }

    private String generateCustomCode(Map<String, Object> template, Map<String, Object> params) {
        String generator = text(template.get("code_generator"), "");
        if ("SOURCE_CONTAINS".equals(generator)) {
            List<Object> exactChecks = parseJsonList(
                    params.getOrDefault("sourceChecksJson", "[]"),
                    "sourceChecksJson", generator);
            List<Object> paths = parseJsonList(
                    params.get("sourcePathsJson"), "sourcePathsJson", generator);
            List<Object> required = parseJsonList(
                    params.get("requiredTokensJson"), "requiredTokensJson", generator);
            List<Object> forbidden = parseJsonList(
                    params.get("forbiddenTokensJson"), "forbiddenTokensJson", generator);
            boolean caseSensitive = bool(params.get("caseSensitive"), true);
            if (!exactChecks.isEmpty()) {
                StringBuilder exactCode = new StringBuilder();
                for (int index = 0; index < exactChecks.size(); index++) {
                    Map<String, Object> check = castMap(exactChecks.get(index));
                    String path = text(check.get("path"), "").replace('\\', '/');
                    List<Object> requiredForFile = nestedSourceTokens(check.get("requiredTokens"),
                            "requiredTokens", generator);
                    List<Object> forbiddenForFile = nestedSourceTokens(check.get("forbiddenTokens"),
                            "forbiddenTokens", generator);
                    String fileVar = "sourceFile" + index;
                    String sourceVar = "source" + index;
                    exactCode.append("final ").append(fileVar).append(" = File(")
                            .append(dartLiteral(path)).append(");\n")
                            .append("expect(").append(fileVar)
                            .append(".existsSync(), isTrue, reason: 'Không tìm thấy source contract: ")
                            .append(path.replace("'", "\\'")).append("');\n")
                            .append("final ").append(sourceVar).append(" = _sourceWithoutComments(")
                            .append(fileVar).append(".readAsStringSync(), ")
                            .append(dartLiteral(path)).append(");\n");
                    appendSourceAssertions(exactCode, sourceVar, path, requiredForFile,
                            forbiddenForFile, caseSensitive);
                }
                return exactCode.toString().stripTrailing();
            }
            StringBuilder code = new StringBuilder()
                    .append("final sourcePaths = <String>")
                    .append(dartLiteral(paths)).append(";\n")
                    .append("final sourceParts = <String>[];\n")
                    .append("for (final path in sourcePaths) {\n")
                    .append("  final file = File(path);\n")
                    .append("  expect(file.existsSync(), isTrue, reason: 'Không tìm thấy source contract: $path');\n")
                    .append("  sourceParts.add(_sourceWithoutComments(file.readAsStringSync(), path));\n")
                    .append("}\n")
                    .append("final source = sourceParts.join('\\n');\n");
            appendSourceAssertions(code, "source", String.join(", ", paths.stream()
                    .map(String::valueOf).toList()), required, forbidden, caseSensitive);
            return code.toString().stripTrailing();
        }
        String contractPath = text(params.get("contractPath"), "").replace('\\', '/');
        String callable = text(params.get("callable"), "");
        List<Object> arguments = parseJsonList(params.get("argumentsJson"), "argumentsJson", callable);
        String invocation = "student_app." + callable + "("
                + arguments.stream().map(this::dartLiteral).reduce((a, b) -> a + ", " + b).orElse("")
                + ")";
        int timeoutMs = (int) number(params.get("timeoutMs"), 3000);
        String timeout = "const Duration(milliseconds: " + timeoutMs + ")";
        String prelude = "final contractFile = File(" + dartLiteral(contractPath) + ");\n"
                + "expect(contractFile.existsSync(), isTrue, "
                + "reason: 'Không tìm thấy public contract: " + contractPath + "');\n";
        return switch (generator) {
            case "PUBLIC_FUNCTION_RESULT" -> {
                String expectedJson = canonicalJson(params.get("expectedJson"), "expectedJson", callable);
                yield prelude
                        + "final actual = await Future<dynamic>.sync(() => " + invocation + ")\n"
                        + "    .timeout(" + timeout + ");\n"
                        + "final expected = jsonDecode(" + dartLiteral(expectedJson) + ");\n"
                        + "expect(actual, equals(expected));";
            }
            case "PUBLIC_FUNCTION_THROWS" -> {
                String exceptionType = text(params.get("exceptionType"), "");
                String message = text(params.get("messageContains"), "");
                StringBuilder code = new StringBuilder(prelude)
                        .append("Object? caught;\n")
                        .append("try {\n")
                        .append("  await Future<dynamic>.sync(() => ").append(invocation).append(")\n")
                        .append("      .timeout(").append(timeout).append(");\n")
                        .append("} catch (error) {\n  caught = error;\n}\n")
                        .append("expect(caught, isNotNull, reason: 'Hàm phải ném ngoại lệ.');\n")
                        .append("expect(caught.runtimeType.toString(), ")
                        .append(dartLiteral(exceptionType)).append(");");
                if (!message.isBlank()) {
                    code.append("\nexpect(caught.toString(), contains(")
                            .append(dartLiteral(message)).append("));");
                }
                yield code.toString();
            }
            case "PUBLIC_STREAM_EVENTS" -> {
                List<Object> expectedEvents = parseJsonList(
                        params.get("expectedEventsJson"), "expectedEventsJson", callable);
                String expectedJson;
                try {
                    expectedJson = mapper.writeValueAsString(expectedEvents);
                } catch (Exception e) {
                    throw new IllegalArgumentException("expectedEventsJson không thể chuẩn hóa ở " + callable);
                }
                yield prelude
                        + "final candidate = " + invocation + ";\n"
                        + "expect(candidate, isA<Stream<dynamic>>());\n"
                        + "final actual = await (candidate as Stream<dynamic>).take("
                        + expectedEvents.size() + ").toList()\n"
                        + "    .timeout(" + timeout + ");\n"
                        + "final expected = jsonDecode(" + dartLiteral(expectedJson) + ");\n"
                        + "expect(actual, equals(expected));";
            }
            default -> throw new IllegalArgumentException("code_generator không được hỗ trợ: " + generator);
        };
    }

    private void appendSourceAssertions(StringBuilder code, String sourceVariable, String path,
                                        List<Object> required, List<Object> forbidden,
                                        boolean caseSensitive) {
        for (Object raw : required) {
            String token = String.valueOf(raw);
            String expression = caseSensitive
                    ? sourceVariable + ".contains(" + dartLiteral(token) + ")"
                    : sourceVariable + ".toLowerCase().contains("
                    + dartLiteral(token.toLowerCase()) + ")";
            code.append("expect(").append(expression)
                    .append(", isTrue, reason: ")
                    .append(dartLiteral("Thiếu source token '" + token + "' trong " + path))
                    .append(");\n");
        }
        for (Object raw : forbidden) {
            String token = String.valueOf(raw);
            String expression = caseSensitive
                    ? sourceVariable + ".contains(" + dartLiteral(token) + ")"
                    : sourceVariable + ".toLowerCase().contains("
                    + dartLiteral(token.toLowerCase()) + ")";
            code.append("expect(").append(expression)
                    .append(", isFalse, reason: ")
                    .append(dartLiteral("Source " + path + " chứa token bị cấm: " + token))
                    .append(");\n");
        }
    }

    private List<Object> parseJsonList(Object raw, String field, String instanceId) {
        Object parsed = parseJsonValue(raw, field, instanceId);
        if (!(parsed instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " phải là JSON array ở " + instanceId);
        }
        return new ArrayList<>(list);
    }

    private Object parseJsonValue(Object raw, String field, String instanceId) {
        String json = text(raw, "").trim();
        if (json.length() > 8000) {
            throw new IllegalArgumentException(field + " quá dài ở " + instanceId);
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " không phải JSON hợp lệ ở " + instanceId);
        }
    }

    private String canonicalJson(Object raw, String field, String instanceId) {
        try {
            return mapper.writeValueAsString(parseJsonValue(raw, field, instanceId));
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " không thể chuẩn hóa ở " + instanceId);
        }
    }

    private String dartLiteral(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) {
            try {
                return mapper.writeValueAsString(string);
            } catch (Exception e) {
                throw new IllegalArgumentException("Không mã hóa được chuỗi tham số Dart.");
            }
        }
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        if (value instanceof List<?> list) {
            return "[" + list.stream().map(this::dartLiteral)
                    .reduce((a, b) -> a + ", " + b).orElse("") + "]";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add(dartLiteral(String.valueOf(entry.getKey())) + ": "
                        + dartLiteral(entry.getValue()));
            }
            return "{" + String.join(", ", entries) + "}";
        }
        throw new IllegalArgumentException("Kiểu JSON không thể chuyển thành Dart literal: "
                + value.getClass().getSimpleName());
    }

    private Map<String, Object> normalizeCustomItem(String examId, Map<String, Object> input, int order,
                                                    Set<String> ids, Map<String, Map<String, Object>> oldById,
                                                    String actor) {
        String instanceId = text(input.get("instance_id"));
        if (instanceId == null || instanceId.isBlank())
            instanceId = examId + "_custom_" + String.format("%02d", order);
        if (!SAFE_INSTANCE_ID.matcher(instanceId).matches())
            throw new IllegalArgumentException("instance_id không hợp lệ: " + instanceId);
        if (!ids.add(instanceId)) throw new IllegalArgumentException("Trùng instance_id: " + instanceId);

        String name = text(input.get("name"));
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Testcase tự viết " + instanceId + " chưa có tên.");
        if (name.length() > 200)
            throw new IllegalArgumentException("Tên testcase " + instanceId + " quá dài (tối đa 200 ký tự).");

        String skillCode = text(input.get("skill_code"));
        Skill skill = findSkill(skillCode);
        if (skill == null || Boolean.TRUE.equals(skill.getDeprecated()))
            throw new IllegalArgumentException("Chủ đề (skill_code) của testcase tự viết " + instanceId
                    + " không có trong syllabus: " + skillCode);

        String difficulty = text(input.get("difficulty"), "basic").toLowerCase();
        if (!DIFFICULTIES.contains(difficulty))
            throw new IllegalArgumentException("difficulty không hợp lệ ở " + instanceId);
        double weight = number(input.get("weight"), 1);
        if (!Double.isFinite(weight) || weight < 0)
            throw new IllegalArgumentException("weight không hợp lệ ở " + instanceId);
        String group = text(input.get("testcase_group"), "LOGIC").toUpperCase();
        if (!TESTCASE_GROUP_LABELS.containsKey(group)) group = "LOGIC";
        if (text(input.get("group_id")) != null && !text(input.get("group_id")).isBlank())
            throw new IllegalArgumentException("Testcase tự viết không gộp được vào testcase lớn: " + instanceId);

        String code = normalizeNewlines(text(input.get("custom_code")));
        validateCustomCode(code, "Testcase \"" + name + "\"");

        Map<String, Object> previous = oldById.get(instanceId);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("instance_id", instanceId);
        item.put("template_id", CUSTOM_TEMPLATE_ID);
        item.put("template_version", "custom-v1");
        item.put("engine_type", COMMON_ENGINE);
        item.put("runner", CUSTOM_RUNNER);
        item.put("skill_code", skill.getCode());
        item.put("layer", CUSTOM_LAYER);
        item.put("testcase_group", group);
        item.put("name", name.trim());
        item.put("description", text(input.get("description"), "Testcase do giáo viên tự viết code."));
        item.put("difficulty", difficulty);
        item.put("enabled", bool(input.get("enabled"), true));
        item.put("order", order);
        item.put("weight", weight);
        item.put("parameters", new LinkedHashMap<String, Object>());
        item.put("expected", text(input.get("expected"), "Đoạn kiểm tra tự viết phải chạy qua toàn bộ assert."));
        item.put("expected_custom", true);
        item.put("execution_key", instanceId);
        item.put("custom_code", code);
        item.put("created_by", previous != null && previous.get("created_by") != null
                ? previous.get("created_by") : actor);
        item.put("created_at", previous != null && previous.get("created_at") != null
                ? previous.get("created_at") : Instant.now().toString());
        return item;
    }

    /**
     * Kiểm tra tĩnh đoạn code giáo viên gõ TRƯỚC khi ghép vào exam_test.dart. Một đoạn hỏng
     * làm cả file test không biên dịch được → toàn bộ lớp bị 0 điểm, nên chặn sớm ở đây.
     * Ném {@link IllegalArgumentException} kèm mô tả tiếng Việt nếu có vấn đề.
     */
    public void validateCustomCode(String code, String label) {
        if (code == null || code.isBlank())
            throw new IllegalArgumentException(label + " chưa có nội dung code.");
        if (code.length() > CUSTOM_CODE_MAX_CHARS)
            throw new IllegalArgumentException(label + " quá dài (tối đa "
                    + CUSTOM_CODE_MAX_CHARS + " ký tự).");
        for (Map.Entry<Pattern, String> rule : CUSTOM_CODE_BANNED) {
            if (rule.getKey().matcher(code).find())
                throw new IllegalArgumentException(label + ": " + rule.getValue() + ".");
        }
        String problem = delimiterProblem(code);
        if (problem != null)
            throw new IllegalArgumentException(label + ": " + problem + ".");
    }

    /**
     * Dò ngoặc/chuỗi/chú thích chưa đóng — lỗi hay gặp nhất khi gõ code trên trình duyệt.
     * Quét một lượt với ngăn xếp ngữ cảnh nên hiểu được chuỗi lồng trong interpolation
     * (vd {@code '${map['k']}'}), raw string và chuỗi ba nháy. Trả null nếu không có vấn đề.
     */
    private String delimiterProblem(String code) {
        Deque<char[]> stack = new ArrayDeque<>();   // [ký tự mở, 1 nếu chuỗi ba nháy, 1 nếu raw]
        int i = 0;
        int length = code.length();
        while (i < length) {
            char[] top = stack.peek();
            char c = code.charAt(i);
            boolean inString = top != null && (top[0] == '\'' || top[0] == '"');
            if (inString) {
                boolean raw = top[2] == 1;
                if (!raw && c == '\\') { i += 2; continue; }
                if (!raw && c == '$' && i + 1 < length && code.charAt(i + 1) == '{') {
                    stack.push(new char[]{'{', 0, 0});   // interpolation: quay lại chế độ code
                    i += 2;
                    continue;
                }
                if (c == top[0]) {
                    if (top[1] == 0) { stack.pop(); i++; continue; }
                    if (i + 2 < length && code.charAt(i + 1) == c && code.charAt(i + 2) == c) {
                        stack.pop();
                        i += 3;
                        continue;
                    }
                }
                if (top[1] == 0 && c == '\n')
                    return "chuỗi ký tự chưa đóng ở dòng " + lineOf(code, i);
                i++;
                continue;
            }
            if (c == '/' && i + 1 < length && code.charAt(i + 1) == '/') {
                while (i < length && code.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < length && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                if (end < 0) return "chú thích /* */ chưa đóng ở dòng " + lineOf(code, i);
                i = end + 2;
                continue;
            }
            boolean raw = c == 'r' && i + 1 < length
                    && (code.charAt(i + 1) == '\'' || code.charAt(i + 1) == '"');
            if (raw || c == '\'' || c == '"') {
                int quoteAt = raw ? i + 1 : i;
                char quote = code.charAt(quoteAt);
                boolean triple = quoteAt + 2 < length
                        && code.charAt(quoteAt + 1) == quote && code.charAt(quoteAt + 2) == quote;
                stack.push(new char[]{quote, (char) (triple ? 1 : 0), (char) (raw ? 1 : 0)});
                i = quoteAt + (triple ? 3 : 1);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                stack.push(new char[]{c, 0, 0});
                i++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                char open = c == ')' ? '(' : c == ']' ? '[' : '{';
                if (top == null || top[0] != open)
                    return "thừa dấu '" + c + "' ở dòng " + lineOf(code, i);
                stack.pop();
                i++;
                continue;
            }
            i++;
        }
        char[] pending = stack.peek();
        if (pending == null) return null;
        return (pending[0] == '\'' || pending[0] == '"')
                ? "còn chuỗi ký tự chưa đóng"
                : "thiếu dấu đóng cho '" + pending[0] + "'";
    }

    private int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index && i < code.length(); i++) if (code.charAt(i) == '\n') line++;
        return line;
    }

    private String normalizeNewlines(String value) {
        return value == null ? null : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Parse THẬT code tay bằng `dart format` trong ảnh nền trước khi Publish. Gộp mọi đoạn vào
     * một file để chỉ tốn một lần chạy Docker, rồi ánh xạ dòng lỗi ngược về đúng testcase.
     * Trả về cảnh báo (không chặn) khi máy chưa dùng được Docker; ném lỗi khi code sai cú pháp.
     */
    private String verifyCustomCodeBeforePublish(List<Map<String, Object>> items) {
        List<Map<String, Object>> customs = enabledCustomItems(items);
        if (customs.isEmpty()) return null;

        StringBuilder source = new StringBuilder();
        List<int[]> ranges = new ArrayList<>();   // [dòng đầu, dòng cuối, vị trí trong customs]
        int line = 1;
        for (int i = 0; i < customs.size(); i++) {
            source.append("void _custom").append(i).append("(dynamic tester) async {\n");
            line++;
            int first = line;
            for (String codeLine : normalizeNewlines(text(customs.get(i).get("custom_code"), "")).split("\n", -1)) {
                source.append(codeLine).append('\n');
                line++;
            }
            ranges.add(new int[]{first, line - 1, i});
            source.append("}\n");
            line++;
        }

        String problem;
        try {
            problem = examService.checkDartSyntax(source.toString());
        } catch (IllegalStateException e) {
            return "Đã Publish nhưng CHƯA kiểm tra được cú pháp code tay (" + e.getMessage()
                    + "). Hãy tải ZIP code và chạy thử trước khi chấm thật.";
        }
        if (problem == null) return null;
        throw new IllegalArgumentException("Code testcase tự viết sai cú pháp — "
                + describeSyntaxProblem(problem, ranges, customs));
    }

    private String describeSyntaxProblem(String problem, List<int[]> ranges,
                                         List<Map<String, Object>> customs) {
        Matcher matcher = Pattern.compile("line (\\d+), column (\\d+) of [^:]+:\\s*(.*)").matcher(problem);
        if (matcher.find()) {
            int reported = Integer.parseInt(matcher.group(1));
            for (int[] range : ranges) {
                if (reported < range[0] || reported > range[1]) continue;
                Map<String, Object> item = customs.get(range[2]);
                return "testcase \"" + text(item.get("name"), text(item.get("instance_id"))) + "\", dòng "
                        + (reported - range[0] + 1) + ": " + matcher.group(3).trim();
            }
        }
        return problem;
    }

    /**
     * Kiểm tra một đoạn code tay theo yêu cầu của giáo viên (nút "Kiểm tra cú pháp" trên UI).
     * Luôn chạy kiểm tra tĩnh; nếu có Docker thì parse thêm bằng Dart để bắt lỗi cú pháp thật.
     */
    public Map<String, Object> checkCustomCode(String rawCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        String code = normalizeNewlines(rawCode);
        try {
            validateCustomCode(code, "Đoạn code");
        } catch (IllegalArgumentException e) {
            out.put("ok", false);
            out.put("checked_by", "static");
            out.put("message", e.getMessage());
            return out;
        }
        try {
            String problem = examService.checkDartSyntax(
                    "void _customCheck(dynamic tester) async {\n" + code + "\n}\n");
            out.put("ok", problem == null);
            out.put("checked_by", "dart");
            out.put("message", problem == null
                    ? "Cú pháp Dart hợp lệ. Lỗi về tên biến/hàm chỉ lộ ra khi chấm thật."
                    : shiftReportedLine(problem));
        } catch (IllegalStateException e) {
            out.put("ok", true);
            out.put("checked_by", "static");
            out.put("message", "Ngoặc và chuỗi đã cân đối. Chưa parse được bằng Dart: " + e.getMessage());
        }
        return out;
    }

    /** Wrapper thêm đúng 1 dòng phía trên nên dòng Dart báo phải trừ 1 mới khớp editor. */
    private String shiftReportedLine(String problem) {
        Matcher matcher = Pattern.compile("line (\\d+), column (\\d+) of [^:]+:\\s*(.*)").matcher(problem);
        if (matcher.find()) {
            int line = Math.max(1, Integer.parseInt(matcher.group(1)) - 1);
            return "Dòng " + line + ", cột " + matcher.group(2) + ": " + matcher.group(3).trim();
        }
        return problem;
    }

    private List<Map<String, Object>> normalizeExistingItems(Object raw) {
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object value : list) if (value instanceof Map<?, ?>) out.add(castMap(value));
        return out;
    }

    private Map<String, Object> parameters(Map<String, Object> template, Object raw) {
        Map<String, Object> schema = map(template.get("parameters_schema"));
        Map<String, Object> supplied = raw instanceof Map<?, ?> ? castMap(raw) : Map.of();
        for (String key : supplied.keySet()) {
            if (!schema.containsKey(key)) throw new IllegalArgumentException("Tham số không tồn tại trong template: " + key);
        }
        Map<String, Object> merged = new LinkedHashMap<>(schema);
        merged.putAll(supplied);
        return merged;
    }

    private String engineType(List<Map<String, Object>> items) {
        // Toàn bộ template được cung cấp cho giao diện đều chạy trên cùng engine semantic.
        return COMMON_ENGINE;
    }

    private String profileId(String engineType) {
        return "COMMON_SEMANTIC_V1";
    }

    private void validateCommonParameters(String runner, Map<String, Object> params, String instanceId) {
        switch (runner) {
            case "WIDGET_VISIBLE" -> {
                requireParameter(params, "widgetKey", instanceId);
                validateOptionalTargetType(params, "targetType", instanceId);
            }
            case "FORM_REQUIRED_FIELDS" -> {
                requireParameter(params, "fieldKeys", instanceId);
                requireParameter(params, "submitKey", instanceId);
                requireParameter(params, "errorKeys", instanceId);
            }
            case "NAVIGATION" -> {
                requireParameter(params, "openKey", instanceId);
                requireParameter(params, "destinationKey", instanceId);
            }
            case "LIST_VISIBLE" -> {
                requireParameter(params, "listKey", instanceId);
                requireParameter(params, "itemKeys", instanceId);
            }
            case "BUTTON_ACTION" -> {
                requireParameter(params, "buttonKey", instanceId);
                requireParameter(params, "resultKey", instanceId);
            }
            case "RESPONSIVE_NO_OVERFLOW" -> {
                validateResponsiveSizes(params, instanceId);
            }
            case "RESPONSIVE_TARGET" -> {
                validateResponsiveSizes(params, instanceId);
                validateTarget(params, instanceId, Set.of("any", "form", "image", "text", "input",
                        "button", "dialog", "icon", "checkbox", "switch", "dropdown", "padding", "container"));
            }
            case "STATE_REACTIVE_FLOW" -> {
                requireParameter(params, "initialKey", instanceId);
                requireParameter(params, "actionKey", instanceId);
                requireParameter(params, "updatedKey", instanceId);
                requireParameter(params, "absentKey", instanceId);
            }
            case "WIDGET_TYPE_VISIBLE" -> validateWidgetTypeVisible(params, instanceId);
            case "WIDGET_TEXT_CONTENT" -> validateWidgetTextContent(params, instanceId);
            case "WIDGET_ENABLED" -> validateWidgetEnabled(params, instanceId);
            case "FORM_VALIDATE_FIELDS" -> validateFormFields(params, instanceId);
            case "FORM_PREFILL" -> validateFormPrefill(params, instanceId);
            case "FORM_SUBMIT" -> validateFormSubmit(params, instanceId);
            case "LIST_ITEM_COUNT" -> validateListItemCount(params, instanceId);
            case "DIALOG_FLOW" -> validateDialogFlow(params, instanceId);
            case "WIDGET_SEMANTICS_LABEL" -> validateWidgetSemanticsLabel(params, instanceId);
            case "WIDGET_DIMENSION" -> validateWidgetDimension(params, instanceId);
            case "WIDGET_PADDING" -> validateWidgetPadding(params, instanceId);
            case "WIDGET_TEXT_STYLE" -> validateWidgetTextStyle(params, instanceId);
            case "WIDGET_GAP" -> validateWidgetGap(params, instanceId);
            case "APP_BOOT" -> { /* rootKey có thể để trống nếu app không công bố root key. */ }
            default -> throw new IllegalArgumentException("Common runner không tồn tại: " + runner);
        }
    }

    private void validateResponsiveSizes(Map<String, Object> params, String instanceId) {
        if (number(params.get("portraitWidth"), 0) <= 0
                || number(params.get("portraitHeight"), 0) <= 0
                || number(params.get("landscapeWidth"), 0) <= 0
                || number(params.get("landscapeHeight"), 0) <= 0) {
            throw new IllegalArgumentException("Kích thước responsive không hợp lệ ở " + instanceId);
        }
    }

    private void validateWidgetTypeVisible(Map<String, Object> params, String instanceId) {
        validateTarget(params, instanceId, Set.of("any", "form", "image", "text", "input",
                "button", "dialog", "icon", "checkbox", "switch", "dropdown", "padding", "container"));
    }

    private void validateWidgetTextContent(Map<String, Object> params, String instanceId) {
        validateTarget(params, instanceId, Set.of("text"));
        requireParameter(params, "expectedText", instanceId);
        String matchMode = text(params.get("matchMode"), "equals").toLowerCase();
        if (!Set.of("equals", "contains").contains(matchMode))
            throw new IllegalArgumentException("matchMode không hợp lệ ở " + instanceId);
    }

    private void validateWidgetEnabled(Map<String, Object> params, String instanceId) {
        validateTarget(params, instanceId, Set.of("button", "input", "checkbox", "switch", "dropdown"));
        requireBoolean(params, "expectedEnabled", instanceId);
    }

    private void validateFormFields(Map<String, Object> params, String instanceId) {
        requireParameter(params, "fieldKeys", instanceId);
        requireParameter(params, "invalidValues", instanceId);
        requireParameter(params, "submitKey", instanceId);
        requireParameter(params, "errorKeys", instanceId);
        String fieldType = text(params.get("fieldType"), "input").toLowerCase();
        if (!Set.of("input", "text").contains(fieldType))
            throw new IllegalArgumentException("fieldType phải là input hoặc text ở " + instanceId);
        List<String> fields = csv(params.get("fieldKeys"));
        List<String> values = csv(params.get("invalidValues"));
        List<String> errors = csv(params.get("errorKeys"));
        if (fields.isEmpty() || fields.size() != values.size() || fields.size() != errors.size())
            throw new IllegalArgumentException("fieldKeys, invalidValues và errorKeys phải cùng số phần tử ở " + instanceId);
    }

    private void validateFormPrefill(Map<String, Object> params, String instanceId) {
        requireParameter(params, "editKey", instanceId);
        requireParameter(params, "fieldKeys", instanceId);
        requireParameter(params, "expectedValues", instanceId);
        validateInputFieldType(params, instanceId);
        List<String> fields = csv(params.get("fieldKeys"));
        List<String> values = csv(params.get("expectedValues"));
        if (fields.isEmpty() || fields.size() != values.size())
            throw new IllegalArgumentException("fieldKeys và expectedValues phải cùng số phần tử ở " + instanceId);
    }

    private void validateFormSubmit(Map<String, Object> params, String instanceId) {
        requireParameter(params, "fieldKeys", instanceId);
        requireParameter(params, "values", instanceId);
        requireParameter(params, "submitKey", instanceId);
        validateInputFieldType(params, instanceId);
        List<String> fields = csv(params.get("fieldKeys"));
        List<String> values = csv(params.get("values"));
        if (fields.isEmpty() || fields.size() != values.size())
            throw new IllegalArgumentException("fieldKeys và values phải cùng số phần tử ở " + instanceId);
    }

    private void validateInputFieldType(Map<String, Object> params, String instanceId) {
        String fieldType = text(params.get("fieldType"), "input").toLowerCase();
        if (!Set.of("input", "text").contains(fieldType))
            throw new IllegalArgumentException("fieldType phải là input hoặc text ở " + instanceId);
    }

    private void validateListItemCount(Map<String, Object> params, String instanceId) {
        requireParameter(params, "listKey", instanceId);
        requireParameter(params, "itemKeys", instanceId);
        if (csv(params.get("itemKeys")).isEmpty())
            throw new IllegalArgumentException("itemKeys không được rỗng ở " + instanceId);
        requireNumber(params, "expectedCount", instanceId, 0);
    }

    private void validateDialogFlow(Map<String, Object> params, String instanceId) {
        requireParameter(params, "actionKey", instanceId);
        requireParameter(params, "dialogKey", instanceId);
        requireParameter(params, "decisionKey", instanceId);
        validateTarget(paramsWithType(params, "dialog"), instanceId, Set.of("dialog"));
    }

    private void validateWidgetSemanticsLabel(Map<String, Object> params, String instanceId) {
        validateTarget(params, instanceId, Set.of("any", "form", "image", "text", "input", "button",
                "dialog", "icon", "checkbox", "switch", "dropdown", "padding", "container"));
        requireParameter(params, "expectedLabel", instanceId);
        String matchMode = text(params.get("matchMode"), "equals").toLowerCase();
        if (!Set.of("equals", "contains").contains(matchMode))
            throw new IllegalArgumentException("matchMode không hợp lệ ở " + instanceId);
    }

    private Map<String, Object> paramsWithType(Map<String, Object> params, String type) {
        Map<String, Object> copy = new LinkedHashMap<>(params);
        copy.put("targetKey", params.get("dialogKey"));
        copy.put("targetType", type);
        return copy;
    }

    /** Mọi testcase layout phải định danh target bằng key và khai loại widget mong đợi. */
    private void validateWidgetDimension(Map<String, Object> params, String instanceId) {
        validateTarget(params, instanceId, Set.of("any", "form", "image", "text", "input",
                "button", "padding", "container"));
        String dimension = text(params.get("dimension"), "").toLowerCase();
        if (!Set.of("width", "height").contains(dimension))
            throw new IllegalArgumentException("dimension phải là width hoặc height ở " + instanceId);
        validateComparison(params, instanceId);
        requireNumber(params, "expected", instanceId, 0);
        requireNumber(params, "tolerance", instanceId, 0);
    }

    private void validateWidgetPadding(Map<String, Object> params, String instanceId) {
        validateTarget(params, instanceId, Set.of("padding"));
        for (String side : List.of("left", "top", "right", "bottom", "tolerance"))
            requireNumber(params, side, instanceId, 0);
    }

    private void validateWidgetTextStyle(Map<String, Object> params, String instanceId) {
        validateTarget(params, instanceId, Set.of("text"));
        requireNumber(params, "fontSize", instanceId, 0.01);
        requireNumber(params, "tolerance", instanceId, 0);
        String weight = text(params.get("fontWeight"), "").toLowerCase();
        if (!Set.of("w100", "w200", "w300", "w400", "w500", "w600", "w700", "w800", "w900")
                .contains(weight)) {
            throw new IllegalArgumentException("fontWeight không hợp lệ ở " + instanceId);
        }
    }

    private void validateWidgetGap(Map<String, Object> params, String instanceId) {
        requireParameter(params, "fromKey", instanceId);
        requireParameter(params, "toKey", instanceId);
        String axis = text(params.get("axis"), "").toLowerCase();
        if (!Set.of("horizontal", "vertical").contains(axis))
            throw new IllegalArgumentException("axis phải là horizontal hoặc vertical ở " + instanceId);
        validateOptionalTargetType(params, "fromType", instanceId);
        validateOptionalTargetType(params, "toType", instanceId);
        requireNumber(params, "expectedGap", instanceId, 0);
        requireNumber(params, "tolerance", instanceId, 0);
    }

    private void validateTarget(Map<String, Object> params, String instanceId, Set<String> allowedTypes) {
        requireParameter(params, "targetKey", instanceId);
        requireParameter(params, "targetType", instanceId);
        String type = text(params.get("targetType"), "").toLowerCase();
        if (!allowedTypes.contains(type))
            throw new IllegalArgumentException("targetType không hợp lệ ở " + instanceId + ": " + type);
    }

    private void validateOptionalTargetType(Map<String, Object> params, String key, String instanceId) {
        String value = text(params.get(key), "").toLowerCase();
        if (!value.isBlank() && !Set.of("any", "form", "image", "text", "input", "button",
                "dialog", "icon", "checkbox", "switch", "dropdown", "padding", "container").contains(value)) {
            throw new IllegalArgumentException(key + " không hợp lệ ở " + instanceId + ": " + value);
        }
    }

    private void validateComparison(Map<String, Object> params, String instanceId) {
        String comparison = text(params.get("comparison"), "equals").toLowerCase();
        if (!Set.of("equals", "at_least", "at_most").contains(comparison))
            throw new IllegalArgumentException("comparison không hợp lệ ở " + instanceId);
    }

    private void requireNumber(Map<String, Object> params, String key, String instanceId, double min) {
        double value = number(params.get(key), Double.NaN);
        if (!Double.isFinite(value) || value < min)
            throw new IllegalArgumentException(key + " không hợp lệ ở " + instanceId);
    }

    private void requireBoolean(Map<String, Object> params, String key, String instanceId) {
        Object value = params.get(key);
        if (value instanceof Boolean) return;
        if (value != null && Set.of("true", "false").contains(String.valueOf(value).toLowerCase())) return;
        throw new IllegalArgumentException(key + " phải là boolean ở " + instanceId);
    }

    private List<String> csv(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        List<String> out = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.trim().isEmpty()) out.add(part.trim());
        }
        return out;
    }

    private void requireParameter(Map<String, Object> params, String key, String instanceId) {
        Object value = params.get(key);
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            throw new IllegalArgumentException("Thiếu semantic parameter " + key + " ở " + instanceId);
        }
    }

    private Map<String, Object> toSkillsMatrix(List<Map<String, Object>> items, String engineType) {
        Map<String, Object> matrix = new LinkedHashMap<>();
        Set<String> emittedGroups = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            // Disabled instance vẫn nằm trong config Draft để bật lại sau, nhưng không được
            // đưa vào matrix đang Publish vì grader cũ chưa hiểu cờ enabled.
            if (!bool(item.get("enabled"), true)) continue;
            String groupId = text(item.get("group_id"));
            if (COMMON_ENGINE.equals(engineType) && groupId != null && !groupId.isBlank()) {
                if (!emittedGroups.add(groupId)) continue;
                List<Map<String, Object>> children = items.stream()
                        .filter(child -> bool(child.get("enabled"), true)
                                && groupId.equals(text(child.get("group_id"))))
                        .toList();
                matrix.put(groupId, commonGroupRow(groupId, children));
                continue;
            }
            matrix.put(String.valueOf(item.get("instance_id")),
                    commonRubricRow(item));
        }
        return matrix;
    }

    /** Matrix của engine chung: runner đọc semantic key và parameters, không biết domain đề. */
    private Map<String, Object> commonRubricRow(Map<String, Object> item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("instance_id", item.get("instance_id"));
        row.put("runner", item.get("runner"));
        row.put("skill_code", item.get("skill_code"));
        row.put("testcase_group", item.get("testcase_group"));
        // group_id/group_name phải ĐI THEO vào matrix, không chỉ nằm trong config: khâu chấm chỉ
        // đọc matrix, nên thiếu chúng là mất nhãn hiển thị của nhóm chức năng.
        row.put("group_id", item.get("group_id"));
        row.put("group_name", item.get("group_name"));
        // Nhãn của result.json v2. Ghi thẳng vào matrix để khâu chấm chỉ việc đọc,
        // và để giáo viên thấy được testcase thuộc tầng/nhóm chức năng nào.
        row.put("rubric", TestCaseTaxonomy.rubricOf(item));
        row.put("rubric_label", TestCaseTaxonomy.rubricLabelOf(item));
        row.put("layer", TestCaseTaxonomy.layerOf(item, text(item.get("instance_id"))));
        row.put("name", item.get("name"));
        row.put("description", item.get("description"));
        row.put("expected", item.get("expected"));
        row.put("difficulty", item.get("difficulty"));
        row.put("weight", item.get("weight"));
        row.put("parameters", item.get("parameters"));
        return row;
    }

    /** Một testcase cha chỉ có một kết quả; mọi testcase con phải đạt thì nhóm mới đạt. */
    private Map<String, Object> commonGroupRow(String groupId, List<Map<String, Object>> children) {
        Map<String, Object> row = new LinkedHashMap<>();
        List<Map<String, Object>> childRows = new ArrayList<>();
        Set<String> skillCodes = new LinkedHashSet<>();
        double totalWeight = 0;
        String groupName = groupId;
        String difficulty = "basic";
        for (Map<String, Object> child : children) {
            childRows.add(commonRubricRow(child));
            String skillCode = text(child.get("skill_code"));
            if (skillCode != null && !skillCode.isBlank()) skillCodes.add(skillCode);
            totalWeight += number(child.get("weight"), 0);
            String childGroupName = text(child.get("group_name"));
            if (childGroupName != null && !childGroupName.isBlank()) groupName = childGroupName;
            difficulty = maxDifficulty(difficulty, text(child.get("difficulty"), "basic"));
        }
        row.put("instance_id", groupId);
        row.put("runner", "GROUP");
        row.put("group_id", groupId);
        row.put("group_name", groupName);
        row.put("name", groupName);
        row.put("expected", null);   // giữ chỗ; dựng ở cuối hàm vì cần children
        row.put("difficulty", difficulty);
        row.put("weight", totalWeight);
        row.put("skill_code", skillCodes.isEmpty() ? "UI_SCAFFOLD_APPBAR" : skillCodes.iterator().next());
        row.put("skill_codes", new ArrayList<>(skillCodes));
        row.put("children", childRows);
        // Ba field dưới đây đều DẪN XUẤT từ các testcase con nên phải dựng sau `children`:
        // expected là yêu cầu của các con ghép lại, layer là tầng CAO NHẤT trong các con.
        row.put("expected", TestCaseTaxonomy.groupExpected(row));
        row.put("rubric", TestCaseTaxonomy.rubricOf(row));
        row.put("rubric_label", TestCaseTaxonomy.rubricLabelOf(row));
        row.put("layer", TestCaseTaxonomy.layerOf(row, groupId));
        return row;
    }

    private String maxDifficulty(String first, String second) {
        int left = difficultyRank(first);
        int right = difficultyRank(second);
        return right > left ? second : first;
    }

    private int difficultyRank(String difficulty) {
        return switch (difficulty == null ? "" : difficulty.toLowerCase()) {
            case "advanced" -> 3;
            case "intermediate" -> 2;
            default -> 1;
        };
    }

    /** Recheck skill_code sau khi dựng matrix, tránh template lệch taxonomy hiện tại. */
    private void validateGeneratedMatrix(String json) {
        List<Map<String, Object>> problems = syllabusService.validateSkillsMatrix(json);
        List<Map<String, Object>> errors = problems.stream()
                .filter(p -> !"warning".equals(p.get("severity"))).toList();
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(p -> p.get("testId") + " → " + p.get("issue"))
                    .reduce((a, b) -> a + "; " + b).orElse("skill_code không hợp lệ");
            throw new IllegalArgumentException("Cấu hình testcase không hợp lệ: " + detail);
        }
    }

    private Map<String, Object> enrichTemplate(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>(source);
        row.putIfAbsent("created_by", TEMPLATE_CREATED_BY);
        row.putIfAbsent("created_at", TEMPLATE_CREATED_AT);
        Skill skill = findSkill(text(source.get("skill_code")));
        if (skill != null) {
            row.put("skill_name", skill.getName());
            row.put("category", skill.getCategoryCode());
            SkillCategory category = syllabusService.categories().stream()
                    .filter(c -> c.getCode().equals(skill.getCategoryCode())).findFirst().orElse(null);
            if (category != null) row.put("category_label", category.getCompetencyLabel() != null
                    && !category.getCompetencyLabel().isBlank() ? category.getCompetencyLabel() : category.getName());
        }
        String group = text(source.get("testcase_group"),
                testcaseGroup(source.get("runner"), source.get("layer")));
        row.put("testcase_group", group);
        row.put("testcase_group_label", TESTCASE_GROUP_LABELS.getOrDefault(group, "Testcase Logic"));
        String templateId = text(source.get("template_id"), "");
        row.put("origin", text(source.get("origin"),
                builtinTemplates.containsKey(templateId) ? "BUILTIN" : "CUSTOM"));
        row.put("hidden", hiddenTemplateIds.contains(templateId));
        // Template gốc luôn khôi phục được về bản trong classpath; template tự thêm thì không.
        row.put("restorable", builtinTemplates.containsKey(templateId)
                && !"BUILTIN".equals(row.get("origin")));
        return row;
    }

    /** Phân nhóm theo bản chất kiểm tra, độc lập với category năng lực của syllabus. */
    private String testcaseGroup(Object rawRunner, Object rawLayer) {
        String runner = text(rawRunner, "").toUpperCase();
        String layer = text(rawLayer, "").toUpperCase();
        if (BEHAVIOR_RUNNERS.contains(runner)) return "BEHAVIOR";
        if (LOGIC_RUNNERS.contains(runner)) return "LOGIC";
        if (layer.equals("RESPONSIVE") || runner.startsWith("WIDGET_") || runner.equals("LIST_VISIBLE")) {
            return "WIDGET";
        }
        return "LOGIC";
    }

    private Skill findSkill(String code) {
        if (code == null) return null;
        return syllabusService.skills().stream().filter(s -> code.equals(s.getCode())).findFirst().orElse(null);
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try { return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("Cấu hình testcase cũ không đọc được: " + e.getMessage()); }
    }

    private Map<String, Map<String, Object>> indexItems(Object raw) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> item : normalizeExistingItems(raw)) {
            String id = text(item.get("instance_id"));
            if (id != null) out.put(id, item);
        }
        return out;
    }

    private double totalWeight(List<Map<String, Object>> items) {
        double total = 0;
        for (Map<String, Object> item : items) {
            if (bool(item.get("enabled"), true)) total += number(item.get("weight"), 0);
        }
        return Math.round(total * 100.0) / 100.0;
    }

    private String renderExpected(String template, Map<String, Object> params) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, Object> e : params.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? castMap(value) : new LinkedHashMap<>();
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static String firstText(Object first, Object second) {
        String value = text(first);
        return value == null || value.isBlank() ? text(second) : value;
    }
    private static String text(Object value, String fallback) {
        String s = text(value);
        return s == null || s.isBlank() ? fallback : s;
    }
    private static boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }
    private static double number(Object value, double fallback) {
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }
}
