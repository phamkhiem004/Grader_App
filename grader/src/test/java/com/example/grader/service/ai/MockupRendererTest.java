package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bản vẽ minh họa là thứ SINH VIÊN nhìn để biết phải gắn ValueKey nào vào widget nào — vẽ sai
 * hoặc thiếu key là cả lớp gắn sai key rồi trượt oan. Test chốt: mọi key khai trong bản mô tả
 * đều phải có mặt trên hình, và hình phải là SVG hợp lệ.
 */
class MockupRendererTest {

    private static final String SPEC = """
            {
              "screens": [
                {
                  "id": "home",
                  "title": "Màn hình danh sách (HomeScreen)",
                  "appBar": "User Manager",
                  "appBarKey": "text.screen.title",
                  "nodes": [
                    {"type":"textfield","label":"Họ và tên","hint":"Nhập họ và tên","key":"field.fullName"},
                    {"type":"textfield","label":"Email","hint":"example@gmail.com","key":"field.email"},
                    {"type":"textfield","label":"Avatar","hint":"Chọn ảnh","key":"field.avatar"},
                    {"type":"error","label":"Email không đúng định dạng","key":"error.email"},
                    {"type":"button","label":"Add User","key":"action.save"},
                    {"type":"list","label":"Danh sách người dùng","key":"list.items",
                     "items":[
                       {"label":"Nguyễn Văn An","sub":"an.nguyen@gmail.com","key":"item.1",
                        "actions":[{"label":"Sửa","icon":"edit","key":"action.item.edit"},
                                   {"label":"Xóa","icon":"delete","key":"action.delete"}]},
                       {"label":"Trần Thị Bình","sub":"binh.tran@gmail.com","key":"item.2"}
                     ]}
                  ]
                }
              ]
            }
            """;

    private JsonNode spec() throws Exception {
        return new ObjectMapper().readTree(SPEC);
    }

    @Test
    void veDuMoiKeyVaXuatSvgHopLe() throws Exception {
        List<Map<String, Object>> screens = new MockupRenderer().render(spec());

        assertEquals(1, screens.size(), "Mỗi màn hình trong bản mô tả phải ra đúng một hình");
        Map<String, Object> screen = screens.get(0);
        String svg = String.valueOf(screen.get("svg"));

        assertTrue(svg.startsWith("<svg "), "Phải là tài liệu SVG");
        assertTrue(svg.endsWith("</svg>"), "SVG phải được đóng thẻ");
        assertTrue(svg.contains("marker-end=\"url(#arrow)\""), "Phải có mũi tên chỉ vào widget");
        assertTrue(svg.contains("#e11d48"), "Mũi tên và chú thích phải màu đỏ");

        // Mọi key khai trong spec phải xuất hiện cả trong danh sách trả về lẫn trên hình.
        for (String key : List.of("text.screen.title", "field.fullName", "field.email", "field.avatar",
                "error.email", "action.save", "list.items", "item.1", "item.2",
                "action.item.edit", "action.delete")) {
            assertTrue(svg.contains(">" + key + "<"), "Hình thiếu chú thích cho key " + key);
        }
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) screen.get("keys");
        assertEquals(11, keys.size(), "Phải liệt kê đủ key đã vẽ: " + keys);
    }

    @Test
    void chongXssVaKyTuDacBietTrongNhan() throws Exception {
        JsonNode spec = new ObjectMapper().readTree("""
                {"screens":[{"id":"x","title":"A & B <script>","nodes":[
                  {"type":"text","label":"5 < 10 & \\"ok\\"","key":"text.note"}]}]}
                """);
        String svg = String.valueOf(new MockupRenderer().render(spec).get(0).get("svg"));

        assertFalse(svg.contains("<script>"), "Nhãn phải được escape, không được chèn thẻ vào SVG");
        assertTrue(svg.contains("&amp;") && svg.contains("&lt;"), "Ký tự đặc biệt phải được escape");
    }

    @Test
    void banMoTaTrongThiKhongVeGiCa() throws Exception {
        assertTrue(new MockupRenderer().render(new ObjectMapper().readTree("{}")).isEmpty());
        assertTrue(new MockupRenderer().render(null).isEmpty());
    }

    /** Xuất một bản mẫu ra target/ để xem bằng mắt khi cần chỉnh phong cách vẽ. */
    @Test
    void xuatBanMauDeXemBangMat() throws Exception {
        String svg = String.valueOf(new MockupRenderer().render(spec()).get(0).get("svg"));
        Path out = Path.of("target", "mockup-preview.svg");
        Files.createDirectories(out.getParent());
        Files.writeString(out, svg, StandardCharsets.UTF_8);
        assertTrue(Files.size(out) > 500);
    }
}
