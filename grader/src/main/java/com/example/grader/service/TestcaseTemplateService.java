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

    private static final String TEMPLATE_VERSION = "2026.1";
    private static final String TEMPLATE_CREATED_BY = "system";
    private static final String TEMPLATE_CREATED_AT = "2026-08-02T00:00:00Z";
    private static final String COMMON_ENGINE = "COMMON_V1";
    private static final Pattern SAFE_INSTANCE_ID = Pattern.compile("[A-Za-z0-9_-]{1,60}");
    private static final Pattern TEMPLATE_ID_PATTERN = Pattern.compile("[A-Z0-9_]{3,80}");
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
        if (loadClasspathTemplates("common-testcase-templates.json", COMMON_ENGINE))
            log.info("✅ Nạp {} testcase dùng chung từ common-testcase-templates.json", templates.size());
        else log.error("Không nạp được thư viện testcase dùng chung.");
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
                row.put("name", friendlyTemplateName(id, text(source.get("name"), id)));
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
            boolean engineReady = Files.exists(dir.resolve("exam_test.dart"))
                    && Files.exists(dir.resolve("grader.dart"));
            exam.setStatus(publish && engineReady ? ExamStatus.READY : ExamStatus.BUILDING);

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
        copyClasspathEngine(dir, "common-testcase-engine/grader.dart", "grader.dart");
        String engine = readClasspathEngine("common-testcase-engine/exam_test.dart");
        Files.writeString(dir.resolve("exam_test.dart"),
                injectCustomTestcases(engine, enabledCustomItems(items)), StandardCharsets.UTF_8);
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
        validateCommonParameters(runner, parameters, templateId);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("template_id", templateId);
        row.put("template_version", text(base.get("template_version"), "custom-tpl-v1"));
        row.put("engine_type", COMMON_ENGINE);
        row.put("profile_id", "COMMON_SEMANTIC_V1");
        row.put("runner", runner);
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
        return CUSTOM_TEMPLATE_ID.equals(text(input.get("template_id")))
                || CUSTOM_RUNNER.equals(text(input.get("runner")));
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
