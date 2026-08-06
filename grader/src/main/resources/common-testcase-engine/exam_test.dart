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

  for (final entry in matrix.entries) {
    final testId = entry.key;
    final metadata = _asMap(entry.value);
    testWidgets(testId, (tester) async {
      await _runCase(tester, testId, metadata);
    });
  }
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
      if (rootKey.isNotEmpty) expect(_byKey(rootKey), findsOneWidget);
      expect(tester.takeException(), isNull);
      return;
    case 'WIDGET_VISIBLE':
      await _boot(tester);
      final widgetKey = _requiredText(parameters, 'widgetKey');
      final widgetFinder = _byKey(widgetKey);
      expect(widgetFinder, findsOneWidget, reason: 'Không tìm thấy widget key: $widgetKey');
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
      for (final key in _csv(parameters, 'fieldKeys')) {
        expect(_byKey(key), findsOneWidget, reason: 'Thiếu field semantic key: $key');
      }
      final submitKey = _text(parameters, 'submitKey');
      expect(_byKey(submitKey), findsOneWidget, reason: 'Thiếu submit key: $submitKey');
      await tester.tap(_byKey(submitKey));
      await _settle(tester);
      for (final key in _csv(parameters, 'errorKeys')) {
        expect(_byKey(key), findsOneWidget, reason: 'Thiếu error key: $key');
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
      await tester.tap(_byKey(openKey));
      await _settle(tester);
      expect(_byKey(destinationKey), findsOneWidget);
      final backKey = _text(parameters, 'backKey');
      final homeKey = _text(parameters, 'homeKey');
      if (backKey.isNotEmpty && homeKey.isNotEmpty) {
        await tester.tap(_byKey(backKey));
        await _settle(tester);
        expect(_byKey(homeKey), findsOneWidget);
      }
      return;
    case 'LIST_VISIBLE':
      await _boot(tester);
      final listKey = _text(parameters, 'listKey');
      expect(_byKey(listKey), findsOneWidget);
      for (final key in _csv(parameters, 'itemKeys')) {
        expect(_byKey(key), findsOneWidget, reason: 'Thiếu item semantic key: $key');
      }
      return;
    case 'BUTTON_ACTION':
      await _boot(tester);
      final buttonKey = _text(parameters, 'buttonKey');
      final resultKey = _text(parameters, 'resultKey');
      await tester.tap(_byKey(buttonKey));
      await _settle(tester);
      expect(_byKey(resultKey), findsOneWidget);
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

  final expectedType = _text(parameters, 'expectedType', 'string').toLowerCase();
  final expectedRaw = _text(parameters, 'expectedValue');
  final expected = _parseDirectExpected(expectedRaw, expectedType);
  final matchMode = _text(parameters, 'matchMode', 'equals').toLowerCase();
  if (matchMode == 'contains') {
    expect(actual.toString(), contains(expected.toString()),
        reason: '$functionName không chứa giá trị mong đợi');
  } else {
    // equals() so sánh sâu List/Map; expect(actual, expected) chỉ so sánh cùng object.
    expect(actual, equals(expected), reason: '$functionName trả về giá trị không đúng');
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
    final symbols = _asList(contract['symbols']).map((value) => value.toString()).toList();
    final file = File(path);
    expect(file.existsSync(), isTrue, reason: 'Khong tim thay source contract $path');
    final source = file.readAsStringSync();
    for (final symbol in symbols) {
      final escaped = RegExp.escape(symbol);
      final declaration = RegExp('\\b(?:class|mixin|enum|extension|typedef)\\s+$escaped\\b|\\b(?:final|const|var|late)\\s+$escaped\\b|\\b$escaped\\s*\\(');
      expect(declaration.hasMatch(source), isTrue, reason: 'Thieu $type symbol $symbol trong $path');
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
    fail('Nhóm ${metadata['name'] ?? groupId} thất bại vì assert con không đạt:\n'
        '${failures.join('\n')}');
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
  expect(_byKey(initialKey), findsOneWidget,
      reason: 'Thiếu state ban đầu: $initialKey');
  await tester.tap(_byKey(actionKey));
  await _settle(tester);
  expect(_byKey(updatedKey), findsOneWidget,
      reason: 'State không cập nhật sau action $actionKey: $updatedKey');
  if (absentKey.isNotEmpty) {
    expect(_byKey(absentKey), findsNothing,
        reason: 'State cũ vẫn còn sau action $actionKey: $absentKey');
  }
}

Future<void> _boot(WidgetTester tester) async {
  // Mỗi testcase tự khởi động lại app rồi áp dụng fixture/setup UI chung.
  await tester.runAsync(() async {
    student_app.main();
    await Future<void>.delayed(const Duration(milliseconds: 500));
  });
  await tester.pump();
  expect(tester.takeException(), isNull);
  await _applySuiteSetup(tester);
}

Future<void> _applySuiteSetup(WidgetTester tester) async {
  final requiredKeys = _suiteCsv('required_keys');
  for (final key in requiredKeys) {
    expect(_byKey(key), findsOneWidget,
        reason: 'Suite yêu cầu semantic key nhưng không tìm thấy: $key');
  }
  final readyKey = _suiteText('ready_key');
  if (readyKey.isNotEmpty) await _waitForVisible(tester, readyKey, _suiteNumber('boot_timeout_ms', 3000));
  await _runSetupSteps(tester, _asList(_activeSuite['setup_steps']), 'suite');
  await _runSetupSteps(tester, _asList(_activeCase['setup_steps']), 'testcase');
}

Future<void> _runSetupSteps(WidgetTester tester, List<dynamic> rawSteps, String owner) async {
  var index = 1;
  for (final raw in rawSteps) {
    final step = _asMap(raw);
    final type = _text(step, 'type').toLowerCase();
    final key = _requiredText(step, 'key');
    final timeout = _number(step, 'timeout_ms', _suiteNumber('step_timeout_ms', 2000));
    switch (type) {
      case 'tap':
        expect(_byKey(key), findsOneWidget, reason: 'Setup $owner #$index thiếu key: $key');
        await tester.tap(_byKey(key));
        await _settle(tester);
        break;
      case 'enter_text':
        final finder = _byKey(key);
        expect(finder, findsOneWidget, reason: 'Setup $owner #$index thiếu field: $key');
        await tester.enterText(finder, _decodeInput(_text(step, 'value')));
        await _settle(tester);
        break;
      case 'expect_visible':
        expect(_byKey(key), findsOneWidget, reason: 'Setup $owner #$index cần thấy key: $key');
        break;
      case 'expect_absent':
        expect(_byKey(key), findsNothing, reason: 'Setup $owner #$index cần ẩn key: $key');
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

Future<void> _waitForVisible(WidgetTester tester, String key, int timeoutMs) async {
  final deadline = DateTime.now().add(Duration(milliseconds: timeoutMs));
  while (DateTime.now().isBefore(deadline)) {
    if (_byKey(key).evaluate().isNotEmpty) return;
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pump();
  }
  expect(_byKey(key), findsOneWidget, reason: 'Không xuất hiện semantic key sau khi chờ: $key');
}

Future<void> _settle(WidgetTester tester) async {
  // Chờ có giới hạn để animation/loading nền không khóa cả batch chấm.
  await tester.runAsync(() async {
    await Future<void>.delayed(const Duration(milliseconds: 300));
  });
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
  expect(portraitFinder, findsOneWidget,
      reason: 'Thiếu target ở portrait: $targetKey');
  _assertTargetType(tester, portraitFinder, targetKey, targetType);

  tester.view.physicalSize = landscape;
  await _settle(tester);
  expect(tester.takeException(), isNull);
  final landscapeFinder = _byKey(targetKey);
  expect(landscapeFinder, findsOneWidget,
      reason: 'Thiếu target ở landscape: $targetKey');
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
  _assertNumber(actual, expected, tolerance, _text(parameters, 'comparison'),
      '$key ($targetType) có $dimension=$actual, mong đợi $expected');
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
    expect(actual, contains(expected), reason: 'Nội dung $key không chứa expectedText');
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
  expect(enabled, _bool(parameters, 'expectedEnabled', true),
      reason: 'Trạng thái enabled của $key không đúng');
}

Future<void> _checkFormValidateFields(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final fields = _csv(parameters, 'fieldKeys');
  final values = _csv(parameters, 'invalidValues');
  final errors = _csv(parameters, 'errorKeys');
  if (fields.isEmpty || fields.length != values.length || fields.length != errors.length) {
    fail('fieldKeys, invalidValues và errorKeys phải có cùng số phần tử.');
  }
  final fieldType = _text(parameters, 'fieldType', 'input');
  for (var i = 0; i < fields.length; i++) {
    final fieldFinder = _byKey(fields[i]);
    expect(fieldFinder, findsOneWidget, reason: 'Thiếu field key: ${fields[i]}');
    _assertTargetType(tester, fieldFinder, fields[i], fieldType);
    await tester.enterText(fieldFinder, _decodeInput(values[i]));
  }
  final submitKey = _requiredText(parameters, 'submitKey');
  await tester.tap(_byKey(submitKey));
  await _settle(tester);
  for (final errorKey in errors) {
    expect(_byKey(errorKey), findsOneWidget,
        reason: 'Thiếu lỗi validation key: $errorKey');
  }
}

Future<void> _checkListItemCount(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final listKey = _requiredText(parameters, 'listKey');
  final listFinder = _byKey(listKey);
  expect(listFinder, findsOneWidget, reason: 'Không tìm thấy list key: $listKey');
  final itemKeys = _csv(parameters, 'itemKeys');
  if (itemKeys.isEmpty) fail('Thiếu itemKeys khi kiểm tra list.');
  var count = 0;
  for (final itemKey in itemKeys) {
    count += find.descendant(of: listFinder, matching: _byKey(itemKey)).evaluate().length;
  }
  expect(count, _number(parameters, 'expectedCount', double.nan).toInt(),
      reason: 'Số item trong $listKey không đúng');
}

Future<void> _checkFormPrefill(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final editKey = _requiredText(parameters, 'editKey');
  await tester.tap(_byKey(editKey));
  await _settle(tester);
  final fields = _csv(parameters, 'fieldKeys');
  final expectedValues = _csv(parameters, 'expectedValues');
  if (fields.isEmpty || fields.length != expectedValues.length) {
    fail('fieldKeys và expectedValues phải có cùng số phần tử.');
  }
  final fieldType = _text(parameters, 'fieldType', 'input');
  for (var i = 0; i < fields.length; i++) {
    final fieldFinder = _byKey(fields[i]);
    expect(fieldFinder, findsOneWidget, reason: 'Thiếu field key: ${fields[i]}');
    _assertTargetType(tester, fieldFinder, fields[i], fieldType);
    final editable = find.descendant(of: fieldFinder, matching: find.byType(EditableText));
    expect(editable, findsOneWidget, reason: 'Field ${fields[i]} không phải editable input');
    expect(tester.widget<EditableText>(editable).controller.text, _decodeInput(expectedValues[i]),
        reason: 'Field ${fields[i]} không được prefill đúng');
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
    expect(fieldFinder, findsOneWidget, reason: 'Thiếu field key: ${fields[i]}');
    _assertTargetType(tester, fieldFinder, fields[i], fieldType);
    await tester.enterText(fieldFinder, _decodeInput(values[i]));
  }
  await tester.tap(_byKey(_requiredText(parameters, 'submitKey')));
  await _settle(tester);
  final resultKey = _text(parameters, 'resultKey');
  if (resultKey.isNotEmpty) expect(_byKey(resultKey), findsOneWidget);
  for (final errorKey in _csv(parameters, 'errorKeys')) {
    expect(_byKey(errorKey), findsNothing,
        reason: 'Dữ liệu hợp lệ nhưng vẫn còn error key: $errorKey');
  }
}

Future<void> _checkDialogFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final actionKey = _requiredText(parameters, 'actionKey');
  await tester.tap(_byKey(actionKey));
  await _settle(tester);

  final dialogKey = _requiredText(parameters, 'dialogKey');
  final dialogFinder = _byKey(dialogKey);
  expect(dialogFinder, findsOneWidget, reason: 'Không tìm thấy dialog key: $dialogKey');
  _assertTargetType(tester, dialogFinder, dialogKey, 'dialog');

  final decisionKey = _requiredText(parameters, 'decisionKey');
  await tester.tap(_byKey(decisionKey));
  await _settle(tester);
  final resultKey = _text(parameters, 'resultKey');
  if (resultKey.isNotEmpty) expect(_byKey(resultKey), findsOneWidget);
  final absentKey = _text(parameters, 'absentKey');
  if (absentKey.isNotEmpty) expect(_byKey(absentKey), findsNothing);
}

Future<void> _checkWidgetSemanticsLabel(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  final semantics = tester.ensureSemantics();
  addTearDown(semantics.dispose);
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _text(parameters, 'targetType', 'any');
  final finder = _byKey(key);
  expect(finder, findsOneWidget, reason: 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);
  final actual = tester.getSemantics(finder).label;
  final expected = _requiredText(parameters, 'expectedLabel');
  final matchMode = _text(parameters, 'matchMode', 'equals').toLowerCase();
  if (matchMode == 'contains') {
    expect(actual, contains(expected), reason: 'Semantics label của $key không chứa expectedLabel');
  } else {
    expect(actual, expected, reason: 'Semantics label của $key không đúng');
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
  _assertNumber(padding.left, _number(parameters, 'left', double.nan), tolerance,
      'equals', '$key left padding');
  _assertNumber(padding.top, _number(parameters, 'top', double.nan), tolerance,
      'equals', '$key top padding');
  _assertNumber(padding.right, _number(parameters, 'right', double.nan), tolerance,
      'equals', '$key right padding');
  _assertNumber(padding.bottom, _number(parameters, 'bottom', double.nan), tolerance,
      'equals', '$key bottom padding');
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
  final resolved = DefaultTextStyle.of(tester.element(finder))
      .style
      .merge(text.style);
  final tolerance = _number(parameters, 'tolerance', 0.5);
  final expectedSize = _number(parameters, 'fontSize', double.nan);
  if (!expectedSize.isNaN) {
    _assertNumber(resolved.fontSize ?? double.nan, expectedSize, tolerance,
        'equals', '$key fontSize');
  }
  final expectedWeight = _text(parameters, 'fontWeight');
  if (expectedWeight.isNotEmpty) {
    expect(resolved.fontWeight, _fontWeight(expectedWeight),
        reason: '$key fontWeight không đúng');
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
  expect(fromFinder, findsOneWidget, reason: 'Không tìm thấy fromKey: $fromKey');
  expect(toFinder, findsOneWidget, reason: 'Không tìm thấy toKey: $toKey');
  final fromType = _text(parameters, 'fromType');
  final toType = _text(parameters, 'toType');
  if (fromType.isNotEmpty) _assertTargetType(tester, fromFinder, fromKey, fromType);
  if (toType.isNotEmpty) _assertTargetType(tester, toFinder, toKey, toType);

  final axis = _text(parameters, 'axis').toLowerCase();
  final from = tester.getRect(fromFinder);
  final to = tester.getRect(toFinder);
  final actual = axis == 'horizontal' ? to.left - from.right : to.top - from.bottom;
  _assertNumber(actual, _number(parameters, 'expectedGap', double.nan),
      _number(parameters, 'tolerance', 0.5), 'equals',
      'Khoảng cách $fromKey → $toKey');
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
    'button' => widget is ButtonStyleButton ||
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
    fail('Key $key đang gắn vào ${widget.runtimeType}, không phải targetType=$targetType');
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
  final exact = find.byKey(ValueKey<String>(key), skipOffstage: false);
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
      return _buttonWithText(RegExp(
        r'^(add|create|save|submit|update|thêm|tạo|lưu|cập nhật)(\s+user)?$',
        caseSensitive: false,
      ));
    case 'action.item.edit':
      return _buttonWithText(RegExp(r'^(edit|sửa|chỉnh sửa)$', caseSensitive: false));
    case 'action.delete':
      return _buttonWithText(RegExp(r'^(delete|remove|xóa)$', caseSensitive: false));
    case 'action.delete.cancel':
      return _buttonWithText(RegExp(r'^(cancel|no|hủy|đóng)$', caseSensitive: false));
    case 'action.back':
      return _buttonWithText(RegExp(r'^(back|quay lại|trở về)$', caseSensitive: false));
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
        (widget) => widget is Text && RegExp(
          r'success|successful|saved|added|updated|thành công|đã thêm|đã cập nhật|đã lưu',
          caseSensitive: false,
        ).hasMatch(widget.data ?? ''),
        skipOffstage: false,
      );
    case 'state.empty':
      return find.byWidgetPredicate(
        (widget) => widget is Text && RegExp(
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
      (widget) => widget is ButtonStyleButton ||
          widget is IconButton ||
          widget is FloatingActionButton ||
          widget is RawMaterialButton,
      skipOffstage: false,
    ),
    matchRoot: true,
  );
  return buttons.evaluate().isNotEmpty ? buttons.first : _notFound();
}

Finder _validationErrorFor(String key) {
  final all = find.byWidgetPredicate(
    (widget) => widget is Text && RegExp(
      r'required|minimum|min|invalid|bắt buộc|tối thiểu|không hợp lệ|lỗi',
      caseSensitive: false,
    ).hasMatch(widget.data ?? ''),
    skipOffstage: false,
  );
  final email = key.toLowerCase().contains('email');
  final specific = find.byWidgetPredicate(
    (widget) => widget is Text && (email
        ? RegExp(r'email|e-mail|định dạng', caseSensitive: false)
            .hasMatch(widget.data ?? '')
        : RegExp(r'name|họ|tên|full', caseSensitive: false)
            .hasMatch(widget.data ?? '')),
    skipOffstage: false,
  );
  return specific.evaluate().isNotEmpty ? specific.first : all;
}

Finder _notFound() => find.byWidgetPredicate((_) => false);

Map<String, dynamic> _suiteMap() => _activeSuite;

String _suiteText(String key, [String fallback = '']) => _text(_suiteMap(), key, fallback);

bool _suiteBool(String key, bool fallback) => _bool(_suiteMap(), key, fallback);

double _suiteNumber(String key, double fallback) => _number(_suiteMap(), key, fallback);

List<String> _suiteCsv(String key) {
  final value = _suiteMap()[key];
  if (value is List) {
    return value.map((item) => item.toString().trim()).where((item) => item.isNotEmpty).toList();
  }
  return _text(_suiteMap(), key).split(',').map((item) => item.trim()).where((item) => item.isNotEmpty).toList();
}

List<dynamic> _asList(dynamic value) => value is List ? value : <dynamic>[];

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

String _text(Map<String, dynamic> map, String key, [String fallback = '']) {
  final value = map[key];
  return value == null ? fallback : value.toString().trim();
}

List<String> _csv(Map<String, dynamic> map, String key) => _text(map, key)
    .split(',')
    .map((value) => value.trim())
    .where((value) => value.isNotEmpty)
    .toList();

double _number(Map<String, dynamic> map, String key, double fallback) {
  final value = map[key];
  return value is num ? value.toDouble() : double.tryParse('$value') ?? fallback;
}
