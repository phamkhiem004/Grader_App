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
import com.example.grader.entity.Skill;

import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                "template_contract", Map.of(
                        "modelPath", "lib/models/person.dart",
                        "modelClass", "Person"),
                "setup_steps", List.of(Map.of("type", "tap", "key", "action.add"))), null);

        assertEquals("Todo CRUD", suite.get("name"));
        assertEquals(true, suite.get("strict_semantic_keys"));
        assertEquals(List.of("screen.home", "list.items"), suite.get("required_keys"));
        assertEquals("Person", ((Map<?, ?>) suite.get("template_contract")).get("modelClass"));
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
    void shipsReusableTemplateContractBlueprintsWithoutKeysOrAdapter() throws Exception {
        String examTest = new ClassPathResource("template-contract-engine/exam_test.dart")
                .getContentAsString(StandardCharsets.UTF_8);
        String grader = new ClassPathResource("template-contract-engine/grader.dart")
                .getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> templates = new ObjectMapper().readValue(
                new ClassPathResource("template-contract-testcase-templates.json").getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {});

        assertEquals(11, templates.size());
        assertEquals(11, templates.stream().map(row -> row.get("template_id")).distinct().count());
        assertTrue(templates.stream().allMatch(row -> "TEMPLATE_CONTRACT_V1".equals(row.get("engine_type"))));
        assertTrue(templates.stream().noneMatch(row -> Boolean.TRUE.equals(row.get("fixed_contract"))));
        assertTrue(examTest.contains("Future<void> _checkTemplateModelFields"));
        assertTrue(examTest.contains("Future<void> _checkTemplateFormAction"));
        assertTrue(examTest.contains("labelText, decoration.hintText"));
        assertTrue(!examTest.contains("grading_adapter.dart"));
        assertTrue(grader.contains("TEMPLATE_CONTRACT_V1-1.0.1"));
        assertTrue(grader.contains("await runCases(readOnly, 1)"));
        assertTrue(grader.contains("'TEMPLATE_FORM_ACTION'"));
        assertTrue(grader.contains("event['result'] == 'success' && !skipped"));
    }

    @Test
    void validatesPerExamTemplateContractParameters() {
        Map<String, Object> model = new LinkedHashMap<>(Map.of(
                "sourcePath", "lib/models/person.dart",
                "className", "Person",
                "fields", "uid:String,firstName:String,lastName:String"));
        service.validateTemplateContractParameters("TEMPLATE_MODEL_FIELDS", model, "MODEL_01");

        assertThrows(IllegalArgumentException.class, () -> service.validateTemplateContractParameters(
                "TEMPLATE_MODEL_FIELDS", new LinkedHashMap<>(Map.of(
                        "sourcePath", "../person.dart",
                        "className", "Person",
                        "fields", "uid:String")), "MODEL_BAD"));
        assertThrows(IllegalArgumentException.class, () -> service.validateTemplateContractParameters(
                "TEMPLATE_FORM_ACTION", new LinkedHashMap<>(Map.of(
                        "fieldLabels", "uid,first name,last name",
                        "inputValues", "SV01,An",
                        "actionLabel", "Add",
                        "expectedTexts", "SV01")), "FORM_BAD"));
    }

    @Test
    void templateContractMatrixKeepsEachExamParameters() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("instance_id", "PERSON_FORM_ADD");
        item.put("runner", "TEMPLATE_FORM_ACTION");
        item.put("skill_code", "STATE_SETSTATE_STATEFUL");
        item.put("layer", "BLACKBOX");
        item.put("testcase_group", "BEHAVIOR");
        item.put("name", "Add person");
        item.put("description", "Add by starter labels");
        item.put("expected", "Person appears");
        item.put("difficulty", "intermediate");
        item.put("weight", 2.5);
        item.put("enabled", true);
        item.put("parameters", Map.of(
                "fieldLabels", "uid,first name,last name",
                "inputValues", "SV01,An,Nguyen",
                "actionLabel", "Add",
                "expectedTexts", "SV01,An,Nguyen"));
        item.put("setup_steps", List.of());
        Map<String, Object> suite = service.normalizeSuite(Map.of(
                "profile", "TEMPLATE_CONTRACT_V1",
                "strict_semantic_keys", false), null);

        Map<?, ?> row = (Map<?, ?>) service.toSkillsMatrix(
                List.of(item), "TEMPLATE_CONTRACT_V1", suite).get("PERSON_FORM_ADD");
        assertEquals("TEMPLATE_FORM_ACTION", row.get("runner"));
        assertEquals("uid,first name,last name", ((Map<?, ?>) row.get("parameters")).get("fieldLabels"));
        assertEquals("TEMPLATE_CONTRACT_V1", ((Map<?, ?>) row.get("suite")).get("profile"));
    }

    @Test
    void materializesReusableTemplateContractEngine(@TempDir Path root) throws Exception {
        service.materializeEngine(root, "TEMPLATE_CONTRACT_V1");

        assertTrue(Files.readString(root.resolve("exam_test.dart"), StandardCharsets.UTF_8)
                .contains("_checkTemplateRepositoryMethods"));
        assertTrue(Files.readString(root.resolve("grader.dart"), StandardCharsets.UTF_8)
                .contains("TEMPLATE_CONTRACT_V1-1.0.1"));
    }

    @Test
    void createsAndReloadsCustomTemplateContractBlueprint(@TempDir Path root) throws Exception {
        SyllabusService syllabus = mock(SyllabusService.class);
        Skill skill = new Skill();
        skill.setCode("DART_CLASSES_OOP");
        skill.setName("Dart classes");
        skill.setCategoryCode("DART");
        when(syllabus.skills()).thenReturn(List.of(skill));
        when(syllabus.categories()).thenReturn(List.of());
        setField(service, "syllabusService", syllabus);
        setField(service, "examsDirectory", root.toString());
        service.loadTemplates();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template_id", "CUSTOM_PERSON_FIELDS");
        body.put("engine_type", "TEMPLATE_CONTRACT_V1");
        body.put("runner", "TEMPLATE_MODEL_FIELDS");
        body.put("skill_code", "DART_CLASSES_OOP");
        body.put("layer", "MODEL");
        body.put("testcase_group", "LOGIC");
        body.put("name", "Person fields");
        body.put("description", "Reusable model field check");
        body.put("difficulty", "basic");
        body.put("weight_default", 1);
        body.put("parameters_schema", Map.of(
                "sourcePath", "lib/models/person.dart",
                "className", "Person",
                "fields", "uid:String,firstName:String,lastName:String"));
        body.put("expected_template", "Person has configured fields");
        Map<String, Object> created = service.createTemplate(body, "teacher@fpt.edu.vn");

        assertEquals("TEMPLATE_CONTRACT_V1", created.get("engine_type"));
        TestcaseTemplateService reloaded = new TestcaseTemplateService();
        setField(reloaded, "syllabusService", syllabus);
        setField(reloaded, "examsDirectory", root.toString());
        reloaded.loadTemplates();
        assertTrue(reloaded.listTemplates(null, null, null).stream().anyMatch(
                row -> "CUSTOM_PERSON_FIELDS".equals(row.get("template_id"))
                        && "TEMPLATE_CONTRACT_V1".equals(row.get("engine_type"))));
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
