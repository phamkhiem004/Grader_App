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
  final runner = (metadata['runner'] ?? '').toString();
  final parameters = _asMap(metadata['parameters']);

  switch (runner) {
    case 'APP_BOOT':
      await _boot(tester);
      final rootKey = _text(parameters, 'rootKey');
      if (rootKey.isNotEmpty) {
        _expectPresent(_byKey(rootKey), 'screen',
            'Không tìm thấy màn hình gốc: $rootKey');
      }
      expect(tester.takeException(), isNull);
      return;
    case 'WIDGET_VISIBLE':
      await _boot(tester);
      final widgetKey = _requiredText(parameters, 'widgetKey');
      final widgetFinder = _byKey(widgetKey);
      _expectPresent(widgetFinder, _subject(parameters),
          'Không tìm thấy widget key: $widgetKey');
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
        _expectPresent(_byKey(key), 'field', 'Thiếu field semantic key: $key');
      }
      final submitKey = _text(parameters, 'submitKey');
      _expectPresent(_byKey(submitKey), 'button', 'Thiếu submit key: $submitKey');
      await tester.tap(_byKey(submitKey));
      await _settle(tester);
      for (final key in _csv(parameters, 'errorKeys')) {
        _expectPresent(_byKey(key), 'error', 'Thiếu error key: $key',
            where: 'after_action');
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
      _expectPresent(_byKey(destinationKey), 'screen',
          'Không mở được màn hình đích: $destinationKey', where: 'after_action');
      final backKey = _text(parameters, 'backKey');
      final homeKey = _text(parameters, 'homeKey');
      if (backKey.isNotEmpty && homeKey.isNotEmpty) {
        await tester.tap(_byKey(backKey));
        await _settle(tester);
        _expectPresent(_byKey(homeKey), 'screen',
            'Sau khi quay lại, không thấy màn hình trước: $homeKey', where: 'after_action');
        // Phải kiểm cả chiều BIẾN MẤT. Một mình `homeKey` là phép kiểm KHÔNG THỂ HỎNG:
        // màn hình trước vẫn nằm trong cây widget suốt lúc màn hình sau đang mở, nên nút
        // quay lại có bấm được hay không thì nó vẫn đạt. Chính chỗ này từng che lỗi
        // `_settle` không đẩy đồng hồ ảo, làm cả một lỗi chấm sai điểm không ai thấy.
        _expectGone(_goneByKey(destinationKey), 'screen',
            'Bấm quay lại nhưng màn hình $destinationKey vẫn đang hiển thị',
            where: 'after_action');
      }
      return;
    case 'LIST_VISIBLE':
      await _boot(tester);
      final listKey = _text(parameters, 'listKey');
      _expectPresent(_byKey(listKey), 'list', 'Không tìm thấy list key: $listKey');
      for (final key in _csv(parameters, 'itemKeys')) {
        _expectPresent(_byKey(key), 'item', 'Thiếu item semantic key: $key');
      }
      return;
    case 'BUTTON_ACTION':
      await _boot(tester);
      final buttonKey = _text(parameters, 'buttonKey');
      final resultKey = _text(parameters, 'resultKey');
      await tester.tap(_byKey(buttonKey));
      await _settle(tester);
      _expectPresent(_byKey(resultKey), _subject(parameters),
          'Bấm xong nhưng không thấy kết quả: $resultKey', where: 'after_action');
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
      // Nêu TÊN yêu cầu con (tiếng Việt, giáo viên nhập) chứ không nêu instance_id:
      // chuỗi này chảy vào result_json nên không được lộ định danh nội bộ.
      failures.add('${_text(child, 'name', 'Yêu cầu con')}: $error');
    }
  }

  if (failures.isNotEmpty) {
    // Không dùng chữ "assert" và không đếm số testcase con — từ vựng nội bộ của hệ thống
    // chấm, sinh viên không kiểm chứng được (cùng lý do đã sửa `expected` của GROUP).
    fail('Chưa đạt yêu cầu "${metadata['name'] ?? groupId}":\n${failures.join('\n')}');
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
  _expectPresent(_byKey(initialKey), 'widget', 'Thiếu state ban đầu: $initialKey');
  await tester.tap(_byKey(actionKey));
  await _settle(tester);
  _expectPresent(_byKey(updatedKey), 'widget',
      'State không cập nhật sau action $actionKey: $updatedKey', where: 'after_action');
  if (absentKey.isNotEmpty) {
    _expectGone(_goneByKey(absentKey), 'widget',
        'State cũ vẫn còn sau action $actionKey: $absentKey', where: 'after_action');
  }
}

/// KÊNH QUAN SÁT CÓ CẤU TRÚC (SPEC mục 5.3) — nguồn của `actual` trong result.json.
///
/// Runner là nơi DUY NHẤT biết chính xác nó kiểm gì và thấy gì trước khi assertion nổ. Nó phát
/// ra dữ liệu MÁY ĐỌC ở đây; toàn bộ việc diễn ra tiếng Việt do **backend** làm.
///
/// Vì sao render ở backend chứ không ở đây: file này bị chép ĐÓNG BĂNG vào thư mục testcase của
/// từng đề lúc publish. Câu chữ để trong này thì sửa một chữ cũng phải nâng engine cho mọi đề đã
/// publish; để ở backend thì sửa một lần là đề cũ lẫn đề mới đều hưởng.
///
/// LUẬT NỘI DUNG (SPEC mục 5.4) — payload chỉ được chứa:
///  - `kind`: loại quan sát (bảng đóng, xem `_ObsKind` phía backend);
///  - `subject`: loại thành phần theo cách SINH VIÊN hiểu (`field`, `button`, `list`, `item`,
///    `dialog`, `screen`, `error`, `text`) — **KHÔNG BAO GIỜ** là semantic key nội bộ;
///  - số đếm / giá trị QUAN SÁT ĐƯỢC (`expected`, `found`, `seen`, `unit`, `dimension`);
///  - `where`: bối cảnh kiểm (`portrait`, `landscape`, `after_action`…).
///
/// Tuyệt đối không đưa `ValueKey`, tên hàm test hay số thứ tự test vào đây.
const String kObservationMarker = '###GRADER_OBS###';

void _observe(
  String kind, {
  String? subject,
  num? expected,
  num? found,
  String? seen,
  String? unit,
  String? dimension,
  String? where,
}) {
  final payload = <String, dynamic>{'kind': kind};
  void put(String key, Object? value) {
    if (value != null) payload[key] = value;
  }

  put('subject', subject);
  put('expected', expected);
  put('found', found);
  // Chữ NGƯỜI DÙNG THẤY trên màn hình được phép in (SPEC 5.4); cắt ngắn cho gọn JSON.
  put('seen', seen == null ? null : (seen.length > 80 ? '${seen.substring(0, 80)}…' : seen));
  put('unit', unit);
  put('dimension', dimension);
  put('where', where);
  print('$kObservationMarker${jsonEncode(payload)}');
}

/// Loại thành phần theo cách SINH VIÊN hiểu, suy từ `targetType` mà template đã khai.
///
/// Cố ý KHÔNG suy từ semantic key: key là định danh nội bộ của hệ thống chấm
/// (`field.name`, `action.save`), sinh viên không kiểm chứng được nên không được lộ ra.
String _subject(Map<String, dynamic> parameters, [String fallback = 'widget']) {
  const known = <String>{
    'button', 'text', 'input', 'field', 'list', 'item', 'dialog', 'screen', 'image', 'icon',
  };
  final type = _text(parameters, 'targetType').toLowerCase();
  return known.contains(type) ? type : fallback;
}

/// Bọc phép kiểm "PHẢI CÓ": phát quan sát trước, rồi assert Y NGUYÊN như cũ.
///
/// Quan sát chỉ là hiệu ứng lề — assertion không đổi một chữ nên **điểm không thể lệch**. Đó là
/// cổng bắt buộc của P5 và là lý do chọn cách bọc thay vì viết lại từng runner.
void _expectPresent(Finder finder, String subject, String reason, {String? where}) {
  final found = finder.evaluate().length;
  if (found != 1) {
    _observe('MISSING', subject: subject, expected: 1, found: found, where: where);
  }
  expect(finder, findsOneWidget, reason: reason);
}

/// Bọc phép kiểm "PHẢI BIẾN MẤT". Luôn dùng [_goneByKey] cho finder, xem lý do ở đó.
void _expectGone(Finder finder, String subject, String reason, {String? where}) {
  final found = finder.evaluate().length;
  if (found != 0) {
    _observe('STILL_PRESENT', subject: subject, expected: 0, found: found, where: where);
  }
  expect(finder, findsNothing, reason: reason);
}

/// Dấu hiệu MÁY ĐỌC cho `grader.dart`: khung hình đầu tiên của ứng dụng không dựng được,
/// nên runner **chưa chạy tới phần khẳng định của chính nó** ⇒ `status: not_run`.
///
/// Đây là *cơ chế phụ thuộc thật lúc chạy* mà SPEC mục 4.1 đòi hỏi, KHÔNG phải heuristic:
/// mọi runner của engine chung đều gọi `_boot()` trước khi kiểm phần của mình, nên lỗi xảy ra
/// BÊN TRONG `_boot()` là bằng chứng chắc chắn — khác hẳn kiểu suy đoán "test tầng cao hỏng
/// sau test tầng thấp thì coi là bị chặn".
///
/// PHẢI khớp hằng cùng tên trong `grader.dart` (hai chương trình Dart riêng, không import nhau).
const String kBootFailedMarker = '###GRADER_BOOT_FAILED###';

Future<void> _boot(WidgetTester tester) async {
  // SQLite FFI là I/O thật; gọi main trong FakeAsync khiến Future loadUsers không
  // được hoàn tất, còn pumpAndSettle thì chờ vô hạn vì CircularProgressIndicator.
  await tester.runAsync(() async {
    student_app.main();
    await tester.pump();
    await Future<void>.delayed(const Duration(milliseconds: 500));
  });
  await tester.pump();
  final exception = tester.takeException();
  // In TRƯỚC khi assertion ném: sau đó không còn cơ hội in nữa.
  if (exception != null) {
    print(kBootFailedMarker);
    _observe('BOOT_FAILED');
  }
  // Giữ nguyên dạng `expect(..., isNull)`: backend đã có luật đọc `Actual:` là đối tượng lỗi
  // Dart và đổi thành câu tiếng Việt, đừng tự nhét exception vào thông điệp fail.
  expect(exception, isNull);
}

Future<void> _settle(WidgetTester tester) async {
  // Phải chờ HAI loại thời gian khác nhau, thiếu một loại là chấm sai điểm:
  //
  //  - `runAsync`: I/O THẬT (SQLite FFI) chỉ chạy được ngoài đồng hồ ảo của test.
  //  - ĐẨY đồng hồ ảo: hoạt ảnh chuyển route chỉ chạy khi đồng hồ ảo tiến. `pump()` trần
  //    không đẩy đồng hồ, nên hoạt ảnh không bao giờ kết thúc: lớp phủ chuyển cảnh còn nguyên,
  //    `tester.tap` vào nút trên AppBar màn hình mới bị chắn và chỉ ghi một dòng cảnh báo —
  //    sinh viên làm đúng vẫn mất điểm.
  //
  await tester.runAsync(() async {
    await Future<void>.delayed(const Duration(milliseconds: 300));
  });

  try {
    // `pumpAndSettle` CÓ GIỚI HẠN: đúng cơ chế cần (chạy tới khi không còn frame nào được
    // xếp lịch), nhưng phải chặn timeout kẻo bài có loading/animation không dứt treo cả
    // batch chấm. Vài nhịp `pump(Duration)` cố định là KHÔNG đủ: hoạt ảnh pop route còn
    // ticker đang chạy, màn hình cũ chưa bị gỡ khỏi cây widget.
    await tester.pumpAndSettle(const Duration(milliseconds: 50),
        EnginePhase.sendSemanticsUpdate, const Duration(seconds: 2));
  } catch (_) {
    // Không lặng được trong hạn: vẫn đẩy đồng hồ một nhịp để không đứng im tại t=0.
    await tester.pump(const Duration(milliseconds: 350));
  }
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
  _expectNoLayoutError(tester, where: 'portrait');

  tester.view.physicalSize = landscape;
  await _settle(tester);
  _expectNoLayoutError(tester, where: 'landscape');
}

/// Không có lỗi bố cục ở kích thước đang kiểm.
///
/// Tràn khung là điều QUAN SÁT ĐƯỢC: sinh viên mở máy ở đúng kích thước đó là thấy vệt vàng.
/// Nên chỉ cần nói *tràn ở kích thước nào*, không cần dán log tiếng Anh.
void _expectNoLayoutError(WidgetTester tester, {required String where}) {
  final exception = tester.takeException();
  if (exception != null) {
    final text = exception.toString().toLowerCase();
    _observe(text.contains('overflow') ? 'OVERFLOW' : 'LAYOUT_ERROR', where: where);
  }
  expect(exception, isNull);
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
  _expectNoLayoutError(tester, where: 'portrait');
  final portraitFinder = _byKey(targetKey);
  _expectPresent(portraitFinder, _subject(parameters),
      'Thiếu target ở portrait: $targetKey', where: 'portrait');
  _assertTargetType(tester, portraitFinder, targetKey, targetType);

  tester.view.physicalSize = landscape;
  await _settle(tester);
  _expectNoLayoutError(tester, where: 'landscape');
  final landscapeFinder = _byKey(targetKey);
  _expectPresent(landscapeFinder, _subject(parameters),
      'Thiếu target ở landscape: $targetKey', where: 'landscape');
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
  _expectPresent(finder, _subject(parameters), 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);

  final dimension = _text(parameters, 'dimension').toLowerCase();
  final expected = _number(parameters, 'expected', double.nan);
  final tolerance = _number(parameters, 'tolerance', 0.5);
  final size = tester.getSize(finder);
  final actual = dimension == 'width' ? size.width : size.height;
  _assertNumber(actual, expected, tolerance, _text(parameters, 'comparison'),
      '$key ($targetType) có $dimension=$actual, mong đợi $expected',
      dimension: dimension == 'width' ? 'chiều rộng' : 'chiều cao');
}

Future<void> _checkWidgetTypeVisible(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  _expectPresent(finder, _subject(parameters), 'Không tìm thấy target key: $key');
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
  _expectPresent(finder, 'text', 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);
  final widget = tester.widget<Text>(finder);
  final actual = widget.data ?? widget.textSpan?.toPlainText() ?? '';
  final expected = _requiredText(parameters, 'expectedText');
  final matchMode = _text(parameters, 'matchMode', 'equals').toLowerCase();
  final ok = matchMode == 'contains' ? actual.contains(expected) : actual == expected;
  // Chữ SINH VIÊN đang hiển thị được phép in nguyên văn (SPEC 5.4): đó là thứ em ấy tự
  // nhìn thấy trên máy mình, không phải khoá nội bộ hay log của hệ thống chấm.
  if (!ok) _observe('TEXT_MISMATCH', subject: 'text', seen: actual);
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
  _expectPresent(finder, _subject(parameters, 'button'), 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);
  final enabled = _enabledState(tester.widget<Widget>(finder));
  if (enabled == null) {
    fail('Không đọc được trạng thái enabled của $key (${targetType}).');
  }
  final wantEnabled = _bool(parameters, 'expectedEnabled', true);
  if (enabled != wantEnabled) {
    _observe('ENABLED_MISMATCH', subject: _subject(parameters, 'button'),
        seen: enabled == true ? 'enabled' : 'disabled');
  }
  expect(enabled, wantEnabled, reason: 'Trạng thái enabled của $key không đúng');
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
    _expectPresent(fieldFinder, 'field', 'Thiếu field key: ${fields[i]}');
    _assertTargetType(tester, fieldFinder, fields[i], fieldType);
    await tester.enterText(fieldFinder, _decodeInput(values[i]));
  }
  final submitKey = _requiredText(parameters, 'submitKey');
  await tester.tap(_byKey(submitKey));
  await _settle(tester);
  for (final errorKey in errors) {
    _expectPresent(_byKey(errorKey), 'error',
        'Thiếu lỗi validation key: $errorKey', where: 'after_action');
  }
}

Future<void> _checkListItemCount(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final listKey = _requiredText(parameters, 'listKey');
  final listFinder = _byKey(listKey);
  _expectPresent(listFinder, 'list', 'Không tìm thấy list key: $listKey');
  final itemKeys = _csv(parameters, 'itemKeys');
  if (itemKeys.isEmpty) fail('Thiếu itemKeys khi kiểm tra list.');
  var count = 0;
  for (final itemKey in itemKeys) {
    count += find.descendant(of: listFinder, matching: _byKey(itemKey)).evaluate().length;
  }
  final wanted = _number(parameters, 'expectedCount', double.nan).toInt();
  // Số đếm là quan sát được và vô hại: sinh viên tự đếm lại trên màn hình của mình.
  if (count != wanted) {
    _observe('COUNT_MISMATCH', subject: 'item', expected: wanted, found: count);
  }
  expect(count, wanted, reason: 'Số item trong $listKey không đúng');
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
    _expectPresent(fieldFinder, 'field', 'Thiếu field key: ${fields[i]}');
    _assertTargetType(tester, fieldFinder, fields[i], fieldType);
    final editable = find.descendant(of: fieldFinder, matching: find.byType(EditableText));
    _expectPresent(editable, 'field', 'Field ${fields[i]} không phải editable input');
    final filled = tester.widget<EditableText>(editable).controller.text;
    // Giá trị đang nằm trong ô nhập là thứ sinh viên tự nhìn thấy được.
    if (filled != _decodeInput(expectedValues[i])) {
      _observe('TEXT_MISMATCH', subject: 'field', seen: filled, where: 'after_action');
    }
    expect(filled, _decodeInput(expectedValues[i]),
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
    _expectPresent(fieldFinder, 'field', 'Thiếu field key: ${fields[i]}');
    _assertTargetType(tester, fieldFinder, fields[i], fieldType);
    await tester.enterText(fieldFinder, _decodeInput(values[i]));
  }
  await tester.tap(_byKey(_requiredText(parameters, 'submitKey')));
  await _settle(tester);
  final resultKey = _text(parameters, 'resultKey');
  if (resultKey.isNotEmpty) {
    _expectPresent(_byKey(resultKey), 'item',
        'Lưu xong nhưng không thấy kết quả: $resultKey', where: 'after_action');
  }
  for (final errorKey in _csv(parameters, 'errorKeys')) {
    _expectGone(_goneByKey(errorKey), 'error',
        'Dữ liệu hợp lệ nhưng vẫn còn error key: $errorKey', where: 'after_action');
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
  _expectPresent(dialogFinder, 'dialog',
      'Không tìm thấy dialog key: $dialogKey', where: 'after_action');
  _assertTargetType(tester, dialogFinder, dialogKey, 'dialog');

  final decisionKey = _requiredText(parameters, 'decisionKey');
  await tester.tap(_byKey(decisionKey));
  await _settle(tester);
  final resultKey = _text(parameters, 'resultKey');
  if (resultKey.isNotEmpty) {
    _expectPresent(_byKey(resultKey), 'item',
        'Xác nhận xong nhưng không thấy kết quả: $resultKey', where: 'after_action');
  }
  final absentKey = _text(parameters, 'absentKey');
  if (absentKey.isNotEmpty) {
    _expectGone(_goneByKey(absentKey), 'item',
        'Sau khi xác nhận, mục lẽ ra phải biến mất vẫn còn: $absentKey',
        where: 'after_action');
  }
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
  _expectPresent(finder, _subject(parameters), 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);
  final actual = tester.getSemantics(finder).label;
  final expected = _requiredText(parameters, 'expectedLabel');
  final matchMode = _text(parameters, 'matchMode', 'equals').toLowerCase();
  // Nhãn trợ năng là chữ trình đọc màn hình đọc lên cho người dùng — được phép in.
  final labelOk = matchMode == 'contains' ? actual.contains(expected) : actual == expected;
  if (!labelOk) {
    _observe('LABEL_MISMATCH', subject: 'widget', seen: actual.isEmpty ? null : actual);
  }
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
  _expectPresent(finder, _subject(parameters), 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);

  final render = tester.renderObject<RenderPadding>(finder);
  final padding = render.padding.resolve(TextDirection.ltr);
  final tolerance = _number(parameters, 'tolerance', 0.5);
  _assertNumber(padding.left, _number(parameters, 'left', double.nan), tolerance,
      'equals', '$key left padding', dimension: 'khoảng đệm bên trái');
  _assertNumber(padding.top, _number(parameters, 'top', double.nan), tolerance,
      'equals', '$key top padding', dimension: 'khoảng đệm phía trên');
  _assertNumber(padding.right, _number(parameters, 'right', double.nan), tolerance,
      'equals', '$key right padding', dimension: 'khoảng đệm bên phải');
  _assertNumber(padding.bottom, _number(parameters, 'bottom', double.nan), tolerance,
      'equals', '$key bottom padding', dimension: 'khoảng đệm phía dưới');
}

Future<void> _checkWidgetTextStyle(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  _expectPresent(finder, 'text', 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);

  final text = tester.widget<Text>(finder);
  final resolved = DefaultTextStyle.of(tester.element(finder))
      .style
      .merge(text.style);
  final tolerance = _number(parameters, 'tolerance', 0.5);
  final expectedSize = _number(parameters, 'fontSize', double.nan);
  if (!expectedSize.isNaN) {
    _assertNumber(resolved.fontSize ?? double.nan, expectedSize, tolerance,
        'equals', '$key fontSize', dimension: 'cỡ chữ');
  }
  final expectedWeight = _text(parameters, 'fontWeight');
  if (expectedWeight.isNotEmpty) {
    if (resolved.fontWeight != _fontWeight(expectedWeight)) {
      _observe('STYLE_MISMATCH', subject: 'text', dimension: 'độ đậm chữ');
    }
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
  _expectPresent(fromFinder, 'widget', 'Không tìm thấy fromKey: $fromKey');
  _expectPresent(toFinder, 'widget', 'Không tìm thấy toKey: $toKey');
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
      'Khoảng cách $fromKey → $toKey', dimension: 'khoảng cách giữa hai thành phần');
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

/// Điểm chung của WIDGET_DIMENSION / WIDGET_PADDING / WIDGET_GAP / WIDGET_TEXT_STYLE —
/// nên phát quan sát ở đây là phủ được cả bốn runner bằng một chỗ.
///
/// [dimension] là tên phép đo hiển thị cho sinh viên (`chiều rộng`, `khoảng cách`…), KHÔNG phải
/// semantic key. Số đo là quan sát được: sinh viên đo lại trên máy mình là ra.
void _assertNumber(
  double actual,
  double expected,
  double tolerance,
  String comparison,
  String reason, {
  String dimension = 'kích thước',
  String unit = 'px',
}) {
  if (expected.isNaN) fail('Thiếu giá trị số khi kiểm tra: $reason');
  final margin = tolerance < 0 ? 0 : tolerance;
  final mode = comparison.isEmpty ? 'equals' : comparison.toLowerCase();
  final ok = switch (mode) {
    'at_least' => actual >= expected - margin,
    'at_most' => actual <= expected + margin,
    _ => (actual - expected).abs() <= margin,
  };
  if (!ok) {
    _observe('NUMBER_MISMATCH',
        dimension: dimension, expected: expected, found: actual, unit: unit);
  }
  switch (mode) {
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

/// Finder cho phép kiểm **"ĐÃ BIẾN MẤT"** — chỉ nhận `ValueKey` chính xác, KHÔNG fallback.
///
/// Fallback theo vai trò/vị trí của [_byKey] chứng minh được *"có thứ đại loại như vậy"*
/// nhưng KHÔNG BAO GIỜ chứng minh được *"đúng thứ đó đã mất"* — dùng nó cho `findsNothing`
/// là chấm sai điểm sinh viên làm đúng:
///
///  - `item.1` rơi về `ListTile` ở **vị trí 0**: sinh viên xoá đúng người dùng thứ nhất thì
///    finder lại bắt được người thứ hai đang đứng đầu ⇒ luôn hỏng.
///  - `error.name` rơi về bất kỳ `Text` khớp `name|họ|tên|full`: bắt trúng **nhãn của ô nhập**
///    ("Full name") ⇒ phép kiểm "không còn thông báo lỗi" luôn hỏng.
///
/// Không đặt `skipOffstage: false`: màn hình trước trong Navigator vẫn nằm trong cây widget
/// nhưng đã bị che, không được tính là "còn hiển thị".
///
/// Sinh viên không dùng key thì finder rỗng và phép kiểm đạt — cố ý nghiêng về phía sinh viên,
/// vì phần khẳng định CHÍNH của mỗi runner vẫn phải đạt riêng.
Finder _goneByKey(String key) => find.byKey(ValueKey<String>(key));

Finder _byKey(String key) {
  final exact = find.byKey(ValueKey<String>(key), skipOffstage: false);
  if (exact.evaluate().isNotEmpty) return exact;

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

/// Fallback tìm thông báo lỗi của một ô nhập khi bài không đặt `ValueKey`.
///
/// ĐIỀU KIỆN BẮT BUỘC: nội dung `Text` phải nói lên chuyện LỖI. Bản trước ưu tiên nhánh
/// "khớp tên field" mà không đòi thêm gì, nên **nhãn của ô nhập** ("Full name", "Email")
/// cũng được tính là thông báo lỗi ⇒ form không kiểm dữ liệu gì vẫn đạt. Đó là chấm sai
/// điểm theo chiều CHO ĐIỂM OAN, ngược với lỗi `_byKey` nhưng cùng một nguồn: fallback
/// đoán theo chữ hiển thị.
Finder _validationErrorFor(String key) {
  const errorWording = r'required|minimum|min|invalid|bắt buộc|tối thiểu|không hợp lệ|lỗi';
  final fieldWording = key.toLowerCase().contains('email')
      ? r'email|e-mail|định dạng'
      : r'name|họ|tên|full';

  Finder errorTextAlsoMatching(String extra) => find.byWidgetPredicate(
        (widget) =>
            widget is Text &&
            RegExp(errorWording, caseSensitive: false).hasMatch(widget.data ?? '') &&
            RegExp(extra, caseSensitive: false).hasMatch(widget.data ?? ''),
        skipOffstage: false,
      );

  // Ưu tiên thông báo lỗi nói rõ ô nào; không có thì nhận thông báo lỗi bất kỳ.
  final specific = errorTextAlsoMatching(fieldWording);
  return specific.evaluate().isNotEmpty ? specific.first : errorTextAlsoMatching('');
}

Finder _notFound() => find.byWidgetPredicate((_) => false);

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
