package com.example.grader.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pin NGỮ PHÁP KHOÁ đã công bố ở repo hợp đồng ({@code common-key-grammar.json}) vào chính
 * nguồn sinh ra nó — phía NLP xây tầng FATAL của guard trên file đó, nên nó lệch nguồn là
 * lưới của họ thô đi ÂM THẦM (CHANGELOG_FOR_GRADER 2026-08-11 mục 5).
 *
 * <p>Nguồn dẫn xuất = mọi giá trị tham số {@code *Key}/{@code *Keys} trong thư viện template
 * (bản mặc định giáo viên nhận) + {@code skills_matrix.json} của fixture (kể cả testcase con).
 * Không quét engine bằng regex: engine chỉ chứa bản sao fallback của đúng các khoá template,
 * và regex trên Dart từng vớ nhầm tên tệp công cụ ({@code flutter.bat}).
 *
 * <p>Test tự bỏ qua khi máy không có repo hợp đồng (cùng kiểu với test phía NLP) — nhưng khi
 * repo CÓ MẶT thì mọi sai lệch là đỏ, không có mức "cảnh báo".
 */
class KeyGrammarContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GRAMMAR = Path.of("..", "..", "SPEC_grader_result_json",
            "common-key-grammar.json");
    private static final Path FIXTURE_MATRIX = Path.of("..", "fixtures", "result-json-v2",
            "exam", "skills_matrix.json");

    /** Tên tham số mang KHOÁ: kết thúc bằng Key/Keys (widgetKey, fieldKeys, absentKey…). */
    private static final Pattern KEY_PARAM = Pattern.compile(".*Keys?$");

    @Test
    void publishedGrammarMatchesWhatGraderActuallyShips() throws Exception {
        assumeTrue(Files.exists(GRAMMAR), "Không có repo hợp đồng trên máy này — bỏ qua");
        JsonNode grammar = MAPPER.readTree(Files.readString(GRAMMAR, StandardCharsets.UTF_8));

        Set<String> shippedKeys = shippedKeys();
        // Đọc hỏng ⇒ tập rỗng ⇒ mọi phép so dưới đạt vô nghĩa (bài học A2b).
        assertTrue(shippedKeys.size() >= 25,
                "Chỉ gom được " + shippedKeys.size() + " khoá — nguồn có thể đã đổi hình dạng");

        // 1. Mọi khoá Grader phát hành phải khớp key_pattern đã công bố.
        Pattern keyPattern = Pattern.compile(grammar.path("key_pattern").asText());
        List<String> badShape = shippedKeys.stream().filter(k -> !keyPattern.matcher(k).matches()).toList();
        assertTrue(badShape.isEmpty(), "Khoá lệch hình dạng đã công bố: " + badShape);

        // 2. Tập namespace dẫn xuất phải BẰNG tập đã công bố — hai chiều.
        Set<String> derived = new TreeSet<>();
        for (String key : shippedKeys) derived.add(key.substring(0, key.indexOf('.')));
        Set<String> published = new TreeSet<>();
        grammar.path("namespaces").forEach(n -> published.add(n.asText()));
        assertEquals(published, derived,
                "Ngữ pháp công bố lệch nguồn — sửa common-key-grammar.json VÀ ghi CHANGELOG_FOR_NLP"
                        + " (tầng FATAL phía NLP pin vào file đó)");

        // 3. namespace_pattern phải chấp nhận đúng tập đó — nó là thứ phía NLP dùng trực tiếp.
        Pattern nsPattern = Pattern.compile(grammar.path("namespace_pattern").asText());
        List<String> notAccepted = shippedKeys.stream()
                .filter(k -> !nsPattern.matcher(k).find()).toList();
        assertTrue(notAccepted.isEmpty(), "namespace_pattern không nhận khoá thật: " + notAccepted);

        // 4. Mỗi namespace công bố phải có ví dụ, và ví dụ phải là khoá THẬT đang phát hành.
        for (String ns : published) {
            String example = grammar.path("examples").path(ns).asText("");
            assertTrue(shippedKeys.contains(example),
                    "Ví dụ của namespace '" + ns + "' không phải khoá thật: '" + example + "'");
        }
    }

    /** Hợp nhất khoá từ hai nguồn thật của đường soạn đề — đúng phép đo sinh ra file công bố. */
    private static Set<String> shippedKeys() throws Exception {
        Set<String> keys = new LinkedHashSet<>();

        try (InputStream in = KeyGrammarContractTest.class
                .getResourceAsStream("/common-testcase-templates.json")) {
            assertNotNull(in, "Không tìm thấy thư viện template trên classpath");
            for (JsonNode template : MAPPER.readTree(in)) {
                collectKeyParams(template.path("parameters_schema"), keys);
            }
        }

        assertTrue(Files.exists(FIXTURE_MATRIX), "Thiếu skills_matrix.json của fixture");
        JsonNode matrix = MAPPER.readTree(Files.readString(FIXTURE_MATRIX, StandardCharsets.UTF_8));
        List<JsonNode> queue = new ArrayList<>();
        matrix.forEach(queue::add);
        for (int i = 0; i < queue.size(); i++) {
            JsonNode row = queue.get(i);
            collectKeyParams(row.path("parameters"), keys);
            row.path("children").forEach(queue::add);
        }
        return keys;
    }

    private static void collectKeyParams(JsonNode params, Set<String> out) {
        params.properties().forEach(entry -> {
            if (!KEY_PARAM.matcher(entry.getKey()).matches()) return;
            for (String token : entry.getValue().asText("").split(",")) {
                String key = token.trim();
                if (!key.isEmpty() && key.contains(".")) out.add(key);
            }
        });
    }
}
