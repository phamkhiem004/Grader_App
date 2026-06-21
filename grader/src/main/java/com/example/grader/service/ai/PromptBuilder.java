package com.example.grader.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Dựng prompt cho bộ sinh đề (2 pha). Nhúng hợp đồng kỹ thuật của hệ thống chấm (giống
 * docs/prompt-tao-testcase.md) nhưng ÉP đầu ra về 1 object JSON để bóc tách máy móc, BỎ grader.dart
 * (backend tự chèn). Pha A tối đa hóa số testcase + độ phủ syllabus + phân bố điểm; pha B sinh
 * ĐỀ BÀI phát SV + KHUNG CODE starter từ testcase đã chốt.
 */
@Component
public class PromptBuilder {

    /**
     * Cho phép AI sinh WIDGET TEST (render UI) hay không. Mặc định FALSE: chỉ test LOGIC/UNIT thuần — nhanh,
     * luôn PASS, đạt số lượng ổn định (model nhỏ như gpt-4o-mini hay viết widget test lệch solution → fail).
     * Bật TRUE khi dùng model mạnh (gpt-4o/gpt-4.1...) để có thêm test kiểm tra giao diện.
     */
    @Value("${grader.ai.allow-widget-tests:false}")
    private boolean allowWidgetTests;

    /** Danh mục skill_code hợp lệ (SYLLABUS v2026.2) — model chỉ được chọn trong đây. */
    private static final String SKILL_CODES = """
            [DART_ESSENTIALS]   DART_SYNTAX, DART_FUNCTIONS, DART_CLASSES, DART_COLLECTIONS, DART_NULL_SAFETY
            [OOP_ASYNC]         OOP_INHERITANCE, OOP_PATTERNS, OOP_MODEL, ASYNC_FUTURE, ASYNC_STREAM
            [UI_FUNDAMENTALS]   UI_WIDGETS, UI_MATERIAL, UI_LISTS, UI_PICKERS
            [NAV_STATE]         NAV_BASIC, NAV_NAMED, NAV_ADVANCED, STATE_BASIC, STATE_LIFTING
            [LAYOUT_RESPONSIVE] LAYOUT_FLEX, LAYOUT_STACK, LAYOUT_GRID, LAYOUT_RESPONSIVE
            [FORMS_VALIDATION]  FORM_INPUT, FORM_VALIDATE, FORM_BUSINESS
            [NETWORKING]        NET_JSON, NET_FUTUREBUILDER
            [STORAGE]           STORE_CACHE
            [AUTH]              AUTH_GUARD
            """;

    // ════════════════════════════════════════════════════════════
    //  PHA A — TESTCASE + LỜI GIẢI MẪU
    // ════════════════════════════════════════════════════════════
    public String systemTestcase() {
        return """
            Bạn là trợ lý TẠO BỘ TESTCASE TỰ ĐỘNG cho hệ thống chấm thi Flutter "Grader App".
            Từ ĐỀ BÀI của giảng viên, sinh testcase chấm tự động + LỜI GIẢI MẪU để hệ thống tự kiểm thử.

            # ĐẦU RA: CHỈ MỘT OBJECT JSON (không markdown, không lời dẫn) với ĐÚNG các khóa:
            {
              "assumptions": "Hợp đồng API: tên file lib/, class, field, hàm + signature, và CHUỖI TEXT UI chính xác mà test tìm.",
              "exam_test.dart": "<toàn bộ nội dung exam_test.dart — NHIỀU test()/testWidgets()>",
              "skills_matrix.json": { "<MÃ_TEST>": { "skill_code": "...", "difficulty": "...", "weight": N, "skill": "...", "name": "...", "description": "...", "expected": "..." } },
              "solution": { "lib/<file>.dart": "<lời giải ĐÚNG>", "lib/main.dart": "..." }
            }
            KHÔNG sinh "grader.dart" (backend tự chèn) và KHÔNG sinh "de_bai"/"starter" ở pha này.

            # HIỂU MÔ TẢ NGẮN CỦA GIẢNG VIÊN (RẤT QUAN TRỌNG)
            GV thường chỉ mô tả VẮN TẮT (vd: "viết app thu chi 2 màn hình, kiểm tra dart essentials, form validate, list view").
            Bạn PHẢI TỰ THIẾT KẾ đề đầy đủ & nhất quán — ĐỪNG đòi GV nói chi tiết:
            - Tự CHỌN cấu trúc app + số màn hình hợp lý; tự ĐẶT TÊN file lib/, class, field, hàm (kèm signature) và TEXT UI cụ thể.
            - GHI TẤT CẢ lựa chọn đó vào 'assumptions' (đây là HỢP ĐỒNG để exam_test ↔ starter ↔ solution dùng CHUNG).
            - "2 màn hình"/"3 screens" → tạo ĐÚNG số màn hình đó, mỗi màn hình ≥1 file lib/ + ≥1 testcase widget.
            - ÁNH XẠ cụm kiến thức GV nêu (Việt/Anh) sang skill_code ĐÚNG, ví dụ:
              "dart essential(s) / cú pháp / hàm / class / list-map / null"  → DART_SYNTAX, DART_FUNCTIONS, DART_CLASSES, DART_COLLECTIONS, DART_NULL_SAFETY
              "form / validate / kiểm tra nhập liệu"                          → FORM_INPUT, FORM_VALIDATE, FORM_BUSINESS
              "list view / danh sách"                                         → UI_LISTS ;  "widget / material / giao diện" → UI_WIDGETS, UI_MATERIAL ;  "date/time picker" → UI_PICKERS
              "điều hướng / nhiều màn hình / navigation"                      → NAV_BASIC, NAV_NAMED, NAV_ADVANCED
              "state / setState / quản lý trạng thái / lifting"               → STATE_BASIC, STATE_LIFTING
              "async / future / stream / api / json / mạng"                   → ASYNC_FUTURE, ASYNC_STREAM, NET_JSON, NET_FUTUREBUILDER
              "layout / responsive / grid / stack / flex"                     → LAYOUT_FLEX, LAYOUT_STACK, LAYOUT_GRID, LAYOUT_RESPONSIVE
              "kế thừa / OOP / model / pattern"                               → OOP_INHERITANCE, OOP_PATTERNS, OOP_MODEL
              "lưu / cache / local storage"  → STORE_CACHE ;  "đăng nhập / phân quyền / guard" → AUTH_GUARD
            - Nếu GV có nêu category/skill ưu tiên thì BÁM vào đó trước, rồi mở rộng thêm cho đủ độ phủ.

            # SỐ LƯỢNG & ĐỘ PHỦ (RẤT QUAN TRỌNG — đừng làm ít)
            - Tạo CÀNG NHIỀU testcase CÀNG TỐT, ĐÚNG/HƠN số lượng yêu cầu; mỗi test 1 tiêu chí rõ ràng, độc lập.
            - PHỦ TỐI ĐA SYLLABUS: trải trên NHIỀU skill_code thuộc NHIỀU category khác nhau (đừng dồn 1 mảng).
            - TRỘN ĐỦ 3 độ khó: có cả basic, intermediate VÀ advanced để phân bố ĐIỂM có chiều sâu (đừng để toàn basic).
            - Mỗi yêu cầu/ràng buộc trong đề nên có ≥1 testcase; tách logic thuần (unit) và UI (widget) thành các test riêng.

            # HỢP ĐỒNG KỸ THUẬT (BẮT BUỘC)
            1. Bài SV mount ở /app/lib; file test ở /app/test/exam_test.dart → import bằng '../lib/<file>.dart'.
               TẤT CẢ file lib ĐẶT PHẲNG ngay trong lib/ (TUYỆT ĐỐI KHÔNG tạo thư mục con như lib/services/, lib/models/).
               Import giữa các file lib dùng tên file CÙNG CẤP, vd: import 'transaction.dart'; (không 'services/transaction.dart').
            2. CHỈ dùng 'flutter' và 'flutter_test'. KHÔNG package ngoài (provider, http, intl, mockito...), không mạng/I-O/plugin.
            3. TÊN TEST = MÃ TESTCASE: mỗi test đặt tên là mã (vd 'TC_LOGIC_01'), TRÙNG KHÍT key trong skills_matrix.json (phân biệt hoa/thường).
            4. MỖI expect(...) PHẢI có 'reason:' rõ nghĩa. Test DETERMINISTIC: tránh Future.delayed/timer/animation vô hạn.
               WIDGET TEST phải ROBUST & DỄ PASS:
                 • pump TRỰC TIẾP đúng màn hình cần test: pumpWidget(MaterialApp(home: XScreen(...))) rồi pumpAndSettle();
                   KHÔNG dựa vào điều hướng giữa các màn hình (đừng tap để chuyển trang rồi mới find) — dễ vỡ.
                 • Solution PHẢI render CHÍNH XÁC mọi chuỗi mà test tìm bằng find.text('...') (khớp từng ký tự, có dấu).
                 • Truyền sẵn dữ liệu/callback qua constructor của màn hình (vd XScreen(items: [...], onAdd: (_){})), đừng phụ thuộc state ngoài.
               ƯU TIÊN test LOGIC thuần (unit) vì dễ deterministic; widget test chỉ cho hành vi/UI đã nêu rõ.
            5. Mỗi test ĐỘC LẬP — KHÔNG chia sẻ trạng thái giữa các test. TUYỆT ĐỐI KHÔNG dùng hàm/biến TOÀN CỤC
               có state thay đổi (vd top-level addIncome()/getTotalIncome() thao tác trên 1 list dùng chung —
               sẽ khiến test "khi rỗng = 0" FAIL vì test trước đã đổi state). Thiết kế HƯỚNG ĐỐI TƯỢNG: đặt state
               trong class, MỖI test TẠO ĐỐI TƯỢNG MỚI rồi thao tác trên nó, vd:
                 final store = ExpenseStore(); store.addIncome(Income('Salary', 1000)); expect(store.totalIncome, 1000);
               (Nếu buộc phải dùng state chung thì reset trong setUp().) Định danh tiếng Anh không dấu; chuỗi UI có thể tiếng Việt.
            6. KHÔNG khai báo class/enum/typedef BÊN TRONG main() hay trong test(...) — Dart CẤM (sẽ lỗi
               "'class' can't be used as an identifier"). Mọi lớp phụ (fake/stub/model test) PHẢI đặt ở
               TOP-LEVEL (ngoài main, thường ĐẶT TRƯỚC main). Trong main() chỉ gọi test()/testWidgets()/group()/setUp().
            7. main() PHẢI là phần tử CUỐI CÙNG của file: KHÔNG đặt class/hàm/biến nào SAU dấu '}' đóng main
               (để hệ thống có thể ghép thêm test mới vào cuối main ở các lô sau).
            8. Khi test giá trị THIẾU/KHÔNG hợp lệ: KHÔNG truyền `null` cho tham số NON-NULLABLE (gây lỗi biên dịch
               "The argument type 'Null' can't be assigned"). Dùng chuỗi rỗng '', số 0/-1, hoặc nếu thực sự cần nhận
               null thì khai báo tham số NULLABLE (String?/double?) trong solution.
            9. HẠN CHẾ RegExp / raw-string phức tạp trong solution (dễ lỗi "String starting with r' must end" hoặc sai
               pattern). Ưu tiên kiểm tra bằng logic thường: isEmpty/length/contains/startsWith/int.tryParse/double.tryParse.
               Nếu buộc dùng RegExp: viết raw string r'...' ĐÓNG ĐÚNG trên MỘT dòng, không ký tự lạ.

            # ĐỘ KHÓ → ĐIỂM (grader tự suy weight từ difficulty)
            basic=1 · intermediate=2 · advanced=3. 'difficulty' BẮT BUỘC mỗi testcase; 'weight' ghi đúng theo độ khó.
            KHÔNG ép tổng=10 (grader chuẩn hóa: điểm = Σweight_pass / Σweight × 10).

            # DANH MỤC skill_code HỢP LỆ (chọn CHÍNH XÁC, không bịa)
            %s

            # LỜI GIẢI MẪU (solution) — để hệ thống tự kiểm thử
            Code lib/ ĐÚNG để KHI chạy mọi test đều PASS: đủ class/field/method đúng signature; text UI khớp tuyệt đối.
            Hệ thống sẽ chạy thử; nếu lỗi biên dịch hoặc còn test FAIL, bạn nhận lỗi và phải SỬA rồi trả lại JSON đầy đủ.
            """.formatted(SKILL_CODES);
    }

    /** Prompt LÔ ĐẦU: sinh {@code firstN} testcase (tổng mục tiêu {@code target}; các lô sau xin thêm). */
    public String userTestcase(GenRequest r, int firstN, int target) {
        StringBuilder sb = new StringBuilder();
        sb.append("# YÊU CẦU (LÔ NỀN)\n");
        sb.append("- Tạo ").append(firstN).append(" testcase ĐẦY ĐỦ, chất lượng cho lô nền");
        if (target > firstN)
            sb.append(" (TỔNG MỤC TIÊU ~").append(target).append(" testcase — tôi sẽ xin THÊM (CHỈ phần mới) ở các lô sau)");
        sb.append(".\n");
        sb.append("- ⚠ LÔ NỀN CHỈ dùng test LOGIC/UNIT THUẦN trên class/hàm Dart (KHÔNG widget test, KHÔNG pumpWidget) "
                + "để CHẮC CHẮN PASS 100%. Vẫn phủ được FORM_VALIDATE/business qua HÀM thuần (vd validateTitle(String)->String?), "
                + "UI_LISTS qua logic store (add/remove/lọc/tổng).");
        sb.append(allowWidgetTests ? " Widget test (render UI) sẽ thêm ở các lô sau.\n"
                                   : " Đề thi này KHÔNG dùng widget test — chỉ chấm logic.\n");
        sb.append("- Phân bố độ khó: ").append(describeDifficulty(r.difficultyOr()))
          .append(" — nhớ có cả advanced để điểm có chiều sâu.\n");
        sb.append("- Phủ càng nhiều skill_code / category càng tốt; mỗi yêu cầu/ràng buộc của đề ≥1 testcase.\n");
        if (r.categories() != null && !r.categories().isEmpty())
            sb.append("- Ưu tiên (nhưng không giới hạn) các mảng: ").append(String.join(", ", r.categories())).append('\n');
        if (r.extra() != null && !r.extra().isBlank())
            sb.append("- Yêu cầu thêm: ").append(r.extra().trim()).append('\n');
        sb.append("\n# ĐỀ BÀI / Ý TƯỞNG\n");
        sb.append(r.topic() == null || r.topic().isBlank()
                ? "(trống — hãy tự đề xuất một đề Flutter có nhiều phần: model logic + UI + state + validate)"
                : r.topic().trim());
        sb.append("\n\nNếu đề thiếu thông tin (tên file/class/text), TỰ chọn quy ước hợp lý, ghi rõ trong 'assumptions', "
                + "giữ NHẤT QUÁN giữa test ↔ import ↔ lời giải. Trả về ĐÚNG 1 object JSON theo hợp đồng.");
        return sb.toString();
    }

    /**
     * Message xin LÔ THÊM dạng DELTA: chỉ trả PHẦN MỚI (test/matrix/solution-patch) — backend tự ghép vào
     * bộ đang có. Nhờ vậy mỗi lần gọi AI ngắn (không in lại test cũ) → nhanh & không bị cắt do giới hạn token.
     */
    public String addBatchDelta(int currentCount, List<String> ids, Set<String> coveredSkills, int addN, int target) {
        String idList = ids.size() > 60 ? String.join(", ", ids.subList(0, 60)) + ", …" : String.join(", ", ids);
        String skills = coveredSkills.isEmpty() ? "(chưa rõ)" : String.join(", ", coveredSkills);
        return ("""
            Đang có %d/%d testcase (mã đã dùng: %s).
            skill_code đã phủ: %s.

            Hãy THÊM %d testcase MỚI (mã KHÁC HẲN các mã trên), ưu tiên skill_code/category CHƯA phủ; trộn độ khó.
            ⚠ CHỈ trả về PHẦN THÊM — KHÔNG in lại test cũ, KHÔNG in lại exam_test.dart đầy đủ. Trả ĐÚNG 1 object JSON:
            {
              "new_tests": "<các hàm test()/testWidgets() MỚI, đặt tên = mã testcase; sẽ được GHÉP vào TRONG main()>",
              "skills_matrix.json": { "<MÃ_MỚI>": { "skill_code": "...", "difficulty": "...", "weight": N, "skill": "...", "name": "...", "description": "...", "expected": "..." } },
              "solution_patch": { "lib/<file>.dart": "<nội dung ĐẦY ĐỦ MỚI của file CẦN sửa/thêm để test mới PASS — chỉ file thay đổi>" },
              "new_top_level": "<(chỉ nếu cần) class phụ MỚI ở TOP-LEVEL cho test mới — TUYỆT ĐỐI không để trong main()>",
              "new_imports": ["<(chỉ nếu thêm file lib mới) import '../lib/x.dart';>"]
            }
            %s
            QUY TẮC: new_tests DÙNG ĐÚNG hợp đồng/lib hiện có; KHÔNG khai báo class trong main()/test; test mới PHẢI PASS
            với solution sau khi áp solution_patch. Nếu không cần đổi lib thì BỎ "solution_patch"/"new_top_level"/"new_imports".
            """).formatted(currentCount, target, idList, skills, addN,
                allowWidgetTests
                    ? "Phần lớn vẫn nên là test LOGIC (chắc chắn PASS). CÓ THỂ xen 1-2 widget test ĐƠN GIẢN nếu CHẮC "
                      + "render đúng: pump TRỰC TIẾP màn hình (MaterialApp(home: XScreen(...))), KHÔNG điều hướng; "
                      + "solution_patch render CHÍNH XÁC text mà find.text('...') tìm. KHÔNG chắc thì để là test logic."
                    : "⚠ CHỈ thêm test LOGIC/UNIT THUẦN (KHÔNG widget test, KHÔNG pumpWidget) để CHẮC CHẮN PASS — "
                      + "phủ thêm logic/validate/business/collections/null-safety/async chưa có.");
    }

    /** Feed lỗi cho 1 LÔ DELTA (1 lượt sửa trước khi bỏ): test mới chưa biên dịch / chưa PASS. */
    public String fixDelta(CompileResult c) {
        StringBuilder sb = new StringBuilder();
        sb.append("LÔ THÊM vừa rồi CHƯA ĐẠT: ").append(c.summary()).append('\n');
        if (!c.compiled())
            sb.append("Lỗi BIÊN DỊCH ở test mới. Thường gặp: truyền null cho tham số non-nullable (dùng '' / số thay vì null), "
                    + "sai tên/đối số so với lib hiện có, hoặc lỡ khai báo class trong main().\n");
        else
            sb.append("Biên dịch OK nhưng còn test FAIL — test mới chưa khớp solution hoặc dùng state chung. "
                    + "Mỗi test phải tạo ĐỐI TƯỢNG MỚI; bổ sung solution_patch nếu thiếu hành vi.\n");
        if (c.errorsExcerpt() != null && !c.errorsExcerpt().isBlank())
            sb.append("\nCHI TIẾT:\n").append(c.errorsExcerpt()).append('\n');
        sb.append("\n→ Test nào KHÓ sửa nhanh thì BỎ HẲN test đó khỏi new_tests + skills_matrix (GIỮ các test còn lại đúng) — "
                + "thà thêm ít mà chắc còn hơn bỏ cả lô. Tránh RegExp/raw-string phức tạp; kiểm tra bằng length/contains/tryParse.\n");
        sb.append("Sửa LẠI và CHỈ trả phần DELTA đúng (new_tests, skills_matrix.json, solution_patch nếu cần). "
                + "KHÔNG in lại test cũ / exam_test.dart đầy đủ.");
        return sb.toString();
    }

    /** (Cũ — không còn dùng) Message xin LÔ TIẾP THEO bằng cách trả lại TOÀN BỘ đã gộp. */
    public String addBatch(int currentCount, List<String> ids, Set<String> coveredSkills, int addN, int target) {
        String idList = ids.size() > 60 ? String.join(", ", ids.subList(0, 60)) + ", …" : String.join(", ", ids);
        StringBuilder sb = new StringBuilder();
        sb.append("TỐT — đang có ").append(currentCount).append('/').append(target).append(" testcase: ").append(idList).append(".\n");
        sb.append("Đã phủ skill_code: ").append(coveredSkills.isEmpty() ? "(chưa rõ)" : String.join(", ", coveredSkills)).append(".\n\n");
        sb.append("Hãy THÊM ").append(addN).append(" testcase MỚI (mã KHÁC, KHÔNG trùng mã cũ), ưu tiên phủ skill_code/category CHƯA có; trộn độ khó.\n");
        sb.append("Cập nhật lời giải mẫu (solution) nếu cần để MỌI test (cũ + mới) đều PASS.\n");
        sb.append("TRẢ LẠI ĐẦY ĐỦ 1 object JSON (assumptions, exam_test.dart, skills_matrix.json, solution) đã GỘP — "
                + "GIỮ NGUYÊN toàn bộ testcase cũ, chỉ THÊM mới. Mục tiêu tổng: ").append(target).append(" testcase.");
        return sb.toString();
    }

    /** Feed lỗi PHA A: lời giải mẫu chưa biên dịch / chưa PASS hết. */
    public String fixSolution(CompileResult c, int round) {
        StringBuilder sb = new StringBuilder();
        sb.append("KẾT QUẢ CHẠY THỬ (lần ").append(round).append("): ").append(c.summary()).append("\n\n");
        if (!c.compiled())
            sb.append("LỖI BIÊN DỊCH — exam_test.dart và/hoặc lời giải mẫu không compile. Sửa cho khớp hợp đồng "
                    + "(đúng tên file/class/field/method/param/text), chỉ flutter/flutter_test.\n");
        else
            sb.append("Biên dịch OK nhưng LỜI GIẢI MẪU chưa PASS hết (").append(c.passed()).append('/').append(c.total())
              .append("). TESTCASE và LỜI GIẢI chưa nhất quán — sửa cho khớp (đừng giảm số testcase).\n");
        if (c.errorsExcerpt() != null && !c.errorsExcerpt().isBlank())
            sb.append("\nCHI TIẾT LỖI:\n").append(c.errorsExcerpt()).append('\n');

        // Gợi ý NHẮM TRÚNG các lỗi hay khiến model kẹt vòng lặp:
        String low = c.errorsExcerpt() == null ? "" : c.errorsExcerpt().toLowerCase();
        if (low.contains("can't be used as an identifier") || low.contains("'class'")
                || low.contains("'extends'") || low.contains("expected ';' after this")) {
            sb.append("\n⚠ NGUYÊN NHÂN GỐC: bạn đang KHAI BÁO CLASS BÊN TRONG main()/test — Dart KHÔNG cho phép. "
                    + "Hãy DI CHUYỂN mọi class (kể cả lớp phụ/stub/fake) RA TOP-LEVEL (đặt TRƯỚC hàm main, ngoài main); "
                    + "trong main() chỉ được gọi test()/testWidgets(). Đừng chỉ đổi tên class — phải ĐƯA RA NGOÀI main.\n");
        }
        if (low.contains("too few positional arguments") || low.contains("too many positional arguments")
                || low.contains("no named parameter") || low.contains("isn't defined")) {
            sb.append("\n⚠ Lệch HỢP ĐỒNG: tên/đối số constructor/hàm trong test KHÔNG khớp lời giải. "
                    + "Sửa cho test ↔ solution dùng CÙNG tên + CÙNG signature (named/positional, kiểu).\n");
        }

        sb.append("\nTrả lại ĐẦY ĐỦ 1 object JSON (assumptions, exam_test.dart, skills_matrix.json, solution) đã sửa. "
                + "GIỮ NGUYÊN hoặc TĂNG số testcase. Đừng giải thích ngoài JSON.");
        return sb.toString();
    }

    /**
     * Feed lỗi PHA A — LẦN SỬA CUỐI của lô nền: ưu tiên CÓ BỘ XANH 100% thay vì cố giữ đủ số.
     * Cho phép XÓA HẲN test khó để bộ còn lại PASS hết (sẽ bù thêm ở các lô delta sau).
     */
    public String fixSolutionFinal(CompileResult c, int round) {
        StringBuilder sb = new StringBuilder();
        sb.append("KẾT QUẢ CHẠY THỬ (lần ").append(round).append("): ").append(c.summary()).append("\n\n");
        sb.append("ĐÂY LÀ LẦN SỬA CUỐI của lô nền. MỤC TIÊU SỐ 1: bộ trả về BIÊN DỊCH SẠCH và MỌI test còn lại PASS 100%.\n");
        sb.append("→ Test nào bạn KHÔNG chắc sửa cho PASS (đặc biệt widget test khó, text không khớp, cần điều hướng): "
                + "HÃY XÓA HẲN test đó VÀ entry skills_matrix tương ứng. THÀ ÍT testcase mà chắc chắn xanh — tôi sẽ xin THÊM ở các lô sau.\n");
        sb.append("Giữ lại các test logic/đơn giản; sửa nhanh những test gần đúng; xóa phần còn lại.\n");
        if (c.errorsExcerpt() != null && !c.errorsExcerpt().isBlank())
            sb.append("\nCHI TIẾT LỖI:\n").append(c.errorsExcerpt()).append('\n');
        sb.append("\nTrả lại ĐẦY ĐỦ 1 object JSON (assumptions, exam_test.dart, skills_matrix.json, solution). Đừng giải thích ngoài JSON.");
        return sb.toString();
    }

    /**
     * Message CHỈNH SỬA THEO YÊU CẦU (nối vào hội thoại pha A đã có): áp ý GV vào bộ testcase
     * + lời giải mẫu hiện tại. Đổi chức năng → cập nhật test; chỉ đổi cách diễn đạt → giữ nguyên test.
     */
    public String refineTestcase(String instruction) {
        return ("""
            NGƯỜI RA ĐỀ MUỐN CHỈNH SỬA. YÊU CẦU (giữa hai dấu ngoặc):
            «%s»

            Hãy ÁP DỤNG yêu cầu trên vào bộ testcase + lời giải mẫu HIỆN TẠI (ở các phản hồi trên):
            - Nếu yêu cầu liên quan CHỨC NĂNG / ĐỘ KHÓ / SỐ LƯỢNG / SKILL → cập nhật exam_test.dart + skills_matrix.json + solution cho khớp.
            - Nếu yêu cầu CHỈ về cách diễn đạt / đề bài (không đổi chức năng) → GIỮ NGUYÊN Y HỆT testcase + solution, trả lại như cũ.
            - GIỮ những testcase còn đúng; chỉ sửa/thêm/bớt phần liên quan. Mọi test (cũ + mới) PHẢI PASS với solution.
            - KHÔNG khai báo class trong main()/test (luôn để class ở TOP-LEVEL). Cập nhật 'assumptions' nếu hợp đồng đổi.

            Trả lại ĐẦY ĐỦ 1 object JSON (assumptions, exam_test.dart, skills_matrix.json, solution). Đừng giải thích ngoài JSON.
            """).formatted(instruction == null ? "" : instruction.trim());
    }

    // ════════════════════════════════════════════════════════════
    //  PHA B — ĐỀ BÀI PHÁT SV + KHUNG CODE (STARTER)
    // ════════════════════════════════════════════════════════════
    public String systemStarter() {
        return """
            Bạn là trợ lý tạo TÀI LIỆU PHÁT CHO SINH VIÊN của hệ thống chấm thi Flutter "Grader App".
            Cho trước bộ TESTCASE đã chốt (exam_test.dart) + giả định hợp đồng. Hãy tạo 2 thứ KHỚP 100% với test:
              1) ĐỀ BÀI (đề thi để phát cho SV làm).
              2) KHUNG CODE (starter lib/) để SV điền logic vào là chạy được test.

            # ĐẦU RA: CHỈ MỘT OBJECT JSON với ĐÚNG các khóa:
            {
              "de_bai": "<đề bài dạng markdown, tường thuật, KHÔNG lộ lời giải>",
              "starter": { "lib/<file>.dart": "<khung code>", "lib/main.dart": "..." }
            }

            # ĐỀ BÀI (de_bai)
            - Mở đầu đúng câu: "Hãy viết chương trình Flutter chạy trên Android gồm các yêu cầu sau".
            - Gạch đầu dòng tường thuật; ghi RÕ INLINE tên file lib/, class/field/hàm (kèm signature) và CHUỖI TEXT UI
              chính xác (trong ngoặc kép) — để SV làm đúng "hợp đồng" mà test mong đợi.
            - Mô tả HÀNH VI cần đạt, KHÔNG đưa lời giải/đáp án. Văn phong rõ ràng, sạch, có thể in phát cho SV.

            # KHUNG CODE (starter) — NGUYÊN TẮC SỐNG CÒN
            1. PHẢI BIÊN DỊCH SẠCH (compile). Code không compile = SV bị 0 điểm oan.
            2. Khai báo ĐỦ class/constructor(+named params & kiểu)/field/getter/method ĐÚNG signature mà exam_test.dart gọi,
               và mọi chuỗi text UI mà test tìm (find.text(...)). TÊN trùng KHÍT test (phân biệt hoa/thường).
            3. Là KHUNG, KHÔNG phải lời giải: thân hàm để STUB an toàn + // TODO (trả default: false/0/''/[]...).
               KHÔNG throw UnimplementedError ở getter/field mà build() dùng (gây crash). build() trả Scaffold tối thiểu + // TODO.
            4. Dữ liệu/nhãn CỐ ĐỊNH thuộc đề (mà test khẳng định) nên đặt sẵn; phần LOGIC/HÀNH VI để trống cho SV.
            5. Chỉ dùng 'flutter'. Thêm lib/main.dart để SV chạy thử (không bị test gọi, miễn compile).

            Hệ thống sẽ BIÊN DỊCH thử starter; nếu lỗi compile, bạn nhận lỗi và phải sửa rồi trả lại JSON đầy đủ.
            """;
    }

    public String userStarter(GenArtifacts art) { return userStarter(art, null, null); }

    /**
     * Prompt pha B. Khi {@code refineInstruction} có giá trị (refine), yêu cầu chỉnh đề bài + khung code
     * theo ý GV và (nếu có) dựa trên {@code priorDeBai} để giữ văn phong/cấu trúc.
     */
    public String userStarter(GenArtifacts art, String refineInstruction, String priorDeBai) {
        StringBuilder sb = new StringBuilder();
        sb.append("# GIẢ ĐỊNH HỢP ĐỒNG (từ pha tạo testcase)\n");
        sb.append(art.assumptions() == null || art.assumptions().isBlank() ? "(không có — suy từ exam_test.dart)" : art.assumptions());
        sb.append("\n\n# TESTCASE ĐÃ CHỐT (exam_test.dart) — căn cứ để sinh đề bài & starter\n");
        sb.append("```dart\n").append(art.examTest()).append("\n```\n");
        if (refineInstruction != null && !refineInstruction.isBlank()) {
            sb.append("\n# YÊU CẦU CHỈNH SỬA TỪ NGƯỜI RA ĐỀ (áp vào đề bài & khung code)\n«")
              .append(refineInstruction.trim()).append("»\n");
            if (priorDeBai != null && !priorDeBai.isBlank())
                sb.append("\n# ĐỀ BÀI HIỆN TẠI (chỉnh dựa trên đây, giữ văn phong; vẫn KHỚP testcase ở trên)\n")
                  .append(priorDeBai.trim()).append('\n');
        }
        sb.append("\nHãy trả về ĐÚNG 1 object JSON { de_bai, starter } khớp 100% với test trên. "
                + "starter PHẢI compile sạch; de_bai KHÔNG lộ lời giải.");
        return sb.toString();
    }

    /** Feed lỗi PHA B: khung code không biên dịch. */
    public String fixStarter(CompileResult c, int round) {
        StringBuilder sb = new StringBuilder();
        sb.append("KHUNG CODE (starter) chưa đạt (lần ").append(round).append("): ").append(c.summary()).append('\n');
        sb.append("starter phải BIÊN DỊCH SẠCH với exam_test.dart (đúng tên/đủ signature/đủ text UI test gọi), chỉ flutter.\n");
        if (c.errorsExcerpt() != null && !c.errorsExcerpt().isBlank())
            sb.append("\nCHI TIẾT LỖI:\n").append(c.errorsExcerpt()).append('\n');
        sb.append("\nTrả lại ĐẦY ĐỦ 1 object JSON { de_bai, starter } đã sửa. Đừng giải thích ngoài JSON.");
        return sb.toString();
    }

    private String describeDifficulty(String d) {
        return switch (d.toLowerCase()) {
            case "basic"        -> "chủ yếu basic nhưng vẫn có vài intermediate/advanced";
            case "intermediate" -> "trọng tâm intermediate, kèm basic & advanced";
            case "advanced"     -> "nhiều advanced, vẫn có basic & intermediate làm nền";
            default              -> "ĐA DẠNG: trộn đều basic + intermediate + advanced";
        };
    }
}
