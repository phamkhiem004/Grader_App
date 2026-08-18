package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/** Đóng gói execution plan thành bộ runner dùng chung có thể mount trực tiếp vào Docker. */
@Service
public class BehaviorSuiteMaterializer {

    @Value("${grader.template-dir:grader-base}")
    private String templateDir;

    @Value("${grader.exams-dir:exams}")
    private String examsDir;

    private final BehaviorAuthoringService authoring;
    private final BehaviorArtifactService artifacts;
    private final ExamRepository exams;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public BehaviorSuiteMaterializer(BehaviorAuthoringService authoring,
                                     BehaviorArtifactService artifacts,
                                     ExamRepository exams) {
        this.authoring = authoring;
        this.artifacts = artifacts;
        this.exams = exams;
    }

    @Transactional
    public Map<String, Object> materialize(String suiteId) {
        Map<String, Object> plan = authoring.executionPlan(suiteId);
        Map<String, Object> suite = map(plan.get("suite"));
        String suiteCode = ExamService.safeId(text(suite, "suite_code"), "bộ chấm");
        String configuredExamId = text(suite, "exam_id");
        String examId = ExamService.safeId(
                configuredExamId.isBlank() ? suiteCode : configuredExamId,
                "đề");

        List<Map<String, Object>> cases = expandCases(plan, suiteCode);
        if (cases.isEmpty()) throw new IllegalStateException("Bộ chấm không có checkpoint để publish");
        Map<String, Object> matrix = buildMatrix(cases);

        Path examDir = examsRoot().resolve(examId).normalize();
        Path target = examDir.resolve("testcase").normalize();
        if (!target.startsWith(examDir)) throw new IllegalStateException("Đường dẫn publish không an toàn");

        Path staging = null;
        Path backup = null;
        try {
            Files.createDirectories(examDir);
            staging = Files.createTempDirectory(examDir, ".rar-staging-");
            writeBundle(staging, suiteId, plan, suite, suiteCode, cases, matrix, true);

            if (Files.exists(target)) {
                backup = examDir.resolve(".testcase-rar-backup-" + UUID.randomUUID()).normalize();
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            moveDirectory(staging, target);
            staging = null;
            if (backup != null) {
                deleteRecursively(backup);
                backup = null;
            }

            Exam exam = exams.findByExamId(examId).orElseGet(Exam::new);
            if (exam.getExamId() == null) exam.setExamId(examId);
            exam.setExamName(text(suite, "name"));
            exam.setTeacherNote(text(suite, "description"));
            exam.setTestcasePath(target.toAbsolutePath().toString());
            exam.setStatus(ExamStatus.READY);
            exam.setTestcaseStatus("PUBLISHED");
            exam.setTestcaseVersion((exam.getTestcaseVersion() == null ? 0 : exam.getTestcaseVersion()) + 1);
            exam.setTestcasePublishedAt(Instant.now());
            exams.save(exam);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exam_id", examId);
            result.put("testcase_path", target.toAbsolutePath().toString());
            result.put("scenario_count", list(plan.get("scenarios")).size());
            result.put("criterion_count", cases.size());
            result.put("files", List.of(
                    "exam_test.dart", "grader.dart", "behavior_plan.json",
                    "skills_matrix.json", "contract.json", "suite_manifest.json",
                    "fixtures/student.db", "fixtures/hidden.db", "fixtures/expected-output.db"));
            result.put("ready_for_grading", true);
            return result;
        } catch (Exception e) {
            try {
                if (staging != null) deleteRecursively(staging);
                if (backup != null && Files.exists(backup) && !Files.exists(target)) {
                    moveDirectory(backup, target);
                }
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("Không publish được bộ Record–Replay: " + e.getMessage(), e);
        }
    }

    /**
     * Sinh bundle tạm để chạy chính Golden Solution. Không ghi Exam, không thay testcase đang
     * dùng để chấm và không yêu cầu suite đã publish.
     */
    public Path createValidationBundle(String suiteId, Path root) {
        Map<String, Object> plan = authoring.previewExecutionPlan(suiteId);
        Map<String, Object> suite = map(plan.get("suite"));
        String suiteCode = ExamService.safeId(text(suite, "suite_code"), "bộ chấm");
        List<Map<String, Object>> cases = expandCases(plan, suiteCode);
        if (cases.isEmpty()) throw new IllegalStateException("Bộ chấm không có checkpoint để preflight");
        Path target = root.toAbsolutePath().normalize().resolve("test");
        try {
            Files.createDirectories(target);
            writeBundle(target, suiteId, plan, suite, suiteCode, cases, buildMatrix(cases), true);
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Không sinh được bundle preflight: " + e.getMessage(), e);
        }
    }

    /**
     * Sinh runner tạm cho bước lấy oracle. Ở thời điểm này Output Database chưa tồn tại;
     * runner sẽ khởi tạo từ Hidden DB, replay Golden Solution và tự capture DB sau thao tác.
     */
    public Path createCaptureBundle(String suiteId, Path root) {
        Map<String, Object> plan = authoring.previewExecutionPlan(suiteId);
        Map<String, Object> suite = map(plan.get("suite"));
        String suiteCode = ExamService.safeId(text(suite, "suite_code"), "bộ chấm");
        List<Map<String, Object>> cases = expandCases(plan, suiteCode);
        if (cases.isEmpty()) {
            throw new IllegalStateException("Record chưa tạo được checkpoint tối thiểu để capture Golden");
        }
        Path target = root.toAbsolutePath().normalize().resolve("test");
        try {
            Files.createDirectories(target);
            writeBundle(target, suiteId, plan, suite, suiteCode, cases, buildMatrix(cases), false);
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Không sinh được bundle capture Golden: " + e.getMessage(), e);
        }
    }

    private void writeBundle(Path target,
                             String suiteId,
                             Map<String, Object> plan,
                             Map<String, Object> suite,
                             String suiteCode,
                             List<Map<String, Object>> cases,
                             Map<String, Object> matrix,
                             boolean requireOutputDatabase) throws Exception {
        copyResource("behavior-replay-engine/exam_test.dart", target.resolve("exam_test.dart"));
        copyResource("behavior-replay-engine/grader.dart", target.resolve("grader.dart"));

        Path fixtures = target.resolve("fixtures");
        Files.createDirectories(fixtures);
        copyArtifact(suiteId, BehaviorArtifactType.STUDENT_DATABASE, fixtures.resolve("student.db"));
        copyArtifact(suiteId, BehaviorArtifactType.HIDDEN_DATABASE, fixtures.resolve("hidden.db"));
        if (requireOutputDatabase
                || artifacts.activeOptional(suiteId, BehaviorArtifactType.OUTPUT_DATABASE).isPresent()) {
            copyArtifact(suiteId, BehaviorArtifactType.OUTPUT_DATABASE, fixtures.resolve("expected-output.db"));
        } else {
            // File chỉ là placeholder của bundle capture; không được dùng làm oracle.
            // Output thật sẽ được exam_test.dart ghi sang captured-output.db.
            copyArtifact(suiteId, BehaviorArtifactType.HIDDEN_DATABASE, fixtures.resolve("expected-output.db"));
        }

        Map<String, Object> databaseContract = new LinkedHashMap<>(map(plan.get("database_contract")));
        databaseContract.put("enabled", true);
        databaseContract.put("student_fixture_path", "/app/test/fixtures/student.db");
        databaseContract.put("hidden_fixture_path", "/app/test/fixtures/hidden.db");
        databaseContract.put("expected_output_path", "/app/test/fixtures/expected-output.db");

        Map<String, Object> executablePlan = new LinkedHashMap<>();
        executablePlan.put("schema_version", plan.get("schema_version"));
        executablePlan.put("suite", suite);
        executablePlan.put("public_contract", plan.get("public_contract"));
        executablePlan.put("database_contract", databaseContract);
        executablePlan.put("runtime_config", plan.get("runtime_config"));
        executablePlan.put("cases", cases);
        writeJson(target.resolve("behavior_plan.json"), executablePlan);
        writeJson(target.resolve("skills_matrix.json"), matrix);
        writeJson(target.resolve("contract.json"), publicContract(plan));
        writeJson(target.resolve("suite_manifest.json"), Map.of(
                "engine", "GOLDEN_BEHAVIOR_RECORD_REPLAY",
                "engine_version", "1.0.0",
                "suite_id", suiteId,
                "suite_code", suiteCode,
                "revision", suite.getOrDefault("revision", 1),
                "scenario_count", list(plan.get("scenarios")).size(),
                "criterion_count", cases.size(),
                "artifact_manifest", artifacts.activeManifest(suiteId),
                "published_at", Instant.now().toString()));
    }

    private List<Map<String, Object>> expandCases(Map<String, Object> plan, String suiteCode) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object rawScenario : list(plan.get("scenarios"))) {
            Map<String, Object> scenario = map(rawScenario);
            List<Object> checkpoints = list(scenario.get("checkpoints"));
            if (checkpoints.isEmpty()) continue;
            List<Object> scenarioViewports = list(scenario.get("viewports"));
            if (scenarioViewports.isEmpty()) {
                scenarioViewports = List.of(Map.of(
                        "name", "default", "width", 390, "height", 844, "device_pixel_ratio", 1));
            }
            double scenarioWeight = number(scenario.get("weight"), 1.0);
            double checkpointTotal = checkpoints.stream()
                    .map(BehaviorSuiteMaterializer::map)
                    .mapToDouble(item -> Math.max(0.0001, number(item.get("weight"), 1.0)))
                    .sum();
            int index = 0;
            for (Object rawCheckpoint : checkpoints) {
                Map<String, Object> checkpoint = map(rawCheckpoint);
                index++;
                String checkpointId = text(checkpoint, "id");
                if (checkpointId.isBlank()) checkpointId = "CHECKPOINT_" + index;
                boolean databaseCheckpoint = "database_observation".equals(text(checkpoint, "kind"))
                        || "database".equals(text(checkpoint, "scope"));
                List<Object> checkpointViewports = databaseCheckpoint
                        ? List.of(first(scenarioViewports))
                        : scenarioViewports;
                double checkpointWeight = scenarioWeight
                        * Math.max(0.0001, number(checkpoint.get("weight"), 1.0))
                        / checkpointTotal;
                int viewportIndex = 0;
                for (Object rawViewport : checkpointViewports) {
                    viewportIndex++;
                    Map<String, Object> viewport = map(rawViewport);
                    String viewportName = text(viewport, "name");
                    if (viewportName.isBlank()) viewportName = "viewport_" + viewportIndex;
                    String executionCode = text(scenario, "scenario_code") + "__VP_" + viewportIndex;
                    String testSuffix = checkpointViewports.size() > 1 ? "_" + viewportName : "";
                    String testId = safeTestId(suiteCode + "_" + text(scenario, "scenario_code")
                            + "_" + checkpointId + testSuffix);
                    double itemWeight = checkpointWeight / checkpointViewports.size();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("test_id", testId);
                    item.put("scenario_id", scenario.get("id"));
                    item.put("scenario_code", scenario.get("scenario_code"));
                    item.put("execution_code", executionCode);
                    item.put("name", checkpointName(scenario, checkpoint, index)
                            + (checkpointViewports.size() > 1 ? " [" + viewportName + "]" : ""));
                    item.put("description", scenario.get("description"));
                    item.put("skill_code", scenario.getOrDefault("skill_code", "UI_BUTTONS_SELECTION"));
                    item.put("weight", Math.round(itemWeight * 1_000_000d) / 1_000_000d);
                    item.put("variables", scenario.get("variables"));
                    item.put("initial_state", scenario.get("initial_state"));
                    item.put("steps", scenario.get("steps"));
                    item.put("viewport", viewport);
                    item.put("checkpoint", checkpoint);
                    item.put("oracle", scenario.get("oracle"));
                    out.add(item);
                }
            }
        }
        return out;
    }

    private Map<String, Object> buildMatrix(List<Map<String, Object>> cases) {
        Map<String, Object> matrix = new LinkedHashMap<>();
        for (Map<String, Object> item : cases) {
            Map<String, Object> checkpoint = map(item.get("checkpoint"));
            String expected = text(checkpoint, "expected");
            if (expected.isBlank()) expected = "Kết quả phải khớp observation của Golden App.";
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("instance_id", item.get("test_id"));
            metadata.put("runner", "BEHAVIOR_REPLAY");
            metadata.put("scenario_code", item.get("scenario_code"));
            metadata.put("execution_code", item.get("execution_code"));
            metadata.put("checkpoint_id", checkpoint.get("id"));
            metadata.put("skill_code", item.get("skill_code"));
            metadata.put("testcase_group", "BEHAVIOR");
            metadata.put("layer", "behavior");
            metadata.put("name", item.get("name"));
            metadata.put("description", item.get("description"));
            metadata.put("expected", expected);
            metadata.put("difficulty", "intermediate");
            metadata.put("weight", item.get("weight"));
            matrix.put(String.valueOf(item.get("test_id")), metadata);
        }
        return matrix;
    }

    private Map<String, Object> publicContract(Map<String, Object> plan) {
        Map<String, Object> runtime = map(plan.get("runtime_config"));
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("engine", "GOLDEN_BEHAVIOR_RECORD_REPLAY");
        contract.put("schema_version", plan.get("schema_version"));
        contract.put("public_contract", plan.get("public_contract"));
        contract.put("database_contract", plan.get("database_contract"));
        contract.put("allowed_packages", runtime.getOrDefault("allowed_packages", List.of(
                "flutter", "flutter_test", "path", "sqflite", "sqflite_common_ffi")));
        return contract;
    }

    private String checkpointName(Map<String, Object> scenario, Map<String, Object> checkpoint, int index) {
        String label = text(checkpoint, "name");
        if (label.isBlank()) label = text(checkpoint, "label");
        if (label.isBlank()) label = text(checkpoint, "id");
        if (label.isBlank()) label = "Checkpoint " + index;
        return text(scenario, "name") + " — " + label;
    }

    private String safeTestId(String value) {
        String safe = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        return safe.length() <= 180 ? safe : safe.substring(0, 180);
    }

    private Path examsRoot() {
        Path configured = Path.of(examsDir);
        if (configured.isAbsolute()) return configured.normalize();
        Path template = locateTemplateDir();
        Path root = template.getParent();
        return (root == null ? configured.toAbsolutePath() : root.resolve(configured)).normalize();
    }

    private Path locateTemplateDir() {
        Path configured = Path.of(templateDir);
        String name = configured.getFileName() == null ? "grader-base" : configured.getFileName().toString();
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && cursor != null; depth++, cursor = cursor.getParent()) {
            for (Path candidate : List.of(cursor.resolve(configured), cursor.resolve(name))) {
                if (Files.exists(candidate.resolve("Dockerfile.base"))) return candidate.normalize();
            }
        }
        return configured.toAbsolutePath().normalize();
    }

    private void copyResource(String resource, Path target) throws Exception {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyArtifact(String suiteId, BehaviorArtifactType type, Path target) throws Exception {
        BehaviorArtifact artifact = artifacts.active(suiteId, type);
        Path source = Path.of(artifact.getStoragePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("Artifact " + type + " không còn tồn tại trên đĩa");
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeJson(Path file, Object value) throws Exception {
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                StandardCharsets.UTF_8);
    }

    private void moveDirectory(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Object first(List<Object> values) {
        return values.isEmpty() ? Map.of() : values.get(0);
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> source ? new ArrayList<>(source) : new ArrayList<>();
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
