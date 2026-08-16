package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vẽ hình minh họa giao diện từ bản mô tả bố cục của AI, kèm MŨI TÊN ĐỎ chỉ vào từng thành phần
 * và chú thích Item Key phải gắn cho thành phần đó.
 *
 * <p>AI chỉ mô tả CÓ GÌ (loại widget, nhãn, key) — toạ độ do lớp này tự tính. Nếu để AI tự sinh
 * SVG hoặc tự đặt toạ độ thì hình hay bị chồng chữ, mũi tên trỏ lệch, và mỗi lần sinh một kiểu;
 * tách như vậy thì hình luôn vẽ được và luôn cùng một phong cách.
 */
@Component
public class MockupRenderer {

    // Khung điện thoại
    private static final int MARGIN = 24;
    private static final int PHONE_W = 300;
    private static final int PAD = 16;
    private static final int APPBAR_H = 48;
    private static final int GAP = 12;
    private static final int LANE = 28;           // làn dọc sát khung máy: mọi mũi tên đi qua đây
    private static final int LABEL_GAP = 96;      // từ mép phải khung máy tới cột chú thích
    /** Cao hơn tổng chiều cao một chú thích (key 13px + mô tả 11px + đệm) → chữ không thể chồng nhau. */
    private static final int LABEL_MIN_SPACING = 44;
    private static final int CHIP_PAD_X = 8;      // đệm ngang trong khung nền của chú thích
    private static final int BADGE_R = 9;         // số thứ tự gắn ở widget và ở đầu chú thích
    /** Hai mũi tên vào làn cách nhau tối thiểu ngần này → không có hai đoạn nằm đè lên nhau. */
    private static final int LANE_MIN_GAP = 13;
    // Ước lượng bề rộng chữ. Rộng dư thì chỉ tốn chỗ trắng, hụt thì chữ tràn khỏi nền → luôn ước dư.
    private static final double KEY_CHAR_W = 8.0;   // Consolas/Courier 13px
    private static final double SUB_CHAR_W = 6.3;   // Segoe UI 11px

    /** Một chú thích: mũi tên từ (x,y) của widget sang cột chữ bên phải. */
    private record Note(double x, double y, double h, String key, String label) {}

    /**
     * @param spec JSON: { screens: [ { id, title, appBar, nodes: [...] } ] }
     * @return danh sách màn hình đã vẽ: { id, title, svg, keys: [...] }
     */
    public List<Map<String, Object>> render(JsonNode spec) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (spec == null) return out;
        JsonNode screens = spec.path("screens");
        if (!screens.isArray() || screens.isEmpty()) return out;

        int index = 0;
        for (JsonNode screen : screens) {
            index++;
            String id = text(screen.path("id"), "screen" + index);
            String title = text(screen.path("title"), "Màn hình " + index);
            Set<String> keys = new LinkedHashSet<>();
            String svg = renderScreen(screen, title, keys);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", safeFileId(id));
            row.put("title", title);
            row.put("svg", svg);
            row.put("keys", new ArrayList<>(keys));
            out.add(row);
        }
        return out;
    }

    private String renderScreen(JsonNode screen, String title, Set<String> keys) {
        StringBuilder body = new StringBuilder();
        List<Note> notes = new ArrayList<>();

        double phoneX = MARGIN;
        double phoneY = MARGIN + 34;          // chừa chỗ cho tiêu đề hình
        double contentX = phoneX + PAD;
        double contentW = PHONE_W - PAD * 2;
        double y = phoneY + APPBAR_H + GAP;

        // App bar. Chú thích phải ghi sổ TRƯỚC khi xếp chỗ cột chú thích, nếu không key tiêu đề
        // màn hình bị bỏ quên (vẽ sau lúc đã chốt vị trí).
        String appBar = text(screen.path("appBar"), title);
        String appBarKey = text(screen.path("appBarKey"), null);
        if (appBarKey != null && !appBarKey.isBlank()) {
            keys.add(appBarKey);
            notes.add(new Note(phoneX + PHONE_W, phoneY + APPBAR_H / 2.0, APPBAR_H, appBarKey,
                    "Tiêu đề màn hình"));
        }

        for (JsonNode node : screen.path("nodes")) {
            y = drawNode(body, notes, keys, node, contentX, y, contentW);
            y += GAP;
        }

        double phoneH = Math.max(360, y - phoneY + PAD);
        // Xếp chú thích TRƯỚC khi chốt kích thước: nhiều key thì cột chú thích dài hơn khung máy,
        // phải nới hình cho vừa. Trước đây kẹp vào đáy nên các nhãn cuối chồng đè lên nhau.
        List<Placed> placed = layoutNotes(notes);
        double notesBottom = placed.isEmpty() ? 0 : placed.get(placed.size() - 1).bottom();
        double svgH = Math.max(phoneY + phoneH + MARGIN + 16, notesBottom + MARGIN);
        // Bề rộng bám theo chú thích DÀI NHẤT: cột rộng cố định thì key dài bị cắt cụt ở mép hình.
        double widestChip = placed.stream().mapToDouble(Placed::width).max().orElse(0);
        double svgW = Math.max(MARGIN + PHONE_W + LABEL_GAP + widestChip + MARGIN,
                MARGIN + PHONE_W + LABEL_GAP + 220 + MARGIN);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
           .append(fmt(svgW)).append(' ').append(fmt(svgH))
           .append("\" width=\"").append(fmt(svgW)).append("\" height=\"").append(fmt(svgH)).append("\">");
        svg.append("<defs><marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" "
                + "markerWidth=\"7\" markerHeight=\"7\" orient=\"auto-start-reverse\">"
                + "<path d=\"M0,0 L10,5 L0,10 z\" fill=\"#e11d48\"/></marker></defs>");
        svg.append("<style>text{font-family:'Segoe UI',Roboto,Arial,sans-serif}</style>");
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>");

        // Tiêu đề hình
        svg.append(textEl(MARGIN, MARGIN + 16, esc(title), 15, "#0f172a", "bold", "start"));

        // Khung máy + app bar
        svg.append("<rect x=\"").append(fmt(phoneX)).append("\" y=\"").append(fmt(phoneY))
           .append("\" width=\"").append(PHONE_W).append("\" height=\"").append(fmt(phoneH))
           .append("\" rx=\"22\" fill=\"#f8fafc\" stroke=\"#0f172a\" stroke-width=\"2\"/>");
        svg.append("<path d=\"M").append(fmt(phoneX)).append(',').append(fmt(phoneY + 22))
           .append(" a22,22 0 0 1 22,-22 h").append(PHONE_W - 44).append(" a22,22 0 0 1 22,22 v")
           .append(APPBAR_H - 22).append(" h-").append(PHONE_W).append(" z\" fill=\"#1d4ed8\"/>");
        svg.append(textEl(phoneX + PAD, phoneY + 31, esc(appBar), 14, "#ffffff", "bold", "start"));

        svg.append(body);
        svg.append(drawNotes(placed, phoneX + PHONE_W));
        svg.append("</svg>");
        return svg.toString();
    }

    /** Vẽ một thành phần, trả về y sau khi vẽ xong. */
    private double drawNode(StringBuilder svg, List<Note> notes, Set<String> keys,
                            JsonNode node, double x, double y, double w) {
        String type = text(node.path("type"), "text").toLowerCase();
        String label = text(node.path("label"), "");
        String key = text(node.path("key"), null);
        double h;
        // Mép phải THẬT của thành phần. Ô nhập/nút chiếm trọn bề ngang nên bằng x+w, còn ô tick hay
        // dòng chữ ngắn thì hẹp hơn nhiều — neo mũi tên vào x+w là chỉ vào khoảng trắng bên cạnh.
        double anchorX = x + w;

        switch (type) {
            case "textfield", "input", "field" -> {
                h = 52;
                String hint = text(node.path("hint"), "Nhập " + label.toLowerCase());
                svg.append(box(x, y, w, h, 10, "#ffffff", "#cbd5e1"));
                svg.append(textEl(x + 10, y + 17, esc(label), 10, "#64748b", "normal", "start"));
                svg.append(textEl(x + 10, y + 36, esc(hint), 12, "#94a3b8", "normal", "start"));
            }
            case "button", "action" -> {
                h = 44;
                svg.append(box(x, y, w, h, 10, "#1d4ed8", "#1d4ed8"));
                svg.append(textEl(x + w / 2, y + 27, esc(label.toUpperCase()), 13, "#ffffff", "bold", "middle"));
            }
            case "list" -> {
                double startY = y;
                if (!label.isBlank()) {
                    svg.append(textEl(x, y + 12, esc(label), 11, "#475569", "bold", "start"));
                    y += 20;
                }
                double listTop = y;
                double rowY = y;
                int shown = 0;
                for (JsonNode item : node.path("items")) {
                    if (shown >= 3) break;                    // 3 dòng là đủ minh họa
                    rowY = drawListItem(svg, notes, keys, item, x, rowY, w) + 8;
                    shown++;
                }
                if (shown == 0) {                              // danh sách rỗng vẫn phải thấy được khung
                    svg.append(box(x, rowY, w, 56, 10, "#ffffff", "#cbd5e1"));
                    rowY += 56;
                }
                double listH = rowY - listTop;
                svg.append("<rect x=\"").append(fmt(x - 4)).append("\" y=\"").append(fmt(listTop - 4))
                   .append("\" width=\"").append(fmt(w + 8)).append("\" height=\"").append(fmt(listH + 8))
                   .append("\" rx=\"12\" fill=\"none\" stroke=\"#94a3b8\" stroke-dasharray=\"4 3\"/>");
                if (key != null) {
                    keys.add(key);
                    // Neo ở góc TRÊN-PHẢI của cả khung danh sách: neo vào giữa thì số thứ tự của
                    // danh sách rơi trúng số của item đứng giữa, hai vòng tròn đè lên nhau.
                    notes.add(new Note(x + w + 4, listTop + 6, listH, key,
                            label.isBlank() ? "Danh sách" : label));
                }
                return rowY;
            }
            case "image", "avatar" -> {
                h = 84;
                svg.append(box(x, y, 84, h, 10, "#ffffff", "#cbd5e1"));
                svg.append("<path d=\"M").append(fmt(x + 16)).append(',').append(fmt(y + 62))
                   .append(" l18,-22 l14,16 l10,-12 l16,18 z\" fill=\"#cbd5e1\"/>");
                svg.append("<circle cx=\"").append(fmt(x + 28)).append("\" cy=\"").append(fmt(y + 26))
                   .append("\" r=\"7\" fill=\"#cbd5e1\"/>");
                if (!label.isBlank())
                    svg.append(textEl(x + 96, y + 46, esc(label), 12, "#475569", "normal", "start"));
                anchorX = x + 84;
            }
            case "checkbox", "switch" -> {
                h = 32;
                svg.append(box(x, y + 6, 20, 20, 4, "#ffffff", "#94a3b8"));
                svg.append(textEl(x + 30, y + 21, esc(label), 12, "#334155", "normal", "start"));
                anchorX = Math.min(x + w, x + 30 + textWidth(label, 6.4));
            }
            case "error" -> {
                h = 24;
                String message = label.isBlank() ? "Thông báo lỗi" : label;
                svg.append(textEl(x, y + 16, esc(message), 11, "#dc2626", "normal", "start"));
                anchorX = Math.min(x + w, x + textWidth(message, 5.9));
            }
            default -> {                                       // text, title, message…
                h = 26;
                svg.append(textEl(x, y + 17, esc(label), 13, "#0f172a",
                        "title".equals(type) ? "bold" : "normal", "start"));
                anchorX = Math.min(x + w, x + textWidth(label, 7.0));
            }
        }

        if (key != null) {
            keys.add(key);
            notes.add(new Note(anchorX, y + h / 2, h, key, label.isBlank() ? type : label));
        }
        return y + h;
    }

    /** Ước lượng bề ngang một dòng chữ đã vẽ, để neo mũi tên vào đúng mép chữ. */
    private double textWidth(String label, double perChar) {
        return (label == null ? 0 : label.length()) * perChar;
    }

    /** Một dòng trong danh sách: avatar + 2 dòng chữ + các nút hành động bên phải. */
    private double drawListItem(StringBuilder svg, List<Note> notes, Set<String> keys,
                                JsonNode item, double x, double y, double w) {
        double h = 56;
        String label = text(item.path("label"), "Mục dữ liệu");
        String sub = text(item.path("sub"), "");
        String key = text(item.path("key"), null);

        svg.append(box(x, y, w, h, 10, "#ffffff", "#e2e8f0"));
        svg.append("<circle cx=\"").append(fmt(x + 26)).append("\" cy=\"").append(fmt(y + 28))
           .append("\" r=\"16\" fill=\"#e2e8f0\"/>");
        svg.append(textEl(x + 50, y + 24, esc(label), 12, "#0f172a", "bold", "start"));
        if (!sub.isBlank())
            svg.append(textEl(x + 50, y + 41, esc(sub), 10, "#64748b", "normal", "start"));

        // Nút hành động xếp từ phải sang trái
        double ax = x + w - 22;
        List<JsonNode> actions = new ArrayList<>();
        item.path("actions").forEach(actions::add);
        for (int i = actions.size() - 1; i >= 0; i--) {
            JsonNode action = actions.get(i);
            String icon = text(action.path("icon"), text(action.path("label"), "")).toLowerCase();
            String actionKey = text(action.path("key"), null);
            svg.append(icon(ax - 8, y + 20, icon));
            if (actionKey != null) {
                keys.add(actionKey);
                // Mũi tên của nút nhỏ đi thẳng từ chính nút đó, không phải từ cạnh dòng, để
                // người đọc thấy rõ key gắn vào NÚT chứ không phải vào cả item.
                // Neo lệch sang phải icon một chút: đặt đúng tâm icon thì số thứ tự che mất hình
                // cái bút / thùng rác, mà chính hình đó mới nói nút này làm gì.
                notes.add(new Note(ax + 11, y + 28, 16, actionKey,
                        text(action.path("label"), "Nút trong mục")));
            }
            ax -= 30;
        }

        if (key != null) {
            keys.add(key);
            // Góc dưới-phải của dòng: các nút hành động đã chiếm giữa dòng bên phải.
            notes.add(new Note(x + w + 2, y + h - 8, h, key, label));
        }
        return y + h;
    }

    /**
     * Một chú thích đã chốt vị trí: {@code laneY} là chỗ mũi tên rẽ khỏi làn dọc, {@code labelY} là
     * dòng chữ key, {@code bottom} là mép dưới (để tính chiều cao hình).
     */
    private record Placed(Note note, int number, double laneY, double labelY, double bottom, double width) {}

    /**
     * Xếp chỗ cho cột chú thích. Hai ràng buộc phải giữ cùng lúc:
     *
     * <ul>
     *   <li>Chữ không đè nhau → mỗi nhãn cách nhãn trước ít nhất {@code LABEL_MIN_SPACING}.</li>
     *   <li>Mũi tên không đè nhau → điểm rẽ vào làn (laneY) cũng phải cách nhau. Hai nút cùng một
     *       dòng danh sách có ĐÚNG một y, trước đây hai mũi tên nằm chồng khít lên nhau nên nhìn
     *       như một đường; giờ tách ra nên vẫn lần ra được nút nào ứng với key nào.</li>
     * </ul>
     *
     * <p>Cả laneY và labelY đều tăng dần theo thứ tự đã sắp, nên các đoạn chéo nối hai cột không
     * thể cắt nhau — đây là điểm khiến hình hết rối khi đề có nhiều key.
     */
    private List<Placed> layoutNotes(List<Note> notes) {
        List<Note> sorted = new ArrayList<>(notes);
        sorted.sort((a, b) -> a.y() != b.y() ? Double.compare(a.y(), b.y()) : Double.compare(a.x(), b.x()));

        List<Placed> out = new ArrayList<>();
        double lastLane = -100;
        double lastBottom = -100;
        int number = 0;
        for (Note note : sorted) {
            number++;
            double laneY = Math.max(note.y(), lastLane + LANE_MIN_GAP);
            double labelY = Math.max(laneY, lastBottom + LABEL_MIN_SPACING);
            boolean hasSub = note.label() != null && !note.label().isBlank();
            double bottom = labelY + (hasSub ? 17 : 0);
            out.add(new Placed(note, number, laneY, labelY, bottom, chipWidth(note)));
            lastLane = laneY;
            lastBottom = bottom;
        }
        return out;
    }

    /** Bề rộng khung nền của một chú thích (đã tính chỗ cho số thứ tự). */
    private double chipWidth(Note note) {
        String sub = shorten(note.label(), 44);
        double text = Math.max(note.key().length() * KEY_CHAR_W,
                sub.isBlank() ? 0 : sub.length() * SUB_CHAR_W);
        return BADGE_R * 2 + 6 + text + CHIP_PAD_X * 2;
    }

    /**
     * Mũi tên đỏ từ chú thích về đúng thành phần, kèm SỐ THỨ TỰ gắn ngay tại thành phần.
     *
     * <p>Đường đi gồm hai chặng: đoạn chéo từ khung chữ về một làn dọc sát khung máy, rồi đoạn
     * ngắn từ làn đâm vào widget. Vì cả hai đầu đều đã sắp tăng dần theo y (xem
     * {@link #layoutNotes}) nên các đoạn chéo không cắt nhau — trước đây mỗi mũi tên bắn thẳng từ
     * cột chữ vào widget, đề nhiều key thì chúng cắt chéo nhau chằng chịt.
     *
     * <p>Số thứ tự là chốt chặn cuối: kể cả khi in mờ hay thu nhỏ, vẫn tra được key nào thuộc
     * thành phần nào mà không cần dò theo đường kẻ.
     *
     * <p>Chữ nằm trong khung nền trắng VÀ có viền trắng quanh nét chữ (paint-order), nên không bao
     * giờ bị đường kẻ cắt ngang dù ước lượng bề rộng có lệch.
     */
    private String drawNotes(List<Placed> placed, double phoneRight) {
        if (placed.isEmpty()) return "";
        double labelX = phoneRight + LABEL_GAP;
        double laneX = phoneRight + LANE;
        StringBuilder svg = new StringBuilder();

        // Vẽ TẤT CẢ mũi tên trước, rồi mới tới khung chữ: khung chữ luôn nằm trên, không bị cắt.
        for (Placed p : placed) {
            Note note = p.note();
            double fromX = labelX - CHIP_PAD_X - 4;      // mép trái khung chú thích
            double fromY = p.labelY() - 4;               // giữa dòng chữ key
            svg.append("<path d=\"M").append(fmt(fromX)).append(',').append(fmt(fromY))
               .append(" L").append(fmt(laneX)).append(',').append(fmt(p.laneY()))
               .append(" L").append(fmt(note.x() + BADGE_R + 2)).append(',').append(fmt(note.y()))
               .append("\" fill=\"none\" stroke=\"#e11d48\" stroke-width=\"1.5\" "
                       + "stroke-linejoin=\"round\" marker-end=\"url(#arrow)\"/>");
        }

        // Số thứ tự gắn tại widget — vẽ sau mũi tên để luôn nằm trên.
        for (Placed p : placed) {
            Note note = p.note();
            svg.append("<circle cx=\"").append(fmt(note.x())).append("\" cy=\"").append(fmt(note.y()))
               .append("\" r=\"").append(BADGE_R).append("\" fill=\"#e11d48\" stroke=\"#ffffff\" stroke-width=\"1.6\"/>");
            svg.append("<text x=\"").append(fmt(note.x())).append("\" y=\"").append(fmt(note.y() + 3.6))
               .append("\" font-size=\"10\" fill=\"#ffffff\" font-weight=\"bold\" text-anchor=\"middle\">")
               .append(p.number()).append("</text>");
        }

        for (Placed p : placed) {
            Note note = p.note();
            String key = esc(note.key());
            String sub = note.label() == null || note.label().isBlank()
                    ? "" : esc(shorten(note.label(), 44));
            boolean hasSub = !sub.isEmpty();
            double chipY = p.labelY() - 15;
            double chipH = hasSub ? 33 : 21;
            double badgeX = labelX + BADGE_R - 2;
            double textX = labelX + BADGE_R * 2 + 4;

            svg.append("<rect x=\"").append(fmt(labelX - CHIP_PAD_X)).append("\" y=\"").append(fmt(chipY))
               .append("\" width=\"").append(fmt(p.width())).append("\" height=\"").append(fmt(chipH))
               .append("\" rx=\"6\" fill=\"#ffffff\" stroke=\"#fecdd3\"/>");
            svg.append("<circle cx=\"").append(fmt(badgeX)).append("\" cy=\"").append(fmt(p.labelY() - 4))
               .append("\" r=\"").append(BADGE_R).append("\" fill=\"#e11d48\"/>");
            svg.append("<text x=\"").append(fmt(badgeX)).append("\" y=\"").append(fmt(p.labelY() - 0.4))
               .append("\" font-size=\"10\" fill=\"#ffffff\" font-weight=\"bold\" text-anchor=\"middle\">")
               .append(p.number()).append("</text>");
            svg.append("<text x=\"").append(fmt(textX)).append("\" y=\"").append(fmt(p.labelY()))
               .append("\" font-size=\"13\" fill=\"#e11d48\" font-weight=\"bold\" "
                       + "font-family=\"Consolas,'Courier New',monospace\" "
                       + "stroke=\"#ffffff\" stroke-width=\"3\" paint-order=\"stroke\">")
               .append(key).append("</text>");
            if (hasSub) {
                svg.append("<text x=\"").append(fmt(textX)).append("\" y=\"").append(fmt(p.labelY() + 15))
                   .append("\" font-size=\"11\" fill=\"#475569\" "
                           + "stroke=\"#ffffff\" stroke-width=\"3\" paint-order=\"stroke\">")
                   .append(sub).append("</text>");
            }
        }
        return svg.toString();
    }

    // ── Nguyên liệu SVG ──────────────────────────────────────────

    private String box(double x, double y, double w, double h, int r, String fill, String stroke) {
        return "<rect x=\"" + fmt(x) + "\" y=\"" + fmt(y) + "\" width=\"" + fmt(w) + "\" height=\"" + fmt(h)
                + "\" rx=\"" + r + "\" fill=\"" + fill + "\" stroke=\"" + stroke + "\"/>";
    }

    private String textEl(double x, double y, String content, int size, String fill,
                          String weight, String anchor) {
        return "<text x=\"" + fmt(x) + "\" y=\"" + fmt(y) + "\" font-size=\"" + size + "\" fill=\"" + fill
                + "\" font-weight=\"" + weight + "\" text-anchor=\"" + anchor + "\">" + content + "</text>";
    }

    /** Icon vector nhỏ cho nút chỉ có icon (bút sửa, thùng rác, dấu cộng…). */
    private String icon(double x, double y, String kind) {
        String k = kind == null ? "" : kind;
        if (k.contains("edit") || k.contains("sửa") || k.contains("sua") || k.contains("pencil")) {
            return "<path d=\"M" + fmt(x) + "," + fmt(y + 14) + " v-3 l9,-9 l3,3 l-9,9 z\" fill=\"#2563eb\"/>";
        }
        if (k.contains("delete") || k.contains("xóa") || k.contains("xoa") || k.contains("trash")) {
            return "<path d=\"M" + fmt(x + 2) + "," + fmt(y + 4) + " h12 v10 a2,2 0 0 1 -2,2 h-8 "
                    + "a2,2 0 0 1 -2,-2 z\" fill=\"#dc2626\"/>"
                    + "<rect x=\"" + fmt(x) + "\" y=\"" + fmt(y + 1) + "\" width=\"16\" height=\"2.5\" fill=\"#dc2626\"/>";
        }
        if (k.contains("add") || k.contains("thêm") || k.contains("them") || k.contains("plus")) {
            return "<path d=\"M" + fmt(x + 8) + "," + fmt(y + 2) + " v12 M" + fmt(x + 2) + "," + fmt(y + 8)
                    + " h12\" stroke=\"#1d4ed8\" stroke-width=\"2.5\" stroke-linecap=\"round\"/>";
        }
        return "<rect x=\"" + fmt(x + 2) + "\" y=\"" + fmt(y + 3) + "\" width=\"12\" height=\"12\" rx=\"3\""
                + " fill=\"none\" stroke=\"#64748b\" stroke-width=\"1.6\"/>";
    }

    private String shorten(String s, int max) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max - 1) + "…";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String fmt(double v) {
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    private String text(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        String s = node.asText("").trim();
        return s.isEmpty() ? fallback : s;
    }

    /** Tên file an toàn cho SVG lưu trong handout. */
    private String safeFileId(String raw) {
        String s = raw == null ? "screen" : raw.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        s = s.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return s.isEmpty() ? "screen" : s;
    }
}
