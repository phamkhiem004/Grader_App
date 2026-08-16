package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bản mô tả (mockup_spec / spec khung starter) phải rời máy chủ dưới dạng Map/List THUẦN.
 *
 * <p>Classpath có cả Jackson 2 lẫn Jackson 3 (xem CLAUDE.md). Service dùng JsonNode của Jackson 2,
 * còn bộ chuyển đổi HTTP của Spring Boot 4 là Jackson 3 — nó không nhận ra cây của Jackson 2 nên
 * serialize như một bean thường: client nhận {"array":false,"nodeType":"OBJECT",…} thay vì nội
 * dung thật. Lỗi này KHÔNG lộ ra ở test service (đối tượng trong bộ nhớ vẫn đúng), chỉ lộ khi đã
 * qua HTTP — nên test này serialize lại đúng bằng Jackson 3 để bắt tận nơi.
 *
 * <p>Hậu quả nếu tái phát: mọi thao tác gửi bản mô tả NGƯỢC lên máy chủ (vẽ lại hình, nhờ AI sửa
 * hình, nhờ AI sửa khung starter) đều gãy vì máy chủ nhận lại một object rỗng nghĩa.
 */
class AiAuthorResponseShapeTest {

    private static final String AI_MOCKUP_REPLY = """
            {
              "mockup": {"screens":[{"id":"home","title":"Màn hình chính","appBar":"Demo",
                "nodes":[{"type":"button","label":"Add User","key":"action.save"}]}]},
              "notes": ["Đã bỏ ô số điện thoại"]
            }
            """;

    private AiExamAuthorService service(String llmReply) {
        LlmService llm = mock(LlmService.class);
        try {
            when(llm.chatJson(anyList())).thenReturn(new ObjectMapper().readTree(llmReply));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        AiExamAuthorService service = new AiExamAuthorService();
        ReflectionTestUtils.setField(service, "llm", llm);
        ReflectionTestUtils.setField(service, "mockupRenderer", new MockupRenderer());
        // Lượt sửa hình giờ bóc CẢ danh sách key nên phải hỏi thư viện xem có những cách dò nào.
        // Catalog rỗng là đủ: bộ cách dò rơi về danh sách mặc định trong service.
        com.example.grader.service.TestcaseTemplateService templates =
                mock(com.example.grader.service.TestcaseTemplateService.class);
        when(templates.contractCatalog()).thenReturn(java.util.Map.of());
        ReflectionTestUtils.setField(service, "templateService", templates);
        return service;
    }

    @Test
    void banMoTaHinhTraVeDangMapThuan() throws Exception {
        Map<String, Object> out = service(AI_MOCKUP_REPLY).reviseMockup(
                Map.of("screens", List.of(Map.of("id", "home", "nodes", List.of()))),
                "Bỏ ô số điện thoại",
                Map.of("keys", List.of(Map.of("key", "action.save"))));

        Object spec = out.get("mockup_spec");
        assertFalse(spec instanceof JsonNode,
                "mockup_spec còn là JsonNode của Jackson 2 → qua HTTP sẽ thành object rỗng nghĩa");
        assertInstanceOf(Map.class, spec);

        // Serialize đúng bằng bộ chuyển đổi của Spring Boot 4 (Jackson 3) rồi soi kết quả thật.
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(out);
        assertTrue(json.contains("\"screens\""), "Bản mô tả phải còn nguyên khi ra tới client");
        assertTrue(json.contains("action.save"), "Key trong bản mô tả bị mất khi serialize");
        assertFalse(json.contains("\"nodeType\""),
                "Đây là dấu hiệu JsonNode bị serialize như bean — client sẽ nhận rác");
    }

    @Test
    void hinhVanDuocVeLaiSauKhiAiSua() {
        Map<String, Object> out = service(AI_MOCKUP_REPLY).reviseMockup(
                Map.of("screens", List.of(Map.of("id", "home", "nodes", List.of()))),
                "Thêm nút lưu",
                Map.of("keys", List.of()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> screens = (List<Map<String, Object>>) out.get("screens");
        assertEquals(1, screens.size());
        assertTrue(String.valueOf(screens.get(0).get("svg")).startsWith("<svg "));
        assertEquals(List.of("Đã bỏ ô số điện thoại"), out.get("notes"));
    }

    @Test
    void thieuYeuCauSuaThiBaoLoiChuKhongGoiAi() {
        AiExamAuthorService service = service(AI_MOCKUP_REPLY);
        assertThrows(IllegalArgumentException.class,
                () -> service.reviseMockup(Map.of("screens", List.of()), "  ", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.reviseMockup(null, "sửa gì đó", null));
    }
}
