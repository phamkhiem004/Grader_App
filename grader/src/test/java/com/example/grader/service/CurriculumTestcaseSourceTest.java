package com.example.grader.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chốt nguồn học liệu không tham chiếu skill/template ảo và mẫu logic đổi được domain. */
class CurriculumTestcaseSourceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void curriculumSourceCoversAllDocumentsAndReferencesRealSkillsAndTemplates() throws Exception {
        Map<String, Object> source = objectResource("prm393-curriculum-testcase-source.json");
        List<Map<String, Object>> modules = rows(source.get("modules"));
        assertEquals(12, modules.size());

        Set<String> documents = new LinkedHashSet<>();
        Set<String> usedSkills = new LinkedHashSet<>();
        Set<String> recommendedTemplates = new LinkedHashSet<>();
        for (Map<String, Object> module : modules) {
            assertFalse(strings(module.get("recommended_template_ids")).isEmpty(),
                    module.get("id") + " chưa có template tái sử dụng gợi ý");
            documents.addAll(strings(module.get("source_document_ids")));
            usedSkills.addAll(strings(module.get("skill_codes")));
            recommendedTemplates.addAll(strings(module.get("recommended_template_ids")));
        }
        documents.addAll(strings(source.get("project_source_document_ids")));
        assertEquals(26, documents.size(), "Nguồn curriculum phải trỏ đủ 26 tài liệu đã nhập");
        Map<String, Object> library = asMap(source.get("template_library"));
        assertEquals(25, library.get("common_engine_templates"));
        assertEquals(52, library.get("curriculum_templates"));
        assertEquals(77, library.get("total_templates"));

        Map<String, Object> syllabus = objectResource("syllabus.json");
        Set<String> syllabusSkills = new LinkedHashSet<>();
        for (Map<String, Object> row : rows(syllabus.get("skills"))) {
            syllabusSkills.add(String.valueOf(row.get("code")));
        }
        assertTrue(syllabusSkills.containsAll(usedSkills),
                "Nguồn curriculum tham chiếu skill chưa có trong syllabus: "
                        + difference(usedSkills, syllabusSkills));

        Set<String> templateIds = new LinkedHashSet<>();
        for (Map<String, Object> row : allTemplates()) {
            templateIds.add(String.valueOf(row.get("template_id")));
        }
        assertTrue(templateIds.containsAll(recommendedTemplates),
                "Nguồn curriculum tham chiếu template chưa tồn tại: "
                        + difference(recommendedTemplates, templateIds));
    }

    @Test
    void serviceLoadsCommonAndCurriculumLibrariesTogether() throws Exception {
        TestcaseTemplateService service = new TestcaseTemplateService();
        service.loadTemplates();
        Field templatesField = TestcaseTemplateService.class.getDeclaredField("templates");
        templatesField.setAccessible(true);
        Map<?, ?> loaded = (Map<?, ?>) templatesField.get(service);
        assertEquals(83, loaded.size());
        assertTrue(loaded.containsKey("COMMON_APP_BOOT"));
        assertTrue(loaded.containsKey("CURRICULUM_STORAGE_SQLITE_CRUD"));
        Map<String, Object> widgetVisible = asMap(loaded.get("COMMON_WIDGET_VISIBLE"));
        assertTrue(asMap(widgetVisible.get("parameters_schema")).containsKey("targetType"),
                "COMMON_WIDGET_VISIBLE phải giữ targetType để các bộ đã cấu hình mở và lưu lại được");
    }

    @Test
    void everyAutomaticallyTestableSkillHasAtLeastOneReusableTemplate() throws Exception {
        Set<String> automatedModes = Set.of(
                "auto", "auto_with_fixture", "auto_and_static",
                "auto_with_isolated_database", "auto_with_process_fixture");
        Set<String> expectedSkills = new LinkedHashSet<>();
        for (Map<String, Object> skill : rows(objectResource("syllabus.json").get("skills"))) {
            if (automatedModes.contains(String.valueOf(skill.get("testable")))) {
                expectedSkills.add(String.valueOf(skill.get("code")));
            }
        }

        Set<String> coveredSkills = new LinkedHashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> template : allTemplates()) {
            assertTrue(ids.add(String.valueOf(template.get("template_id"))),
                    "Trùng template_id giữa thư viện engine và curriculum");
            coveredSkills.add(String.valueOf(template.get("skill_code")));
        }
        assertEquals(62, expectedSkills.size());
        assertTrue(coveredSkills.containsAll(expectedSkills),
                "Skill có thể chấm tự động nhưng chưa có template: "
                        + difference(expectedSkills, coveredSkills));
        assertEquals(83, ids.size(), "Thư viện phải gồm 30 mẫu chung và 53 mẫu curriculum");
    }

    @Test
    void everyCurriculumTemplateDefaultParametersGenerateOrValidate() throws Exception {
        TestcaseTemplateService service = new TestcaseTemplateService();
        Method validateGenerated = TestcaseTemplateService.class.getDeclaredMethod(
                "validateGeneratedCustomParameters", Map.class, Map.class, String.class);
        Method generate = TestcaseTemplateService.class.getDeclaredMethod(
                "generateCustomCode", Map.class, Map.class);
        Method validateCommon = TestcaseTemplateService.class.getDeclaredMethod(
                "validateCommonParameters", String.class, Map.class, String.class);
        validateGenerated.setAccessible(true);
        generate.setAccessible(true);
        validateCommon.setAccessible(true);

        for (Map<String, Object> template : arrayResource(
                "prm393-curriculum-testcase-templates.json")) {
            String id = String.valueOf(template.get("template_id"));
            Map<String, Object> params = new LinkedHashMap<>(asMap(template.get("parameters_schema")));
            if (template.get("code_generator") != null) {
                validateGenerated.invoke(service, template, params, id);
                String code = String.valueOf(generate.invoke(service, template, params));
                assertFalse(code.isBlank(), id + " không sinh được code");
                assertFalse(code.contains("grading_adapter"), id + " không được dùng adapter");
            } else {
                validateCommon.invoke(service, String.valueOf(template.get("runner")), params, id);
            }
        }
    }

    @Test
    void generatedLogicTemplateIsTheSameRunnerAcrossThreeUnrelatedContexts() throws Exception {
        Map<String, Map<String, Object>> templates = new LinkedHashMap<>();
        for (Map<String, Object> row : arrayResource("common-testcase-templates.json")) {
            templates.put(String.valueOf(row.get("template_id")), row);
        }
        Map<String, Object> template = templates.get("COMMON_PUBLIC_FUNCTION_RESULT");
        TestcaseTemplateService service = new TestcaseTemplateService();
        Method validate = TestcaseTemplateService.class.getDeclaredMethod(
                "validateGeneratedCustomParameters", Map.class, Map.class, String.class);
        Method generate = TestcaseTemplateService.class.getDeclaredMethod(
                "generateCustomCode", Map.class, Map.class);
        validate.setAccessible(true);
        generate.setAccessible(true);

        List<Map<String, Object>> contexts = List.of(
                context("lib/tasks/task_rules.dart", "validateTitle", "[\"A\"]", "true"),
                context("lib/catalog/price_rules.dart", "PriceRules.total", "[2,10]", "20"),
                context("lib/ledger/expense_rules.dart", "isValidAmount", "[50]", "true"));
        Set<String> generated = new LinkedHashSet<>();
        int index = 0;
        for (Map<String, Object> params : contexts) {
            validate.invoke(service, template, params, "context_" + ++index);
            String code = String.valueOf(generate.invoke(service, template, params));
            generated.add(code);
            assertTrue(code.contains("student_app." + params.get("callable")));
            assertTrue(code.contains("jsonDecode"));
            assertFalse(code.contains("grading_adapter"));
            assertFalse(code.toLowerCase().contains("userrepository"));
        }
        assertEquals(3, generated.size(),
                "Một template phải sinh đúng ba lời gọi khác nhau chỉ bằng thay parameters");

        Map<String, Object> streamTemplate = templates.get("COMMON_PUBLIC_STREAM_EVENTS");
        Map<String, Object> streamParams = context(
                "lib/events/task_events.dart", "taskEvents", "[]", "null");
        streamParams.remove("expectedJson");
        streamParams.put("expectedEventsJson", "[\"created\",\"completed\"]");
        validate.invoke(service, streamTemplate, streamParams, "stream_context");
        String streamCode = String.valueOf(generate.invoke(service, streamTemplate, streamParams));
        assertTrue(streamCode.contains(".take(2).toList()"),
                "Stream chạy liên tục phải dừng sau đúng số event expected, không chờ close vô hạn");
        assertTrue(streamCode.contains("File(\"lib/events/task_events.dart\")"),
                "contractPath phải được dùng thật trong code sinh ra");
    }

    @Test
    void publicContractTemplatesRejectFreeFormDartAndUnsafePaths() throws Exception {
        Map<String, Object> template = arrayResource("common-testcase-templates.json").stream()
                .filter(row -> "COMMON_PUBLIC_FUNCTION_RESULT".equals(row.get("template_id")))
                .findFirst().orElseThrow();
        TestcaseTemplateService service = new TestcaseTemplateService();
        Method validate = TestcaseTemplateService.class.getDeclaredMethod(
                "validateGeneratedCustomParameters", Map.class, Map.class, String.class);
        validate.setAccessible(true);

        assertRejected(validate, service, template,
                context("../secret.dart", "run", "[]", "true"), "unsafe_path");
        assertRejected(validate, service, template,
                context("lib/domain/rules.dart", "run(); evil", "[]", "true"), "unsafe_callable");
    }

    @Test
    void sourceContractTargetsEachFileAndIgnoresCommentsInsteadOfPassingOnGlobalText() throws Exception {
        TestcaseTemplateService service = new TestcaseTemplateService();
        service.loadTemplates();
        Field templatesField = TestcaseTemplateService.class.getDeclaredField("templates");
        templatesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> loaded =
                (Map<String, Map<String, Object>>) templatesField.get(service);
        Map<String, Object> template = loaded.get("CURRICULUM_PROJ_FOLDER_STRUCTURE");
        Map<String, Object> params = new LinkedHashMap<>(asMap(template.get("parameters_schema")));
        params.put("sourceChecksJson", "["
                + "{\"path\":\"lib/models/task.dart\","
                + "\"requiredTokens\":[\"class Task\"],\"forbiddenTokens\":[\"dynamic id\"]},"
                + "{\"path\":\"lib/repositories/task_repository.dart\","
                + "\"requiredTokens\":[\"class TaskRepository\"],\"forbiddenTokens\":[]}"
                + "]");

        Method validate = TestcaseTemplateService.class.getDeclaredMethod(
                "validateGeneratedCustomParameters", Map.class, Map.class, String.class);
        Method generate = TestcaseTemplateService.class.getDeclaredMethod(
                "generateCustomCode", Map.class, Map.class);
        validate.setAccessible(true);
        generate.setAccessible(true);
        validate.invoke(service, template, params, "source_exact");
        String code = String.valueOf(generate.invoke(service, template, params));

        assertTrue(code.contains("final source0 = _sourceWithoutComments"));
        assertTrue(code.contains("final source1 = _sourceWithoutComments"));
        assertTrue(code.contains("_sourceContainsToken(source0, \"class Task\", caseSensitive: true)"));
        assertTrue(code.contains("_sourceContainsToken(source1, \"class TaskRepository\", caseSensitive: true)"));
        assertTrue(code.contains("_observe('SOURCE_CONTRACT_VIOLATION'"));
        assertFalse(code.contains("sourceParts.join"),
                "Chế độ exact không được gộp file rồi tìm token toàn cục");

        String engine = textResource("common-testcase-engine/exam_test.dart");
        assertTrue(engine.contains("String _sourceWithoutComments"));
        assertTrue(engine.contains("inLineComment"));
        assertTrue(engine.contains("inBlockComment"));
        assertTrue(engine.contains("bool _sourceContainsToken"));

        params.put("sourceChecksJson", "[{\"path\":\"../secret.dart\","
                + "\"requiredTokens\":[\"secret\"],\"forbiddenTokens\":[]}]");
        assertRejected(validate, service, template, params, "unsafe_exact_source_path");
    }

    private void assertRejected(Method validate, TestcaseTemplateService service,
                                Map<String, Object> template, Map<String, Object> params,
                                String instanceId) throws Exception {
        boolean rejected = false;
        try {
            validate.invoke(service, template, params, instanceId);
        } catch (java.lang.reflect.InvocationTargetException error) {
            rejected = error.getCause() instanceof IllegalArgumentException;
        }
        assertTrue(rejected, "Template tham số hóa không được trở thành ô chèn code Dart tự do: "
                + instanceId);
    }

    private Map<String, Object> context(String path, String callable,
                                        String argumentsJson, String expectedJson) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("contractPath", path);
        params.put("callable", callable);
        params.put("argumentsJson", argumentsJson);
        params.put("expectedJson", expectedJson);
        params.put("timeoutMs", 3000);
        return params;
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> out = new LinkedHashSet<>(left);
        out.removeAll(right);
        return out;
    }

    private Map<String, Object> objectResource(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {});
        }
    }

    private String textResource(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<Map<String, Object>> arrayResource(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, new TypeReference<ArrayList<Map<String, Object>>>() {});
        }
    }

    private List<Map<String, Object>> allTemplates() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>(
                arrayResource("common-testcase-templates.json"));
        out.addAll(arrayResource("prm393-curriculum-testcase-templates.json"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
}
