import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/calculator.dart';
import '../lib/login_screen.dart';

void main() {
  // ── Không dùng group() — tên test phải khớp skills_matrix ──
  test('TC_LOGIC_01', () {
    final calculator = Calculator();
    expect(calculator.add(2, 3), equals(5));
  });

  testWidgets('TC_UI_01', (WidgetTester tester) async {
    await tester.pumpWidget(const LoginScreen());
    await tester.pumpAndSettle();
    expect(find.byType(TextField), findsWidgets);
    expect(find.byType(ElevatedButton), findsOneWidget);
    expect(find.text('Đăng nhập'), findsOneWidget);
  });

  testWidgets('TC_UI_02', (WidgetTester tester) async {
    await tester.pumpWidget(const LoginScreen());
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('username')), 'admin');
    await tester.enterText(find.byKey(const Key('password')), '123456');
    await tester.tap(find.byType(ElevatedButton));
    await tester.pumpAndSettle();
    expect(find.text('Trang chủ'), findsOneWidget);
  });
}
