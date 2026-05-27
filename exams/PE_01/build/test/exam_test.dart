import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/event.dart';
import '../lib/event_screen.dart';

void main() {
  test('TC_LOGIC_01', () {
    final event = Event(id: 'E1', title: 'Hackathon', capacity: 5);
    expect(event.title, equals('Hackathon'));
    expect(event.capacity, equals(5));
    expect(event.registeredCount, equals(0));
  });

  test('TC_LOGIC_02', () {
    final event = Event(id: 'E1', title: 'Hackathon', capacity: 2);
    expect(event.canRegister, isTrue);
    event.registeredCount = 2;
    expect(event.canRegister, isFalse);
  });

  test('TC_LOGIC_03', () {
    final event = Event(id: 'E1', title: 'Hackathon', capacity: 1);
    
    // Đăng ký lần 1 thành công
    final success = event.register();
    expect(success, isTrue);
    expect(event.registeredCount, equals(1));
    
    // Đăng ký lần 2 thất bại do đã đầy
    final fail = event.register();
    expect(fail, isFalse);
    expect(event.registeredCount, equals(1));
  });

  testWidgets('TC_UI_01', (WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: EventScreen()));
    await tester.pumpAndSettle();
    
    expect(find.byType(ListView), findsOneWidget);
    expect(find.text('Flutter Workshop'), findsOneWidget);
    expect(find.text('Dart OOP'), findsOneWidget);
  });

  testWidgets('TC_UI_02', (WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: EventScreen()));
    await tester.pumpAndSettle();
    
    expect(find.byType(ElevatedButton), findsNWidgets(2));
    expect(find.text('Đăng ký'), findsWidgets);
    expect(find.text('Đã đăng ký: 0/3'), findsOneWidget);
    expect(find.text('Đã đăng ký: 0/1'), findsOneWidget);
  });

  testWidgets('TC_UI_03', (WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: EventScreen()));
    await tester.pumpAndSettle();

    // Nhấn đăng ký sự kiện đầu tiên
    final registerBtn = find.widgetWithText(ElevatedButton, 'Đăng ký').first;
    await tester.tap(registerBtn);
    await tester.pumpAndSettle();

    // Kiểm tra UI đã update số lượng
    expect(find.text('Đã đăng ký: 1/3'), findsOneWidget);
  });

  testWidgets('TC_UI_04', (WidgetTester tester) async {
    await tester.pumpWidget(const MaterialApp(home: EventScreen()));
    await tester.pumpAndSettle();

    // Nhấn đăng ký sự kiện thứ 2 (Dart OOP) - Có capacity là 1
    final buttons = find.widgetWithText(ElevatedButton, 'Đăng ký');
    await tester.tap(buttons.last);
    await tester.pumpAndSettle();

    // Kiểm tra UI update số lượng và text của nút đổi thành 'Đã đầy'
    expect(find.text('Đã đăng ký: 1/1'), findsOneWidget);
    expect(find.text('Đã đầy'), findsOneWidget);
  });
}