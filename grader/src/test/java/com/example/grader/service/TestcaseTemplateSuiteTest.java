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
        Map<?, ?> dynamicContract = (Map<?, ?>) suite.get("template_contract");
        assertEquals(4, dynamicContract.get("version"));
        List<?> contractSections = (List<?>) dynamicContract.get("sections");
        List<?> contractFields = (List<?>) ((Map<?, ?>) contractSections.get(0)).get("fields");
        assertTrue(contractFields.stream().anyMatch(field -> field instanceof Map<?, ?> value
                && "model.class".equals(value.get("key")) && "Person".equals(value.get("value"))));
        assertEquals("tap", ((List<?>) suite.get("setup_steps")).get(0) instanceof Map<?, ?> step
                ? step.get("type") : null);
    }

    @Test
    void normalizesReusableStarterKeyCatalogAndRejectsAmbiguousKeys() {
        Map<String, Object> suite = service.normalizeSuite(Map.of(
                "key_contract", Map.of(
                        "source_path", "lib/grading/app_keys.dart",
                        "class_name", "AppKeys",
                        "keys", List.of(
                                Map.of("symbol", "uidField", "value", "person.form.uid",
                                        "group", "Form", "description", "Ô nhập UID"),
                                Map.of("symbol", "addButton", "value", "person.action.add",
                                        "group", "Action", "description", "Nút Add")))), null);

        Map<?, ?> keyContract = (Map<?, ?>) suite.get("key_contract");
        assertEquals("lib/grading/app_keys.dart", keyContract.get("source_path"));
        assertEquals("AppKeys", keyContract.get("class_name"));
        List<?> keys = (List<?>) keyContract.get("keys");
        assertEquals(2, keys.size());
        assertEquals("person.form.uid", ((Map<?, ?>) keys.get(0)).get("value"));

        assertThrows(IllegalArgumentException.class, () -> service.normalizeSuite(Map.of(
                "key_contract", Map.of("source_path", "../app_keys.dart",
                        "class_name", "AppKeys", "keys", List.of())), null));
        assertThrows(IllegalArgumentException.class, () -> service.normalizeSuite(Map.of(
                "key_contract", Map.of("source_path", "lib/grading/app_keys.dart",
                        "class_name", "AppKeys", "keys", List.of(
                                Map.of("symbol", "first", "value", "person.form.uid"),
                                Map.of("symbol", "second", "value", "person.form.uid")))), null));
    }

    @Test
    void acceptsDynamicContractSectionsAndRejectsDuplicateKeys() {
        Map<String, Object> contract = Map.of(
                "version", 2,
                "sections", List.of(
                        Map.of("id", "api", "name", "API contract", "fields", List.of(
                                Map.of("id", "endpoint", "key", "api.endpoint", "label", "Endpoint",
                                        "kind", "text", "value", "/products"))),
                        Map.of("id", "ui", "name", "UI contract", "fields", List.of(
                                Map.of("id", "buttons", "key", "ui.buttonLabels", "label", "Buttons",
                                        "kind", "csv", "value", "Load,Retry")))));
        Map<String, Object> normalized = service.normalizeSuite(
                Map.of("template_contract", contract), null);
        Map<?, ?> result = (Map<?, ?>) normalized.get("template_contract");
        assertEquals(2, ((List<?>) result.get("sections")).size());

        Map<String, Object> duplicate = Map.of("version", 2, "sections", List.of(
                Map.of("id", "one", "name", "One", "fields", List.of(
                        Map.of("id", "a", "key", "same.key", "label", "A", "kind", "text", "value", "1"))),
                Map.of("id", "two", "name", "Two", "fields", List.of(
                        Map.of("id", "b", "key", "same.key", "label", "B", "kind", "text", "value", "2")))));
        assertThrows(IllegalArgumentException.class,
                () -> service.normalizeSuite(Map.of("template_contract", duplicate), null));
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
        assertTrue(grader.contains("COMMON_V1-2.9.0"),
                "Không được hạ version engine khi xử lý merge conflict");
        assertTrue(examTest.contains("GRADER_CASE_MODE"));
        assertTrue(examTest.contains("_GRADER_PREFLIGHT"));
        assertTrue(examTest.contains("_revealLazyItem"));
        assertTrue(grader.contains("Blocked bởi lỗi khởi động chung"));
        assertTrue(grader.contains("event['result'] == 'success' && !skipped"));
        assertTrue(grader.contains("process.exitCode.timeout(timeout)"));
    }

    @Test
    void commonBlueprintsRequireObservableTransitionsAndDescribeTheirRealScope() throws Exception {
        String engine = new ClassPathResource("common-testcase-engine/exam_test.dart")
                .getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> templates = new ObjectMapper().readValue(
                new ClassPathResource("common-testcase-templates.json").getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {});

        assertEquals(29, templates.size());
        assertEquals(29, templates.stream().map(row -> row.get("template_id")).distinct().count());
        Map<String, Map<String, Object>> byId = templates.stream().collect(java.util.stream.Collectors.toMap(
                row -> String.valueOf(row.get("template_id")), row -> row));
        assertTrue(((Map<?, ?>) byId.get("COMMON_BUTTON_ACTION").get("parameters_schema"))
                .containsKey("requireNewResult"));
        assertTrue(((Map<?, ?>) byId.get("COMMON_NAVIGATION").get("parameters_schema"))
                .containsKey("requireNewDestination"));
        assertTrue(((Map<?, ?>) byId.get("COMMON_DIALOG_FLOW").get("parameters_schema"))
                .containsKey("requireNewDialog"));
        assertTrue(((Map<?, ?>) byId.get("COMMON_FORM_PREFILL").get("parameters_schema"))
                .containsKey("requirePrefillTransition"));
        assertTrue(byId.containsKey("COMMON_FORM_FORMAT_VALIDATION"));
        assertTrue(byId.containsKey("COMMON_FORM_BOUNDARY_VALIDATION"));
        assertTrue(byId.containsKey("COMMON_FORM_CROSS_FIELD_VALIDATION"));
        assertTrue(String.valueOf(byId.get("COMMON_LIST_ITEM_COUNT").get("name"))
                .contains("itemKey"));
        assertTrue(engine.contains("int _visibleKeyCount"));
        assertTrue(engine.contains("void _expectNewSemanticKey"));
        assertTrue(engine.contains("không nằm trong list"));
        assertTrue(engine.contains("jsonDecode(text)"));
    }

    @Test
    void shipsReusableTemplateContractBlueprintsWithoutKeysOrAdapter() throws Exception {
        String commonEngine = new ClassPathResource("template-contract-engine/common_testcase_engine.dart")
                .getContentAsString(StandardCharsets.UTF_8);
        String grader = new ClassPathResource("template-contract-engine/grader.dart")
                .getContentAsString(StandardCharsets.UTF_8);
        List<Map<String, Object>> templates = new ObjectMapper().readValue(
                new ClassPathResource("template-contract-testcase-templates.json").getInputStream(),
                new TypeReference<List<Map<String, Object>>>() {});

        assertEquals(24, templates.size());
        assertEquals(24, templates.stream().map(row -> row.get("template_id")).distinct().count());
        assertTrue(templates.stream().allMatch(row -> "TEMPLATE_CONTRACT_V1".equals(row.get("engine_type"))));
        assertTrue(templates.stream().noneMatch(row -> Boolean.TRUE.equals(row.get("fixed_contract"))));
        assertTrue(templates.stream().allMatch(row -> row.get("contract_bindings") instanceof Map<?, ?>));
        Map<String, Map<String, Object>> byId = templates.stream().collect(java.util.stream.Collectors.toMap(
                row -> String.valueOf(row.get("template_id")), row -> row));
        assertTrue(((Map<?, ?>) byId.get("TPL_APP_BOOT").get("parameters_schema")).containsKey("readyText"));
        assertTrue(((Map<?, ?>) byId.get("TPL_SOURCE_SYMBOLS").get("parameters_schema")).containsKey("symbolTypes"));
        assertTrue(((Map<?, ?>) byId.get("TPL_SQLITE_SCHEMA").get("parameters_schema")).containsKey("schemaMethod"));
        assertTrue(((Map<?, ?>) byId.get("TPL_BUTTONS_BY_TEXT").get("parameters_schema")).containsKey("scopeType"));
        assertTrue(((Map<?, ?>) byId.get("TPL_FORM_ACTION_BY_TEXT").get("parameters_schema")).containsKey("resultScopeType"));
        assertTrue(((Map<?, ?>) byId.get("TPL_FORM_ACTION_BY_TEXT").get("parameters_schema")).containsKey("requireNewResult"));
        assertTrue(((Map<?, ?>) byId.get("TPL_FORM_VALIDATION_BY_LABEL").get("parameters_schema")).containsKey("requireNewErrors"));
        assertTrue(((Map<?, ?>) byId.get("TPL_FORM_VALIDATION_BY_LABEL").get("parameters_schema")).containsKey("errorFieldLabels"));
        assertTrue(((Map<?, ?>) byId.get("TPL_FORM_VALIDATION_BY_LABEL").get("parameters_schema")).containsKey("errorTextMatchMode"));
        assertTrue(byId.containsKey("TPL_FORM_REQUIRED_VALIDATION"));
        assertTrue(byId.containsKey("TPL_FORM_FORMAT_VALIDATION"));
        assertTrue(byId.containsKey("TPL_FORM_BOUNDARY_VALIDATION"));
        assertTrue(byId.containsKey("TPL_FORM_CROSS_FIELD_VALIDATION"));
        assertTrue(byId.containsKey("TPL_FORM_VALID_DATA_ACCEPTED"));
        assertTrue(((Map<?, ?>) byId.get("TPL_LIST_CONTENT_BY_TEXT").get("parameters_schema")).containsKey("minimumOccurrences"));
        assertTrue(((Map<?, ?>) byId.get("TPL_RESPONSIVE_NO_OVERFLOW").get("parameters_schema")).containsKey("portraitExpectedTexts"));
        assertTrue(commonEngine.contains("void runTemplateContractExam()"));
        assertTrue(commonEngine.contains("Future<void> _checkTemplateModelFields"));
        assertTrue(commonEngine.contains("Future<void> _checkTemplateFormAction"));
        assertTrue(commonEngine.contains("labelText, decoration.hintText"));
        assertTrue(commonEngine.contains("Finder? _targetUiScope"));
        assertTrue(commonEngine.contains("String _methodBody"));
        assertTrue(!commonEngine.contains("await _tap(tester, finder.first"),
                "Workflow không được âm thầm tap phần tử đầu tiên khi selector bị trùng");
        assertTrue(!commonEngine.contains("grading_adapter.dart"));
        assertTrue(grader.contains("TEMPLATE_CONTRACT_V1-2.6.0"));
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

        service.validateTemplateContractParameters("TEMPLATE_UI_WORKFLOW", new LinkedHashMap<>(Map.of(
                "stepsJson", "[{\"type\":\"tap\",\"target\":\"Add\"},"
                        + "{\"type\":\"expectVisible\",\"value\":\"Saved\"}]")), "FLOW_OK");
        assertThrows(IllegalArgumentException.class, () -> service.validateTemplateContractParameters(
                "TEMPLATE_UI_WORKFLOW", new LinkedHashMap<>(Map.of(
                        "stepsJson", "[{\"type\":\"runDart\",\"value\":\"danger\"}]")), "FLOW_BAD"));
        assertThrows(IllegalArgumentException.class, () -> service.validateTemplateContractParameters(
                "TEMPLATE_FORM_VALIDATION", new LinkedHashMap<>(Map.of(
                        "fieldLabels", "Name,Email",
                        "invalidValues", "<empty>,invalid",
                        "actionLabel", "Save",
                        "errorTexts", "Required,Invalid",
                        "formIndex", "0")), "FORM_SCOPE_BAD"));
        assertThrows(IllegalArgumentException.class, () -> service.validateTemplateContractParameters(
                "TEMPLATE_FORM_VALIDATION", new LinkedHashMap<>(Map.of(
                        "fieldLabels", "Name,Email",
                        "invalidValues", "<empty>,invalid",
                        "actionLabel", "Save",
                        "errorTexts", "Required,Invalid",
                        "errorFieldLabels", "Email")), "FORM_ERROR_MAPPING_BAD"));
        assertThrows(IllegalArgumentException.class, () -> service.validateTemplateContractParameters(
                "TEMPLATE_BUTTONS", new LinkedHashMap<>(Map.of(
                        "buttonLabels", "Save",
                        "scopeIndex", "2")), "BUTTON_SCOPE_WITHOUT_TYPE"));
        assertThrows(IllegalArgumentException.class, () -> service.validateTemplateContractParameters(
                "TEMPLATE_SOURCE_SYMBOLS", new LinkedHashMap<>(Map.of(
                        "sourcePath", "lib/main.dart",
                        "symbols", "App,main",
                        "symbolTypes", "class")), "SYMBOL_TYPE_COUNT_BAD"));
        assertThrows(IllegalArgumentException.class, () -> service.validateTemplateContractParameters(
                "TEMPLATE_UI_WORKFLOW", new LinkedHashMap<>(Map.of(
                        "stepsJson", "[{\"type\":\"tap\",\"target\":\"Save\",\"occurrence\":0}]")),
                "WORKFLOW_OCCURRENCE_BAD"));
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
        assertTrue(!row.containsKey("suite"),
                "Cấu hình editor và giá trị runtime mặc định không được làm phình skills_matrix");
        assertTrue(!row.containsKey("setup_steps"),
                "Danh sách setup rỗng không cần xuất ra skills_matrix");

        Map<String, Object> second = new LinkedHashMap<>(item);
        second.put("instance_id", "PERSON_FORM_ADD_02");
        Map<String, Object> compact = service.toSkillsMatrix(
                List.of(item, second), "TEMPLATE_CONTRACT_V1", suite);
        assertTrue(!((Map<?, ?>) compact.get("PERSON_FORM_ADD")).containsKey("suite"));
        assertTrue(!((Map<?, ?>) compact.get("PERSON_FORM_ADD_02")).containsKey("suite"),
                "Suite runtime rỗng không được ghi vào bất kỳ testcase nào");
    }

    @Test
    void templateContractMatrixPublishesOnlyNonDefaultRuntimeSuite() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("instance_id", "APP_BOOT_01");
        item.put("runner", "APP_BOOT");
        item.put("skill_code", "UI_SCAFFOLD_APPBAR");
        item.put("layer", "BLACKBOX");
        item.put("testcase_group", "BEHAVIOR");
        item.put("name", "Boot");
        item.put("description", "Boot student app");
        item.put("expected", "No exception");
        item.put("difficulty", "basic");
        item.put("weight", 1.0);
        item.put("enabled", true);
        item.put("parameters", Map.of("rootKey", "screen.home"));
        item.put("setup_steps", List.of());

        Map<String, Object> suite = service.normalizeSuite(Map.of(
                "name", "Editor-only name",
                "context", "editor-only-context",
                "profile", "TEMPLATE_CONTRACT_V1",
                "template_contract", Map.of(
                        "version", 2,
                        "sections", List.of(Map.of(
                                "id", "model",
                                "name", "Model",
                                "fields", List.of(Map.of(
                                        "id", "model_path",
                                        "key", "model.path",
                                        "label", "File model",
                                        "kind", "path",
                                        "value", "lib/models/person.dart"))))),
                "source_contracts", List.of(Map.of(
                        "type", "model",
                        "path", "lib/models/person.dart",
                        "symbols", "Person")),
                "key_contract", Map.of(
                        "source_path", "lib/grading/app_keys.dart",
                        "class_name", "AppKeys",
                        "keys", List.of(Map.of("symbol", "home", "value", "screen.home"))),
                "ready_key", "screen.home",
                "boot_timeout_ms", 4500), null);

        Map<?, ?> runtimeRow = (Map<?, ?>) service.toSkillsMatrix(
                List.of(item), "TEMPLATE_CONTRACT_V1", suite).get("APP_BOOT_01");
        Map<?, ?> runtimeSuite = (Map<?, ?>) runtimeRow.get("suite");
        assertEquals("screen.home", runtimeSuite.get("ready_key"));
        assertEquals(4500, runtimeSuite.get("boot_timeout_ms"));
        assertEquals(1, ((List<?>) runtimeSuite.get("source_contracts")).size());
        assertTrue(!runtimeSuite.containsKey("template_contract"));
        assertTrue(!runtimeSuite.containsKey("key_contract"));
        assertTrue(!runtimeSuite.containsKey("persistence"));
        assertTrue(!runtimeSuite.containsKey("golden"));
        assertTrue(!runtimeSuite.containsKey("profile"));
        assertTrue(!runtimeSuite.containsKey("name"));
    }

    @Test
    void materializesReusableTemplateContractEngine(@TempDir Path root) throws Exception {
        service.materializeEngine(root, "TEMPLATE_CONTRACT_V1", List.of(
                Map.of(
                        "enabled", true,
                        "instance_id", "REPOSITORY_METHODS_01",
                        "runner", "TEMPLATE_REPOSITORY_METHODS",
                        "name", "Repository methods",
                        "weight", 1.0),
                Map.of(
                        "enabled", true,
                        "instance_id", "FORM_VALIDATION_01",
                        "runner", "TEMPLATE_FORM_VALIDATION",
                        "name", "Scoped form validation",
                        "weight", 2.0),
                Map.of("enabled", true, "instance_id", "APP_BOOT_01", "runner", "APP_BOOT",
                        "name", "Ready app", "weight", 1.0),
                Map.of("enabled", true, "instance_id", "BUTTONS_01", "runner", "TEMPLATE_BUTTONS",
                        "name", "Scoped buttons", "weight", 1.0),
                Map.of("enabled", true, "instance_id", "TEXT_01", "runner", "TEMPLATE_TEXT_VISIBLE",
                        "name", "Scoped text", "weight", 1.0),
                Map.of("enabled", true, "instance_id", "WORKFLOW_01", "runner", "TEMPLATE_UI_WORKFLOW",
                        "name", "Scoped workflow", "weight", 1.0),
                Map.of("enabled", true, "instance_id", "RESPONSIVE_01", "runner", "RESPONSIVE_NO_OVERFLOW",
                        "name", "Responsive content", "weight", 1.0),
                Map.of("enabled", true, "instance_id", "SQLITE_01", "runner", "TEMPLATE_SQLITE_SCHEMA",
                        "name", "Scoped schema", "weight", 1.0),
                Map.of("enabled", true, "instance_id", "MAPPING_01", "runner", "TEMPLATE_MODEL_MAPPING",
                        "name", "Scoped mapping", "weight", 1.0)));

        String bundledExam = Files.readString(root.resolve("exam_test.dart"), StandardCharsets.UTF_8);
        assertTrue(bundledExam.contains("void main()"));
        assertTrue(bundledExam.contains("REPOSITORY_METHODS_01"));
        assertTrue(bundledExam.contains("testWidgets(\"REPOSITORY_METHODS_01\""),
                "Mỗi testcase phải xuất hiện trực tiếp dưới dạng testWidgets để giảng viên dễ theo dõi");
        assertTrue(bundledExam.contains("testWidgets(\"FORM_VALIDATION_01\""));
        assertTrue(bundledExam.contains("_checkTemplateRepositoryMethods"));
        assertTrue(bundledExam.contains("Finder? _targetForm"),
                "Runner form phải mang theo cơ chế giới hạn phạm vi Form");
        assertTrue(bundledExam.contains("Finder? _targetUiScope"));
        assertTrue(bundledExam.contains("Future<void> _checkTemplateAppBoot"));
        assertTrue(bundledExam.contains("String _methodBody"));
        assertTrue(bundledExam.contains("void _expectResponsiveTexts"));
        assertTrue(!bundledExam.contains("_checkTemplateFormAction"),
                "Runner không được chọn không được ghép vào exam_test.dart");
        assertTrue(!Files.exists(root.resolve("common_testcase_engine.dart")),
                "Module nội bộ không được xuất hiện trong artifact ba file");
        assertTrue(Files.readString(root.resolve("grader.dart"), StandardCharsets.UTF_8)
                .contains("TEMPLATE_CONTRACT_V1-2.6.0"));
    }

    @Test
    void materializesHybridStarterLogicAndSemanticKeyUiWithoutAdapter(@TempDir Path root) throws Exception {
        service.materializeEngine(root, "STARTER_KEY_HYBRID_V1", List.of(
                Map.of("enabled", true, "instance_id", "MODEL_01", "runner", "TEMPLATE_MODEL_FIELDS",
                        "name", "Model starter contract", "weight", 1.0),
                Map.of("enabled", true, "instance_id", "WIDGET_01", "runner", "WIDGET_VISIBLE",
                        "name", "Widget semantic key", "weight", 1.0),
                Map.of("enabled", true, "instance_id", "FORM_01", "runner", "FORM_SUBMIT",
                        "name", "Submit by key", "weight", 2.0)));

        String examTest = Files.readString(root.resolve("exam_test.dart"), StandardCharsets.UTF_8);
        String grader = Files.readString(root.resolve("grader.dart"), StandardCharsets.UTF_8);

        assertTrue(examTest.contains("testWidgets(\"MODEL_01\""));
        assertTrue(examTest.contains("testWidgets(\"WIDGET_01\""));
        assertTrue(examTest.contains("testWidgets(\"FORM_01\""));
        assertTrue(examTest.contains("Future<void> _checkTemplateModelFields"));
        assertTrue(examTest.contains("Future<void> _checkKeyWidgetVisible"));
        assertTrue(examTest.contains("Future<void> _checkFormSubmit"));
        assertTrue(!examTest.contains("_checkTemplateFormAction"),
                "Hybrid UI phải dùng semantic Key, không ghép runner dò label/text");
        assertTrue(!examTest.contains("grading_adapter.dart"));
        assertTrue(grader.contains("STARTER_KEY_HYBRID_V1-1.0.0"));
        assertTrue(!Files.exists(root.resolve("common_testcase_engine.dart")));
    }

    @Test
    void hybridEngineRejectsAdapterPathAndAcceptsPublicStarterCalls(@TempDir Path root) throws Exception {
        assertEquals("STARTER_KEY_HYBRID_V1", service.engineType(List.of(
                Map.of("engine_type", "STARTER_KEY_HYBRID_V1"),
                Map.of("engine_type", "STARTER_KEY_HYBRID_V1"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.materializeEngine(root, "STARTER_KEY_HYBRID_V1", List.of(
                        Map.of("enabled", true, "instance_id", "BAD_ADAPTER", "runner", "DIRECT_FUNCTION",
                                "parameters", Map.of(
                                        "functionPath", "lib/grading/grading_adapter.dart",
                                        "functionName", "runGradingCase",
                                        "argumentsJson", "[]")))));

        service.materializeEngine(root, "STARTER_KEY_HYBRID_V1", List.of(
                Map.of("enabled", true, "instance_id", "PUBLIC_LOGIC", "runner", "DIRECT_FUNCTION",
                        "name", "Public starter function", "weight", 1.0,
                        "parameters", Map.of(
                                "functionPath", "lib/domain/validator.dart",
                                "functionName", "validate",
                                "argumentsJson", "[\"ok\"]"))));
        String source = Files.readString(root.resolve("exam_test.dart"), StandardCharsets.UTF_8);
        assertTrue(source.contains("import '../lib/domain/validator.dart' as direct_0;"));
        assertTrue(source.contains("return direct_0.validate(arguments[0]);"));
        assertTrue(source.contains("await _checkDirectFunction(tester, parameters);"));
        assertTrue(source.contains("final actual = await tester.runAsync<dynamic>(() async"),
                "Direct functions must start async/SQLite work inside WidgetTester's real async zone");
        assertTrue(!source.contains("grading_adapter.dart"));
    }

    @Test
    void materializesReusableStarterCallSequence(@TempDir Path root) throws Exception {
        String steps = """
                [
                  {"functionName":"resetStore","arguments":[],"expectedType":"null","expectedValue":null},
                  {"functionName":"addPerson","arguments":["S01","An"],"expectedType":"int","expectedValue":1},
                  {"functionName":"readCount","arguments":[],"expectedType":"int","expectedValue":1}
                ]
                """;
        service.materializeEngine(root, "STARTER_KEY_HYBRID_V1", List.of(
                Map.of("enabled", true, "instance_id", "SQL_SEQUENCE", "runner", "STARTER_CALL_SEQUENCE",
                        "name", "SQLite sequence", "weight", 3.0,
                        "parameters", Map.of(
                                "sourcePath", "lib/database/person_store.dart",
                                "stepsJson", steps))));

        String source = Files.readString(root.resolve("exam_test.dart"), StandardCharsets.UTF_8);
        assertTrue(source.contains("Future<void> _checkStarterCallSequence"));
        assertTrue(source.contains("await _checkStarterCallSequence(tester, parameters);"));
        assertTrue(source.contains("final actual = await tester.runAsync<dynamic>(() async"),
                "SQLite sequence steps must be invoked inside runAsync, not merely awaited there");
        assertTrue(source.contains("import '../lib/database/person_store.dart' as direct_0;"));
        assertEquals(1, count(source, "case 'lib/database/person_store.dart::resetStore':"));
        assertEquals(1, count(source, "case 'lib/database/person_store.dart::addPerson':"));
        assertEquals(1, count(source, "case 'lib/database/person_store.dart::readCount':"));
        assertTrue(Files.readString(root.resolve("grader.dart"), StandardCharsets.UTF_8)
                .contains("runner == 'STARTER_CALL_SEQUENCE'"));
    }

    @Test
    void hybridLibraryContainsOnlyStarterLogicAndKeyUiBlueprints(@TempDir Path root) throws Exception {
        SyllabusService syllabus = mock(SyllabusService.class);
        when(syllabus.skills()).thenReturn(List.of());
        setField(service, "syllabusService", syllabus);
        setField(service, "examsDirectory", root.toString());
        service.loadTemplates();

        List<Map<String, Object>> hybrid = service.listTemplates(null, null, null).stream()
                .filter(row -> "STARTER_KEY_HYBRID_V1".equals(row.get("engine_type")))
                .toList();

        assertEquals(89, hybrid.size());
        assertEquals(52, hybrid.stream()
                .filter(row -> String.valueOf(row.get("template_id")).startsWith("HYBRID_REUSE_"))
                .count());
        assertTrue(hybrid.stream().anyMatch(row -> "DIRECT_FUNCTION".equals(row.get("runner"))));
        assertTrue(hybrid.stream().anyMatch(row -> "STARTER_CALL_SEQUENCE".equals(row.get("runner"))));
        assertTrue(hybrid.stream().noneMatch(row -> String.valueOf(row.get("template_id"))
                .contains("GRADING_ADAPTER")));
        assertTrue(hybrid.stream().noneMatch(row -> "TEMPLATE_FORM_ACTION".equals(row.get("runner"))));
        assertTrue(hybrid.stream().anyMatch(row -> "TEMPLATE_SQLITE_SCHEMA".equals(row.get("runner"))
                && "STARTER_CONTRACT".equals(row.get("hybrid_target"))));
        assertTrue(hybrid.stream().anyMatch(row -> "FORM_SUBMIT".equals(row.get("runner"))
                && "SEMANTIC_KEY".equals(row.get("hybrid_target"))));
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
