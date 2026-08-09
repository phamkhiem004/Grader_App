import 'dart:convert';
import 'dart:io';
import 'dart:ui' show Size;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';

// Engine chung chỉ nhìn vào semantic key công khai, không import model/repository của bài.
import '../lib/main.dart' as student_app;
// __DIRECT_FUNCTION_IMPORTS__

Map<String, dynamic> _activeCase = <String, dynamic>{};
Map<String, dynamic> _activeSuite = <String, dynamic>{};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final matrix = _loadMatrix();
  final mode = Platform.environment['GRADER_CASE_MODE'] ?? 'all';

  if (mode == 'preflight') {
    final candidates = matrix.entries.where(
      (entry) => !_isDirectMetadata(_asMap(entry.value)),
    );
    if (candidates.isNotEmpty) {
      final metadata = _asMap(candidates.first.value);
      testWidgets('_GRADER_PREFLIGHT', (tester) async {
        _activeCase = metadata;
        _activeSuite = _asMap(metadata['suite']);
        await _checkSourceContracts();
        await _boot(tester);
      });
    }
    return;
  }

  for (final entry in matrix.entries) {
    final testId = entry.key;
    final metadata = _asMap(entry.value);
    if (mode == 'direct' && !_isDirectMetadata(metadata)) continue;
    if (mode == 'case' && Platform.environment['GRADER_CASE_ID'] != testId) {
      continue;
    }
    testWidgets(testId, (tester) async {
      await _runCase(tester, testId, metadata);
    });
  }
}

bool _isDirectMetadata(Map<String, dynamic> metadata) {
  if ((metadata['runner'] ?? '').toString() == 'DIRECT_FUNCTION') return true;
  if ((metadata['runner'] ?? '').toString() != 'GROUP') return false;
  final children = _asList(metadata['children']);
  return children.isNotEmpty &&
      children.every(
        (child) =>
            (_asMap(child)['runner'] ?? '').toString() == 'DIRECT_FUNCTION',
      );
}

Future<void> _runCase(
  WidgetTester tester,
  String testId,
  Map<String, dynamic> metadata,
) async {
  _activeCase = metadata;
  _activeSuite = _asMap(metadata['suite']);
  await _checkSourceContracts();
  final runner = (metadata['runner'] ?? '').toString();
  final parameters = _asMap(metadata['parameters']);

  switch (runner) {
    case 'DIRECT_FUNCTION':
      await _checkDirectFunction(parameters);
      return;
    case 'APP_BOOT':
      await _boot(tester);
      final rootKey = _text(parameters, 'rootKey');
      if (rootKey.isNotEmpty) expect(_visibleByKey(rootKey), findsOneWidget);
      final readyKey = _text(parameters, 'readyKey');
      if (readyKey.isNotEmpty) {
        await _waitForVisible(
          tester,
          readyKey,
          _number(parameters, 'readyTimeoutMs', 3000).toInt(),
        );
      }
      expect(tester.takeException(), isNull);
      return;
    case 'WIDGET_VISIBLE':
      await _boot(tester);
      final widgetKey = _requiredText(parameters, 'widgetKey');
      final widgetFinder = _byKey(widgetKey);
      expect(
        widgetFinder,
        findsOneWidget,
        reason: 'Không tìm thấy widget key: $widgetKey',
      );
      final visibleType = _text(parameters, 'targetType');
      if (visibleType.isNotEmpty) {
        _assertTargetType(tester, widgetFinder, widgetKey, visibleType);
      }
      return;
    case 'WIDGET_TYPE_VISIBLE':
      await _checkWidgetTypeVisible(tester, parameters);
      return;
    case 'WIDGET_TEXT_CONTENT':
      await _checkWidgetTextContent(tester, parameters);
      return;
    case 'WIDGET_ENABLED':
      await _checkWidgetEnabled(tester, parameters);
      return;
    case 'FORM_VALIDATE_FIELDS':
      await _checkFormValidateFields(tester, parameters);
      return;
    case 'FORM_PREFILL':
      await _checkFormPrefill(tester, parameters);
      return;
    case 'FORM_SUBMIT':
      await _checkFormSubmit(tester, parameters);
      return;
    case 'LIST_ITEM_COUNT':
      await _checkListItemCount(tester, parameters);
      return;
    case 'DIALOG_FLOW':
      await _checkDialogFlow(tester, parameters);
      return;
    case 'WIDGET_SEMANTICS_LABEL':
      await _checkWidgetSemanticsLabel(tester, parameters);
      return;
    case 'STATE_REACTIVE_FLOW':
      await _checkStateReactiveFlow(tester, parameters);
      return;
    case 'GROUP':
      await _checkGroup(tester, testId, metadata);
      return;
    case 'FORM_REQUIRED_FIELDS':
      await _boot(tester);
      final fields = _csv(parameters, 'fieldKeys');
      final errors = _csv(parameters, 'errorKeys');
      if (fields.isEmpty || errors.isEmpty) {
        fail('fieldKeys và errorKeys không được để trống.');
      }
      final fieldType = _text(parameters, 'fieldType', 'input');
      for (final key in fields) {
        final field = _byKey(key);
        expect(field, findsOneWidget, reason: 'Thiếu field semantic key: $key');
        _assertTargetType(tester, field, key, fieldType);
        await tester.enterText(field, '');
      }
      final beforeErrors = [for (final key in errors) _visibleKeyCount(key)];
      final submitKey = _text(parameters, 'submitKey');
      expect(
        _byKey(submitKey),
        findsOneWidget,
        reason: 'Thiếu submit key: $submitKey',
      );
      await _tap(tester, _byKey(submitKey), submitKey);
      await _settle(tester);
      for (var index = 0; index < errors.length; index++) {
        final key = errors[index];
        final after = _visibleKeyCount(key);
        if (_bool(parameters, 'requireNewErrors', true)) {
          _expectNewSemanticKey(
            key,
            beforeErrors[index],
            after,
            'Submit rỗng không tạo đúng một lỗi mới: $key',
          );
        } else {
          expect(
            after,
            greaterThanOrEqualTo(1),
            reason: 'Thiếu error key: $key',
          );
        }
      }
      return;
    case 'RESPONSIVE_NO_OVERFLOW':
      await _responsive(tester, parameters);
      return;
    case 'RESPONSIVE_TARGET':
      await _responsiveTarget(tester, parameters);
      return;
    case 'WIDGET_DIMENSION':
      await _checkWidgetDimension(tester, parameters);
      return;
    case 'WIDGET_PADDING':
      await _checkWidgetPadding(tester, parameters);
      return;
    case 'WIDGET_TEXT_STYLE':
      await _checkWidgetTextStyle(tester, parameters);
      return;
    case 'WIDGET_GAP':
      await _checkWidgetGap(tester, parameters);
      return;
    case 'NAVIGATION':
      await _boot(tester);
      final openKey = _text(parameters, 'openKey');
      final destinationKey = _text(parameters, 'destinationKey');
      final destinationBefore = _visibleKeyCount(destinationKey);
      await _tap(tester, _byKey(openKey), openKey);
      await _settle(tester);
      final destinationAfter = _visibleKeyCount(destinationKey);
      if (_bool(parameters, 'requireNewDestination', true)) {
        _expectNewSemanticKey(
          destinationKey,
          destinationBefore,
          destinationAfter,
          'Bấm $openKey không mở đúng một màn hình mới $destinationKey',
        );
      } else {
        expect(destinationAfter, greaterThanOrEqualTo(1));
      }
      final backKey = _text(parameters, 'backKey');
      final homeKey = _text(parameters, 'homeKey');
      if (backKey.isNotEmpty && homeKey.isNotEmpty) {
        await _tap(tester, _byKey(backKey), backKey);
        await _settle(tester);
        expect(
          _isKeyOnCurrentRoute(homeKey),
          isTrue,
          reason: 'Bấm $backKey không quay lại route chứa $homeKey',
        );
        if (_bool(parameters, 'hideDestinationAfterBack', true)) {
          expect(
            _isKeyOnCurrentRoute(destinationKey),
            isFalse,
            reason: '$destinationKey vẫn là route hiện tại sau khi quay lại',
          );
        }
      }
      return;
    case 'LIST_VISIBLE':
      await _boot(tester);
      final listKey = _text(parameters, 'listKey');
      final listFinder = _byKey(listKey);
      expect(listFinder, findsOneWidget);
      for (final key in _csv(parameters, 'itemKeys')) {
        final itemFinder = _exactByKey(key);
        await _revealLazyItem(tester, listFinder, itemFinder);
        expect(
          find.descendant(
            of: listFinder,
            matching: itemFinder,
            matchRoot: true,
          ),
          findsOneWidget,
          reason: 'Item $key không nằm trong list $listKey',
        );
      }
      return;
    case 'BUTTON_ACTION':
      await _boot(tester);
      final buttonKey = _text(parameters, 'buttonKey');
      final resultKey = _text(parameters, 'resultKey');
      final resultBefore = _visibleKeyCount(resultKey);
      await _tap(tester, _byKey(buttonKey), buttonKey);
      await _settle(tester);
      final resultAfter = _visibleKeyCount(resultKey);
      if (_bool(parameters, 'requireNewResult', true)) {
        _expectNewSemanticKey(
          resultKey,
          resultBefore,
          resultAfter,
          'Bấm $buttonKey không tạo đúng một $resultKey mới',
        );
      } else {
        expect(resultAfter, greaterThanOrEqualTo(1));
      }
      return;
    default:
      fail('Testcase $testId chưa có common runner: $runner');
  }
}

Future<void> _checkDirectFunction(Map<String, dynamic> parameters) async {
  final functionPath = _requiredText(parameters, 'functionPath');
  final functionName = _requiredText(parameters, 'functionName');
  final rawArguments = _text(parameters, 'argumentsJson', '[]');
  dynamic decoded;
  try {
    decoded = jsonDecode(rawArguments);
  } catch (error) {
    fail('argumentsJson không hợp lệ: $error');
  }
  if (decoded is! List) fail('argumentsJson phải là một mảng JSON.');

  dynamic actual = _invokeDirectFunction(
    '$functionPath::$functionName',
    List<dynamic>.from(decoded as List),
  );
  if (actual is Future) actual = await actual;

  final expectedType = _text(
    parameters,
    'expectedType',
    'string',
  ).toLowerCase();
  final expectedRaw = _text(parameters, 'expectedValue');
  final expected = _parseDirectExpected(expectedRaw, expectedType);
  final matchMode = _text(parameters, 'matchMode', 'equals').toLowerCase();
  if (matchMode == 'contains') {
    expect(
      actual.toString(),
      contains(expected.toString()),
      reason: '$functionName không chứa giá trị mong đợi',
    );
  } else {
    // equals() so sánh sâu List/Map; expect(actual, expected) chỉ so sánh cùng object.
    expect(
      actual,
      equals(expected),
      reason: '$functionName trả về giá trị không đúng',
    );
  }
}

dynamic _invokeDirectFunction(String target, List<dynamic> arguments) {
  switch (target) {
    // __DIRECT_FUNCTION_CASES__
    default:
      fail('Chưa sinh dispatcher cho hàm $target. Hãy lưu lại Draft/Publish.');
  }
}

dynamic _parseDirectExpected(String value, String type) {
  switch (type) {
    case 'bool':
      return value.toLowerCase() == 'true';
    case 'int':
      return int.parse(value);
    case 'double':
      return double.parse(value);
    case 'json':
      return jsonDecode(value);
    case 'null':
      return null;
    default:
      return value;
  }
}

Future<void> _checkSourceContracts() async {
  final contracts = _asList(_activeSuite['source_contracts']);
  for (final raw in contracts) {
    final contract = _asMap(raw);
    final path = _text(contract, 'path');
    final type = _text(contract, 'type', 'symbol');
    final symbols = _asList(
      contract['symbols'],
    ).map((value) => value.toString()).toList();
    final file = File(path);
    expect(
      file.existsSync(),
      isTrue,
      reason: 'Khong tim thay source contract $path',
    );
    final source = file.readAsStringSync();
    for (final symbol in symbols) {
      final escaped = RegExp.escape(symbol);
      final declaration = RegExp(
        '\\b(?:class|mixin|enum|extension|typedef)\\s+$escaped\\b|\\b(?:final|const|var|late)\\s+$escaped\\b|\\b$escaped\\s*\\(',
      );
      expect(
        declaration.hasMatch(source),
        isTrue,
        reason: 'Thieu $type symbol $symbol trong $path',
      );
    }
  }
}

Future<void> _checkGroup(
  WidgetTester tester,
  String groupId,
  Map<String, dynamic> metadata,
) async {
  final rawChildren = metadata['children'];
  if (rawChildren is! List || rawChildren.isEmpty) {
    fail('Nhóm $groupId không có testcase con.');
  }

  final failures = <String>[];
  for (final rawChild in rawChildren) {
    final child = _asMap(rawChild);
    final childId = _text(child, 'instance_id', 'child');
    try {
      await _runCase(tester, '$groupId/$childId', child);
    } catch (error) {
      failures.add('$childId: $error');
    }
  }

  if (failures.isNotEmpty) {
    fail(
      'Nhóm ${metadata['name'] ?? groupId} thất bại vì assert con không đạt:\n'
      '${failures.join('\n')}',
    );
  }
}

Future<void> _checkStateReactiveFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final initialKey = _requiredText(parameters, 'initialKey');
  final actionKey = _requiredText(parameters, 'actionKey');
  final updatedKey = _requiredText(parameters, 'updatedKey');
  final absentKey = _text(parameters, 'absentKey');
  expect(
    _visibleByKey(initialKey),
    findsOneWidget,
    reason: 'Thiếu state ban đầu: $initialKey',
  );
  final updatedBefore = _visibleKeyCount(updatedKey);
  await _tap(tester, _byKey(actionKey), actionKey);
  await _settle(tester);
  final updatedAfter = _visibleKeyCount(updatedKey);
  if (_bool(parameters, 'requireNewUpdatedState', true)) {
    _expectNewSemanticKey(
      updatedKey,
      updatedBefore,
      updatedAfter,
      'State không chuyển sang đúng một $updatedKey mới sau $actionKey',
    );
  } else {
    expect(
      updatedAfter,
      greaterThanOrEqualTo(1),
      reason: 'State không cập nhật sau action $actionKey: $updatedKey',
    );
  }
  if (absentKey.isNotEmpty) {
    expect(
      _goneByKey(absentKey),
      findsNothing,
      reason: 'State cũ vẫn còn sau action $actionKey: $absentKey',
    );
  }
}

Future<void> _boot(WidgetTester tester) async {
  // Dispose the previous widget tree; unknown static singletons are not
  // pretended to be reset because the common engine cannot inspect them safely.
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump();
  await tester.runAsync(() async {
    await Future<void>.sync(student_app.main).timeout(
      const Duration(seconds: 8),
      onTimeout: () => throw TestFailure(
        'student_app.main() did not complete within 8 seconds.',
      ),
    );
    await Future<void>.delayed(const Duration(milliseconds: 50));
  });
  await tester.pump();
  expect(tester.takeException(), isNull);
  await _applySuiteSetup(tester);
}

Future<void> _applySuiteSetup(WidgetTester tester) async {
  final requiredKeys = _suiteCsv('required_keys');
  for (final key in requiredKeys) {
    expect(
      _byKey(key),
      findsOneWidget,
      reason: 'Suite yêu cầu semantic key nhưng không tìm thấy: $key',
    );
  }
  final readyKey = _suiteText('ready_key');
  if (readyKey.isNotEmpty)
    await _waitForVisible(
      tester,
      readyKey,
      _suiteNumber('boot_timeout_ms', 3000).toInt(),
    );
  await _runSetupSteps(tester, _asList(_activeSuite['setup_steps']), 'suite');
  await _runSetupSteps(tester, _asList(_activeCase['setup_steps']), 'testcase');
}

Future<void> _runSetupSteps(
  WidgetTester tester,
  List<dynamic> rawSteps,
  String owner,
) async {
  var index = 1;
  for (final raw in rawSteps) {
    final step = _asMap(raw);
    final type = _text(step, 'type').toLowerCase();
    final key = _requiredText(step, 'key');
    final timeout = _number(
      step,
      'timeout_ms',
      _suiteNumber('step_timeout_ms', 2000),
    );
    switch (type) {
      case 'tap':
        expect(
          _byKey(key),
          findsOneWidget,
          reason: 'Setup $owner #$index thiếu key: $key',
        );
        await _tap(tester, _byKey(key), key);
        await _settle(tester);
        break;
      case 'enter_text':
        final finder = _byKey(key);
        expect(
          finder,
          findsOneWidget,
          reason: 'Setup $owner #$index thiếu field: $key',
        );
        await tester.enterText(finder, _decodeInput(_text(step, 'value')));
        await _settle(tester);
        break;
      case 'expect_visible':
        expect(
          _byKey(key),
          findsOneWidget,
          reason: 'Setup $owner #$index cần thấy key: $key',
        );
        break;
      case 'expect_absent':
        expect(
          _byKey(key),
          findsNothing,
          reason: 'Setup $owner #$index cần ẩn key: $key',
        );
        break;
      case 'wait_for_visible':
        await _waitForVisible(tester, key, timeout.toInt());
        break;
      default:
        fail('Setup $owner #$index có loại bước không được hỗ trợ: $type');
    }
    index++;
  }
}

Future<void> _waitForVisible(
  WidgetTester tester,
  String key,
  int timeoutMs,
) async {
  final deadline = DateTime.now().add(Duration(milliseconds: timeoutMs));
  while (DateTime.now().isBefore(deadline)) {
    if (_visibleByKey(key).evaluate().isNotEmpty) return;
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pump();
  }
  expect(
    _visibleByKey(key),
    findsOneWidget,
    reason: 'Không xuất hiện semantic key sau khi chờ: $key',
  );
}

Future<void> _revealLazyItem(
  WidgetTester tester,
  Finder listFinder,
  Finder itemFinder,
) async {
  if (itemFinder.evaluate().isNotEmpty) return;
  final scrollable = find.descendant(
    of: listFinder,
    matching: find.byType(Scrollable, skipOffstage: false),
  );
  if (scrollable.evaluate().isEmpty) return;
  try {
    await tester.scrollUntilVisible(
      itemFinder,
      240,
      scrollable: scrollable.first,
      maxScrolls: 60,
    );
    await _settle(tester);
  } catch (_) {
    // Assertion bên gọi sẽ báo đúng key không tìm thấy thay vì lỗi scroll chung chung.
  }
}

Finder _goneByKey(String key) =>
    find.byKey(ValueKey<String>(key), skipOffstage: false);

Finder _exactByKey(String key) =>
    find.byKey(ValueKey<String>(key), skipOffstage: false);

Finder _visibleByKey(String key) {
  final exact = find.byKey(ValueKey<String>(key));
  if (exact.evaluate().isNotEmpty ||
      _suiteBool('strict_semantic_keys', false)) {
    return exact;
  }
  return _byKey(key);
}

int _visibleKeyCount(String key) => _visibleByKey(key).evaluate().length;

void _expectNewSemanticKey(String key, int before, int after, String reason) {
  expect(
    before,
    0,
    reason:
        '$key đã xuất hiện trước thao tác nên không chứng minh được chuyển trạng thái.',
  );
  expect(after, 1, reason: reason);
}

bool _isKeyOnCurrentRoute(String key) {
  return _visibleByKey(key).evaluate().any((element) {
    final route = ModalRoute.of(element);
    return route == null || route.isCurrent;
  });
}

Future<void> _tap(WidgetTester tester, Finder finder, String key) async {
  expect(finder, findsOneWidget, reason: 'Missing action semantic key: $key');
  try {
    await tester.tap(finder, warnIfMissed: false);
  } catch (error, stack) {
    _failIfActionThrew(key, error, stack);
  }
}

Never _failIfActionThrew(String key, Object error, StackTrace stack) {
  fail('Action $key threw an exception: $error\n$stack');
}

Future<void> _settle(WidgetTester tester) async {
  // Advance fake time and the real event loop so animations and overlays settle.
  for (var frame = 0; frame < 8; frame++) {
    await tester.pump(const Duration(milliseconds: 50));
    await tester.runAsync(
      () => Future<void>.delayed(const Duration(milliseconds: 1)),
    );
  }
  await tester.pump();
}

Future<void> _responsive(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final portrait = Size(
    _number(parameters, 'portraitWidth', 390),
    _number(parameters, 'portraitHeight', 844),
  );
  final landscape = Size(
    _number(parameters, 'landscapeWidth', 1024),
    _number(parameters, 'landscapeHeight', 768),
  );

  tester.view.physicalSize = portrait;
  await _boot(tester);
  expect(tester.takeException(), isNull);

  tester.view.physicalSize = landscape;
  await _settle(tester);
  expect(tester.takeException(), isNull);
}

Future<void> _responsiveTarget(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final portrait = Size(
    _number(parameters, 'portraitWidth', 390),
    _number(parameters, 'portraitHeight', 844),
  );
  final landscape = Size(
    _number(parameters, 'landscapeWidth', 1024),
    _number(parameters, 'landscapeHeight', 768),
  );
  final targetKey = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');

  tester.view.physicalSize = portrait;
  await _boot(tester);
  expect(tester.takeException(), isNull);
  final portraitFinder = _byKey(targetKey);
  expect(
    portraitFinder,
    findsOneWidget,
    reason: 'Thiếu target ở portrait: $targetKey',
  );
  _assertTargetType(tester, portraitFinder, targetKey, targetType);

  tester.view.physicalSize = landscape;
  await _settle(tester);
  expect(tester.takeException(), isNull);
  final landscapeFinder = _byKey(targetKey);
  expect(
    landscapeFinder,
    findsOneWidget,
    reason: 'Thiếu target ở landscape: $targetKey',
  );
  _assertTargetType(tester, landscapeFinder, targetKey, targetType);
}

Future<void> _checkWidgetDimension(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  expect(finder, findsOneWidget, reason: 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);

  final dimension = _text(parameters, 'dimension').toLowerCase();
  final expected = _number(parameters, 'expected', double.nan);
  final tolerance = _number(parameters, 'tolerance', 0.5);
  final size = tester.getSize(finder);
  final actual = dimension == 'width' ? size.width : size.height;
  _assertNumber(
    actual,
    expected,
    tolerance,
    _text(parameters, 'comparison'),
    '$key ($targetType) có $dimension=$actual, mong đợi $expected',
  );
}

Future<void> _checkWidgetTypeVisible(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  expect(finder, findsOneWidget, reason: 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);
}

Future<void> _checkWidgetTextContent(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _text(parameters, 'targetType', 'text');
  final finder = _byKey(key);
  expect(finder, findsOneWidget, reason: 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);
  final widget = tester.widget<Text>(finder);
  final actual = widget.data ?? widget.textSpan?.toPlainText() ?? '';
  final expected = _requiredText(parameters, 'expectedText');
  final matchMode = _text(parameters, 'matchMode', 'equals').toLowerCase();
  if (matchMode == 'contains') {
    expect(
      actual,
      contains(expected),
      reason: 'Nội dung $key không chứa expectedText',
    );
  } else {
    expect(actual, expected, reason: 'Nội dung $key không đúng');
  }
}

Future<void> _checkWidgetEnabled(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  expect(finder, findsOneWidget, reason: 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);
  final enabled = _enabledState(tester.widget<Widget>(finder));
  if (enabled == null) {
    fail('Không đọc được trạng thái enabled của $key (${targetType}).');
  }
  expect(
    enabled,
    _bool(parameters, 'expectedEnabled', true),
    reason: 'Trạng thái enabled của $key không đúng',
  );
}

Future<void> _checkFormValidateFields(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final fields = _csv(parameters, 'fieldKeys');
  final values = _csv(parameters, 'invalidValues');
  final errors = _csv(parameters, 'errorKeys');
  if (fields.isEmpty || fields.length != values.length || errors.isEmpty) {
    fail('fieldKeys phải khớp invalidValues và errorKeys không được để trống.');
  }
  final fieldType = _text(parameters, 'fieldType', 'input');
  for (var i = 0; i < fields.length; i++) {
    final fieldFinder = _byKey(fields[i]);
    expect(
      fieldFinder,
      findsOneWidget,
      reason: 'Thiếu field key: ${fields[i]}',
    );
    _assertTargetType(tester, fieldFinder, fields[i], fieldType);
    await tester.enterText(fieldFinder, _decodeInput(values[i]));
  }
  final beforeErrors = [for (final key in errors) _visibleKeyCount(key)];
  final submitKey = _requiredText(parameters, 'submitKey');
  await _tap(tester, _byKey(submitKey), submitKey);
  await _settle(tester);
  for (var index = 0; index < errors.length; index++) {
    final errorKey = errors[index];
    final after = _visibleKeyCount(errorKey);
    if (_bool(parameters, 'requireNewErrors', true)) {
      _expectNewSemanticKey(
        errorKey,
        beforeErrors[index],
        after,
        'Submit không tạo đúng một lỗi validation mới: $errorKey',
      );
    } else {
      expect(
        after,
        greaterThanOrEqualTo(1),
        reason: 'Thiếu lỗi validation key: $errorKey',
      );
    }
  }
}

Future<void> _checkListItemCount(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final listKey = _requiredText(parameters, 'listKey');
  final listFinder = _byKey(listKey);
  expect(
    listFinder,
    findsOneWidget,
    reason: 'Không tìm thấy list key: $listKey',
  );
  final itemKeys = _csv(parameters, 'itemKeys');
  if (itemKeys.isEmpty) fail('Thiếu itemKeys khi kiểm tra list.');
  var count = 0;
  for (final itemKey in itemKeys) {
    final itemFinder = _exactByKey(itemKey);
    await _revealLazyItem(tester, listFinder, itemFinder);
    if (find
        .descendant(of: listFinder, matching: itemFinder)
        .evaluate()
        .isNotEmpty) {
      count++;
    }
  }
  expect(
    count,
    _number(parameters, 'expectedCount', double.nan).toInt(),
    reason: 'Số item trong $listKey không đúng',
  );
}

Future<void> _checkFormPrefill(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final editKey = _requiredText(parameters, 'editKey');
  final fields = _csv(parameters, 'fieldKeys');
  final beforeValues = <String?>[];
  for (final key in fields) {
    final field = _byKey(key);
    if (field.evaluate().length != 1) {
      beforeValues.add(null);
      continue;
    }
    final editable = find.descendant(
      of: field,
      matching: find.byType(EditableText),
    );
    beforeValues.add(
      editable.evaluate().length == 1
          ? tester.widget<EditableText>(editable).controller.text
          : null,
    );
  }
  await _tap(tester, _byKey(editKey), editKey);
  await _settle(tester);
  final expectedValues = _csv(parameters, 'expectedValues');
  if (fields.isEmpty || fields.length != expectedValues.length) {
    fail('fieldKeys và expectedValues phải có cùng số phần tử.');
  }
  final fieldType = _text(parameters, 'fieldType', 'input');
  var changedByEdit = false;
  for (var i = 0; i < fields.length; i++) {
    final fieldFinder = _byKey(fields[i]);
    expect(
      fieldFinder,
      findsOneWidget,
      reason: 'Thiếu field key: ${fields[i]}',
    );
    _assertTargetType(tester, fieldFinder, fields[i], fieldType);
    final editable = find.descendant(
      of: fieldFinder,
      matching: find.byType(EditableText),
    );
    expect(
      editable,
      findsOneWidget,
      reason: 'Field ${fields[i]} không phải editable input',
    );
    final actual = tester.widget<EditableText>(editable).controller.text;
    final expected = _decodeInput(expectedValues[i]);
    expect(
      actual,
      expected,
      reason: 'Field ${fields[i]} không được prefill đúng',
    );
    if (beforeValues[i] == null || beforeValues[i] != actual)
      changedByEdit = true;
  }
  if (_bool(parameters, 'requirePrefillTransition', true)) {
    expect(
      changedByEdit,
      isTrue,
      reason: 'Bấm $editKey không tạo thay đổi prefill quan sát được.',
    );
  }
}

Future<void> _checkFormSubmit(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final fields = _csv(parameters, 'fieldKeys');
  final values = _csv(parameters, 'values');
  if (fields.isEmpty || fields.length != values.length) {
    fail('fieldKeys và values phải có cùng số phần tử.');
  }
  final fieldType = _text(parameters, 'fieldType', 'input');
  for (var i = 0; i < fields.length; i++) {
    final fieldFinder = _byKey(fields[i]);
    expect(
      fieldFinder,
      findsOneWidget,
      reason: 'Thiếu field key: ${fields[i]}',
    );
    _assertTargetType(tester, fieldFinder, fields[i], fieldType);
    await tester.enterText(fieldFinder, _decodeInput(values[i]));
  }
  final resultKey = _text(parameters, 'resultKey');
  final resultBefore = resultKey.isEmpty ? 0 : _visibleKeyCount(resultKey);
  final submitKey = _requiredText(parameters, 'submitKey');
  await _tap(tester, _byKey(submitKey), submitKey);
  await _settle(tester);
  if (resultKey.isNotEmpty) {
    final resultAfter = _visibleKeyCount(resultKey);
    if (_bool(parameters, 'requireNewResult', true)) {
      _expectNewSemanticKey(
        resultKey,
        resultBefore,
        resultAfter,
        'Submit $submitKey không tạo đúng một $resultKey mới',
      );
    } else {
      expect(resultAfter, greaterThanOrEqualTo(1));
    }
  }
  for (final errorKey in _csv(parameters, 'errorKeys')) {
    expect(
      _goneByKey(errorKey),
      findsNothing,
      reason: 'Dữ liệu hợp lệ nhưng vẫn còn error key: $errorKey',
    );
  }
}

Future<void> _checkDialogFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final actionKey = _requiredText(parameters, 'actionKey');
  final dialogKey = _requiredText(parameters, 'dialogKey');
  final dialogBefore = _visibleKeyCount(dialogKey);
  final resultKey = _text(parameters, 'resultKey');
  final resultBefore = resultKey.isEmpty ? 0 : _visibleKeyCount(resultKey);
  await _tap(tester, _byKey(actionKey), actionKey);
  await _settle(tester);

  final dialogFinder = _visibleByKey(dialogKey);
  final dialogAfter = dialogFinder.evaluate().length;
  if (_bool(parameters, 'requireNewDialog', true)) {
    _expectNewSemanticKey(
      dialogKey,
      dialogBefore,
      dialogAfter,
      'Bấm $actionKey không mở đúng một dialog $dialogKey mới',
    );
  } else {
    expect(
      dialogFinder,
      findsOneWidget,
      reason: 'Không tìm thấy dialog key: $dialogKey',
    );
  }
  _assertTargetType(tester, dialogFinder, dialogKey, 'dialog');

  final decisionKey = _requiredText(parameters, 'decisionKey');
  final decisionFinder = find.descendant(
    of: dialogFinder,
    matching: _byKey(decisionKey),
    matchRoot: true,
  );
  await _tap(tester, decisionFinder, decisionKey);
  await _settle(tester);
  if (resultKey.isNotEmpty) {
    final resultAfter = _visibleKeyCount(resultKey);
    if (_bool(parameters, 'requireNewResult', false)) {
      _expectNewSemanticKey(
        resultKey,
        resultBefore,
        resultAfter,
        'Quyết định $decisionKey không tạo đúng một $resultKey mới',
      );
    } else {
      expect(resultAfter, greaterThanOrEqualTo(1));
    }
  }
  final absentKey = _text(parameters, 'absentKey');
  if (absentKey.isNotEmpty) expect(_byKey(absentKey), findsNothing);
}

Future<void> _checkWidgetSemanticsLabel(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  final semantics = tester.ensureSemantics();
  try {
    await _boot(tester);
    final key = _requiredText(parameters, 'targetKey');
    final targetType = _text(parameters, 'targetType', 'any');
    final finder = _byKey(key);
    expect(finder, findsOneWidget, reason: 'Missing target key: $key');
    _assertTargetType(tester, finder, key, targetType);
    final actual = tester.getSemantics(finder).label;
    final expected = _requiredText(parameters, 'expectedLabel');
    final matchMode = _text(parameters, 'matchMode', 'equals').toLowerCase();
    if (matchMode == 'contains') {
      expect(
        actual,
        contains(expected),
        reason: 'Semantics label does not contain expectedLabel.',
      );
    } else {
      expect(
        actual,
        expected,
        reason: 'Semantics label does not match expectedLabel.',
      );
    }
  } finally {
    semantics.dispose();
  }
}

Future<void> _checkWidgetPadding(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  expect(finder, findsOneWidget, reason: 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);

  final render = tester.renderObject<RenderPadding>(finder);
  final padding = render.padding.resolve(TextDirection.ltr);
  final tolerance = _number(parameters, 'tolerance', 0.5);
  _assertNumber(
    padding.left,
    _number(parameters, 'left', double.nan),
    tolerance,
    'equals',
    '$key left padding',
  );
  _assertNumber(
    padding.top,
    _number(parameters, 'top', double.nan),
    tolerance,
    'equals',
    '$key top padding',
  );
  _assertNumber(
    padding.right,
    _number(parameters, 'right', double.nan),
    tolerance,
    'equals',
    '$key right padding',
  );
  _assertNumber(
    padding.bottom,
    _number(parameters, 'bottom', double.nan),
    tolerance,
    'equals',
    '$key bottom padding',
  );
}

Future<void> _checkWidgetTextStyle(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  expect(finder, findsOneWidget, reason: 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);

  final text = tester.widget<Text>(finder);
  final resolved = DefaultTextStyle.of(
    tester.element(finder),
  ).style.merge(text.style);
  final tolerance = _number(parameters, 'tolerance', 0.5);
  final expectedSize = _number(parameters, 'fontSize', double.nan);
  if (!expectedSize.isNaN) {
    _assertNumber(
      resolved.fontSize ?? double.nan,
      expectedSize,
      tolerance,
      'equals',
      '$key fontSize',
    );
  }
  final expectedWeight = _text(parameters, 'fontWeight');
  if (expectedWeight.isNotEmpty) {
    expect(
      resolved.fontWeight,
      _fontWeight(expectedWeight),
      reason: '$key fontWeight không đúng',
    );
  }
}

Future<void> _checkWidgetGap(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final fromKey = _requiredText(parameters, 'fromKey');
  final toKey = _requiredText(parameters, 'toKey');
  final fromFinder = _byKey(fromKey);
  final toFinder = _byKey(toKey);
  expect(
    fromFinder,
    findsOneWidget,
    reason: 'Không tìm thấy fromKey: $fromKey',
  );
  expect(toFinder, findsOneWidget, reason: 'Không tìm thấy toKey: $toKey');
  final fromType = _text(parameters, 'fromType');
  final toType = _text(parameters, 'toType');
  if (fromType.isNotEmpty)
    _assertTargetType(tester, fromFinder, fromKey, fromType);
  if (toType.isNotEmpty) _assertTargetType(tester, toFinder, toKey, toType);

  final axis = _text(parameters, 'axis').toLowerCase();
  final from = tester.getRect(fromFinder);
  final to = tester.getRect(toFinder);
  final actual = axis == 'horizontal'
      ? to.left - from.right
      : to.top - from.bottom;
  _assertNumber(
    actual,
    _number(parameters, 'expectedGap', double.nan),
    _number(parameters, 'tolerance', 0.5),
    'equals',
    'Khoảng cách $fromKey → $toKey',
  );
}

String _requiredText(Map<String, dynamic> map, String key) {
  final value = _text(map, key);
  if (value.isEmpty) fail('Thiếu parameter $key');
  return value;
}

void _assertTargetType(
  WidgetTester tester,
  Finder finder,
  String key,
  String targetType,
) {
  final normalizedType = targetType.toLowerCase();
  if (normalizedType == 'any') return;
  final widget = tester.widget<Widget>(finder);
  final matches = switch (normalizedType) {
    'form' => widget is Form,
    'image' => widget is Image,
    'text' => widget is Text,
    'input' => widget is TextField || widget is TextFormField,
    'button' =>
      widget is ButtonStyleButton ||
          widget is IconButton ||
          widget is FloatingActionButton ||
          widget is RawMaterialButton,
    'dialog' => widget is AlertDialog,
    'icon' => widget is Icon,
    'checkbox' => widget is Checkbox,
    'switch' => widget is Switch,
    'dropdown' => widget is DropdownButton,
    'padding' => widget is Padding,
    // Template dùng key vai trò screen.home có thể fallback vào Scaffold khi
    // bài không gắn ValueKey; README không bắt buộc sinh viên phải dùng key.
    'container' => widget is Container || widget is Scaffold,
    _ => false,
  };
  if (!matches) {
    fail(
      'Key $key đang gắn vào ${widget.runtimeType}, không phải targetType=$targetType',
    );
  }
}

bool? _enabledState(Widget widget) {
  if (widget is TextField) return widget.enabled;
  if (widget is TextFormField) return widget.enabled;
  if (widget is ButtonStyleButton) return widget.onPressed != null;
  if (widget is IconButton) return widget.onPressed != null;
  if (widget is FloatingActionButton) return widget.onPressed != null;
  if (widget is RawMaterialButton) return widget.onPressed != null;
  if (widget is Checkbox) return widget.onChanged != null;
  if (widget is Switch) return widget.onChanged != null;
  if (widget is DropdownButton) return widget.onChanged != null;
  return null;
}

String _decodeInput(String value) => value == '__EMPTY__' ? '' : value;

bool _bool(Map<String, dynamic> map, String key, bool fallback) {
  final value = map[key];
  if (value is bool) return value;
  return value == null ? fallback : value.toString().toLowerCase() == 'true';
}

void _assertNumber(
  double actual,
  double expected,
  double tolerance,
  String comparison,
  String reason,
) {
  if (expected.isNaN) fail('Thiếu giá trị số khi kiểm tra: $reason');
  final margin = tolerance < 0 ? 0 : tolerance;
  switch (comparison.isEmpty ? 'equals' : comparison.toLowerCase()) {
    case 'at_least':
      expect(actual, greaterThanOrEqualTo(expected - margin), reason: reason);
      return;
    case 'at_most':
      expect(actual, lessThanOrEqualTo(expected + margin), reason: reason);
      return;
    default:
      expect(actual, closeTo(expected, margin), reason: reason);
  }
}

FontWeight _fontWeight(String value) {
  const weights = <String, FontWeight>{
    'w100': FontWeight.w100,
    'w200': FontWeight.w200,
    'w300': FontWeight.w300,
    'w400': FontWeight.w400,
    'w500': FontWeight.w500,
    'w600': FontWeight.w600,
    'w700': FontWeight.w700,
    'w800': FontWeight.w800,
    'w900': FontWeight.w900,
  };
  return weights[value.toLowerCase()] ?? FontWeight.w400;
}

Finder _byKey(String key) {
  final exact = find.byKey(ValueKey<String>(key));
  if (exact.evaluate().isNotEmpty) return exact;
  if (_suiteBool('strict_semantic_keys', false)) return _notFound();

  // Legacy fallback chỉ được phép khi suite tắt strict_semantic_keys. Khi starter không ép ValueKey, dùng
  // fallback theo vai trò hiển thị để template vẫn đánh giá được hành vi.
  switch (key) {
    case 'screen.home':
    case 'screen.list':
      return find.byType(Scaffold, skipOffstage: false);
    case 'screen.detail':
      return find.text('User Detail', skipOffstage: false);
    case 'field.title':
    case 'field.name':
    case 'field.fullName':
      return _textFormFieldAt(0);
    case 'field.email':
      return _textFormFieldAt(1);
    case 'field.avatar':
      return find.byWidgetPredicate(
        (widget) => widget is DropdownButtonFormField,
        skipOffstage: false,
      );
    case 'action.save':
      return _buttonWithText(
        RegExp(
          r'^(add|create|save|submit|update|thêm|tạo|lưu|cập nhật)(\s+user)?$',
          caseSensitive: false,
        ),
      );
    case 'action.item.edit':
      return _buttonWithText(
        RegExp(r'^(edit|sửa|chỉnh sửa)$', caseSensitive: false),
      );
    case 'action.delete':
      return _buttonWithText(
        RegExp(r'^(delete|remove|xóa)$', caseSensitive: false),
      );
    case 'action.delete.cancel':
      return _buttonWithText(
        RegExp(r'^(cancel|no|hủy|đóng)$', caseSensitive: false),
      );
    case 'action.back':
      return _buttonWithText(
        RegExp(r'^(back|quay lại|trở về)$', caseSensitive: false),
      );
    case 'action.open-detail':
      return find.byType(ListTile, skipOffstage: false);
    case 'list.items':
      return find.byType(ListView, skipOffstage: false);
    case 'item.1':
      return _listTileAt(0);
    case 'item.2':
      return _listTileAt(1);
    case 'item.3':
      return _listTileAt(2);
    case 'dialog.delete':
      return find.byType(AlertDialog, skipOffstage: false);
    case 'message.success':
      return find.byWidgetPredicate(
        (widget) =>
            widget is Text &&
            RegExp(
              r'success|successful|saved|added|updated|thành công|đã thêm|đã cập nhật|đã lưu',
              caseSensitive: false,
            ).hasMatch(widget.data ?? ''),
        skipOffstage: false,
      );
    case 'state.empty':
      return find.byWidgetPredicate(
        (widget) =>
            widget is Text &&
            RegExp(
              r'no users|empty|chưa có|không có',
              caseSensitive: false,
            ).hasMatch(widget.data ?? ''),
        skipOffstage: false,
      );
    case 'state.loaded':
      return _listTileAt(0);
    case 'text.screen.title':
      final appBar = find.byType(AppBar, skipOffstage: false);
      return find.descendant(
        of: appBar,
        matching: find.byType(Text, skipOffstage: false),
        matchRoot: true,
      );
    default:
      if (key.startsWith('error.')) return _validationErrorFor(key);
      return _notFound();
  }
}

Finder _textFormFieldAt(int index) {
  final fields = find.byWidgetPredicate(
    (widget) => widget is TextFormField || widget is TextField,
    skipOffstage: false,
  );
  return fields.evaluate().length > index ? fields.at(index) : _notFound();
}

Finder _listTileAt(int index) {
  final items = find.byType(ListTile, skipOffstage: false);
  return items.evaluate().length > index ? items.at(index) : _notFound();
}

Finder _buttonWithText(RegExp pattern) {
  final labels = find.byWidgetPredicate(
    (widget) => widget is Text && pattern.hasMatch((widget.data ?? '').trim()),
    skipOffstage: false,
  );
  final buttons = find.ancestor(
    of: labels,
    matching: find.byWidgetPredicate(
      (widget) =>
          widget is ButtonStyleButton ||
          widget is IconButton ||
          widget is FloatingActionButton ||
          widget is RawMaterialButton,
      skipOffstage: false,
    ),
    matchRoot: true,
  );
  // Không âm thầm chọn nút đầu tiên khi label bị trùng. Testcase gọi hàm này
  // phải chứng minh target là duy nhất hoặc dùng semantic key chính xác.
  return buttons.evaluate().isNotEmpty ? buttons : _notFound();
}

Finder _validationErrorFor(String key) {
  final fieldName = key
      .replaceFirst(RegExp(r'^error[._-]?'), '')
      .replaceAll(RegExp(r'^(title|name|full_name)$'), 'fullName');
  final candidates = <String>[
    'field.$fieldName',
    'field.${fieldName.replaceAll('_', '-')}',
  ];
  Finder? field;
  for (final candidate in candidates) {
    final finder = _exactByKey(candidate);
    if (finder.evaluate().isNotEmpty) {
      field = finder;
      break;
    }
  }
  if (field == null) return _notFound();
  final errorWidgets = find.byWidgetPredicate((widget) {
    if (widget is InputDecorator) {
      return widget.decoration.errorText?.trim().isNotEmpty == true;
    }
    if (widget is Text) {
      return RegExp(
        r'required|minimum|min|invalid|error',
        caseSensitive: false,
      ).hasMatch(widget.data ?? '');
    }
    return false;
  }, skipOffstage: false);
  final scoped = find.descendant(
    of: field,
    matching: errorWidgets,
    matchRoot: true,
  );
  return scoped.evaluate().isNotEmpty ? scoped.first : _notFound();
}

Finder _notFound() => find.byWidgetPredicate((_) => false);

Map<String, dynamic> _suiteMap() => _activeSuite;

String _suiteText(String key, [String fallback = '']) =>
    _text(_suiteMap(), key, fallback);

bool _suiteBool(String key, bool fallback) => _bool(_suiteMap(), key, fallback);

double _suiteNumber(String key, double fallback) =>
    _number(_suiteMap(), key, fallback);

List<String> _suiteCsv(String key) {
  final value = _suiteMap()[key];
  if (value is List) {
    return value
        .map((item) => item.toString().trim())
        .where((item) => item.isNotEmpty)
        .toList();
  }
  return _text(_suiteMap(), key)
      .split(',')
      .map((item) => item.trim())
      .where((item) => item.isNotEmpty)
      .toList();
}

List<dynamic> _asList(dynamic value) => value is List ? value : <dynamic>[];

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

String _text(Map<String, dynamic> map, String key, [String fallback = '']) {
  final value = map[key];
  return value == null ? fallback : value.toString().trim();
}

List<String> _csv(Map<String, dynamic> map, String key) {
  final raw = map[key];
  if (raw is List) {
    return raw.map((value) => value == null ? '' : '$value'.trim()).toList();
  }
  final text = raw == null ? '' : '$raw'.trim();
  if (text.startsWith('[')) {
    try {
      final decoded = jsonDecode(text);
      if (decoded is List) {
        return decoded
            .map((value) => value == null ? '' : '$value'.trim())
            .toList();
      }
    } catch (_) {
      fail('$key phải là CSV hoặc JSON array hợp lệ.');
    }
  }
  return text
      .split(',')
      .map((value) => value.trim())
      .where((value) => value.isNotEmpty)
      .toList();
}

double _number(Map<String, dynamic> map, String key, double fallback) {
  final value = map[key];
  return value is num
      ? value.toDouble()
      : double.tryParse('$value') ?? fallback;
}
