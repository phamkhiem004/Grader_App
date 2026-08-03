package com.example.grader.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHÂN LOẠI LỖI TESTCASE — biến log THÔ của {@code flutter test --machine} thành bộ ba sạch
 * {@code { code, actual, message }} dùng chung cho MỌI đề (chuẩn hoá ở backend, không sửa grader.dart).
 *
 *  - {@code code}    : mã loại lỗi (xem CATALOG bên dưới) — để FE tô màu & lọc nhóm lỗi.
 *  - {@code actual}  : KẾT QUẢ THỰC TẾ gọn (giá trị/finder/exception) — để so với {@code expected} (rubric).
 *  - {@code message} : LÝ DO/giải thích. Ưu tiên {@code reason:} của testcase; nếu trống → câu mô tả
 *                      mặc định theo từng {@code code} (nên luôn có message dù testcase không khai reason).
 *
 * Cơ chế: tách các mảnh (Expected/Actual/reason) → duyệt CATALOG theo thứ tự ƯU TIÊN (cụ thể → chung),
 * lấy luật đầu tiên khớp. Muốn thêm loại lỗi mới: thêm 1 {@link Rule} vào {@link #buildCatalog()}.
 */
public final class TestErrorClassifier {

    /** Kết quả phân loại 1 lỗi. */
    public record Result(String code, String actual, String message) {}

    // ── Các mảnh tách ra từ log thô ────────────────────────────────
    static final class Parsed {
        String raw = "", low = "";
        String expectedVal, actualVal;            // phần sau "Expected:" / "Actual:"
        final List<String> reasonLines = new ArrayList<>();
        String reason = "";
        boolean has(String kw)            { return low.contains(kw); }
        String  messageOr(String fallbk)  { return reason.isBlank() ? fallbk : reason; }
    }

    record Rule(Predicate<Parsed> when, Function<Parsed, Result> build) {}

    private final List<Rule> catalog = buildCatalog();

    /** API chính: log thô → { code, actual, message }. */
    public Result classify(String raw) {
        Parsed p = parse(raw == null ? "" : raw);
        for (Rule r : catalog) if (r.when().test(p)) return r.build().apply(p);
        String actual = cleanValue(p.actualVal);
        return new Result("ASSERTION_FAILED", actual.isBlank() ? null : actual,
                p.messageOr("Kết quả không khớp yêu cầu."));
    }

    // ════════════════════════════════════════════════════════════════════
    //  CATALOG — danh mục luật theo thứ tự ƯU TIÊN (cụ thể nhất ở trên)
    // ════════════════════════════════════════════════════════════════════
    private List<Rule> buildCatalog() {
        List<Rule> c = new ArrayList<>();

        // ── Lỗi UI/layout & thời gian (đặc thù, nhận diện trước) ──
        // Lưu ý: nhóm exception/UI-crash dùng message CỐ ĐỊNH (hướng dẫn tiếng Việt) — KHÔNG lấy
        // `reason` vì với exception, các dòng còn lại là DUMP tiếng Anh (đã nằm ở `actual`).
        c.add(rule(p -> p.has("overflowed by") || p.has("renderflex overflowed"),
                p -> new Result("LAYOUT_OVERFLOW", "Giao diện bị tràn (overflow)",
                        "Bố cục tràn khung — cần cuộn/giới hạn kích thước (vd Expanded, SingleChildScrollView).")));

        c.add(rule(p -> p.has("pumpandsettle timed out") || p.has("timed out") || p.has("timeout after"),
                p -> new Result("TIMEOUT", "Quá thời gian chờ UI ổn định",
                        "Animation/loading không kết thúc — có thể lặp vô hạn hoặc thiếu điều kiện dừng.")));

        // ── Exception runtime CỤ THỂ (ưu tiên trước exception chung) ──
        c.add(rule(p -> p.has("null check operator used on a null value")
                     || p.has("unexpectedly null") || p.has("type 'null' is not a subtype"),
                p -> new Result("NULL_ERROR", excActual(p),
                        "Truy cập giá trị null — biến chưa khởi tạo hoặc thiếu xử lý null-safety.")));

        c.add(rule(p -> p.has("couldn't resolve the package")
                     || p.has("target of uri doesn't exist") || p.has("uri doesn't exist")
                     || p.has("error when reading"),
                p -> new Result("COMPILE_ERROR", excActual(p),
                        "Không biên dịch được mã nguồn hoặc dependency nên testcase chưa thể chạy.")));

        c.add(rule(p -> p.has("rangeerror") || p.has("index out of range") || p.has("out of range")
                     || p.has("invalid value: not in inclusive range"),
                p -> new Result("RANGE_ERROR", excActual(p),
                        "Truy cập phần tử ngoài phạm vi (index sai hoặc danh sách rỗng).")));

        c.add(rule(p -> p.has("is not a subtype of type") || p.has("type cast")
                     || p.has("_typeerror") || p.has("is not a subtype"),
                p -> new Result("TYPE_ERROR", excActual(p),
                        "Sai kiểu dữ liệu (ép kiểu/khai báo kiểu không khớp).")));

        c.add(rule(p -> p.has("nosuchmethoderror") || p.has("no such method") || p.has("isn't defined"),
                p -> new Result("NO_SUCH_METHOD", excActual(p),
                        "Gọi hàm/thuộc tính không tồn tại (sai tên hoặc chưa định nghĩa trong lib/).")));

        c.add(rule(p -> p.has("formatexception"),
                p -> new Result("FORMAT_ERROR", excActual(p),
                        "Lỗi định dạng/parse dữ liệu (vd parse số/ngày sai định dạng).")));

        c.add(rule(p -> p.has("bad state") || p.has("stateerror") || p.has("concurrent modification"),
                p -> new Result("STATE_ERROR", excActual(p),
                        "Trạng thái không hợp lệ (vd sửa list khi đang duyệt, stream đã đóng).")));

        c.add(rule(p -> p.has("assertion was thrown building")
                     || (p.has("was thrown building") && p.has("assert")),
                p -> new Result("BUILD_ERROR", excActual(p),
                        "Lỗi khi dựng widget — sai tham số hoặc thiếu thuộc tính bắt buộc.")));

        // ── Widget finder: phân biệt THỪA / THIẾU / SAI SỐ LƯỢNG ──
        c.add(rule(p -> isFinder(p) && expectsNone(p) && foundCount(p) > 0,
                p -> new Result("WIDGET_UNEXPECTED",
                        "Tìm thấy " + foundCount(p) + " widget (đáng lẽ KHÔNG có)",
                        p.messageOr("Có widget/nhãn xuất hiện dù không được phép."))));

        c.add(rule(p -> isFinder(p) && foundCount(p) > 0,
                p -> new Result("WIDGET_COUNT",
                        "Tìm thấy " + foundCount(p) + " widget (không khớp số lượng cần)",
                        p.messageOr("Số lượng widget hiển thị không đúng yêu cầu."))));

        c.add(rule(TestErrorClassifier::isFinder,           // còn lại: 0 hoặc không rõ → coi như thiếu
                p -> new Result("WIDGET_NOT_FOUND", "Không tìm thấy widget/nhãn nào khớp",
                        p.messageOr("Thiếu hoặc sai widget/nhãn UI mà đề yêu cầu."))));

        // ── Exception CHUNG (sau khi đã thử các loại cụ thể) ──
        c.add(rule(p -> p.has("exception caught") || p.has("was thrown")
                     || p.has("see exception logs above") || p.has("unhandled exception"),
                p -> new Result("EXCEPTION_THROWN", excActual(p),
                        "Code ném lỗi khi chạy — kiểm tra null/ép kiểu/parse/logic trong lib/.")));

        // ── So khớp giá trị (scalar) ──
        c.add(rule(p -> p.expectedVal != null && p.actualVal != null,
                p -> new Result("VALUE_MISMATCH", cleanValue(p.actualVal),
                        p.messageOr("Giá trị trả về không đúng yêu cầu."))));

        return c;
    }

    private static Rule rule(Predicate<Parsed> when, Function<Parsed, Result> build) {
        return new Rule(when, build);
    }

    // ── Parse log thô: cắt stack/khung framework, tách Expected/Actual + reason ──
    private Parsed parse(String raw) {
        Parsed p = new Parsed();
        p.raw = raw; p.low = raw.toLowerCase();
        boolean skipTestDescriptionValue = false;
        for (String line : raw.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) continue;

            // Flutter/dart:test thường chèn tên testcase vào error event như:
            // "The test description was:\n  TC_...". Đây là metadata của test,
            // không phải actual/exception. Bỏ cả dòng nhãn và dòng giá trị kế tiếp.
            String tl = t.toLowerCase();
            if (tl.startsWith("the test description was")) {
                skipTestDescriptionValue = !tl.contains(":") || tl.endsWith(":");
                continue;
            }
            if (skipTestDescriptionValue) {
                skipTestDescriptionValue = false;
                continue;
            }
            if (t.startsWith("When the exception was thrown") || t.equals("this was the stack:")
                    || t.startsWith("The relevant error-causing widget")) break;     // bỏ phần stack/cây widget
            if (t.startsWith("#") || t.startsWith("package:") || t.contains("(package:flutter")
                    || t.matches("[=═╔╚║╟╢─━].*")) continue;                          // bỏ stack-frame/khung kẻ
            if (tl.startsWith("test failed. see exception logs above")
                    || tl.matches("the following .*was thrown running a test:?")) continue;   // boilerplate
            if (tl.startsWith("expected:"))   p.expectedVal = after(t);
            else if (tl.startsWith("actual:")) p.actualVal  = after(t);
            else if (tl.startsWith("which:"))  { /* bỏ dòng matcher tiếng Anh */ }
            else if (!t.equals("..."))         p.reasonLines.add(t);
        }
        p.reason = String.join(" ", p.reasonLines).strip();
        return p;
    }

    // ── Helpers ────────────────────────────────────────────────────
    private static String after(String t) { return t.substring(t.indexOf(':') + 1).strip(); }
    private static String low(String s)    { return s == null ? "" : s.toLowerCase(); }

    /** Có phải lỗi của một Finder (kiểm tra widget) không? */
    private static boolean isFinder(Parsed p) {
        String a = low(p.actualVal), e = low(p.expectedVal);
        return e.contains("matching candidate")
            || a.contains("finder:<") || a.contains("widgets with") || a.contains("widget with")
            || (a.contains("found ") && a.contains("widget"));
    }

    /** Testcase mong đợi KHÔNG có widget (findsNothing)? */
    private static boolean expectsNone(Parsed p) {
        String e = low(p.expectedVal);
        return e.contains("no matching") || e.contains("zero") || e.contains("nothing");
    }

    /** Số widget thực tế Finder tìm thấy (-1 nếu không xác định). */
    private static int foundCount(Parsed p) {
        String a = p.actualVal;
        if (a != null) {
            Matcher m = Pattern.compile("(?i)found\\s+(\\d+)\\s+widget").matcher(a);
            if (m.find()) return Integer.parseInt(m.group(1));
            if (a.toLowerCase().contains("zero widget")) return 0;
        }
        return -1;
    }

    /** "Kết quả thực tế" cho exception; tuyệt đối không trả lại tên testcase. */
    private static String excActual(Parsed p) {
        String line = bestExceptionLine(p.reasonLines);
        if (line.isBlank()) return "Không có giá trị actual — testcase dừng do exception";
        Matcher m = Pattern.compile("(?i)was thrown building\\s+([A-Za-z0-9_]+)").matcher(line);
        if (m.find()) return "Exception khi dựng widget " + m.group(1);

        // Với wrapper của dart:test, chỉ lấy loại exception (ví dụ TestFailure),
        // không lấy phần "The test description was: <test_id>".
        m = Pattern.compile("(?i)the following\\s+(.+?)\\s+was thrown").matcher(line);
        if (m.find()) return "Exception: " + shorten(m.group(1), 120);
        return "Exception: " + shorten(line, 160);
    }

    /** Dòng exception giàu thông tin nhất: ưu tiên dòng CHI TIẾT nêu rõ loại lỗi (bỏ dòng bọc). */
    private static String bestExceptionLine(List<String> lines) {
        for (String t : lines) {                          // 1) dòng chi tiết, BỎ dòng bọc "The following…"
            String tl = t.toLowerCase();
            if (tl.startsWith("the following") || tl.startsWith("the test description was")) continue;
            if (tl.contains("is not a subtype") || tl.contains("rangeerror") || tl.contains("nosuchmethod")
                    || tl.contains("null check") || tl.contains("formatexception") || tl.contains("bad state")
                    || tl.contains("out of range") || tl.contains("unsupported")
                    || tl.contains("overflowed") || tl.contains("stack overflow")) return t;
        }
        for (String t : lines)                            // 2) dòng bọc "The following X was thrown building Y"
            if (t.toLowerCase().matches("the following .*was thrown.*")) return t;
        for (String t : lines) {                           // 3) bỏ metadata còn sót từ log cũ
            String tl = t.toLowerCase();
            if (!tl.startsWith("the test description was") && !tl.startsWith("test description:")) return t;
        }
        return "";                                         // không có actual/exception detail đáng tin cậy
    }

    /** Bỏ ngoặc nhọn của giá trị matcher: "&lt;1&gt;" → "1". */
    private static String cleanValue(String v) {
        if (v == null) return "";
        String s = v.strip();
        if (s.length() >= 2 && s.startsWith("<") && s.endsWith(">")) s = s.substring(1, s.length() - 1).strip();
        return shorten(s, 200);
    }

    /** Gộp khoảng trắng + cắt ngắn để tránh phình JSON lưu DB/trả API. */
    static String shorten(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").strip();
        return s.length() > max ? s.substring(0, max).strip() + "…" : s;
    }
}
