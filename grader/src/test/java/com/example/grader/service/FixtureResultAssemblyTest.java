package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.entity.Skill;
import com.example.grader.entity.SkillCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chạy CODE THẬT của khâu gắn nhãn trên DỮ LIỆU THẬT do grader sinh ra, rồi ghi
 * {@code target/fixture-result-<bài>.json} để {@code verify_result.py} nghiệm thu ngoài.
 *
 * <p>Không cần MySQL: {@link SyllabusService.Resolver} dựng thẳng từ {@code syllabus.json},
 * đúng như {@code seedOnStartup} nạp vào DB.
 *
 * <p>Phần bọc ngoài (student/exam/grading_result) được DỰNG LẠI giống
 * {@code BatchGradingService.assembleResultJson} — method đó là private và cần repository,
 * nên ở đây chỉ gọi thật các bước P1 đã đổi.
 *
 * <p>Test tự bỏ qua khi chưa chấm fixture (cần Docker): chạy
 * {@code fixtures/result-json-v2/run-fixture.sh} trước.
 */
class FixtureResultAssemblyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURE = Path.of("..", "fixtures", "result-json-v2");
    /** Từ vựng nội bộ của hệ thống chấm — sinh viên không kiểm chứng được số lượng test. */
    private static final java.util.regex.Pattern INTERNAL_TEST_COUNT =
            java.util.regex.Pattern.compile("\\b\\d+\\s+(assert|testcase|test|phép kiểm)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    @Test
    void assemblesLabelsForEveryFixtureSubmission() throws Exception {
        SyllabusService.Resolver resolver = resolverFromSeedFile();
        int done = 0;

        for (String variant : List.of("high", "medium", "broken-boot", "broken-compile")) {
            Path graderOut = FIXTURE.resolve(".build/out/" + variant + ".json");
            if (!Files.exists(graderOut)) continue;

            Map<String, Object> assembled = assemble(graderOut, resolver, variant);
            Path target = Path.of("target", "fixture-result-" + variant + ".json");
            Files.createDirectories(target.getParent());
            Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(assembled), StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tcs = (List<Map<String, Object>>) assembled.get("test_cases");
            assertEquals(13, tcs.size(), variant + ": số testcase");
            for (Map<String, Object> tc : tcs) {
                String id = String.valueOf(tc.get("test_id"));
                // A1: ba khoá phải CÓ MẶT ở mọi testcase, kể cả giá trị null.
                assertTrue(tc.containsKey("rubric"), variant + "/" + id + ": thiếu rubric");
                assertTrue(tc.containsKey("layer"), variant + "/" + id + ": thiếu layer");
                assertTrue(tc.containsKey("chapter"), variant + "/" + id + ": thiếu chapter");
                // A3: layer phải nằm trong enum của SPEC.
                assertTrue(TestCaseTaxonomy.LAYERS.contains(tc.get("layer")),
                        variant + "/" + id + ": layer = " + tc.get("layer"));
                // `expected` đi thẳng tới sinh viên nên không được chứa từ vựng nội bộ
                // của hệ thống chấm: chữ "assert", hay đếm số testcase/test.
                String expected = String.valueOf(tc.get("expected"));
                assertFalse(expected.toLowerCase().contains("assert"),
                        variant + "/" + id + ": expected lộ từ vựng nội bộ — " + expected);
                assertFalse(INTERNAL_TEST_COUNT.matcher(expected).find(),
                        variant + "/" + id + ": expected đếm số test — " + expected);
                // Chứng minh harness đã chạy sanitizeTestCaseErrors: mọi câu FAIL phải có
                // đủ error + student_safe_summary hệt như backend gửi đi.
                if (String.valueOf(tc.get("status")).contains("fail")) {
                    assertNotNull(tc.get("error"), variant + "/" + id + ": thiếu error");
                    assertFalse(String.valueOf(tc.get("student_safe_summary")).isBlank(),
                            variant + "/" + id + ": thiếu student_safe_summary");
                }
                // P5: `actual` của mọi test KHÔNG đạt phải đến từ kênh quan sát có cấu trúc,
                // không phải từ bóc log tiếng Anh. Runner nào rơi lại về bóc log thì đỏ ở đây.
                if (!"passed".equals(tc.get("status"))) {
                    assertEquals("observation", tc.get("actual_source"),
                            variant + "/" + id + ": actual còn dựng từ log — " + tc.get("actual"));
                }
            }
            done++;
        }

        assumeTrue(done > 0, "Chưa chấm fixture — chạy fixtures/result-json-v2/run-fixture.sh trước");
    }

    @Test
    void labelsMatchSpecForKnownTestcases() throws Exception {
        Path graderOut = FIXTURE.resolve(".build/out/high.json");
        assumeTrue(Files.exists(graderOut), "Chưa chấm fixture bài high");

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tcs = (List<Map<String, Object>>)
                assemble(graderOut, resolverFromSeedFile(), "high").get("test_cases");
        for (Map<String, Object> tc : tcs) byId.put(String.valueOf(tc.get("test_id")), tc);

        // B1: layer suy từ runner
        assertEquals("integration", byId.get("TC_APP_BOOT").get("layer"));
        assertEquals("widget", byId.get("TC_LIST_VISIBLE").get("layer"));
        assertEquals("responsive", byId.get("TC_RESPONSIVE_TARGET").get("layer"));
        // B2: GROUP lấy tầng cao nhất của các con (WIDGET_VISIBLE + FORM_SUBMIT)
        assertEquals("integration", byId.get("TC_ADD_USER").get("layer"));

        // rubric = nhóm chức năng giáo viên gom, KHÔNG phải testcase_group
        assertEquals("XEM_DS", byId.get("TC_LIST_VISIBLE").get("rubric"));
        assertEquals("THEM_USER", byId.get("TC_ADD_USER").get("rubric"));
        assertEquals("RESPONSIVE", byId.get("TC_RESPONSIVE_TARGET").get("rubric"));

        // A5: chapter khớp category trong syllabus
        assertEquals("UI_BASIC_WIDGETS", byId.get("TC_APP_BOOT").get("category"));
        assertEquals(4, byId.get("TC_APP_BOOT").get("chapter"));
        assertEquals("ADVANCED_WIDGETS", byId.get("TC_LIST_VISIBLE").get("category"));
        assertEquals(7, byId.get("TC_LIST_VISIBLE").get("chapter"));
        assertEquals("STATE_MANAGEMENT", byId.get("TC_DELETE_CONFIRM").get("category"));
        assertEquals(6, byId.get("TC_DELETE_CONFIRM").get("chapter"));

        // GROUP có expected giáo viên viết tay: bản ghép máy KHÔNG được ghi đè, vì câu viết
        // tay bao giờ cũng sát đề hơn.
        assertEquals("Nhập họ tên và email hợp lệ rồi lưu thì người dùng mới phải xuất hiện "
                        + "trong danh sách và không còn thông báo lỗi.",
                byId.get("TC_ADD_USER").get("expected"));
    }

    // ── dựng lại luồng ghép, gọi THẬT các bước P1 đã đổi ───────────────────
    @SuppressWarnings("unchecked")
    private Map<String, Object> assemble(Path graderOut, SyllabusService.Resolver resolver,
                                         String variant) throws Exception {
        JsonNode grader = MAPPER.readTree(Files.readString(graderOut, StandardCharsets.UTF_8));

        List<Map<String, Object>> tcs = new ArrayList<>();
        for (JsonNode n : grader.get("test_cases")) tcs.add(MAPPER.convertValue(n, Map.class));

        BatchGradingService batch = new BatchGradingService();
        Exam exam = new Exam();
        exam.setTestcasePath(FIXTURE.resolve("exam").toAbsolutePath().toString());

        // ĐÚNG THỨ TỰ của assembleResultJson. Thiếu một bước là artifact không trung thực:
        // sanitizeTestCaseErrors mới là chỗ sinh `error` + `student_safe_summary`, nên bỏ nó
        // thì nhóm luật E của ACCEPTANCE xanh giả (từng làm samples/ sai, xem CHANGELOG_FOR_NLP).
        Object matrix = invoke(batch, "loadSkillsMatrix", new Class<?>[]{Exam.class}, exam);
        assertNotNull(matrix, "Không đọc được skills_matrix.json của fixture");
        invoke(batch, "enrichTestCases", new Class<?>[]{List.class, Map.class}, tcs, matrix);
        invoke(batch, "annotateTaxonomy", new Class<?>[]{List.class, Map.class}, tcs, matrix);
        invoke(batch, "normalizeExpectedFields", new Class<?>[]{List.class}, tcs);
        invoke(batch, "sanitizeTestCaseErrors", new Class<?>[]{List.class}, tcs);
        CompetencyService competency = new CompetencyService();
        competency.annotateTestCases(tcs, resolver);
        // Chạy SAU khối gắn nhãn, y như assembleResultJson — đây là chỗ bảo đảm khoá hợp đồng.
        invoke(batch, "guaranteeContractKeys", new Class<?>[]{List.class}, tcs);

        Map<String, Object> gradingResult = MAPPER.convertValue(grader.get("grading_result"), Map.class);
        gradingResult.putIfAbsent("not_run_tests",
                invoke(batch, "countStatus", new Class<?>[]{List.class, String.class}, tcs, "not_run"));
        // Fixture chạy resolver thật và không ngã, nên luôn null — vẫn phải có mặt.
        gradingResult.put("annotation_error", null);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema_version", "2");
        root.put("student", Map.of("id", "FIXTURE_" + variant.toUpperCase(), "name", "Fixture " + variant));
        root.put("exam", Map.of("code", "FIXTURE_V2", "title", "Fixture result.json v2", "total_score", 10));
        root.put("grading_result", gradingResult);
        root.put("test_cases", tcs);
        // Backend phát hành cả khối này; thiếu nó thì bên đọc tưởng nó đã bị bỏ.
        root.put("competency_assessment", competency.assess(tcs, resolver));
        root.put("teacher_note", "");
        return root;
    }

    private Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /** Dựng Resolver từ syllabus.json y như SyllabusService.seedOnStartup nạp vào DB. */
    private SyllabusService.Resolver resolverFromSeedFile() throws Exception {
        JsonNode root;
        try (var in = getClass().getResourceAsStream("/syllabus.json")) {
            assertNotNull(in, "Không tìm thấy syllabus.json trên classpath");
            root = MAPPER.readTree(in);
        }

        Map<String, SkillCategory> cats = new LinkedHashMap<>();
        for (JsonNode c : root.path("categories")) {
            SkillCategory cat = new SkillCategory();
            cat.setCode(c.path("code").asText());
            cat.setName(c.path("name").asText());
            cat.setCompetencyLabel(c.path("competency_label").asText(null));
            cat.setDisplayOrder(c.path("order").asInt(0));
            if (c.hasNonNull("chapter")) cat.setChapter(c.path("chapter").asInt());
            cats.put(cat.getCode(), cat);
        }

        Map<String, Skill> skills = new LinkedHashMap<>();
        for (JsonNode s : root.path("skills")) {
            Skill sk = new Skill();
            sk.setCode(s.path("code").asText());
            sk.setCategoryCode(s.path("category").asText());
            sk.setName(s.path("name").asText());
            sk.setDefaultDifficulty(s.path("default_difficulty").asText("basic"));
            skills.put(sk.getCode(), sk);
        }

        return new SyllabusService.Resolver(cats, skills);
    }
}
