package com.example.grader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phát MỘT mẫu result.json có `expected` đi ĐƯỜNG MÁY SINH — phía NLP xin, và lý do họ xin đáng
 * ghi lại: bảy mẫu đang phát hành đều có `expected` GÕ TAY, nên hợp đồng máy-kiểm giữa hai bên có
 * một vùng mù CỐ ĐỊNH, không phải vùng mù tạm thời. Nó chỉ mất khi có một mẫu đi đường đó.
 *
 * <p>Mẫu này dựng bằng đúng ba thứ thật: template thật, {@code renderExpected} thật của khâu soạn
 * đề, và tham số thật của 25 dòng fixture. Rồi chạy đủ chuỗi hàm của {@code assembleResultJson}
 * (dùng lại {@link FixtureResultAssemblyTest#assemble}) trên kết quả chấm thật.
 *
 * <p>PHẠM VI, nói rõ để không phát biểu rộng hơn phép đo: chỉ trường `expected` là máy sinh. Phần
 * còn lại của skills_matrix vẫn là fixture gõ tay, và hình dạng đề của fixture (25 testcase, mỗi
 * cái mang một nhãn nhóm) KHÔNG phải hình dạng mà khâu soạn đề sinh ra hôm nay — ở đó `group_id`
 * gom các testcase thành MỘT testcase lớn.
 */
class MachineExpectedSampleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURE = Path.of("..", "fixtures", "result-json-v2");
    /** Bài `medium` — có cả test đạt, test hỏng và nhóm, nên mẫu phát ra không nghèo dữ liệu. */
    private static final String VARIANT = "medium";

    @Test
    void publishesASampleWhoseExpectedCameFromTheAuthoringPath() throws Exception {
        Path graderOut = FIXTURE.resolve(".build/out/" + VARIANT + ".json");
        assumeTrue(Files.exists(graderOut),
                "Chưa chấm fixture — chạy fixtures/result-json-v2/run-fixture.sh trước");

        Path examDir = Path.of("target", "machine-expected-exam");
        Files.createDirectories(examDir);
        Set<String> teacherText = new LinkedHashSet<>();
        Map<String, Object> matrix = machineExpectedMatrix(teacherText);
        Files.writeString(examDir.resolve("skills_matrix.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(matrix),
                StandardCharsets.UTF_8);

        Map<String, Object> assembled = FixtureResultAssemblyTest.assemble(
                graderOut, FixtureResultAssemblyTest.resolverFromSeedFile(), VARIANT, examDir);
        Path target = Path.of("target", "fixture-result-machine-expected.json");
        Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(assembled), StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tcs = (List<Map<String, Object>>) assembled.get("test_cases");
        assertTrue(tcs.size() >= 20, "Mẫu chỉ có " + tcs.size() + " testcase");

        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (Map<String, Object> tc : tcs) {
            String expected = String.valueOf(tc.get("expected"));
            assertTrue(!expected.isBlank() && !"PASS".equals(expected),
                    tc.get("test_id") + ": expected rỗng — bản bù 'PASS' của normalizeExpectedFields"
                            + " nghĩa là dòng matrix không khớp test_id");
            // Nội dung giảng viên gõ đi thẳng theo hợp đồng; phần Grader dựng mới phải sạch.
            String scanned = expected;
            for (String content : teacherText) scanned = scanned.replace(content, "");
            bad.addAll(TemplateExpectedTextTest.violations(String.valueOf(tc.get("test_id")), scanned));
            checked++;
        }
        assertTrue(checked >= 20, "Chỉ soi được " + checked + " testcase");
        assertTrue(bad.isEmpty(), "Mẫu máy sinh còn rác:\n  " + String.join("\n  ", bad));
    }

    /**
     * Bản sao skills_matrix của fixture với MỌI `expected` thay bằng bản {@code renderExpected}
     * dựng ra — kể cả `expected` của testcase con trong nhóm, vì câu của nhóm được ghép từ chúng.
     * Dòng GROUP để trống expected để {@code TestCaseTaxonomy.groupExpected} ghép — đó chính là
     * đường máy sinh của nhóm.
     */
    private Map<String, Object> machineExpectedMatrix(Set<String> teacherText) throws Exception {
        JsonNode rows = MAPPER.readTree(Files.readString(
                FIXTURE.resolve("exam").resolve("skills_matrix.json"), StandardCharsets.UTF_8));

        TestcaseTemplateService service = new TestcaseTemplateService();
        service.loadTemplates();
        Map<String, JsonNode> byRunner = new LinkedHashMap<>();
        try (var in = getClass().getResourceAsStream("/common-testcase-templates.json")) {
            assertNotNull(in, "Không tìm thấy thư viện template trên classpath");
            MAPPER.readTree(in).forEach(t -> byRunner.put(t.path("runner").asText(), t));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> row : rows.properties()) {
            out.put(row.getKey(), machineRow(row.getValue(), byRunner, service, teacherText));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> machineRow(JsonNode row, Map<String, JsonNode> byRunner,
                                           TestcaseTemplateService service, Set<String> teacherText) {
        Map<String, Object> copy = MAPPER.convertValue(row, Map.class);
        JsonNode template = byRunner.get(row.path("runner").asText());
        if (template == null) {
            copy.put("expected", "");     // GROUP: để groupExpected ghép từ các con
        } else {
            Map<String, Object> params = new LinkedHashMap<>();
            template.path("parameters_schema").properties()
                    .forEach(e -> params.put(e.getKey(), plain(e.getValue())));
            row.path("parameters").properties().forEach(e -> params.put(e.getKey(), plain(e.getValue())));
            for (String key : TemplateExpectedTextTest.FREE_TEXT_PARAMS) {
                Object value = params.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    teacherText.add(String.valueOf(value));
                }
            }
            copy.put("expected", service.renderExpected(
                    template.path("expected_template").asText(""), params));
        }
        if (row.has("children")) {
            List<Object> children = new ArrayList<>();
            for (JsonNode child : row.path("children")) {
                children.add(machineRow(child, byRunner, service, teacherText));
            }
            copy.put("children", children);
        }
        return copy;
    }

    private static Object plain(JsonNode node) {
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.asText();
    }
}
