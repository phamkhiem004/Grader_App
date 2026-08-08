import 'dart:async';
import 'dart:convert';
import 'dart:io';

Future<ProcessResult> _runProcess(
  String executable,
  List<String> arguments, {
  Map<String, String>? environment,
  String? workingDirectory,
  bool runInShell = false,
}) async {
  const limit = Duration(seconds: 20);
  final process = await Process.start(
    executable,
    arguments,
    environment: environment,
    workingDirectory: workingDirectory,
    runInShell: runInShell,
  );
  final stdoutFuture = process.stdout.transform(utf8.decoder).join();
  final stderrFuture = process.stderr.transform(utf8.decoder).join();
  try {
    final code = await process.exitCode.timeout(limit);
    return ProcessResult(
      process.pid,
      code,
      await stdoutFuture,
      await stderrFuture,
    );
  } on TimeoutException {
    process.kill();
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
      -1,
      out,
      '$err\nGRADER_PROCESS_TIMEOUT after 20s',
    );
  }
}

Future<void> main() async {
  _materializeEmbeddedFiles();
  final matrix = _loadMatrix();
  final details = <Map<String, dynamic>>[];

  // Vẫn chấm từng ID riêng, nhưng gom các ID cùng file vào một Flutter process.
  // Khởi động Flutter cho từng ID làm bộ 48 test vượt timeout Docker dù bài đúng.
  final audit = <String, _TestRun>{};
  final missingContractFiles = _missingContractFiles();
  if (missingContractFiles.isNotEmpty) {
    // Nếu thiếu file contract bắt buộc thì điểm vốn đã bị khóa về 0; không
    // khởi động Flutter/UI vô ích cho bài chắc chắn không đạt contract.
    final message =
        'Bỏ qua runner vì thiếu contract: ' + missingContractFiles.join(', ');
    for (final id in matrix.keys) {
      details.add(_detail(id, _metadata(matrix, id), _TestRun(false, message)));
    }
  } else {
    final groups = <String, List<String>>{};
    for (final id in matrix.keys.where(_isRunnableTest)) {
      groups.putIfAbsent(_executionGroupKey(id), () => <String>[]).add(id);
    }
    for (final names in groups.values) {
      // Kết quả nhóm đủ để chấm; retry từng testcase fail làm bài lỗi khởi động
      // hàng chục Flutter process và dễ chạm timeout Docker.
      final runs = await _runWidgetGroup(names);
      for (final id in names) {
        final metadata = _metadata(matrix, id);
        details.add(_detail(id, metadata, runs[id]!));
      }
    }

    audit.addAll(await _runArchitectureAudit());
    for (final id in matrix.keys.where((name) => name.startsWith('ARCH_'))) {
      final metadata = _metadata(matrix, id);
      details.add(<String, dynamic>{
        'test_id': id,
        'name': metadata['name'] ?? id,
        'status': audit[id]?.passed == true ? 'passed' : 'failed',
        'weight': _weight(metadata),
        'message':
            audit[id]?.message ??
            audit['_runner']?.message ??
            'Architecture audit không chạy được',
      });
    }
  }

  final passedCount = details
      .where((item) => item['status'] == 'passed')
      .length;
  final failedCount = details.length - passedCount;
  final totalWeight = details.fold<double>(
    0,
    (sum, item) => sum + (item['weight'] as num).toDouble(),
  );
  final earnedWeight = details
      .where((item) => item['status'] == 'passed')
      .fold<double>(0, (sum, item) => sum + (item['weight'] as num).toDouble());
  final contractViolation = details.any(
    (item) =>
        item['test_id'].toString().startsWith('CONTRACT_') &&
        item['status'] != 'passed',
  );
  final rawScore = totalWeight == 0 ? 0.0 : earnedWeight / totalWeight * 10;
  // Tên public trong starter là bắt buộc; vẫn chạy các layer còn lại để chẩn đoán,
  // nhưng bài vi phạm contract không được ghi nhận là bài đạt rubric.
  final score10 = contractViolation ? 0.0 : rawScore;

  final result = <String, dynamic>{
    'mode': 'layered_contract_model_repository_viewmodel_screen_blackbox',
    'diem': _round(score10),
    'contract_violation': contractViolation,
    'soTestPass': passedCount,
    'soTestFail': failedCount,
    'tongSoTest': details.length,
    'chiTiet': details
        .map(
          (item) => <String, dynamic>{
            'name': item['test_id'],
            'status': item['status'] == 'passed' ? 'PASS' : 'FAILED',
            'message': item['message'],
          },
        )
        .toList(),
    'test_cases': details.map((item) {
      final metadata = _metadata(matrix, item['test_id']);
      final passed = item['status'] == 'passed';
      final expected =
          metadata['expected']?.toString() ??
          metadata['name']?.toString() ??
          item['test_id'].toString();
      return <String, dynamic>{
        'test_id': item['test_id'],
        'name': item['name'],
        'status': item['status'],
        'score': passed ? item['weight'] : 0,
        'max_score': item['weight'],
        'difficulty': metadata['difficulty'] ?? 'basic',
        'skill_code': metadata['skill_code'] ?? 'N/A',
        // Giữ cả expected và expect để tương thích các format JSON cũ.
        'expected': expected,
        'expect': expected,
        'actual': passed ? 'Đã đáp ứng yêu cầu' : item['message'],
      };
    }).toList(),
    'grading_result': <String, dynamic>{
      'score': _round(score10),
      'raw_score_before_contract_gate': _round(rawScore),
      'total_raw_score': earnedWeight,
      'passed_tests': passedCount,
      'failed_tests': failedCount,
      'total_tests': details.length,
      'earned_weight': earnedWeight,
      'total_weight': totalWeight,
      'blocked': contractViolation || details.isEmpty,
      'contract_violation': contractViolation,
      'runner_error': audit['_runner']?.message,
    },
  };

  _cleanupEmbeddedFiles();
  stdout.writeln('--- GRADE_RESULT_START ---');
  stdout.writeln(jsonEncode(result));
  stdout.writeln('--- GRADE_RESULT_END ---');
}

Map<String, dynamic> _detail(
  String id,
  Map<String, dynamic> metadata,
  _TestRun run,
) => <String, dynamic>{
  'test_id': id,
  'name': metadata['name'] ?? id,
  'status': run.passed ? 'passed' : 'failed',
  'weight': _weight(metadata),
  'message': run.message,
};

bool _isRunnableTest(String name) => _testFile(name) != null;

String _executionGroupKey(String id) {
  final file = _testFile(id)!;
  if (file == 'test/exam_test.dart') {
    // Persistence cần giữ dữ liệu giữa seed/reload nhưng mỗi rubric ID vẫn
    // được setUp dọn riêng khi chạy chung một process.
    if (id.startsWith('PERSIST_')) return 'persist::$file';
    // Các UI test thường xuyên dọn storage trong setUp nên có thể chạy chung
    // một process; làm vậy giảm nhiều lần khởi động Flutter nhưng parser vẫn
    // tách kết quả theo từng test ID để chấm điểm độc lập.
    return 'ui::$file';
  }

  // Golden giữ riêng để ổn định font/cache.
  if (file == 'test/_prm393_visual.dart') return 'visual::$file';

  // Contract và unit test không dùng chung state UI/DB thật; gom các file
  // độc lập để giảm số lần khởi động Flutter, rồi retry riêng nếu có lỗi.
  return 'unit::all';
}

String? _testFile(String name) {
  if (name.startsWith('CONTRACT_MODEL'))
    return 'test/_prm393_contract_model.dart';
  if (name.startsWith('CONTRACT_REPOSITORY'))
    return 'test/_prm393_contract_repository.dart';
  if (name.startsWith('CONTRACT_VIEWMODEL'))
    return 'test/_prm393_contract_viewmodel.dart';
  if (name.startsWith('CONTRACT_SCREEN'))
    return 'test/_prm393_contract_screen.dart';
  if (name.startsWith('MODEL_GRANULAR_'))
    return 'test/_prm393_model_granular.dart';
  if (name.startsWith('MODEL_')) return 'test/_prm393_model.dart';
  if (name.startsWith('SQLITE_')) return 'test/_prm393_sqlite_repository.dart';
  if (name.startsWith('REPOSITORY_GRANULAR_'))
    return 'test/_prm393_repository_granular.dart';
  if (name.startsWith('REPOSITORY_')) return 'test/_prm393_repository.dart';
  if (name.startsWith('VIEWMODEL_GRANULAR_'))
    return 'test/_prm393_viewmodel_granular.dart';
  if (name.startsWith('VIEWMODEL_')) return 'test/_prm393_viewmodel.dart';
  if (name.startsWith('SCREEN_GRANULAR_'))
    return 'test/_prm393_screen_granular.dart';
  if (name.startsWith('SCREEN_')) return 'test/_prm393_screen.dart';
  if (name.startsWith('VISUAL_')) return 'test/_prm393_visual.dart';
  if (name.startsWith('UI_') || name.startsWith('PERSIST_')) {
    return 'test/exam_test.dart';
  }
  return null;
}

List<String> _missingContractFiles() {
  const required = <String>[
    'lib/models/user_model.dart',
    'lib/database/database_service.dart',
    'lib/repositories/user_repository.dart',
    'lib/viewmodels/user_view_model.dart',
    'lib/screens/user_list_screen.dart',
    'lib/screens/user_detail_screen.dart',
  ];
  return required.where((path) => !File(path).existsSync()).toList();
}

Map<String, dynamic> _metadata(Map<String, dynamic> matrix, String id) {
  final value = matrix[id];
  return value is Map ? Map<String, dynamic>.from(value) : <String, dynamic>{};
}

class _TestRun {
  const _TestRun(this.passed, this.message);

  final bool passed;
  final String message;
}

Future<_TestRun> _runWidgetTest(String name) async {
  final sandbox = await Directory.systemTemp.createTemp(
    'prm393_v9_${_safeName(name)}_',
  );
  final dataHome = Directory('${sandbox.path}/data')
    ..createSync(recursive: true);
  final configHome = Directory('${sandbox.path}/config')
    ..createSync(recursive: true);
  final cacheHome = Directory('${sandbox.path}/cache')
    ..createSync(recursive: true);
  final environment = Map<String, String>.from(Platform.environment)
    ..['GRADER_DATA_HOME'] = sandbox.path
    ..['GRADER_TEST_NAME'] = name;
  // Đổi HOME/XDG cho test dữ liệu; Golden không dùng storage và cần giữ môi trường
  // font/cache ổn định để ảnh tham chiếu không lệch vì sandbox của từng test.
  if (!name.startsWith('VISUAL_')) {
    environment
      ..['HOME'] = sandbox.path
      ..['XDG_DATA_HOME'] = dataHome.path
      ..['XDG_CONFIG_HOME'] = configHome.path
      ..['XDG_CACHE_HOME'] = cacheHome.path
      ..['TMPDIR'] = sandbox.path;
  }

  try {
    if (name.startsWith('PERSIST_')) {
      final seeded = await _runFlutterTest(
        name,
        environment,
        persistMode: 'seed',
      );
      if (!seeded.passed) return seeded;
      return await _runFlutterTest(name, environment, persistMode: 'reload');
    }
    return await _runFlutterTest(name, environment);
  } finally {
    try {
      await sandbox.delete(recursive: true);
    } catch (_) {
      // Cleanup lỗi không làm thay đổi kết quả test đã có.
    }
  }
}

/// Chạy một nhóm test cùng file trong một process rồi tách kết quả theo test ID.
/// Nhóm persistence dùng chung sandbox giữa seed và reload; các nhóm còn lại
/// dùng sandbox riêng theo layer nên không làm thay đổi dữ liệu của layer khác.
Future<Map<String, _TestRun>> _runWidgetGroup(List<String> names) async {
  final sandbox = await Directory.systemTemp.createTemp(
    'prm393_v9_group_${_safeName(names.first)}_',
  );
  final dataHome = Directory('${sandbox.path}/data')
    ..createSync(recursive: true);
  final configHome = Directory('${sandbox.path}/config')
    ..createSync(recursive: true);
  final cacheHome = Directory('${sandbox.path}/cache')
    ..createSync(recursive: true);
  final environment = Map<String, String>.from(Platform.environment)
    ..['GRADER_DATA_HOME'] = sandbox.path
    ..['GRADER_TEST_NAME'] = names.join(',');
  if (!names.first.startsWith('VISUAL_')) {
    environment
      ..['HOME'] = sandbox.path
      ..['XDG_DATA_HOME'] = dataHome.path
      ..['XDG_CONFIG_HOME'] = configHome.path
      ..['XDG_CACHE_HOME'] = cacheHome.path
      ..['TMPDIR'] = sandbox.path;
  }

  try {
    // Chạy cả 3 persistence ID trong một process. Mỗi test tự dọn storage
    // trong setUp rồi kiểm tra reload nội bộ, bỏ seed/reload process thứ hai.
    return await _runFlutterGroup(names, environment);
  } finally {
    try {
      await sandbox.delete(recursive: true);
    } catch (_) {
      // Cleanup lỗi không làm thay đổi kết quả test.
    }
  }
}

_TestRun _combineRuns(_TestRun seed, _TestRun reload) {
  if (seed.passed && reload.passed) return const _TestRun(true, 'OK');
  if (!seed.passed) return _TestRun(false, 'Seed: ${seed.message}');
  return _TestRun(false, 'Reload: ${reload.message}');
}

Future<Map<String, _TestRun>> _runFlutterGroup(
  List<String> names,
  Map<String, String> environment, {
  String? persistMode,
}) async {
  final files = <String>{};
  for (final name in names) {
    final file = _testFile(name);
    if (file != null) files.add(file);
  }
  if (files.isEmpty) {
    return <String, _TestRun>{
      for (final id in names)
        id: const _TestRun(false, 'Không tìm thấy test file.'),
    };
  }

  final args = <String>[
    'test',
    '--no-pub',
    '--reporter=json',
    '--dart-define=GRADER_MODE=true',
    '--dart-define=GRADER_TEST_NAME=${names.join(',')}',
    if (persistMode != null) '--dart-define=PERSIST_MODE=$persistMode',
  ];
  // Dùng prefix chung cho nhóm một file. Với nhiều file, chạy toàn bộ test
  // trong các file rồi parser chỉ nhận các ID có trong matrix; tránh regex
  // alternation và giảm thêm một lần khởi động Flutter.
  if (files.length == 1) {
    args.add('--name=${_commonNamePrefix(names)}');
  }
  args.addAll(files);

  final process = await _runProcess(
    Platform.isWindows ? 'flutter.bat' : 'flutter',
    args,
    environment: environment,
    workingDirectory: Directory.current.path,
    runInShell: false,
  );
  return _parseGroupedReporter(process, names);
}

String _commonNamePrefix(List<String> names) {
  var prefix = names.first;
  for (final name in names.skip(1)) {
    while (!name.startsWith(prefix) && prefix.isNotEmpty) {
      prefix = prefix.substring(0, prefix.length - 1);
    }
  }
  return RegExp.escape(prefix.isEmpty ? names.first : prefix);
}

Map<String, _TestRun> _parseGroupedReporter(
  ProcessResult process,
  List<String> names,
) {
  final namesSet = names.toSet();
  final namesById = <int, String>{};
  final errorsById = <int, List<String>>{};
  final runs = <String, _TestRun>{};

  for (final line in process.stdout.toString().split('\n')) {
    final text = line.trim();
    if (text.isEmpty) continue;
    dynamic event;
    try {
      event = jsonDecode(text);
    } catch (_) {
      continue;
    }
    if (event is! Map) continue;
    final type = event['type']?.toString();
    if (type == 'testStart' && event['test'] is Map) {
      final test = event['test'] as Map;
      final id = (test['id'] as num?)?.toInt();
      final name = test['name']?.toString();
      if (id != null && name != null && namesSet.contains(name)) {
        namesById[id] = name;
      }
    } else if (type == 'error') {
      final id = (event['testID'] as num?)?.toInt();
      if (id != null) {
        final message = event['error']?.toString();
        if (message != null && message.isNotEmpty) {
          errorsById.putIfAbsent(id, () => <String>[]).add(message);
        }
      }
    } else if (type == 'testDone') {
      final id = (event['testID'] as num?)?.toInt();
      final name = id == null ? null : namesById[id];
      if (name == null) continue;
      final skipped = event['skipped'] == true;
      // A skipped testcase has not verified the student's implementation and
      // therefore must never be awarded points.
      final passed = event['result'] == 'success' && !skipped;
      final error = errorsById[id]?.join('\n');
      runs[name] = _TestRun(
        passed,
        passed ? 'OK' : (error ?? 'Test thất bại.'),
      );
    }
  }

  final fallback = process.exitCode == 0
      ? 'Test không được runner chọn hoặc không tạo kết quả.'
      : _diagnostic(process);
  for (final name in names) {
    runs.putIfAbsent(name, () => _TestRun(false, fallback));
  }
  return runs;
}

Future<_TestRun> _runFlutterTest(
  String name,
  Map<String, String> environment, {
  String? persistMode,
}) async {
  final file = _testFile(name);
  if (file == null) return const _TestRun(false, 'Không tìm thấy test file.');
  final args = <String>[
    'test',
    '--no-pub',
    '--dart-define=GRADER_MODE=true',
    '--dart-define=GRADER_TEST_NAME=$name',
  ];
  if (persistMode != null) args.add('--dart-define=PERSIST_MODE=$persistMode');
  args.addAll(<String>['--plain-name', name, file]);

  final process = await _runProcess(
    Platform.isWindows ? 'flutter.bat' : 'flutter',
    args,
    environment: environment,
    workingDirectory: Directory.current.path,
    runInShell: false,
  );
  return _TestRun(
    process.exitCode == 0,
    process.exitCode == 0 ? 'OK' : _diagnostic(process),
  );
}

String _diagnostic(ProcessResult process) {
  final stderr = process.stderr.toString().trim();
  if (stderr.isNotEmpty) return stderr;
  final lines = process.stdout
      .toString()
      .split('\n')
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty)
      .toList();
  if (lines.length > 12) return lines.sublist(lines.length - 12).join('\n');
  return lines.join('\n').isEmpty
      ? 'Test không tạo được kết quả.'
      : lines.join('\n');
}

Future<Map<String, _TestRun>> _runArchitectureAudit() async {
  final libPath = Directory('/app/lib').existsSync() ? '/app/lib' : 'lib';
  final process = await _runProcess(
    Platform.isWindows ? 'dart.bat' : 'dart',
    <String>['test/_prm393_architecture_audit.dart', libPath],
    workingDirectory: Directory.current.path,
    runInShell: true,
  );
  if (process.exitCode != 0) {
    return <String, _TestRun>{
      '_runner': _TestRun(false, process.stderr.toString().trim()),
    };
  }
  for (final line in process.stdout.toString().split('\n').reversed) {
    final value = line.trim();
    if (value.isEmpty) continue;
    try {
      final decoded = jsonDecode(value);
      if (decoded is! Map || decoded['checks'] is! List) continue;
      final result = <String, _TestRun>{};
      for (final raw in decoded['checks'] as List) {
        if (raw is! Map) continue;
        final id = raw['test_id']?.toString();
        if (id == null) continue;
        result[id] = _TestRun(
          raw['passed'] == true,
          raw['message']?.toString() ?? 'Audit hoàn tất',
        );
      }
      return result;
    } catch (_) {
      continue;
    }
  }
  return <String, _TestRun>{
    '_runner': const _TestRun(false, 'Audit không trả JSON hợp lệ.'),
  };
}

String _safeName(String value) =>
    value.replaceAll(RegExp(r'[^A-Za-z0-9_-]'), '_');

Map<String, dynamic> _loadMatrix() {
  for (final path in <String>[
    'test/skills_matrix.json',
    'skills_matrix.json',
  ]) {
    final file = File(path);
    if (!file.existsSync()) continue;
    try {
      final value = jsonDecode(file.readAsStringSync());
      if (value is Map<String, dynamic>) return value;
    } catch (_) {
      return <String, dynamic>{};
    }
  }
  return <String, dynamic>{};
}

double _weight(Map<String, dynamic> metadata) {
  final value = metadata['weight'];
  return value is num ? value.toDouble() : 1;
}

double _round(double value) => double.parse(value.toStringAsFixed(2));
// Các layer phụ được nhúng để ZIP public vẫn đúng hợp đồng chỉ có 3 file.
// Khi chấm, chúng được bung tạm vào test/ rồi xóa sau khi hoàn tất.
const _embeddedSources = <String, String>{
  'test/_prm393_contract_model.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9tb2RlbHMvdXNlcl9tb2RlbC5kYXJ0JzsKCi8vLyBDb250cmFjdCBuw6B5IGLDoW0gxJHDum5nIHTDqm4gcHVibGljIHRyb25nIHN0YXJ0ZXIgxJHGsOG7o2MgcGjDoXQgY2hvIHNpbmggdmnDqm4uCnZvaWQgbWFpbigpIHsKICB0ZXN0KCdDT05UUkFDVF9NT0RFTF9TWU1CT0xTJywgKCkgewogICAgY29uc3QgdXNlciA9IFVzZXJNb2RlbCgKICAgICAgaWQ6IDEsCiAgICAgIGZ1bGxOYW1lOiAnQ29udHJhY3QgVXNlcicsCiAgICAgIGVtYWlsOiAnY29udHJhY3RAZXhhbXBsZS5jb20nLAogICAgICBhdmF0YXI6ICdhc3NldHMvZGVmYXVsdF9hdmF0YXIuanBnJywKICAgICk7CiAgICBleHBlY3QodXNlciwgaXNBPFVzZXJNb2RlbD4oKSk7CiAgfSk7Cn0K',
  'test/_prm393_contract_repository.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9kYXRhYmFzZS9kYXRhYmFzZV9zZXJ2aWNlLmRhcnQnOwppbXBvcnQgJy4uL2xpYi9tb2RlbHMvdXNlcl9tb2RlbC5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvcmVwb3NpdG9yaWVzL3VzZXJfcmVwb3NpdG9yeS5kYXJ0JzsKCmNsYXNzIF9Db250cmFjdERhdGFiYXNlIGltcGxlbWVudHMgRGF0YWJhc2VTZXJ2aWNlIHsKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gb3BlbigpIGFzeW5jIHt9CgogIEBvdmVycmlkZQogIEZ1dHVyZTxMaXN0PE1hcDxTdHJpbmcsIE9iamVjdD8+Pj4gcXVlcnlVc2VycygpIGFzeW5jID0+IGNvbnN0IFtdOwoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gaW5zZXJ0VXNlcihNYXA8U3RyaW5nLCBPYmplY3Q/PiB2YWx1ZXMpIGFzeW5jIHt9CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiB1cGRhdGVVc2VyKGludCBpZCwgTWFwPFN0cmluZywgT2JqZWN0Pz4gdmFsdWVzKSBhc3luYyB7fQoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gZGVsZXRlVXNlcihpbnQgaWQpIGFzeW5jIHt9Cn0KCnZvaWQgbWFpbigpIHsKICB0ZXN0KCdDT05UUkFDVF9SRVBPU0lUT1JZX1NZTUJPTFMnLCAoKSB7CiAgICBmaW5hbCBVc2VyUmVwb3NpdG9yeSByZXBvc2l0b3J5ID0gU3FsaXRlVXNlclJlcG9zaXRvcnkoX0NvbnRyYWN0RGF0YWJhc2UoKSk7CiAgICBleHBlY3QocmVwb3NpdG9yeSwgaXNBPFNxbGl0ZVVzZXJSZXBvc2l0b3J5PigpKTsKICAgIGV4cGVjdCgKICAgICAgY29uc3QgVXNlck1vZGVsKGZ1bGxOYW1lOiAneCcsIGVtYWlsOiAneEB5LnonLCBhdmF0YXI6ICdhJyksCiAgICAgIGlzQTxVc2VyTW9kZWw+KCksCiAgICApOwogIH0pOwp9Cg==',
  'test/_prm393_contract_viewmodel.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3JpdmVycG9kL2ZsdXR0ZXJfcml2ZXJwb2QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9yZXBvc2l0b3JpZXMvdXNlcl9yZXBvc2l0b3J5LmRhcnQnOwppbXBvcnQgJy4uL2xpYi92aWV3bW9kZWxzL3VzZXJfdmlld19tb2RlbC5kYXJ0JzsKCnZvaWQgbWFpbigpIHsKICB0ZXN0KCdDT05UUkFDVF9WSUVXTU9ERUxfUFJPVklERVJfU1lNQk9MUycsICgpIHsKICAgIGV4cGVjdCh1c2VyUmVwb3NpdG9yeVByb3ZpZGVyLCBpc0E8UHJvdmlkZXJCYXNlPFVzZXJSZXBvc2l0b3J5Pj4oKSk7CiAgICBleHBlY3QodXNlclZpZXdNb2RlbFByb3ZpZGVyLCBpc05vdE51bGwpOwogICAgZmluYWwgVXNlclZpZXdNb2RlbCBGdW5jdGlvbihVc2VyUmVwb3NpdG9yeSkgY29uc3RydWN0b3IgPQogICAgICAgIFVzZXJWaWV3TW9kZWwubmV3OwogICAgZXhwZWN0KGNvbnN0cnVjdG9yLCBpc05vdE51bGwpOwogIH0pOwp9Cg==',
  'test/_prm393_contract_screen.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9tb2RlbHMvdXNlcl9tb2RlbC5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvc2NyZWVucy91c2VyX2RldGFpbF9zY3JlZW4uZGFydCc7CmltcG9ydCAnLi4vbGliL3NjcmVlbnMvdXNlcl9saXN0X3NjcmVlbi5kYXJ0JzsKCnZvaWQgbWFpbigpIHsKICB0ZXN0KCdDT05UUkFDVF9TQ1JFRU5fU1lNQk9MUycsICgpIHsKICAgIGNvbnN0IHVzZXIgPSBVc2VyTW9kZWwoZnVsbE5hbWU6ICd4JywgZW1haWw6ICd4QHkueicsIGF2YXRhcjogJ2EnKTsKICAgIGV4cGVjdChjb25zdCBVc2VyTGlzdFNjcmVlbigpLCBpc0E8VXNlckxpc3RTY3JlZW4+KCkpOwogICAgZXhwZWN0KFVzZXJEZXRhaWxTY3JlZW4odXNlcjogdXNlciksIGlzQTxVc2VyRGV0YWlsU2NyZWVuPigpKTsKICB9KTsKfQo=',
  'test/_prm393_model.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9tb2RlbHMvdXNlcl9tb2RlbC5kYXJ0JzsKCnZvaWQgbWFpbigpIHsKICB0ZXN0KCdNT0RFTF9GSUVMRFNfQ09QWVdJVEhfQU5EX01BUFBJTkcnLCAoKSB7CiAgICBjb25zdCBvcmlnaW5hbCA9IFVzZXJNb2RlbCgKICAgICAgaWQ6IDcsCiAgICAgIGZ1bGxOYW1lOiAnT3JpZ2luYWwgVXNlcicsCiAgICAgIGVtYWlsOiAnb3JpZ2luYWxAZXhhbXBsZS5jb20nLAogICAgICBhdmF0YXI6ICdsaWIvYXNzZXRzL2RlZmF1bHRfYXZhdGFyLmpwZycsCiAgICApOwogICAgZXhwZWN0KG9yaWdpbmFsLmlkLCA3KTsKICAgIGV4cGVjdChvcmlnaW5hbC5mdWxsTmFtZSwgaXNBPFN0cmluZz4oKSk7CiAgICBleHBlY3Qob3JpZ2luYWwuZW1haWwsIGlzQTxTdHJpbmc+KCkpOwogICAgZXhwZWN0KG9yaWdpbmFsLmF2YXRhciwgaXNBPFN0cmluZz4oKSk7CgogICAgZmluYWwgY29waWVkID0gb3JpZ2luYWwuY29weVdpdGgoCiAgICAgIGZ1bGxOYW1lOiAnVXBkYXRlZCBVc2VyJywKICAgICAgZW1haWw6ICd1cGRhdGVkQGV4YW1wbGUuY29tJywKICAgICk7CiAgICBleHBlY3QoY29waWVkLmlkLCA3KTsKICAgIGV4cGVjdChjb3BpZWQuZnVsbE5hbWUsICdVcGRhdGVkIFVzZXInKTsKICAgIGV4cGVjdChjb3BpZWQuZW1haWwsICd1cGRhdGVkQGV4YW1wbGUuY29tJyk7CiAgICBleHBlY3QoY29waWVkLmF2YXRhciwgb3JpZ2luYWwuYXZhdGFyKTsKCiAgICBmaW5hbCBtYXBwZWQgPSBVc2VyTW9kZWwuZnJvbU1hcCg8U3RyaW5nLCBPYmplY3Q/PnsKICAgICAgJ2lkJzogOCwKICAgICAgJ2Z1bGxfbmFtZSc6ICdNYXBwZWQgVXNlcicsCiAgICAgICdlbWFpbCc6ICdtYXBwZWRAZXhhbXBsZS5jb20nLAogICAgICAnYXZhdGFyJzogJ2xpYi9hc3NldHMvZGVmYXVsdF9hdmF0YXIuanBnJywKICAgIH0pOwogICAgZXhwZWN0KG1hcHBlZC5pZCwgOCk7CiAgICBleHBlY3QobWFwcGVkLnRvTWFwKCksIDxTdHJpbmcsIE9iamVjdD8+ewogICAgICAnZnVsbF9uYW1lJzogJ01hcHBlZCBVc2VyJywKICAgICAgJ2VtYWlsJzogJ21hcHBlZEBleGFtcGxlLmNvbScsCiAgICAgICdhdmF0YXInOiAnbGliL2Fzc2V0cy9kZWZhdWx0X2F2YXRhci5qcGcnLAogICAgfSk7CiAgfSk7Cn0K',
  'test/_prm393_repository.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9kYXRhYmFzZS9kYXRhYmFzZV9zZXJ2aWNlLmRhcnQnOwppbXBvcnQgJy4uL2xpYi9tb2RlbHMvdXNlcl9tb2RlbC5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvcmVwb3NpdG9yaWVzL3VzZXJfcmVwb3NpdG9yeS5kYXJ0JzsKCmNsYXNzIF9UZW1wb3JhcnlEYXRhYmFzZSBpbXBsZW1lbnRzIERhdGFiYXNlU2VydmljZSB7CiAgZmluYWwgcm93cyA9IDxNYXA8U3RyaW5nLCBPYmplY3Q/Pj5bXTsKICB2YXIgX25leHRJZCA9IDE7CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiBvcGVuKCkgYXN5bmMge30KCiAgQG92ZXJyaWRlCiAgRnV0dXJlPExpc3Q8TWFwPFN0cmluZywgT2JqZWN0Pz4+PiBxdWVyeVVzZXJzKCkgYXN5bmMgPT4KICAgICAgcm93cy5yZXZlcnNlZC5tYXAoKHJvdykgPT4gTWFwPFN0cmluZywgT2JqZWN0Pz4uZnJvbShyb3cpKS50b0xpc3QoKTsKCiAgQG92ZXJyaWRlCiAgRnV0dXJlPHZvaWQ+IGluc2VydFVzZXIoTWFwPFN0cmluZywgT2JqZWN0Pz4gdmFsdWVzKSBhc3luYyB7CiAgICByb3dzLmFkZCg8U3RyaW5nLCBPYmplY3Q/PnsnaWQnOiBfbmV4dElkKyssIC4uLnZhbHVlc30pOwogIH0KCiAgQG92ZXJyaWRlCiAgRnV0dXJlPHZvaWQ+IHVwZGF0ZVVzZXIoaW50IGlkLCBNYXA8U3RyaW5nLCBPYmplY3Q/PiB2YWx1ZXMpIGFzeW5jIHsKICAgIGZpbmFsIGluZGV4ID0gcm93cy5pbmRleFdoZXJlKChyb3cpID0+IHJvd1snaWQnXSA9PSBpZCk7CiAgICBpZiAoaW5kZXggPCAwKSB0aHJvdyBTdGF0ZUVycm9yKCdtaXNzaW5nIGlkJyk7CiAgICByb3dzW2luZGV4XSA9IDxTdHJpbmcsIE9iamVjdD8+eydpZCc6IGlkLCAuLi52YWx1ZXN9OwogIH0KCiAgQG92ZXJyaWRlCiAgRnV0dXJlPHZvaWQ+IGRlbGV0ZVVzZXIoaW50IGlkKSBhc3luYyB7CiAgICByb3dzLnJlbW92ZVdoZXJlKChyb3cpID0+IHJvd1snaWQnXSA9PSBpZCk7CiAgfQp9Cgp2b2lkIG1haW4oKSB7CiAgdGVzdCgnUkVQT1NJVE9SWV9DUlVEX01BUFBJTkdfQU5EX0RVUExJQ0FURV9ST1dTJywgKCkgYXN5bmMgewogICAgZmluYWwgZGF0YWJhc2UgPSBfVGVtcG9yYXJ5RGF0YWJhc2UoKTsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBTcWxpdGVVc2VyUmVwb3NpdG9yeShkYXRhYmFzZSk7CiAgICBjb25zdCBmaXJzdCA9IFVzZXJNb2RlbCgKICAgICAgZnVsbE5hbWU6ICdTYW1lIFVzZXInLAogICAgICBlbWFpbDogJ3NhbWVAZXhhbXBsZS5jb20nLAogICAgICBhdmF0YXI6ICdsaWIvYXNzZXRzL2RlZmF1bHRfYXZhdGFyLmpwZycsCiAgICApOwogICAgY29uc3Qgc2Vjb25kID0gVXNlck1vZGVsKAogICAgICBmdWxsTmFtZTogJ1NhbWUgVXNlcicsCiAgICAgIGVtYWlsOiAnc2FtZUBleGFtcGxlLmNvbScsCiAgICAgIGF2YXRhcjogJ2xpYi9hc3NldHMvZGVmYXVsdF9hdmF0YXIuanBnJywKICAgICk7CgogICAgYXdhaXQgcmVwb3NpdG9yeS5hZGRVc2VyKGZpcnN0KTsKICAgIGF3YWl0IHJlcG9zaXRvcnkuYWRkVXNlcihzZWNvbmQpOwogICAgdmFyIHVzZXJzID0gYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpOwogICAgZXhwZWN0KHVzZXJzLCBoYXNMZW5ndGgoMikpOwogICAgZXhwZWN0KHVzZXJzLm1hcCgodXNlcikgPT4gdXNlci5pZCkudG9TZXQoKSwgaGFzTGVuZ3RoKDIpKTsKCiAgICBmaW5hbCB1cGRhdGVkID0gdXNlcnMubGFzdC5jb3B5V2l0aChlbWFpbDogJ3VwZGF0ZWRAZXhhbXBsZS5jb20nKTsKICAgIGF3YWl0IHJlcG9zaXRvcnkudXBkYXRlVXNlcih1cGRhdGVkKTsKICAgIHVzZXJzID0gYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpOwogICAgZXhwZWN0KHVzZXJzLmFueSgodXNlcikgPT4gdXNlci5lbWFpbCA9PSAndXBkYXRlZEBleGFtcGxlLmNvbScpLCBpc1RydWUpOwoKICAgIGF3YWl0IHJlcG9zaXRvcnkuZGVsZXRlVXNlcih1cGRhdGVkLmlkISk7CiAgICB1c2VycyA9IGF3YWl0IHJlcG9zaXRvcnkuZ2V0VXNlcnMoKTsKICAgIGV4cGVjdCh1c2VycywgaGFzTGVuZ3RoKDEpKTsKICAgIGV4cGVjdCh1c2Vycy5zaW5nbGUuZW1haWwsICdzYW1lQGV4YW1wbGUuY29tJyk7CiAgICBleHBlY3QoCiAgICAgICgpID0+IHJlcG9zaXRvcnkudXBkYXRlVXNlcigKICAgICAgICBjb25zdCBVc2VyTW9kZWwoZnVsbE5hbWU6ICdObyBJRCcsIGVtYWlsOiAneEB5LnonLCBhdmF0YXI6ICdhJyksCiAgICAgICksCiAgICAgIHRocm93c0FyZ3VtZW50RXJyb3IsCiAgICApOwogIH0pOwp9Cg==',
  'test/_prm393_sqlite_repository.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CmltcG9ydCAncGFja2FnZTpwYXRoL3BhdGguZGFydCcgYXMgcGF0aDsKaW1wb3J0ICdwYWNrYWdlOnNxZmxpdGUvc3FmbGl0ZS5kYXJ0JzsKaW1wb3J0ICdwYWNrYWdlOnNxZmxpdGVfY29tbW9uX2ZmaS9zcWZsaXRlX2ZmaS5kYXJ0JzsKCmltcG9ydCAnLi4vbGliL2RhdGFiYXNlL2RhdGFiYXNlX3NlcnZpY2UuZGFydCc7CmltcG9ydCAnLi4vbGliL21vZGVscy91c2VyX21vZGVsLmRhcnQnOwppbXBvcnQgJy4uL2xpYi9yZXBvc2l0b3JpZXMvdXNlcl9yZXBvc2l0b3J5LmRhcnQnOwoKdm9pZCBtYWluKCkgewogIGxhdGUgU3RyaW5nIGRhdGFiYXNlUGF0aDsKCiAgc2V0VXBBbGwoKCkgewogICAgc3FmbGl0ZUZmaUluaXQoKTsKICAgIGRhdGFiYXNlRmFjdG9yeSA9IGRhdGFiYXNlRmFjdG9yeUZmaTsKICB9KTsKCiAgc2V0VXAoKCkgYXN5bmMgewogICAgZGF0YWJhc2VQYXRoID0gcGF0aC5qb2luKGF3YWl0IGdldERhdGFiYXNlc1BhdGgoKSwgJ3BybTM5M191c2Vycy5kYicpOwogICAgYXdhaXQgZGF0YWJhc2VGYWN0b3J5LmRlbGV0ZURhdGFiYXNlKGRhdGFiYXNlUGF0aCk7CiAgfSk7CgogIHRlYXJEb3duKCgpIGFzeW5jIHsKICAgIGF3YWl0IGRhdGFiYXNlRmFjdG9yeS5kZWxldGVEYXRhYmFzZShkYXRhYmFzZVBhdGgpOwogIH0pOwoKICB0ZXN0KCdTUUxJVEVfUkVQT1NJVE9SWV9URU1QX0RBVEFCQVNFX0NSVUQnLCAoKSBhc3luYyB7CiAgICBmaW5hbCByZXBvc2l0b3J5ID0gU3FsaXRlVXNlclJlcG9zaXRvcnkoU3FsaXRlRGF0YWJhc2VTZXJ2aWNlKCkpOwogICAgY29uc3QgZmlyc3QgPSBVc2VyTW9kZWwoCiAgICAgIGZ1bGxOYW1lOiAnU1FMaXRlIFNhbWUnLAogICAgICBlbWFpbDogJ3NxbGl0ZS5zYW1lQGV4YW1wbGUuY29tJywKICAgICAgYXZhdGFyOiAnbGliL2Fzc2V0cy9kZWZhdWx0X2F2YXRhci5qcGcnLAogICAgKTsKICAgIGNvbnN0IHNlY29uZCA9IFVzZXJNb2RlbCgKICAgICAgZnVsbE5hbWU6ICdTUUxpdGUgU2FtZScsCiAgICAgIGVtYWlsOiAnc3FsaXRlLnNhbWVAZXhhbXBsZS5jb20nLAogICAgICBhdmF0YXI6ICdsaWIvYXNzZXRzL2RlZmF1bHRfYXZhdGFyLmpwZycsCiAgICApOwoKICAgIGF3YWl0IHJlcG9zaXRvcnkuYWRkVXNlcihmaXJzdCk7CiAgICBhd2FpdCByZXBvc2l0b3J5LmFkZFVzZXIoc2Vjb25kKTsKICAgIHZhciB1c2VycyA9IGF3YWl0IHJlcG9zaXRvcnkuZ2V0VXNlcnMoKTsKICAgIGV4cGVjdCh1c2VycywgaGFzTGVuZ3RoKDIpKTsKICAgIGV4cGVjdCh1c2Vycy5tYXAoKHVzZXIpID0+IHVzZXIuaWQpLnRvU2V0KCksIGhhc0xlbmd0aCgyKSk7CgogICAgZmluYWwgdXBkYXRlZCA9IHVzZXJzLmxhc3QuY29weVdpdGgoZW1haWw6ICdzcWxpdGUudXBkYXRlZEBleGFtcGxlLmNvbScpOwogICAgYXdhaXQgcmVwb3NpdG9yeS51cGRhdGVVc2VyKHVwZGF0ZWQpOwogICAgdXNlcnMgPSBhd2FpdCByZXBvc2l0b3J5LmdldFVzZXJzKCk7CiAgICBleHBlY3QoCiAgICAgIHVzZXJzLmFueSgodXNlcikgPT4gdXNlci5lbWFpbCA9PSAnc3FsaXRlLnVwZGF0ZWRAZXhhbXBsZS5jb20nKSwKICAgICAgaXNUcnVlLAogICAgKTsKCiAgICBhd2FpdCByZXBvc2l0b3J5LmRlbGV0ZVVzZXIodXBkYXRlZC5pZCEpOwogICAgdXNlcnMgPSBhd2FpdCByZXBvc2l0b3J5LmdldFVzZXJzKCk7CiAgICBleHBlY3QodXNlcnMsIGhhc0xlbmd0aCgxKSk7CiAgfSk7Cn0K',
  'test/_prm393_viewmodel.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3JpdmVycG9kL2ZsdXR0ZXJfcml2ZXJwb2QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9tb2RlbHMvdXNlcl9tb2RlbC5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvcmVwb3NpdG9yaWVzL3VzZXJfcmVwb3NpdG9yeS5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvdmlld21vZGVscy91c2VyX3ZpZXdfbW9kZWwuZGFydCc7CgpjbGFzcyBfU3B5UmVwb3NpdG9yeSBpbXBsZW1lbnRzIFVzZXJSZXBvc2l0b3J5IHsKICBfU3B5UmVwb3NpdG9yeShbTGlzdDxVc2VyTW9kZWw+IHNlZWQgPSBjb25zdCBbXV0pCiAgICA6IHVzZXJzID0gc2VlZC5tYXAoKHVzZXIpID0+IHVzZXIuY29weVdpdGgoKSkudG9MaXN0KCk7CgogIGZpbmFsIExpc3Q8VXNlck1vZGVsPiB1c2VyczsKICBmaW5hbCBjYWxscyA9IDxTdHJpbmc+W107CiAgdmFyIF9uZXh0SWQgPSAxOwoKICBAb3ZlcnJpZGUKICBGdXR1cmU8TGlzdDxVc2VyTW9kZWw+PiBnZXRVc2VycygpIGFzeW5jIHsKICAgIGNhbGxzLmFkZCgnZ2V0VXNlcnMnKTsKICAgIHJldHVybiB1c2Vycy5tYXAoKHVzZXIpID0+IHVzZXIuY29weVdpdGgoKSkudG9MaXN0KCk7CiAgfQoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gYWRkVXNlcihVc2VyTW9kZWwgdXNlcikgYXN5bmMgewogICAgY2FsbHMuYWRkKCdhZGRVc2VyJyk7CiAgICBmaW5hbCBpZCA9IHVzZXIuaWQgPz8gX25leHRJZCsrOwogICAgaWYgKGlkID49IF9uZXh0SWQpIF9uZXh0SWQgPSBpZCArIDE7CiAgICB1c2Vycy5hZGQodXNlci5jb3B5V2l0aChpZDogaWQpKTsKICB9CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiB1cGRhdGVVc2VyKFVzZXJNb2RlbCB1c2VyKSBhc3luYyB7CiAgICBjYWxscy5hZGQoJ3VwZGF0ZVVzZXInKTsKICAgIGZpbmFsIGluZGV4ID0gdXNlcnMuaW5kZXhXaGVyZSgoaXRlbSkgPT4gaXRlbS5pZCA9PSB1c2VyLmlkKTsKICAgIGlmIChpbmRleCA8IDApIHRocm93IFN0YXRlRXJyb3IoJ21pc3NpbmcgaWQnKTsKICAgIHVzZXJzW2luZGV4XSA9IHVzZXIuY29weVdpdGgoKTsKICB9CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiBkZWxldGVVc2VyKGludCBpZCkgYXN5bmMgewogICAgY2FsbHMuYWRkKCdkZWxldGVVc2VyJyk7CiAgICB1c2Vycy5yZW1vdmVXaGVyZSgodXNlcikgPT4gdXNlci5pZCA9PSBpZCk7CiAgfQp9Cgp2b2lkIG1haW4oKSB7CiAgdGVzdCgnVklFV01PREVMX0xPQURfQUREX1VQREFURV9ERUxFVEVfQU5EX1NUQVRFJywgKCkgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TcHlSZXBvc2l0b3J5KGNvbnN0IFsKICAgICAgVXNlck1vZGVsKAogICAgICAgIGlkOiAxMCwKICAgICAgICBmdWxsTmFtZTogJ1NlZWQgVXNlcicsCiAgICAgICAgZW1haWw6ICdzZWVkQGV4YW1wbGUuY29tJywKICAgICAgICBhdmF0YXI6ICdsaWIvYXNzZXRzL2RlZmF1bHRfYXZhdGFyLmpwZycsCiAgICAgICksCiAgICBdKTsKICAgIGZpbmFsIGNvbnRhaW5lciA9IFByb3ZpZGVyQ29udGFpbmVyKAogICAgICBvdmVycmlkZXM6IFt1c2VyUmVwb3NpdG9yeVByb3ZpZGVyLm92ZXJyaWRlV2l0aFZhbHVlKHJlcG9zaXRvcnkpXSwKICAgICk7CiAgICBhZGRUZWFyRG93bihjb250YWluZXIuZGlzcG9zZSk7CiAgICBmaW5hbCB2aWV3TW9kZWwgPSBjb250YWluZXIucmVhZCh1c2VyVmlld01vZGVsUHJvdmlkZXIubm90aWZpZXIpOwoKICAgIGF3YWl0IHZpZXdNb2RlbC5sb2FkVXNlcnMoKTsKICAgIGV4cGVjdCgKICAgICAgY29udGFpbmVyLnJlYWQodXNlclZpZXdNb2RlbFByb3ZpZGVyKS5pdGVtcy5zaW5nbGUuZnVsbE5hbWUsCiAgICAgICdTZWVkIFVzZXInLAogICAgKTsKICAgIGV4cGVjdChjb250YWluZXIucmVhZCh1c2VyVmlld01vZGVsUHJvdmlkZXIpLmlzTG9hZGluZywgaXNGYWxzZSk7CgogICAgYXdhaXQgdmlld01vZGVsLmFkZFVzZXIoCiAgICAgIGNvbnN0IFVzZXJNb2RlbCgKICAgICAgICBmdWxsTmFtZTogJ0FkZGVkIFVzZXInLAogICAgICAgIGVtYWlsOiAnYWRkZWRAZXhhbXBsZS5jb20nLAogICAgICAgIGF2YXRhcjogJ2xpYi9hc3NldHMvZGVmYXVsdF9hdmF0YXIuanBnJywKICAgICAgKSwKICAgICk7CiAgICBmaW5hbCBhZGRlZCA9IHJlcG9zaXRvcnkudXNlcnMuc2luZ2xlV2hlcmUoCiAgICAgICh1c2VyKSA9PiB1c2VyLmZ1bGxOYW1lID09ICdBZGRlZCBVc2VyJywKICAgICk7CiAgICBleHBlY3QoYWRkZWQuaWQsIGlzTm90TnVsbCk7CiAgICBleHBlY3QoY29udGFpbmVyLnJlYWQodXNlclZpZXdNb2RlbFByb3ZpZGVyKS5pdGVtcywgaGFzTGVuZ3RoKDIpKTsKCiAgICBhd2FpdCB2aWV3TW9kZWwudXBkYXRlVXNlcihhZGRlZC5jb3B5V2l0aChmdWxsTmFtZTogJ1VwZGF0ZWQgVXNlcicpKTsKICAgIGV4cGVjdCgKICAgICAgY29udGFpbmVyCiAgICAgICAgICAucmVhZCh1c2VyVmlld01vZGVsUHJvdmlkZXIpCiAgICAgICAgICAuaXRlbXMKICAgICAgICAgIC5hbnkoKHVzZXIpID0+IHVzZXIuZnVsbE5hbWUgPT0gJ1VwZGF0ZWQgVXNlcicpLAogICAgICBpc1RydWUsCiAgICApOwoKICAgIGF3YWl0IHZpZXdNb2RlbC5kZWxldGVVc2VyKGFkZGVkLmlkISk7CiAgICBleHBlY3QoY29udGFpbmVyLnJlYWQodXNlclZpZXdNb2RlbFByb3ZpZGVyKS5pdGVtcywgaGFzTGVuZ3RoKDEpKTsKICAgIGV4cGVjdCgKICAgICAgcmVwb3NpdG9yeS5jYWxscywKICAgICAgY29udGFpbnNBbGwoPFN0cmluZz5bJ2dldFVzZXJzJywgJ2FkZFVzZXInLCAndXBkYXRlVXNlcicsICdkZWxldGVVc2VyJ10pLAogICAgKTsKICB9KTsKfQo=',
  'test/_prm393_screen.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXIvbWF0ZXJpYWwuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3JpdmVycG9kL2ZsdXR0ZXJfcml2ZXJwb2QuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3Rlc3QvZmx1dHRlcl90ZXN0LmRhcnQnOwoKaW1wb3J0ICcuLi9saWIvbW9kZWxzL3VzZXJfbW9kZWwuZGFydCc7CmltcG9ydCAnLi4vbGliL3JlcG9zaXRvcmllcy91c2VyX3JlcG9zaXRvcnkuZGFydCc7CmltcG9ydCAnLi4vbGliL3NjcmVlbnMvdXNlcl9saXN0X3NjcmVlbi5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvdmlld21vZGVscy91c2VyX3ZpZXdfbW9kZWwuZGFydCc7CgpjbGFzcyBfU2NyZWVuRmFrZVJlcG9zaXRvcnkgaW1wbGVtZW50cyBVc2VyUmVwb3NpdG9yeSB7CiAgX1NjcmVlbkZha2VSZXBvc2l0b3J5KFtMaXN0PFVzZXJNb2RlbD4gc2VlZCA9IGNvbnN0IFtdXSkKICAgIDogdXNlcnMgPSBzZWVkLm1hcCgodXNlcikgPT4gdXNlci5jb3B5V2l0aCgpKS50b0xpc3QoKTsKCiAgZmluYWwgTGlzdDxVc2VyTW9kZWw+IHVzZXJzOwogIHZhciBfbmV4dElkID0gMTsKCiAgQG92ZXJyaWRlCiAgRnV0dXJlPExpc3Q8VXNlck1vZGVsPj4gZ2V0VXNlcnMoKSBhc3luYyA9PgogICAgICB1c2Vycy5tYXAoKHVzZXIpID0+IHVzZXIuY29weVdpdGgoKSkudG9MaXN0KCk7CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiBhZGRVc2VyKFVzZXJNb2RlbCB1c2VyKSBhc3luYyB7CiAgICBmaW5hbCBpZCA9IHVzZXIuaWQgPz8gX25leHRJZCsrOwogICAgaWYgKGlkID49IF9uZXh0SWQpIF9uZXh0SWQgPSBpZCArIDE7CiAgICB1c2Vycy5hZGQodXNlci5jb3B5V2l0aChpZDogaWQpKTsKICB9CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiB1cGRhdGVVc2VyKFVzZXJNb2RlbCB1c2VyKSBhc3luYyB7CiAgICBmaW5hbCBpbmRleCA9IHVzZXJzLmluZGV4V2hlcmUoKGl0ZW0pID0+IGl0ZW0uaWQgPT0gdXNlci5pZCk7CiAgICBpZiAoaW5kZXggPCAwKSB0aHJvdyBTdGF0ZUVycm9yKCdtaXNzaW5nIGlkJyk7CiAgICB1c2Vyc1tpbmRleF0gPSB1c2VyLmNvcHlXaXRoKCk7CiAgfQoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gZGVsZXRlVXNlcihpbnQgaWQpIGFzeW5jID0+CiAgICAgIHVzZXJzLnJlbW92ZVdoZXJlKCh1c2VyKSA9PiB1c2VyLmlkID09IGlkKTsKfQoKY29uc3QgX2F2YXRhciA9ICdsaWIvYXNzZXRzL2RlZmF1bHRfYXZhdGFyLmpwZyc7CgpGdXR1cmU8dm9pZD4gX3B1bXBTY3JlZW4oCiAgV2lkZ2V0VGVzdGVyIHRlc3RlciwKICBfU2NyZWVuRmFrZVJlcG9zaXRvcnkgcmVwb3NpdG9yeSwKKSBhc3luYyB7CiAgYXdhaXQgdGVzdGVyLnB1bXBXaWRnZXQoCiAgICBQcm92aWRlclNjb3BlKAogICAgICBvdmVycmlkZXM6IFt1c2VyUmVwb3NpdG9yeVByb3ZpZGVyLm92ZXJyaWRlV2l0aFZhbHVlKHJlcG9zaXRvcnkpXSwKICAgICAgY2hpbGQ6IGNvbnN0IE1hdGVyaWFsQXBwKGhvbWU6IFVzZXJMaXN0U2NyZWVuKCkpLAogICAgKSwKICApOwogIGF3YWl0IHRlc3Rlci5wdW1wKCk7CiAgYXdhaXQgdGVzdGVyLnB1bXAoY29uc3QgRHVyYXRpb24obWlsbGlzZWNvbmRzOiAxMDApKTsKfQoKRmluZGVyIF9maWVsZChpbnQgaW5kZXgpID0+IGZpbmQuYnlUeXBlKFRleHRGb3JtRmllbGQpLmF0KGluZGV4KTsKCkZ1dHVyZTx2b2lkPiBfY2hvb3NlQXZhdGFyKFdpZGdldFRlc3RlciB0ZXN0ZXIpIGFzeW5jIHsKICBhd2FpdCB0ZXN0ZXIudGFwKGZpbmQudGV4dCgnQ2hvb3NlIEF2YXRhcicpKTsKICBhd2FpdCB0ZXN0ZXIucHVtcEFuZFNldHRsZSgpOwogIGF3YWl0IHRlc3Rlci50YXAoZmluZC50ZXh0KCdEZWZhdWx0IEF2YXRhcicpKTsKICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwp9Cgp2b2lkIG1haW4oKSB7CiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9GT1JNX0NPTlRST0xTJywgKHRlc3RlcikgYXN5bmMgewogICAgYXdhaXQgX3B1bXBTY3JlZW4odGVzdGVyLCBfU2NyZWVuRmFrZVJlcG9zaXRvcnkoKSk7CiAgICBleHBlY3QoZmluZC5ieVR5cGUoVGV4dEZvcm1GaWVsZCksIGZpbmRzTldpZGdldHMoMikpOwogICAgZXhwZWN0KGZpbmQudGV4dCgnQ2hvb3NlIEF2YXRhcicpLCBmaW5kc09uZVdpZGdldCk7CiAgICBleHBlY3QoZmluZC50ZXh0KCdBZGQgVXNlcicpLCBmaW5kc09uZVdpZGdldCk7CiAgfSk7CgogIHRlc3RXaWRnZXRzKCdTQ1JFRU5fVkFMSURBVEVfRUFDSF9GSUVMRCcsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGF3YWl0IF9wdW1wU2NyZWVuKHRlc3RlciwgX1NjcmVlbkZha2VSZXBvc2l0b3J5KCkpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLnRleHQoJ0FkZCBVc2VyJykpOwogICAgYXdhaXQgdGVzdGVyLnB1bXAoKTsKICAgIGZpbmFsIGVycm9ycyA9IGZpbmQuYnlXaWRnZXRQcmVkaWNhdGUoKHdpZGdldCkgewogICAgICBmaW5hbCB0ZXh0ID0gd2lkZ2V0IGlzIFRleHQgPyB3aWRnZXQuZGF0YSA/PyAnJyA6ICcnOwogICAgICByZXR1cm4gUmVnRXhwKAogICAgICAgIHIncmVxdWlyZWR8aW52YWxpZHxtaW5pbXVtfG1pbnxi4bqvdCBideG7mWN8a2jDtG5nIGjhu6NwIGzhu4d8dOG7kWkgdGhp4buDdXxiYXQgYnVvY3xraG9uZyBob3AgbGV8dG9pIHRoaWV1JywKICAgICAgICBjYXNlU2Vuc2l0aXZlOiBmYWxzZSwKICAgICAgKS5oYXNNYXRjaCh0ZXh0KTsKICAgIH0pOwogICAgZXhwZWN0KGVycm9ycywgZmluZHNOV2lkZ2V0cygzKSk7CiAgfSk7CgogIHRlc3RXaWRnZXRzKCdTQ1JFRU5fTElTVF9GUk9NX0ZBS0VfUkVQT1NJVE9SWScsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU2NyZWVuRmFrZVJlcG9zaXRvcnkoY29uc3QgWwogICAgICBVc2VyTW9kZWwoCiAgICAgICAgaWQ6IDEsCiAgICAgICAgZnVsbE5hbWU6ICdGaXJzdCBVc2VyJywKICAgICAgICBlbWFpbDogJ2ZpcnN0QGV4YW1wbGUuY29tJywKICAgICAgICBhdmF0YXI6IF9hdmF0YXIsCiAgICAgICksCiAgICAgIFVzZXJNb2RlbCgKICAgICAgICBpZDogMiwKICAgICAgICBmdWxsTmFtZTogJ1NlY29uZCBVc2VyJywKICAgICAgICBlbWFpbDogJ3NlY29uZEBleGFtcGxlLmNvbScsCiAgICAgICAgYXZhdGFyOiBfYXZhdGFyLAogICAgICApLAogICAgXSk7CiAgICBhd2FpdCBfcHVtcFNjcmVlbih0ZXN0ZXIsIHJlcG9zaXRvcnkpOwogICAgZXhwZWN0KGZpbmQudGV4dCgnRmlyc3QgVXNlcicpLCBmaW5kc09uZVdpZGdldCk7CiAgICBleHBlY3QoZmluZC50ZXh0KCdTZWNvbmQgVXNlcicpLCBmaW5kc09uZVdpZGdldCk7CiAgfSk7CgogIHRlc3RXaWRnZXRzKCdTQ1JFRU5fQUREX1VTRVInLCAodGVzdGVyKSBhc3luYyB7CiAgICBmaW5hbCByZXBvc2l0b3J5ID0gX1NjcmVlbkZha2VSZXBvc2l0b3J5KCk7CiAgICBhd2FpdCBfcHVtcFNjcmVlbih0ZXN0ZXIsIHJlcG9zaXRvcnkpOwogICAgYXdhaXQgdGVzdGVyLmVudGVyVGV4dChfZmllbGQoMCksICdBZGRlZCBGcm9tIFNjcmVlbicpOwogICAgYXdhaXQgdGVzdGVyLmVudGVyVGV4dChfZmllbGQoMSksICdzY3JlZW5AZXhhbXBsZS5jb20nKTsKICAgIGF3YWl0IF9jaG9vc2VBdmF0YXIodGVzdGVyKTsKICAgIGF3YWl0IHRlc3Rlci50YXAoZmluZC50ZXh0KCdBZGQgVXNlcicpKTsKICAgIGF3YWl0IHRlc3Rlci5wdW1wKCk7CiAgICBleHBlY3QocmVwb3NpdG9yeS51c2Vycy5zaW5nbGUuZnVsbE5hbWUsICdBZGRlZCBGcm9tIFNjcmVlbicpOwogIH0pOwoKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX1VQREFURV9VU0VSJywgKHRlc3RlcikgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TY3JlZW5GYWtlUmVwb3NpdG9yeShjb25zdCBbCiAgICAgIFVzZXJNb2RlbCgKICAgICAgICBpZDogMSwKICAgICAgICBmdWxsTmFtZTogJ09yaWdpbmFsIEZyb20gU2NyZWVuJywKICAgICAgICBlbWFpbDogJ29yaWdpbmFsLXNjcmVlbkBleGFtcGxlLmNvbScsCiAgICAgICAgYXZhdGFyOiBfYXZhdGFyLAogICAgICApLAogICAgXSk7CiAgICBhd2FpdCBfcHVtcFNjcmVlbih0ZXN0ZXIsIHJlcG9zaXRvcnkpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLmJ5U2VtYW50aWNzTGFiZWwoJ0VkaXQnKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgZXhwZWN0KAogICAgICB0ZXN0ZXIud2lkZ2V0PFRleHRGb3JtRmllbGQ+KF9maWVsZCgwKSkuY29udHJvbGxlciEudGV4dCwKICAgICAgJ09yaWdpbmFsIEZyb20gU2NyZWVuJywKICAgICk7CiAgICBhd2FpdCB0ZXN0ZXIuZW50ZXJUZXh0KF9maWVsZCgwKSwgJ1VwZGF0ZWQgRnJvbSBTY3JlZW4nKTsKICAgIGF3YWl0IHRlc3Rlci50YXAoZmluZC50ZXh0KCdVcGRhdGUgVXNlcicpKTsKICAgIGF3YWl0IHRlc3Rlci5wdW1wKCk7CiAgICBleHBlY3QocmVwb3NpdG9yeS51c2Vycy5zaW5nbGUuZnVsbE5hbWUsICdVcGRhdGVkIEZyb20gU2NyZWVuJyk7CiAgfSk7CgogIHRlc3RXaWRnZXRzKCdTQ1JFRU5fREVMRVRFX0RJQUxPR19BTkRfQ09ORklSTScsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU2NyZWVuRmFrZVJlcG9zaXRvcnkoY29uc3QgWwogICAgICBVc2VyTW9kZWwoCiAgICAgICAgaWQ6IDEsCiAgICAgICAgZnVsbE5hbWU6ICdEZWxldGUgVGFyZ2V0JywKICAgICAgICBlbWFpbDogJ2RlbGV0ZUBleGFtcGxlLmNvbScsCiAgICAgICAgYXZhdGFyOiBfYXZhdGFyLAogICAgICApLAogICAgXSk7CiAgICBhd2FpdCBfcHVtcFNjcmVlbih0ZXN0ZXIsIHJlcG9zaXRvcnkpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLmJ5U2VtYW50aWNzTGFiZWwoJ0RlbGV0ZScpKTsKICAgIGF3YWl0IHRlc3Rlci5wdW1wKCk7CiAgICBleHBlY3QoZmluZC50ZXh0KCdDb25maXJtIERlbGV0ZScpLCBmaW5kc09uZVdpZGdldCk7CiAgICBhd2FpdCB0ZXN0ZXIudGFwKGZpbmQudGV4dCgnQ29uZmlybSBEZWxldGUnKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgZXhwZWN0KHJlcG9zaXRvcnkudXNlcnMsIGlzRW1wdHkpOwogIH0pOwoKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX0RFVEFJTF9SRUNFSVZFU19VU0VSJywgKHRlc3RlcikgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TY3JlZW5GYWtlUmVwb3NpdG9yeShjb25zdCBbCiAgICAgIFVzZXJNb2RlbCgKICAgICAgICBpZDogMSwKICAgICAgICBmdWxsTmFtZTogJ0RldGFpbCBUYXJnZXQnLAogICAgICAgIGVtYWlsOiAnZGV0YWlsQGV4YW1wbGUuY29tJywKICAgICAgICBhdmF0YXI6IF9hdmF0YXIsCiAgICAgICksCiAgICBdKTsKICAgIGF3YWl0IF9wdW1wU2NyZWVuKHRlc3RlciwgcmVwb3NpdG9yeSk7CiAgICBhd2FpdCB0ZXN0ZXIudGFwKGZpbmQudGV4dCgnRGV0YWlsIFRhcmdldCcpKTsKICAgIGF3YWl0IHRlc3Rlci5wdW1wQW5kU2V0dGxlKCk7CiAgICBleHBlY3QoZmluZC50ZXh0KCdEZXRhaWwgVGFyZ2V0JyksIGZpbmRzT25lV2lkZ2V0KTsKICAgIGV4cGVjdChmaW5kLnRleHQoJ2RldGFpbEBleGFtcGxlLmNvbScpLCBmaW5kc09uZVdpZGdldCk7CiAgICBleHBlY3QoZmluZC50ZXh0KCdCYWNrJyksIGZpbmRzT25lV2lkZ2V0KTsKICB9KTsKfQo=',
  'test/_prm393_visual.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXIvbWF0ZXJpYWwuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3Rlc3QvZmx1dHRlcl90ZXN0LmRhcnQnOwoKaW1wb3J0ICcuLi9saWIvbWFpbi5kYXJ0JyBhcyBzdHVkZW50X2FwcDsKCmNvbnN0IF9zdHJpY3RHb2xkZW4gPSBib29sLmZyb21FbnZpcm9ubWVudCgnUFJNMzkzX1NUUklDVF9HT0xERU4nKTsKCi8vLyBHb2xkZW4gdGhhbSBjaGnhur91IGNo4buJIGTDuW5nIMSR4buDIHJldmlldyB2aXN1YWwgcmVncmVzc2lvbiwga2jDtG5nIGtow7NhIMSRaeG7g20KLy8vIHRoZW8gdOG7q25nIHBpeGVsIHbDrCBzaW5oIHZpw6puIGPDsyB0aOG7gyBjaOG7jW4gdGhlbWUvbGF5b3V0IGjhu6NwIGzhu4cga2jDoWMgbmhhdS4Kdm9pZCBtYWluKCkgewogIFRlc3RXaWRnZXRzRmx1dHRlckJpbmRpbmcuZW5zdXJlSW5pdGlhbGl6ZWQoKTsKCiAgdGVzdFdpZGdldHMoJ1ZJU1VBTF9HT0xERU5fUE9SVFJBSVQnLCAodGVzdGVyKSBhc3luYyB7CiAgICB0ZXN0ZXIudmlldy5waHlzaWNhbFNpemUgPSBjb25zdCBTaXplKDQwMCwgODAwKTsKICAgIHRlc3Rlci52aWV3LmRldmljZVBpeGVsUmF0aW8gPSAxOwogICAgYWRkVGVhckRvd24odGVzdGVyLnZpZXcucmVzZXRQaHlzaWNhbFNpemUpOwogICAgYWRkVGVhckRvd24odGVzdGVyLnZpZXcucmVzZXREZXZpY2VQaXhlbFJhdGlvKTsKCiAgICBzdHVkZW50X2FwcC5tYWluKCk7CiAgICBhd2FpdCBfc2V0dGxlKHRlc3Rlcik7CiAgICBhd2FpdCBfY2hlY2tWaXN1YWwodGVzdGVyLCAnZ29sZGVucy9wcm0zOTNfaG9tZV9wb3J0cmFpdC5wbmcnKTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1ZJU1VBTF9HT0xERU5fTEFORFNDQVBFJywgKHRlc3RlcikgYXN5bmMgewogICAgdGVzdGVyLnZpZXcucGh5c2ljYWxTaXplID0gY29uc3QgU2l6ZSgxMDI0LCA3NjgpOwogICAgdGVzdGVyLnZpZXcuZGV2aWNlUGl4ZWxSYXRpbyA9IDE7CiAgICBhZGRUZWFyRG93bih0ZXN0ZXIudmlldy5yZXNldFBoeXNpY2FsU2l6ZSk7CiAgICBhZGRUZWFyRG93bih0ZXN0ZXIudmlldy5yZXNldERldmljZVBpeGVsUmF0aW8pOwoKICAgIHN0dWRlbnRfYXBwLm1haW4oKTsKICAgIGF3YWl0IF9zZXR0bGUodGVzdGVyKTsKICAgIGF3YWl0IF9jaGVja1Zpc3VhbCh0ZXN0ZXIsICdnb2xkZW5zL3BybTM5M19ob21lX2xhbmRzY2FwZS5wbmcnKTsKICB9KTsKfQoKRnV0dXJlPHZvaWQ+IF9jaGVja1Zpc3VhbChXaWRnZXRUZXN0ZXIgdGVzdGVyLCBTdHJpbmcgZ29sZGVuUGF0aCkgYXN5bmMgewogIGV4cGVjdChmaW5kLmJ5VHlwZShNYXRlcmlhbEFwcCksIGZpbmRzT25lV2lkZ2V0KTsKICBleHBlY3QodGVzdGVyLnRha2VFeGNlcHRpb24oKSwgaXNOdWxsKTsKICBpZiAoX3N0cmljdEdvbGRlbikgewogICAgYXdhaXQgZXhwZWN0TGF0ZXIoZmluZC5ieVR5cGUoTWF0ZXJpYWxBcHApLCBtYXRjaGVzR29sZGVuRmlsZShnb2xkZW5QYXRoKSk7CiAgfQp9CgpGdXR1cmU8dm9pZD4gX3NldHRsZShXaWRnZXRUZXN0ZXIgdGVzdGVyKSBhc3luYyB7CiAgYXdhaXQgdGVzdGVyLnB1bXAoY29uc3QgRHVyYXRpb24obWlsbGlzZWNvbmRzOiAyMDApKTsKICBhd2FpdCB0ZXN0ZXIucHVtcChjb25zdCBEdXJhdGlvbihtaWxsaXNlY29uZHM6IDUwMCkpOwp9Cg==',
  'test/_prm393_architecture_audit.dart':
      'aW1wb3J0ICdkYXJ0OmNvbnZlcnQnOwppbXBvcnQgJ2RhcnQ6aW8nOwoKLy8gxJDDonkgbMOgIGV2aWRlbmNlIGjhu5cgdHLhu6MgY2hvIHJ1YnJpYyBraeG6v24gdHLDumMsIGtow7RuZyBwaOG6o2kgYuG6sW5nIGNo4bupbmcgdHV54buHdCDEkeG7kWkuCi8vIFVJIHbhuqtuIMSRxrDhu6NjIGNo4bqlbSDEkeG7mWMgbOG6rXAgcXVhIGjDoG5oIHZpIGJsYWNrLWJveC4KRnV0dXJlPHZvaWQ+IG1haW4oTGlzdDxTdHJpbmc+IGFyZ3MpIGFzeW5jIHsKICBmaW5hbCByb290ID0gRGlyZWN0b3J5KGFyZ3MuaXNFbXB0eSA/ICcvYXBwL2xpYicgOiBhcmdzLmZpcnN0KTsKICBmaW5hbCBmaWxlcyA9IHJvb3QuZXhpc3RzU3luYygpCiAgICAgID8gcm9vdAogICAgICAgICAgICAubGlzdFN5bmMocmVjdXJzaXZlOiB0cnVlKQogICAgICAgICAgICAud2hlcmVUeXBlPEZpbGU+KCkKICAgICAgICAgICAgLndoZXJlKChmaWxlKSA9PiBmaWxlLnBhdGgudG9Mb3dlckNhc2UoKS5lbmRzV2l0aCgnLmRhcnQnKSkKICAgICAgICAgICAgLnRvTGlzdCgpCiAgICAgIDogPEZpbGU+W107CgogIGZpbmFsIHNvdXJjZXMgPSA8U3RyaW5nLCBTdHJpbmc+e307CiAgZm9yIChmaW5hbCBmaWxlIGluIGZpbGVzKSB7CiAgICB0cnkgewogICAgICBzb3VyY2VzW19ub3JtYWxQYXRoKGZpbGUucGF0aCkudG9Mb3dlckNhc2UoKV0gPSBhd2FpdCBmaWxlLnJlYWRBc1N0cmluZygpOwogICAgfSBjYXRjaCAoXykgewogICAgICAvLyBC4buPIHF1YSBmaWxlIGzhu5dpIMSR4buNYzsgY8OhYyBldmlkZW5jZSBjw7JuIGzhuqFpIHbhuqtuIGPDsyBnacOhIHRy4buLLgogICAgfQogIH0KCiAgZmluYWwgcHJvamVjdFJvb3QgPSByb290LnBhcmVudDsKICBmaW5hbCBwdWJzcGVjQ2FuZGlkYXRlcyA9IDxGaWxlPlsKICAgIEZpbGUoJyR7cHJvamVjdFJvb3QucGF0aH0ke1BsYXRmb3JtLnBhdGhTZXBhcmF0b3J9cHVic3BlYy55YW1sJyksCiAgICBGaWxlKCcke3Byb2plY3RSb290LnBhdGh9JHtQbGF0Zm9ybS5wYXRoU2VwYXJhdG9yfXB1YnNwZWNfc3R1ZGVudC55YW1sJyksCiAgXTsKICBmaW5hbCBwdWJzcGVjRmlsZSA9IHB1YnNwZWNDYW5kaWRhdGVzLmZpcnN0V2hlcmUoCiAgICAoZmlsZSkgPT4gZmlsZS5leGlzdHNTeW5jKCksCiAgICBvckVsc2U6ICgpID0+IHB1YnNwZWNDYW5kaWRhdGVzLmZpcnN0LAogICk7CiAgZmluYWwgcHVic3BlYyA9IHB1YnNwZWNGaWxlLmV4aXN0c1N5bmMoKQogICAgICA/IChhd2FpdCBwdWJzcGVjRmlsZS5yZWFkQXNTdHJpbmcoKSkudG9Mb3dlckNhc2UoKQogICAgICA6ICcnOwogIGZpbmFsIGFsbCA9IHNvdXJjZXMudmFsdWVzLmpvaW4oJ1xuJyk7CiAgZmluYWwgbG93ZXJBbGwgPSBhbGwudG9Mb3dlckNhc2UoKTsKCiAgZmluYWwgdWlGaWxlcyA9IHNvdXJjZXMuZW50cmllcy53aGVyZSgoZW50cnkpID0+IF9pc1VpUGF0aChlbnRyeS5rZXkpKTsKICBmaW5hbCBsb2dpY0ZpbGVzID0gc291cmNlcy5lbnRyaWVzLndoZXJlKChlbnRyeSkgPT4gX2lzTG9naWNQYXRoKGVudHJ5LmtleSkpOwogIGZpbmFsIHN0b3JhZ2VGaWxlcyA9IHNvdXJjZXMuZW50cmllcy53aGVyZSgKICAgIChlbnRyeSkgPT4gX2lzU3RvcmFnZVBhdGgoZW50cnkua2V5KSwKICApOwoKICBmaW5hbCBoYXNVaVNvdXJjZSA9CiAgICAgIHVpRmlsZXMuaXNOb3RFbXB0eSB8fAogICAgICBSZWdFeHAoCiAgICAgICAgcidleHRlbmRzXHMrKHN0YXRlbGVzc3dpZGdldHxzdGF0ZWZ1bHdpZGdldHxjb25zdW1lcndpZGdldHxob29rd2lkZ2V0KScsCiAgICAgICAgY2FzZVNlbnNpdGl2ZTogZmFsc2UsCiAgICAgICkuaGFzTWF0Y2goYWxsKTsKICBmaW5hbCBoYXNMb2dpY1NvdXJjZSA9CiAgICAgIGxvZ2ljRmlsZXMuaXNOb3RFbXB0eSB8fAogICAgICBSZWdFeHAoCiAgICAgICAgcidcYihjaGFuZ2Vub3RpZmllcnxzdGF0ZW5vdGlmaWVyfG5vdGlmaWVyfHJlZlwud2F0Y2h8cmVmXC5yZWFkfHByb3ZpZGVyXHMqPHxibG9jfGN1Yml0KVxiJywKICAgICAgICBjYXNlU2Vuc2l0aXZlOiBmYWxzZSwKICAgICAgKS5oYXNNYXRjaChhbGwpOwogIGZpbmFsIGhhc1N0b3JhZ2VTb3VyY2UgPQogICAgICBzdG9yYWdlRmlsZXMuaXNOb3RFbXB0eSB8fAogICAgICBSZWdFeHAoCiAgICAgICAgcidvcGVuRGF0YWJhc2V8c3FsaXRlM1wub3BlbnxjcmVhdGVccyt0YWJsZXxpbnNlcnRccytpbnRvJywKICAgICAgICBjYXNlU2Vuc2l0aXZlOiBmYWxzZSwKICAgICAgKS5oYXNNYXRjaChhbGwpOwogIGZpbmFsIHZpZXdJbXBvcnRzU3RvcmFnZSA9IHVpRmlsZXMuYW55KAogICAgKGVudHJ5KSA9PiBfaGFzU3RvcmFnZUltcG9ydChlbnRyeS52YWx1ZSksCiAgKTsKICBmaW5hbCB1aVVzZXNMb2dpYyA9IHVpRmlsZXMuYW55KChlbnRyeSkgPT4gX2hhc0xvZ2ljSW1wb3J0KGVudHJ5LnZhbHVlKSk7CiAgZmluYWwgbG9naWNVc2VzU3RvcmFnZSA9IGxvZ2ljRmlsZXMuYW55KAogICAgKGVudHJ5KSA9PiBfaGFzU3RvcmFnZUltcG9ydChlbnRyeS52YWx1ZSksCiAgKTsKCiAgZmluYWwgbXZ2bSA9CiAgICAgIGhhc1VpU291cmNlICYmCiAgICAgIGhhc0xvZ2ljU291cmNlICYmCiAgICAgIGhhc1N0b3JhZ2VTb3VyY2UgJiYKICAgICAgIXZpZXdJbXBvcnRzU3RvcmFnZSAmJgogICAgICB1aVVzZXNMb2dpYyAmJgogICAgICBsb2dpY1VzZXNTdG9yYWdlOwoKICBmaW5hbCByaXZlcnBvZFBhY2thZ2UgPSBfY29udGFpbnNBbnkoJyRsb3dlckFsbFxuJHB1YnNwZWMnLCA8U3RyaW5nPlsKICAgICdmbHV0dGVyX3JpdmVycG9kJywKICAgICdyaXZlcnBvZF9hbm5vdGF0aW9uJywKICBdKTsKICBmaW5hbCByaXZlcnBvZEFwaSA9IFJlZ0V4cCgKICAgIHInKEByaXZlcnBvZHxcYihjb25zdW1lcih3aWRnZXR8c3RhdGVmdWx3aWRnZXR8c3RhdGUpP3xyZWZccypcLnxwcm92aWRlclxzKjx8bm90aWZpZXJwcm92aWRlcnxzdGF0ZW5vdGlmaWVycHJvdmlkZXIpXGIpJywKICAgIGNhc2VTZW5zaXRpdmU6IGZhbHNlLAogICkuaGFzTWF0Y2goYWxsKTsKICBmaW5hbCBnZW5lcmF0b3JFdmlkZW5jZSA9CiAgICAgIGxvd2VyQWxsLmNvbnRhaW5zKCdAcml2ZXJwb2QnKSB8fAogICAgICBSZWdFeHAoCiAgICAgICAgcicnJ3BhcnRccytbJyJdW14nIl0rXC5nXC5kYXJ0WyciXScnJywKICAgICAgICBjYXNlU2Vuc2l0aXZlOiBmYWxzZSwKICAgICAgKS5oYXNNYXRjaChhbGwpOwogIGZpbmFsIHByb3ZpZGVyU2NvcGUgPSBSZWdFeHAoCiAgICByJ1xicHJvdmlkZXJzY29wZVxzKlwoJywKICAgIGNhc2VTZW5zaXRpdmU6IGZhbHNlLAogICkuaGFzTWF0Y2goYWxsKTsKICBmaW5hbCByaXZlcnBvZFdpcmluZyA9IFJlZ0V4cCgKICAgIHInXGIocmVmXHMqXC5ccyood2F0Y2h8cmVhZCl8cHJvdmlkZXJccyo8fG5vdGlmaWVycHJvdmlkZXJ8c3RhdGVub3RpZmllcnByb3ZpZGVyKVxiJywKICAgIGNhc2VTZW5zaXRpdmU6IGZhbHNlLAogICkuaGFzTWF0Y2goYWxsKTsKICBmaW5hbCByaXZlcnBvZCA9CiAgICAgIHJpdmVycG9kUGFja2FnZSAmJgogICAgICByaXZlcnBvZEFwaSAmJgogICAgICBnZW5lcmF0b3JFdmlkZW5jZSAmJgogICAgICBwcm92aWRlclNjb3BlICYmCiAgICAgIHJpdmVycG9kV2lyaW5nOwoKICBmaW5hbCBzcWxpdGVQYWNrYWdlID0gX2NvbnRhaW5zQW55KCckbG93ZXJBbGxcbiRwdWJzcGVjJywgPFN0cmluZz5bCiAgICAnc3FmbGl0ZScsCiAgICAnc3FsaXRlMycsCiAgICAnc3FsaXRlX2FzeW5jJywKICAgICdkcmlmdCcsCiAgICAnZmxvb3InLAogIF0pOwogIGZpbmFsIHNxbGl0ZUFwaSA9IFJlZ0V4cCgKICAgIHInb3BlbkRhdGFiYXNlfHNxbGl0ZTNcLm9wZW58ZHJpZnRkYXRhYmFzZXxAZHJpZnRkYXRhYmFzZXxjcmVhdGVccyt0YWJsZXxpbnNlcnRccytpbnRvfFxic2VsZWN0XGJ8XC5xdWVyeVxzKlwofFwuaW5zZXJ0XHMqXCgnLAogICAgY2FzZVNlbnNpdGl2ZTogZmFsc2UsCiAgKS5oYXNNYXRjaChhbGwpOwogIGZpbmFsIHNxbGl0ZVdpcmluZyA9IGxvZ2ljRmlsZXMuYW55KAogICAgKGVudHJ5KSA9PiBfaGFzU3RvcmFnZUltcG9ydChlbnRyeS52YWx1ZSksCiAgKTsKICBmaW5hbCBzcWxpdGUgPSBzcWxpdGVQYWNrYWdlICYmIHNxbGl0ZUFwaSAmJiBzcWxpdGVXaXJpbmc7CiAgZmluYWwgZm9ybUdsb2JhbEtleSA9CiAgICAgIFJlZ0V4cChyJ1xiZm9ybVxzKlwofGZvcm1zdGF0ZScsIGNhc2VTZW5zaXRpdmU6IGZhbHNlKS5oYXNNYXRjaChhbGwpICYmCiAgICAgIFJlZ0V4cCgKICAgICAgICByJ2dsb2JhbGtleVxzKjxccypmb3Jtc3RhdGVccyo+JywKICAgICAgICBjYXNlU2Vuc2l0aXZlOiBmYWxzZSwKICAgICAgKS5oYXNNYXRjaChhbGwpICYmCiAgICAgIFJlZ0V4cChyJ1wudmFsaWRhdGVccypcKFxzKlwpJywgY2FzZVNlbnNpdGl2ZTogZmFsc2UpLmhhc01hdGNoKGFsbCk7CiAgZmluYWwgaW1hZ2VTb3VyY2UgPSBSZWdFeHAoCiAgICByJ2ltYWdlXHMqXC5ccyoobmV0d29ya3xhc3NldClccypcKCcsCiAgICBjYXNlU2Vuc2l0aXZlOiBmYWxzZSwKICApLmhhc01hdGNoKGFsbCk7CiAgZmluYWwgZGF0YUludGVncml0eSA9CiAgICAgIFJlZ0V4cChyJ2F1dG9pbmNyZW1lbnQnLCBjYXNlU2Vuc2l0aXZlOiBmYWxzZSkuaGFzTWF0Y2goYWxsKSAmJgogICAgICBSZWdFeHAocidcYmlkXGInLCBjYXNlU2Vuc2l0aXZlOiBmYWxzZSkuaGFzTWF0Y2goYWxsKSAmJgogICAgICBSZWdFeHAoCiAgICAgICAgcicocmVwb3NpdG9yeXxkYW98ZGF0YXNvdXJjZSknLAogICAgICAgIGNhc2VTZW5zaXRpdmU6IGZhbHNlLAogICAgICApLmhhc01hdGNoKGxvd2VyQWxsKTsKCiAgZmluYWwgY2hlY2tzID0gPE1hcDxTdHJpbmcsIE9iamVjdD4+WwogICAgPFN0cmluZywgT2JqZWN0PnsKICAgICAgJ3Rlc3RfaWQnOiAnQVJDSF9NVlZNJywKICAgICAgJ3Bhc3NlZCc6IG12dm0sCiAgICAgICdtZXNzYWdlJzogbXZ2bQogICAgICAgICAgPyAnQ8OzIHdpcmluZyBVSSDihpIgc3RhdGUvdmlld21vZGVsIOKGkiBzdG9yYWdlOyBVSSBraMO0bmcgaW1wb3J0IHN0b3JhZ2UgdHLhu7FjIHRp4bq/cC4nCiAgICAgICAgICA6ICdDaMawYSDEkeG7pyBldmlkZW5jZSBNVlZNOyBj4bqnbiByZXZpZXcgdGjhu6cgY8O0bmcgbuG6v3UgYsOgaSBkw7luZyB0w6puL3Thu5UgY2jhu6ljIGtow6FjLicsCiAgICB9LAogICAgPFN0cmluZywgT2JqZWN0PnsKICAgICAgJ3Rlc3RfaWQnOiAnQVJDSF9SSVZFUlBPRF9HRU5FUkFUT1InLAogICAgICAncGFzc2VkJzogcml2ZXJwb2QsCiAgICAgICdtZXNzYWdlJzogcml2ZXJwb2QKICAgICAgICAgID8gJ0PDsyBQcm92aWRlclNjb3BlLCB3aXJpbmcgcmVmL3Byb3ZpZGVyIHbDoCBldmlkZW5jZSBSaXZlcnBvZCBHZW5lcmF0b3IuJwogICAgICAgICAgOiAnQ2jGsGEgxJHhu6cgUHJvdmlkZXJTY29wZSArIHdpcmluZyByZWYvcHJvdmlkZXIgKyBldmlkZW5jZSBHZW5lcmF0b3IuJywKICAgIH0sCiAgICA8U3RyaW5nLCBPYmplY3Q+ewogICAgICAndGVzdF9pZCc6ICdBUkNIX1NRTElURScsCiAgICAgICdwYXNzZWQnOiBzcWxpdGUsCiAgICAgICdtZXNzYWdlJzogc3FsaXRlCiAgICAgICAgICA/ICdDw7MgZGVwZW5kZW5jeSBTUUxpdGUsIEFQSSBwZXJzaXN0ZW5jZSB2w6AgbG9naWMgbuG7kWkgdOG7m2kgdOG6p25nIHN0b3JhZ2UuJwogICAgICAgICAgOiAnQ2jGsGEgxJHhu6cgZGVwZW5kZW5jeSBTUUxpdGUsIEFQSSBwZXJzaXN0ZW5jZSB2w6Agd2lyaW5nIHN0b3JhZ2UuJywKICAgIH0sCiAgICA8U3RyaW5nLCBPYmplY3Q+ewogICAgICAndGVzdF9pZCc6ICdBUkNIX0ZPUk1fR0xPQkFMS0VZJywKICAgICAgJ3Bhc3NlZCc6IGZvcm1HbG9iYWxLZXksCiAgICAgICdtZXNzYWdlJzogZm9ybUdsb2JhbEtleQogICAgICAgICAgPyAnQ8OzIEZvcm0sIEdsb2JhbEtleTxGb3JtU3RhdGU+IHbDoCBs4buHbmggdmFsaWRhdGUoKS4nCiAgICAgICAgICA6ICdDaMawYSB0w6xtIHRo4bqleSDEkeG7pyBGb3JtICsgR2xvYmFsS2V5PEZvcm1TdGF0ZT4gKyB2YWxpZGF0ZSgpOyBj4bqnbiByZXZpZXcgbuG6v3UgZMO5bmcgYWJzdHJhY3Rpb24ga2jDoWMuJywKICAgIH0sCiAgICA8U3RyaW5nLCBPYmplY3Q+ewogICAgICAndGVzdF9pZCc6ICdBUkNIX0lNQUdFX1NPVVJDRScsCiAgICAgICdwYXNzZWQnOiBpbWFnZVNvdXJjZSwKICAgICAgJ21lc3NhZ2UnOiBpbWFnZVNvdXJjZQogICAgICAgICAgPyAnQ8OzIEltYWdlLmFzc2V0IGhv4bq3YyBJbWFnZS5uZXR3b3JrIHRoZW8gecOqdSBj4bqndSDEkeG7gS4nCiAgICAgICAgICA6ICdDaMawYSB0w6xtIHRo4bqleSBJbWFnZS5hc3NldC9JbWFnZS5uZXR3b3JrIHRyb25nIHNvdXJjZS4nLAogICAgfSwKICAgIDxTdHJpbmcsIE9iamVjdD57CiAgICAgICd0ZXN0X2lkJzogJ0FSQ0hfREFUQV9JTlRFR1JJVFknLAogICAgICAncGFzc2VkJzogZGF0YUludGVncml0eSwKICAgICAgJ21lc3NhZ2UnOiBkYXRhSW50ZWdyaXR5CiAgICAgICAgICA/ICdDw7MgZXZpZGVuY2UgaWQgdOG7sSB0xINuZyB2w6AgdOG6p25nIHJlcG9zaXRvcnkvREFPL2RhdGFzb3VyY2UuJwogICAgICAgICAgOiAnQ2jGsGEgdMOsbSB0aOG6pXkgxJHhu6cgZXZpZGVuY2UgaWQgdOG7sSB0xINuZyB2w6AgdOG6p25nIENSVUQ7IGPhuqduIHJldmlldyB0aOG7pyBjw7RuZy4nLAogICAgfSwKICBdOwoKICBzdGRvdXQud3JpdGVsbigKICAgIGpzb25FbmNvZGUoPFN0cmluZywgT2JqZWN0PnsKICAgICAgJ2ZpbGVzX3NjYW5uZWQnOiBmaWxlcy5sZW5ndGgsCiAgICAgICdwdWJzcGVjX3NjYW5uZWQnOiBwdWJzcGVjRmlsZS5leGlzdHNTeW5jKCksCiAgICAgICdjaGVja3MnOiBjaGVja3MsCiAgICB9KSwKICApOwp9CgpTdHJpbmcgX25vcm1hbFBhdGgoU3RyaW5nIHBhdGgpID0+IHBhdGgucmVwbGFjZUFsbCgnXFwnLCAnLycpOwoKTGlzdDxTdHJpbmc+IF9zZWdtZW50cyhTdHJpbmcgcGF0aCkgewogIHJldHVybiBwYXRoCiAgICAgIC50b0xvd2VyQ2FzZSgpCiAgICAgIC5zcGxpdChSZWdFeHAocidbLy5fLV0rJykpCiAgICAgIC53aGVyZSgocGFydCkgPT4gcGFydC5pc05vdEVtcHR5KQogICAgICAudG9MaXN0KCk7Cn0KCmJvb2wgX2lzTG9naWNQYXRoKFN0cmluZyBwYXRoKSB7CiAgZmluYWwgbG93ZXIgPSBwYXRoLnRvTG93ZXJDYXNlKCk7CiAgcmV0dXJuIDxTdHJpbmc+WwogICAgJ3ZpZXdtb2RlbCcsCiAgICAndmlld19tb2RlbCcsCiAgICAndmlld21vZGVscycsCiAgICAnY29udHJvbGxlcicsCiAgICAnY29udHJvbGxlcnMnLAogICAgJ25vdGlmaWVyJywKICAgICdub3RpZmllcnMnLAogICAgJ2Jsb2MnLAogICAgJ2Jsb2NzJywKICAgICdjdWJpdCcsCiAgICAnY3ViaXRzJywKICAgICdwcm92aWRlcicsCiAgICAncHJvdmlkZXJzJywKICAgICdzdGF0ZScsCiAgICAnc3RhdGVzJywKICAgICd1c2VjYXNlJywKICAgICd1c2VfY2FzZScsCiAgXS5hbnkobG93ZXIuY29udGFpbnMpOwp9Cgpib29sIF9pc1VpUGF0aChTdHJpbmcgcGF0aCkgewogIGlmIChfaXNMb2dpY1BhdGgocGF0aCkgfHwgX2lzU3RvcmFnZVBhdGgocGF0aCkpIHJldHVybiBmYWxzZTsKICBmaW5hbCBwYXJ0cyA9IF9zZWdtZW50cyhwYXRoKTsKICByZXR1cm4gPFN0cmluZz5bCiAgICAnc2NyZWVuJywKICAgICdzY3JlZW5zJywKICAgICd3aWRnZXQnLAogICAgJ3dpZGdldHMnLAogICAgJ3BhZ2UnLAogICAgJ3BhZ2VzJywKICAgICdwcmVzZW50YXRpb24nLAogICAgJ3VpJywKICBdLmFueShwYXJ0cy5jb250YWlucyk7Cn0KCmJvb2wgX2lzU3RvcmFnZVBhdGgoU3RyaW5nIHBhdGgpIHsKICBmaW5hbCBsb3dlciA9IHBhdGgudG9Mb3dlckNhc2UoKTsKICByZXR1cm4gPFN0cmluZz5bCiAgICAncmVwb3NpdG9yeScsCiAgICAncmVwb3NpdG9yaWVzJywKICAgICdkYXRhYmFzZScsCiAgICAnZGF0YWJhc2VzJywKICAgICdzdG9yYWdlJywKICAgICdkYXRhc291cmNlJywKICAgICdkYXRhX3NvdXJjZScsCiAgICAnZGFvJywKICAgICdzZXJ2aWNlJywKICAgICdzZXJ2aWNlcycsCiAgXS5hbnkobG93ZXIuY29udGFpbnMpOwp9Cgpib29sIF9jb250YWluc0FueShTdHJpbmcgc291cmNlLCBMaXN0PFN0cmluZz4gdG9rZW5zKSB7CiAgcmV0dXJuIHRva2Vucy5hbnkoc291cmNlLmNvbnRhaW5zKTsKfQoKYm9vbCBfaGFzU3RvcmFnZUltcG9ydChTdHJpbmcgc291cmNlKSB7CiAgcmV0dXJuIFJlZ0V4cCgKICAgIHInJydpbXBvcnRccytbJyJdW14nIl0qKHNxZmxpdGV8c3FsaXRlM3xkcmlmdHxmbG9vcnxkYXRhYmFzZXxyZXBvc2l0b3J5fHN0b3JhZ2V8ZGF0YXNvdXJjZXxkYXRhX3NvdXJjZSlbXiciXSpbJyJdJycnLAogICAgY2FzZVNlbnNpdGl2ZTogZmFsc2UsCiAgKS5oYXNNYXRjaChzb3VyY2UpOwp9Cgpib29sIF9oYXNMb2dpY0ltcG9ydChTdHJpbmcgc291cmNlKSB7CiAgcmV0dXJuIFJlZ0V4cCgKICAgIHInJydpbXBvcnRccytbJyJdW14nIl0qKHZpZXdtb2RlbHx2aWV3X21vZGVsfHByb3ZpZGVyfG5vdGlmaWVyfGNvbnRyb2xsZXJ8YmxvY3xjdWJpdHxzdGF0ZSlbXiciXSpbJyJdJycnLAogICAgY2FzZVNlbnNpdGl2ZTogZmFsc2UsCiAgKS5oYXNNYXRjaChzb3VyY2UpOwp9Cg==',
};

const _embeddedGoldens = <String, String>{
  'test/_prm393_model_granular.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9tb2RlbHMvdXNlcl9tb2RlbC5kYXJ0JzsKCmNvbnN0IF9hdmF0YXIgPSAnbGliL2Fzc2V0cy9kZWZhdWx0X2F2YXRhci5qcGcnOwoKdm9pZCBtYWluKCkgewogIHRlc3QoJ01PREVMX0dSQU5VTEFSX0ZJRUxEUycsICgpIHsKICAgIGNvbnN0IHVzZXIgPSBVc2VyTW9kZWwoCiAgICAgIGlkOiA3LAogICAgICBmdWxsTmFtZTogJ01vZGVsIFVzZXInLAogICAgICBlbWFpbDogJ21vZGVsQGV4YW1wbGUuY29tJywKICAgICAgYXZhdGFyOiBfYXZhdGFyLAogICAgKTsKICAgIGV4cGVjdCh1c2VyLmlkLCA3KTsKICAgIGV4cGVjdCh1c2VyLmZ1bGxOYW1lLCAnTW9kZWwgVXNlcicpOwogICAgZXhwZWN0KHVzZXIuZW1haWwsICdtb2RlbEBleGFtcGxlLmNvbScpOwogICAgZXhwZWN0KHVzZXIuYXZhdGFyLCBfYXZhdGFyKTsKICB9KTsKCiAgdGVzdCgnTU9ERUxfR1JBTlVMQVJfQ09QWVdJVEgnLCAoKSB7CiAgICBjb25zdCB1c2VyID0gVXNlck1vZGVsKAogICAgICBpZDogNywKICAgICAgZnVsbE5hbWU6ICdPcmlnaW5hbCcsCiAgICAgIGVtYWlsOiAnb3JpZ2luYWxAZXhhbXBsZS5jb20nLAogICAgICBhdmF0YXI6IF9hdmF0YXIsCiAgICApOwogICAgZmluYWwgdXBkYXRlZCA9IHVzZXIuY29weVdpdGgoCiAgICAgIGZ1bGxOYW1lOiAnVXBkYXRlZCcsCiAgICAgIGVtYWlsOiAndXBkYXRlZEBleGFtcGxlLmNvbScsCiAgICApOwogICAgZXhwZWN0KHVwZGF0ZWQuaWQsIDcpOwogICAgZXhwZWN0KHVwZGF0ZWQuZnVsbE5hbWUsICdVcGRhdGVkJyk7CiAgICBleHBlY3QodXBkYXRlZC5lbWFpbCwgJ3VwZGF0ZWRAZXhhbXBsZS5jb20nKTsKICAgIGV4cGVjdCh1cGRhdGVkLmF2YXRhciwgX2F2YXRhcik7CiAgfSk7CgogIHRlc3QoJ01PREVMX0dSQU5VTEFSX01BUFBJTkcnLCAoKSB7CiAgICBmaW5hbCB1c2VyID0gVXNlck1vZGVsLmZyb21NYXAoPFN0cmluZywgT2JqZWN0Pz57CiAgICAgICdpZCc6IDgsCiAgICAgICdmdWxsX25hbWUnOiAnTWFwcGVkJywKICAgICAgJ2VtYWlsJzogJ21hcHBlZEBleGFtcGxlLmNvbScsCiAgICAgICdhdmF0YXInOiBfYXZhdGFyLAogICAgfSk7CiAgICBleHBlY3QodXNlci5pZCwgOCk7CiAgICBleHBlY3QodXNlci50b01hcCgpLCA8U3RyaW5nLCBPYmplY3Q/PnsKICAgICAgJ2Z1bGxfbmFtZSc6ICdNYXBwZWQnLAogICAgICAnZW1haWwnOiAnbWFwcGVkQGV4YW1wbGUuY29tJywKICAgICAgJ2F2YXRhcic6IF9hdmF0YXIsCiAgICB9KTsKICB9KTsKfQo=',
  'test/_prm393_repository_granular.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9kYXRhYmFzZS9kYXRhYmFzZV9zZXJ2aWNlLmRhcnQnOwppbXBvcnQgJy4uL2xpYi9tb2RlbHMvdXNlcl9tb2RlbC5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvcmVwb3NpdG9yaWVzL3VzZXJfcmVwb3NpdG9yeS5kYXJ0JzsKCmNvbnN0IF9hdmF0YXIgPSAnbGliL2Fzc2V0cy9kZWZhdWx0X2F2YXRhci5qcGcnOwoKY2xhc3MgX01lbW9yeURhdGFiYXNlIGltcGxlbWVudHMgRGF0YWJhc2VTZXJ2aWNlIHsKICBmaW5hbCByb3dzID0gPE1hcDxTdHJpbmcsIE9iamVjdD8+PltdOwogIHZhciBfbmV4dElkID0gMTsKCiAgQG92ZXJyaWRlCiAgRnV0dXJlPHZvaWQ+IG9wZW4oKSBhc3luYyB7fQoKICBAb3ZlcnJpZGUKICBGdXR1cmU8TGlzdDxNYXA8U3RyaW5nLCBPYmplY3Q/Pj4+IHF1ZXJ5VXNlcnMoKSBhc3luYyA9PgogICAgICByb3dzLnJldmVyc2VkLm1hcCgocm93KSA9PiBNYXA8U3RyaW5nLCBPYmplY3Q/Pi5mcm9tKHJvdykpLnRvTGlzdCgpOwoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gaW5zZXJ0VXNlcihNYXA8U3RyaW5nLCBPYmplY3Q/PiB2YWx1ZXMpIGFzeW5jIHsKICAgIHJvd3MuYWRkKDxTdHJpbmcsIE9iamVjdD8+eydpZCc6IF9uZXh0SWQrKywgLi4udmFsdWVzfSk7CiAgfQoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gdXBkYXRlVXNlcihpbnQgaWQsIE1hcDxTdHJpbmcsIE9iamVjdD8+IHZhbHVlcykgYXN5bmMgewogICAgZmluYWwgaW5kZXggPSByb3dzLmluZGV4V2hlcmUoKHJvdykgPT4gcm93WydpZCddID09IGlkKTsKICAgIGlmIChpbmRleCA8IDApIHRocm93IFN0YXRlRXJyb3IoJ21pc3NpbmcgaWQnKTsKICAgIHJvd3NbaW5kZXhdID0gPFN0cmluZywgT2JqZWN0Pz57J2lkJzogaWQsIC4uLnZhbHVlc307CiAgfQoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gZGVsZXRlVXNlcihpbnQgaWQpIGFzeW5jIHsKICAgIHJvd3MucmVtb3ZlV2hlcmUoKHJvdykgPT4gcm93WydpZCddID09IGlkKTsKICB9Cn0KClVzZXJNb2RlbCBfdXNlcihTdHJpbmcgbmFtZSwgU3RyaW5nIGVtYWlsKSA9PgogICAgVXNlck1vZGVsKGZ1bGxOYW1lOiBuYW1lLCBlbWFpbDogZW1haWwsIGF2YXRhcjogX2F2YXRhcik7Cgp2b2lkIG1haW4oKSB7CiAgdGVzdCgnUkVQT1NJVE9SWV9HUkFOVUxBUl9BRERfQVVUT19JRCcsICgpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBTcWxpdGVVc2VyUmVwb3NpdG9yeShfTWVtb3J5RGF0YWJhc2UoKSk7CiAgICBhd2FpdCByZXBvc2l0b3J5LmFkZFVzZXIoX3VzZXIoJ09uZScsICdvbmVAZXhhbXBsZS5jb20nKSk7CiAgICBhd2FpdCByZXBvc2l0b3J5LmFkZFVzZXIoX3VzZXIoJ1R3bycsICd0d29AZXhhbXBsZS5jb20nKSk7CiAgICBmaW5hbCB1c2VycyA9IGF3YWl0IHJlcG9zaXRvcnkuZ2V0VXNlcnMoKTsKICAgIGV4cGVjdCh1c2VycywgaGFzTGVuZ3RoKDIpKTsKICAgIGV4cGVjdCh1c2Vycy5tYXAoKHVzZXIpID0+IHVzZXIuaWQpLndoZXJlVHlwZTxpbnQ+KCkudG9TZXQoKSwgaGFzTGVuZ3RoKDIpKTsKICB9KTsKCiAgdGVzdCgnUkVQT1NJVE9SWV9HUkFOVUxBUl9NQVBQSU5HJywgKCkgYXN5bmMgewogICAgZmluYWwgZGF0YWJhc2UgPSBfTWVtb3J5RGF0YWJhc2UoKQogICAgICAuLnJvd3MuYWRkKDxTdHJpbmcsIE9iamVjdD8+ewogICAgICAgICdpZCc6IDMsCiAgICAgICAgJ2Z1bGxfbmFtZSc6ICdNYXBwZWQgVXNlcicsCiAgICAgICAgJ2VtYWlsJzogJ21hcHBlZEBleGFtcGxlLmNvbScsCiAgICAgICAgJ2F2YXRhcic6IF9hdmF0YXIsCiAgICAgIH0pOwogICAgZmluYWwgdXNlcnMgPSBhd2FpdCBTcWxpdGVVc2VyUmVwb3NpdG9yeShkYXRhYmFzZSkuZ2V0VXNlcnMoKTsKICAgIGV4cGVjdCh1c2Vycy5zaW5nbGUuZnVsbE5hbWUsICdNYXBwZWQgVXNlcicpOwogICAgZXhwZWN0KHVzZXJzLnNpbmdsZS5lbWFpbCwgJ21hcHBlZEBleGFtcGxlLmNvbScpOwogIH0pOwoKICB0ZXN0KCdSRVBPU0lUT1JZX0dSQU5VTEFSX0RVUExJQ0FURV9ST1dTJywgKCkgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IFNxbGl0ZVVzZXJSZXBvc2l0b3J5KF9NZW1vcnlEYXRhYmFzZSgpKTsKICAgIGZpbmFsIHVzZXIgPSBfdXNlcignU2FtZSBVc2VyJywgJ3NhbWVAZXhhbXBsZS5jb20nKTsKICAgIGF3YWl0IHJlcG9zaXRvcnkuYWRkVXNlcih1c2VyKTsKICAgIGF3YWl0IHJlcG9zaXRvcnkuYWRkVXNlcih1c2VyKTsKICAgIGZpbmFsIHVzZXJzID0gYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpOwogICAgZXhwZWN0KHVzZXJzLCBoYXNMZW5ndGgoMikpOwogICAgZXhwZWN0KHVzZXJzLm1hcCgoaXRlbSkgPT4gaXRlbS5pZCkudG9TZXQoKSwgaGFzTGVuZ3RoKDIpKTsKICB9KTsKCiAgdGVzdCgnUkVQT1NJVE9SWV9HUkFOVUxBUl9VUERBVEUnLCAoKSBhc3luYyB7CiAgICBmaW5hbCByZXBvc2l0b3J5ID0gU3FsaXRlVXNlclJlcG9zaXRvcnkoX01lbW9yeURhdGFiYXNlKCkpOwogICAgYXdhaXQgcmVwb3NpdG9yeS5hZGRVc2VyKF91c2VyKCdCZWZvcmUnLCAnYmVmb3JlQGV4YW1wbGUuY29tJykpOwogICAgZmluYWwgb3JpZ2luYWwgPSAoYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpKS5zaW5nbGU7CiAgICBhd2FpdCByZXBvc2l0b3J5LnVwZGF0ZVVzZXIob3JpZ2luYWwuY29weVdpdGgoZW1haWw6ICdhZnRlckBleGFtcGxlLmNvbScpKTsKICAgIGV4cGVjdCgoYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpKS5zaW5nbGUuZW1haWwsICdhZnRlckBleGFtcGxlLmNvbScpOwogIH0pOwoKICB0ZXN0KCdSRVBPU0lUT1JZX0dSQU5VTEFSX0RFTEVURScsICgpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBTcWxpdGVVc2VyUmVwb3NpdG9yeShfTWVtb3J5RGF0YWJhc2UoKSk7CiAgICBhd2FpdCByZXBvc2l0b3J5LmFkZFVzZXIoX3VzZXIoJ0tlZXAnLCAna2VlcEBleGFtcGxlLmNvbScpKTsKICAgIGF3YWl0IHJlcG9zaXRvcnkuYWRkVXNlcihfdXNlcignUmVtb3ZlJywgJ3JlbW92ZUBleGFtcGxlLmNvbScpKTsKICAgIGZpbmFsIHVzZXJzID0gYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpOwogICAgYXdhaXQgcmVwb3NpdG9yeS5kZWxldGVVc2VyKHVzZXJzLmxhc3QuaWQhKTsKICAgIGZpbmFsIHJlbWFpbmluZyA9IGF3YWl0IHJlcG9zaXRvcnkuZ2V0VXNlcnMoKTsKICAgIGV4cGVjdChyZW1haW5pbmcsIGhhc0xlbmd0aCgxKSk7CiAgICBleHBlY3QocmVtYWluaW5nLnNpbmdsZS5lbWFpbCwgJ2tlZXBAZXhhbXBsZS5jb20nKTsKICB9KTsKfQo=',
  'test/_prm393_viewmodel_granular.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfcml2ZXJwb2QvZmx1dHRlcl9yaXZlcnBvZC5kYXJ0JzsKaW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9tb2RlbHMvdXNlcl9tb2RlbC5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvcmVwb3NpdG9yaWVzL3VzZXJfcmVwb3NpdG9yeS5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvdmlld21vZGVscy91c2VyX3ZpZXdfbW9kZWwuZGFydCc7Cgpjb25zdCBfYXZhdGFyID0gJ2xpYi9hc3NldHMvZGVmYXVsdF9hdmF0YXIuanBnJzsKCmNsYXNzIF9TcHlSZXBvc2l0b3J5IGltcGxlbWVudHMgVXNlclJlcG9zaXRvcnkgewogIF9TcHlSZXBvc2l0b3J5KFtMaXN0PFVzZXJNb2RlbD4gc2VlZCA9IGNvbnN0IFtdXSkKICAgIDogdXNlcnMgPSBzZWVkLm1hcCgodXNlcikgPT4gdXNlci5jb3B5V2l0aCgpKS50b0xpc3QoKTsKCiAgZmluYWwgTGlzdDxVc2VyTW9kZWw+IHVzZXJzOwogIHZhciBfbmV4dElkID0gMTsKCiAgQG92ZXJyaWRlCiAgRnV0dXJlPExpc3Q8VXNlck1vZGVsPj4gZ2V0VXNlcnMoKSBhc3luYyA9PgogICAgICB1c2Vycy5tYXAoKHVzZXIpID0+IHVzZXIuY29weVdpdGgoKSkudG9MaXN0KCk7CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiBhZGRVc2VyKFVzZXJNb2RlbCB1c2VyKSBhc3luYyB7CiAgICBmaW5hbCBpZCA9IHVzZXIuaWQgPz8gX25leHRJZCsrOwogICAgaWYgKGlkID49IF9uZXh0SWQpIF9uZXh0SWQgPSBpZCArIDE7CiAgICB1c2Vycy5hZGQodXNlci5jb3B5V2l0aChpZDogaWQpKTsKICB9CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiB1cGRhdGVVc2VyKFVzZXJNb2RlbCB1c2VyKSBhc3luYyB7CiAgICBmaW5hbCBpbmRleCA9IHVzZXJzLmluZGV4V2hlcmUoKGl0ZW0pID0+IGl0ZW0uaWQgPT0gdXNlci5pZCk7CiAgICBpZiAoaW5kZXggPCAwKSB0aHJvdyBTdGF0ZUVycm9yKCdtaXNzaW5nIGlkJyk7CiAgICB1c2Vyc1tpbmRleF0gPSB1c2VyLmNvcHlXaXRoKCk7CiAgfQoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gZGVsZXRlVXNlcihpbnQgaWQpIGFzeW5jIHsKICAgIHVzZXJzLnJlbW92ZVdoZXJlKCh1c2VyKSA9PiB1c2VyLmlkID09IGlkKTsKICB9Cn0KClVzZXJNb2RlbCBfdXNlcihTdHJpbmcgbmFtZSwgU3RyaW5nIGVtYWlsLCB7aW50PyBpZH0pID0+CiAgICBVc2VyTW9kZWwoaWQ6IGlkLCBmdWxsTmFtZTogbmFtZSwgZW1haWw6IGVtYWlsLCBhdmF0YXI6IF9hdmF0YXIpOwoKUHJvdmlkZXJDb250YWluZXIgX2NvbnRhaW5lcihfU3B5UmVwb3NpdG9yeSByZXBvc2l0b3J5KSA9PiBQcm92aWRlckNvbnRhaW5lcigKICBvdmVycmlkZXM6IFt1c2VyUmVwb3NpdG9yeVByb3ZpZGVyLm92ZXJyaWRlV2l0aFZhbHVlKHJlcG9zaXRvcnkpXSwKKTsKCnZvaWQgbWFpbigpIHsKICB0ZXN0KCdWSUVXTU9ERUxfR1JBTlVMQVJfTE9BRF9TVEFURScsICgpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU3B5UmVwb3NpdG9yeShbCiAgICAgIF91c2VyKCdTZWVkJywgJ3NlZWRAZXhhbXBsZS5jb20nLCBpZDogMTApLAogICAgXSk7CiAgICBmaW5hbCBjb250YWluZXIgPSBfY29udGFpbmVyKHJlcG9zaXRvcnkpOwogICAgYWRkVGVhckRvd24oY29udGFpbmVyLmRpc3Bvc2UpOwogICAgZmluYWwgbm90aWZpZXIgPSBjb250YWluZXIucmVhZCh1c2VyVmlld01vZGVsUHJvdmlkZXIubm90aWZpZXIpOwogICAgYXdhaXQgbm90aWZpZXIubG9hZFVzZXJzKCk7CiAgICBleHBlY3QoY29udGFpbmVyLnJlYWQodXNlclZpZXdNb2RlbFByb3ZpZGVyKS5pdGVtcy5zaW5nbGUuZnVsbE5hbWUsICdTZWVkJyk7CiAgICBleHBlY3QoY29udGFpbmVyLnJlYWQodXNlclZpZXdNb2RlbFByb3ZpZGVyKS5pc0xvYWRpbmcsIGlzRmFsc2UpOwogIH0pOwoKICB0ZXN0KCdWSUVXTU9ERUxfR1JBTlVMQVJfQUREX0FVVE9fSUQnLCAoKSBhc3luYyB7CiAgICBmaW5hbCByZXBvc2l0b3J5ID0gX1NweVJlcG9zaXRvcnkoKTsKICAgIGZpbmFsIGNvbnRhaW5lciA9IF9jb250YWluZXIocmVwb3NpdG9yeSk7CiAgICBhZGRUZWFyRG93bihjb250YWluZXIuZGlzcG9zZSk7CiAgICBmaW5hbCBub3RpZmllciA9IGNvbnRhaW5lci5yZWFkKHVzZXJWaWV3TW9kZWxQcm92aWRlci5ub3RpZmllcik7CiAgICBhd2FpdCBub3RpZmllci5hZGRVc2VyKF91c2VyKCdBZGRlZCcsICdhZGRlZEBleGFtcGxlLmNvbScpKTsKICAgIGV4cGVjdChyZXBvc2l0b3J5LnVzZXJzLnNpbmdsZS5pZCwgaXNOb3ROdWxsKTsKICAgIGV4cGVjdChjb250YWluZXIucmVhZCh1c2VyVmlld01vZGVsUHJvdmlkZXIpLml0ZW1zLCBoYXNMZW5ndGgoMSkpOwogIH0pOwoKICB0ZXN0KCdWSUVXTU9ERUxfR1JBTlVMQVJfVVBEQVRFX1NUQVRFJywgKCkgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TcHlSZXBvc2l0b3J5KFsKICAgICAgX3VzZXIoJ0JlZm9yZScsICdiZWZvcmVAZXhhbXBsZS5jb20nLCBpZDogMSksCiAgICBdKTsKICAgIGZpbmFsIGNvbnRhaW5lciA9IF9jb250YWluZXIocmVwb3NpdG9yeSk7CiAgICBhZGRUZWFyRG93bihjb250YWluZXIuZGlzcG9zZSk7CiAgICBmaW5hbCBub3RpZmllciA9IGNvbnRhaW5lci5yZWFkKHVzZXJWaWV3TW9kZWxQcm92aWRlci5ub3RpZmllcik7CiAgICBhd2FpdCBub3RpZmllci5sb2FkVXNlcnMoKTsKICAgIGF3YWl0IG5vdGlmaWVyLnVwZGF0ZVVzZXIoX3VzZXIoJ0FmdGVyJywgJ2FmdGVyQGV4YW1wbGUuY29tJywgaWQ6IDEpKTsKICAgIGV4cGVjdCgKICAgICAgY29udGFpbmVyLnJlYWQodXNlclZpZXdNb2RlbFByb3ZpZGVyKS5pdGVtcy5zaW5nbGUuZnVsbE5hbWUsCiAgICAgICdBZnRlcicsCiAgICApOwogIH0pOwoKICB0ZXN0KCdWSUVXTU9ERUxfR1JBTlVMQVJfREVMRVRFX1NUQVRFJywgKCkgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TcHlSZXBvc2l0b3J5KFsKICAgICAgX3VzZXIoJ0tlZXAnLCAna2VlcEBleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgICAgX3VzZXIoJ1JlbW92ZScsICdyZW1vdmVAZXhhbXBsZS5jb20nLCBpZDogMiksCiAgICBdKTsKICAgIGZpbmFsIGNvbnRhaW5lciA9IF9jb250YWluZXIocmVwb3NpdG9yeSk7CiAgICBhZGRUZWFyRG93bihjb250YWluZXIuZGlzcG9zZSk7CiAgICBmaW5hbCBub3RpZmllciA9IGNvbnRhaW5lci5yZWFkKHVzZXJWaWV3TW9kZWxQcm92aWRlci5ub3RpZmllcik7CiAgICBhd2FpdCBub3RpZmllci5sb2FkVXNlcnMoKTsKICAgIGF3YWl0IG5vdGlmaWVyLmRlbGV0ZVVzZXIoMik7CiAgICBleHBlY3QoY29udGFpbmVyLnJlYWQodXNlclZpZXdNb2RlbFByb3ZpZGVyKS5pdGVtcywgaGFzTGVuZ3RoKDEpKTsKICAgIGV4cGVjdChjb250YWluZXIucmVhZCh1c2VyVmlld01vZGVsUHJvdmlkZXIpLml0ZW1zLnNpbmdsZS5mdWxsTmFtZSwgJ0tlZXAnKTsKICB9KTsKfQo=',
  'test/_prm393_screen_granular.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXIvbWF0ZXJpYWwuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3JpdmVycG9kL2ZsdXR0ZXJfcml2ZXJwb2QuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3Rlc3QvZmx1dHRlcl90ZXN0LmRhcnQnOwoKaW1wb3J0ICcuLi9saWIvbW9kZWxzL3VzZXJfbW9kZWwuZGFydCc7CmltcG9ydCAnLi4vbGliL3JlcG9zaXRvcmllcy91c2VyX3JlcG9zaXRvcnkuZGFydCc7CmltcG9ydCAnLi4vbGliL3NjcmVlbnMvdXNlcl9saXN0X3NjcmVlbi5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvdmlld21vZGVscy91c2VyX3ZpZXdfbW9kZWwuZGFydCc7Cgpjb25zdCBfYXZhdGFyID0gJ2xpYi9hc3NldHMvZGVmYXVsdF9hdmF0YXIuanBnJzsKCmNsYXNzIF9TY3JlZW5SZXBvc2l0b3J5IGltcGxlbWVudHMgVXNlclJlcG9zaXRvcnkgewogIF9TY3JlZW5SZXBvc2l0b3J5KExpc3Q8VXNlck1vZGVsPiBzZWVkKQogICAgOiB1c2VycyA9IHNlZWQubWFwKCh1c2VyKSA9PiB1c2VyLmNvcHlXaXRoKCkpLnRvTGlzdCgpOwoKICBmaW5hbCBMaXN0PFVzZXJNb2RlbD4gdXNlcnM7CgogIEBvdmVycmlkZQogIEZ1dHVyZTxMaXN0PFVzZXJNb2RlbD4+IGdldFVzZXJzKCkgYXN5bmMgPT4KICAgICAgdXNlcnMubWFwKCh1c2VyKSA9PiB1c2VyLmNvcHlXaXRoKCkpLnRvTGlzdCgpOwoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gYWRkVXNlcihVc2VyTW9kZWwgdXNlcikgYXN5bmMgPT4gdXNlcnMuYWRkKHVzZXIuY29weVdpdGgoaWQ6IDEpKTsKCiAgQG92ZXJyaWRlCiAgRnV0dXJlPHZvaWQ+IHVwZGF0ZVVzZXIoVXNlck1vZGVsIHVzZXIpIGFzeW5jIHt9CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiBkZWxldGVVc2VyKGludCBpZCkgYXN5bmMge30KfQoKRnV0dXJlPHZvaWQ+IF9wdW1wKFdpZGdldFRlc3RlciB0ZXN0ZXIsIF9TY3JlZW5SZXBvc2l0b3J5IHJlcG9zaXRvcnkpIGFzeW5jIHsKICBhd2FpdCB0ZXN0ZXIucHVtcFdpZGdldCgKICAgIFByb3ZpZGVyU2NvcGUoCiAgICAgIG92ZXJyaWRlczogW3VzZXJSZXBvc2l0b3J5UHJvdmlkZXIub3ZlcnJpZGVXaXRoVmFsdWUocmVwb3NpdG9yeSldLAogICAgICBjaGlsZDogY29uc3QgTWF0ZXJpYWxBcHAoaG9tZTogVXNlckxpc3RTY3JlZW4oKSksCiAgICApLAogICk7CiAgYXdhaXQgdGVzdGVyLnB1bXAoKTsKICBhd2FpdCB0ZXN0ZXIucHVtcChjb25zdCBEdXJhdGlvbihtaWxsaXNlY29uZHM6IDEwMCkpOwp9CgpGaW5kZXIgX3VzZXJUZXh0KFN0cmluZyB2YWx1ZSkgPT4KICAgIGZpbmQuYnlXaWRnZXRQcmVkaWNhdGUoKHdpZGdldCkgPT4gd2lkZ2V0IGlzIFRleHQgJiYgd2lkZ2V0LmRhdGEgPT0gdmFsdWUpOwoKVXNlck1vZGVsIF91c2VyKFN0cmluZyBuYW1lLCBTdHJpbmcgZW1haWwsIHtpbnQ/IGlkfSkgPT4KICAgIFVzZXJNb2RlbChpZDogaWQsIGZ1bGxOYW1lOiBuYW1lLCBlbWFpbDogZW1haWwsIGF2YXRhcjogX2F2YXRhcik7Cgp2b2lkIG1haW4oKSB7CiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9MSVNUX1NJTkdMRV9VU0VSJywgKHRlc3RlcikgYXN5bmMgewogICAgYXdhaXQgX3B1bXAoCiAgICAgIHRlc3RlciwKICAgICAgX1NjcmVlblJlcG9zaXRvcnkoW191c2VyKCdPbmUgVXNlcicsICdvbmVAZXhhbXBsZS5jb20nLCBpZDogMSldKSwKICAgICk7CiAgICBleHBlY3QoX3VzZXJUZXh0KCdPbmUgVXNlcicpLCBmaW5kc09uZVdpZGdldCk7CiAgICBleHBlY3QoX3VzZXJUZXh0KCdvbmVAZXhhbXBsZS5jb20nKSwgZmluZHNPbmVXaWRnZXQpOwogIH0pOwoKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX0dSQU5VTEFSX0xJU1RfTVVMVElQTEVfVVNFUlMnLCAodGVzdGVyKSBhc3luYyB7CiAgICBhd2FpdCBfcHVtcCgKICAgICAgdGVzdGVyLAogICAgICBfU2NyZWVuUmVwb3NpdG9yeShbCiAgICAgICAgX3VzZXIoJ0ZpcnN0IFVzZXInLCAnZmlyc3RAZXhhbXBsZS5jb20nLCBpZDogMSksCiAgICAgICAgX3VzZXIoJ1NlY29uZCBVc2VyJywgJ3NlY29uZEBleGFtcGxlLmNvbScsIGlkOiAyKSwKICAgICAgXSksCiAgICApOwogICAgZXhwZWN0KF91c2VyVGV4dCgnRmlyc3QgVXNlcicpLCBmaW5kc09uZVdpZGdldCk7CiAgICBleHBlY3QoX3VzZXJUZXh0KCdTZWNvbmQgVXNlcicpLCBmaW5kc09uZVdpZGdldCk7CiAgfSk7Cn0K',
  'goldens/prm393_home_portrait.png':
      'iVBORw0KGgoAAAANSUhEUgAAAZAAAAMgCAYAAAAN6jSQAAAAAXNSR0IArs4c6QAAAARzQklUCAgICHwIZIgAAB/vSURBVHic7d17dF51mejxJ1CaQvICjilpubeWVlK1pKUUFaFQCir3q4cZxzPjUac6czzrdLyMZ9bMuI5nLW8Ia3lmRjx4H9cwAyJyc8BSpBS5FMqtNNgCLQJi06YD9E2At1Ry/nBcS6cUyJP9Zu8kn8/f7N9+3iTk29+bZP9aGo3BwQCAIdqt7AEAGJ0EBIAUAQEgRUAASBEQAFIEBIAUAQEgRUAASBEQAFIEBIAUAQEgRUAASBEQAFIEBIAUAQEgZULZA1TNj67+UdkjjGlnnnFmU9b1eWMkNOvrd7RqcaDU75s2fVrZI4xpGzdsbMq6Pm+MhGZ9/Y5W3sICIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAlAllD1A1F198cdkjkODzBiOvpdEYHCx7CABGH29hAZAiIACkCAgAKQICQIqAAJAiIACkCAgAKQICQIqAAJAiIACkCAgAKQICQIqAAJAiIACkCAgAKQICQIqAAJAiIACkCAgAKQICQIqAAJAiIACkCAgAKQICQIqAAJAiIACkCAgAKQICQIqAAJAiIACkCAgAKQICQIqAAJAiIACkCAgAKRNG8mbr1v08rrnmR/GTn9wYTz31ZPT2boqBgYGRHAFgzGhra4spU6bGgQceFCeeuDjOP/+/xKGHThux+49IQNat+3l84hP/M+6884449dTT4mMf++8xc+asmDp1arS310ZiBIAxp7+/Hk8//XSsX78urrnmqpg/vzve+c5j4sILL44ZMw5r+v1bGo3BwWbe4G//9q/j29/+ZnziE5+OD37ww9Ha2trM2wGMW41GI775zf8XX/nKl+JDH/pI/N3f/e+m3q9pAdmyZXOce+6Zse++fxDf+tb3olaz0wAYCfV6PT784T+Jbduei3/5lyujo6OjKfdpyg/RG41GnHnmqXHCCSfG5Zf/UDwARlCtVovLLvtBvP3tx8SZZ54SjUajKfdpyg7kggvOi4kTJ8all36n6KUBGIKPfvTD8dJL2+N73/vnwtcufAfywx/+ILZu3Rpf+9o3il4agCH66lf/MbZs2RLXXXdN4WsXHpDPfvZv4qMf/YuYMGFEf0MYgFewxx57xEc+8tH43Oc+G4MFv+FUaEDuvPOOeOaZZ+K97z21yGUBGIb3vvfU2LRpU9x77+pC1y00INdff22cdtoZ0dLSUuSyAAxDS0tLnHLKafHjH19X6LqFBmT16nvi3HPPL3JJAApw6qmnx+rV9xS6ZqEBeeqpJ+Ntb5tT5JIAFOCooxbEk08+UeiahQbkiSd+Ea2tk4pcEoACtLZOiqeeerLQNQv9O5DW1pao17cXtRwABarVJkajUdxvYnmcOwApAgJAioAAkCIgAKQICAAplXtg1bXXFv/Ar9HotNNOb8q6Pr6/4eM7OjXr80ZO5X6Nd9abZxY1zqi27ufrm7Kuj+9v+PiOTs36vI0Xfo0XgEoQEABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIGVC2QP8Zxd++cKyRxjTfHyby8eX8aSl0RgcLGqx1taWqNe3F7UcAAWq1SZGo1HYt3xvYQGQIyAApAgIACkCAkCKgACQIiAApAgIACkCAkCKgACQIiAApAgIACkCAkCKgACQIiAApAgIACkCAkCKgACQIiAApFTuTPSIiHq9Hvfed3+s7emJLX19ZY8DUIrJHR0xu6sr5nYfEbVarexxdlK5M9Hr9XpcceUPY/r0aTG7qysmd3QUNR7AqLKlry/W9vTEhg0b47xzzh52RIo+E71yAVlx68oYjMFYeOyxRY0FMKoV9X2x6IBU7mcga3t6YnZXV9ljAFRGV9fh0dPzcNlj7KRyAdnS1+dtK4DfMbmjo5I/D65cQAAYHQQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASClkueBZJ117nmFrHPVD64oZB2AscwOBIAUAQEgZUy9hfW7hvo2VFFvfwGMF2M2IFXxxS9fGHfedVf6+o//xZ/HV//+H9LXH71gQURE6TN8+pOfSF8fEfHoo4/GJ//qM8OaISrwcRjuDF/+wudjxowZ6euhSN7CAiBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIGbOPMvFsK4DmsgMBIEVAAEgZU29hOUkQYOTYgQCQMqZ2IFU03HMwIiKOX7iwkFlG8wwzZsyoxA6z7I8DVIkdCAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkFK5gEzu6IgtfX1ljwFQGVv6+mJyR0fZY+ykcgGZ3dUVa3t6yh4DoDJ6eh6Orq7Dyx5jJ5ULyNzuI2LDho2x4taVdiLAuLalry9W3LoyHtuwIeZ1d5c9zk5aGo3BwaIWa21tiXp9+7DXqdfrsfq++6Kn52ERAcatyR0d0dV1eMzr7o5arTbs9Wq1idFoFPYtv5oBAaB4RQekcm9hATA6CAgAKQICQIqAAJAiIACkCAgAKQICQIqAAJAiIACkCAgAKQICQMqEsgd4JfV6Pe697/5Y29PjYYrAuDW5oyNmd3XF3O4jCnmYYtEq9zDFer0eV1z5w5g+fVrM7uqq5CEqACNhS19frO3piQ0bNsZ555w97IiM+afxrrh1ZQzGYCw89tiixgIY1Yr6vjjmn8a7tqcnZnd1lT0GQGV0dR0ePT0Plz3GTioXkKqe/QtQlskdHZX8eXDlAgLA6CAgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAAplTwPJOusc88rZJ2rfnBFIesAjGV2IACkCAgAKWPqLazfNdS3oYp6+wtgvBizAamKL375wrjzrrvS13/8L/48vvr3/5C+/ugFCyIiRv0MX/7C52PGjBnp64HieQsLgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBlzD7KxLOtAJrLDgSAFAEBIGVMvYXlJEGAkWMHAkDKmNqBVNGnP/mJYa9x/MKFhcwy2mcAqsUOBIAUAQEgRUAASBEQAFIEBIAUAQEgRUAASBEQAFIEBIAUAQEgRUAASBEQAFIEBIAUAQEgRUAASKlcQCZ3dMSWvr6yxwCojC19fTG5o6PsMXZSuYDM7uqKtT09ZY8BUBk9PQ9HV9fhZY+xk8oFZG73EbFhw8ZYcetKOxFgXNvS1xcrbl0Zj23YEPO6u8seZyctjcbgYFGLtba2RL2+fdjr1Ov1WH3ffdHT87CIAOPW5I6O6Oo6POZ1d0etVhv2erXaxGg0CvuWX82AAFC8ogNSubewABgdBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIGVC2QMU5dlnn4t1j6yPzZu3RF9fX/QP9Ed//0Bs3+58EhiOAw84ILbV69He3h7tbW2x/9SpMWPGm+KA/fcvezRKNuoPlLrjzrvi/gceiO0vbY9Zh82M6dOnxaRJk6K9rT3a29ti4sSJIzoPjDWDg4NRr9ejv78/+gcGom/r1ujpeTiefe65mPO2t8b8eUfGvvvuU/aYvA5OJPwPd65aFctuWh7vXrw4Djn0kNhv8uQRuS/wGwMDA7Hx8cdj+U9/GjPe9KZYvGiRf7BV3LgPSO/mzXHNtdfFwQcdFItPXBS77ebHOFC2e1bfG8uWL4+TF58Yc7u7yx6HXRjXAXlwzUNx+x13xAfe/0ex1157Ne0+QM6y5ctj+/btccp73lP2KLyCcXsm+qq7747HNjwWSz7yYfGAilq8aFF07tcZ3/rOd8sehREwKgKy6u67Y/OWvjjrjDPKHgV4DUfOmxtHLzgqLv/BD8oehSarfEAeXPNQ/PLpp+PU99oSw2jRdfjh0XX44XHlVT8qexSaqNIB6d28OW6/4w47DxiF3jJ7duy77z5x289uL3sUmqTSAbn62uviA+//o7LHAJIWHX98PPHkk/GrX20qexSaoLIBuXPVqjjkoIP8wBxGuaMXHBU33Xxz2WPQBJV9lMmym5bHX//Vp9PXf/HSG+PO+zcM6Zqjj5gen/7wyel7AjubPm1a3Hb77bFx4+MxbdqhZY9DgSq5A7njrrvi3Sed5I8EYYw4+cQT4/4HHyh7DApWyR3I/fc/EOecfVbZY7yqs/78a6nrjj5i+pB3RhERb515QKxZ/8sRu+6MRXPi6uVD/x/+7UdMjzsSr+9D5x0Tpyx865Cve/QXm+OTX7pyyNe9ZeYB8VDi45L9/A3HSH/NfPlT58SMQ/Yb8nWvprOzMzY+/ouo1+tRq9UKXZvyVC4gzz77XGx/aXthz7Z6Pd+Yrr9lTXzjitsKuR/wymbNPCzWrX8kjpw3t+xRKEjl3iNa98j6mHXYzLLHAAo2a+bMWLd+fdljUKDKBWTz5s0xffq0sscACnboIYdEf39/2WNQoMoFpK9va0yaNKnsMYCCTZgwIf79mWcc8jaGVC4g/QP90d7WXvYYQBPU2tujbhcyZlQvIP0D0d7eVvYYQBO019qjvy4gY0XlAgLA6FC5gLS3t0V//0DZYwBN0F/vj/aat6jHiuoFpK09+gdscWEsqvf3R61dQMaKyv0h4eSOjmi82ChsvW9ccZs/EoQK2LFjR7zxD/4gJk6cWPYoFKRyO5D99pscj24Y2UdFAM238fHHo93uY0yp3A5k1syZ8e3v/VO85+SThrWOp+pCtaxb/0jMmnlY2WNQoMrtQPbZZ59obW2NzZs3lz0KUKD1jzwSMw8TkLGkcgGJiOie87Z4/BdPlD0GUJBNmzbFtEMP9STeMaal0RgcLGqx1taWqNeLeUzB//n8F+Izn/pk7L777oWsB5Tnu9//fhx3zLvi0EMPKXuUca1WmxiNRmHf8qu5A4mIWHziolh20/KyxwCG6bENG2L33XYXjzGosgFZMH9+PPX0L+P5558vexRgGO5adXeceMLxZY9BE1Q2IBERZ5x2Wnzne/9U9hhA0k3Lb45DDjk4pkyZUvYoNEGlAzK5oyOOfde74sqrrip7FGCIHlyzJur9/fHOt7+97FFokkoHJCLiLbO74pCDD45rr/9x2aMAr9Oahx6KRx59NM464/SyR6GJKh+QiIgj582LKZ2ddiIwCqy6+55Yfe99cc5ZZ5U9Ck02KgISETH/yHkxa+as+MdLvh4DA57WC1V04003xdZ/3xp/8oE/LnsURkBl/w5kV7b09cXV11wbBxywfyxetCgmTKjc01hg3Fl19z2xbPnyeO+73x3dR8wpexx2oei/Axl1Afmt337BnnjCCb/5LY/OzhG5L/Ab9Xo9Nm58PJbfcku8edZM/6AbBQTkP7lr1aq4/4EH4/kXXohZMw+L6YdOiz332jNq7e3R3t7u0dEwTIODg7FtWz36++tR7++PrVv/Pdb29ET/wEDMeetbYv6RR8bee+9d9pi8DgKyC9u2bYt16x+JzZs3x5a+vqj390d/f39s317OPDBWHHjggVGvb4v29lrU2ttj6v5T47A3vSmm+tuOUUdAAEgZN8/CAqDaBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIGVC2QNAVTW274i1jzwdD2/YFJu2PBe9W7dFb189tvW/UPZojBJ77TkxOt+4d3S+sRadHXvHYYd2xpxZB0Z7W2vZoxWipdEYHCxqsdbWlqjXtxe1HIy4FxsvxQ0r18Y9a34Rax99uuxxGKOmHzQ55s0+ON5z7FviDfvsNWL3rdUmRqNR2Ld8AYH4j3D8260PxY9uuj+29b9Y9jiMExP3mBDvftfsOPPEI0YkJAICBRIOquC3ITnn5Lmxd/ukpt2n6ID4ITrj2k23Pxzfv+Yu8aBU21/aEdfc/ED883WrYseOl8se53WzA2Fc2rHj5fi/3785br37kbJHgd/z5ulT4n8teU/U2orfidiBQAG++6M7xINK+vmGTfH5r98Qv/519XciAsK4c+PKtXHdTx8sewzYpYcf+1VcevnKssd4TQLCuLJuY29cesVtZY8Br+nG23rixpVryx7jVQkI48bLLw/Gxd+5aVS8NQAREd+68vZ45rnnyx5jlwSEceOWVeuit29b2WPA67b9pR3xw2X3lT3GLnmUCePCSy/9Oi7/t9XDWuNLnz4rursOGvJ1i//rV4d1X8a3n9zWE2cv7h7Rv1h/vexAGBduuG2t3Qej0vaXdsS//vjussd4RXYgjAvrN/aWPcJr+v5X/jQ6O2pDvm7ghe3RtufEIV/X21dP3e++nifjU1+8asjXfej8d8b7Tpk35Ouyr2+kPy7RxN3mQ49U87lsAsKYt2PHy3Hv2icKW6+3rx7v/8tvv+p/k/1mCa/kl73Pxi97n40DOvcte5Tf4y0sxrw165+K51/0hARGt7se2Fj2CDsREMa8jU9tLXsEGLaNT/WVPcJOBIQx75lt1f09eni9qvh1LCCMec9W8H88GKoqfh0LCGNeFf/lBkNVxa9jAQEgRUAY896wd/X+gheGqopfxwLCmLdvBf/Hg6Gq4texPyRkzCv6X26dHbVY9t2PF7omvBY7ECjBtAPfWPYIMGzTDuwoe4Sd2IEw5r115oGx154T4/kXhvfX6JnnP0FRFsyZVvYIO7EDYcybMGG3mNt1cNljQNoBnftW7jlYISCMF1X81xu8XlX9+m1pNAYHi1qstbUl6nUPraN6Xn55MD722X+O3q3OBGF02WOP3ePSz/1x7FPbc9hr1WoTo9Eo7Fu+HQjjw267tcT7z1hQ9hgwZKcd/7ZC4tEMAsK4ccy8GXHI/n4ji9Fjr0kT45yT55Y9xi4JCOPKxz9wfLRO9MuHjA4f/cPjYq9JQz9VcaQICOPK9IMmx8f+cGHZY8BrOvuk7jhm3oyyx3hVAsK4c+z8w+Lsk7rLHgN2ae7sg+P9p1f/Z3YCwrj0/tMXxOwZ+5c9Buyk4w3t8Zd/ujhaWlrKHuU1+TVexrXrb1kT37ryZ/Hyy8X9aiNkLX7H4fGR9x0bEyY059/2Rf8ar4Aw7j3w86fiS5feGM+/6GuXcuy2W0ssueC4WPyOw5t6HwGBJni2/kJcf8ua+PGKNcN+Zha8XrvvvlssPGpmnLW4e0QeVSIg0ETPv7A9rrtlTVz30wejPvBi2eMwRk2auEecdExXnL7obfHGfdtH7L4CAiNkU9+2uG31o/Hgz5+K5/pfiIHnGzHw/PZ4cftLZY/GKLHHHrtH256t0bbnxKi1TYo3T58S7+h+Uxx26H6lzCMgAKR4FhYAlSAgAKQICAApAgJAioAAkCIgAKQICAAphQakra0tGo1GkUsCUIBt256L9vZi/+q90IDst19n9Pb2FrkkAAXo7e2NKVOmFrpmoQGZMmVKrFnzQJFLAlCA9evXxdSpFQ7I/PkLYuXKFUUuCUABli27MebPL/aUw0IDcu6558eyZTcWuSQABVi27MY477z3FbpmoQFZsODoqNVqcdNNPylyWQCG4frrr40DDjgg5s6dV+i6hT6NNyLiZz+7LZYs+VDcfvs90draWuTSAAxRo9GIOXPeHFdeeU10d88tdO3CAxIRsXTp/4jnnnsuvva1S4teGoAhWLLkv8XkyfvFF794YeFrNyUgv/71r+P444+JRYsWx2c+8zdFLw/A6/D5z38uli9fFj/96W2x++67F75+UwISEbFly+Y45ZST48ADD4pLL/1O1Gq1ZtwGgP+kXq/HBz/4gdi06em4/vqfREdHR1Pu07RHmUyevF/cfPPKaDQacdRRc+KGG65v1q0A+A833HB9HHXUnBgcfDluvnll0+IRzdyB/Nbg4GBce+3VcfHFF0ZLy25x7LEL48gj58e0adNj//33j7a2kTtQHmAsGRjoj1/96lexYcNjsXr13bFixS0xOPhyLF36yTj11NOjpaWlqfdvekB+1/r16+Lqq6+KZct+Ek8++UT09m6KgYGBkbo9wJjS1tYWU6ZMjYMOOjgWLz4pzjzz7Jgx47ARu/+IBgSAscPj3AFIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUlrmHHHUYNlDADD62IEAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAyoRm3+Dyf708Ojs7h3xdb29v6rrV966OpUuXDvm6iIgVt6xIXXfcwuOGdP1vZ7zoooti3tx5Q77fpt5NMaVzypCv6+3tjfPfd/6Qr4sSPo9eY/H3G861Zfz/SPXZgQCQ0vQdyHjzav8CXvJnS+KCCy4Y8ZkAmsEOBIAUAQEgRUAASBEQAFIEBIAUAQEgxa/xFqyzszP9B4kAo4kdCAApdiAF+u0jTQDGAzsQAFIEBIAUAQEgpWXOEUcNlj0EAKOPHQgAKQICQIqAAJAiIACkjLkz0cfDOdNeY/H3G861zn3ftTJeIyPHDgSAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIMWZ6ACk2IEAkCIgAKQICAApAgJAijPRh3m/4VzrNe6as7Sh+uxAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSnIkOQIodCAApAgJAioAAkCIgAKQ4E32Y9xvOtV7jrnmNr36/7LnvI/3/4+p7V8fSpUuHfF1ExIpbVqSuGw+fx6qwAwEgRUAASBEQAFIEBIAUAQEgRUAASBEQAFIEBIAUAQEgRUAASBEQAFIEBIAUAQEgRUAASHEmOgApdiAApAgIACkCAkCKgACQ4kz0Yd5vONd6jbs2Hs4LL+PzeNzC41LXXXTRRTFv7rwhXzcevlaHc+77aGcHAkCKgACQIiAApAgIACkCAkCKgACQIiAApAgIACkCAkCKgACQIiAApAgIACkCAkCKgACQ4kx0AFLsQABIERAAUgQEgBQBASDFmejDvN9wrh1Nr9FZ2rt22WWXxSVfv2TI1y35syVxwQUXDPm68fK1mj3bnpFjBwJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkOJMdABS7EAASBEQAFIEBIAUAQEgxZnow7zfcK71GnfNayz+fsO5djS9xuMWHpe6jqGzAwEgRUAASBEQAFIEBIAUAQEgRUAASBEQAFIEBIAUAQEgRUAASBEQAFIEBIAUAQEgRUAASHEmOgApdiAApAgIACkCAkCKgACQ4kz037nf+e87f8jXxSh7jSN9zvRFF10U8+bOG/J1o+m88Msuuywu+folqXvCaGYHAkCKgACQIiAApAgIACkCAkCKgACQIiAApAgIACkCAkCKgACQIiAApAgIACkCAkCKgACQ4kx0AFLsQABIERAAUgQEgBQBASDFmejDvN9wrvUad81rLP5+UcLZ9qvvXR1Lly5N3ZPqswMBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEhxJjoAKXYgAKQICAApAgJAioAAkOJM9GHebzjXeo275jUWf7/hXDuaXmP23HeGzg4EgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASDFmegApNiBAJAiIACkCAgAKQICQIoz0Yd5v+Fcm71u9b2rY+nSpUO+LiJixS0rUtc5S3vXLrroopg3d96QrxsPX6vDeY3nv+/8IV/HyLIDASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIERAAUgQEgBQBASBFQABIcSY6ACl2IACkCAgAKQICQIqAAJBS2TPRL7vssrjk65cM+bolf7YkLrjggiFfN17OmfYad208nImefY3wSuxAAEgREABSBASAFAEBIEVAAEgREABSBASAFAEBIEVAAEgREABSmv4ok6wTTjgh9UiSTb2bmjIPAL/PDgSAFAEBIEVAAEhxJjoAKXYgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAioAAkCIgAKQICAApAgJAyv8Hx/6Uj1cE2SkAAAAASUVORK5CYII=',
  'goldens/prm393_home_landscape.png':
      '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',
};

const _embeddedOverrides = <String, String>{
  'test/_prm393_screen_granular.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXIvbWF0ZXJpYWwuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3JpdmVycG9kL2ZsdXR0ZXJfcml2ZXJwb2QuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3Rlc3QvZmx1dHRlcl90ZXN0LmRhcnQnOwoKaW1wb3J0ICcuLi9saWIvbW9kZWxzL3VzZXJfbW9kZWwuZGFydCc7CmltcG9ydCAnLi4vbGliL3JlcG9zaXRvcmllcy91c2VyX3JlcG9zaXRvcnkuZGFydCc7CmltcG9ydCAnLi4vbGliL3NjcmVlbnMvdXNlcl9saXN0X3NjcmVlbi5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvdmlld21vZGVscy91c2VyX3ZpZXdfbW9kZWwuZGFydCc7Cgpjb25zdCBfYXZhdGFyID0gJ2xpYi9hc3NldHMvZGVmYXVsdF9hdmF0YXIuanBnJzsKCmNsYXNzIF9TY3JlZW5SZXBvc2l0b3J5IGltcGxlbWVudHMgVXNlclJlcG9zaXRvcnkgewogIF9TY3JlZW5SZXBvc2l0b3J5KExpc3Q8VXNlck1vZGVsPiBzZWVkKQogICAgOiB1c2VycyA9IHNlZWQubWFwKCh1c2VyKSA9PiB1c2VyLmNvcHlXaXRoKCkpLnRvTGlzdCgpOwoKICBmaW5hbCBMaXN0PFVzZXJNb2RlbD4gdXNlcnM7CiAgdmFyIF9uZXh0SWQgPSAxOwoKICBAb3ZlcnJpZGUKICBGdXR1cmU8TGlzdDxVc2VyTW9kZWw+PiBnZXRVc2VycygpIGFzeW5jID0+CiAgICAgIHVzZXJzLm1hcCgodXNlcikgPT4gdXNlci5jb3B5V2l0aCgpKS50b0xpc3QoKTsKCiAgQG92ZXJyaWRlCiAgRnV0dXJlPHZvaWQ+IGFkZFVzZXIoVXNlck1vZGVsIHVzZXIpIGFzeW5jIHsKICAgIGZpbmFsIGlkID0gdXNlci5pZCA/PyBfbmV4dElkKys7CiAgICBpZiAoaWQgPj0gX25leHRJZCkgX25leHRJZCA9IGlkICsgMTsKICAgIHVzZXJzLmFkZCh1c2VyLmNvcHlXaXRoKGlkOiBpZCkpOwogIH0KCiAgQG92ZXJyaWRlCiAgRnV0dXJlPHZvaWQ+IHVwZGF0ZVVzZXIoVXNlck1vZGVsIHVzZXIpIGFzeW5jIHsKICAgIGZpbmFsIGluZGV4ID0gdXNlcnMuaW5kZXhXaGVyZSgoaXRlbSkgPT4gaXRlbS5pZCA9PSB1c2VyLmlkKTsKICAgIGlmIChpbmRleCA8IDApIHRocm93IFN0YXRlRXJyb3IoJ21pc3NpbmcgaWQnKTsKICAgIHVzZXJzW2luZGV4XSA9IHVzZXIuY29weVdpdGgoKTsKICB9CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiBkZWxldGVVc2VyKGludCBpZCkgYXN5bmMgewogICAgdXNlcnMucmVtb3ZlV2hlcmUoKHVzZXIpID0+IHVzZXIuaWQgPT0gaWQpOwogIH0KfQoKRnV0dXJlPHZvaWQ+IF9wdW1wKFdpZGdldFRlc3RlciB0ZXN0ZXIsIF9TY3JlZW5SZXBvc2l0b3J5IHJlcG9zaXRvcnkpIGFzeW5jIHsKICBhd2FpdCB0ZXN0ZXIucHVtcFdpZGdldCgKICAgIFByb3ZpZGVyU2NvcGUoCiAgICAgIG92ZXJyaWRlczogW3VzZXJSZXBvc2l0b3J5UHJvdmlkZXIub3ZlcnJpZGVXaXRoVmFsdWUocmVwb3NpdG9yeSldLAogICAgICBjaGlsZDogY29uc3QgTWF0ZXJpYWxBcHAoaG9tZTogVXNlckxpc3RTY3JlZW4oKSksCiAgICApLAogICk7CiAgYXdhaXQgdGVzdGVyLnB1bXAoKTsKICBhd2FpdCB0ZXN0ZXIucHVtcChjb25zdCBEdXJhdGlvbihtaWxsaXNlY29uZHM6IDEwMCkpOwp9CgpGaW5kZXIgX3VzZXJUZXh0KFN0cmluZyB2YWx1ZSkgPT4KICAgIGZpbmQuYnlXaWRnZXRQcmVkaWNhdGUoKHdpZGdldCkgPT4gd2lkZ2V0IGlzIFRleHQgJiYgd2lkZ2V0LmRhdGEgPT0gdmFsdWUpOwoKRmluZGVyIF9maWVsZChpbnQgaW5kZXgpID0+IGZpbmQuYnlUeXBlKFRleHRGb3JtRmllbGQpLmF0KGluZGV4KTsKCkZ1dHVyZTx2b2lkPiBfY2hvb3NlQXZhdGFyKFdpZGdldFRlc3RlciB0ZXN0ZXIpIGFzeW5jIHsKICBhd2FpdCB0ZXN0ZXIudGFwKGZpbmQudGV4dCgnQ2hvb3NlIEF2YXRhcicpKTsKICBhd2FpdCB0ZXN0ZXIucHVtcEFuZFNldHRsZSgpOwogIGF3YWl0IHRlc3Rlci50YXAoZmluZC50ZXh0KCdEZWZhdWx0IEF2YXRhcicpKTsKICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwp9CgpVc2VyTW9kZWwgX3VzZXIoU3RyaW5nIG5hbWUsIFN0cmluZyBlbWFpbCwge2ludD8gaWR9KSA9PgogICAgVXNlck1vZGVsKGlkOiBpZCwgZnVsbE5hbWU6IG5hbWUsIGVtYWlsOiBlbWFpbCwgYXZhdGFyOiBfYXZhdGFyKTsKCnZvaWQgbWFpbigpIHsKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX0dSQU5VTEFSX0xJU1RfU0lOR0xFX1VTRVInLCAodGVzdGVyKSBhc3luYyB7CiAgICBhd2FpdCBfcHVtcCgKICAgICAgdGVzdGVyLAogICAgICBfU2NyZWVuUmVwb3NpdG9yeShbX3VzZXIoJ09uZSBVc2VyJywgJ29uZUBleGFtcGxlLmNvbScsIGlkOiAxKV0pLAogICAgKTsKICAgIGV4cGVjdChfdXNlclRleHQoJ09uZSBVc2VyJyksIGZpbmRzT25lV2lkZ2V0KTsKICAgIGV4cGVjdChfdXNlclRleHQoJ29uZUBleGFtcGxlLmNvbScpLCBmaW5kc09uZVdpZGdldCk7CiAgfSk7CgogIHRlc3RXaWRnZXRzKCdTQ1JFRU5fR1JBTlVMQVJfTElTVF9NVUxUSVBMRV9VU0VSUycsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGF3YWl0IF9wdW1wKAogICAgICB0ZXN0ZXIsCiAgICAgIF9TY3JlZW5SZXBvc2l0b3J5KFsKICAgICAgICBfdXNlcignRmlyc3QgVXNlcicsICdmaXJzdEBleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgICAgICBfdXNlcignU2Vjb25kIFVzZXInLCAnc2Vjb25kQGV4YW1wbGUuY29tJywgaWQ6IDIpLAogICAgICBdKSwKICAgICk7CiAgICBleHBlY3QoX3VzZXJUZXh0KCdGaXJzdCBVc2VyJyksIGZpbmRzT25lV2lkZ2V0KTsKICAgIGV4cGVjdChfdXNlclRleHQoJ1NlY29uZCBVc2VyJyksIGZpbmRzT25lV2lkZ2V0KTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9BRERfUkVQT1NJVE9SWScsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU2NyZWVuUmVwb3NpdG9yeShbXSk7CiAgICBhd2FpdCBfcHVtcCh0ZXN0ZXIsIHJlcG9zaXRvcnkpOwogICAgYXdhaXQgdGVzdGVyLmVudGVyVGV4dChfZmllbGQoMCksICdBZGRlZCBVc2VyJyk7CiAgICBhd2FpdCB0ZXN0ZXIuZW50ZXJUZXh0KF9maWVsZCgxKSwgJ2FkZGVkQGV4YW1wbGUuY29tJyk7CiAgICBhd2FpdCBfY2hvb3NlQXZhdGFyKHRlc3Rlcik7CiAgICBhd2FpdCB0ZXN0ZXIudGFwKGZpbmQudGV4dCgnQWRkIFVzZXInKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgZXhwZWN0KHJlcG9zaXRvcnkudXNlcnMuc2luZ2xlLmZ1bGxOYW1lLCAnQWRkZWQgVXNlcicpOwogIH0pOwoKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX0dSQU5VTEFSX0FERF9MSVNUX1NUQVRFJywgKHRlc3RlcikgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TY3JlZW5SZXBvc2l0b3J5KFtdKTsKICAgIGF3YWl0IF9wdW1wKHRlc3RlciwgcmVwb3NpdG9yeSk7CiAgICBhd2FpdCB0ZXN0ZXIuZW50ZXJUZXh0KF9maWVsZCgwKSwgJ1JlbmRlcmVkIFVzZXInKTsKICAgIGF3YWl0IHRlc3Rlci5lbnRlclRleHQoX2ZpZWxkKDEpLCAncmVuZGVyZWRAZXhhbXBsZS5jb20nKTsKICAgIGF3YWl0IF9jaG9vc2VBdmF0YXIodGVzdGVyKTsKICAgIGF3YWl0IHRlc3Rlci50YXAoZmluZC50ZXh0KCdBZGQgVXNlcicpKTsKICAgIGF3YWl0IHRlc3Rlci5wdW1wKCk7CiAgICBleHBlY3QoX3VzZXJUZXh0KCdSZW5kZXJlZCBVc2VyJyksIGZpbmRzT25lV2lkZ2V0KTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9VUERBVEVfTE9BRCcsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU2NyZWVuUmVwb3NpdG9yeShbCiAgICAgIF91c2VyKCdPcmlnaW5hbCBVc2VyJywgJ29yaWdpbmFsQGV4YW1wbGUuY29tJywgaWQ6IDEpLAogICAgXSk7CiAgICBhd2FpdCBfcHVtcCh0ZXN0ZXIsIHJlcG9zaXRvcnkpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLmJ5U2VtYW50aWNzTGFiZWwoJ0VkaXQnKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgZXhwZWN0KAogICAgICB0ZXN0ZXIud2lkZ2V0PFRleHRGb3JtRmllbGQ+KF9maWVsZCgwKSkuY29udHJvbGxlciEudGV4dCwKICAgICAgJ09yaWdpbmFsIFVzZXInLAogICAgKTsKICAgIGV4cGVjdCgKICAgICAgdGVzdGVyLndpZGdldDxUZXh0Rm9ybUZpZWxkPihfZmllbGQoMSkpLmNvbnRyb2xsZXIhLnRleHQsCiAgICAgICdvcmlnaW5hbEBleGFtcGxlLmNvbScsCiAgICApOwogIH0pOwoKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX0dSQU5VTEFSX1VQREFURV9SRVBPU0lUT1JZJywgKHRlc3RlcikgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TY3JlZW5SZXBvc2l0b3J5KFsKICAgICAgX3VzZXIoJ0JlZm9yZSBVc2VyJywgJ2JlZm9yZUBleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgIF0pOwogICAgYXdhaXQgX3B1bXAodGVzdGVyLCByZXBvc2l0b3J5KTsKICAgIGF3YWl0IHRlc3Rlci50YXAoZmluZC5ieVNlbWFudGljc0xhYmVsKCdFZGl0JykpOwogICAgYXdhaXQgdGVzdGVyLnB1bXAoKTsKICAgIGF3YWl0IHRlc3Rlci5lbnRlclRleHQoX2ZpZWxkKDApLCAnQWZ0ZXIgVXNlcicpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLnRleHQoJ1VwZGF0ZSBVc2VyJykpOwogICAgYXdhaXQgdGVzdGVyLnB1bXAoKTsKICAgIGV4cGVjdChyZXBvc2l0b3J5LnVzZXJzLnNpbmdsZS5mdWxsTmFtZSwgJ0FmdGVyIFVzZXInKTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9ERUxFVEVfRElBTE9HJywgKHRlc3RlcikgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TY3JlZW5SZXBvc2l0b3J5KFsKICAgICAgX3VzZXIoJ0RlbGV0ZSBVc2VyJywgJ2RlbGV0ZUBleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgIF0pOwogICAgYXdhaXQgX3B1bXAodGVzdGVyLCByZXBvc2l0b3J5KTsKICAgIGF3YWl0IHRlc3Rlci50YXAoZmluZC5ieVNlbWFudGljc0xhYmVsKCdEZWxldGUnKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgZXhwZWN0KGZpbmQudGV4dCgnQ29uZmlybSBEZWxldGUnKSwgZmluZHNPbmVXaWRnZXQpOwogIH0pOwoKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX0dSQU5VTEFSX0RFTEVURV9SRVBPU0lUT1JZJywgKHRlc3RlcikgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TY3JlZW5SZXBvc2l0b3J5KFsKICAgICAgX3VzZXIoJ0RlbGV0ZSBVc2VyJywgJ2RlbGV0ZUBleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgIF0pOwogICAgYXdhaXQgX3B1bXAodGVzdGVyLCByZXBvc2l0b3J5KTsKICAgIGF3YWl0IHRlc3Rlci50YXAoZmluZC5ieVNlbWFudGljc0xhYmVsKCdEZWxldGUnKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLnRleHQoJ0NvbmZpcm0gRGVsZXRlJykpOwogICAgYXdhaXQgdGVzdGVyLnB1bXAoKTsKICAgIGV4cGVjdChyZXBvc2l0b3J5LnVzZXJzLCBpc0VtcHR5KTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9ERVRBSUxfREFUQScsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU2NyZWVuUmVwb3NpdG9yeShbCiAgICAgIF91c2VyKCdEZXRhaWwgVXNlcicsICdkZXRhaWxAZXhhbXBsZS5jb20nLCBpZDogMSksCiAgICBdKTsKICAgIGF3YWl0IF9wdW1wKHRlc3RlciwgcmVwb3NpdG9yeSk7CiAgICBhd2FpdCB0ZXN0ZXIudGFwKF91c2VyVGV4dCgnRGV0YWlsIFVzZXInKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcEFuZFNldHRsZSgpOwogICAgZXhwZWN0KF91c2VyVGV4dCgnRGV0YWlsIFVzZXInKSwgZmluZHNPbmVXaWRnZXQpOwogICAgZXhwZWN0KF91c2VyVGV4dCgnZGV0YWlsQGV4YW1wbGUuY29tJyksIGZpbmRzT25lV2lkZ2V0KTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9ERVRBSUxfQkFDSycsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU2NyZWVuUmVwb3NpdG9yeShbCiAgICAgIF91c2VyKCdCYWNrIFVzZXInLCAnYmFja0BleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgIF0pOwogICAgYXdhaXQgX3B1bXAodGVzdGVyLCByZXBvc2l0b3J5KTsKICAgIGF3YWl0IHRlc3Rlci50YXAoX3VzZXJUZXh0KCdCYWNrIFVzZXInKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcEFuZFNldHRsZSgpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLnRleHQoJ0JhY2snKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgZXhwZWN0KF91c2VyVGV4dCgnQmFjayBVc2VyJyksIGZpbmRzT25lV2lkZ2V0KTsKICB9KTsKfQo=',
  'test/_prm393_repository_granular.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXJfdGVzdC9mbHV0dGVyX3Rlc3QuZGFydCc7CgppbXBvcnQgJy4uL2xpYi9kYXRhYmFzZS9kYXRhYmFzZV9zZXJ2aWNlLmRhcnQnOwppbXBvcnQgJy4uL2xpYi9tb2RlbHMvdXNlcl9tb2RlbC5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvcmVwb3NpdG9yaWVzL3VzZXJfcmVwb3NpdG9yeS5kYXJ0JzsKCmNvbnN0IF9hdmF0YXIgPSAnbGliL2Fzc2V0cy9kZWZhdWx0X2F2YXRhci5qcGcnOwoKY2xhc3MgX01lbW9yeURhdGFiYXNlIGltcGxlbWVudHMgRGF0YWJhc2VTZXJ2aWNlIHsKICBmaW5hbCByb3dzID0gPE1hcDxTdHJpbmcsIE9iamVjdD8+PltdOwogIHZhciBfbmV4dElkID0gMTsKCiAgQG92ZXJyaWRlCiAgRnV0dXJlPHZvaWQ+IG9wZW4oKSBhc3luYyB7fQoKICBAb3ZlcnJpZGUKICBGdXR1cmU8TGlzdDxNYXA8U3RyaW5nLCBPYmplY3Q/Pj4+IHF1ZXJ5VXNlcnMoKSBhc3luYyA9PgogICAgICByb3dzLnJldmVyc2VkLm1hcCgocm93KSA9PiBNYXA8U3RyaW5nLCBPYmplY3Q/Pi5mcm9tKHJvdykpLnRvTGlzdCgpOwoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gaW5zZXJ0VXNlcihNYXA8U3RyaW5nLCBPYmplY3Q/PiB2YWx1ZXMpIGFzeW5jIHsKICAgIHJvd3MuYWRkKDxTdHJpbmcsIE9iamVjdD8+eydpZCc6IF9uZXh0SWQrKywgLi4udmFsdWVzfSk7CiAgfQoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gdXBkYXRlVXNlcihpbnQgaWQsIE1hcDxTdHJpbmcsIE9iamVjdD8+IHZhbHVlcykgYXN5bmMgewogICAgZmluYWwgaW5kZXggPSByb3dzLmluZGV4V2hlcmUoKHJvdykgPT4gcm93WydpZCddID09IGlkKTsKICAgIGlmIChpbmRleCA8IDApIHRocm93IFN0YXRlRXJyb3IoJ21pc3NpbmcgaWQnKTsKICAgIHJvd3NbaW5kZXhdID0gPFN0cmluZywgT2JqZWN0Pz57J2lkJzogaWQsIC4uLnZhbHVlc307CiAgfQoKICBAb3ZlcnJpZGUKICBGdXR1cmU8dm9pZD4gZGVsZXRlVXNlcihpbnQgaWQpIGFzeW5jIHsKICAgIHJvd3MucmVtb3ZlV2hlcmUoKHJvdykgPT4gcm93WydpZCddID09IGlkKTsKICB9Cn0KClVzZXJNb2RlbCBfdXNlcihTdHJpbmcgbmFtZSwgU3RyaW5nIGVtYWlsKSA9PgogICAgVXNlck1vZGVsKGZ1bGxOYW1lOiBuYW1lLCBlbWFpbDogZW1haWwsIGF2YXRhcjogX2F2YXRhcik7Cgp2b2lkIG1haW4oKSB7CiAgdGVzdCgnUkVQT1NJVE9SWV9HUkFOVUxBUl9BRERfQVVUT19JRCcsICgpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBTcWxpdGVVc2VyUmVwb3NpdG9yeShfTWVtb3J5RGF0YWJhc2UoKSk7CiAgICBhd2FpdCByZXBvc2l0b3J5LmFkZFVzZXIoX3VzZXIoJ09uZScsICdvbmVAZXhhbXBsZS5jb20nKSk7CiAgICBhd2FpdCByZXBvc2l0b3J5LmFkZFVzZXIoX3VzZXIoJ1R3bycsICd0d29AZXhhbXBsZS5jb20nKSk7CiAgICBmaW5hbCB1c2VycyA9IGF3YWl0IHJlcG9zaXRvcnkuZ2V0VXNlcnMoKTsKICAgIGV4cGVjdCh1c2VycywgaGFzTGVuZ3RoKDIpKTsKICAgIGV4cGVjdCh1c2Vycy5tYXAoKHVzZXIpID0+IHVzZXIuaWQpLndoZXJlVHlwZTxpbnQ+KCkudG9TZXQoKSwgaGFzTGVuZ3RoKDIpKTsKICB9KTsKCiAgdGVzdCgnUkVQT1NJVE9SWV9HUkFOVUxBUl9NQVBQSU5HJywgKCkgYXN5bmMgewogICAgZmluYWwgZGF0YWJhc2UgPSBfTWVtb3J5RGF0YWJhc2UoKQogICAgICAuLnJvd3MuYWRkKDxTdHJpbmcsIE9iamVjdD8+ewogICAgICAgICdpZCc6IDMsCiAgICAgICAgJ2Z1bGxfbmFtZSc6ICdNYXBwZWQgVXNlcicsCiAgICAgICAgJ2VtYWlsJzogJ21hcHBlZEBleGFtcGxlLmNvbScsCiAgICAgICAgJ2F2YXRhcic6IF9hdmF0YXIsCiAgICAgIH0pOwogICAgZmluYWwgdXNlcnMgPSBhd2FpdCBTcWxpdGVVc2VyUmVwb3NpdG9yeShkYXRhYmFzZSkuZ2V0VXNlcnMoKTsKICAgIGV4cGVjdCh1c2Vycy5zaW5nbGUuZnVsbE5hbWUsICdNYXBwZWQgVXNlcicpOwogICAgZXhwZWN0KHVzZXJzLnNpbmdsZS5lbWFpbCwgJ21hcHBlZEBleGFtcGxlLmNvbScpOwogIH0pOwoKICB0ZXN0KCdSRVBPU0lUT1JZX0dSQU5VTEFSX0RVUExJQ0FURV9ST1dTJywgKCkgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IFNxbGl0ZVVzZXJSZXBvc2l0b3J5KF9NZW1vcnlEYXRhYmFzZSgpKTsKICAgIGZpbmFsIHVzZXIgPSBfdXNlcignU2FtZSBVc2VyJywgJ3NhbWVAZXhhbXBsZS5jb20nKTsKICAgIGF3YWl0IHJlcG9zaXRvcnkuYWRkVXNlcih1c2VyKTsKICAgIGF3YWl0IHJlcG9zaXRvcnkuYWRkVXNlcih1c2VyKTsKICAgIGZpbmFsIHVzZXJzID0gYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpOwogICAgZXhwZWN0KHVzZXJzLCBoYXNMZW5ndGgoMikpOwogICAgZXhwZWN0KHVzZXJzLm1hcCgoaXRlbSkgPT4gaXRlbS5pZCkudG9TZXQoKSwgaGFzTGVuZ3RoKDIpKTsKICB9KTsKCiAgdGVzdCgnUkVQT1NJVE9SWV9HUkFOVUxBUl9VUERBVEUnLCAoKSBhc3luYyB7CiAgICBmaW5hbCByZXBvc2l0b3J5ID0gU3FsaXRlVXNlclJlcG9zaXRvcnkoX01lbW9yeURhdGFiYXNlKCkpOwogICAgYXdhaXQgcmVwb3NpdG9yeS5hZGRVc2VyKF91c2VyKCdCZWZvcmUnLCAnYmVmb3JlQGV4YW1wbGUuY29tJykpOwogICAgZmluYWwgb3JpZ2luYWwgPSAoYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpKS5zaW5nbGU7CiAgICBhd2FpdCByZXBvc2l0b3J5LnVwZGF0ZVVzZXIob3JpZ2luYWwuY29weVdpdGgoZW1haWw6ICdhZnRlckBleGFtcGxlLmNvbScpKTsKICAgIGV4cGVjdCgoYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpKS5zaW5nbGUuZW1haWwsICdhZnRlckBleGFtcGxlLmNvbScpOwogIH0pOwoKICB0ZXN0KCdSRVBPU0lUT1JZX0dSQU5VTEFSX0RFTEVURScsICgpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBTcWxpdGVVc2VyUmVwb3NpdG9yeShfTWVtb3J5RGF0YWJhc2UoKSk7CiAgICBhd2FpdCByZXBvc2l0b3J5LmFkZFVzZXIoX3VzZXIoJ0tlZXAnLCAna2VlcEBleGFtcGxlLmNvbScpKTsKICAgIGF3YWl0IHJlcG9zaXRvcnkuYWRkVXNlcihfdXNlcignUmVtb3ZlJywgJ3JlbW92ZUBleGFtcGxlLmNvbScpKTsKICAgIGZpbmFsIHVzZXJzID0gYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpOwogICAgZmluYWwgcmVtb3ZlZCA9IHVzZXJzLnNpbmdsZVdoZXJlKAogICAgICAodXNlcikgPT4gdXNlci5lbWFpbCA9PSAncmVtb3ZlQGV4YW1wbGUuY29tJywKICAgICk7CiAgICBhd2FpdCByZXBvc2l0b3J5LmRlbGV0ZVVzZXIocmVtb3ZlZC5pZCEpOwogICAgZmluYWwgcmVtYWluaW5nID0gYXdhaXQgcmVwb3NpdG9yeS5nZXRVc2VycygpOwogICAgZXhwZWN0KHJlbWFpbmluZywgaGFzTGVuZ3RoKDEpKTsKICAgIGV4cGVjdChyZW1haW5pbmcuc2luZ2xlLmVtYWlsLCAna2VlcEBleGFtcGxlLmNvbScpOwogIH0pOwp9Cg==',
};

const _embeddedFinalOverrides = <String, String>{
  'test/_prm393_screen_granular.dart':
      'aW1wb3J0ICdwYWNrYWdlOmZsdXR0ZXIvbWF0ZXJpYWwuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3JpdmVycG9kL2ZsdXR0ZXJfcml2ZXJwb2QuZGFydCc7CmltcG9ydCAncGFja2FnZTpmbHV0dGVyX3Rlc3QvZmx1dHRlcl90ZXN0LmRhcnQnOwoKaW1wb3J0ICcuLi9saWIvbW9kZWxzL3VzZXJfbW9kZWwuZGFydCc7CmltcG9ydCAnLi4vbGliL3JlcG9zaXRvcmllcy91c2VyX3JlcG9zaXRvcnkuZGFydCc7CmltcG9ydCAnLi4vbGliL3NjcmVlbnMvdXNlcl9saXN0X3NjcmVlbi5kYXJ0JzsKaW1wb3J0ICcuLi9saWIvdmlld21vZGVscy91c2VyX3ZpZXdfbW9kZWwuZGFydCc7Cgpjb25zdCBfYXZhdGFyID0gJ2xpYi9hc3NldHMvZGVmYXVsdF9hdmF0YXIuanBnJzsKCmNsYXNzIF9TY3JlZW5SZXBvc2l0b3J5IGltcGxlbWVudHMgVXNlclJlcG9zaXRvcnkgewogIF9TY3JlZW5SZXBvc2l0b3J5KExpc3Q8VXNlck1vZGVsPiBzZWVkKQogICAgOiB1c2VycyA9IHNlZWQubWFwKCh1c2VyKSA9PiB1c2VyLmNvcHlXaXRoKCkpLnRvTGlzdCgpOwoKICBmaW5hbCBMaXN0PFVzZXJNb2RlbD4gdXNlcnM7CiAgdmFyIF9uZXh0SWQgPSAxOwoKICBAb3ZlcnJpZGUKICBGdXR1cmU8TGlzdDxVc2VyTW9kZWw+PiBnZXRVc2VycygpIGFzeW5jID0+CiAgICAgIHVzZXJzLm1hcCgodXNlcikgPT4gdXNlci5jb3B5V2l0aCgpKS50b0xpc3QoKTsKCiAgQG92ZXJyaWRlCiAgRnV0dXJlPHZvaWQ+IGFkZFVzZXIoVXNlck1vZGVsIHVzZXIpIGFzeW5jIHsKICAgIGZpbmFsIGlkID0gdXNlci5pZCA/PyBfbmV4dElkKys7CiAgICBpZiAoaWQgPj0gX25leHRJZCkgX25leHRJZCA9IGlkICsgMTsKICAgIHVzZXJzLmFkZCh1c2VyLmNvcHlXaXRoKGlkOiBpZCkpOwogIH0KCiAgQG92ZXJyaWRlCiAgRnV0dXJlPHZvaWQ+IHVwZGF0ZVVzZXIoVXNlck1vZGVsIHVzZXIpIGFzeW5jIHsKICAgIGZpbmFsIGluZGV4ID0gdXNlcnMuaW5kZXhXaGVyZSgoaXRlbSkgPT4gaXRlbS5pZCA9PSB1c2VyLmlkKTsKICAgIGlmIChpbmRleCA8IDApIHRocm93IFN0YXRlRXJyb3IoJ21pc3NpbmcgaWQnKTsKICAgIHVzZXJzW2luZGV4XSA9IHVzZXIuY29weVdpdGgoKTsKICB9CgogIEBvdmVycmlkZQogIEZ1dHVyZTx2b2lkPiBkZWxldGVVc2VyKGludCBpZCkgYXN5bmMgewogICAgdXNlcnMucmVtb3ZlV2hlcmUoKHVzZXIpID0+IHVzZXIuaWQgPT0gaWQpOwogIH0KfQoKRnV0dXJlPHZvaWQ+IF9wdW1wKFdpZGdldFRlc3RlciB0ZXN0ZXIsIF9TY3JlZW5SZXBvc2l0b3J5IHJlcG9zaXRvcnkpIGFzeW5jIHsKICBhd2FpdCB0ZXN0ZXIucHVtcFdpZGdldCgKICAgIFByb3ZpZGVyU2NvcGUoCiAgICAgIG92ZXJyaWRlczogW3VzZXJSZXBvc2l0b3J5UHJvdmlkZXIub3ZlcnJpZGVXaXRoVmFsdWUocmVwb3NpdG9yeSldLAogICAgICBjaGlsZDogY29uc3QgTWF0ZXJpYWxBcHAoaG9tZTogVXNlckxpc3RTY3JlZW4oKSksCiAgICApLAogICk7CiAgYXdhaXQgdGVzdGVyLnB1bXAoKTsKICBhd2FpdCB0ZXN0ZXIucHVtcChjb25zdCBEdXJhdGlvbihtaWxsaXNlY29uZHM6IDEwMCkpOwp9CgpGaW5kZXIgX3VzZXJUZXh0KFN0cmluZyB2YWx1ZSkgPT4KICAgIGZpbmQuYnlXaWRnZXRQcmVkaWNhdGUoKHdpZGdldCkgPT4gd2lkZ2V0IGlzIFRleHQgJiYgd2lkZ2V0LmRhdGEgPT0gdmFsdWUpOwoKRmluZGVyIF9maWVsZChpbnQgaW5kZXgpID0+IGZpbmQuYnlUeXBlKFRleHRGb3JtRmllbGQpLmF0KGluZGV4KTsKCkZ1dHVyZTx2b2lkPiBfY2hvb3NlQXZhdGFyKFdpZGdldFRlc3RlciB0ZXN0ZXIpIGFzeW5jIHsKICBhd2FpdCB0ZXN0ZXIudGFwKGZpbmQudGV4dCgnQ2hvb3NlIEF2YXRhcicpKTsKICBhd2FpdCB0ZXN0ZXIucHVtcEFuZFNldHRsZSgpOwogIGF3YWl0IHRlc3Rlci50YXAoZmluZC50ZXh0KCdEZWZhdWx0IEF2YXRhcicpKTsKICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwp9CgpVc2VyTW9kZWwgX3VzZXIoU3RyaW5nIG5hbWUsIFN0cmluZyBlbWFpbCwge2ludD8gaWR9KSA9PgogICAgVXNlck1vZGVsKGlkOiBpZCwgZnVsbE5hbWU6IG5hbWUsIGVtYWlsOiBlbWFpbCwgYXZhdGFyOiBfYXZhdGFyKTsKCnZvaWQgbWFpbigpIHsKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX0dSQU5VTEFSX0xJU1RfU0lOR0xFX1VTRVInLCAodGVzdGVyKSBhc3luYyB7CiAgICBhd2FpdCBfcHVtcCgKICAgICAgdGVzdGVyLAogICAgICBfU2NyZWVuUmVwb3NpdG9yeShbX3VzZXIoJ09uZSBVc2VyJywgJ29uZUBleGFtcGxlLmNvbScsIGlkOiAxKV0pLAogICAgKTsKICAgIGV4cGVjdChfdXNlclRleHQoJ09uZSBVc2VyJyksIGZpbmRzT25lV2lkZ2V0KTsKICAgIGV4cGVjdChfdXNlclRleHQoJ29uZUBleGFtcGxlLmNvbScpLCBmaW5kc09uZVdpZGdldCk7CiAgfSk7CgogIHRlc3RXaWRnZXRzKCdTQ1JFRU5fR1JBTlVMQVJfTElTVF9NVUxUSVBMRV9VU0VSUycsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGF3YWl0IF9wdW1wKAogICAgICB0ZXN0ZXIsCiAgICAgIF9TY3JlZW5SZXBvc2l0b3J5KFsKICAgICAgICBfdXNlcignRmlyc3QgVXNlcicsICdmaXJzdEBleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgICAgICBfdXNlcignU2Vjb25kIFVzZXInLCAnc2Vjb25kQGV4YW1wbGUuY29tJywgaWQ6IDIpLAogICAgICBdKSwKICAgICk7CiAgICBleHBlY3QoX3VzZXJUZXh0KCdGaXJzdCBVc2VyJyksIGZpbmRzT25lV2lkZ2V0KTsKICAgIGV4cGVjdChfdXNlclRleHQoJ1NlY29uZCBVc2VyJyksIGZpbmRzT25lV2lkZ2V0KTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9BRERfUkVQT1NJVE9SWScsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU2NyZWVuUmVwb3NpdG9yeShbXSk7CiAgICBhd2FpdCBfcHVtcCh0ZXN0ZXIsIHJlcG9zaXRvcnkpOwogICAgYXdhaXQgdGVzdGVyLmVudGVyVGV4dChfZmllbGQoMCksICdBZGRlZCBVc2VyJyk7CiAgICBhd2FpdCB0ZXN0ZXIuZW50ZXJUZXh0KF9maWVsZCgxKSwgJ2FkZGVkQGV4YW1wbGUuY29tJyk7CiAgICBhd2FpdCBfY2hvb3NlQXZhdGFyKHRlc3Rlcik7CiAgICBhd2FpdCB0ZXN0ZXIudGFwKGZpbmQudGV4dCgnQWRkIFVzZXInKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgZXhwZWN0KHJlcG9zaXRvcnkudXNlcnMuc2luZ2xlLmZ1bGxOYW1lLCAnQWRkZWQgVXNlcicpOwogIH0pOwoKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX0dSQU5VTEFSX0FERF9MSVNUX1NUQVRFJywgKHRlc3RlcikgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TY3JlZW5SZXBvc2l0b3J5KFtdKTsKICAgIGF3YWl0IF9wdW1wKHRlc3RlciwgcmVwb3NpdG9yeSk7CiAgICBhd2FpdCB0ZXN0ZXIuZW50ZXJUZXh0KF9maWVsZCgwKSwgJ1JlbmRlcmVkIFVzZXInKTsKICAgIGF3YWl0IHRlc3Rlci5lbnRlclRleHQoX2ZpZWxkKDEpLCAncmVuZGVyZWRAZXhhbXBsZS5jb20nKTsKICAgIGF3YWl0IF9jaG9vc2VBdmF0YXIodGVzdGVyKTsKICAgIGF3YWl0IHRlc3Rlci50YXAoZmluZC50ZXh0KCdBZGQgVXNlcicpKTsKICAgIGF3YWl0IHRlc3Rlci5wdW1wKCk7CiAgICBleHBlY3QoX3VzZXJUZXh0KCdSZW5kZXJlZCBVc2VyJyksIGZpbmRzT25lV2lkZ2V0KTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9VUERBVEVfTE9BRCcsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU2NyZWVuUmVwb3NpdG9yeShbCiAgICAgIF91c2VyKCdPcmlnaW5hbCBVc2VyJywgJ29yaWdpbmFsQGV4YW1wbGUuY29tJywgaWQ6IDEpLAogICAgXSk7CiAgICBhd2FpdCBfcHVtcCh0ZXN0ZXIsIHJlcG9zaXRvcnkpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLmJ5U2VtYW50aWNzTGFiZWwoJ0VkaXQnKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgZXhwZWN0KAogICAgICB0ZXN0ZXIud2lkZ2V0PFRleHRGb3JtRmllbGQ+KF9maWVsZCgwKSkuY29udHJvbGxlciEudGV4dCwKICAgICAgJ09yaWdpbmFsIFVzZXInLAogICAgKTsKICAgIGV4cGVjdCgKICAgICAgdGVzdGVyLndpZGdldDxUZXh0Rm9ybUZpZWxkPihfZmllbGQoMSkpLmNvbnRyb2xsZXIhLnRleHQsCiAgICAgICdvcmlnaW5hbEBleGFtcGxlLmNvbScsCiAgICApOwogIH0pOwoKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX0dSQU5VTEFSX1VQREFURV9SRVBPU0lUT1JZJywgKHRlc3RlcikgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TY3JlZW5SZXBvc2l0b3J5KFsKICAgICAgX3VzZXIoJ0JlZm9yZSBVc2VyJywgJ2JlZm9yZUBleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgIF0pOwogICAgYXdhaXQgX3B1bXAodGVzdGVyLCByZXBvc2l0b3J5KTsKICAgIGF3YWl0IHRlc3Rlci50YXAoZmluZC5ieVNlbWFudGljc0xhYmVsKCdFZGl0JykpOwogICAgYXdhaXQgdGVzdGVyLnB1bXAoKTsKICAgIGF3YWl0IHRlc3Rlci5lbnRlclRleHQoX2ZpZWxkKDApLCAnQWZ0ZXIgVXNlcicpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLnRleHQoJ1VwZGF0ZSBVc2VyJykpOwogICAgYXdhaXQgdGVzdGVyLnB1bXAoKTsKICAgIGV4cGVjdChyZXBvc2l0b3J5LnVzZXJzLnNpbmdsZS5mdWxsTmFtZSwgJ0FmdGVyIFVzZXInKTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9ERUxFVEVfRElBTE9HJywgKHRlc3RlcikgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TY3JlZW5SZXBvc2l0b3J5KFsKICAgICAgX3VzZXIoJ0RlbGV0ZSBVc2VyJywgJ2RlbGV0ZUBleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgIF0pOwogICAgYXdhaXQgX3B1bXAodGVzdGVyLCByZXBvc2l0b3J5KTsKICAgIGF3YWl0IHRlc3Rlci50YXAoZmluZC5ieVNlbWFudGljc0xhYmVsKCdEZWxldGUnKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgZXhwZWN0KGZpbmQudGV4dCgnQ29uZmlybSBEZWxldGUnKSwgZmluZHNPbmVXaWRnZXQpOwogIH0pOwoKICB0ZXN0V2lkZ2V0cygnU0NSRUVOX0dSQU5VTEFSX0RFTEVURV9SRVBPU0lUT1JZJywgKHRlc3RlcikgYXN5bmMgewogICAgZmluYWwgcmVwb3NpdG9yeSA9IF9TY3JlZW5SZXBvc2l0b3J5KFsKICAgICAgX3VzZXIoJ0RlbGV0ZSBVc2VyJywgJ2RlbGV0ZUBleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgIF0pOwogICAgYXdhaXQgX3B1bXAodGVzdGVyLCByZXBvc2l0b3J5KTsKICAgIGF3YWl0IHRlc3Rlci50YXAoZmluZC5ieVNlbWFudGljc0xhYmVsKCdEZWxldGUnKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcCgpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLnRleHQoJ0NvbmZpcm0gRGVsZXRlJykpOwogICAgYXdhaXQgdGVzdGVyLnB1bXAoKTsKICAgIGV4cGVjdChyZXBvc2l0b3J5LnVzZXJzLCBpc0VtcHR5KTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9ERVRBSUxfREFUQScsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU2NyZWVuUmVwb3NpdG9yeShbCiAgICAgIF91c2VyKCdEZXRhaWwgVXNlcicsICdkZXRhaWxAZXhhbXBsZS5jb20nLCBpZDogMSksCiAgICBdKTsKICAgIGF3YWl0IF9wdW1wKHRlc3RlciwgcmVwb3NpdG9yeSk7CiAgICBhd2FpdCB0ZXN0ZXIudGFwKF91c2VyVGV4dCgnRGV0YWlsIFVzZXInKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcEFuZFNldHRsZSgpOwogICAgZXhwZWN0KF91c2VyVGV4dCgnRGV0YWlsIFVzZXInKSwgZmluZHNPbmVXaWRnZXQpOwogICAgZXhwZWN0KF91c2VyVGV4dCgnZGV0YWlsQGV4YW1wbGUuY29tJyksIGZpbmRzT25lV2lkZ2V0KTsKICB9KTsKCiAgdGVzdFdpZGdldHMoJ1NDUkVFTl9HUkFOVUxBUl9ERVRBSUxfQkFDSycsICh0ZXN0ZXIpIGFzeW5jIHsKICAgIGZpbmFsIHJlcG9zaXRvcnkgPSBfU2NyZWVuUmVwb3NpdG9yeShbCiAgICAgIF91c2VyKCdCYWNrIFVzZXInLCAnYmFja0BleGFtcGxlLmNvbScsIGlkOiAxKSwKICAgIF0pOwogICAgYXdhaXQgX3B1bXAodGVzdGVyLCByZXBvc2l0b3J5KTsKICAgIGF3YWl0IHRlc3Rlci50YXAoX3VzZXJUZXh0KCdCYWNrIFVzZXInKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcEFuZFNldHRsZSgpOwogICAgYXdhaXQgdGVzdGVyLnRhcChmaW5kLnRleHQoJ0JhY2snKSk7CiAgICBhd2FpdCB0ZXN0ZXIucHVtcEFuZFNldHRsZSgpOwogICAgZXhwZWN0KF91c2VyVGV4dCgnQmFjayBVc2VyJyksIGZpbmRzT25lV2lkZ2V0KTsKICB9KTsKfQo=',
};

void _materializeEmbeddedFiles() {
  for (final entry in _embeddedSources.entries) {
    final file = File(entry.key);
    file.parent.createSync(recursive: true);
    file.writeAsBytesSync(base64Decode(entry.value));
  }
  for (final entry in _embeddedGoldens.entries) {
    final file = File(entry.key);
    file.parent.createSync(recursive: true);
    file.writeAsBytesSync(base64Decode(entry.value));
  }
  for (final entry in _embeddedOverrides.entries) {
    final file = File(entry.key);
    file.parent.createSync(recursive: true);
    file.writeAsBytesSync(base64Decode(entry.value));
  }
  for (final entry in _embeddedFinalOverrides.entries) {
    final file = File(entry.key);
    file.parent.createSync(recursive: true);
    file.writeAsBytesSync(base64Decode(entry.value));
  }
}

void _cleanupEmbeddedFiles() {
  for (final path in _embeddedSources.keys) {
    try {
      final file = File(path);
      if (file.existsSync()) file.deleteSync();
    } catch (_) {
      // File tạm không dọn được không làm thay đổi kết quả.
    }
  }
  for (final path in _embeddedGoldens.keys) {
    try {
      final file = File(path);
      if (file.existsSync()) file.deleteSync();
    } catch (_) {
      // File tạm không dọn được không làm thay đổi kết quả.
    }
  }
  for (final path in _embeddedOverrides.keys) {
    try {
      final file = File(path);
      if (file.existsSync()) file.deleteSync();
    } catch (_) {
      // File tạm không dọn được không làm thay đổi kết quả.
    }
  }
  for (final path in _embeddedFinalOverrides.keys) {
    try {
      final file = File(path);
      if (file.existsSync()) file.deleteSync();
    } catch (_) {
      // File tạm không dọn được không làm thay đổi kết quả.
    }
  }
}
