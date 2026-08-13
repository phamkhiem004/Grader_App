import 'dart:async';
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
/// 2.7.0 — A2c: NGUYÊN NHÂN GỐC thắng triệu chứng. `flutter_test` KHÔNG dừng thân test khi
/// handler của bài ném lỗi, nên assertion của runner hỏng vì HỆ QUẢ và ghi đè nguyên nhân:
/// app ném RangeError lúc bấm xoá mà báo cáo nói "không thấy hộp thoại nào", error_code
/// WIDGET_NOT_FOUND. Nay mọi thao tác đi kèm `_failIfActionThrew` (10 chỗ) phát `ACTION_FAILED`
/// — kind cố ý KHÔNG mang error_code để classifier giữ độ mịn (RANGE_ERROR/NULL_ERROR/
/// STATE_ERROR). Điểm KHÔNG đổi: lỗi chưa lấy đi vẫn làm flutter_test đánh hỏng test.
/// 2.8.0 — chạy các lô testcase nhỏ trong process riêng, giới hạn cả thời gian mỗi process
/// lẫn toàn bộ lượt chấm. Một app treo không còn giữ Docker quá bốn phút; lô sau vẫn có cơ
/// hội chạy mà không phải trả chi phí khởi động Flutter cho từng testcase.
/// 2.9.0 — source-contract loại comment Dart/YAML trước khi tìm token và hỗ trợ
/// sourceChecksJson để gắn assertion vào đúng từng file; không còn pass do comment
/// hoặc do token nằm nhầm file. UI contract của bộ mới mặc định bắt buộc ValueKey.
/// 3.0.0 — hỗ trợ lô testcase có thể cấu hình; mặc định gom suite vào một process để Flutter
/// chỉ compile một lần. GROUP dùng một lần boot để các bước lồng nhau giữ đúng trạng thái.
/// Thêm các flow CRUD/persistence/responsive tái sử dụng và SQLite FFI không dùng isolate.
/// 3.2.0: run real SQLite, isolate headless image codec failures from CRUD,
/// and report rubric criteria separately from executable scenarios.
/// 3.3.0: isolate APP_BOOT as a preflight process and report the last execution
/// stage so student, testcase and infrastructure timeouts are not conflated.
/// 3.4.0: student-stage timeouts become explicit student failures; testcase or
/// unknown timeouts still require manual review. Source contracts ignore harmless
/// formatting differences and emit structured contract-violation evidence.
const String kEngineVersion = 'COMMON_V1-3.4.0';

/// PHẢI khớp hằng cùng tên trong `exam_test.dart` — hai chương trình Dart riêng biệt,
/// không import nhau nên không chia sẻ được hằng số.
const String kBootFailedMarker = '###GRADER_BOOT_FAILED###';
const String kObservationMarker = '###GRADER_OBS###';
const String kStageMarker = '###GRADER_STAGE###';
const int kProcessTimeoutExitCode = -124;
// Compile Flutter chiếm phần lớn thời gian. Các scenario đã dùng dữ liệu riêng và
// SQLite noIsolate, nên gom toàn bộ suite vào một process ổn định hơn nhiều so với
// compile lại 3-4 lần. Có thể override bằng GRADER_BATCH_SIZE khi cần chia lô.
const int kDefaultBatchSize = 64;
const int kDefaultBatchTimeoutSeconds = 180;
const int kDefaultPreflightTimeoutSeconds = 60;
const int kDefaultTotalTimeoutSeconds = 210;

Future<void> main() async {
  final matrix = _loadMatrix();
  final runs = <String, Map<String, dynamic>>{};
  final runnerErrors = <String>[];
  final batchSize = _positiveEnvInt('GRADER_BATCH_SIZE', kDefaultBatchSize);
  final batchTimeout = Duration(
    seconds: _positiveEnvInt(
      'GRADER_BATCH_TIMEOUT_SECONDS',
      kDefaultBatchTimeoutSeconds,
    ),
  );
  final totalTimeout = Duration(
    seconds: _positiveEnvInt(
      'GRADER_TOTAL_TIMEOUT_SECONDS',
      kDefaultTotalTimeoutSeconds,
    ),
  );
  final preflightTimeout = Duration(
    seconds: _positiveEnvInt(
      'GRADER_PREFLIGHT_TIMEOUT_SECONDS',
      kDefaultPreflightTimeoutSeconds,
    ),
  );
  final totalWatch = Stopwatch()..start();
  var totalBudgetExceeded = false;
  final testIds = matrix.keys.toList(growable: false);
  String? appBootId;
  for (final entry in matrix.entries) {
    if (_asMap(entry.value)['runner']?.toString() == 'APP_BOOT') {
      appBootId = entry.key;
      break;
    }
  }
  // APP_BOOT runs alone first. A synchronous loop in student_app.main() can then
  // be attributed to app boot and stopped quickly instead of consuming the whole suite.
  final batches = <List<String>>[];
  if (appBootId != null) batches.add(<String>[appBootId]);
  final remainingIds = testIds
      .where((id) => id != appBootId)
      .toList(growable: false);
  for (var offset = 0; offset < remainingIds.length; offset += batchSize) {
    final end = offset + batchSize < remainingIds.length
        ? offset + batchSize
        : remainingIds.length;
    batches.add(remainingIds.sublist(offset, end));
  }

  for (final batch in batches) {
    final isPreflight =
        appBootId != null && batch.length == 1 && batch.first == appBootId;
    final remaining = totalTimeout - totalWatch.elapsed;
    if (remaining <= Duration.zero) {
      totalBudgetExceeded = true;
      break;
    }
    final configuredLimit = isPreflight ? preflightTimeout : batchTimeout;
    final limit = remaining < configuredLimit ? remaining : configuredLimit;
    final process = await _runFlutterBatch(batch, limit);
    stdout.write(process.stdout);
    stderr.write(process.stderr);

    final parsed = _parseReporter(process.stdout.toString());
    runs.addAll(parsed);
    if (process.exitCode == kProcessTimeoutExitCode) {
      // --concurrency=1 bảo đảm testcase đầu tiên chưa có testDone là testcase đang treo.
      // Các testcase còn lại trong lô chưa có cơ hội chạy và sẽ được ghi not_run.
      final missing = batch.where((id) => !runs.containsKey(id)).toList();
      if (missing.isNotEmpty) {
        final timedOutId = missing.first;
        final diagnostic = _timeoutDiagnostic(
          process.stdout.toString(),
          timedOutId,
          limit.inSeconds,
        );
        runs[timedOutId] = <String, dynamic>{
          'passed': false,
          'bootFailed': false,
          'observation': <String, dynamic>{
            'kind': 'PROCESS_TIMEOUT',
            'timeout_seconds': limit.inSeconds,
            ...diagnostic,
          },
          'message': 'Testcase vượt quá ${limit.inSeconds} giây và đã bị dừng.',
        };
        runnerErrors.add(
          '$timedOutId: process timeout trong lô sau ${limit.inSeconds}s',
        );
      } else {
        runnerErrors.add(
          'Lô ${batch.join(',')}: process timeout sau ${limit.inSeconds}s',
        );
      }
      if (isPreflight) break;
      continue;
    }

    final missing = batch.where((id) => !runs.containsKey(id)).toList();
    if (missing.isNotEmpty) {
      final batchLabel = batch.join(',');
      runnerErrors.add('$batchLabel: ${_shorten(_processError(process), 400)}');
      if (parsed.isEmpty) {
        // Lỗi compile/runner dùng chung: chạy lô tiếp theo chỉ lặp lại cùng lỗi.
        break;
      }
    }
    if (batch.any((id) => runs[id]?['bootFailed'] == true)) {
      // _boot() là đường chung của toàn bộ runner; thử tiếp chỉ lặp lại cùng lỗi gốc.
      break;
    }
  }
  totalWatch.stop();
  if (totalBudgetExceeded) {
    runnerErrors.add(
      'GRADER_TOTAL_TIMEOUT after ${totalTimeout.inSeconds}s; các testcase còn lại chưa chạy.',
    );
  }

  final output = <String, dynamic>{
    // Giữ nguyên mode để API/report cũ không phải đổi; engine_version cho biết cơ chế cô lập.
    'mode': 'common_semantic_key_v1',
    'test_cases': <Map<String, dynamic>>[],
  };
  final cases = output['test_cases'] as List<Map<String, dynamic>>;

  // Cả bộ test không khởi động được (lib/ không biên dịch, runner crash) thì KHÔNG một
  // testcase nào của matrix có kết quả. Đây là lỗi RUNNER, khác hẳn "bài làm sai".
  final ranAny = matrix.keys.any(runs.containsKey);
  final runnerError = runnerErrors.isEmpty
      ? null
      : _shorten(runnerErrors.join('\n'), 2000);
  final runnerDiagnostic = _runnerDiagnostic(
    runnerError: runnerError,
    totalBudgetExceeded: totalBudgetExceeded,
    ranAny: ranAny,
    runs: runs,
  );
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
    final resultObservation = _asMap(result?['observation']);
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
        !ok && (result == null || (bootFailed && runner != 'APP_BOOT'));

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
      actual = bootFailed
          ? 'Chưa chạy: ứng dụng không mở được nên bộ chấm chưa kiểm tới yêu cầu này.'
          : totalBudgetExceeded
          ? 'Chưa chạy: bộ chấm đã hết ngân sách thời gian an toàn.'
          : ranAny
          ? 'Chưa chạy: runner đã dừng sau một lỗi dùng chung.'
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
      'error_origin': ok
          ? null
          : totalBudgetExceeded
          ? 'ENVIRONMENT'
          : resultObservation['kind'] == 'PROCESS_TIMEOUT'
          ? (resultObservation['origin'] ?? 'UNDETERMINED')
          : bootFailed
          ? 'STUDENT'
          : isNotRun
          ? 'UNDETERMINED'
          : 'STUDENT',
      'error_stage': ok
          ? null
          : bootFailed
          ? 'APP_BOOT'
          : resultObservation['kind'] == 'SOURCE_CONTRACT_VIOLATION' ||
                resultObservation['kind'] == 'SOURCE_POLICY_VIOLATION'
          ? 'SOURCE_CONTRACT'
          : resultObservation['kind'] == 'PROCESS_TIMEOUT'
          ? (resultObservation['stage'] ?? 'TESTCASE_EXECUTION')
          : isNotRun
          ? 'SUITE_STARTUP'
          : 'TESTCASE_EXECUTION',
      'requires_manual_review':
          !ok &&
          (totalBudgetExceeded ||
              isNotRun ||
              (resultObservation['kind'] == 'PROCESS_TIMEOUT' &&
                  resultObservation['manual_review'] != false)),
    });
  }

  final score = total == 0 ? 0.0 : earned / total * 10;
  output['diem'] = _round(score);
  output['soTestPass'] = passed;
  output['soTestFail'] = cases.length - passed;
  output['tongSoTest'] = cases.length;
  output['tongSoTieuChi'] = matrix.values
      .map((value) => _criterionCount(_asMap(value)))
      .fold<int>(0, (sum, count) => sum + count);
  output['chiTiet'] = cases
      .map(
        (item) => <String, dynamic>{
          'name': item['test_id'],
          'status': item['status'] == 'passed' ? 'PASS' : 'FAILED',
          'message': item['actual'],
        },
      )
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
    'total_scenarios': cases.length,
    'total_criteria': output['tongSoTieuChi'],
    'not_run_tests': notRun,
    'earned_weight': earned,
    'total_weight': total,
    'blocked': false,
    'contract_violation': false,
    'runner_error': runnerError,
    'diagnostic_code': runnerDiagnostic?['code'],
    'diagnostic_origin': runnerDiagnostic?['origin'],
    'diagnostic_stage': runnerDiagnostic?['stage'],
    'diagnostic_message': runnerDiagnostic?['message'],
    'requires_manual_review': runnerDiagnostic?['manual_review'] == true,
    'engine_version': kEngineVersion,
  };

  stdout.writeln('--- GRADE_RESULT_START ---');
  stdout.writeln(jsonEncode(output));
  stdout.writeln('--- GRADE_RESULT_END ---');
}

int _criterionCount(Map<String, dynamic> metadata) {
  final children = _asList(metadata['children']);
  if (children.isEmpty) return 1;
  return children
      .map((child) => _criterionCount(_asMap(child)))
      .fold<int>(0, (sum, count) => sum + count);
}

Map<String, dynamic> _timeoutDiagnostic(
  String reporterOutput,
  String testId,
  int timeoutSeconds,
) {
  final namesById = <int, String>{};
  String? lastStage;
  for (final line in reporterOutput.split('\n')) {
    dynamic event;
    try {
      event = jsonDecode(line.trim());
    } catch (_) {
      continue;
    }
    if (event is! Map) continue;
    if (event['type'] == 'testStart' && event['test'] is Map) {
      final test = event['test'] as Map;
      final id = (test['id'] as num?)?.toInt();
      final name = test['name']?.toString();
      if (id != null && name != null) namesById[id] = name;
      continue;
    }
    if (event['type'] != 'print') continue;
    final reporterId = (event['testID'] as num?)?.toInt();
    if (reporterId == null || namesById[reporterId] != testId) continue;
    final message = event['message']?.toString() ?? '';
    final at = message.lastIndexOf(kStageMarker);
    if (at < 0) continue;
    final candidate = message.substring(at + kStageMarker.length).trim();
    final match = RegExp(r'^[A-Z0-9_]+').firstMatch(candidate);
    if (match != null) lastStage = match.group(0);
  }

  return switch (lastStage) {
    'STUDENT_APP_BOOT' => <String, dynamic>{
      'diagnostic_code': 'STUDENT_APP_BOOT_TIMEOUT',
      'origin': 'STUDENT',
      'stage': 'APP_BOOT',
      'message':
          'Ứng dụng sinh viên bị kẹt trong lúc chạy main()/khởi tạo dữ liệu quá $timeoutSeconds giây.',
      'manual_review': false,
    },
    'STUDENT_UI_ACTION' => <String, dynamic>{
      'diagnostic_code': 'STUDENT_ACTION_TIMEOUT',
      'origin': 'STUDENT',
      'stage': 'UI_ACTION',
      'message':
          'Bài sinh viên bị kẹt khi xử lý thao tác giao diện quá $timeoutSeconds giây.',
      'manual_review': false,
    },
    'STUDENT_ASYNC_SETTLE' => <String, dynamic>{
      'diagnostic_code': 'STUDENT_ASYNC_TIMEOUT',
      'origin': 'STUDENT',
      'stage': 'ASYNC_STATE_SETTLE',
      'message':
          'Bài sinh viên không hoàn tất cập nhật trạng thái/I/O sau thao tác trong $timeoutSeconds giây.',
      'manual_review': false,
    },
    'STUDENT_PUBLIC_FUNCTION' => <String, dynamic>{
      'diagnostic_code': 'STUDENT_FUNCTION_TIMEOUT',
      'origin': 'STUDENT',
      'stage': 'LOGIC_EXECUTION',
      'message':
          'Hàm public của bài sinh viên không hoàn tất trong $timeoutSeconds giây.',
      'manual_review': false,
    },
    'TESTCASE_SOURCE_CHECK' ||
    'TESTCASE_CUSTOM_CODE' ||
    'TESTCASE_DISPATCH' ||
    'TESTCASE_ASSERTION' => <String, dynamic>{
      'diagnostic_code': 'TESTCASE_EXECUTION_TIMEOUT',
      'origin': 'TESTCASE',
      'stage': lastStage,
      'message':
          'Runner/testcase bị kẹt tại $lastStage sau $timeoutSeconds giây; không quy lỗi cho sinh viên.',
      'manual_review': true,
    },
    _ => <String, dynamic>{
      'diagnostic_code': 'TEST_PROCESS_TIMEOUT',
      'origin': 'UNDETERMINED',
      'stage': 'TESTCASE_EXECUTION',
      'message':
          'Tiến trình test vượt quá $timeoutSeconds giây nhưng không có đủ dấu mốc để quy lỗi.',
      'manual_review': true,
    },
  };
}

Map<String, dynamic>? _runnerDiagnostic({
  required String? runnerError,
  required bool totalBudgetExceeded,
  required bool ranAny,
  required Map<String, Map<String, dynamic>> runs,
}) {
  if (totalBudgetExceeded) {
    return <String, dynamic>{
      'code': 'GRADER_TOTAL_TIMEOUT',
      'origin': 'ENVIRONMENT',
      'stage': 'GRADER_TOTAL',
      'message': 'Bộ chấm đã hết ngân sách thời gian; cần kiểm tra tải máy và chấm tay phần chưa chạy.',
      'manual_review': true,
    };
  }
  final processTimeout = runs.values.any(
    (run) => (run['observation'] as Map?)?['kind'] == 'PROCESS_TIMEOUT',
  );
  if (processTimeout) {
    final timeoutRun = runs.values.firstWhere(
      (run) => (run['observation'] as Map?)?['kind'] == 'PROCESS_TIMEOUT',
    );
    final timeoutObservation = _asMap(timeoutRun['observation']);
    return <String, dynamic>{
      'code': timeoutObservation['diagnostic_code'] ?? 'TEST_PROCESS_TIMEOUT',
      'origin': timeoutObservation['origin'] ?? 'UNDETERMINED',
      'stage': timeoutObservation['stage'] ?? 'TESTCASE_EXECUTION',
      'message':
          timeoutObservation['message'] ??
          'Một testcase bị timeout nhưng chưa đủ bằng chứng để quy lỗi.',
      'manual_review': timeoutObservation['manual_review'] != false,
    };
  }
  if (runnerError == null || runnerError.isEmpty) return null;
  final lower = runnerError.toLowerCase();
  final studentCompileEvidence = RegExp(
    r'''(?:^|\s)(?:\.\./)?lib/[^\s:'"]+\.dart(?::\d+:\d+)?''',
    caseSensitive: false,
  ).hasMatch(runnerError);
  final testcaseCompileEvidence = RegExp(
    r'''(?:^|\s)test/(?:exam_test|grader)\.dart(?::\d+:\d+)?''',
    caseSensitive: false,
  ).hasMatch(runnerError);
  final publicContractEvidence =
      lower.contains('student_app.') &&
      (lower.contains('method not found') ||
          lower.contains('getter not found') ||
          lower.contains('undefined name') ||
          lower.contains("isn't defined"));
  if (!ranAny && studentCompileEvidence) {
    return <String, dynamic>{
      'code': 'STUDENT_COMPILE_ERROR',
      'origin': 'STUDENT',
      'stage': 'SOURCE_COMPILE',
      'message': 'Mã nguồn bài sinh viên không biên dịch được; xem runner_error để biết file và dòng đầu tiên.',
      'manual_review': false,
    };
  }
  if (!ranAny && publicContractEvidence) {
    return <String, dynamic>{
      'code': 'STUDENT_CONTRACT_COMPILE_ERROR',
      'origin': 'STUDENT',
      'stage': 'SOURCE_CONTRACT',
      'message':
          'Bài sinh viên thiếu hoặc đổi tên symbol public mà contract yêu cầu.',
      'manual_review': false,
    };
  }
  if (!ranAny && testcaseCompileEvidence) {
    return <String, dynamic>{
      'code': 'TESTCASE_COMPILE_ERROR',
      'origin': 'TESTCASE',
      'stage': 'SOURCE_COMPILE',
      'message':
          'Bộ testcase không biên dịch được; không quy thành 0 điểm sinh viên.',
      'manual_review': true,
    };
  }
  if (lower.contains('test/exam_test.dart') ||
      lower.contains('test/grader.dart') ||
      lower.contains('chưa có common runner')) {
    return <String, dynamic>{
      'code': 'TESTCASE_RUNNER_ERROR',
      'origin': 'TESTCASE',
      'stage': 'TESTCASE_SETUP',
      'message': 'Bộ testcase không khởi động đúng; không được quy thành 0 điểm của sinh viên.',
      'manual_review': true,
    };
  }
  if (!ranAny) {
    return <String, dynamic>{
      'code': 'SUITE_STARTUP_UNDETERMINED',
      'origin': 'UNDETERMINED',
      'stage': 'SUITE_STARTUP',
      'message': 'Bộ test không khởi động; cần đối chiếu log compile để xác định lỗi bài hay lỗi testcase.',
      'manual_review': true,
    };
  }
  return null;
}

Future<ProcessResult> _runFlutterBatch(List<String> testIds, Duration timeout) {
  final environment = <String, String>{
    ...Platform.environment,
    'GRADER_CASE_MODE': 'batch',
    'GRADER_CASE_IDS': testIds.join(','),
  };
  return _runProcess(
    Platform.isWindows ? 'flutter.bat' : 'flutter',
    <String>[
      'test',
      '--no-pub',
      '--concurrency=1',
      '--reporter=json',
      'test/exam_test.dart',
    ],
    environment: environment,
    timeout: timeout,
  );
}

Future<ProcessResult> _runProcess(
  String executable,
  List<String> arguments, {
  required Duration timeout,
  Map<String, String>? environment,
}) async {
  late final Process process;
  try {
    process = await Process.start(
      executable,
      arguments,
      environment: environment,
      // Windows cần shell để thực thi flutter.bat; container Linux chạy binary trực tiếp.
      runInShell: Platform.isWindows,
    );
  } on ProcessException catch (error) {
    return ProcessResult(
      0,
      127,
      '',
      'Không khởi động được $executable: $error',
    );
  }
  final stdoutFuture = process.stdout.transform(utf8.decoder).join();
  final stderrFuture = process.stderr.transform(utf8.decoder).join();
  try {
    final exitCode = await process.exitCode.timeout(timeout);
    return ProcessResult(
      process.pid,
      exitCode,
      await stdoutFuture,
      await stderrFuture,
    );
  } on TimeoutException {
    await _killProcessTree(process);
    final out = await stdoutFuture.timeout(
      const Duration(seconds: 2),
      onTimeout: () => '',
    );
    final err = await stderrFuture.timeout(
      const Duration(seconds: 2),
      onTimeout: () => '',
    );
    return ProcessResult(
      process.pid,
      kProcessTimeoutExitCode,
      out,
      '$err\nGRADER_PROCESS_TIMEOUT after ${timeout.inSeconds}s',
    );
  }
}

Future<void> _killProcessTree(Process process) async {
  if (Platform.isWindows) {
    try {
      await Process.run('taskkill', <String>[
        '/PID',
        '${process.pid}',
        '/T',
        '/F',
      ], runInShell: false).timeout(const Duration(seconds: 2));
    } catch (_) {
      process.kill();
    }
  } else {
    process.kill(ProcessSignal.sigkill);
  }
  try {
    await process.exitCode.timeout(const Duration(seconds: 2));
  } catch (_) {
    // Process đã được yêu cầu dừng; không giữ grader chỉ để chờ cleanup của hệ điều hành.
  }
}

int _positiveEnvInt(String name, int fallback) {
  final value = int.tryParse(Platform.environment[name] ?? '');
  return value != null && value > 0 ? value : fallback;
}

Map<String, dynamic> _loadMatrix() {
  for (final path in <String>[
    'test/skills_matrix.json',
    'skills_matrix.json',
  ]) {
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

List<dynamic> _asList(dynamic value) => value is List ? value : <dynamic>[];

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
        errorsById
            .putIfAbsent(id, () => <String>[])
            .add(event['error']?.toString() ?? '');
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
  return value is num
      ? value.toDouble()
      : double.tryParse('$value') ?? fallback;
}

double _round(double value) => double.parse(value.toStringAsFixed(2));

/// Gộp khoảng trắng + cắt ngắn để log runner không phình result_json lưu DB.
String _shorten(String value, int max) {
  final text = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  return text.length > max ? '${text.substring(0, max).trim()}…' : text;
}
