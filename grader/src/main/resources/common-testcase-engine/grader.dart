import 'dart:convert';
import 'dart:io';

Future<void> main() async {
  final matrix = _loadMatrix();
  final process = await Process.run(
    Platform.isWindows ? 'flutter.bat' : 'flutter',
    // GRADER_MODE để starter tự khởi tạo storage cho môi trường test (vd sqflite ffi);
    // bài không dùng cờ này thì define thừa cũng vô hại.
    <String>[
      'test',
      '--no-pub',
      '--dart-define=GRADER_MODE=true',
      '--reporter=json',
      'test/exam_test.dart',
    ],
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

  var passed = 0;
  var earned = 0.0;
  var total = 0.0;
  for (final entry in matrix.entries) {
    final id = entry.key;
    final metadata = _asMap(entry.value);
    final weight = _number(metadata, 'weight', 1);
    final result = runs[id];
    final ok = result?['passed'] == true;
    if (ok) {
      passed++;
      earned += weight;
    }
    total += weight;
    cases.add(<String, dynamic>{
      'test_id': id,
      'name': metadata['name'] ?? id,
      'status': ok ? 'passed' : 'failed',
      'score': ok ? weight : 0,
      'max_score': weight,
      'difficulty': metadata['difficulty'] ?? 'basic',
      'skill_code': metadata['skill_code'] ?? 'N/A',
      'expected': metadata['expected']?.toString() ?? id,
      'actual': ok ? 'Đã đáp ứng yêu cầu' : (result?['message'] ?? _processError(process)),
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
    'earned_weight': earned,
    'total_weight': total,
    'passed_tests': passed,
    'failed_tests': cases.length - passed,
    'total_tests': cases.length,
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
        errorsById.putIfAbsent(id, () => <String>[]).add(event['error']?.toString() ?? '');
      }
    } else if (type == 'testDone') {
      final id = (event['testID'] as num?)?.toInt();
      final name = id == null ? null : namesById[id];
      if (name == null) continue;
      final ok = event['result'] == 'success' || event['skipped'] == true;
      runs[name] = <String, dynamic>{
        'passed': ok,
        'message': ok
            ? 'Đã đáp ứng yêu cầu'
            : (errorsById[id]?.where((value) => value.isNotEmpty).join('\n') ?? 'Test thất bại.'),
      };
    }
  }
  return runs;
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
