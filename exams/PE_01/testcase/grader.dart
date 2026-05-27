import 'dart:io';
import 'dart:convert';

void main() async {
  print('⏳ Đang chấm bài...');

  final result = await Process.run('flutter', [
    'test',
    '--machine',
    '--no-pub',
  ], runInShell: true);

  final matrixFile = File('test/skills_matrix.json');
  final Map<String, dynamic> matrix = jsonDecode(
    await matrixFile.readAsString(),
  );

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

  double totalScore = 0;
  double maxScore = 0;
  int passCount = 0;
  final List<Map<String, dynamic>> chiTiet = [];

  print('\n=============================================');
  print('🏆 KẾT QUẢ CHẤM ĐIỂM');
  print('=============================================');

  testsResult.forEach((tName, isSuccess) {
    if (!matrix.containsKey(tName)) return;
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

  final diem = maxScore > 0
      ? double.parse(((totalScore / maxScore) * 10).toStringAsFixed(2))
      : 0.0;

  print('\n=> TỔNG: $totalScore / $maxScore điểm thô = $diem / 10');

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