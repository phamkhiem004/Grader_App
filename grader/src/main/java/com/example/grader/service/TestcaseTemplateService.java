package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import com.example.grader.entity.Skill;
import com.example.grader.entity.SkillCategory;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final String TEMPLATE_VERSION = "2026.1";
    private static final String TEMPLATE_CREATED_BY = "system";
    private static final String TEMPLATE_CREATED_AT = "2026-08-02T00:00:00Z";
    private static final String COMMON_ENGINE = "COMMON_V1";
    private static final String CUSTOM_TEMPLATE_VERSION = "teacher-v1";
    private static final String CUSTOM_TEMPLATE_FILE = "custom-testcase-templates.json";
    private static final Pattern DART_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> SUITE_PROFILES = Set.of(
            "COMMON_UI", "FLUTTER_LAYERED", "PERSISTENCE", "REPOSITORY_SQLITE", "GOLDEN_RESPONSIVE");
    private static final Set<String> RESET_STRATEGIES = Set.of(
            "APP_RESTART", "FIXTURE_STEPS", "CLEAR_STORAGE", "PERSISTENCE_PHASE");
    private static final Set<String> SOURCE_CONTRACT_TYPES = Set.of(
            "model", "repository", "provider", "screen", "helper", "service");
    private static final Pattern SAFE_INSTANCE_ID = Pattern.compile("[A-Za-z0-9_-]{1,60}");
    private static final Pattern SEMANTIC_KEY = Pattern.compile("[a-z][a-z0-9_-]*(?:\\.[a-z0-9_-]+)+");
    private static final Set<String> DIFFICULTIES = Set.of("basic", "intermediate", "advanced");
    private static final Set<String> LAYERS = Set.of(
            "CONTRACT", "MODEL", "REPOSITORY", "VIEWMODEL", "SCREEN", "BLACKBOX", "RESPONSIVE");
    private static final Set<String> SUPPORTED_COMMON_RUNNERS = Set.of(
            "APP_BOOT", "WIDGET_VISIBLE", "FORM_REQUIRED_FIELDS", "RESPONSIVE_NO_OVERFLOW",
            "RESPONSIVE_TARGET", "NAVIGATION", "LIST_VISIBLE", "BUTTON_ACTION", "WIDGET_DIMENSION",
            "WIDGET_PADDING", "WIDGET_TEXT_STYLE", "WIDGET_GAP", "WIDGET_TYPE_VISIBLE",
            "WIDGET_TEXT_CONTENT", "WIDGET_ENABLED", "FORM_VALIDATE_FIELDS", "LIST_ITEM_COUNT",
            "DIALOG_FLOW", "FORM_PREFILL", "FORM_SUBMIT", "WIDGET_SEMANTICS_LABEL",
            "STATE_REACTIVE_FLOW", "DIRECT_FUNCTION");
    private static final Set<String> SUITE_STEP_TYPES = Set.of(
            "tap", "enter_text", "expect_visible", "expect_absent", "wait_for_visible");
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
    /** Tên thân thiện hiển thị cho giáo viên; template_id vẫn giữ nguyên để grader nhận diện. */
    private static final Map<String, String> FRIENDLY_TEMPLATE_NAMES = Map.ofEntries(
            Map.entry("COMMON_APP_BOOT", "Mở ứng dụng không bị lỗi"),
            Map.entry("COMMON_WIDGET_VISIBLE", "Hiển thị đúng thành phần trên màn hình"),
            Map.entry("COMMON_FORM_REQUIRED_FIELDS", "Kiểm tra các ô bắt buộc trong biểu mẫu"),
            Map.entry("COMMON_RESPONSIVE_NO_OVERFLOW", "Giao diện không bị tràn ở mọi kích thước"),
            Map.entry("COMMON_RESPONSIVE_TARGET", "Thành phần giao diện xuất hiện ở cả dọc và ngang"),
            Map.entry("COMMON_NAVIGATION", "Mở đúng màn hình và quay lại được"),
            Map.entry("COMMON_LIST_VISIBLE", "Hiển thị danh sách và các mục bên trong"),
            Map.entry("COMMON_BUTTON_ACTION", "Nút bấm thực hiện đúng thao tác"),
            Map.entry("COMMON_WIDGET_DIMENSION", "Kích thước thành phần giao diện đúng yêu cầu"),
            Map.entry("COMMON_WIDGET_PADDING", "Khoảng cách bên trong thành phần đúng yêu cầu"),
            Map.entry("COMMON_WIDGET_TEXT_STYLE", "Cỡ chữ và kiểu chữ đúng yêu cầu"),
            Map.entry("COMMON_WIDGET_GAP", "Khoảng cách giữa các thành phần đúng yêu cầu"),
            Map.entry("COMMON_WIDGET_TYPE_VISIBLE", "Thành phần giao diện đúng loại"),
            Map.entry("COMMON_WIDGET_TEXT_CONTENT", "Nội dung chữ hiển thị đúng"),
            Map.entry("COMMON_WIDGET_ENABLED", "Nút hoặc ô nhập có đúng trạng thái bật/tắt"),
            Map.entry("COMMON_FORM_VALIDATE_FIELDS", "Biểu mẫu hiển thị lỗi khi nhập dữ liệu sai"),
            Map.entry("COMMON_LIST_ITEM_COUNT", "Danh sách hiển thị đúng số lượng mục"),
            Map.entry("COMMON_DIALOG_FLOW", "Hộp thoại xác nhận hoạt động đúng"),
            Map.entry("COMMON_FORM_PREFILL", "Biểu mẫu tự điền đúng dữ liệu khi chỉnh sửa"),
            Map.entry("COMMON_FORM_SUBMIT", "Gửi biểu mẫu hợp lệ thành công"),
            Map.entry("COMMON_WIDGET_SEMANTICS_LABEL", "Thành phần có nhãn hỗ trợ người dùng trợ năng"),
            Map.entry("COMMON_STATE_REACTIVE", "Giao diện cập nhật đúng sau thao tác"),
            Map.entry("CONTRACT_VALIDATE_INPUT", "Hàm kiểm tra dữ liệu đầu vào hoạt động đúng"),
            Map.entry("MODEL_CONSTRUCTOR_DEFAULTS", "Đối tượng dữ liệu có giá trị mặc định đúng"),
            Map.entry("MODEL_JSON_MAPPING", "Đọc và ghi dữ liệu JSON đúng cách"),
            Map.entry("REPOSITORY_CRUD_CONTRACT", "Kho dữ liệu thực hiện đủ thêm, xem, sửa, xóa"),
            Map.entry("REPOSITORY_ISOLATED_STATE", "Mỗi kho dữ liệu giữ dữ liệu riêng biệt"),
            Map.entry("VIEWMODEL_LOADING_ERROR", "Màn hình hiển thị đúng trạng thái tải và lỗi"),
            Map.entry("VIEWMODEL_ASYNC_RETRY", "Tác vụ tự thử lại khi gặp lỗi"),
            Map.entry("SCREEN_SCAFFOLD_APPBAR", "Màn hình có thanh tiêu đề và nội dung chính"),
            Map.entry("SCREEN_FORM_VALIDATION", "Biểu mẫu kiểm tra dữ liệu nhập"),
            Map.entry("SCREEN_ACCESSIBLE_ACTIONS", "Thao tác trên màn hình có phản hồi đúng"),
            Map.entry("UI_RESPONSIVE_PORTRAIT", "Giao diện dọc hiển thị một cột"),
            Map.entry("UI_RESPONSIVE_LANDSCAPE", "Giao diện ngang hiển thị nhiều cột"),
            Map.entry("BLACKBOX_EMPTY_STATE", "Danh sách rỗng hiển thị thông báo phù hợp"),
            Map.entry("BLACKBOX_NAVIGATION_FLOW", "Mở màn hình khác và quay lại đúng trạng thái"),
            Map.entry("CONTRACT_NULL_SAFETY", "Dữ liệu rỗng được xử lý an toàn"),
            Map.entry("ASYNC_STREAM_STATE", "Luồng dữ liệu cập nhật đúng trạng thái"),
            Map.entry("CONTRACT_MODEL_SYMBOLS", "Bắt buộc có các Model người dùng"),
            Map.entry("CONTRACT_REPOSITORY_SYMBOLS", "Bắt buộc có các kho dữ liệu người dùng"),
            Map.entry("CONTRACT_VIEWMODEL_PROVIDER_SYMBOLS", "Bắt buộc có ViewModel và Provider"),
            Map.entry("CONTRACT_SCREEN_SYMBOLS", "Bắt buộc có màn hình quản lý người dùng"),
            Map.entry("MODEL_GRANULAR_FIELDS", "Model có đủ trường dữ liệu"),
            Map.entry("MODEL_GRANULAR_COPYWITH", "Model tạo bản sao bằng copyWith đúng"),
            Map.entry("MODEL_GRANULAR_MAPPING", "Model chuyển đổi đúng với SQLite"),
            Map.entry("REPOSITORY_GRANULAR_ADD_AUTO_ID", "Thêm dữ liệu và tự tăng mã ID"),
            Map.entry("REPOSITORY_GRANULAR_MAPPING", "Kho dữ liệu chuyển đổi dữ liệu đúng"),
            Map.entry("REPOSITORY_GRANULAR_DUPLICATE_ROWS", "Kho dữ liệu giữ được các bản ghi trùng"),
            Map.entry("REPOSITORY_GRANULAR_UPDATE", "Cập nhật đúng bản ghi theo ID"),
            Map.entry("REPOSITORY_GRANULAR_DELETE", "Xóa đúng bản ghi theo ID"),
            Map.entry("SQLITE_REPOSITORY_TEMP_DATABASE_CRUD", "Kho dữ liệu thực hiện CRUD với SQLite"),
            Map.entry("VIEWMODEL_GRANULAR_LOAD_STATE", "Tải dữ liệu và cập nhật trạng thái"),
            Map.entry("VIEWMODEL_GRANULAR_ADD_AUTO_ID", "Thêm dữ liệu và cập nhật danh sách"),
            Map.entry("VIEWMODEL_GRANULAR_UPDATE_STATE", "Sửa dữ liệu và cập nhật trạng thái"),
            Map.entry("VIEWMODEL_GRANULAR_DELETE_STATE", "Xóa dữ liệu và cập nhật trạng thái"),
            Map.entry("ARCH_MVVM", "Kết nối đúng kiến trúc MVVM"),
            Map.entry("ARCH_SQLITE", "Kết nối SQLite và lưu trữ dữ liệu đúng"),
            Map.entry("ARCH_RIVERPOD_GENERATOR", "Cấu hình Riverpod và mã tự sinh đúng"),
            Map.entry("SCREEN_VALIDATE_EACH_FIELD", "Biểu mẫu kiểm tra lỗi từng ô nhập"),
            Map.entry("SCREEN_FORM_CONTROLS", "Biểu mẫu có đủ ô nhập và nút thao tác"),
            Map.entry("SCREEN_GRANULAR_LIST_SINGLE_USER", "Danh sách hiển thị một người dùng"),
            Map.entry("SCREEN_GRANULAR_LIST_MULTIPLE_USERS", "Danh sách hiển thị nhiều người dùng"),
            Map.entry("SCREEN_GRANULAR_ADD_REPOSITORY", "Nút thêm gọi đúng kho dữ liệu"),
            Map.entry("SCREEN_GRANULAR_ADD_LIST_STATE", "Thêm dữ liệu và cập nhật danh sách"),
            Map.entry("SCREEN_GRANULAR_UPDATE_LOAD", "Màn hình sửa nạp đúng dữ liệu"),
            Map.entry("SCREEN_GRANULAR_UPDATE_REPOSITORY", "Nút sửa gọi đúng kho dữ liệu"),
            Map.entry("SCREEN_GRANULAR_DELETE_DIALOG", "Nút xóa mở hộp thoại xác nhận"),
            Map.entry("SCREEN_GRANULAR_DELETE_REPOSITORY", "Nút xóa gọi đúng kho dữ liệu"),
            Map.entry("SCREEN_GRANULAR_DETAIL_DATA", "Màn hình chi tiết hiển thị đúng dữ liệu"),
            Map.entry("SCREEN_GRANULAR_DETAIL_BACK", "Từ chi tiết quay lại đúng màn hình"),
            Map.entry("UI_BOOT", "Mở ứng dụng không bị lỗi"),
            Map.entry("UI_CREATE_VALID", "Thêm người dùng hợp lệ"),
            Map.entry("UI_EDIT_LOAD", "Mở form sửa và nạp đúng dữ liệu"),
            Map.entry("UI_EDIT_USER", "Sửa thông tin người dùng"),
            Map.entry("UI_DELETE_DIALOG", "Mở hộp thoại xác nhận xóa"),
            Map.entry("UI_DELETE_CONFIRM", "Xác nhận xóa người dùng"),
            Map.entry("UI_DETAIL_OPEN", "Mở màn hình chi tiết"),
            Map.entry("UI_DETAIL_BACK", "Quay lại từ màn hình chi tiết"),
            Map.entry("PERSIST_ADD_RELOAD", "Dữ liệu thêm mới vẫn còn sau khi tải lại"),
            Map.entry("PERSIST_EDIT_RELOAD", "Dữ liệu đã sửa vẫn còn sau khi tải lại"),
            Map.entry("PERSIST_DELETE_RELOAD", "Dữ liệu đã xóa không xuất hiện sau khi tải lại"),
            Map.entry("UI_LAYOUT_OVERFLOW", "Giao diện không bị tràn màn hình"),
            Map.entry("VISUAL_GOLDEN_PORTRAIT", "Giao diện dọc khớp mẫu chuẩn"),
            Map.entry("VISUAL_GOLDEN_LANDSCAPE", "Giao diện ngang khớp mẫu chuẩn")
    );
    /** Mô tả bổ sung cho rubric layered cũ vốn chỉ có name, không có description. */
    private static final Map<String, String> FRIENDLY_TEMPLATE_DESCRIPTIONS = Map.ofEntries(
            Map.entry("CONTRACT_MODEL_SYMBOLS", "Xác nhận bài làm có các Model bắt buộc để quản lý dữ liệu người dùng."),
            Map.entry("CONTRACT_REPOSITORY_SYMBOLS", "Xác nhận bài làm có đủ các kho dữ liệu bắt buộc cho các thao tác chính."),
            Map.entry("CONTRACT_VIEWMODEL_PROVIDER_SYMBOLS", "Xác nhận bài làm có ViewModel và Provider để quản lý trạng thái ứng dụng."),
            Map.entry("CONTRACT_SCREEN_SYMBOLS", "Xác nhận bài làm có màn hình chính để hiển thị và thao tác với dữ liệu người dùng."),
            Map.entry("MODEL_GRANULAR_FIELDS", "Kiểm tra Model có đủ các trường dữ liệu và kiểu dữ liệu cần thiết."),
            Map.entry("MODEL_GRANULAR_COPYWITH", "Kiểm tra copyWith tạo bản sao mà không làm thay đổi đối tượng ban đầu."),
            Map.entry("MODEL_GRANULAR_MAPPING", "Kiểm tra Model chuyển đổi đúng giữa đối tượng Dart và dữ liệu SQLite."),
            Map.entry("REPOSITORY_GRANULAR_ADD_AUTO_ID", "Kiểm tra thêm dữ liệu mới và tự cấp mã ID không bị trùng."),
            Map.entry("REPOSITORY_GRANULAR_MAPPING", "Kiểm tra kho dữ liệu chuyển đổi đúng dữ liệu khi đọc và ghi."),
            Map.entry("REPOSITORY_GRANULAR_DUPLICATE_ROWS", "Kiểm tra kho dữ liệu không tự loại bỏ các bản ghi có nội dung trùng nhau."),
            Map.entry("REPOSITORY_GRANULAR_UPDATE", "Kiểm tra chỉ đúng bản ghi có ID được yêu cầu bị cập nhật."),
            Map.entry("REPOSITORY_GRANULAR_DELETE", "Kiểm tra chỉ đúng bản ghi có ID được yêu cầu bị xóa."),
            Map.entry("SQLITE_REPOSITORY_TEMP_DATABASE_CRUD", "Kiểm tra đầy đủ thêm, xem, sửa, xóa trên cơ sở dữ liệu SQLite tạm."),
            Map.entry("VIEWMODEL_GRANULAR_LOAD_STATE", "Kiểm tra trạng thái tải dữ liệu, thành công và lỗi được cập nhật đúng."),
            Map.entry("VIEWMODEL_GRANULAR_ADD_AUTO_ID", "Kiểm tra thêm người dùng và cập nhật danh sách trên giao diện."),
            Map.entry("VIEWMODEL_GRANULAR_UPDATE_STATE", "Kiểm tra sửa người dùng và cập nhật đúng trạng thái giao diện."),
            Map.entry("VIEWMODEL_GRANULAR_DELETE_STATE", "Kiểm tra xóa người dùng và cập nhật đúng trạng thái giao diện."),
            Map.entry("ARCH_MVVM", "Kiểm tra các thành phần được kết nối đúng theo kiến trúc MVVM."),
            Map.entry("ARCH_SQLITE", "Kiểm tra ứng dụng kết nối SQLite và lưu trữ dữ liệu đúng cách."),
            Map.entry("ARCH_RIVERPOD_GENERATOR", "Kiểm tra cấu hình Riverpod, ProviderScope và mã tự sinh hoạt động đúng."),
            Map.entry("SCREEN_VALIDATE_EACH_FIELD", "Kiểm tra từng ô nhập hiển thị đúng lỗi khi dữ liệu không hợp lệ."),
            Map.entry("SCREEN_FORM_CONTROLS", "Kiểm tra biểu mẫu có đủ ô nhập, nút thêm, nút sửa và nút xóa."),
            Map.entry("SCREEN_GRANULAR_LIST_SINGLE_USER", "Kiểm tra danh sách hiển thị đúng thông tin khi chỉ có một người dùng."),
            Map.entry("SCREEN_GRANULAR_LIST_MULTIPLE_USERS", "Kiểm tra danh sách hiển thị đúng nhiều người dùng và không bị lặp sai."),
            Map.entry("SCREEN_GRANULAR_ADD_REPOSITORY", "Kiểm tra nút thêm gọi đúng kho dữ liệu và xử lý kết quả trả về."),
            Map.entry("SCREEN_GRANULAR_ADD_LIST_STATE", "Kiểm tra sau khi thêm, danh sách trên màn hình được cập nhật ngay."),
            Map.entry("SCREEN_GRANULAR_UPDATE_LOAD", "Kiểm tra mở form sửa và nạp đúng dữ liệu của người dùng được chọn."),
            Map.entry("SCREEN_GRANULAR_UPDATE_REPOSITORY", "Kiểm tra nút sửa gọi đúng kho dữ liệu với đúng ID người dùng."),
            Map.entry("SCREEN_GRANULAR_DELETE_DIALOG", "Kiểm tra nút xóa hiển thị hộp thoại xác nhận trước khi xóa."),
            Map.entry("SCREEN_GRANULAR_DELETE_REPOSITORY", "Kiểm tra sau khi xác nhận, nút xóa gọi đúng kho dữ liệu."),
            Map.entry("SCREEN_GRANULAR_DETAIL_DATA", "Kiểm tra màn hình chi tiết hiển thị đúng thông tin người dùng."),
            Map.entry("SCREEN_GRANULAR_DETAIL_BACK", "Kiểm tra nút quay lại đưa người dùng về đúng màn hình danh sách."),
            Map.entry("UI_BOOT", "Mở ứng dụng và kiểm tra ứng dụng không phát sinh lỗi ngay từ đầu."),
            Map.entry("UI_CREATE_VALID", "Nhập dữ liệu hợp lệ, thêm người dùng và kiểm tra người dùng xuất hiện trong danh sách."),
            Map.entry("UI_EDIT_LOAD", "Chọn sửa và kiểm tra biểu mẫu được điền sẵn đúng dữ liệu cũ."),
            Map.entry("UI_EDIT_USER", "Sửa thông tin người dùng và kiểm tra dữ liệu mới được hiển thị đúng."),
            Map.entry("UI_DELETE_DIALOG", "Chọn xóa và kiểm tra hộp thoại xác nhận xuất hiện."),
            Map.entry("UI_DELETE_CONFIRM", "Xác nhận xóa và kiểm tra người dùng không còn trong danh sách."),
            Map.entry("UI_DETAIL_OPEN", "Mở màn hình chi tiết và kiểm tra thông tin người dùng được hiển thị."),
            Map.entry("UI_DETAIL_BACK", "Từ màn hình chi tiết quay lại và kiểm tra danh sách ban đầu vẫn đúng."),
            Map.entry("PERSIST_ADD_RELOAD", "Thêm người dùng, tải lại ứng dụng và kiểm tra dữ liệu vẫn còn."),
            Map.entry("PERSIST_EDIT_RELOAD", "Sửa người dùng, tải lại ứng dụng và kiểm tra dữ liệu mới vẫn còn."),
            Map.entry("PERSIST_DELETE_RELOAD", "Xóa người dùng, tải lại ứng dụng và kiểm tra dữ liệu đã xóa không quay lại."),
            Map.entry("UI_RESPONSIVE_PORTRAIT", "Kiểm tra giao diện dọc hiển thị đúng bố cục và không bị tràn."),
            Map.entry("UI_RESPONSIVE_LANDSCAPE", "Kiểm tra giao diện ngang hoặc máy tính bảng hiển thị đúng bố cục."),
            Map.entry("UI_LAYOUT_OVERFLOW", "Kiểm tra giao diện không bị tràn nội dung ở các kích thước được yêu cầu."),
            Map.entry("VISUAL_GOLDEN_PORTRAIT", "So sánh giao diện dọc thực tế với hình ảnh mẫu chuẩn."),
            Map.entry("VISUAL_GOLDEN_LANDSCAPE", "So sánh giao diện ngang thực tế với hình ảnh mẫu chuẩn.")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> templates = new LinkedHashMap<>();

    @Value("${grader.exams-dir:exams}")
    private String examsDirectory;

    @Autowired private ExamRepository examRepository;
    @Autowired private SyllabusService syllabusService;
    @Autowired private ExamService examService;

    @PostConstruct
    public void loadTemplates() {
        templates.clear();
        if (loadClasspathTemplates("common-testcase-templates.json", COMMON_ENGINE))
            log.info("✅ Nạp {} testcase dùng chung từ common-testcase-templates.json", templates.size());
        else log.error("Không nạp được thư viện testcase dùng chung.");
        loadCustomTemplates();
    }

    private void loadCustomTemplates() {
        Path file = customTemplateFile();
        if (!Files.exists(file)) return;
        try {
            List<Map<String, Object>> rows = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> source : rows) {
                String id = text(source.get("template_id"));
                if (id == null || id.isBlank() || templates.containsKey(id)) continue;
                Map<String, Object> row = new LinkedHashMap<>(source);
                if (!COMMON_ENGINE.equals(text(row.get("engine_type"), COMMON_ENGINE))) continue;
                String runner = text(row.get("runner"), "").toUpperCase();
                if (!SUPPORTED_COMMON_RUNNERS.contains(runner)) continue;
                if (!(row.get("parameters_schema") instanceof Map<?, ?>)) continue;
                row.put("custom", true);
                row.put("engine_type", COMMON_ENGINE);
                templates.put(id, row);
            }
            log.info("Loaded {} custom testcase templates from {}", rows.size(), file);
        } catch (Exception e) {
            log.warn("Cannot read custom testcase template library {}: {}", file, e.getMessage());
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
                row.put("name", friendlyTemplateName(id, text(source.get("name"), id)));
                templates.put(id, row);
            }
            return !rows.isEmpty();
        } catch (Exception e) {
            log.warn("Không nạp được {}: {}", resourceName, e.getMessage());
            return false;
        }
    }

    /** Tạo template dùng lại từ runner đã được common engine hỗ trợ. */
    public synchronized Map<String, Object> createTemplate(Map<String, Object> body, String teacherEmail) {
        ensureReferenceTemplatesLoaded();
        if (body == null) throw new IllegalArgumentException("Thiếu dữ liệu template testcase");
        String id = text(body.get("template_id"));
        if (id == null || id.isBlank()) id = "CUSTOM_" + slug(text(body.get("name"), "CUSTOM_TESTCASE"));
        id = id.trim().toUpperCase();
        if (!Pattern.compile("[A-Z][A-Z0-9_-]{2,79}").matcher(id).matches())
            throw new IllegalArgumentException("template_id phải gồm chữ in hoa, số, _ hoặc - (3-80 ký tự)");
        if (templates.containsKey(id)) throw new IllegalArgumentException("template_id đã tồn tại: " + id);

        String runner = text(body.get("runner"), "").trim().toUpperCase();
        if (!SUPPORTED_COMMON_RUNNERS.contains(runner))
            throw new IllegalArgumentException("Runner chưa được engine hỗ trợ: " + runner);
        String skillCode = text(body.get("skill_code"), "").trim();
        Skill skill = findSkill(skillCode);
        if (skill == null || Boolean.TRUE.equals(skill.getDeprecated()))
            throw new IllegalArgumentException("skill_code không tồn tại hoặc đã ngừng dùng: " + skillCode);
        String layer = text(body.get("layer"), "").trim().toUpperCase();
        if (!LAYERS.contains(layer)) throw new IllegalArgumentException("layer không hợp lệ: " + layer);
        String group = text(body.get("testcase_group"), testcaseGroup(runner, layer)).trim().toUpperCase();
        if (!TESTCASE_GROUP_LABELS.containsKey(group)) throw new IllegalArgumentException("testcase_group không hợp lệ: " + group);

        String name = requiredLimitedText(body.get("name"), 160, "Tên template");
        String description = requiredLimitedText(body.get("description"), 1000, "Mô tả template");
        String expected = requiredLimitedText(body.get("expected_template"), 1000, "Expected template");
        String difficulty = text(body.get("difficulty"), "basic").trim().toLowerCase();
        if (!DIFFICULTIES.contains(difficulty)) throw new IllegalArgumentException("difficulty không hợp lệ: " + difficulty);
        double weight = number(body.get("weight_default"), 1);
        if (!Double.isFinite(weight) || weight < 0 || weight > 100)
            throw new IllegalArgumentException("weight_default phải nằm trong khoảng 0-100");
        if (!(body.get("parameters_schema") instanceof Map<?, ?>))
            throw new IllegalArgumentException("parameters_schema phải là một JSON object");
        Map<String, Object> schema = normalizeTemplateParameters(castMap(body.get("parameters_schema")), id);
        validateCommonParameters(runner, schema, id);

        Instant now = Instant.now();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("template_id", id); row.put("template_version", CUSTOM_TEMPLATE_VERSION);
        row.put("engine_type", COMMON_ENGINE); row.put("runner", runner);
        row.put("skill_code", skillCode); row.put("layer", layer); row.put("testcase_group", group);
        row.put("name", name); row.put("description", description); row.put("difficulty", difficulty);
        row.put("weight_default", weight); row.put("parameters_schema", schema);
        row.put("expected_template", expected); row.put("execution_key", id);
        row.put("custom", true); row.put("created_by", text(teacherEmail, "unknown"));
        row.put("created_at", now.toString());
        templates.put(id, row);
        try { persistCustomTemplates(); } catch (Exception e) {
            templates.remove(id);
            throw new IllegalStateException("Không lưu được template custom: " + e.getMessage(), e);
        }
        return enrichTemplate(row);
    }

    private Map<String, Object> normalizeTemplateParameters(Map<String, Object> input, String templateId) {
        if (input.size() > 50) throw new IllegalArgumentException("parameters_schema không được quá 50 tham số");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (!DART_IDENTIFIER.matcher(key).matches()) throw new IllegalArgumentException("Tên tham số không hợp lệ ở " + templateId + ": " + key);
            if (value instanceof Map<?, ?> || value instanceof List<?>) throw new IllegalArgumentException("Tham số " + key + " phải là giá trị đơn giản");
            if (value instanceof String string && string.length() > 2000) throw new IllegalArgumentException("Tham số " + key + " quá dài");
            out.put(key, value);
        }
        return out;
    }

    private void persistCustomTemplates() throws Exception {
        Path file = customTemplateFile(); Files.createDirectories(file.getParent());
        List<Map<String, Object>> custom = templates.values().stream().filter(row -> bool(row.get("custom"), false)).toList();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(custom), StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    private Path customTemplateFile() {
        return Path.of(examsDirectory == null || examsDirectory.isBlank() ? "exams" : examsDirectory, "_template-library", CUSTOM_TEMPLATE_FILE).toAbsolutePath().normalize();
    }

    private String requiredLimitedText(Object raw, int maxLength, String field) {
        String value = text(raw);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " không được để trống");
        return limitedText(value, "", maxLength, field);
    }

    private String slug(String value) {
        String normalized = value == null ? "CUSTOM_TESTCASE" : value.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (normalized.length() < 3) normalized = "TESTCASE";
        return normalized.substring(0, Math.min(normalized.length(), 70));
    }

    /** Danh sách template kèm skill/category để frontend dựng 3 khu vực kéo-thả. */
    public List<Map<String, Object>> listTemplates(String category, String skillCode, String layer) {
        ensureReferenceTemplatesLoaded();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> source : templates.values()) {
            Map<String, Object> row = enrichTemplate(source);
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
            empty.put("suite", defaultSuite());
            empty.put("items", List.of());
            empty.put("total_weight", 0);
            return empty;
        }
        try {
            Map<String, Object> config = mapper.readValue(exam.getTestcaseConfigJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            List<Map<String, Object>> items = normalizeExistingItems(config.get("items"));
            config.put("suite", normalizeSuite(config.get("suite"), null));
            config.put("items", items);
            config.put("total_weight", totalWeight(items));
            return config;
        } catch (Exception e) {
            throw new IllegalStateException("Cấu hình testcase của đề bị hỏng: " + e.getMessage());
        }
    }

    public Map<String, Object> saveDraft(String examId, Map<String, Object> body, String teacherEmail) {
        return save(examId, body, teacherEmail, false);
    }

    public Map<String, Object> publish(String examId, Map<String, Object> body, String teacherEmail) {
        return save(examId, body, teacherEmail, true);
    }

    private Map<String, Object> save(String rawExamId, Map<String, Object> body,
                                     String teacherEmail, boolean publish) {
        ensureReferenceTemplatesLoaded();
        String examId = ExamService.safeId(rawExamId, "đề");
        if (body == null) throw new IllegalArgumentException("Thiếu cấu hình testcase");

        Exam exam = examRepository.findByExamId(examId).orElseGet(Exam::new);
        boolean isNew = exam.getId() == null;
        if (!isNew && !isTemplateCreatedExam(exam, teacherEmail)) {
            throw new IllegalStateException("Mã đề " + examId
                    + " đã tồn tại. Hãy dùng một mã đề mới để tạo testcase.");
        }
        String examName = firstText(body.get("exam_name"), body.get("examName"));
        if (isNew && (examName == null || examName.isBlank()))
            throw new IllegalArgumentException("Vui lòng nhập tên đề thi khi tạo đề mới");
        Map<String, Object> oldConfig = parseConfig(exam.getTestcaseConfigJson());
        Map<String, Map<String, Object>> oldById = indexItems(oldConfig.get("items"));
        List<Map<String, Object>> items = normalizeItems(examId, body.get("items"), oldById, teacherEmail);
        Map<String, Object> suite = normalizeSuite(body.get("suite"), oldConfig.get("suite"));
        String engineType = engineType(items);

        int currentVersion = exam.getTestcaseVersion() == null ? 0 : exam.getTestcaseVersion();
        // Draft cũng là một bản cấu hình materialize được, nên không dùng version 0 sau lần lưu đầu.
        int version = currentVersion + 1;
        Instant now = Instant.now();
        String firstCreatedAt = text(oldConfig.get("created_at"));
        if (firstCreatedAt == null) firstCreatedAt = now.toString();

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("schema_version", 2);
        config.put("exam_id", examId);
        config.put("status", publish ? "PUBLISHED" : "DRAFT");
        config.put("template_version", TEMPLATE_VERSION);
        config.put("engine_type", engineType);
        config.put("profile_id", profileId(engineType));
        config.put("version", version);
        config.put("created_by", text(oldConfig.get("created_by")) != null
                ? oldConfig.get("created_by") : teacherEmail);
        config.put("created_at", firstCreatedAt);
        config.put("updated_by", teacherEmail);
        config.put("updated_at", now.toString());
        if (publish) config.put("published_at", now.toString());
        else if (oldConfig.get("published_at") != null) config.put("published_at", oldConfig.get("published_at"));
        config.put("suite", suite);
        config.put("items", items);

        try {
            String skillsMatrixJson = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(toSkillsMatrix(items, engineType, suite));
            validateGeneratedMatrix(skillsMatrixJson);
            Map<String, Object> generatedMatrix = mapper.readValue(skillsMatrixJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            // Bộ mới chỉ chứa rubric trong skills_matrix hiện tại, không ghép lại dữ liệu cũ.
            Map<String, Object> publishedMatrix = generatedMatrix;

            // Draft cũng materialize thành bộ code để giáo viên tải xuống kiểm tra ngay;
            // chỉ Publish mới chuyển ExamStatus sang READY để cho phép chấm.
            if (publish) examService.snapshotCurrentTestcase(examId);
            Path dir = examService.testcaseDirectoryForConfiguration(examId);
            Files.createDirectories(dir);
            materializeEngine(dir, engineType);
            if (COMMON_ENGINE.equals(engineType)) materializeDirectFunctionRunner(dir, items);
            Files.writeString(dir.resolve("skills_matrix.json"), mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(publishedMatrix), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("testcase-config.json"), mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config), StandardCharsets.UTF_8);
            exam.setTestcasePath(dir.toAbsolutePath().normalize().toString());
            boolean engineReady = Files.exists(dir.resolve("exam_test.dart"))
                    && Files.exists(dir.resolve("grader.dart"));
            exam.setStatus(publish && engineReady ? ExamStatus.READY : ExamStatus.BUILDING);

            exam.setExamId(examId);
            if (isNew || exam.getCreatedBy() == null || exam.getCreatedBy().isBlank()) exam.setCreatedBy(teacherEmail);
            if (examName != null && !examName.isBlank()) exam.setExamName(examName.trim());
            String teacherNote = firstText(body.get("teacher_note"), body.get("teacherNote"));
            if (teacherNote != null) exam.setTeacherNote(teacherNote.trim());
            exam.setTestcaseConfigJson(mapper.writeValueAsString(config));
            exam.setTestcaseVersion(version);
            exam.setTestcaseStatus(publish ? "PUBLISHED" : "DRAFT");
            if (publish) exam.setTestcasePublishedAt(now);
            examRepository.save(exam);
            return response(exam, config, items, publish);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không lưu được cấu hình testcase: " + e.getMessage(), e);
        }
    }

    /** Chọn engine theo profile, không dùng grader gắn chặt với một đề cho testcase chung. */
    private void materializeEngine(Path dir, String engineType) throws Exception {
        if (COMMON_ENGINE.equals(engineType)) {
            copyClasspathEngine(dir, "common-testcase-engine/grader.dart", "grader.dart");
            copyClasspathEngine(dir, "common-testcase-engine/exam_test.dart", "exam_test.dart");
        }
    }

    private void materializeDirectFunctionRunner(Path dir, List<Map<String, Object>> items) throws Exception {
        List<Map<String, Object>> directItems = items.stream()
                .filter(item -> bool(item.get("enabled"), true) && "DIRECT_FUNCTION".equals(item.get("runner")))
                .toList();
        if (directItems.isEmpty()) return;

        Path examTest = dir.resolve("exam_test.dart");
        String source = Files.readString(examTest, StandardCharsets.UTF_8);
        source = renderDirectFunctionRunner(source, directItems);
        Files.writeString(examTest, source, StandardCharsets.UTF_8);
    }

    String renderDirectFunctionRunner(String source, List<Map<String, Object>> directItems) {
        StringBuilder imports = new StringBuilder();
        StringBuilder dispatch = new StringBuilder();
        int index = 0;
        for (Map<String, Object> item : directItems) {
            Map<String, Object> params = map(item.get("parameters"));
            String functionPath = text(params.get("functionPath"), "");
            String functionName = text(params.get("functionName"), "");
            String alias = "direct_" + index++;
            int argumentCount = directArgumentCount(params.get("argumentsJson"));
            imports.append("import '../").append(functionPath).append("' as ").append(alias).append(";\n");
            dispatch.append("    case '").append(functionPath).append("::").append(functionName).append("':\n")
                    .append("      return ").append(alias).append('.').append(functionName).append('(');
            for (int argument = 0; argument < argumentCount; argument++) {
                if (argument > 0) dispatch.append(", ");
                dispatch.append("arguments[").append(argument).append(']');
            }
            dispatch.append(");\n");
        }
        source = source.replace("// __DIRECT_FUNCTION_IMPORTS__", imports.toString().stripTrailing());
        source = source.replace("    // __DIRECT_FUNCTION_CASES__", dispatch.toString().stripTrailing());
        return source;
    }

    private int directArgumentCount(Object rawArguments) {
        try {
            List<?> arguments = mapper.readValue(text(rawArguments, "[]"), new TypeReference<List<Object>>() {});
            return arguments.size();
        } catch (Exception e) {
            throw new IllegalArgumentException("argumentsJson khong hop le khi sinh dispatcher: " + e.getMessage(), e);
        }
    }

    private void copyClasspathEngine(Path dir, String resourceName, String targetName) throws Exception {
        ClassPathResource resource = new ClassPathResource(resourceName);
        if (!resource.exists()) throw new IllegalStateException("Thiếu engine testcase: " + resourceName);
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, dir.resolve(targetName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Đảm bảo thư viện testcase dùng chung luôn có sẵn trước mỗi request. */
    private synchronized void ensureReferenceTemplatesLoaded() {
        if (templates.isEmpty() && !loadClasspathTemplates("common-testcase-templates.json", COMMON_ENGINE))
            log.error("Không nạp được thư viện testcase dùng chung.");
    }

    /** Chỉ cho phép tiếp tục đúng đề Draft/Publish được tạo bởi chức năng template này. */
    private boolean isTemplateCreatedExam(Exam exam, String teacherEmail) {
        return exam.getTestcaseConfigJson() != null && !exam.getTestcaseConfigJson().isBlank()
                && exam.getCreatedBy() != null && exam.getCreatedBy().equalsIgnoreCase(teacherEmail);
    }

    private Map<String, Object> response(Exam exam, Map<String, Object> config,
                                         List<Map<String, Object>> items, boolean publish) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exam_id", exam.getExamId());
        out.put("status", config.get("status"));
        out.put("version", config.get("version"));
        out.put("template_version", config.get("template_version"));
        out.put("engine_type", config.get("engine_type"));
        out.put("profile_id", config.get("profile_id"));
        out.put("suite", config.get("suite"));
        out.put("items", items);
        out.put("total_weight", totalWeight(items));
        boolean engineReady = exam.getTestcasePath() != null
                && Files.exists(Path.of(exam.getTestcasePath()).resolve("exam_test.dart"))
                && Files.exists(Path.of(exam.getTestcasePath()).resolve("grader.dart"));
        out.put("engine_ready", engineReady);
        if (publish && !engineReady) {
            out.put("warning", "Đã Publish cấu hình, nhưng đề chưa có exam_test.dart và grader.dart để chạy chấm.");
        }
        return out;
    }

    /**
     * Khung chay chung cua mot bo de. Chi nhan thao tac UI trong whitelist de bo testcase
     * van doc lap voi model/repository rieng cua tung starter.
     */
    Map<String, Object> normalizeSuite(Object rawSuite, Object fallbackSuite) {
        Object source = rawSuite instanceof Map<?, ?> ? rawSuite : fallbackSuite;
        Map<String, Object> input = source instanceof Map<?, ?> ? castMap(source) : Map.of();
        Map<String, Object> suite = defaultSuite();
        boolean hasSuite = source instanceof Map<?, ?>;

        suite.put("name", limitedText(input.get("name"), text(suite.get("name")), 120, "Ten khung bo testcase"));
        suite.put("context", limitedText(input.get("context"), "", 120, "Ngu canh bo testcase"));
        suite.put("fixture_name", limitedText(input.get("fixture_name"), "", 120, "Ten fixture"));
        suite.put("fixture_description", limitedText(input.get("fixture_description"), "", 500, "Mo ta fixture"));
        suite.put("profile", enumValue(input.get("profile"), "COMMON_UI", SUITE_PROFILES, "profile"));
        suite.put("reset_strategy", enumValue(input.get("reset_strategy"), "APP_RESTART", RESET_STRATEGIES, "reset_strategy"));
        suite.put("source_contracts", normalizeSourceContracts(input.get("source_contracts")));
        suite.put("persistence", normalizePersistence(input.get("persistence")));
        suite.put("golden", normalizeGolden(input.get("golden")));
        suite.put("strict_semantic_keys", bool(input.get("strict_semantic_keys"), hasSuite));
        suite.put("ready_key", optionalSemanticKey(input.get("ready_key"), "ready_key"));
        suite.put("required_keys", semanticKeyList(input.get("required_keys"), "required_keys", 100));
        suite.put("boot_timeout_ms", boundedInt(input.get("boot_timeout_ms"), 3000, 100, 30000,
                "boot_timeout_ms"));
        suite.put("step_timeout_ms", boundedInt(input.get("step_timeout_ms"), 2000, 100, 30000,
                "step_timeout_ms"));
        suite.put("setup_steps", normalizeSetupSteps(input.get("setup_steps"), "khung bo testcase", 30));
        return suite;
    }

    private Map<String, Object> defaultSuite() {
        Map<String, Object> suite = new LinkedHashMap<>();
        suite.put("suite_version", 1);
        suite.put("name", "Khung mac dinh");
        suite.put("context", "");
        suite.put("fixture_name", "");
        suite.put("fixture_description", "");
        suite.put("profile", "COMMON_UI");
        suite.put("reset_strategy", "APP_RESTART");
        suite.put("source_contracts", List.of());
        suite.put("persistence", defaultPersistence());
        suite.put("golden", defaultGolden());
        suite.put("strict_semantic_keys", false);
        suite.put("ready_key", "");
        suite.put("required_keys", List.of());
        suite.put("boot_timeout_ms", 3000);
        suite.put("step_timeout_ms", 2000);
        suite.put("setup_steps", List.of());
        return suite;
    }

    private List<Map<String, Object>> normalizeSourceContracts(Object raw) {
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("source_contracts phai la mot mang");
        if (list.size() > 30) throw new IllegalArgumentException("source_contracts khong duoc vuot qua 30 muc");
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?>)) throw new IllegalArgumentException("source_contracts #" + (i + 1) + " phai la object");
            Map<String, Object> input = castMap(list.get(i));
            String type = text(input.get("type"), "").trim().toLowerCase();
            if (!SOURCE_CONTRACT_TYPES.contains(type)) throw new IllegalArgumentException("type source contract khong hop le: " + type);
            String path = text(input.get("path"), "").trim().replace('\\', '/');
            if (path.isBlank() || path.startsWith("/") || path.contains("..") || !path.startsWith("lib/"))
                throw new IllegalArgumentException("path source contract phai la duong dan tuong doi trong lib/: " + path);
            List<String> symbols = new ArrayList<>();
            Object rawSymbols = input.get("symbols");
            if (rawSymbols instanceof List<?> values) for (Object value : values) {
                String symbol = text(value, "").trim();
                if (!DART_IDENTIFIER.matcher(symbol).matches()) throw new IllegalArgumentException("Ten symbol Dart khong hop le: " + symbol);
                if (!symbols.contains(symbol)) symbols.add(symbol);
            } else if (rawSymbols != null) {
                for (String value : String.valueOf(rawSymbols).split(",")) {
                    String symbol = value.trim();
                    if (!symbol.isBlank() && !DART_IDENTIFIER.matcher(symbol).matches()) throw new IllegalArgumentException("Ten symbol Dart khong hop le: " + symbol);
                    if (!symbol.isBlank() && !symbols.contains(symbol)) symbols.add(symbol);
                }
            }
            if (symbols.isEmpty()) throw new IllegalArgumentException("source contract phai co it nhat mot symbol");
            Map<String, Object> contract = new LinkedHashMap<>();
            contract.put("type", type); contract.put("path", path); contract.put("symbols", symbols);
            out.add(contract);
        }
        return out;
    }

    private Map<String, Object> normalizePersistence(Object raw) {
        Map<String, Object> input = raw instanceof Map<?, ?> ? castMap(raw) : Map.of();
        Map<String, Object> out = defaultPersistence();
        out.put("enabled", bool(input.get("enabled"), false));
        out.put("storage_kind", enumValue(input.get("storage_kind"), "none", Set.of("none", "sqlite", "api", "shared_preferences"), "persistence.storage_kind"));
        out.put("reload_key", optionalSemanticKey(input.get("reload_key"), "persistence.reload_key"));
        out.put("reset_steps", normalizeSetupSteps(input.get("reset_steps"), "persistence reset", 20));
        out.put("notes", limitedText(input.get("notes"), "", 500, "persistence.notes"));
        return out;
    }

    private Map<String, Object> defaultPersistence() {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("enabled", false); out.put("storage_kind", "none");
        out.put("reload_key", ""); out.put("reset_steps", List.of()); out.put("notes", ""); return out;
    }

    private Map<String, Object> normalizeGolden(Object raw) {
        Map<String, Object> input = raw instanceof Map<?, ?> ? castMap(raw) : Map.of();
        Map<String, Object> out = defaultGolden();
        out.put("enabled", bool(input.get("enabled"), false));
        out.put("portrait_asset", relativeAsset(input.get("portrait_asset"), "golden.portrait_asset"));
        out.put("landscape_asset", relativeAsset(input.get("landscape_asset"), "golden.landscape_asset"));
        out.put("threshold", boundedDecimal(input.get("threshold"), 0.01, 0, 1, "golden.threshold"));
        return out;
    }

    private Map<String, Object> defaultGolden() {
        Map<String, Object> out = new LinkedHashMap<>(); out.put("enabled", false); out.put("portrait_asset", "");
        out.put("landscape_asset", ""); out.put("threshold", 0.01); return out;
    }

    private String relativeAsset(Object raw, String field) {
        String value = text(raw, "").trim().replace('\\', '/');
        if (!value.isBlank() && (value.startsWith("/") || value.contains(".."))) throw new IllegalArgumentException(field + " phai la duong dan tuong doi");
        return value;
    }

    private double boundedDecimal(Object raw, double fallback, double min, double max, String field) {
        double value = number(raw, fallback); if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(field + " phai nam trong khoang " + min + " den " + max); return value;
    }

    private String enumValue(Object raw, String fallback, Set<String> allowed, String field) {
        String value = text(raw, fallback).trim(); if (!allowed.contains(value)) throw new IllegalArgumentException(field + " khong hop le: " + value); return value;
    }

    private List<Map<String, Object>> normalizeSetupSteps(Object raw, String owner, int maxSteps) {
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> steps))
            throw new IllegalArgumentException("setup_steps cua " + owner + " phai la mot mang");
        if (steps.size() > maxSteps)
            throw new IllegalArgumentException("setup_steps cua " + owner + " khong duoc vuot qua " + maxSteps + " buoc");
        List<Map<String, Object>> out = new ArrayList<>();
        int index = 1;
        for (Object rawStep : steps) {
            if (!(rawStep instanceof Map<?, ?>))
                throw new IllegalArgumentException("Buoc setup " + index + " cua " + owner + " phai la object");
            Map<String, Object> input = castMap(rawStep);
            String type = text(input.get("type"), "").trim().toLowerCase();
            if (!SUITE_STEP_TYPES.contains(type))
                throw new IllegalArgumentException("Loai buoc setup khong hop le o " + owner + " #" + index + ": " + type);
            String key = requiredSemanticKey(input.get("key"), owner + " setup #" + index);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("type", type);
            step.put("key", key);
            if ("enter_text".equals(type)) {
                String value = text(input.get("value"));
                if (value == null) throw new IllegalArgumentException("Thieu value o " + owner + " setup #" + index);
                if (value.length() > 1000) throw new IllegalArgumentException("value qua dai o " + owner + " setup #" + index);
                step.put("value", value);
            }
            if (input.get("timeout_ms") != null) {
                step.put("timeout_ms", boundedInt(input.get("timeout_ms"), 2000, 100, 30000,
                        owner + " setup #" + index + " timeout_ms"));
            }
            out.add(step);
            index++;
        }
        return out;
    }

    private List<String> semanticKeyList(Object raw, String field, int maxItems) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String key = text(item);
                if (key != null && !key.isBlank()) values.add(key.trim());
            }
        } else if (raw != null) {
            for (String part : String.valueOf(raw).split(",")) {
                if (!part.trim().isEmpty()) values.add(part.trim());
            }
        }
        if (values.size() > maxItems)
            throw new IllegalArgumentException(field + " khong duoc vuot qua " + maxItems + " key");
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) unique.add(requiredSemanticKey(value, field));
        return new ArrayList<>(unique);
    }

    private String optionalSemanticKey(Object raw, String field) {
        String value = text(raw, "").trim();
        return value.isEmpty() ? "" : requiredSemanticKey(value, field);
    }

    private String requiredSemanticKey(Object raw, String field) {
        String value = text(raw, "").trim();
        if (!SEMANTIC_KEY.matcher(value).matches())
            throw new IllegalArgumentException(field + " phai la semantic key dang screen.home, action.save hoac field.title");
        return value;
    }

    private String limitedText(Object raw, String fallback, int maxLength, String field) {
        String value = text(raw);
        if (value == null) value = fallback;
        value = value == null ? "" : value.trim();
        if (value.length() > maxLength)
            throw new IllegalArgumentException(field + " khong duoc vuot qua " + maxLength + " ky tu");
        return value;
    }

    private int boundedInt(Object raw, int fallback, int min, int max, String field) {
        double value = number(raw, fallback);
        if (!Double.isFinite(value) || value < min || value > max || value != Math.rint(value))
            throw new IllegalArgumentException(field + " phai la so nguyen tu " + min + " den " + max);
        return (int) value;
    }

    private List<Map<String, Object>> normalizeItems(String examId, Object rawItems,
                                                       Map<String, Map<String, Object>> oldById,
                                                       String teacherEmail) {
        if (!(rawItems instanceof List<?> list)) throw new IllegalArgumentException("items phải là một mảng testcase");
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int index = 1;
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?>)) throw new IllegalArgumentException("Mỗi testcase phải là object");
            Map<String, Object> input = castMap(raw);
            String templateId = text(input.get("template_id"));
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
            if (COMMON_ENGINE.equals(templateEngine)) {
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
            item.put("setup_steps", normalizeSetupSteps(input.get("setup_steps"),
                    "testcase " + instanceId, 15));
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
            String groupId = text(input.get("group_id"));
            if (groupId != null && !groupId.isBlank()) {
                if (!COMMON_ENGINE.equals(templateEngine))
                    throw new IllegalArgumentException("Testcase này không thuộc thư viện dùng chung: " + instanceId);
                if (!SAFE_INSTANCE_ID.matcher(groupId).matches())
                    throw new IllegalArgumentException("group_id không hợp lệ ở " + instanceId);
                item.put("group_id", groupId);
                String groupName = text(input.get("group_name"));
                item.put("group_name", groupName == null || groupName.isBlank() ? groupId : groupName.trim());
            }
            item.put("created_by", previous != null && previous.get("created_by") != null
                    ? previous.get("created_by") : teacherEmail);
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
            case "DIRECT_FUNCTION" -> validateDirectFunction(params, instanceId);
            case "APP_BOOT" -> { /* rootKey có thể để trống nếu app không công bố root key. */ }
            default -> throw new IllegalArgumentException("Common runner không tồn tại: " + runner);
        }
    }

    private void validateDirectFunction(Map<String, Object> params, String instanceId) {
        String path = text(params.get("functionPath"), "").trim().replace('\\', '/');
        if (path.isBlank() || path.startsWith("/") || path.contains("..") || !path.startsWith("lib/")
                || !path.endsWith(".dart")) {
            throw new IllegalArgumentException("functionPath phai la file Dart tuong doi trong lib/ o " + instanceId);
        }
        String functionName = text(params.get("functionName"), "").trim();
        if (!DART_IDENTIFIER.matcher(functionName).matches())
            throw new IllegalArgumentException("functionName khong hop le o " + instanceId + ": " + functionName);
        String argumentsJson = text(params.get("argumentsJson"), "[]").trim();
        try {
            Object arguments = mapper.readValue(argumentsJson, Object.class);
            if (!(arguments instanceof List<?> list) || list.size() > 5)
                throw new IllegalArgumentException("argumentsJson phai la mang JSON toi da 5 phan tu o " + instanceId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("argumentsJson khong hop le o " + instanceId + ": " + e.getMessage());
        }
        String expectedType = text(params.get("expectedType"), "string").toLowerCase();
        if (!Set.of("string", "bool", "int", "double", "json", "null").contains(expectedType))
            throw new IllegalArgumentException("expectedType khong hop le o " + instanceId);
        String matchMode = text(params.get("matchMode"), "equals").toLowerCase();
        if (!Set.of("equals", "contains").contains(matchMode))
            throw new IllegalArgumentException("matchMode khong hop le o " + instanceId);
        requireParameter(params, "expectedValue", instanceId);
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

    Map<String, Object> toSkillsMatrix(List<Map<String, Object>> items, String engineType,
                                               Map<String, Object> suite) {
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
                matrix.put(groupId, commonGroupRow(groupId, children, suite));
                continue;
            }
            matrix.put(String.valueOf(item.get("instance_id")),
                    commonRubricRow(item, suite));
        }
        return matrix;
    }

    /** Matrix của engine chung: runner đọc semantic key và parameters, không biết domain đề. */
    private Map<String, Object> commonRubricRow(Map<String, Object> item, Map<String, Object> suite) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("instance_id", item.get("instance_id"));
        row.put("runner", item.get("runner"));
        row.put("skill_code", item.get("skill_code"));
        row.put("testcase_group", item.get("testcase_group"));
        row.put("name", item.get("name"));
        row.put("description", item.get("description"));
        row.put("expected", item.get("expected"));
        row.put("difficulty", item.get("difficulty"));
        row.put("weight", item.get("weight"));
        row.put("parameters", item.get("parameters"));
        row.put("setup_steps", item.getOrDefault("setup_steps", List.of()));
        row.put("suite", suite);
        return row;
    }

    /** Một testcase cha chỉ có một kết quả; mọi testcase con phải đạt thì nhóm mới đạt. */
    private Map<String, Object> commonGroupRow(String groupId, List<Map<String, Object>> children,
                                                       Map<String, Object> suite) {
        Map<String, Object> row = new LinkedHashMap<>();
        List<Map<String, Object>> childRows = new ArrayList<>();
        Set<String> skillCodes = new LinkedHashSet<>();
        double totalWeight = 0;
        String groupName = groupId;
        String difficulty = "basic";
        for (Map<String, Object> child : children) {
            childRows.add(commonRubricRow(child, suite));
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
        row.put("name", groupName);
        row.put("expected", "Tất cả " + children.size() + " assert trong nhóm phải đạt.");
        row.put("difficulty", difficulty);
        row.put("weight", totalWeight);
        row.put("skill_code", skillCodes.isEmpty() ? "UI_SCAFFOLD_APPBAR" : skillCodes.iterator().next());
        row.put("skill_codes", new ArrayList<>(skillCodes));
        row.put("children", childRows);
        row.put("suite", suite);
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

    private String friendlyTemplateName(String templateId, String fallback) {
        return FRIENDLY_TEMPLATE_NAMES.getOrDefault(templateId, fallback);
    }

    private String friendlyTemplateDescription(String templateId, String fallback) {
        if (fallback != null && !fallback.isBlank()) return fallback;
        String name = friendlyTemplateName(templateId, templateId);
        return FRIENDLY_TEMPLATE_DESCRIPTIONS.getOrDefault(templateId,
                "Mô tả yêu cầu: " + name + ".");
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
