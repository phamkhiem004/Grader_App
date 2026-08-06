package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
