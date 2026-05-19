import 'dart:io';
import 'dart:convert';

void main() async {
  print('⏳ Đang chấm bài...');

  // 1. Chạy flutter test --machine
  final result = await Process.run('flutter', [
    'test',
    '--machine',
    '--no-pub',
  ], runInShell: true);

  // 2. Đọc skills_matrix.json — format đúng: {"TC_LOGIC_01": 2.0}
  final matrixFile = File('test/skills_matrix.json');
  final Map<String, dynamic> matrix = jsonDecode(
    await matrixFile.readAsString(),
  );

  // 3. Parse events từ flutter test --machine
  final lines = result.stdout.toString().trim().split('\n');
  final Map<int, String> testIdToName = {};
  final Map<String, bool> testsResult = {};

  for (var line in lines) {
    if (!line.startsWith('{')) continue;
    try {
      final event = jsonDecode(line) as Map<String, dynamic>;
      final type = event['type'] as String?;

      if (type == 'testStart') {
        final test = event['test'] as Map<String, dynamic>;
        testIdToName[test['id'] as int] = test['name'] as String;
      } else if (type == 'testDone') {
        final id = event['testID'] as int;
        final name = testIdToName[id] ?? '';
        final res = event['result'] as String;
        final hidden = event['hidden'] as bool? ?? false;
        if (name.isEmpty || name.startsWith('loading ') || hidden) continue;
        testsResult[name] = (res == 'success');
      }
    } catch (_) {}
  }

  // 4. Tính điểm — đọc trực tiếp matrix[tName] là double
  double totalScore = 0;
  double maxScore = 0;
  int passCount = 0;
  final List<Map<String, dynamic>> chiTiet = [];

  print('\n=============================================');
  print('🏆 KẾT QUẢ CHẤM ĐIỂM');
  print('=============================================');

  testsResult.forEach((tName, isSuccess) {
    if (!matrix.containsKey(tName)) return;

    // ✅ Đọc đúng — matrix[tName] là double
    final point = (matrix[tName] as num).toDouble();
    maxScore += point;

    if (isSuccess) {
      totalScore += point;
      passCount++;
      print('✅ [PASS] ${tName.padRight(20)} : +$point điểm');
      chiTiet.add({'name': tName, 'status': 'PASS', 'message': '+$point điểm'});
    } else {
      print('❌ [FAIL] ${tName.padRight(20)} : +0 điểm');
      chiTiet.add({
        'name': tName,
        'status': 'FAILED',
        'message': '0/$point điểm',
      });
    }
  });

  // 5. Quy về hệ 10
  final diem = maxScore > 0
      ? double.parse(((totalScore / maxScore) * 10).toStringAsFixed(2))
      : 0.0;

  print('\n=> TỔNG: $totalScore / $maxScore điểm thô = $diem / 10');

  // 6. Xuất GRADE_RESULT cho Spring Boot đọc ← QUAN TRỌNG
  print('\n--- GRADE_RESULT_START ---');
  print(
    jsonEncode({
      'diem': diem,
      'soTestPass': passCount,
      'tongSoTest': testsResult.length,
      'chiTiet': chiTiet,
    }),
  );
  print('--- GRADE_RESULT_END ---');
}
