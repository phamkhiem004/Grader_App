import 'dart:convert';
import 'dart:io';

/// Phiên bản engine ĐÃ CHẤM, để truy vết khi kết quả bất thường.
///
/// PHẢI tăng mỗi lần đổi hành vi engine. Quan trọng vì engine được chép ĐÓNG BĂNG
/// vào thư mục testcase của đề lúc publish: đề publish trước bản này vẫn chạy engine
/// cũ khi chấm lại, và khi đó trường `engine_version` sẽ VẮNG MẶT.
/// 2.1.0 — P3b: sửa ba khiếm khuyết CHẤM SAI ĐIỂM (xem exam_test.dart `_goneByKey`,
/// `_settle`, `_validationErrorFor`). Bài chấm bằng bản < 2.1.0 có thể sai điểm.
/// 2.2.0 — P4: phát `status: not_run` + `executed` + `not_run_tests`. Điểm KHÔNG đổi.
/// 2.3.0 — P5: phát kênh quan sát có cấu trúc (`observation`). Điểm KHÔNG đổi — quan sát chỉ
/// là hiệu ứng lề, mọi assertion giữ nguyên.
/// 2.4.0 — A2: `subject` nhận thêm giá trị `checkbox` (trước đó ô chọn rơi về `button`).
/// Điểm KHÔNG đổi — chỉ đổi từ vựng của quan sát, không đổi một assertion nào.
/// 2.5.0 — A2: sửa khiếm khuyết CHẤM SAI ĐIỂM thứ tư — `WIDGET_SEMANTICS_LABEL` trả
/// SemanticsHandle bằng addTearDown nên KHÔNG BAO GIỜ ĐẠT ĐƯỢC. Bài chấm bằng bản
/// < 2.5.0 mà có testcase runner này thì SAI ĐIỂM, phải chấm lại.
/// Kèm theo: chỉ giữ khối chẩn đoán ĐẦU TIÊN của mỗi test (xem `_firstBlock`) — trước đó
/// một lỗi bố cục lặp lại mỗi khung hình làm `result_json` phình lên 745 KB. Điểm không đổi.
/// 2.6.0 — A2b: vá hai lỗ hổng của kênh quan sát, cả hai đều làm `observation` null nên sinh
/// viên nhận log tiếng Anh. (a) tám chỗ `tester.tap` không kiểm nút có tồn tại — nay qua `_tap`;
/// (b) `_assertTargetType` `fail()` trần — nay phát `TYPE_MISMATCH` (`kind` thứ 14, chín runner
/// gọi tới). Điểm KHÔNG đổi: hai phép kiểm này không nghiêm hơn `tester.tap`, chỉ báo khác.
const String kEngineVersion = 'COMMON_V1-2.6.0';

/// PHẢI khớp hằng cùng tên trong `exam_test.dart` — hai chương trình Dart riêng biệt,
/// không import nhau nên không chia sẻ được hằng số.
const String kBootFailedMarker = '###GRADER_BOOT_FAILED###';
const String kObservationMarker = '###GRADER_OBS###';

Future<void> main() async {
  final matrix = _loadMatrix();
  final process = await Process.run(
    Platform.isWindows ? 'flutter.bat' : 'flutter',
    <String>['test', '--no-pub', '--reporter=json', 'test/exam_test.dart'],
    runInShell: false,
  );

  stdout.write(process.stdout);
  stderr.write(process.stderr);

  final runs = _parseReporter(process.stdout.toString());
  final output = <String, dynamic>{
    'mode': 'common_semantic_key_v1',
    'test_cases': <Map<String, dynamic>>[],
  };
  final cases = output['test_cases'] as List<Map<String, dynamic>>;

  // Cả bộ test không khởi động được (lib/ không biên dịch, runner crash) thì KHÔNG một
  // testcase nào của matrix có kết quả. Đây là lỗi RUNNER, khác hẳn "bài làm sai".
  final ranAny = matrix.keys.any(runs.containsKey);
  final runnerError = ranAny ? null : _shorten(_processError(process), 400);
  var passed = 0;
  var notRun = 0;
  var earned = 0.0;
  var total = 0.0;
  for (final entry in matrix.entries) {
    final id = entry.key;
    final metadata = _asMap(entry.value);
    final weight = _number(metadata, 'weight', 1);
    final runner = (metadata['runner'] ?? '').toString();
    final result = runs[id];
    final ok = result?['passed'] == true;

    // `not_run` = CHƯA CÓ CƠ HỘI CHẠY, khác hẳn `failed` (đã chạy tới nơi và không đạt).
    // Chỉ gán khi QUAN SÁT ĐƯỢC, tuyệt đối không suy đoán từ thứ tự hay tầng của test:
    //
    //  a) Cả bộ test không khởi động được ⇒ không testcase nào có kết quả.
    //  b) `_boot()` chung ném lỗi ⇒ runner chưa tới phần khẳng định của chính nó
    //     (engine in kBootFailedMarker ngay tại chỗ, xem exam_test.dart).
    //
    // Ngoại lệ của (b): yêu cầu của APP_BOOT CHÍNH LÀ khởi động được, nên nó ĐÃ chạy và ĐÃ
    // có phán quyết — gắn `not_run` cho nó là nói sai, và làm mất luôn nguyên nhân gốc.
    final bootFailed = result?['bootFailed'] == true;
    final isNotRun =
        !ok && (!ranAny || (bootFailed && runner != 'APP_BOOT'));

    if (ok) {
      passed++;
      earned += weight;
    } else if (isNotRun) {
      notRun++;
    }
    total += weight;

    // `actual` của test chưa chạy phải nêu VÌ SAO chưa chạy, không được lẫn chẩn đoán
    // (SPEC mục 5.4). Log thô của runner là tiếng Anh + đường dẫn file nên chỉ nằm ở
    // grading_result.runner_error cho giáo viên, không đi tới sinh viên.
    final String actual;
    if (ok) {
      actual = 'Đã đáp ứng yêu cầu';
    } else if (isNotRun) {
      actual = ranAny
          ? 'Chưa chạy: ứng dụng không mở được nên bộ chấm chưa kiểm tới yêu cầu này.'
          : 'Chưa chạy: bộ test không khởi động được nên chưa kiểm tới yêu cầu này.';
    } else {
      actual = (result?['message'] ?? 'Test thất bại.').toString();
    }

    cases.add(<String, dynamic>{
      'test_id': id,
      'name': metadata['name'] ?? id,
      'status': ok ? 'passed' : (isNotRun ? 'not_run' : 'failed'),
      // Trường DẪN XUẤT của `status`, để bên đọc lọc nhanh mà không phải so chuỗi.
      'executed': !isNotRun,
      'score': ok ? weight : 0,
      'max_score': weight,
      'difficulty': metadata['difficulty'] ?? 'basic',
      'skill_code': metadata['skill_code'] ?? 'N/A',
      'expected': metadata['expected']?.toString() ?? id,
      'actual': actual,
      // Quan sát MÁY ĐỌC; backend dựng `actual` tiếng Việt từ đây (SPEC mục 5.3).
      // `actual` ở trên chỉ là PHƯƠNG ÁN CHỐNG RỖNG cho backend cũ — câu chữ chính thức do
      // backend render, vì file này bị đóng băng vào từng đề lúc publish.
      'observation': ok
          ? null
          : isNotRun
              ? <String, dynamic>{'kind': ranAny ? 'NOT_RUN_BOOT' : 'NOT_RUN_SUITE'}
              : result?['observation'],
    });
  }

  final score = total == 0 ? 0.0 : earned / total * 10;
  output['diem'] = _round(score);
  output['soTestPass'] = passed;
  output['soTestFail'] = cases.length - passed;
  output['tongSoTest'] = cases.length;
  output['chiTiet'] = cases
      .map((item) => <String, dynamic>{
            'name': item['test_id'],
            'status': item['status'] == 'passed' ? 'PASS' : 'FAILED',
            'message': item['actual'],
          })
      .toList();
  output['grading_result'] = <String, dynamic>{
    'score': _round(score),
    // Engine chung kiểm hoàn toàn blackbox qua semantic key nên KHÔNG có cổng contract
    // tên public: hai trường dưới luôn bằng score và false. Vẫn phải CÓ MẶT để hợp đồng
    // không đổi theo engine (SPEC mục 2) — bên đọc không phải biết engine nào đã chấm.
    'raw_score_before_contract_gate': _round(score),
    'total_raw_score': earned,
    'passed_tests': passed,
    // `failed_tests` GIỮ nghĩa cũ = mọi test không passed, `not_run` là TẬP CON của nó
    // (SPEC mục 2) — đổi nghĩa sẽ làm lệch số liệu và biểu đồ đang có của frontend.
    'failed_tests': cases.length - passed,
    'total_tests': cases.length,
    'not_run_tests': notRun,
    'earned_weight': earned,
    'total_weight': total,
    'blocked': false,
    'contract_violation': false,
    'runner_error': runnerError,
    'engine_version': kEngineVersion,
  };

  stdout.writeln('--- GRADE_RESULT_START ---');
  stdout.writeln(jsonEncode(output));
  stdout.writeln('--- GRADE_RESULT_END ---');
}

Map<String, dynamic> _loadMatrix() {
  for (final path in <String>['test/skills_matrix.json', 'skills_matrix.json']) {
    final file = File(path);
    if (!file.existsSync()) continue;
    final value = jsonDecode(file.readAsStringSync());
    if (value is Map) return _asMap(value);
  }
  return <String, dynamic>{};
}

Map<String, dynamic> _asMap(dynamic value) {
  if (value is! Map) return <String, dynamic>{};
  return <String, dynamic>{
    for (final entry in value.entries) entry.key.toString(): entry.value,
  };
}

Map<String, Map<String, dynamic>> _parseReporter(String output) {
  final namesById = <int, String>{};
  final errorsById = <int, List<String>>{};
  final dumpsById = <int, List<String>>{};
  final bootFailedIds = <int>{};
  final obsById = <int, Map<String, dynamic>>{};
  final runs = <String, Map<String, dynamic>>{};
  for (final line in output.split('\n')) {
    final raw = line.trim();
    if (raw.isEmpty) continue;
    dynamic event;
    try {
      event = jsonDecode(raw);
    } catch (_) {
      continue;
    }
    if (event is! Map) continue;
    final type = event['type']?.toString();
    if (type == 'testStart' && event['test'] is Map) {
      final test = event['test'] as Map;
      final id = (test['id'] as num?)?.toInt();
      final name = test['name']?.toString();
      if (id != null && name != null) namesById[id] = name;
    } else if (type == 'print') {
      // testWidgets NUỐT exception: event `error` chỉ còn câu bao "Test failed. See
      // exception logs above." + tên test, còn CHẨN ĐOÁN THẬT nằm trong khối dump mà
      // flutter_test in ra dưới dạng event `print`. Không đọc event này thì `actual`
      // vĩnh viễn vô nghĩa (SPEC mục 5.1).
      final id = (event['testID'] as num?)?.toInt();
      final message = event['message']?.toString() ?? '';
      if (id == null) continue;
      // Engine tự khai "chưa chạy tới phần khẳng định của mình" ngay tại `_boot()`.
      if (message.contains(kBootFailedMarker)) bootFailedIds.add(id);
      // Kênh quan sát có cấu trúc: giữ quan sát ĐẦU TIÊN của mỗi test. Cái đầu là nguyên
      // nhân, các cái sau (nếu có, ví dụ trong GROUP) chỉ là hệ quả kéo theo.
      final observation = _parseObservation(message);
      if (observation != null) obsById.putIfAbsent(id, () => observation);
      // Lọc chặt: chỉ nhận dump của flutter_test. print thường (log của bài sinh viên,
      // cảnh báo "tap() derived an Offset...") không phải chẩn đoán, gom vào là nhiễu.
      if (_isFailureDump(message)) {
        dumpsById.putIfAbsent(id, () => <String>[]).add(message);
      }
    } else if (type == 'error') {
      final id = (event['testID'] as num?)?.toInt();
      if (id != null) {
        errorsById.putIfAbsent(id, () => <String>[]).add(event['error']?.toString() ?? '');
      }
    } else if (type == 'testDone') {
      final id = (event['testID'] as num?)?.toInt();
      final name = id == null ? null : namesById[id];
      if (name == null) continue;
      final ok = event['result'] == 'success' || event['skipped'] == true;
      runs[name] = <String, dynamic>{
        'passed': ok,
        'bootFailed': bootFailedIds.contains(id),
        'observation': obsById[id],
        'message': ok
            ? 'Đã đáp ứng yêu cầu'
            : _failureMessage(dumpsById[id], errorsById[id]),
      };
    }
  }
  return runs;
}

/// Bóc payload quan sát có cấu trúc khỏi một dòng print; null nếu dòng đó không phải quan sát.
///
/// Chỉ CHUYỂN TIẾP dữ liệu, không diễn giải: việc render tiếng Việt nằm ở backend để sửa câu
/// chữ không phải nâng engine cho mọi đề đã publish (SPEC mục 5.3).
Map<String, dynamic>? _parseObservation(String message) {
  final at = message.indexOf(kObservationMarker);
  if (at < 0) return null;
  var json = message.substring(at + kObservationMarker.length);
  // print có thể gộp nhiều dòng; payload luôn nằm gọn trên dòng đầu.
  final nl = json.indexOf('\n');
  if (nl >= 0) json = json.substring(0, nl);
  json = json.trim();
  try {
    final value = jsonDecode(json);
    return value is Map ? _asMap(value) : null;
  } catch (_) {
    return null;
  }
}

/// Khối dump lỗi của flutter_test, phân biệt với print thường của ứng dụng.
bool _isFailureDump(String message) {
  final low = message.toLowerCase();
  return low.contains('exception caught by') ||
      low.contains('was thrown running a test') ||
      low.contains('was thrown building');
}

/// Chẩn đoán của một test hỏng: ưu tiên dump (có nội dung thật), sau đó mới tới
/// event `error` (thường chỉ là câu bao). Chỉ giữ khối ĐẦU TIÊN, xem [_firstBlock].
String _failureMessage(List<String>? dumps, List<String>? errors) {
  for (final source in <List<String>?>[dumps, errors]) {
    for (final value in source ?? const <String>[]) {
      if (value.trim().isNotEmpty) return _firstBlock(value, 4000);
    }
  }
  return 'Test thất bại.';
}

/// Khối chẩn đoán ĐẦU TIÊN, cắt ở [max] ký tự.
///
/// Vì sao không gộp cả loạt như trước: lỗi bố cục được flutter_test báo LẠI mỗi khung hình,
/// nên gộp hết là hàng trăm bản sao của cùng một khối — đo được **174.928 ký tự cho MỘT
/// testcase** (745 KB cho một bài nộp), mà chuỗi này chảy thẳng vào `result_json` lưu DB rồi
/// ra API và ra file mẫu gửi bên đọc. Khối đầu là nguyên nhân, các khối sau là hệ quả kéo
/// theo — cùng lý do kênh quan sát chỉ giữ quan sát đầu tiên.
///
/// KHÔNG dùng [_shorten] ở đây: nó gộp cả dấu xuống dòng, mà bộ phân loại lỗi phía backend và
/// trang Lịch sử còn đọc cấu trúc nhiều dòng của khối dump cho dữ liệu chưa có `observation`.
String _firstBlock(String value, int max) {
  final text = value.trim();
  return text.length > max ? '${text.substring(0, max).trimRight()}\n…' : text;
}

String _processError(ProcessResult process) {
  final stderrText = process.stderr.toString().trim();
  if (stderrText.isNotEmpty) return stderrText;
  final stdoutText = process.stdout.toString().trim();
  return stdoutText.isEmpty ? 'Test không tạo được kết quả.' : stdoutText;
}

double _number(Map<String, dynamic> map, String key, double fallback) {
  final value = map[key];
  return value is num ? value.toDouble() : double.tryParse('$value') ?? fallback;
}

double _round(double value) => double.parse(value.toStringAsFixed(2));

/// Gộp khoảng trắng + cắt ngắn để log runner không phình result_json lưu DB.
String _shorten(String value, int max) {
  final text = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  return text.length > max ? '${text.substring(0, max).trim()}…' : text;
}
