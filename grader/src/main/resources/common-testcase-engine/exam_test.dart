import 'dart:convert';
import 'dart:io';
import 'dart:ui' show Size;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';

// Engine chung chỉ nhìn vào semantic key công khai, không import model/repository của bài.
import '../lib/main.dart' as student_app;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final matrix = _loadMatrix();

  for (final entry in matrix.entries) {
    final testId = entry.key;
    final metadata = _asMap(entry.value);
    // Testcase code tay được sinh thành testWidgets riêng, nhưng vẫn đăng ký ĐÚNG VỊ TRÍ
    // trong đề: một testcase chuẩn bị dữ liệu đặt đầu danh sách phải chạy trước phần sau.
    if (_text(metadata, 'runner') == 'CUSTOM_CODE') {
      _registerCustomTestcase(testId);
      continue;
    }
    testWidgets(testId, (tester) async {
      await _runCase(tester, testId, metadata);
    });
  }
}

// ─────────────────── CUSTOM_TESTCASES_BEGIN ───────────────────
// Vùng này do backend sinh lại mỗi lần lưu cấu hình testcase (phần "Tự viết code"
// của giáo viên). Sửa tay ở đây sẽ bị ghi đè ở lần lưu kế tiếp.
void _registerCustomTestcase(String testId) {}
// ──────────────────── CUSTOM_TESTCASES_END ────────────────────

Future<void> _runCase(
  WidgetTester tester,
  String testId,
  Map<String, dynamic> metadata,
) async {
  final runner = (metadata['runner'] ?? '').toString();
  final parameters = _asMap(metadata['parameters']);

  switch (runner) {
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
    case 'CUSTOM_CODE':
      // Không bao giờ chạy tới đây: testcase code tay được đăng ký riêng ở
      // _registerCustomTestcases. Nếu rơi vào đây nghĩa là file sinh ra bị lệch.
      fail('Testcase code tay $testId chưa được sinh vào exam_test.dart.');
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
  // SQLite FFI là I/O thật; gọi main trong FakeAsync khiến Future loadUsers không
  // được hoàn tất, còn pumpAndSettle thì chờ vô hạn vì CircularProgressIndicator.
  await tester.runAsync(() async {
    student_app.main();
    await tester.pump();
    await Future<void>.delayed(const Duration(milliseconds: 500));
  });
  await tester.pump();
  expect(tester.takeException(), isNull);
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
  // flutter_test kiểm tra SemanticsHandle còn sống NGAY khi thân test kết thúc, tức
  // TRƯỚC khi tearDown chạy. Dùng addTearDown(semantics.dispose) sẽ khiến testcase
  // luôn fail "A SemanticsHandle was active at the end of the test" dù bài làm đúng.
  final semantics = tester.ensureSemantics();
  try {
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

  // Hợp đồng bài làm (Khu vực 0) quyết định cách dò khi bài không gắn ValueKey.
  // Không có hợp đồng thì giữ nguyên heuristic cũ để đề cũ chấm lại vẫn ra đúng.
  final rule = _contractRule(key);
  if (_contractRequiresKeys() && !_ruleFlag(rule, 'allow_fallback')) return _notFound();
  if (rule != null) {
    final strategy = _text(rule, 'strategy', 'auto');
    if (strategy == 'key_only') return _notFound();
    if (strategy != 'auto') {
      final finder = _contractFinder(rule);
      if (finder != null) return finder;
    }
  }

  // Public contract của starter không ép ValueKey. Khi không có key, dùng
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
  // TextFormField DỰNG một TextField con, nên predicate "is TextFormField || is TextField"
  // đếm mỗi ô nhập hai lần → field.email (index 1) lại trỏ vào chính ô đầu tiên.
  // Chỉ đếm TextField: bài dùng TextFormField vẫn khớp qua TextField con, và mọi thao tác
  // (enterText, đọc EditableText, targetType='input') đều chạy đúng trên widget này.
  final fields = find.byType(TextField, skipOffstage: false);
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
  // Chỉ chấp nhận Text TRÔNG NHƯ thông báo lỗi. Trước đây nhánh "specific" chỉ khớp
  // tên field nên nhãn ô nhập ("Full Name", "Email") cũng bị tính là lỗi validation
  // → FORM_REQUIRED_FIELDS/FORM_VALIDATE_FIELDS pass giả dù bài không validate gì.
  final errorPattern = RegExp(
    r'required|minimum|at least|invalid|must|cannot|empty|not valid'
    r'|bắt buộc|tối thiểu|không hợp lệ|không được|vui lòng|hãy nhập|sai định dạng|lỗi',
    caseSensitive: false,
  );
  bool isError(Widget widget) =>
      widget is Text && errorPattern.hasMatch(widget.data ?? '');

  final all = find.byWidgetPredicate(isError, skipOffstage: false);
  final email = key.toLowerCase().contains('email');
  final fieldPattern = email
      ? RegExp(r'email|e-mail|định dạng', caseSensitive: false)
      : RegExp(r'name|họ|tên|full', caseSensitive: false);
  // Lỗi của đúng field: vừa là thông báo lỗi, vừa nhắc tới tên field.
  final specific = find.byWidgetPredicate(
    (widget) => isError(widget) && fieldPattern.hasMatch((widget as Text).data ?? ''),
    skipOffstage: false,
  );
  return specific.evaluate().isNotEmpty ? specific.first : all;
}

Finder _notFound() => find.byWidgetPredicate((_) => false);

// ─────────────────────── HỢP ĐỒNG BÀI LÀM (Khu vực 0) ───────────────────────
// Giáo viên khai mỗi semantic key được dò thế nào khi bài không gắn ValueKey.
// Trước đây cách dò bị hardcode trong _byKey nên bài dùng GridView/Card/nút icon
// (đúng đề nhưng khác giả định) bị chấm trượt oan.

Map<String, dynamic>? _contractCache;

Map<String, dynamic> _contract() {
  if (_contractCache != null) return _contractCache!;
  for (final path in <String>['test/contract.json', 'contract.json']) {
    final file = File(path);
    if (!file.existsSync()) continue;
    final value = jsonDecode(file.readAsStringSync());
    if (value is Map) return _contractCache = _asMap(value);
  }
  return _contractCache = <String, dynamic>{};
}

/// Đề bắt buộc sinh viên gắn ValueKey: bỏ hết cách dò thay thế, thiếu key là trượt.
bool _contractRequiresKeys() => _contract()['require_keys'] == true;

bool _ruleFlag(Map<String, dynamic>? rule, String key) =>
    rule != null && rule[key] == true;

Map<String, dynamic>? _contractRule(String key) {
  final keys = _contract()['keys'];
  if (keys is List) {
    for (final raw in keys) {
      final rule = _asMap(raw);
      if (_text(rule, 'key') == key) return rule;
    }
  } else if (keys is Map && keys[key] != null) {
    return _asMap(keys[key]);
  }
  return null;
}

Finder? _contractFinder(Map<String, dynamic> rule) {
  final strategy = _text(rule, 'strategy');
  final value = _text(rule, 'value');
  final index = _number(rule, 'index', 0).toInt();
  switch (strategy) {
    case 'widget_type':
      return _pickAt(_byTypeName(value), index);
    case 'icon':
      return _pickAt(_buttonOrSelf(_iconFinder(value)), index);
    case 'tooltip':
      return _pickAt(_buttonOrSelf(find.byTooltip(value, skipOffstage: false)), index);
    case 'text':
      return _pickAt(_textLike(value), index);
    case 'button_text':
      return _pickAt(_buttonOrSelf(_textLike(value)), index);
    case 'type_with_text':
      return _pickAt(
        find.ancestor(
          of: _textLike(_text(rule, 'text')),
          matching: _byTypeName(value),
          matchRoot: true,
        ),
        index,
      );
    default:
      return null;
  }
}

Finder _pickAt(Finder finder, int index) {
  // Thiếu phần tử thứ index thì phải BÁO KHÔNG TÌM THẤY. Nếu tự lùi về phần tử 0,
  // "item.1" sẽ trỏ vào chính card của form và testcase pass giả khi danh sách rỗng.
  final count = finder.evaluate().length;
  if (index < 0 || index >= count) return _notFound();
  return finder.at(index);
}

/// Khớp theo TÊN class để giáo viên gõ được 'SliverGrid', 'Card', 'InkWell'...
/// mà engine không cần map cứng từng loại widget.
Finder _byTypeName(String name) {
  final wanted = name.trim();
  if (wanted.isEmpty) return _notFound();
  return find.byWidgetPredicate((widget) {
    final actual = widget.runtimeType.toString();
    final base = actual.contains('<') ? actual.substring(0, actual.indexOf('<')) : actual;
    return base == wanted;
  }, skipOffstage: false);
}

/// Text đúng nội dung; bọc trong /.../ để dùng biểu thức chính quy.
Finder _textLike(String value) {
  final trimmed = value.trim();
  if (trimmed.isEmpty) return _notFound();
  final isRegex = trimmed.length > 2 && trimmed.startsWith('/') && trimmed.endsWith('/');
  final pattern = isRegex
      ? RegExp(trimmed.substring(1, trimmed.length - 1), caseSensitive: false)
      : RegExp('^${RegExp.escape(trimmed)}\$', caseSensitive: false);
  return find.byWidgetPredicate(
    (widget) => widget is Text && pattern.hasMatch((widget.data ?? '').trim()),
    skipOffstage: false,
  );
}

/// Nhóm icon theo Ý NGHĨA: chọn "Sửa (bút)" là khớp mọi biến thể edit/mode_edit/create,
/// vì đề chỉ yêu cầu "icon bút" chứ không chỉ định đúng một hằng Icons nào.
const Map<String, List<IconData>> _iconGroups = <String, List<IconData>>{
  'edit': <IconData>[
    Icons.edit, Icons.edit_outlined, Icons.edit_note, Icons.mode_edit,
    Icons.mode_edit_outlined, Icons.create, Icons.create_outlined,
    Icons.drive_file_rename_outline,
  ],
  'delete': <IconData>[
    Icons.delete, Icons.delete_outline, Icons.delete_forever, Icons.delete_rounded,
    Icons.remove_circle, Icons.remove_circle_outline,
  ],
  'add': <IconData>[
    Icons.add, Icons.add_circle, Icons.add_circle_outline, Icons.add_box,
    Icons.add_box_outlined, Icons.playlist_add,
  ],
  'save': <IconData>[
    Icons.save, Icons.save_outlined, Icons.save_alt, Icons.check,
    Icons.check_circle, Icons.check_circle_outline, Icons.done,
  ],
  'back': <IconData>[
    Icons.arrow_back, Icons.arrow_back_ios, Icons.arrow_back_ios_new,
    Icons.chevron_left, Icons.keyboard_arrow_left,
  ],
  'forward': <IconData>[
    Icons.arrow_forward, Icons.arrow_forward_ios, Icons.chevron_right,
    Icons.open_in_new, Icons.visibility, Icons.visibility_outlined,
  ],
  'close': <IconData>[Icons.close, Icons.cancel, Icons.cancel_outlined, Icons.clear],
  'search': <IconData>[Icons.search, Icons.manage_search],
  'person': <IconData>[
    Icons.person, Icons.person_outline, Icons.person_outlined,
    Icons.account_circle, Icons.account_circle_outlined,
  ],
  'email': <IconData>[
    Icons.email, Icons.email_outlined, Icons.mail, Icons.mail_outline,
    Icons.alternate_email,
  ],
  'image': <IconData>[
    Icons.image, Icons.image_outlined, Icons.photo, Icons.photo_outlined,
    Icons.add_photo_alternate, Icons.add_photo_alternate_outlined, Icons.camera_alt,
  ],
  'menu': <IconData>[Icons.menu, Icons.menu_open, Icons.more_vert, Icons.more_horiz],
};

Finder _iconFinder(String value) {
  final name = value.trim();
  final group = _iconGroups[name];
  final codePoint = group == null ? int.tryParse(name) : null;
  if (group == null && codePoint == null) return _notFound();
  return find.byWidgetPredicate((widget) {
    if (widget is! Icon) return false;
    final icon = widget.icon;
    if (icon == null) return false;
    if (codePoint != null) return icon.codePoint == codePoint;
    return group!.any((candidate) =>
        candidate.codePoint == icon.codePoint && candidate.fontFamily == icon.fontFamily);
  }, skipOffstage: false);
}

/// Nút bọc ngoài (nếu có) để targetType='button' và tap() đều đúng đối tượng.
Finder _buttonOrSelf(Finder inner) {
  if (inner.evaluate().isEmpty) return inner;
  // Nút thật phải được ưu tiên: mỗi button Material tự dựng một InkWell BÊN TRONG nó,
  // nên lấy ancestor gần nhất sẽ ra InkWell chứ không phải FilledButton/IconButton.
  for (final matcher in <bool Function(Widget)>[_isRealButton, _isTappable]) {
    final found = find.ancestor(
      of: inner,
      matching: find.byWidgetPredicate(matcher, skipOffstage: false),
      matchRoot: true,
    );
    if (found.evaluate().isNotEmpty) return found;
  }
  return inner;
}

bool _isRealButton(Widget widget) =>
    widget is ButtonStyleButton ||
    widget is IconButton ||
    widget is FloatingActionButton ||
    widget is RawMaterialButton;

bool _isTappable(Widget widget) => widget is InkWell || widget is GestureDetector;

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
