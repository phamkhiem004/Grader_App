import 'dart:async';
import 'dart:convert';
import 'dart:io';

const kEngineVersion = 'TEMPLATE_CONTRACT_V1-2.6.0';

Future<void> main() async {
  final matrix = _loadMatrix();
  final persistenceIds = matrix.entries
      .where((entry) => _isPersistenceMetadata(_asMap(entry.value)))
      .map((entry) => entry.key)
      .toSet();
  final directIds = matrix.entries
      .where(
        (entry) =>
            _isDirectMetadata(_asMap(entry.value)) &&
            !persistenceIds.contains(entry.key),
      )
      .map((entry) => entry.key)
      .toSet();
  final bootIds = matrix.keys
      .where(
        (id) =>
            !directIds.contains(id) && !persistenceIds.contains(id),
      )
      .toSet();
  final runs = <String, Map<String, dynamic>>{};

  var preflightPassed = bootIds.isEmpty;
  String? preflightFailure;
  if (bootIds.isNotEmpty) {
    final preflight = await _runFlutter(
      'preflight',
      const Duration(seconds: 30),
    );
    stdout.write(preflight.stdoutText);
    stderr.write(preflight.stderrText);
    final preflightRuns = _parseReporter(preflight.stdoutText);
    preflightPassed = preflightRuns['_GRADER_PREFLIGHT']?['passed'] == true;
    if (!preflightPassed) {
      preflightFailure =
          preflightRuns['_GRADER_PREFLIGHT']?['message']?.toString() ??
          _processError(preflight);
      for (final id in bootIds) {
        runs[id] = <String, dynamic>{
          'passed': false,
          'blocked': true,
          'message': 'Blocked bởi lỗi khởi động chung: $preflightFailure',
        };
      }
    }
  }

  if (directIds.isNotEmpty) {
    // Mỗi logic testcase có process/timeout riêng để singleton, SQLite hoặc một
    // Future bị treo không làm nhiễm hay xóa kết quả của testcase khác.
    for (final id in directIds) {
      final direct = await _runFlutter(
        'case',
        const Duration(seconds: 30),
        caseId: id,
      );
      stdout.write(direct.stdoutText);
      stderr.write(direct.stderrText);
      final parsed = _parseReporter(direct.stdoutText);
      runs[id] =
          parsed[id] ??
          <String, dynamic>{'passed': false, 'message': _processError(direct)};
    }
  }

  for (final id in persistenceIds) {
    final metadata = _asMap(matrix[id]);
    final parameters = _asMap(metadata['parameters']);
    final configuredNamespace = (parameters['fixtureNamespace'] ?? '')
        .toString()
        .trim();
    // Luôn gắn instance ID để hai testcase dùng cùng template/namespace không
    // vô tình đọc chung database hoặc file fixture của nhau.
    final fixtureId = configuredNamespace.isEmpty
        ? id
        : '${configuredNamespace}_$id';
    final seed = await _runFlutter(
      'case',
      const Duration(seconds: 30),
      caseId: id,
      extraEnvironment: <String, String>{
        'GRADER_PERSISTENCE_PHASE': 'seed',
        'GRADER_FIXTURE_ID': fixtureId,
      },
    );
    stdout.write(seed.stdoutText);
    stderr.write(seed.stderrText);
    final seedResult = _parseReporter(seed.stdoutText)[id];
    if (seedResult?['passed'] != true) {
      runs[id] = <String, dynamic>{
        'passed': false,
        'message': 'Pha seed persistence thất bại: '
            '${seedResult?['message'] ?? _processError(seed)}',
      };
      continue;
    }

    final verify = await _runFlutter(
      'case',
      const Duration(seconds: 30),
      caseId: id,
      extraEnvironment: <String, String>{
        'GRADER_PERSISTENCE_PHASE': 'verify',
        'GRADER_FIXTURE_ID': fixtureId,
      },
    );
    stdout.write(verify.stdoutText);
    stderr.write(verify.stderrText);
    final verifyResult = _parseReporter(verify.stdoutText)[id];
    runs[id] = verifyResult ??
        <String, dynamic>{
          'passed': false,
          'message': 'Pha verify persistence thất bại: ${_processError(verify)}',
        };
  }

  if (preflightPassed && bootIds.isNotEmpty) {
    Future<void> runCases(List<String> pending, int concurrency) async {
      var cursor = 0;
      Future<void> worker() async {
        while (cursor < pending.length) {
          final id = pending[cursor++];
          final result = await _runFlutter(
            'case',
            const Duration(seconds: 20),
            caseId: id,
          );
          stdout.write(result.stdoutText);
          stderr.write(result.stderrText);
          final parsed = _parseReporter(result.stdoutText);
          runs[id] =
              parsed[id] ??
              <String, dynamic>{
                'passed': false,
                'message': _processError(result),
              };
        }
      }

      await Future.wait(
        List<Future<void>>.generate(concurrency, (_) => worker()),
      );
    }

    // Starter contract có thể mở cùng một SQLite file ngay cả ở testcase chỉ đọc.
    // Chạy tuần tự để tránh tranh lock. Mỗi case vẫn có process và timeout riêng.
    final readOnly = bootIds
        .where((id) => !_isStatefulMetadata(_asMap(matrix[id])))
        .toList(growable: false);
    final stateful = bootIds
        .where((id) => _isStatefulMetadata(_asMap(matrix[id])))
        .toList(growable: false);
    await runCases(readOnly, 1);
    await runCases(stateful, 1);
  }

  final output = <String, dynamic>{
    'mode': kEngineVersion,
    'engine_version': kEngineVersion,
    'test_cases': <Map<String, dynamic>>[],
  };
  final cases = output['test_cases'] as List<Map<String, dynamic>>;

  var passed = 0;
  var blockedCount = 0;
  var earned = 0.0;
  var total = 0.0;
  for (final entry in matrix.entries) {
    final id = entry.key;
    final metadata = _asMap(entry.value);
    final weight = _number(metadata, 'weight', 1);
    final result = runs[id];
    final ok = result?['passed'] == true;
    final blocked = result?['blocked'] == true;
    if (blocked) blockedCount++;
    if (ok) {
      passed++;
      earned += weight;
    }
    total += weight;
    cases.add(<String, dynamic>{
      'test_id': id,
      'name': metadata['name'] ?? id,
      'status': ok ? 'passed' : (blocked ? 'blocked' : 'failed'),
      'score': ok ? weight : 0,
      'max_score': weight,
      'difficulty': metadata['difficulty'] ?? 'basic',
      'skill_code': metadata['skill_code'] ?? 'N/A',
      'expected': metadata['expected']?.toString() ?? id,
      'actual': ok
          ? 'Đã đáp ứng yêu cầu'
          : (result?['message'] ?? 'Test không tạo được kết quả.'),
    });
  }

  final score = total == 0 ? 0.0 : earned / total * 10;
  output['diem'] = _round(score);
  output['soTestPass'] = passed;
  output['soTestFail'] = cases.length - passed - blockedCount;
  output['soTestBlocked'] = blockedCount;
  output['tongSoTest'] = cases.length;
  output['chiTiet'] = cases
      .map(
        (item) => <String, dynamic>{
          'name': item['test_id'],
          'status': item['status'] == 'passed'
              ? 'PASS'
              : (item['status'] == 'blocked' ? 'BLOCKED' : 'FAILED'),
          'message': item['actual'],
        },
      )
      .toList();
  output['grading_result'] = <String, dynamic>{
    'score': _round(score),
    'earned_weight': earned,
    'total_weight': total,
    'passed_tests': passed,
    'failed_tests': cases.length - passed - blockedCount,
    'blocked_tests': blockedCount,
    'total_tests': cases.length,
  };

  stdout.writeln('--- GRADE_RESULT_START ---');
  stdout.writeln(jsonEncode(output));
  stdout.writeln('--- GRADE_RESULT_END ---');
}

bool _isDirectMetadata(Map<String, dynamic> metadata) {
  final runner = (metadata['runner'] ?? '').toString();
  if (runner == 'DIRECT_FUNCTION' ||
      runner == 'DIRECT_FUNCTION_THROWS' ||
      runner == 'DIRECT_STREAM_EVENTS' ||
      runner == 'STARTER_CALL_SEQUENCE' ||
      runner == 'PROCESS_PERSISTENCE_SEQUENCE' ||
      runner == 'PROJECT_FILE_CONTRACT' ||
      runner.startsWith('TEMPLATE_SOURCE_') ||
      runner.startsWith('TEMPLATE_MODEL_') ||
      runner == 'TEMPLATE_SQLITE_SCHEMA' ||
      runner == 'TEMPLATE_REPOSITORY_METHODS')
    return true;
  if ((metadata['runner'] ?? '').toString() != 'GROUP') return false;
  final children = metadata['children'];
  if (children is! List || children.isEmpty) return false;
  return children.every((child) {
    final childRunner = (_asMap(child)['runner'] ?? '').toString();
    return <String>{
      'DIRECT_FUNCTION',
      'DIRECT_FUNCTION_THROWS',
      'DIRECT_STREAM_EVENTS',
      'STARTER_CALL_SEQUENCE',
      'PROCESS_PERSISTENCE_SEQUENCE',
      'PROJECT_FILE_CONTRACT',
    }.contains(childRunner);
  });
}

bool _isPersistenceMetadata(Map<String, dynamic> metadata) {
  final runner = (metadata['runner'] ?? '').toString();
  if (runner == 'PROCESS_PERSISTENCE_SEQUENCE') return true;
  if (runner != 'GROUP') return false;
  final children = metadata['children'];
  return children is List &&
      children.isNotEmpty &&
      children.every(
        (child) => _isPersistenceMetadata(_asMap(child)),
      );
}

bool _isStatefulMetadata(Map<String, dynamic> metadata) {
  const stateful = <String>{
    'BUTTON_ACTION',
    'DIALOG_FLOW',
    'FORM_SUBMIT',
    'STATE_REACTIVE_FLOW',
    'KEY_WORKFLOW',
    'FORM_FOCUS_FLOW',
    'TEMPLATE_FORM_ACTION',
    'TEMPLATE_FORM_VALIDATION',
    'TEMPLATE_UI_WORKFLOW',
  };
  final runner = (metadata['runner'] ?? '').toString();
  if (stateful.contains(runner)) return true;
  if (runner != 'GROUP') return false;
  final children = metadata['children'];
  return children is List &&
      children.any((child) => _isStatefulMetadata(_asMap(child)));
}

class _CommandResult {
  final int exitCode;
  final String stdoutText;
  final String stderrText;
  final bool timedOut;

  const _CommandResult(
    this.exitCode,
    this.stdoutText,
    this.stderrText,
    this.timedOut,
  );
}

Future<_CommandResult> _runFlutter(
  String mode,
  Duration timeout, {
  String? caseId,
  Map<String, String> extraEnvironment = const <String, String>{},
}) async {
  final process = await Process.start(
    Platform.isWindows ? 'flutter.bat' : 'flutter',
    <String>['test', '--no-pub', '--reporter=json', 'test/exam_test.dart'],
    runInShell: false,
    environment: <String, String>{
      ...Platform.environment,
      ...extraEnvironment,
      'GRADER_CASE_MODE': mode,
      if (caseId != null) 'GRADER_CASE_ID': caseId,
    },
  );
  final out = StringBuffer();
  final err = StringBuffer();
  final outDone = process.stdout
      .transform(utf8.decoder)
      .listen(out.write)
      .asFuture<void>();
  final errDone = process.stderr
      .transform(utf8.decoder)
      .listen(err.write)
      .asFuture<void>();
  var timedOut = false;
  var exitCode = -1;
  try {
    exitCode = await process.exitCode.timeout(timeout);
  } on TimeoutException {
    timedOut = true;
    process.kill();
    try {
      exitCode = await process.exitCode.timeout(const Duration(seconds: 3));
    } on TimeoutException {
      exitCode = -1;
    }
  }
  try {
    await Future.wait(<Future<void>>[
      outDone,
      errDone,
    ]).timeout(const Duration(seconds: 3));
  } on TimeoutException {
    // Đã có đủ log để trả kết quả; không giữ grader vì stream con chưa đóng.
  }
  return _CommandResult(exitCode, out.toString(), err.toString(), timedOut);
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

Map<String, Map<String, dynamic>> _parseReporter(String output) {
  final namesById = <int, String>{};
  final errorsById = <int, List<String>>{};
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
      final skipped = event['skipped'] == true;
      final ok = event['result'] == 'success' && !skipped;
      runs[name] = <String, dynamic>{
        'passed': ok,
        'blocked': skipped,
        'message': ok
            ? 'Đã đáp ứng yêu cầu'
            : skipped
            ? 'Test bị skip nên không được cộng điểm.'
            : (errorsById[id]?.where((value) => value.isNotEmpty).join('\n') ??
                  'Test thất bại.'),
      };
    }
  }
  return runs;
}

String _processError(_CommandResult process) {
  if (process.timedOut)
    return 'Flutter test vượt quá thời gian cho phép và đã bị dừng.';
  final stderrText = process.stderrText.trim();
  if (stderrText.isNotEmpty) return stderrText;
  final stdoutText = process.stdoutText.trim();
  return stdoutText.isEmpty ? 'Test không tạo được kết quả.' : stdoutText;
}

double _number(Map<String, dynamic> map, String key, double fallback) {
  final value = map[key];
  return value is num
      ? value.toDouble()
      : double.tryParse('$value') ?? fallback;
}

double _round(double value) => double.parse(value.toStringAsFixed(2));
