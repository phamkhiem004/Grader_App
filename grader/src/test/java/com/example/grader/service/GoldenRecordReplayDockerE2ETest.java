package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.repository.BehaviorArtifactRepository;
import com.example.grader.repository.BehaviorScenarioRepository;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.example.grader.repository.ExamRepository;
import com.example.grader.repository.GoldenAppRepository;
import com.example.grader.repository.GoldenRecordingRepository;
import com.example.grader.repository.GoldenValidationRunRepository;
import com.example.grader.repository.OracleSnapshotRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test thật từ Record -> Hidden DB replay -> Output DB -> preflight -> publish.
 * Chỉ bật thủ công vì cần Docker và image grading-base:latest.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@EnabledIfSystemProperty(named = "golden.docker.e2e", matches = "true")
class GoldenRecordReplayDockerE2ETest {

    @TempDir Path temp;

    @Autowired BehaviorAuthoringService authoring;
    @Autowired BehaviorArtifactService artifacts;
    @Autowired BehaviorSuiteMaterializer materializer;
    @Autowired GoldenOracleCaptureService capture;
    @Autowired GoldenValidationService validation;

    @Autowired BehaviorArtifactRepository artifactRepository;
    @Autowired GoldenValidationRunRepository validationRepository;
    @Autowired OracleSnapshotRepository oracleRepository;
    @Autowired BehaviorScenarioRepository scenarioRepository;
    @Autowired GoldenRecordingRepository recordingRepository;
    @Autowired BehaviorSuiteRepository suiteRepository;
    @Autowired GoldenAppRepository goldenAppRepository;
    @Autowired ExamRepository examRepository;

    @AfterEach
    void cleanup() {
        validationRepository.deleteAll();
        artifactRepository.deleteAll();
        oracleRepository.deleteAll();
        scenarioRepository.deleteAll();
        recordingRepository.deleteAll();
        suiteRepository.deleteAll();
        goldenAppRepository.deleteAll();
        examRepository.deleteAll();
    }

    @Test
    void recordsCapturesValidatesAndPublishesARealDockerBundle() throws Exception {
        Path artifactRoot = temp.resolve("artifacts");
        Path examRoot = temp.resolve("exams");
        ReflectionTestUtils.setField(artifacts, "artifactsDir", artifactRoot.toString());
        ReflectionTestUtils.setField(materializer, "examsDir", examRoot.toString());
        ReflectionTestUtils.setField(materializer, "templateDir", Path.of("..", "grader-base").toString());

        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Map<String, Object> goldenApp = authoring.registerGoldenApp(Map.of(
                "name", "Golden E2E " + suffix,
                "platform", "WEB"));
        Map<String, Object> suite = authoring.createSuite(Map.of(
                "suite_code", "GOLDEN_E2E_" + suffix,
                "exam_id", "GOLDEN_E2E_EXAM_" + suffix,
                "golden_app_id", goldenApp.get("id"),
                "name", "Golden Docker E2E",
                "database_contract", Map.of(
                        "enabled", true,
                        "driver", "sqlite",
                        "database_name", "grader_e2e.db",
                        "ignore_columns", List.of())));
        String suiteId = String.valueOf(suite.get("id"));

        Path studentDb = sqlite("student.db", false);
        Path hiddenDb = sqlite("hidden.db", true);
        artifacts.upload(suiteId, BehaviorArtifactType.STUDENT_DATABASE, multipart(studentDb), "{}");
        artifacts.upload(suiteId, BehaviorArtifactType.HIDDEN_DATABASE, multipart(hiddenDb), "{}");
        Map<String, Object> goldenArtifact = artifacts.upload(
                suiteId,
                BehaviorArtifactType.GOLDEN_SOLUTION,
                new MockMultipartFile("file", "golden.zip", "application/zip", goldenZip()),
                "{}");
        authoring.markGoldenSolutionReady(suiteId, goldenArtifact);
        artifacts.writeGenerated(
                suiteId,
                BehaviorArtifactType.GRADING_ENVIRONMENT,
                "grading-environment.json",
                "{\"automation_driver\":\"flutter_test\",\"browser\":\"flutter_tester\"}".getBytes(StandardCharsets.UTF_8),
                Map.of("generated_from", "docker_e2e"));

        Map<String, Object> recording = authoring.startRecording(suiteId, Map.of(
                "name", "Add user",
                "seed", "golden-e2e-seed",
                "viewport", Map.of("name", "phone", "width", 390, "height", 844),
                "initial_state", Map.of("reset_storage", true)));
        String recordingId = String.valueOf(recording.get("id"));
        authoring.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "enter_text",
                "target", Map.of("semanticId", "field.uid"), "value", "RECORDED_HARDCODE_VALUE"));
        authoring.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap",
                "target", Map.of("semanticId", "action.add")));
        authoring.appendEvent(recordingId, Map.of(
                "kind", "checkpoint", "scope", "ui",
                "expect", Map.of("visible_texts", List.of("RECORDED_HARDCODE_VALUE"), "no_exception", true)));
        authoring.stopRecording(recordingId, Map.of());
        artifacts.writeGenerated(
                suiteId,
                BehaviorArtifactType.AUTOMATION_RECORD,
                "automation-record.json",
                automationJson().getBytes(StandardCharsets.UTF_8),
                Map.of("recording_id", recordingId));

        Map<String, Object> scenario = authoring.abstractRecording(recordingId, Map.of(
                "scenario_code", "ADD_USER",
                "name", "Add user",
                "weight", 10.0,
                "viewports", List.of(
                        Map.of("name", "phone", "width", 390, "height", 844),
                        Map.of("name", "desktop", "width", 1280, "height", 800))));
        Map<String, Object> captured = capture.capture(suiteId, String.valueOf(scenario.get("id")));
        assertTrue(((Number) captured.get("database_checkpoint_count")).intValue() >= 1);

        @SuppressWarnings("unchecked")
        Map<String, String> materialized = (Map<String, String>) captured.get("materialized_variables");
        String generatedUid = materialized.get("field_uid");
        assertNotNull(generatedUid);
        assertNotEquals("RECORDED_HARDCODE_VALUE", generatedUid,
                "Replay phải thay dữ liệu record bằng dữ liệu sinh theo seed");

        BehaviorArtifact output = artifacts.active(suiteId, BehaviorArtifactType.OUTPUT_DATABASE);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + output.getStoragePath());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT uid FROM users WHERE uid='" + generatedUid + "'")) {
            assertTrue(rows.next(), "Output DB phải chứa thay đổi thật do Golden App tạo");
        }

        artifacts.writeGenerated(
                suiteId,
                BehaviorArtifactType.TESTCASE_DEFINITION,
                "testcase-definition.json",
                testcaseJson().getBytes(StandardCharsets.UTF_8),
                Map.of("scenario_code", "ADD_USER"));

        Map<String, Object> preflight = validation.validate(suiteId);
        assertEquals("PASSED", preflight.get("status"), String.valueOf(preflight.get("log")));
        authoring.publish(suiteId);
        Map<String, Object> published = materializer.materialize(suiteId);
        assertEquals(true, published.get("ready_for_grading"));
        assertTrue(Files.isRegularFile(examRoot
                .resolve("GOLDEN_E2E_EXAM_" + suffix)
                .resolve("testcase")
                .resolve("behavior_plan.json")));
        assertEquals("READY", examRepository.findByExamId("GOLDEN_E2E_EXAM_" + suffix)
                .orElseThrow().getStatus().name());
    }

    private Path sqlite(String name, boolean hidden) throws Exception {
        Path file = temp.resolve(name);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(uid TEXT PRIMARY KEY, name TEXT NOT NULL)");
            if (hidden) statement.execute("INSERT INTO users VALUES ('HIDDEN_EXISTING', 'Hidden row')");
        }
        return file;
    }

    private MockMultipartFile multipart(Path path) throws Exception {
        return new MockMultipartFile("file", path.getFileName().toString(),
                "application/vnd.sqlite3", Files.readAllBytes(path));
    }

    private byte[] goldenZip() throws Exception {
        String main = """
                import 'package:flutter/material.dart';
                import 'package:path/path.dart' as p;
                import 'package:sqflite_common_ffi/sqflite_ffi.dart';

                void main() => runApp(const GoldenApp());

                class GoldenApp extends StatelessWidget {
                  const GoldenApp({super.key});
                  @override
                  Widget build(BuildContext context) => const MaterialApp(home: AddScreen());
                }

                class AddScreen extends StatefulWidget {
                  const AddScreen({super.key});
                  @override
                  State<AddScreen> createState() => _AddScreenState();
                }

                class _AddScreenState extends State<AddScreen> {
                  final uid = TextEditingController();
                  String saved = '';
                  Future<void> add() async {
                    final path = p.join(await databaseFactory.getDatabasesPath(), 'grader_e2e.db');
                    final db = await databaseFactory.openDatabase(path);
                    await db.insert('users', {'uid': uid.text, 'name': 'Golden user'});
                    await db.close();
                    if (mounted) setState(() => saved = uid.text);
                  }
                  @override
                  Widget build(BuildContext context) => Scaffold(
                    body: Center(child: Column(mainAxisSize: MainAxisSize.min, children: [
                      TextField(key: const ValueKey<String>('field.uid'), controller: uid),
                      ElevatedButton(key: const ValueKey<String>('action.add'), onPressed: add, child: const Text('Add')),
                      Text(saved),
                    ])),
                  );
                }
                """;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("golden/pubspec.yaml"));
            zip.write("name: golden_e2e\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("golden/lib/main.dart"));
            zip.write(main.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private String automationJson() {
        return """
                {"steps":[
                  {"stage":"ACTION","attribute":"semanticId","attributeValue":"field.uid","valueType":"string","value":"RECORDED_HARDCODE_VALUE","action":"enter_text","browser":"flutter_tester"},
                  {"stage":"ACTION","attribute":"semanticId","attributeValue":"action.add","valueType":"string","value":"","action":"tap","browser":"flutter_tester"}
                ]}
                """;
    }

    private String testcaseJson() {
        return """
                {"steps":[
                  {"stage":"ACTION","attribute":"semanticId","attributeValue":"field.uid","valueType":"string","value":"${field_uid}","action":"enter_text","browser":"flutter_tester"},
                  {"stage":"ASSERT","attribute":"text","attributeValue":"${field_uid}","valueType":"string","value":"${field_uid}","action":"observe_ui","browser":"flutter_tester"}
                ]}
                """;
    }
}
