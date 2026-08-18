package com.example.grader.service;

import com.example.grader.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BehaviorAuthoringServiceTest {

    @Autowired BehaviorAuthoringService service;
    @Autowired OracleSnapshotRepository oracleRepository;
    @Autowired BehaviorScenarioRepository scenarioRepository;
    @Autowired GoldenRecordingRepository recordingRepository;
    @Autowired BehaviorSuiteRepository suiteRepository;
    @Autowired GoldenAppRepository goldenAppRepository;

    @AfterEach
    void cleanup() {
        oracleRepository.deleteAll();
        scenarioRepository.deleteAll();
        recordingRepository.deleteAll();
        suiteRepository.deleteAll();
        goldenAppRepository.deleteAll();
    }

    @Test
    void recordsAbstractsAndPublishesWithoutPerExamDartCode() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "User Manager đáp án",
                "runtime_url", "http://localhost:9010/golden",
                "platform", "WEB",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "USER_CRUD_RAR",
                "name", "User CRUD Record Replay",
                "golden_app_id", golden.get("id"),
                "database_contract", Map.of(
                        "enabled", true,
                        "driver", "sqlite",
                        "tables", List.of(Map.of("name", "users", "primary_key", "uid")))));

        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Thêm người dùng",
                "viewport", Map.of("id", "mobile", "width", 390, "height", 844)));
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action",
                "action", "enter_text",
                "target", Map.of("semanticId", "field.uid", "role", "textbox"),
                "value", "SV01"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action",
                "action", "tap",
                "target", Map.of("semanticId", "action.add", "role", "button", "text", "Add")));
        service.appendEvent(recordingId, Map.of(
                "kind", "database_observation",
                "checkpoint", true,
                "table", "users",
                "operation", "INSERT",
                "row", Map.of("uid", "SV01")));
        service.stopRecording(recordingId, Map.of(
                "final_observation", Map.of("visible_texts", List.of("SV01"))));

        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "ADD_USER",
                "weight", 3.0));
        assertEquals("ADD_USER", scenario.get("scenario_code"));
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) scenario.get("variables");
        assertTrue(variables.containsKey("field_uid"));
        assertFalse(((List<?>) scenario.get("checkpoints")).isEmpty());

        // Production gọi bước này sau khi Docker replay Golden trên Hidden DB và
        // capture Output DB. Unit test không cần Docker nên hoàn thiện oracle bằng
        // một diff rỗng; checkpoint DB đã được record trực tiếp ở phía trên.
        service.applyDerivedDatabaseCheckpoints(
                String.valueOf(scenario.get("id")),
                List.of(),
                Map.of("field_uid", "SV01"),
                "a".repeat(64));

        Map<String, Object> published = service.publish(String.valueOf(suite.get("id")));
        assertEquals("PUBLISHED", published.get("status"));
        assertEquals(true, published.get("ready_for_replay"));

        Map<String, Object> plan = service.executionPlan(String.valueOf(suite.get("id")));
        assertEquals("1.0", plan.get("schema_version"));
        assertEquals(1, ((List<?>) plan.get("scenarios")).size());
    }

    @Test
    void refusesRawCoordinateActionsWithoutSemanticTarget() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "NO_COORDINATE_MACRO",
                "name", "Không macro toạ độ",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.appendEvent(String.valueOf(recording.get("id")), Map.of(
                        "kind", "action", "action", "tap", "x", 20, "y", 40)));
        assertTrue(error.getMessage().contains("target ngữ nghĩa"));
    }

    @Test
    void deletesRecordedEventAndResequencesRemainingTrace() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "DELETE_RECORDED_EVENT",
                "name", "Delete recorded event",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of());
        String recordingId = String.valueOf(recording.get("id"));

        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap",
                "target", Map.of("text", "Add")));
        service.appendEvent(recordingId, Map.of(
                "kind", "checkpoint", "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of("Saved"), "no_exception", true)));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap",
                "target", Map.of("text", "Close")));

        Map<String, Object> deleted = service.deleteEvent(recordingId, 2);
        assertEquals(2, deleted.get("event_count"));

        Map<String, Object> refreshed = service.getRecording(recordingId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trace = (List<Map<String, Object>>) refreshed.get("raw_trace");
        assertEquals(2, trace.size());
        assertEquals(1, trace.get(0).get("sequence"));
        assertEquals("Add", ((Map<?, ?>) trace.get(0).get("target")).get("text"));
        assertEquals(2, trace.get(1).get("sequence"));
        assertEquals("Close", ((Map<?, ?>) trace.get(1).get("target")).get("text"));

        service.stopRecording(recordingId, Map.of());
        assertThrows(IllegalStateException.class, () -> service.deleteEvent(recordingId, 1));
    }

    @Test
    void stopAndAbstractAreRetrySafeAndReplayChangesInvalidateOracle() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "RETRY_SAFE_RAR",
                "name", "Retry safe",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of());
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap",
                "target", Map.of("text", "Add")));

        Map<String, Object> firstStop = service.stopRecording(recordingId, Map.of());
        Map<String, Object> secondStop = service.stopRecording(recordingId, Map.of());
        assertEquals("STOPPED", firstStop.get("status"));
        assertEquals("STOPPED", secondStop.get("status"));

        Map<String, Object> firstAbstract = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "ADD_USER"));
        Map<String, Object> secondAbstract = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "IGNORED_ON_RETRY"));
        assertEquals(firstAbstract.get("id"), secondAbstract.get("id"));
        assertEquals("PENDING", ((Map<?, ?>) firstAbstract.get("oracle")).get("status"));

        String scenarioId = String.valueOf(firstAbstract.get("id"));
        service.applyDerivedDatabaseCheckpoints(
                scenarioId, List.of(), Map.of(), "b".repeat(64));
        assertEquals("READY", oracleRepository
                .findFirstByScenarioIdOrderByCreatedAtDesc(scenarioId).orElseThrow().getStatus().name());

        service.updateScenario(scenarioId, Map.of(
                "steps", List.of(Map.of(
                        "id", "step_1", "action", "tap", "target", Map.of("text", "Save")))));
        assertEquals("STALE", oracleRepository
                .findFirstByScenarioIdOrderByCreatedAtDesc(scenarioId).orElseThrow().getStatus().name());
    }
}
