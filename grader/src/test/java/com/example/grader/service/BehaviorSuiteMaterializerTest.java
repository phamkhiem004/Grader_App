package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BehaviorSuiteMaterializerTest {

    @TempDir
    Path tempDir;

    @Test
    void materializesGenericRunnerAndSplitsScenarioWeightByCheckpoint() throws Exception {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        BehaviorSuiteMaterializer materializer = new BehaviorSuiteMaterializer(authoring, artifacts, exams);
        ReflectionTestUtils.setField(materializer, "templateDir", Path.of("..", "grader-base").toString());
        ReflectionTestUtils.setField(materializer, "examsDir", tempDir.toString());

        Map<String, Object> scenario = Map.ofEntries(
                Map.entry("scenario_code", "ADD_USER"),
                Map.entry("name", "Thêm người dùng"),
                Map.entry("description", "Nhập dữ liệu và kiểm UI/SQLite"),
                Map.entry("skill_code", "STORAGE_SQLITE_CRUD"),
                Map.entry("weight", 8.0),
                Map.entry("variables", Map.of("field_uid", Map.of("generator", "stable_id"))),
                Map.entry("initial_state", Map.of("reset_storage", true)),
                Map.entry("steps", List.of(Map.of(
                        "id", "step_1", "action", "enter_text",
                        "target", Map.of("semanticId", "field.uid"), "value", "${field_uid}"))),
                Map.entry("viewports", List.of(
                        Map.of("name", "phone", "width", 390, "height", 844),
                        Map.of("name", "desktop", "width", 1280, "height", 800))),
                Map.entry("oracle", Map.of("seed", "seed-01", "input", Map.of())),
                Map.entry("checkpoints", List.of(
                        Map.of("id", "UI_VISIBLE", "kind", "checkpoint", "scope", "ui",
                                "weight", 3.0, "expect", Map.of("visible_texts", List.of("${field_uid}"))),
                        Map.of("id", "DB_ROW", "kind", "database_observation", "weight", 1.0,
                                "table", "users", "operation", "INSERT", "row", Map.of("uid", "${field_uid}")))));
        Map<String, Object> plan = Map.of(
                "schema_version", "1.0",
                "suite", Map.of(
                        "id", "suite-1", "suite_code", "RAR_USER", "exam_id", "RAR_USER_EXAM",
                        "name", "RAR User", "description", "Golden behavior", "revision", 1),
                "public_contract", Map.of("allow_coordinate_fallback", false),
                "database_contract", Map.of("enabled", true, "database_name", "users.db"),
                "runtime_config", Map.of("default_timeout_ms", 5000),
                "scenarios", List.of(scenario));
        when(authoring.executionPlan("suite-1")).thenReturn(plan);
        for (BehaviorArtifactType type : List.of(
                BehaviorArtifactType.STUDENT_DATABASE,
                BehaviorArtifactType.HIDDEN_DATABASE,
                BehaviorArtifactType.OUTPUT_DATABASE)) {
            Path source = tempDir.resolve(type.name().toLowerCase() + ".db");
            Files.writeString(source, "fixture-" + type);
            BehaviorArtifact artifact = new BehaviorArtifact();
            artifact.setArtifactType(type);
            artifact.setStoragePath(source.toString());
            when(artifacts.active("suite-1", type)).thenReturn(artifact);
        }
        when(exams.findByExamId("RAR_USER_EXAM")).thenReturn(Optional.empty());
        when(exams.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = materializer.materialize("suite-1");

        assertEquals(true, result.get("ready_for_grading"));
        Path output = tempDir.resolve("RAR_USER_EXAM").resolve("testcase");
        for (String file : List.of("exam_test.dart", "grader.dart", "behavior_plan.json",
                "skills_matrix.json", "contract.json", "suite_manifest.json")) {
            assertTrue(Files.exists(output.resolve(file)), file + " phải được sinh");
        }
        JsonNode matrix = new ObjectMapper().readTree(output.resolve("skills_matrix.json").toFile());
        assertEquals(3, matrix.size());
        assertEquals(3.0, matrix.get("RAR_USER_ADD_USER_UI_VISIBLE_PHONE").get("weight").asDouble(), 0.0001);
        assertEquals(3.0, matrix.get("RAR_USER_ADD_USER_UI_VISIBLE_DESKTOP").get("weight").asDouble(), 0.0001);
        assertEquals(2.0, matrix.get("RAR_USER_ADD_USER_DB_ROW").get("weight").asDouble(), 0.0001);
        verify(exams).save(argThat(exam -> exam.getTestcasePath().endsWith("testcase")));
    }

    @Test
    void captureBundleDoesNotRequireAnOutputDatabaseAndKeepsScenarioIdentity() throws Exception {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        BehaviorSuiteMaterializer materializer = new BehaviorSuiteMaterializer(authoring, artifacts, exams);
        ReflectionTestUtils.setField(materializer, "templateDir", Path.of("..", "grader-base").toString());

        Map<String, Object> scenario = Map.ofEntries(
                Map.entry("id", "scenario-42"),
                Map.entry("scenario_code", "ADD_USER"),
                Map.entry("name", "Thêm người dùng"),
                Map.entry("skill_code", "STORAGE_SQLITE_CRUD"),
                Map.entry("weight", 1.0),
                Map.entry("variables", Map.of()),
                Map.entry("initial_state", Map.of("reset_storage", true)),
                Map.entry("steps", List.of(Map.of(
                        "id", "step_1", "action", "tap",
                        "target", Map.of("text", "Add")))),
                Map.entry("viewports", List.of(Map.of(
                        "name", "phone", "width", 390, "height", 844))),
                Map.entry("oracle", Map.of("seed", "seed-42", "input", Map.of())),
                Map.entry("checkpoints", List.of(Map.of(
                        "id", "NO_EXCEPTION", "kind", "checkpoint", "scope", "ui",
                        "weight", 1.0, "expect", Map.of("no_exception", true)))));
        Map<String, Object> plan = Map.of(
                "schema_version", "1.0",
                "suite", Map.of("id", "suite-1", "suite_code", "RAR_CAPTURE", "revision", 1),
                "public_contract", Map.of(),
                "database_contract", Map.of("enabled", true, "database_name", "users.db"),
                "runtime_config", Map.of("default_timeout_ms", 5000),
                "scenarios", List.of(scenario));
        when(authoring.previewExecutionPlan("suite-1")).thenReturn(plan);

        for (BehaviorArtifactType type : List.of(
                BehaviorArtifactType.STUDENT_DATABASE,
                BehaviorArtifactType.HIDDEN_DATABASE)) {
            Path source = tempDir.resolve(type.name().toLowerCase() + ".db");
            Files.writeString(source, "fixture-" + type);
            BehaviorArtifact artifact = new BehaviorArtifact();
            artifact.setArtifactType(type);
            artifact.setStoragePath(source.toString());
            when(artifacts.active("suite-1", type)).thenReturn(artifact);
        }
        when(artifacts.activeOptional("suite-1", BehaviorArtifactType.OUTPUT_DATABASE))
                .thenReturn(Optional.empty());
        when(artifacts.activeManifest("suite-1")).thenReturn(Map.of());

        Path output = materializer.createCaptureBundle("suite-1", tempDir.resolve("capture"));

        assertArrayEquals(
                Files.readAllBytes(output.resolve("fixtures/hidden.db")),
                Files.readAllBytes(output.resolve("fixtures/expected-output.db")),
                "Capture bundle chỉ dùng Hidden DB làm placeholder trước khi có Output DB thật");
        JsonNode behaviorPlan = new ObjectMapper().readTree(output.resolve("behavior_plan.json").toFile());
        assertEquals("scenario-42", behaviorPlan.path("cases").get(0).path("scenario_id").asText());
    }
}
