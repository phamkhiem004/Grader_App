package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hình minh họa là thứ sinh viên nhìn để biết gắn ValueKey nào vào đâu. Đề thật hay có ~20 key,
 * và chính lúc đông key thì bản vẽ dễ hỏng: chú thích đè chữ lên nhau, số thứ tự chồng nhau, mũi
 * tên cắt chéo chằng chịt không lần ra được đường nào nối với cái gì.
 *
 * <p>Test này soi thẳng vào TOẠ ĐỘ trong SVG thay vì chỉ kiểm tra "có đủ key" — đè nhau là lỗi
 * hình học, mắt người thấy ngay còn assert theo chuỗi thì không bao giờ bắt được.
 */
class MockupRendererLayoutTest {

    /** Đề dày key: 3 ô nhập kèm lỗi, ảnh, checkbox, nút, thông báo và danh sách 3 dòng có nút. */
    private static final String DENSE_SPEC = """
            {
              "screens": [
                {
                  "id": "home",
                  "title": "Màn hình danh sách (HomeScreen)",
                  "appBar": "User Manager",
                  "appBarKey": "text.screen.title",
                  "nodes": [
                    {"type":"textfield","label":"Họ và tên","key":"field.fullName"},
                    {"type":"error","label":"Họ và tên không được để trống","key":"error.fullName"},
                    {"type":"textfield","label":"Email","key":"field.email"},
                    {"type":"error","label":"Email không đúng định dạng","key":"error.email"},
                    {"type":"textfield","label":"Số điện thoại","key":"field.phone"},
                    {"type":"error","label":"Số điện thoại phải có 10 chữ số","key":"error.phone"},
                    {"type":"image","label":"Ảnh đại diện","key":"field.avatar"},
                    {"type":"checkbox","label":"Kích hoạt tài khoản","key":"field.active"},
                    {"type":"button","label":"Add User","key":"action.save"},
                    {"type":"text","label":"Thêm người dùng thành công","key":"message.success"},
                    {"type":"list","label":"Danh sách người dùng","key":"list.items",
                     "items":[
                       {"label":"Nguyễn Văn An","sub":"an@gmail.com","key":"item.1",
                        "actions":[{"label":"Sửa","icon":"edit","key":"action.item.edit"},
                                   {"label":"Xóa","icon":"delete","key":"action.delete"}]},
                       {"label":"Trần Thị Bình","sub":"binh@gmail.com","key":"item.2",
                        "actions":[{"label":"Sửa","icon":"edit","key":"action.item2.edit"},
                                   {"label":"Xóa","icon":"delete","key":"action.item2.delete"}]},
                       {"label":"Lê Văn Cường","sub":"cuong@gmail.com","key":"item.3",
                        "actions":[{"label":"Xem chi tiết","icon":"open","key":"action.open-detail"}]}
                     ]}
                  ]
                }
              ]
            }
            """;

    private record Rect(double x, double y, double w, double h) {
        boolean hits(Rect o) { return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h; }
    }
    private record Dot(double x, double y, double r) {
        boolean hits(Dot o) { return Math.hypot(x - o.x, y - o.y) < r + o.r; }
    }
    private record Seg(double x1, double y1, double x2, double y2, int arrow) {}

    private String render() throws Exception {
        List<Map<String, Object>> screens =
                new MockupRenderer().render(new ObjectMapper().readTree(DENSE_SPEC));
        assertEquals(1, screens.size());
        String svg = String.valueOf(screens.get(0).get("svg"));
        // Xuất ra target/ để xem bằng mắt khi cần chỉnh phong cách vẽ.
        Path out = Path.of("target", "mockup-dense-preview.svg");
        Files.createDirectories(out.getParent());
        Files.writeString(out, svg, StandardCharsets.UTF_8);
        return svg;
    }

    @Test
    void chuThichKhongDeLenNhau() throws Exception {
        List<Rect> chips = chips(render());
        assertEquals(20, chips.size(), "Phải có đúng một khung chú thích cho mỗi key");
        for (int i = 0; i < chips.size(); i++)
            for (int j = i + 1; j < chips.size(); j++)
                assertFalse(chips.get(i).hits(chips.get(j)),
                        "Hai chú thích đè lên nhau: " + chips.get(i) + " và " + chips.get(j));
    }

    @Test
    void soThuTuGanWidgetKhongChongNhau() throws Exception {
        List<Dot> badges = badges(render());
        assertEquals(20, badges.size(), "Mỗi key phải có một số thứ tự gắn tại widget");
        for (int i = 0; i < badges.size(); i++)
            for (int j = i + 1; j < badges.size(); j++)
                assertFalse(badges.get(i).hits(badges.get(j)),
                        "Số thứ tự " + (i + 1) + " và " + (j + 1) + " chồng nhau");
    }

    /** Mũi tên cắt nhau = không dò được key nào thuộc widget nào; đây là lỗi hay bị nhất. */
    @Test
    void muiTenKhongCatCheoNhau() throws Exception {
        List<Seg> segments = arrowSegments(render());
        assertEquals(40, segments.size(), "Mỗi mũi tên gồm đoạn chéo + đoạn đâm vào widget");
        int crossing = 0;
        for (int i = 0; i < segments.size(); i++)
            for (int j = i + 1; j < segments.size(); j++)
                if (segments.get(i).arrow() != segments.get(j).arrow()
                        && crosses(segments.get(i), segments.get(j))) crossing++;
        assertEquals(0, crossing, crossing + " cặp mũi tên cắt chéo nhau");
    }

    @Test
    void chuThichNamGonTrongNenTrang() throws Exception {
        String svg = render();
        List<Rect> chips = chips(svg);
        List<String> texts = keyTexts(svg);
        double widest = 0;
        for (int i = 0; i < texts.size(); i++) {
            // Nền phải rộng hơn chữ (font mono 13px ≈ 8px/ký tự) + chỗ cho vòng số thứ tự.
            double need = texts.get(i).length() * 8.0 + 26;
            assertTrue(chips.get(i).w() >= need,
                    "Nền chú thích hẹp hơn chữ \"" + texts.get(i) + "\": " + chips.get(i).w() + " < " + need);
            widest = Math.max(widest, chips.get(i).x() + chips.get(i).w());
        }
        Matcher m = Pattern.compile("viewBox=\"0 0 ([\\d.]+) ([\\d.]+)\"").matcher(svg);
        assertTrue(m.find());
        assertTrue(Double.parseDouble(m.group(1)) >= widest,
                "Chú thích dài nhất bị cắt mất ở mép hình");
    }

    // ── Bóc toạ độ từ SVG ────────────────────────────────────────

    private List<Rect> chips(String svg) {
        List<Rect> out = new ArrayList<>();
        Matcher m = Pattern.compile("<rect x=\"([\\d.-]+)\" y=\"([\\d.-]+)\" width=\"([\\d.-]+)\" "
                + "height=\"([\\d.-]+)\" rx=\"6\" fill=\"#ffffff\" stroke=\"#fecdd3\"/>").matcher(svg);
        while (m.find())
            out.add(new Rect(num(m.group(1)), num(m.group(2)), num(m.group(3)), num(m.group(4))));
        return out;
    }

    private List<Dot> badges(String svg) {
        List<Dot> out = new ArrayList<>();
        Matcher m = Pattern.compile("<circle cx=\"([\\d.-]+)\" cy=\"([\\d.-]+)\" r=\"(\\d+)\" "
                + "fill=\"#e11d48\" stroke=\"#ffffff\"").matcher(svg);
        while (m.find()) out.add(new Dot(num(m.group(1)), num(m.group(2)), num(m.group(3))));
        return out;
    }

    private List<String> keyTexts(String svg) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("font-size=\"13\" fill=\"#e11d48\"[^>]*>([^<]+)</text>").matcher(svg);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private List<Seg> arrowSegments(String svg) {
        List<Seg> out = new ArrayList<>();
        Matcher m = Pattern.compile("<path d=\"M([\\d.-]+),([\\d.-]+) L([\\d.-]+),([\\d.-]+) "
                + "L([\\d.-]+),([\\d.-]+)\" fill=\"none\" stroke=\"#e11d48\"").matcher(svg);
        int index = 0;
        while (m.find()) {
            out.add(new Seg(num(m.group(1)), num(m.group(2)), num(m.group(3)), num(m.group(4)), index));
            out.add(new Seg(num(m.group(3)), num(m.group(4)), num(m.group(5)), num(m.group(6)), index));
            index++;
        }
        return out;
    }

    /** Hai đoạn thẳng cắt nhau thực sự (chạm đầu mút không tính). */
    private boolean crosses(Seg a, Seg b) {
        double d1 = side(a, b.x1(), b.y1()), d2 = side(a, b.x2(), b.y2());
        double d3 = side(b, a.x1(), a.y1()), d4 = side(b, a.x2(), a.y2());
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private double side(Seg s, double px, double py) {
        return (s.x2() - s.x1()) * (py - s.y1()) - (s.y2() - s.y1()) * (px - s.x1());
    }

    private double num(String raw) { return Double.parseDouble(raw); }
}
