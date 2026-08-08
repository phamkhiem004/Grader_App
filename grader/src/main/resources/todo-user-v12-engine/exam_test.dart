import 'dart:io';
import 'dart:ui' show SemanticsAction;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

// Chỉ gọi entrypoint và quan sát UI/hành vi công khai của bài nộp.
// Không import model, repository, viewmodel, screen hay widget nội bộ.
import '../lib/main.dart' as student_app;

class _UserData {
  const _UserData(this.name, this.email);

  final String name;
  final String email;
}

enum _FieldKind { fullName, email }

enum _ActionKind {
  submit,
  edit,
  delete,
  confirmDelete,
  cancel,
  back,
  avatarPicker,
  avatarOption,
}

const _persistentName = 'Persistence Check User';
const _persistentEmail = 'persistence.check@example.com';
const _persistEditInitialName = 'Persistence Original User';
const _persistEditInitialEmail = 'persistence.edit.initial@example.com';
const _persistEditedName = 'Persistence Saved User';
const _persistEditedEmail = 'persistence.edit.saved@example.com';
const _persistDeleteName = 'Persistence Removed User';
const _persistDeleteEmail = 'persistence.delete@example.com';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // Khi nhiều blackbox test chạy chung một process, dọn dữ liệu của test
  // trước trong sandbox. Persistence được chạy bằng seed/reload riêng nên
  // phải giữ nguyên database ở hai phase đó.
  setUp(() async {
    const persistMode = String.fromEnvironment('PERSIST_MODE');
    if (persistMode.isEmpty) await _clearGraderStorage();
  });

  testWidgets('UI_BOOT', (tester) async {
    await _boot(tester);
    expect(tester.takeException(), isNull);
  });

  testWidgets('UI_FORM_CONTROLS', (tester) async {
    await _boot(tester);
    await _ensureForm(tester);
    _expectFormControls(tester);
  });

  testWidgets('UI_CREATE_VALID', (tester) async {
    final user = await _createUser(tester);
    expect(_visible(user.name), findsOneWidget);
    expect(_visible(user.email), findsOneWidget);
  });

  testWidgets('UI_DUPLICATE_USERS', (tester) async {
    const name = 'Duplicate User';
    const email = 'duplicate.user@example.com';
    await _createUser(tester, fixedName: name, fixedEmail: email);
    await _ensureForm(tester);
    await _fillText(tester, _FieldKind.fullName, name);
    await _fillText(tester, _FieldKind.email, email);
    await _fillAvatar(tester);
    await _submit(tester);
    await _reveal(tester, name);

    // Hai bản ghi trùng dữ liệu vẫn phải tồn tại độc lập nhờ id tự tăng.
    expect(find.text(name), findsNWidgets(2));
    expect(find.text(email), findsNWidgets(2));
  });

  testWidgets('UI_VALIDATE_FULL_NAME', (tester) async {
    await _boot(tester);
    await _ensureForm(tester);
    await _fillText(tester, _FieldKind.email, 'valid@example.com');
    await _fillAvatar(tester);

    // Đề yêu cầu họ tên bắt buộc và tối thiểu 2 ký tự.
    final emptyNameErrors = _validationError().evaluate().length;
    await _submit(tester);
    _expectFieldValidationError(
      tester,
      'full name',
      RegExp(r'full[\s_-]*name|name|ho\s*ten|họ\s*tên', caseSensitive: false),
      minimumErrorCount: emptyNameErrors,
    );
    await _fillText(tester, _FieldKind.fullName, 'A');
    final shortNameErrors = _validationError().evaluate().length;
    await _submit(tester);
    _expectFieldValidationError(
      tester,
      'full name',
      RegExp(r'full[\s_-]*name|name|ho\s*ten|họ\s*tên', caseSensitive: false),
      minimumErrorCount: shortNameErrors - 1,
    );
  });

  testWidgets('UI_VALIDATE_EMAIL', (tester) async {
    await _boot(tester);
    await _ensureForm(tester);
    await _fillText(tester, _FieldKind.fullName, 'Nguoi Dung');
    await _fillText(tester, _FieldKind.email, 'email-sai');
    await _fillAvatar(tester);
    final errorsBefore = _validationError().evaluate().length;
    await _submit(tester);
    _expectFieldValidationError(
      tester,
      'email',
      RegExp(r'email|e-mail', caseSensitive: false),
      minimumErrorCount: errorsBefore,
    );
  });

  testWidgets('UI_VALIDATE_AVATAR', (tester) async {
    await _boot(tester);
    await _ensureForm(tester);
    await _fillText(tester, _FieldKind.fullName, 'Nguoi Dung');
    await _fillText(tester, _FieldKind.email, 'valid@example.com');
    // Không chọn avatar để kiểm tra avatar bắt buộc.
    final errorsBefore = _validationError().evaluate().length;
    await _submit(tester);
    _expectFieldValidationError(
      tester,
      'avatar',
      RegExp(
        r'avatar|image|photo|picture|choose|select|ảnh|anh|hình|hinh',
        caseSensitive: false,
      ),
      minimumErrorCount: errorsBefore,
    );
  });

  testWidgets('UI_AVATAR_SOURCE', (tester) async {
    final user = await _createUser(tester);
    await _reveal(tester, user.name);
    final images = find.byType(Image, skipOffstage: false);
    expect(images, findsWidgets);

    final assetImages = images
        .evaluate()
        .map((element) => element.widget)
        .whereType<Image>()
        .map((image) => image.image)
        .whereType<AssetImage>()
        .toList();
    expect(
      assetImages.any(
        (image) => image.assetName.toLowerCase().contains('default_avatar.jpg'),
      ),
      isTrue,
      reason: 'Avatar deterministic phai dung asset template da chon.',
    );
  });

  testWidgets('UI_LIST_ITEM', (tester) async {
    await _boot(tester);
    await _ensureForm(tester);
    final imagesBefore = _imageCount();
    final user = await _createUser(tester, boot: false);
    await _reveal(tester, user.name);

    expect(_visible(user.name), findsOneWidget);
    expect(_visible(user.email), findsOneWidget);
    expect(_imageCount(), greaterThan(imagesBefore));
    expect(_action(tester, _ActionKind.edit), findsOneWidget);
    expect(_action(tester, _ActionKind.delete), findsOneWidget);
  });

  testWidgets('UI_EDIT_LOAD', (tester) async {
    final user = await _createUser(tester);
    await _reveal(tester, user.name);
    await _tapAction(tester, _ActionKind.edit);
    expect(_textValue(tester, _FieldKind.fullName), user.name);
    expect(_textValue(tester, _FieldKind.email), user.email);
  });

  testWidgets('UI_EDIT_USER', (tester) async {
    final user = await _createUser(tester);
    await _reveal(tester, user.name);
    await _tapAction(tester, _ActionKind.edit);

    final editedName = '${user.name} Edited';
    final editedEmail =
        'edited.${DateTime.now().microsecondsSinceEpoch}@example.com';
    await _fillText(tester, _FieldKind.fullName, editedName);
    await _fillText(tester, _FieldKind.email, editedEmail);
    await _submit(tester);
    await _reveal(tester, editedName);
    expect(_visible(editedName), findsOneWidget);
    expect(_visible(editedEmail), findsOneWidget);
    expect(_visible(user.name), findsNothing);
  });

  testWidgets('UI_DELETE_DIALOG', (tester) async {
    final user = await _createUser(tester);
    await _reveal(tester, user.name);
    await _tapAction(tester, _ActionKind.delete);

    expect(_action(tester, _ActionKind.confirmDelete), findsOneWidget);
    expect(_action(tester, _ActionKind.cancel), findsOneWidget);

    await _tapAction(tester, _ActionKind.cancel);
    expect(_visible(user.name), findsOneWidget);
  });

  testWidgets('UI_DELETE_CONFIRM', (tester) async {
    final user = await _createUser(tester);
    await _reveal(tester, user.name);

    await _tapAction(tester, _ActionKind.delete);
    await _tapAction(tester, _ActionKind.confirmDelete);
    await _waitUntil(tester, () => _visible(user.name).evaluate().isEmpty);
    expect(_visible(user.name), findsNothing);
  });

  testWidgets('UI_DETAIL_NAVIGATION', (tester) async {
    final user = await _createUser(tester);
    await _reveal(tester, user.name);
    await tester.tap(_visible(user.name));
    await _settle(tester);

    expect(_visible(user.name), findsOneWidget);
    expect(_visible(user.email), findsOneWidget);
    expect(_avatarVisual(), findsWidgets);
    expect(_action(tester, _ActionKind.back), findsOneWidget);

    await _tapAction(tester, _ActionKind.back);
    expect(_visible(user.name), findsOneWidget);
  });

  testWidgets('UI_RESPONSIVE', (tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _boot(tester);
    await _ensureForm(tester);

    final portraitForm = _formRect(tester);
    final portraitScreen = _screenRect(tester);
    expect(portraitForm.left, greaterThanOrEqualTo(portraitScreen.left - 1));
    expect(portraitForm.right, lessThanOrEqualTo(portraitScreen.right + 1));

    final first = await _createUser(tester, boot: false);
    final second = await _createUser(tester, boot: false);

    // Phone portrait theo đề phải xếp một cột.
    await _expectOneColumn(tester, first.name, second.name);
    tester.view.physicalSize = const Size(800, 400);
    await _settle(tester);
    await _expectTwoColumns(tester, first.name, second.name);

    tester.view.physicalSize = const Size(1024, 768);
    await _settle(tester);
    await _expectTwoColumns(tester, first.name, second.name);
    expect(tester.takeException(), isNull);
  });

  testWidgets('UI_LAYOUT_OVERFLOW', (tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _boot(tester);
    await _ensureForm(tester);
    await _createUser(tester, boot: false);
    await _createUser(tester, boot: false);
    expect(tester.takeException(), isNull);

    for (final size in <Size>[const Size(800, 400), const Size(1024, 768)]) {
      tester.view.physicalSize = size;
      await _settle(tester);
      expect(
        tester.takeException(),
        isNull,
        reason: 'Khong duoc overflow o kich thuoc $size.',
      );
    }
  });

  // Grader chạy PERSIST_SEED và PERSIST_RELOAD trong hai Flutter process khác nhau.
  testWidgets('PERSIST_RELOAD', (tester) async {
    const persistMode = String.fromEnvironment('PERSIST_MODE');
    if (persistMode == 'reload') {
      await _boot(tester);
      await _ensureForm(tester);
      await _reveal(tester, _persistentName);
      expect(_visible(_persistentName), findsOneWidget);
      expect(_visible(_persistentEmail), findsOneWidget);
      return;
    }

    await _createUser(
      tester,
      fixedName: _persistentName,
      fixedEmail: _persistentEmail,
    );
    if (persistMode == 'seed') return;
    await _boot(tester);
    await _ensureForm(tester);
    await _reveal(tester, _persistentName);
    expect(_visible(_persistentName), findsOneWidget);
    expect(_visible(_persistentEmail), findsOneWidget);
  });

  testWidgets('PERSIST_EDIT_DELETE', (tester) async {
    const persistMode = String.fromEnvironment('PERSIST_MODE');
    if (persistMode == 'reload') {
      await _boot(tester);
      await _ensureForm(tester);
      await _reveal(tester, _persistEditedName);
      expect(_visible(_persistEditedName), findsOneWidget);
      expect(_visible(_persistEditedEmail), findsOneWidget);
      expect(_visible(_persistDeleteName), findsNothing);
      return;
    }

    final original = await _createUser(
      tester,
      fixedName: _persistEditInitialName,
      fixedEmail: _persistEditInitialEmail,
    );
    await _reveal(tester, original.name);
    await _tapAction(tester, _ActionKind.edit);
    await _setTextWithoutKeyboard(
      tester,
      _FieldKind.fullName,
      _persistEditedName,
    );
    await _setTextWithoutKeyboard(
      tester,
      _FieldKind.email,
      _persistEditedEmail,
    );
    await _submit(tester);
    await _reveal(tester, _persistEditedName);
    expect(_visible(_persistEditedName), findsOneWidget);

    final deleted = await _createUser(
      tester,
      boot: false,
      fixedName: _persistDeleteName,
      fixedEmail: _persistDeleteEmail,
    );
    await _reveal(tester, deleted.name);
    await _tapAction(tester, _ActionKind.delete);
    await _tapAction(tester, _ActionKind.confirmDelete);
    await _waitUntil(
      tester,
      () => _visible(_persistDeleteName).evaluate().isEmpty,
    );
    expect(_visible(_persistDeleteName), findsNothing);
    if (persistMode == 'seed') return;

    await _boot(tester);
    await _ensureForm(tester);
    await _reveal(tester, _persistEditedName);
    expect(_visible(_persistEditedName), findsOneWidget);
    expect(_visible(_persistDeleteName), findsNothing);
  });

  testWidgets('UI_DETAIL_OPEN', (tester) async {
    final user = await _createUser(tester);
    await _reveal(tester, user.name);
    await tester.tap(_visible(user.name));
    await _settle(tester);
    expect(_visible(user.name), findsOneWidget);
    expect(_visible(user.email), findsOneWidget);
    expect(_action(tester, _ActionKind.back), findsOneWidget);
  });

  testWidgets('UI_DETAIL_BACK', (tester) async {
    final user = await _createUser(tester);
    await _reveal(tester, user.name);
    await tester.tap(_visible(user.name));
    await _settle(tester);
    await _tapAction(tester, _ActionKind.back);
    await _reveal(tester, user.name);
    expect(_visible(user.name), findsOneWidget);
  });

  testWidgets('UI_RESPONSIVE_PORTRAIT', (tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _boot(tester);
    await _ensureForm(tester);
    final first = await _createUser(tester, boot: false);
    final second = await _createUser(tester, boot: false);
    await _expectOneColumn(tester, first.name, second.name);
    expect(tester.takeException(), isNull);
  });

  testWidgets('UI_RESPONSIVE_LANDSCAPE', (tester) async {
    tester.view.physicalSize = const Size(1024, 768);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _boot(tester);
    await _ensureForm(tester);
    final first = await _createUser(tester, boot: false);
    final second = await _createUser(tester, boot: false);
    await _expectTwoColumns(tester, first.name, second.name);
    expect(tester.takeException(), isNull);
  });

  testWidgets('PERSIST_ADD_RELOAD', (tester) async {
    const persistMode = String.fromEnvironment('PERSIST_MODE');
    if (persistMode == 'reload') {
      await _boot(tester);
      await _ensureForm(tester);
      await _reveal(tester, _persistentName);
      expect(_visible(_persistentName), findsOneWidget);
      expect(_visible(_persistentEmail), findsOneWidget);
      return;
    }

    await _createUser(
      tester,
      fixedName: _persistentName,
      fixedEmail: _persistentEmail,
    );
    if (persistMode == 'seed') return;
    await _boot(tester);
    await _ensureForm(tester);
    await _reveal(tester, _persistentName);
    expect(_visible(_persistentName), findsOneWidget);
  });

  testWidgets('PERSIST_EDIT_RELOAD', (tester) async {
    const persistMode = String.fromEnvironment('PERSIST_MODE');
    if (persistMode == 'reload') {
      await _boot(tester);
      await _ensureForm(tester);
      await _reveal(tester, _persistEditedName);
      expect(_visible(_persistEditedName), findsOneWidget);
      expect(_visible(_persistEditedEmail), findsOneWidget);
      return;
    }

    final original = await _createUser(
      tester,
      fixedName: _persistEditInitialName,
      fixedEmail: _persistEditInitialEmail,
    );
    await _reveal(tester, original.name);
    await _tapAction(tester, _ActionKind.edit);
    await _setTextWithoutKeyboard(
      tester,
      _FieldKind.fullName,
      _persistEditedName,
    );
    await _setTextWithoutKeyboard(
      tester,
      _FieldKind.email,
      _persistEditedEmail,
    );
    await _submit(tester);
    await _reveal(tester, _persistEditedName);
    expect(_visible(_persistEditedName), findsOneWidget);
    if (persistMode == 'seed') return;
    await _boot(tester);
    await _ensureForm(tester);
    await _reveal(tester, _persistEditedName);
    expect(_visible(_persistEditedEmail), findsOneWidget);
  });

  testWidgets('PERSIST_DELETE_RELOAD', (tester) async {
    const persistMode = String.fromEnvironment('PERSIST_MODE');
    if (persistMode == 'reload') {
      await _boot(tester);
      await _ensureForm(tester);
      expect(_visible(_persistDeleteName), findsNothing);
      return;
    }

    final user = await _createUser(
      tester,
      fixedName: _persistDeleteName,
      fixedEmail: _persistDeleteEmail,
    );
    await _reveal(tester, user.name);
    await _tapAction(tester, _ActionKind.delete);
    await _tapAction(tester, _ActionKind.confirmDelete);
    await _waitUntil(tester, () => _visible(user.name).evaluate().isEmpty);
    expect(_visible(user.name), findsNothing);
    if (persistMode == 'seed') return;
    await _boot(tester);
    await _ensureForm(tester);
    expect(_visible(_persistDeleteName), findsNothing);
  });
}

Future<void> _clearGraderStorage() async {
  final root = Platform.environment['GRADER_DATA_HOME'];
  if (root == null || root.isEmpty) return;
  final directory = Directory(root);
  if (!directory.existsSync()) return;
  await for (final entity in directory.list(
    recursive: true,
    followLinks: false,
  )) {
    if (entity is File) {
      try {
        await entity.delete();
      } catch (_) {
        // Một file khóa tạm không được làm hỏng test UI hiện tại.
      }
    }
  }
}

void _expectFormControls(WidgetTester tester) {
  expect(_editable(tester, _FieldKind.fullName), findsOneWidget);
  expect(_editable(tester, _FieldKind.email), findsOneWidget);
  expect(_action(tester, _ActionKind.avatarPicker), findsOneWidget);
  expect(_action(tester, _ActionKind.submit), findsOneWidget);
}

bool _hasForm(WidgetTester tester) {
  return _editable(tester, _FieldKind.fullName).evaluate().isNotEmpty &&
      _editable(tester, _FieldKind.email).evaluate().isNotEmpty;
}

Future<void> _ensureForm(WidgetTester tester) async {
  if (_hasForm(tester)) return;

  // Đề yêu cầu form nằm phía trên danh sách ở Home; chỉ cuộn để đưa form ra vùng nhìn.
  final scrollables = find.byType(Scrollable, skipOffstage: false);
  for (var index = 0; index < scrollables.evaluate().length; index++) {
    await tester.drag(scrollables.at(index), const Offset(0, 2400));
  }
  await _settle(tester);
  expect(
    _hasForm(tester),
    isTrue,
    reason: 'Không tìm thấy form thêm user trên Home.',
  );
}

Finder _editable(WidgetTester tester, _FieldKind kind) {
  final patterns = _fieldPatterns(kind);
  final candidates = find.byType(EditableText, skipOffstage: false);
  final count = candidates.evaluate().length;
  for (var index = 0; index < count; index++) {
    final candidate = candidates.at(index);
    try {
      final data = tester.getSemantics(candidate).getSemanticsData();
      final description = '${data.label} ${data.value} ${data.hint}';
      if (patterns.any((pattern) => pattern.hasMatch(description))) {
        return candidate;
      }
    } catch (_) {
      // Thử finder semantics fallback bên dưới.
    }
  }

  for (final pattern in patterns) {
    final semantic = find.bySemanticsLabel(pattern, skipOffstage: false);
    final nested = find.descendant(
      of: semantic,
      matching: find.byType(EditableText, skipOffstage: false),
      matchRoot: true,
    );
    if (nested.evaluate().isNotEmpty) return nested.first;
  }
  return _notFound();
}

Finder _action(WidgetTester tester, _ActionKind kind) {
  final pattern = _actionPattern(kind);
  final semantic = find.bySemanticsLabel(pattern, skipOffstage: false);
  final count = semantic.evaluate().length;
  for (var index = 0; index < count; index++) {
    final candidate = semantic.at(index);
    try {
      final data = tester.getSemantics(candidate).getSemanticsData();
      if (data.hasAction(SemanticsAction.tap) &&
          _directActionPattern(kind).hasMatch(data.label.trim())) {
        return candidate;
      }
    } catch (_) {
      // Fallback sang text hiển thị bên dưới.
    }
  }

  // Text fallback cho button có text nhưng chưa cung cấp semantic action riêng.
  final text = find.byWidgetPredicate((widget) {
    return widget is Text && pattern.hasMatch(widget.data ?? '');
  }, skipOffstage: false);
  if (text.evaluate().isNotEmpty) return text.first;
  return _notFound();
}

RegExp _actionPattern(_ActionKind kind) {
  switch (kind) {
    case _ActionKind.submit:
      return RegExp(
        r'add|create|save|submit|update|new user|thêm|tạo|lưu|cập nhật|cap nhat',
        caseSensitive: false,
      );
    case _ActionKind.edit:
      return RegExp(r'edit|sửa|sua|chỉnh sửa|chinh sua', caseSensitive: false);
    case _ActionKind.delete:
      return RegExp(r'delete|remove|xóa|xoa', caseSensitive: false);
    case _ActionKind.confirmDelete:
      return RegExp(
        r'confirm|yes|delete|xóa|xoa|đồng ý|dong y',
        caseSensitive: false,
      );
    case _ActionKind.cancel:
      return RegExp(r'cancel|no|hủy|huy|đóng|dong', caseSensitive: false);
    case _ActionKind.back:
      return RegExp(
        r'back|quay lại|quay lai|trở về|tro ve',
        caseSensitive: false,
      );
    case _ActionKind.avatarPicker:
      return RegExp(
        r'avatar|image|photo|picture|ảnh|anh|hình|hinh|choose|select|chọn',
        caseSensitive: false,
      );
    case _ActionKind.avatarOption:
      return RegExp(
        r'default|sample|asset|image|photo|picture|avatar\s*(option|choice|[0-9]+)|mẫu|mau|mặc định|mac dinh',
        caseSensitive: false,
      );
  }
}

RegExp _directActionPattern(_ActionKind kind) {
  switch (kind) {
    case _ActionKind.submit:
      return RegExp(
        r'^(add|create|save|submit|update|add user|new user|update user|them|tao|luu|cap nhat)(\s+user)?$',
        caseSensitive: false,
      );
    case _ActionKind.edit:
      return RegExp(
        r'^(edit|edit user|sua|sua user|chinh sua)$',
        caseSensitive: false,
      );
    case _ActionKind.delete:
      return RegExp(
        r'^(delete|delete user|remove|remove user|xoa|xoa user)$',
        caseSensitive: false,
      );
    case _ActionKind.confirmDelete:
      return RegExp(
        r'^(confirm delete|confirm|yes|delete|xoa|dong y)$',
        caseSensitive: false,
      );
    case _ActionKind.cancel:
      return RegExp(r'^(cancel|no|huy|dong)$', caseSensitive: false);
    case _ActionKind.back:
      return RegExp(r'^(back|quay lai|tro ve)$', caseSensitive: false);
    case _ActionKind.avatarPicker:
      return RegExp(
        r'^(choose|choose avatar|select|select avatar|avatar|image|photo|picture|anh|hinh|chon)$',
        caseSensitive: false,
      );
    case _ActionKind.avatarOption:
      return RegExp(
        r'^(default|default avatar|sample|asset|image|photo|picture|avatar\s*(option|choice|[0-9]+)|mau|mac dinh)$',
        caseSensitive: false,
      );
  }
}

List<RegExp> _fieldPatterns(_FieldKind kind) {
  switch (kind) {
    case _FieldKind.fullName:
      return <RegExp>[
        RegExp(r'full[\s_-]*name', caseSensitive: false),
        RegExp(r'họ\s*tên|ho\s*ten', caseSensitive: false),
        RegExp(r'\bname\b', caseSensitive: false),
      ];
    case _FieldKind.email:
      return <RegExp>[RegExp(r'email|e-mail', caseSensitive: false)];
  }
}

Finder _validationError() {
  final error = RegExp(
    r'required|must|minimum|min|invalid|bắt buộc|bat buoc|tối thiểu|toi thieu|không hợp lệ|khong hop le|error|lỗi|loi',
    caseSensitive: false,
  );
  final text = find.byWidgetPredicate((widget) {
    final value = switch (widget) {
      Text(:final data) => data?.trim() ?? '',
      RichText(:final text) => text.toPlainText().trim(),
      _ => '',
    };
    // Chỉ nhận thông báo lỗi hiển thị, không dùng label field làm fallback.
    return value.isNotEmpty && error.hasMatch(value);
  });
  return text;
}

Finder _avatarVisual() {
  // Đề yêu cầu Image.asset/Image.network; không dùng image role để đoán nhầm logo.
  return find.byType(Image);
}

int _imageCount() {
  return find.byType(Image).evaluate().length;
}

Finder _avatarOption(WidgetTester tester) {
  final sheets = find.byType(BottomSheet, skipOffstage: false);
  if (sheets.evaluate().isNotEmpty) {
    final optionPattern = _actionPattern(_ActionKind.avatarOption);
    final optionText = find.descendant(
      of: sheets.last,
      matching: find.byWidgetPredicate((widget) {
        return widget is Text && optionPattern.hasMatch(widget.data ?? '');
      }),
    );
    if (optionText.evaluate().isNotEmpty) return optionText.first;
  }
  return _action(tester, _ActionKind.avatarOption);
}

Rect _screenRect(WidgetTester tester) {
  return Rect.fromLTWH(
    0,
    0,
    tester.view.physicalSize.width / tester.view.devicePixelRatio,
    tester.view.physicalSize.height / tester.view.devicePixelRatio,
  );
}

Rect _formRect(WidgetTester tester) {
  final rects = <Rect>[];
  for (final kind in _FieldKind.values) {
    final field = _editable(tester, kind);
    if (field.evaluate().isNotEmpty) rects.add(tester.getRect(field));
  }
  final submit = _action(tester, _ActionKind.submit);
  if (submit.evaluate().isNotEmpty) rects.add(tester.getRect(submit));
  expect(rects, isNotEmpty, reason: 'Không xác định được vùng form.');

  var result = rects.first;
  for (final rect in rects.skip(1)) {
    result = Rect.fromLTRB(
      result.left < rect.left ? result.left : rect.left,
      result.top < rect.top ? result.top : rect.top,
      result.right > rect.right ? result.right : rect.right,
      result.bottom > rect.bottom ? result.bottom : rect.bottom,
    );
  }
  return result;
}

void _expectFieldValidationError(
  WidgetTester tester,
  String fieldName,
  RegExp fieldPattern, {
  required int minimumErrorCount,
}) {
  final errorCount = _validationError().evaluate().where((element) {
    final widget = element.widget;
    final text = switch (widget) {
      Text(:final data) => data ?? '',
      RichText(:final text) => text.toPlainText(),
      _ => '',
    };
    return fieldPattern.hasMatch(text);
  }).length;
  expect(
    errorCount,
    greaterThan(minimumErrorCount),
    reason: 'Cần hiển thị lỗi đúng cho trường $fieldName.',
  );
}

Finder _userText(String value, {required bool skipOffstage}) {
  return find.byWidgetPredicate((widget) {
    if (widget is Text) {
      return widget.data?.trim() == value ||
          widget.textSpan?.toPlainText().trim() == value;
    }
    // Không nhận EditableText: text trong form không phải item của list.
    return false;
  }, skipOffstage: skipOffstage);
}

Finder _visible(String value) => _userText(value, skipOffstage: true);

Future<bool> _waitUntil(
  WidgetTester tester,
  bool Function() condition, {
  Duration timeout = const Duration(seconds: 5),
}) async {
  const step = Duration(milliseconds: 100);
  final steps = (timeout.inMilliseconds / step.inMilliseconds).ceil();
  for (var index = 0; index < steps; index++) {
    if (condition()) return true;
    await tester.pump(step);
  }
  return condition();
}

Future<void> _boot(WidgetTester tester) async {
  student_app.main();
  await _settle(tester);
}

Future<void> _reveal(WidgetTester tester, String value) async {
  final item = _userText(value, skipOffstage: false);
  final found = await _waitUntil(tester, () => item.evaluate().isNotEmpty);
  if (!found) return;

  // Không giả định sinh viên dùng một loại Scrollable hay chiều cao item cụ thể.
  await tester.ensureVisible(item.first);
  await _settle(tester);
}

Future<void> _expectTwoColumns(
  WidgetTester tester,
  String firstName,
  String secondName,
) async {
  await _reveal(tester, firstName);
  final first = tester.getRect(_visible(firstName));
  await _reveal(tester, secondName);
  final second = tester.getRect(_visible(secondName));
  expect(
    (first.left - second.left).abs(),
    greaterThan(24),
    reason: 'Màn hình ngang/tablet cần tối thiểu hai cột hoặc vùng ngang.',
  );
}

Future<void> _expectOneColumn(
  WidgetTester tester,
  String firstName,
  String secondName,
) async {
  await _reveal(tester, firstName);
  final first = tester.getRect(_visible(firstName));
  await _reveal(tester, secondName);
  final second = tester.getRect(_visible(secondName));
  expect(
    (first.left - second.left).abs(),
    lessThanOrEqualTo(24),
    reason: 'Màn hình portrait phải xếp item theo một cột.',
  );
}

String _textValue(WidgetTester tester, _FieldKind kind) {
  final field = _editable(tester, kind);
  expect(field, findsOneWidget);
  return tester.widget<EditableText>(field).controller.text;
}

Future<void> _fillText(
  WidgetTester tester,
  _FieldKind kind,
  String value,
) async {
  final field = _editable(tester, kind);
  expect(field, findsOneWidget, reason: 'Không tìm thấy ô nhập ${kind.name}.');
  await tester.ensureVisible(field);
  await tester.enterText(field, value);
  await _settle(tester);
}

Future<void> _setTextWithoutKeyboard(
  WidgetTester tester,
  _FieldKind kind,
  String value,
) async {
  var field = _editable(tester, kind);
  expect(field, findsOneWidget);
  await tester.ensureVisible(field);
  await _settle(tester);
  field = _editable(tester, kind);
  expect(field, findsOneWidget);
  final editable = tester.widget<EditableText>(field);
  editable.controller.value = TextEditingValue(
    text: value,
    selection: TextSelection.collapsed(offset: value.length),
  );
  await _settle(tester);
}

Future<void> _fillAvatar(WidgetTester tester) async {
  await _tapAction(tester, _ActionKind.avatarPicker);
  final option = _avatarOption(tester);
  expect(
    option,
    findsOneWidget,
    reason: 'Picker avatar cần có lựa chọn ảnh deterministic.',
  );
  await tester.ensureVisible(option);
  await tester.tap(option);
  await _settle(tester);
}

Future<void> _submit(WidgetTester tester) async {
  final pattern = _actionPattern(_ActionKind.submit);
  final labels = find.byWidgetPredicate(
    (widget) => widget is Text && pattern.hasMatch(widget.data ?? ''),
    skipOffstage: false,
  );
  for (var index = 0; index < labels.evaluate().length; index++) {
    await tester.ensureVisible(labels.at(index));
    final button = find.ancestor(
      of: labels.at(index),
      matching: find.byWidgetPredicate((widget) => widget is ButtonStyleButton),
    );
    final tappableButton = button.hitTestable();
    if (tappableButton.evaluate().isNotEmpty) {
      await tester.ensureVisible(tappableButton.first);
      await tester.tap(tappableButton.first);
      await _settle(tester);
      return;
    }
  }
  await _tapAction(tester, _ActionKind.submit);
}

Future<void> _tapAction(WidgetTester tester, _ActionKind kind) async {
  final action =
      (kind == _ActionKind.confirmDelete || kind == _ActionKind.cancel)
      ? _dialogAction(tester, kind)
      : _action(tester, kind);
  expect(action, findsOneWidget, reason: 'Không tìm thấy action ${kind.name}.');
  await tester.ensureVisible(action);
  await tester.tap(action);
  await _settle(tester);
}

Finder _dialogAction(WidgetTester tester, _ActionKind kind) {
  final dialogs = find.byType(AlertDialog, skipOffstage: false);
  if (dialogs.evaluate().isNotEmpty) {
    final pattern = _actionPattern(kind);
    final labels = find.descendant(
      of: dialogs.last,
      matching: find.byWidgetPredicate((widget) {
        return widget is Text && pattern.hasMatch(widget.data ?? '');
      }),
    );
    for (var index = 0; index < labels.evaluate().length; index++) {
      final button = find.ancestor(
        of: labels.at(index),
        matching: find.byWidgetPredicate(
          (widget) => widget is ButtonStyleButton,
        ),
      );
      if (button.evaluate().isNotEmpty) return button.first;
    }
  }
  return _action(tester, kind);
}

Future<_UserData> _createUser(
  WidgetTester tester, {
  bool boot = true,
  String? fixedName,
  String? fixedEmail,
}) async {
  if (boot) await _boot(tester);
  await _ensureForm(tester);
  final suffix = DateTime.now().microsecondsSinceEpoch.toString();
  final user = _UserData(
    fixedName ?? 'UI Student $suffix',
    fixedEmail ?? 'ui.$suffix@example.com',
  );
  await _fillText(tester, _FieldKind.fullName, user.name);
  await _fillText(tester, _FieldKind.email, user.email);
  await _fillAvatar(tester);
  await _submit(tester);
  final saved = await _waitUntil(
    tester,
    () => _userText(user.name, skipOffstage: false).evaluate().isNotEmpty,
  );
  expect(
    saved,
    isTrue,
    reason: 'Repository phải hoàn tất lưu trước khi kiểm tra danh sách.',
  );
  await _reveal(tester, user.name);
  expect(
    _visible(user.name),
    findsOneWidget,
    reason: 'User phải xuất hiện trong list sau khi repository lưu xong.',
  );
  return user;
}

Future<void> _settle(WidgetTester tester) async {
  // TextField có cursor nhấp nháy liên tục nên pumpAndSettle sẽ timeout.
  // Hai frame có thời gian xác định đủ để hoàn thành thao tác UI/async nhỏ.
  await tester.pump(const Duration(milliseconds: 200));
  await tester.pump(const Duration(milliseconds: 500));
}

Finder _notFound() => find.byWidgetPredicate((widget) => false);
