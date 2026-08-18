import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:ui' show Size;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../lib/main.dart' as student_app;

const _observationMarker = '###GRADER_OBS###';
const _checkpointMarker = '###RAR_CHECKPOINT###';
const _captureMarker = '###RAR_CAPTURE###';
const _stageMarker = '###GRADER_STAGE###';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final plan = _readObject('test/behavior_plan.json', 'behavior_plan.json');
  final cases = _asList(plan['cases']).map(_asMap).toList();
  final selectedScenario = Platform.environment['GRADER_SCENARIO_CODE'];
  final grouped = <String, List<Map<String, dynamic>>>{};
  for (final testCase in cases) {
    final executionCode = _text(
      testCase,
      'execution_code',
      _text(testCase, 'scenario_code'),
    );
    if (executionCode.isEmpty) continue;
    grouped.putIfAbsent(executionCode, () => []).add(testCase);
  }

  for (final entry in grouped.entries) {
    if (selectedScenario != null && selectedScenario != entry.key) continue;
    testWidgets(entry.key, (tester) async {
      await _runBehaviorScenario(tester, plan, entry.value);
    });
  }
}

Future<void> _runBehaviorScenario(
  WidgetTester tester,
  Map<String, dynamic> plan,
  List<Map<String, dynamic>> cases,
) async {
  final testCase = cases.first;
  final runtime = _asMap(plan['runtime_config']);
  final databaseContract = _asMap(plan['database_contract']);
  final timeout = Duration(
    milliseconds: _int(runtime['default_timeout_ms'], 5000),
  );
  final variables = _materializeVariables(testCase);

  try {
    sqfliteFfiInit();
    // Flutter widget tests run in a headless sandbox. The regular FFI factory
    // delegates work to a background isolate, which can remain pending forever
    // in constrained Docker environments. Keep all SQLite calls in the test
    // isolate so Golden and student replays have deterministic timeouts.
    databaseFactory = databaseFactoryFfiNoIsolate;
    _applyViewport(tester, _asMap(testCase['viewport']));
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
    if (_bool(_asMap(testCase['initial_state'])['reset_storage'], true)) {
      await tester.runAsync(() => _resetDatabase(databaseContract, variables));
    }
    stdout.writeln('${_stageMarker}STUDENT_APP_BOOT');
    await _bootStudentApp(tester, timeout);

    for (final raw in _asList(testCase['steps'])) {
      final step = _asMap(raw);
      stdout.writeln('${_stageMarker}STUDENT_UI_ACTION');
      await _runStep(tester, step, variables, timeout);
      await _allowExternalAsync(tester);
      await _boundedPump(tester, timeout);
      _throwPendingException(tester, _text(step, 'id', 'action'));
    }

    // Khi abstract một record, backend chạy đúng runner này trên Golden Solution
    // với Hidden DB rồi yêu cầu capture. Chấm bài sinh viên không đặt hai biến môi
    // trường bên dưới nên hoàn toàn không phát sinh file hoặc thay đổi cách assert.
    await tester.runAsync(
      () => _captureOutputDatabase(databaseContract, variables),
    );

    for (final checkpointCase in cases) {
      final checkpoint = _asMap(checkpointCase['checkpoint']);
      stdout.writeln('${_stageMarker}TESTCASE_ASSERTION');
      try {
        await _assertCheckpoint(
          tester,
          checkpoint,
          databaseContract,
          variables,
          timeout,
        );
        _throwPendingException(tester, 'checkpoint');
        _printCheckpoint(checkpointCase, true, 'Đã đáp ứng yêu cầu');
      } catch (error) {
        _printCheckpoint(checkpointCase, false, error.toString());
      }
    }
  } catch (error, stackTrace) {
    for (final checkpointCase in cases) {
      _printCheckpoint(checkpointCase, false, error.toString());
    }
    stdout.writeln(
      '$_observationMarker${jsonEncode(<String, dynamic>{
        'kind': 'BEHAVIOR_REPLAY_FAILURE',
        'scenario_code': testCase['scenario_code'],
        'checkpoint_id': _asMap(testCase['checkpoint'])['id'],
        'message': error.toString(),
      })}',
    );
    Error.throwWithStackTrace(error, stackTrace);
  }
}

void _applyViewport(WidgetTester tester, Map<String, dynamic> viewport) {
  final width = _double(viewport['width'], 390);
  final height = _double(viewport['height'], 844);
  final ratio = _double(viewport['device_pixel_ratio'], 1);
  if (width <= 0 || height <= 0 || ratio <= 0) {
    throw ArgumentError('Viewport không hợp lệ: $viewport');
  }
  tester.view.devicePixelRatio = ratio;
  tester.view.physicalSize = Size(width * ratio, height * ratio);
}

void _printCheckpoint(
  Map<String, dynamic> testCase,
  bool passed,
  String message,
) {
  stdout.writeln(
    '$_checkpointMarker${jsonEncode(<String, dynamic>{
      'test_id': testCase['test_id'],
      'scenario_code': testCase['scenario_code'],
      'passed': passed,
      'message': message,
    })}',
  );
}

Future<void> _bootStudentApp(WidgetTester tester, Duration timeout) async {
  await tester.runAsync(() async {
    await Future<void>.sync(student_app.main).timeout(timeout, onTimeout: () {
    throw TimeoutException('student_app.main() không hoàn tất', timeout);
    });
  });
  await tester.pump();
  await _boundedPump(tester, timeout);
  _throwPendingException(tester, 'boot');
  expect(
    find.byType(WidgetsApp),
    findsAtLeastNWidgets(1),
    reason: 'main() đã chạy nhưng không render WidgetsApp/MaterialApp.',
  );
}

Future<void> _runStep(
  WidgetTester tester,
  Map<String, dynamic> step,
  Map<String, String> variables,
  Duration defaultTimeout,
) async {
  final action = _text(step, 'action');
  final timeout = Duration(
    milliseconds: _int(step['timeout_ms'], defaultTimeout.inMilliseconds),
  );
  switch (action) {
    case 'boot':
      return;
    case 'tap':
      final finder = await _waitForTarget(tester, _asMap(step['target']), timeout);
      await tester.ensureVisible(finder);
      await tester.tap(finder, warnIfMissed: false);
      return;
    case 'enter_text':
      final finder = await _waitForTarget(tester, _asMap(step['target']), timeout);
      await tester.ensureVisible(finder);
      await tester.enterText(finder, _expand(step['value'], variables));
      return;
    case 'clear_text':
      final finder = await _waitForTarget(tester, _asMap(step['target']), timeout);
      await tester.enterText(finder, '');
      return;
    case 'scroll':
      final target = _asMap(step['target']);
      final finder = target.isEmpty
          ? find.byType(Scrollable).first
          : await _waitForTarget(tester, target, timeout);
      final delta = _asMap(step['delta']);
      final dx = _double(delta['x'], 0);
      final dy = _double(delta['y'], -300);
      await tester.drag(finder, Offset(dx, dy));
      return;
    case 'back':
      await tester.pageBack();
      return;
    case 'wait_until':
      final target = _asMap(step['target']);
      final visible = _bool(step['visible'], true);
      await _waitUntil(
        tester,
        () => _finder(target).evaluate().isNotEmpty == visible,
        timeout,
        'wait_until không đạt trạng thái mong đợi',
      );
      return;
    case 'restart':
      throw UnsupportedError(
        'restart phải được tách thành scenario mới để bảo đảm cô lập process.',
      );
    default:
      throw ArgumentError('Action không được hỗ trợ: $action');
  }
}

Future<void> _assertCheckpoint(
  WidgetTester tester,
  Map<String, dynamic> checkpoint,
  Map<String, dynamic> databaseContract,
  Map<String, String> variables,
  Duration timeout,
) async {
  final kind = _text(checkpoint, 'kind');
  if (kind == 'database_observation' ||
      _text(checkpoint, 'scope') == 'database') {
    await tester.runAsync(
      () => _assertDatabase(checkpoint, databaseContract, variables),
    );
    return;
  }

  final expectValue = _asMap(checkpoint['expect']);
  final target = _asMap(checkpoint['target']);
  if (target.isNotEmpty) {
    final visible = _bool(checkpoint['visible'] ?? expectValue['visible'], true);
    await _waitUntil(
      tester,
      () => _finder(target).evaluate().isNotEmpty == visible,
      timeout,
      'Widget không có trạng thái hiển thị mong đợi: $target',
    );
  }

  for (final raw in _asList(expectValue['visible_texts'])) {
    final value = _expand(raw, variables);
    await _waitUntil(
      tester,
      () => find.text(value).evaluate().isNotEmpty,
      timeout,
      'Không thấy nội dung "$value" trên UI.',
    );
  }
  for (final raw in _asList(expectValue['hidden_texts'])) {
    final value = _expand(raw, variables);
    expect(find.text(value), findsNothing, reason: 'Nội dung "$value" vẫn còn trên UI.');
  }

  final expectedText = checkpoint['text'] ?? expectValue['text'];
  if (expectedText != null) {
    final value = _expand(expectedText, variables);
    expect(find.text(value), findsAtLeastNWidgets(1));
  }
  final noException = checkpoint['no_exception'] ?? expectValue['no_exception'];
  if (_bool(noException, false)) _throwPendingException(tester, 'checkpoint');
}

Future<void> _assertDatabase(
  Map<String, dynamic> checkpoint,
  Map<String, dynamic> contract,
  Map<String, String> variables,
) async {
  if (!_bool(contract['enabled'], false)) {
    throw StateError('Checkpoint DB tồn tại nhưng database_contract.enabled=false.');
  }
  final path = await _databasePath(contract, variables);
  if (!File(path).existsSync()) {
    throw StateError('Không tìm thấy SQLite database theo contract: $path');
  }
  final database = await databaseFactoryFfiNoIsolate.openDatabase(path);
  try {
    final table = _expand(checkpoint['table'], variables);
    if (table.isEmpty || !RegExp(r'^[A-Za-z_][A-Za-z0-9_]*$').hasMatch(table)) {
      throw ArgumentError('Tên bảng SQLite không hợp lệ: $table');
    }
    final expected = <String, dynamic>{
      for (final entry in _asMap(checkpoint['row']).entries)
        entry.key: _expandValue(entry.value, variables),
    };
    final rows = await database.query(table);
    final matches = rows.where((row) => _rowContains(row, expected)).toList();
    final operation = _text(checkpoint, 'operation').toUpperCase();
    if (operation == 'DELETE' || _bool(checkpoint['absent'], false)) {
      expect(matches, isEmpty, reason: 'SQLite vẫn còn row phải được xóa: $expected');
    } else {
      expect(matches, isNotEmpty, reason: 'SQLite không có row mong đợi: $expected');
    }
    if (checkpoint['count'] != null) {
      expect(rows.length, _int(checkpoint['count'], -1));
    }
  } finally {
    await database.close();
  }
}

bool _rowContains(Map<String, Object?> actual, Map<String, dynamic> expected) {
  for (final entry in expected.entries) {
    if (!actual.containsKey(entry.key)) return false;
    if ('${actual[entry.key]}' != '${entry.value}') return false;
  }
  return true;
}

Future<String> _databasePath(
  Map<String, dynamic> contract,
  Map<String, String> variables,
) async {
  final configured = _expand(contract['path'], variables);
  if (configured.isNotEmpty) {
    return p.isAbsolute(configured) ? configured : p.normalize(p.join('/app', configured));
  }
  final name = _expand(contract['database_name'] ?? contract['name'], variables);
  if (name.isEmpty) throw StateError('database_contract thiếu path hoặc database_name.');
  final root = await databaseFactoryFfiNoIsolate.getDatabasesPath();
  return p.join(root, name);
}

Future<void> _resetDatabase(
  Map<String, dynamic> contract,
  Map<String, String> variables,
) async {
  if (!_bool(contract['enabled'], false)) return;
  final path = await _databasePath(contract, variables);
  final target = File(path);
  if (target.existsSync()) await databaseFactoryFfiNoIsolate.deleteDatabase(path);
  final fixturePath = _expand(contract['hidden_fixture_path'], variables);
  if (fixturePath.isEmpty) return;
  final fixture = File(fixturePath);
  if (!fixture.existsSync()) {
    throw StateError('Không tìm thấy database ẩn: $fixturePath');
  }
  await target.parent.create(recursive: true);
  await fixture.copy(path);
}

Future<void> _captureOutputDatabase(
  Map<String, dynamic> contract,
  Map<String, String> variables,
) async {
  final outputPath = Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '';
  if (outputPath.isEmpty) return;
  if (!_bool(contract['enabled'], false)) {
    throw StateError('Không thể capture Output DB khi database_contract.enabled=false.');
  }

  final sourcePath = await _databasePath(contract, variables);
  if (!File(sourcePath).existsSync()) {
    throw StateError('Không tìm thấy SQLite sau khi replay Golden: $sourcePath');
  }
  final output = File(outputPath);
  await output.parent.create(recursive: true);
  if (output.existsSync()) await output.delete();

  // VACUUM INTO tạo một snapshot nhất quán ngay cả khi Golden App vẫn giữ kết nối
  // SQLite/WAL. Copy file thô ở thời điểm này có thể bỏ sót dữ liệu trong WAL.
  final database = await databaseFactoryFfiNoIsolate.openDatabase(sourcePath);
  try {
    final escaped = output.absolute.path.replaceAll("'", "''");
    await database.execute("VACUUM INTO '$escaped'");
  } finally {
    await database.close();
  }
  if (!output.existsSync() || output.lengthSync() == 0) {
    throw StateError('Runner không sinh được Output DB tại $outputPath');
  }

  final metadataPath = Platform.environment['GRADER_CAPTURE_METADATA_PATH'] ?? '';
  if (metadataPath.isNotEmpty) {
    final metadata = File(metadataPath);
    await metadata.parent.create(recursive: true);
    await metadata.writeAsString(jsonEncode(<String, dynamic>{
      'schema_version': '1.0',
      'source_database': sourcePath,
      'output_database': output.absolute.path,
      'variables': variables,
    }));
  }
  stdout.writeln(
    '$_captureMarker${jsonEncode(<String, dynamic>{
      'captured': true,
      'output_database': output.absolute.path,
      'variables': variables,
    })}',
  );
}

Future<Finder> _waitForTarget(
  WidgetTester tester,
  Map<String, dynamic> target,
  Duration timeout,
) async {
  if (target.isEmpty) throw ArgumentError('Action thiếu semantic target.');
  await _waitUntil(
    tester,
    () => _finder(target).evaluate().isNotEmpty,
    timeout,
    'Không tìm thấy semantic target: $target',
  );
  final finder = _finder(target);
  final index = _int(target['index'], 0);
  return index <= 0 ? finder.first : finder.at(index);
}

Finder _finder(Map<String, dynamic> target) {
  for (final keyName in const ['semanticId', 'semantic_id', 'valueKey', 'value_key', 'key']) {
    final value = _text(target, keyName);
    if (value.isNotEmpty) {
      final finder = find.byKey(ValueKey<String>(value));
      if (finder.evaluate().isNotEmpty) return finder;
    }
  }
  final label = _text(target, 'label');
  final hint = _text(target, 'hint');
  if (label.isNotEmpty) {
    final semantics = find.bySemanticsLabel(label);
    if (semantics.evaluate().isNotEmpty) return semantics;
  }
  if (label.isNotEmpty || hint.isNotEmpty) {
    final finder = find.byWidgetPredicate((widget) {
      final decoration = switch (widget) {
        TextField field => field.decoration,
        _ => null,
      };
      return decoration != null &&
          (label.isEmpty || decoration.labelText == label) &&
          (hint.isEmpty || decoration.hintText == hint);
    });
    if (finder.evaluate().isNotEmpty) return finder;
  }
  final text = _text(target, 'text');
  if (text.isNotEmpty) return find.text(text);
  throw ArgumentError('Target không có semanticId/key/label/hint/text: $target');
}

Future<void> _waitUntil(
  WidgetTester tester,
  bool Function() condition,
  Duration timeout,
  String message,
) async {
  final watch = Stopwatch()..start();
  while (watch.elapsed < timeout) {
    if (condition()) return;
    await _allowExternalAsync(tester);
    await tester.pump(const Duration(milliseconds: 50));
    _throwPendingException(tester, 'wait');
  }
  throw TimeoutException(message, timeout);
}

Future<void> _boundedPump(WidgetTester tester, Duration timeout) async {
  final watch = Stopwatch()..start();
  var quietFrames = 0;
  while (watch.elapsed < timeout && quietFrames < 3) {
    await _allowExternalAsync(tester);
    await tester.pump(const Duration(milliseconds: 50));
    _throwPendingException(tester, 'pump');
    if (tester.binding.hasScheduledFrame) {
      quietFrames = 0;
    } else {
      quietFrames++;
    }
  }
}

/// Lets file and SQLite futures advance outside the fake clock owned by
/// testWidgets, then returns control to deterministic widget pumping.
Future<void> _allowExternalAsync(WidgetTester tester) async {
  await tester.runAsync(
    () => Future<void>.delayed(const Duration(milliseconds: 15)),
  );
}

void _throwPendingException(WidgetTester tester, String stage) {
  final error = tester.takeException();
  if (error != null) throw StateError('$stage: $error');
}

Map<String, String> _materializeVariables(Map<String, dynamic> testCase) {
  final definitions = _asMap(testCase['variables']);
  final oracleInput = _asMap(_asMap(testCase['oracle'])['input']);
  final seed = _text(_asMap(testCase['oracle']), 'seed', _text(testCase, 'scenario_code'));
  final random = Random(_stableHash(seed));
  final values = <String, String>{};
  for (final entry in definitions.entries) {
    final definition = _asMap(entry.value);
    final generator = _text(definition, 'generator', 'text');
    values[entry.key] = switch (generator) {
      'email' => 'sv${100000 + random.nextInt(899999)}@grader.test',
      'stable_id' => 'SV${100000 + random.nextInt(899999)}',
      'first_name' => 'First${100 + random.nextInt(899)}',
      'last_name' => 'Last${100 + random.nextInt(899)}',
      'phone' => '09${10000000 + random.nextInt(89999999)}',
      _ => 'value_${100000 + random.nextInt(899999)}',
    };
  }
  oracleInput.forEach((key, value) {
    values.putIfAbsent(key, () => '$value');
  });
  return values;
}

int _stableHash(String value) {
  var hash = 0x811c9dc5;
  for (final unit in value.codeUnits) {
    hash ^= unit;
    hash = (hash * 0x01000193) & 0x7fffffff;
  }
  return hash;
}

String _expand(Object? value, Map<String, String> variables) {
  var result = value?.toString() ?? '';
  variables.forEach((key, item) => result = result.replaceAll('\${$key}', item));
  return result;
}

dynamic _expandValue(Object? value, Map<String, String> variables) {
  return value is String ? _expand(value, variables) : value;
}

Map<String, dynamic> _readObject(String primary, String fallback) {
  final file = File(primary).existsSync() ? File(primary) : File(fallback);
  if (!file.existsSync()) throw StateError('Không tìm thấy behavior_plan.json.');
  return _asMap(jsonDecode(file.readAsStringSync()));
}

Map<String, dynamic> _asMap(Object? value) {
  if (value is! Map) return <String, dynamic>{};
  return <String, dynamic>{
    for (final entry in value.entries) '${entry.key}': entry.value,
  };
}

List<dynamic> _asList(Object? value) => value is List ? value : <dynamic>[];

String _text(Map<String, dynamic> source, String key, [String fallback = '']) {
  final value = source[key]?.toString().trim();
  return value == null || value.isEmpty ? fallback : value;
}

bool _bool(Object? value, bool fallback) {
  if (value == null) return fallback;
  if (value is bool) return value;
  return value.toString().toLowerCase() == 'true';
}

int _int(Object? value, int fallback) {
  if (value is num) return value.toInt();
  return int.tryParse('$value') ?? fallback;
}

double _double(Object? value, double fallback) {
  if (value is num) return value.toDouble();
  return double.tryParse('$value') ?? fallback;
}
