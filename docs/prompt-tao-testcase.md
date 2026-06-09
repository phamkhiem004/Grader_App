# Prompt sinh testcase & starter code cho Grader App

Ba prompt dùng nối tiếp nhau để giảng viên có **đề bài chuẩn**, bộ **testcase** (để chấm) và
**starter code** (khung `lib/` phát cho SV) **đồng bộ 100%**, không lệch tên file/class:

0. **Prompt 0 — Sinh đề bài chuẩn** → từ ý tưởng thô, ra đề rõ tên file/class/hàm + text UI.
1. **Prompt 1 — Sinh testcase** → từ đề bài, ra `exam_test.dart`, `grader.dart`, `skills_matrix.json`.
2. **Prompt 2 — Sinh starter code** → từ `exam_test.dart`, ra khung `lib/` phát cho SV.

> Mẹo: dùng **Claude Code** thì nó tự ghi file + nén; dùng **claude.ai** thì copy từng khối code ra file rồi chạy lệnh nén.

---

## Prompt 0 — Sinh đề bài chuẩn (đầu vào cho Prompt 1)

Dán prompt này + ý tưởng/yêu cầu thô vào Claude → nhận đề bài chi tiết, rõ tên file/class/hàm và
text UI. Rà lại đề rồi đưa thẳng vào **Prompt 1**.

````text
Bạn là trợ lý RA ĐỀ THI THỰC HÀNH FLUTTER cho hệ thống chấm tự động "Grader App".
Biến Ý TƯỞNG THÔ ở cuối thành ĐỀ BÀI HOÀN CHỈNH: giữ văn phong TƯỜNG THUẬT, nhưng ghi rõ INLINE
ngay trong câu tên file/class/hàm và text UI — để sinh testcase chấm tự động chính xác.

## ĐỊNH DẠNG ĐỀ
- Mở đầu đúng câu: "Hãy viết chương trình Flutter chạy trên Android gồm các yêu cầu sau".
- Gạch đầu dòng tường thuật: "-" mục lớn, "+" chức năng con, "*" từng ràng buộc. KHÔNG tách section
  kiểu "Hợp đồng/Rubric/Bảng" — mọi tên & UI text nằm NGAY trong câu.
- Giữ mục "Hướng dẫn:" + code helper nếu đề gốc có. CHỈ xuất đề bài, không thêm lời dẫn/giải thích.

## MỖI YÊU CẦU GHI RÕ (inline)
- File trong `lib/` + class/widget + thuộc tính (kèm KIỂU) + hàm (kèm SIGNATURE).
- Constructor: model dùng named + required; widget nhận dữ liệu qua constructor, trả kết quả qua callback
  (vd `ExpenseFormScreen({Expense? initial, required ValueChanged<Expense> onSubmit})`).
- Tách LOGIC ra HÀM THUẦN trong file model để dễ unit test (vd `int nextId(List<Expense>)`) + nêu rõ hành vi.
- CHUỖI UI chính xác (trong ngoặc kép): tiêu đề, nhãn nút/ô nhập, hộp thoại, và THÔNG BÁO LỖI mỗi ràng buộc.

## RÀNG BUỘC
- Chỉ Flutter + flutter_test (không package ngoài/mạng/DB/plugin). Model là class Dart thuần.
- Định danh (class/field/method/file) tiếng Anh KHÔNG DẤU; chuỗi UI hiển thị có thể tiếng Việt.
- Tránh animation/timer vô hạn; mỗi màn hình test độc lập (không cần Navigator/persistence).
- Thiếu thông tin (tên/UI) → tự chọn quy ước hợp lý và ghi rõ NGAY trong câu đó.

=========================== Ý TƯỞNG / YÊU CẦU THÔ ===========================
<DÁN yêu cầu thô vào đây — vd đề "Quản lý thu chi" dạng gạch đầu dòng>
````

---

## Prompt 1 — Sinh testcase

Dán **toàn bộ** prompt dưới đây vào Claude, rồi dán **đề bài** của bạn vào phần `ĐỀ BÀI`.

---

````text
Bạn là trợ lý tạo bộ TESTCASE TỰ ĐỘNG cho hệ thống chấm thi Flutter "Grader App".
Từ ĐỀ BÀI ở cuối, hãy sinh ra ĐÚNG 3 file: `exam_test.dart`, `grader.dart`,
`skills_matrix.json`, tuân thủ NGHIÊM NGẶT hợp đồng kỹ thuật bên dưới.

## Bối cảnh hệ thống (BẮT BUỘC hiểu đúng)
- Bài làm sinh viên (SV) được mount tại `/app/lib`. File test nằm ở `/app/test/exam_test.dart`,
  nên import code SV bằng đường dẫn tương đối: `import '../lib/<ten_file>.dart';`.
- Môi trường chấm CHỈ có sẵn package `flutter` và `flutter_test`. TUYỆT ĐỐI không dùng package
  ngoài (provider, http, mockito, bloc, ...). Không gọi mạng, không I/O, không plugin nền tảng.
- `grader.dart` sẽ chạy `flutter test --machine`, đọc `skills_matrix.json` để quy ra điểm,
  rồi in JSON kết quả. Backend đọc JSON đó để lưu điểm.

## QUY TẮC BẮT BUỘC
1. **Tên test = mã testcase.** Mỗi `test(...)`/`testWidgets(...)` trong `exam_test.dart` phải có
   tên là một mã, ví dụ `'TC_LOGIC_01'`, `'TC_UI_01'`. Tên này PHẢI trùng KHÍT (phân biệt hoa/thường)
   với key trong `skills_matrix.json`. Test có tên không nằm trong matrix sẽ bị bỏ qua khi tính điểm.
2. **`skills_matrix.json`** — map mỗi mã_testcase → **OBJECT metadata**. **CHO ĐIỂM THEO ĐỘ KHÓ:**
   ```json
   { "TC_UI_01": { "skill_code": "UI_WIDGETS", "difficulty": "basic", "weight": 1,
       "skill": "Widget cơ bản", "name": "Render màn hình login",
       "description": "Kiểm tra màn hình đăng nhập hiển thị đúng.",
       "expected": "Hiển thị form email + password + nút Đăng nhập." } }
   ```
   - `skill_code` (BẮT BUỘC): chọn CHÍNH XÁC từ **DANH MỤC KỸ NĂNG (SYLLABUS)** ở mục bên dưới.
   - `difficulty` (BẮT BUỘC): `basic` | `intermediate` | `advanced` (xem tiêu chí ở mục "ĐỘ KHÓ").
     **`difficulty` LÀ NGUỒN SỰ THẬT CHO ĐIỂM** — grader TỰ suy `weight` = điểm theo độ khó khi chấm.
   - `weight` (nên có, để matrix dễ đọc): ghi ĐÚNG theo độ khó **`basic`→1, `intermediate`→2, `advanced`→3**.
     Nếu ghi lệch độ khó, grader vẫn lấy theo `difficulty` (weight chỉ là fallback khi không khai difficulty).
   - **KHÔNG cần tổng = 10.** Grader tự chuẩn hóa: điểm cuối = `Σweight_pass / Σweight × 10`.
   - Nên có thêm `skill` (tên kỹ năng thân thiện), `name`, `description`, `expected`.
3. **`grader.dart`**: COPY NGUYÊN VĂN template ở mục "GRADER.DART CHUẨN" bên dưới. KHÔNG chỉnh sửa.
4. **`exam_test.dart`**:
   - `import 'package:flutter/material.dart';` + `import 'package:flutter_test/flutter_test.dart';`
   - `import '../lib/<file>.dart';` đúng tên các file mà đề yêu cầu SV tạo.
   - Dùng `test(...)` cho logic/unit, `testWidgets(...)` cho widget/UI.
   - Test phải DETERMINISTIC: tránh `Future.delayed`, timer vô hạn, animation vô hạn. Với UI dùng
     `await tester.pumpWidget(MaterialApp(home: ...))` rồi `await tester.pumpAndSettle()`
     (nếu có animation lặp vô hạn thì dùng `pump(Duration(...))` thay vì `pumpAndSettle`).
   - Mỗi test kiểm tra một tiêu chí rõ ràng, dùng `expect(...)`.
   - NÊN thêm `reason:` vào `expect(...)` để khi fail có thông báo dễ hiểu (grader lưu vào trường
     `actual` cho AI đọc): `expect(found, isTrue, reason: 'Phải hiển thị nút Đăng ký');`
5. Mỗi test phải ĐỘC LẬP (không phụ thuộc thứ tự chạy, không chia sẻ state toàn cục).

## ĐỘ KHÓ & CHO ĐIỂM (BẮT BUỘC theo đây)
Chỉ cần gán đúng `difficulty` — grader tự quy ra điểm (`weight`):

| difficulty | Khi nào gán | điểm |
|---|---|---|
| `basic` | Kiến thức 1 buổi học, áp dụng thẳng, không kết hợp. | **1** |
| `intermediate` | Kết hợp 2–3 khái niệm, có điều kiện/edge case. | **2** |
| `advanced` | Tổng hợp nhiều phần, edge case khó, tối ưu/async. | **3** |

→ Testcase càng khó càng nhiều điểm; KHÔNG ép tổng = 10 (grader tự chuẩn hóa:
điểm = Σweight_pass / Σweight × 10). Nên phủ nhiều mức độ khó để đánh giá năng lực
có chiều sâu (đừng để toàn `basic`).

## DANH MỤC KỸ NĂNG (SYLLABUS v2026.2) — chọn `skill_code` từ đây
Mỗi testcase PHẢI gắn `skill_code` lấy CHÍNH XÁC từ danh sách dưới (không tự bịa code mới).
Cho phần chấm tự động chỉ dùng skill `auto`; skill `manual` (cần package ngoài/mạng) để chấm tay.

```
[DART_ESSENTIALS]   DART_SYNTAX, DART_FUNCTIONS, DART_CLASSES, DART_COLLECTIONS, DART_NULL_SAFETY
[OOP_ASYNC]         OOP_INHERITANCE, OOP_PATTERNS, OOP_MODEL, ASYNC_FUTURE, ASYNC_STREAM
[UI_FUNDAMENTALS]   UI_WIDGETS, UI_MATERIAL, UI_LISTS, UI_PICKERS
[NAV_STATE]         NAV_BASIC, NAV_NAMED, NAV_ADVANCED, STATE_BASIC, STATE_LIFTING
[LAYOUT_RESPONSIVE] LAYOUT_FLEX, LAYOUT_STACK, LAYOUT_GRID, LAYOUT_RESPONSIVE
[FORMS_VALIDATION]  FORM_INPUT, FORM_VALIDATE, FORM_BUSINESS
[NETWORKING]        NET_JSON, NET_FUTUREBUILDER, (NET_HTTP = manual)
[STORAGE]           STORE_CACHE, (STORE_PREFS, STORE_DB = manual)
[AUTH]              AUTH_GUARD, (AUTH_BASIC, AUTH_SESSION = manual)
```
(Mô tả đầy đủ từng code xem `syllabus.json`. Một testcase phủ nhiều skill → chọn skill CHÍNH.)

## TRƯỚC KHI VIẾT TEST — suy ra "hợp đồng API"
Đề thi mô tả thứ SV phải làm. Trước khi viết test, hãy SUY RA và LIỆT KÊ ngắn gọn:
- Tên các **file** SV cần tạo trong `lib/` (vd `event.dart`, `event_screen.dart`).
- Tên **class**, **thuộc tính**, **hàm** + signature mà test sẽ gọi.
- Các **chuỗi text UI chính xác** mà widget phải hiển thị (để `find.text(...)` khớp).
Nếu đề THIẾU thông tin (tên file/class/text chưa rõ), hãy NÊU GIẢ ĐỊNH rõ ràng ở đầu câu trả lời,
chọn quy ước hợp lý, và giữ NHẤT QUÁN giữa test ↔ import ↔ giả định. Giảng viên sẽ dựa vào các
giả định này để phát đề khung (starter code) cho SV.

## GRADER.DART CHUẨN (giữ nguyên 100%)
```dart
import 'dart:io';
import 'dart:convert';

void main() async {
  // 1) Chạy test ở chế độ machine
  final result = await Process.run(
    'flutter', ['test', '--machine', '--no-pub'],
    runInShell: true,
  );

  // 2) Đọc rubric — hỗ trợ CẢ dạng số (cũ) lẫn object (mới)
  final matrix = jsonDecode(
    await File('test/skills_matrix.json').readAsString(),
  ) as Map<String, dynamic>;

  // 3) Parse output machine của flutter test
  final idToName = <int, String>{};
  final idToError = <int, String>{};
  final idToPrints = <int, List<String>>{};
  final passed = <String>{};
  final failed = <String>{};
  final failReason = <String, String>{};

  for (final line in result.stdout.toString().split('\n')) {
    if (!line.startsWith('{')) continue;
    try {
      final ev = jsonDecode(line) as Map<String, dynamic>;
      switch (ev['type']) {
        case 'testStart':
          final t = ev['test'] as Map<String, dynamic>;
          idToName[t['id'] as int] = t['name'] as String;
          break;
        case 'error':
          final id = ev['testID'];
          if (id is int) idToError[id] = (ev['error'] ?? '').toString();
          break;
        case 'print':
          // Lỗi chi tiết của WIDGET test nằm trong print (EXCEPTION CAUGHT...).
          final id = ev['testID'];
          if (id is int) {
            final m = (ev['message'] ?? '').toString();
            if (m.isNotEmpty) (idToPrints[id] ??= <String>[]).add(m);
          }
          break;
        case 'testDone':
          final id = ev['testID'] as int;
          final name = idToName[id] ?? '';
          final hidden = ev['hidden'] == true;
          if (name.isEmpty || name.startsWith('loading ') || hidden) break;
          if (ev['result'] == 'success') {
            passed.add(name);
          } else {
            failed.add(name);
            final reason = _failureReason(idToError[id], idToPrints[id]);
            if (reason.isNotEmpty) failReason[name] = reason;
          }
          break;
      }
    } catch (_) {}
  }

  // 4) Tính điểm + dựng test_cases giàu thông tin
  double earned = 0, maxScore = 0;
  int passCount = 0;
  final chiTiet = <Map<String, dynamic>>[];
  final testCases = <Map<String, dynamic>>[];

  final names = ({...passed, ...failed}).toList()..sort();
  for (final name in names) {
    final meta = matrix[name];
    if (meta == null) continue; // test không nằm trong rubric → bỏ qua

    double weight = 1.0;
    String? skill, description, human, expected, difficulty;
    if (meta is num) {
      weight = meta.toDouble();
    } else if (meta is Map) {
      difficulty = meta['difficulty']?.toString();
      // ĐIỂM THEO ĐỘ KHÓ: weight suy TRỰC TIẾP từ difficulty (basic=1, intermediate=2, advanced=3).
      // 'weight' trong matrix chỉ là FALLBACK khi không khai difficulty → difficulty là NGUỒN SỰ THẬT.
      weight = _difficultyPoints(difficulty) ?? (meta['weight'] as num?)?.toDouble() ?? 1.0;
      skill = meta['skill']?.toString();
      description = meta['description']?.toString();
      human = meta['name']?.toString();
      expected = meta['expected']?.toString();
    }

    final ok = passed.contains(name);
    maxScore += weight;
    if (ok) { earned += weight; passCount++; }

    chiTiet.add({
      'name': name,
      'status': ok ? 'PASS' : 'FAILED',
      'message': ok ? '+$weight điểm' : '0/$weight điểm',
    });

    final tc = <String, dynamic>{
      'test_id': name,
      'name': human ?? name,
      'status': ok ? 'passed' : 'failed',
      'weight': weight,
    };
    if (difficulty != null) tc['difficulty'] = difficulty;
    if (skill != null) tc['skill'] = skill;
    if (description != null) tc['description'] = description;
    if (!ok) {
      if (expected != null) tc['expected'] = expected;
      final actual = failReason[name];
      if (actual != null && actual.isNotEmpty) tc['actual'] = actual;
    }
    testCases.add(tc);
  }

  final total = testCases.length;
  final diem = maxScore > 0
      ? double.parse(((earned / maxScore) * 10).toStringAsFixed(2))
      : 0.0;

  // 5) Phân tích tĩnh code SV (best-effort)
  final analyze = await _analyzeLib();

  // 6) Xuất JSON (GIỮ field cũ để tương thích + thêm cấu trúc giàu cho AI)
  print('--- GRADE_RESULT_START ---');
  print(jsonEncode({
    'diem': diem,
    'soTestPass': passCount,
    'tongSoTest': total,
    'chiTiet': chiTiet,
    'grading_result': {
      'score': diem,
      'passed_tests': passCount,
      'failed_tests': total - passCount,
      'total_tests': total,
    },
    'test_cases': testCases,
    'analyze_result': analyze,
  }));
  print('--- GRADE_RESULT_END ---');
}

// Chọn thông báo lỗi chi tiết nhất: ưu tiên print 'EXCEPTION CAUGHT' (widget test),
// nếu không có thì dùng error event (logic test).
String _failureReason(String? error, List<String>? prints) {
  if (prints != null) {
    for (final p in prints) {
      if (p.contains('TestFailure') ||
          p.contains('Expected:') ||
          p.contains('EXCEPTION CAUGHT')) {
        return _clean(p);
      }
    }
  }
  return _clean(error ?? '');
}

String _clean(String s) {
  final t = s
      .replaceAll(RegExp(r'[═╡╞║╔╗╚╝╠╣╬─│┌┐└┘├┤┬┴┼]+'), ' ')
      .replaceAll('EXCEPTION CAUGHT BY FLUTTER TEST FRAMEWORK', '')
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
  return t.length > 600 ? t.substring(0, 600) : t;
}

// ĐIỂM THEO ĐỘ KHÓ: basic=1, intermediate=2, advanced=3 (null nếu không khai difficulty → dùng weight).
double? _difficultyPoints(String? d) {
  switch (d?.trim().toLowerCase()) {
    case 'basic':
      return 1;
    case 'intermediate':
      return 2;
    case 'advanced':
      return 3;
  }
  return null;
}

Future<Map<String, dynamic>> _analyzeLib() async {
  try {
    final r = await Process.run(
      'dart', ['analyze', 'lib', '--format', 'machine'],
      runInShell: true,
    );
    final warnings = <Map<String, dynamic>>[];
    bool hasError = false;
    for (final line in r.stdout.toString().split('\n')) {
      // SEVERITY|TYPE|CODE|FILE|LINE|COL|LENGTH|MESSAGE
      final p = line.split('|');
      if (p.length >= 8) {
        final severity = p[0].toLowerCase();
        if (severity == 'error') hasError = true;
        warnings.add({
          'file': p[3],
          'message': p.sublist(7).join('|'),
          'severity': severity,
        });
      }
    }
    return {'has_error': hasError, 'warnings': warnings};
  } catch (_) {
    return {'has_error': false, 'warnings': <Map<String, dynamic>>[]};
  }
}
```

## ĐỊNH DẠNG ĐẦU RA (làm đúng thứ tự)
1. **Giả định & hợp đồng API** (gạch đầu dòng ngắn gọn: file/class/hàm/text UI).
2. Khối code `dart` tên `exam_test.dart`.
3. Khối code `dart` tên `grader.dart` (nguyên văn template trên).
4. Khối code `json` tên `skills_matrix.json`.
5. (Tuỳ chọn) Khối code `lib/` lời giải mẫu để giảng viên tự kiểm tra test PASS với bài đúng.
6. Lệnh nén ZIP (3 file ở GỐC zip, KHÔNG bọc thư mục con):
   - PowerShell: `Compress-Archive -Path exam_test.dart, grader.dart, skills_matrix.json -DestinationPath FLUTTER_PE_XX.zip -Force`
   - Bash: `zip FLUTTER_PE_XX.zip exam_test.dart grader.dart skills_matrix.json`

KIỂM TRA LẦN CUỐI trước khi trả lời: tên mọi test ↔ key matrix khớp 100%; chỉ dùng flutter/flutter_test;
import `../lib/...` đúng tên file đã nêu trong giả định; MỖI testcase có `skill_code` (trong danh mục) +
`difficulty`; `weight` ĐÚNG theo độ khó (basic=1, intermediate=2, advanced=3).

=========================== ĐỀ BÀI ===========================
<DÁN ĐỀ BÀI / YÊU CẦU BÀI THI VÀO ĐÂY. Càng rõ tên file/class/hàm và text UI mong đợi, test càng chính xác.>
````

---

## Ví dụ minh hoạ (đề "Quản lý sự kiện")

Để bạn hình dung kết quả mong đợi, đây là bộ testcase thật của đề mẫu PE_01:

- **Giả định**: SV tạo `lib/event.dart` (class `Event` có `id, title, capacity, registeredCount`,
  getter `canRegister`, hàm `register()`), và `lib/event_screen.dart` (widget `EventScreen` hiển thị
  ListView các sự kiện, nút `Đăng ký`, text `Đã đăng ký: x/y`, đổi thành `Đã đầy` khi hết chỗ).
- **`skills_matrix.json`** (weight theo độ khó: basic=1, intermediate=2, advanced=3):

```json
{
  "TC_LOGIC_01": { "skill_code": "DART_CLASSES", "difficulty": "basic", "weight": 1,
    "skill": "Class & constructor", "name": "Khởi tạo Event",
    "description": "Tạo Event và truy xuất đủ thuộc tính.",
    "expected": "title, capacity đúng; registeredCount = 0." },
  "TC_LOGIC_02": { "skill_code": "DART_COLLECTIONS", "difficulty": "intermediate", "weight": 2,
    "skill": "Collection & logic thuần", "name": "canRegister",
    "description": "Còn chỗ thì canRegister=true, đầy thì false.",
    "expected": "canRegister đổi theo registeredCount." },
  "TC_UI_03": { "skill_code": "STATE_BASIC", "difficulty": "advanced", "weight": 3,
    "skill": "setState", "name": "Cập nhật khi bấm Đăng ký",
    "description": "Bấm nút làm tăng số đã đăng ký trên UI.",
    "expected": "Hiển thị 'Đã đăng ký: 1/3' sau khi bấm." }
}
```
> `weight` suy ra TỪ `difficulty`, không tự chọn. Không cần tổng = 10 (grader chuẩn hóa). Mỗi key trùng tên test.

- Mỗi `TC_*` ở trên tương ứng 1 `test()`/`testWidgets()` cùng tên trong `exam_test.dart`,
  kiểm tra một tiêu chí (khởi tạo Event, logic `canRegister`, `register()`, render ListView,
  hiển thị số lượng, cập nhật khi bấm nút, trạng thái "Đã đầy").

---

## Prompt 2 — Sinh starter code (khung `lib/` phát cho SV)

Sau khi có `exam_test.dart` từ Prompt 1, dán prompt dưới đây vào Claude **kèm chính `exam_test.dart` đó**
(và nếu có: `skills_matrix.json`, phần "Giả định" của bước 1, và đề bài gốc). Claude sẽ sinh khung `lib/`
khớp 100% với test — SV chỉ điền phần logic.

> Vì sao cần: test gọi đúng tên file/class/hàm/text. Nếu SV tự đặt tên lệch → code không biên dịch → 0 điểm
> oan. Starter code phát kèm đề đảm bảo mọi SV bắt đầu từ đúng "hợp đồng" mà test mong đợi.

````text
Bạn là trợ lý tạo STARTER CODE (khung lib/ phát cho sinh viên) cho hệ thống chấm thi Flutter "Grader App".
Bạn được cung cấp bộ TESTCASE (exam_test.dart) ĐÃ CHỐT ở cuối. Nhiệm vụ: sinh thư mục lib/ KHUNG sao cho
SV điền logic vào là chạy được các test đó. Starter PHẢI khớp 100% hợp đồng mà test gọi.

## NGUYÊN TẮC SỐNG CÒN
1. ĐỌC KỸ exam_test.dart bên dưới. Trích xuất MỌI ký hiệu mà test tham chiếu:
   - Mọi import '../lib/<file>.dart'  → PHẢI tạo đúng các file đó trong lib/.
   - Mọi class, constructor (kể cả named params + kiểu), field, getter, setter, method + signature
     và kiểu trả về mà test gọi tới.
   - Mọi chuỗi text UI mà test tìm: find.text('...'), find.widgetWithText(..., '...').
2. Starter PHẢI BIÊN DỊCH ĐƯỢC (compile sạch). Code không compile = SV bị 0 điểm oan.
   → Khai báo đầy đủ class/field/method ĐÚNG signature; thân hàm để STUB an toàn + // TODO.
3. Starter là KHUNG, KHÔNG phải lời giải. Phần LOGIC/HÀNH VI để trống cho SV:
   - Method trả kiểu non-void: trả default an toàn (false, 0, '', <List>[], ...) kèm // TODO.
   - KHÔNG dùng throw UnimplementedError() ở getter/field được build() dùng (gây crash khó hiểu);
     ưu tiên default an toàn để app vẫn render được.
4. GIỮ ĐÚNG TÊN tuyệt đối: file, class, field, method, named param trùng KHÍT test (phân biệt hoa/thường).
   Không đổi tên, không thêm required param mà test không truyền, không bỏ bớt thành phần test cần.
5. Chỉ dùng package flutter (material). Không package ngoài.
6. Với DỮ LIỆU/NHÃN CỐ ĐỊNH thuộc đề (vd danh sách sự kiện mẫu, tiêu đề tĩnh, nhãn nút) mà test khẳng định:
   nên ĐẶT SẴN trong starter (vì đó là spec, không phải phần SV nghĩ ra) hoặc ghi rõ giá trị mong đợi
   trong // TODO để SV nhập đúng. Mục tiêu: app CHẠY ĐƯỢC; phần SV làm là LOGIC/HÀNH VI.
7. Widget: build() trả Scaffold tối thiểu, dựng sẵn cấu trúc + // TODO. Text ĐỘNG (vd 'Đã đăng ký: x/y')
   để SV tự render theo state.
8. (Khuyến nghị) Thêm lib/main.dart để SV chạy thử (flutter run), wire tới màn hình chính.
   main.dart KHÔNG bị test gọi nên tự do, miễn compile.

## ĐỊNH DẠNG ĐẦU RA
1. "BẢN ĐỒ HỢP ĐỒNG": bảng đối chiếu MỌI symbol test cần ↔ khai báo ở file nào (tự kiểm khớp).
2. Mỗi file lib/: 1 khối code dart ghi rõ đường dẫn (vd lib/event.dart, lib/event_screen.dart, lib/main.dart).
3. "HƯỚNG DẪN SV": liệt kê mỗi // TODO cần làm gì (ngắn gọn).
4. Lệnh nén nộp bài (zip CHỨA thư mục lib ở gốc):
   - PowerShell: Compress-Archive -Path lib -DestinationPath MaSV_HoTen.zip -Force
   - Bash:       zip -r MaSV_HoTen.zip lib

KIỂM TRA LẦN CUỐI (BẮT BUỘC liệt kê checklist): với MỖI import/class/constructor/param/field/getter/
method/text mà exam_test.dart dùng → đã có trong starter, ĐÚNG signature, starter COMPILE SẠCH.

===================== TESTCASE ĐÃ CHỐT (đầu vào) =====================
<DÁN exam_test.dart Ở ĐÂY. Có thể dán kèm skills_matrix.json, phần "Giả định" của Prompt 1, và đề bài gốc.>
````

### Quy trình khuyến nghị cho giảng viên
1. Chạy **Prompt 1** với đề bài → nhận `exam_test.dart`, `grader.dart`, `skills_matrix.json` → nén ZIP → upload *Cấu hình Đề thi*.
2. Chạy **Prompt 2** với chính `exam_test.dart` vừa nhận → nhận khung `lib/` → phát cho SV làm đề.
3. SV điền logic vào khung → nén `MaSV_HoTen.zip` (chứa `lib/`) → nộp → chấm hàng loạt ở *Dashboard*.

> Nhờ dùng chung một `exam_test.dart` làm nguồn sự thật, tên file/class/hàm/text giữa **đề khung** và
> **bộ chấm** luôn khớp — loại bỏ rủi ro SV mất điểm do compile lỗi vì đặt tên sai.
