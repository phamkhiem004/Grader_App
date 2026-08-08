package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestcaseTemplateSuiteTest {

    private final TestcaseTemplateService service = new TestcaseTemplateService();

    @Test
    void normalizesSuiteAndWhitelistedSetupSteps() {
        Map<String, Object> suite = service.normalizeSuite(Map.of(
                "name", "Todo CRUD",
                "context", "todo_crud",
                "fixture_name", "one_existing_todo",
                "ready_key", "screen.home.ready",
                "required_keys", List.of("screen.home", "list.items"),
                "setup_steps", List.of(Map.of("type", "tap", "key", "action.add"))), null);

        assertEquals("Todo CRUD", suite.get("name"));
        assertEquals(true, suite.get("strict_semantic_keys"));
        assertEquals(List.of("screen.home", "list.items"), suite.get("required_keys"));
        assertEquals("tap", ((List<?>) suite.get("setup_steps")).get(0) instanceof Map<?, ?> step
                ? step.get("type") : null);
    }

    @Test
    void normalizesLayeredContractsPersistenceAndGolden() {
        Map<String, Object> suite = service.normalizeSuite(Map.of(
                "profile", "FLUTTER_LAYERED",
                "reset_strategy", "FIXTURE_STEPS",
                "source_contracts", List.of(Map.of(
                        "type", "provider",
                        "path", "lib/providers/user_provider.dart",
                        "symbols", "userRepositoryProvider, userViewModelProvider")),
                "persistence", Map.of("enabled", true, "storage_kind", "sqlite", "reload_key", "action.reload"),
                "golden", Map.of("enabled", true, "portrait_asset", "goldens/home.png", "threshold", 0.02)), null);

        assertEquals("FLUTTER_LAYERED", suite.get("profile"));
        Map<?, ?> contract = (Map<?, ?>) ((List<?>) suite.get("source_contracts")).get(0);
        assertEquals(List.of("userRepositoryProvider", "userViewModelProvider"), contract.get("symbols"));
        assertEquals(true, ((Map<?, ?>) suite.get("persistence")).get("enabled"));
        assertEquals(0.02, ((Map<?, ?>) suite.get("golden")).get("threshold"));
    }

    @Test
    void acceptsDirectFunctionParameters() {
        Map<String, Object> suite = service.normalizeSuite(Map.of(
                "profile", "FLUTTER_LAYERED"), null);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("instance_id", "EXAM_item_01");
        item.put("template_id", "COMMON_DIRECT_FUNCTION");
        item.put("runner", "DIRECT_FUNCTION");
        item.put("skill_code", "DART_CLASSES_OOP");
        item.put("layer", "UNIT");
        item.put("testcase_group", "LOGIC");
        item.put("name", "Direct function");
        item.put("description", "Direct function");
        item.put("expected", "true");
        item.put("difficulty", "advanced");
        item.put("weight", 2);
        item.put("parameters", Map.of(
                "functionPath", "lib/utils/validator.dart",
                "functionName", "isValidEmail",
                "argumentsJson", "[\"student@example.com\"]",
                "expectedType", "bool",
                "expectedValue", "true",
                "matchMode", "equals"));
        item.put("setup_steps", List.of());
        Map<?, ?> matrixRow = (Map<?, ?>) service.toSkillsMatrix(List.of(item), "COMMON_V1", suite).get("EXAM_item_01");
        assertEquals("DIRECT_FUNCTION", matrixRow.get("runner"));
        assertEquals("UNIT", matrixRow.get("layer"));
        assertEquals("isValidEmail", ((Map<?, ?>) matrixRow.get("parameters")).get("functionName"));
    }

    @Test
    void rendersConfiguredDirectFunctionIntoExamTest() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("runner", "DIRECT_FUNCTION");
        item.put("parameters", Map.of(
                "functionPath", "lib/utils/validator.dart",
                "functionName", "isValidEmail",
                "argumentsJson", "[\"student@example.com\"]"));

        String source = service.renderDirectFunctionRunner(
                "import '../lib/main.dart' as student_app;\n"
                        + "// __DIRECT_FUNCTION_IMPORTS__\n"
                        + "switch (target) {\n    // __DIRECT_FUNCTION_CASES__\n}",
                new ArrayList<>(List.of(item)));

        assertEquals(true, source.contains("import '../lib/utils/validator.dart' as direct_0;"));
        assertEquals(true, source.contains("case 'lib/utils/validator.dart::isValidEmail':"));
        assertEquals(true, source.contains("return direct_0.isValidEmail(arguments[0]);"));
        assertEquals(false, source.contains("Function.apply"));
    }

    @Test
    void reusesOneDispatcherWhenFunctionHasManyInputCases() {
        Map<String, Object> first = Map.of("runner", "DIRECT_FUNCTION", "parameters", Map.of(
                "functionPath", "lib/grading/grading_adapter.dart",
                "functionName", "runGradingCase",
                "argumentsJson", "[\"load\",{}]"));
        Map<String, Object> second = Map.of("runner", "DIRECT_FUNCTION", "parameters", Map.of(
                "functionPath", "lib/grading/grading_adapter.dart",
                "functionName", "runGradingCase",
                "argumentsJson", "[\"add\",{\"name\":\"A\"}]"));

        String source = service.renderDirectFunctionRunner(
                "// __DIRECT_FUNCTION_IMPORTS__\nswitch (target) {\n    // __DIRECT_FUNCTION_CASES__\n}",
                List.of(first, second));

        assertEquals(1, count(source, "case 'lib/grading/grading_adapter.dart::runGradingCase':"));
        assertEquals(1, count(source, "import '../lib/grading/grading_adapter.dart'"));
    }

    private int count(String source, String value) {
        return (source.length() - source.replace(value, "").length()) / value.length();
    }

    @Test
    void keepsFairGradingFixesWhenMergingCommonEngine() throws Exception {
        String examTest = new ClassPathResource("common-testcase-engine/exam_test.dart")
                .getContentAsString(StandardCharsets.UTF_8);
        String grader = new ClassPathResource("common-testcase-engine/grader.dart")
                .getContentAsString(StandardCharsets.UTF_8);

        assertTrue(examTest.contains("Finder _goneByKey("),
                "Không được dùng finder fallback khi kiểm tra widget đã biến mất");
        assertTrue(examTest.contains("Future<void> _tap("));
        assertTrue(examTest.contains("Never _failIfActionThrew("));
        assertTrue(examTest.contains("finally {\n    semantics.dispose();"),
                "SemanticsHandle phải dispose ngay trong finally");
        assertTrue(grader.contains("COMMON_V1-2.8.0"),
                "Không được hạ version engine khi xử lý merge conflict");
        assertTrue(examTest.contains("GRADER_CASE_MODE"));
        assertTrue(examTest.contains("_GRADER_PREFLIGHT"));
        assertTrue(examTest.contains("_revealLazyItem"));
        assertTrue(grader.contains("Blocked bởi lỗi khởi động chung"));
        assertTrue(grader.contains("event['result'] == 'success' && !skipped"));
        assertTrue(grader.contains("process.exitCode.timeout(timeout)"));
    }

    @Test
    void shipsFixedTodoStarterV12AsASeparateExactIdEngine() throws Exception {
        String examTest = new ClassPathResource("todo-user-v12-engine/exam_test.dart")
                .getContentAsString(StandardCharsets.UTF_8);
        String grader = new ClassPathResource("todo-user-v12-engine/grader.dart")
                .getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> templates = new ObjectMapper().readValue(
                new ClassPathResource("todo-user-v12-testcase-templates.json").getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {});

        assertEquals(48, templates.size());
        assertEquals(100.0, templates.stream()
                .mapToDouble(row -> ((Number) row.get("weight_default")).doubleValue()).sum(), 0.0001);
        assertTrue(templates.stream().allMatch(row -> "TODO_USER_V12".equals(row.get("engine_type"))));
        assertTrue(templates.stream().allMatch(row -> row.get("template_id").equals(row.get("execution_key"))));
        assertTrue(examTest.contains("import '../lib/main.dart' as student_app;"));
        assertTrue(!examTest.contains("grading_adapter.dart"));
        assertTrue(grader.contains("event['result'] == 'success' && !skipped"));
    }

    @Test
    void fixedTodoMatrixUsesRunnerExecutionKeyInsteadOfUiInstanceId() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("instance_id", "ignored_ui_id");
        item.put("execution_key", "MODEL_GRANULAR_MAPPING");
        item.put("template_id", "MODEL_GRANULAR_MAPPING");
        item.put("skill_code", "DART_CLASSES_OOP");
        item.put("layer", "MODEL");
        item.put("name", "Mapping");
        item.put("expected", "Maps correctly");
        item.put("difficulty", "basic");
        item.put("weight", 1.0);
        item.put("enabled", true);

        Map<String, Object> matrix = service.toSkillsMatrix(
                List.of(item), "TODO_USER_V12", service.normalizeSuite(Map.of(
                        "profile", "TODO_STARTER_V12",
                        "strict_semantic_keys", false), null));

        assertTrue(matrix.containsKey("MODEL_GRANULAR_MAPPING"));
        assertTrue(!matrix.containsKey("ignored_ui_id"));
        Map<?, ?> row = (Map<?, ?>) matrix.get("MODEL_GRANULAR_MAPPING");
        assertEquals("MODEL", row.get("rubric"));
        assertTrue(!row.containsKey("suite"));
        assertTrue(!row.containsKey("parameters"));
    }

    @Test
    void materializesFixedTodoEngineAndRejectsMixedEngines(@TempDir Path root) throws Exception {
        service.materializeEngine(root, "TODO_USER_V12");

        assertTrue(Files.exists(root.resolve("exam_test.dart")));
        assertTrue(Files.exists(root.resolve("grader.dart")));
        assertTrue(Files.readString(root.resolve("exam_test.dart"), StandardCharsets.UTF_8)
                .contains("import '../lib/main.dart' as student_app;"));
        assertEquals("TODO_USER_V12", service.engineType(List.of(
                Map.of("engine_type", "TODO_USER_V12"))));
        assertThrows(IllegalArgumentException.class, () -> service.engineType(List.of(
                Map.of("engine_type", "TODO_USER_V12"),
                Map.of("engine_type", "COMMON_V1"))));
    }

    @Test
    void exposesCompleteTodoV12PackWithThreeScopesAndNoDuplicateIds() {
        List<Map<String, Object>> packs = service.listTemplatePacks();
        Map<String, Object> pack = packs.stream()
                .filter(row -> "TODO_USER_STARTER_V12".equals(row.get("pack_id")))
                .findFirst().orElseThrow();

        assertEquals("TODO_USER_V12", pack.get("engine_type"));
        assertEquals(48, pack.get("testcase_count"));
        assertEquals(100.0, ((Number) pack.get("default_weight")).doubleValue(), 0.0001);
        assertEquals(48, new java.util.LinkedHashSet<>((List<?>) pack.get("template_ids")).size());
        List<?> scopes = (List<?>) pack.get("scopes");
        assertEquals(3, scopes.size());
        assertEquals(List.of(20, 17, 11), scopes.stream()
                .map(scope -> ((Number) ((Map<?, ?>) scope).get("testcase_count")).intValue())
                .toList());
    }

    @Test
    void rejectsEmptyZeroWeightAndUnsupportedSuiteBeforePublish() {
        assertThrows(IllegalArgumentException.class,
                () -> service.validatePublishable(List.of()));

        Map<String, Object> zeroWeight = new LinkedHashMap<>();
        zeroWeight.put("enabled", true);
        zeroWeight.put("weight", 0);
        assertThrows(IllegalArgumentException.class,
                () -> service.validatePublishable(List.of(zeroWeight)));

        Map<String, Object> suite = service.normalizeSuite(Map.of(
                "persistence", Map.of("enabled", true, "storage_kind", "sqlite")), null);
        assertThrows(IllegalArgumentException.class,
                () -> service.validateSuiteCapabilities(suite));
    }

    @Test
    void separatesDraftAndPublishedBuildDirectories(@TempDir Path root) throws Exception {
        ExamService examService = new ExamService();
        Field examsDir = ExamService.class.getDeclaredField("examsDir");
        examsDir.setAccessible(true);
        examsDir.set(examService, root.toString());
        Field templateDir = ExamService.class.getDeclaredField("templateDir");
        templateDir.setAccessible(true);
        templateDir.set(examService, root.resolve("grader-base").toString());

        Path draft = examService.testcaseBuildDirectory("PE_01", 3, false);
        Path published = examService.testcaseBuildDirectory("PE_01", 3, true);

        assertTrue(draft.startsWith(root.resolve("PE_01").resolve("testcase-drafts")));
        assertTrue(published.startsWith(root.resolve("PE_01").resolve("testcase-versions")));
        assertTrue(!draft.equals(published));
    }

    @Test
    void rejectsUnsafeContractPathAndInvalidDartSymbol() {
        assertThrows(IllegalArgumentException.class, () -> service.normalizeSuite(Map.of(
                "source_contracts", List.of(Map.of("type", "model", "path", "../user.dart", "symbols", "User"))), null));
        assertThrows(IllegalArgumentException.class, () -> service.normalizeSuite(Map.of(
                "source_contracts", List.of(Map.of("type", "model", "path", "lib/user.dart", "symbols", "User-Model"))), null));
    }

    @Test
    void rejectsUnknownSetupStepAndInvalidSemanticKey() {
        assertThrows(IllegalArgumentException.class, () -> service.normalizeSuite(Map.of(
                "setup_steps", List.of(Map.of("type", "run_dart", "key", "action.save"))), null));
        assertThrows(IllegalArgumentException.class, () -> service.normalizeSuite(Map.of(
                "required_keys", List.of("not-a-key")), null));
    }

    @Test
    void keepsLegacySuiteNonStrictWhenNoSuiteWasProvided() {
        Map<String, Object> suite = service.normalizeSuite(null, null);
        assertEquals(false, suite.get("strict_semantic_keys"));
    }

    @Test
    void emitsSuiteAndItemSetupIntoMatrix() {
        Map<String, Object> suite = service.normalizeSuite(Map.of(
                "ready_key", "screen.home.ready"), null);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("instance_id", "EXAM_item_01");
        item.put("runner", "APP_BOOT");
        item.put("skill_code", "UI_SCAFFOLD_APPBAR");
        item.put("testcase_group", "WIDGET");
        item.put("name", "Boot");
        item.put("description", "Boot");
        item.put("expected", "ok");
        item.put("difficulty", "basic");
        item.put("weight", 1);
        item.put("parameters", Map.of());
        item.put("setup_steps", List.of(Map.of("type", "expect_visible", "key", "screen.home")));

        Map<String, Object> matrix = service.toSkillsMatrix(List.of(item), "COMMON_V1", suite);
        Map<?, ?> row = (Map<?, ?>) matrix.get("EXAM_item_01");
        assertEquals("screen.home.ready", ((Map<?, ?>) row.get("suite")).get("ready_key"));
        assertEquals(1, ((List<?>) row.get("setup_steps")).size());
    }
}
