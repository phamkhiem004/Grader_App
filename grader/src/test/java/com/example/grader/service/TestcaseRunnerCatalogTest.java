package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Danh mục runner là nguồn dữ liệu cho form "Thêm testcase" ở Khu vực 2. Nếu nó lệch với
 * engine hoặc với bộ validate, giáo viên sẽ tạo được testcase mà lúc chấm mới báo lỗi —
 * lúc đó cả lớp đã thi xong. Test này chốt ba nơi phải khớp nhau.
 */
class TestcaseRunnerCatalogTest {

    private final TestcaseTemplateService service = new TestcaseTemplateService();

    private String engineSource() throws Exception {
        try (var in = new ClassPathResource("common-testcase-engine/exam_test.dart").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyRunnerInCatalogExistsInEngine() throws Exception {
        String engine = engineSource();
        for (Map<String, Object> row : TestcaseRunnerCatalog.runners()) {
            String runner = String.valueOf(row.get("runner"));
            assertTrue(engine.contains("case '" + runner + "':"),
                    "Engine exam_test.dart thiếu case cho runner " + runner);
        }
    }

    @Test
    void defaultParametersPassTheSameValidationUsedWhenSavingAnExam() throws Exception {
        Method validate = TestcaseTemplateService.class.getDeclaredMethod(
                "validateCommonParameters", String.class, Map.class, String.class);
        validate.setAccessible(true);

        for (Map<String, Object> row : TestcaseRunnerCatalog.runners()) {
            String runner = String.valueOf(row.get("runner"));
            Map<String, Object> params = new LinkedHashMap<>(asMap(row.get("parameters_schema")));
            validate.invoke(service, runner, params, "catalog_" + runner);
        }
    }

    @Test
    void requiredParametersHaveANonEmptyDefault() {
        for (Map<String, Object> row : TestcaseRunnerCatalog.runners()) {
            for (Object raw : (List<?>) row.get("parameters")) {
                Map<String, Object> param = asMap(raw);
                if (!Boolean.TRUE.equals(param.get("required"))) continue;
                Object value = param.get("default");
                assertFalse(value == null || String.valueOf(value).isBlank(),
                        "Tham số bắt buộc " + row.get("runner") + "." + param.get("name")
                                + " phải có giá trị mặc định để form không lưu ra template hỏng");
            }
        }
    }

    @Test
    void pairedValueParametersPointAtAnExistingListParameter() {
        for (Map<String, Object> row : TestcaseRunnerCatalog.runners()) {
            List<?> params = (List<?>) row.get("parameters");
            for (Object raw : params) {
                Map<String, Object> param = asMap(raw);
                if (!"values".equals(param.get("type"))) continue;
                String pairWith = String.valueOf(param.get("pair_with"));
                boolean found = params.stream()
                        .map(this::asMap)
                        .anyMatch(other -> pairWith.equals(other.get("name")));
                assertTrue(found, row.get("runner") + "." + param.get("name")
                        + " ghép cặp với tham số không tồn tại: " + pairWith);
                Map<String, Object> schema = asMap(row.get("parameters_schema"));
                assertTrue(csvSize(schema.get(pairWith)) == csvSize(schema.get(param.get("name"))),
                        row.get("runner") + ": " + param.get("name") + " và " + pairWith
                                + " phải có cùng số phần tử ở giá trị mặc định");
            }
        }
    }

    private int csvSize(Object value) {
        return value == null ? 0 : String.valueOf(value).split(",").length;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
