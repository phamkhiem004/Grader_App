import 'dart:io';
import 'dart:convert';

void main() async {
  // 1) Chạy test ở chế độ machine
  final result = await Process.run(
    'flutter', ['test', '--machine', '--no-pub'],
    runInShell: true,
  );

  // 2) Đọc rubric — hỗ trợ CẢ dạng số (cũ) lẫn object (mới)
  final matrix = jsonDecode(
    await File('test/skills_matrix.json').readAsString(),
  ) as Map<String, dynamic>;

  // 3) Parse output machine của flutter test
  final idToName = <int, String>{};
  final idToError = <int, String>{};
  final idToPrints = <int, List<String>>{};
  final passed = <String>{};
  final failed = <String>{};
  final failReason = <String, String>{};

  for (final line in result.stdout.toString().split('\n')) {
    if (!line.startsWith('{')) continue;
    try {
      final ev = jsonDecode(line) as Map<String, dynamic>;
      switch (ev['type']) {
        case 'testStart':
          final t = ev['test'] as Map<String, dynamic>;
          idToName[t['id'] as int] = t['name'] as String;
          break;
        case 'error':
          final id = ev['testID'];
          if (id is int) idToError[id] = (ev['error'] ?? '').toString();
          break;
        case 'print':
          // Lỗi chi tiết của WIDGET test nằm trong print (EXCEPTION CAUGHT...).
          final id = ev['testID'];
          if (id is int) {
            final m = (ev['message'] ?? '').toString();
            if (m.isNotEmpty) (idToPrints[id] ??= <String>[]).add(m);
          }
          break;
        case 'testDone':
          final id = ev['testID'] as int;
          final name = idToName[id] ?? '';
          final hidden = ev['hidden'] == true;
          if (name.isEmpty || name.startsWith('loading ') || hidden) break;
          if (ev['result'] == 'success') {
            passed.add(name);
          } else {
            failed.add(name);
            final reason = _failureReason(idToError[id], idToPrints[id]);
            if (reason.isNotEmpty) failReason[name] = reason;
          }
          break;
      }
    } catch (_) {}
  }

  // 4) Tính điểm + dựng test_cases giàu thông tin
  double earned = 0, maxScore = 0;
  int passCount = 0;
  final chiTiet = <Map<String, dynamic>>[];
  final testCases = <Map<String, dynamic>>[];

  final names = ({...passed, ...failed}).toList()..sort();
  for (final name in names) {
    final meta = matrix[name];
    if (meta == null) continue; // test không nằm trong rubric → bỏ qua

    double weight = 1.0;
    String? skill, description, human, expected, difficulty;
    if (meta is num) {
      weight = meta.toDouble();
    } else if (meta is Map) {
      difficulty = meta['difficulty']?.toString();
      // ĐIỂM THEO ĐỘ KHÓ: weight suy TRỰC TIẾP từ difficulty (basic=1, intermediate=2, advanced=3).
      // 'weight' trong matrix chỉ là FALLBACK khi không khai difficulty → difficulty là NGUỒN SỰ THẬT.
      weight = _difficultyPoints(difficulty) ?? (meta['weight'] as num?)?.toDouble() ?? 1.0;
      skill = meta['skill']?.toString();
      description = meta['description']?.toString();
      human = meta['name']?.toString();
      expected = meta['expected']?.toString();
    }

    final ok = passed.contains(name);
    maxScore += weight;
    if (ok) { earned += weight; passCount++; }

    chiTiet.add({
      'name': name,
      'status': ok ? 'PASS' : 'FAILED',
      'message': ok ? '+$weight điểm' : '0/$weight điểm',
    });

    final tc = <String, dynamic>{
      'test_id': name,
      'name': human ?? name,
      'status': ok ? 'passed' : 'failed',
      'weight': weight,
    };
    if (difficulty != null) tc['difficulty'] = difficulty;
    if (skill != null) tc['skill'] = skill;
    if (description != null) tc['description'] = description;
    if (!ok) {
      if (expected != null) tc['expected'] = expected;
      final actual = failReason[name];
      if (actual != null && actual.isNotEmpty) tc['actual'] = actual;
    }
    testCases.add(tc);
  }

  final total = testCases.length;
  final diem = maxScore > 0
      ? double.parse(((earned / maxScore) * 10).toStringAsFixed(2))
      : 0.0;

  // 5) Phân tích tĩnh code SV (best-effort)
  final analyze = _analyzeEnabled()
      ? await _analyzeLib()
      : {'has_error': false, 'warnings': <Map<String, dynamic>>[]};

  // 6) Xuất JSON (GIỮ field cũ để tương thích + thêm cấu trúc giàu cho AI)
  print('--- GRADE_RESULT_START ---');
  print(jsonEncode({
    'diem': diem,
    'soTestPass': passCount,
    'tongSoTest': total,
    'chiTiet': chiTiet,
    'grading_result': {
      'score': diem,
      'passed_tests': passCount,
      'failed_tests': total - passCount,
      'total_tests': total,
    },
    'test_cases': testCases,
    'analyze_result': analyze,
  }));
  print('--- GRADE_RESULT_END ---');
}

bool _analyzeEnabled() {
  final v = (Platform.environment['GRADER_ANALYZE_LIB'] ?? 'true').toLowerCase();
  return v == '1' || v == 'true' || v == 'yes' || v == 'on';
}

// Chọn thông báo lỗi chi tiết nhất: ưu tiên print 'EXCEPTION CAUGHT' (widget test),
// nếu không có thì dùng error event (logic test).
String _failureReason(String? error, List<String>? prints) {
  if (prints != null) {
    for (final p in prints) {
      if (p.contains('TestFailure') ||
          p.contains('Expected:') ||
          p.contains('EXCEPTION CAUGHT')) {
        return _clean(p);
      }
    }
  }
  return _clean(error ?? '');
}

String _clean(String s) {
  final t = s
      .replaceAll(RegExp(r'[═╡╞║╔╗╚╝╠╣╬─│┌┐└┘├┤┬┴┼]+'), ' ')
      .replaceAll('EXCEPTION CAUGHT BY FLUTTER TEST FRAMEWORK', '')
      // GỘP space/tab trong dòng nhưng GIỮ '\n' — để backend tách được Expected:/Actual:/reason
      // → phân loại lỗi (error.code) + actual gọn chính xác. ĐỪNG gộp '\n' thành ' '.
      .replaceAll(RegExp(r'[ \t]+'), ' ')
      .replaceAll(RegExp(r'\n{2,}'), '\n')
      .trim();
  return t.length > 1200 ? '${t.substring(0, 1200)}…' : t;
}

// ĐIỂM THEO ĐỘ KHÓ: basic=1, intermediate=2, advanced=3 (null nếu không khai difficulty → dùng weight).
double? _difficultyPoints(String? d) {
  switch (d?.trim().toLowerCase()) {
    case 'basic':
      return 1;
    case 'intermediate':
      return 2;
    case 'advanced':
      return 3;
  }
  return null;
}

Future<Map<String, dynamic>> _analyzeLib() async {
  try {
    final r = await Process.run(
      'dart', ['analyze', 'lib', '--format', 'machine'],
      runInShell: true,
    );
    final warnings = <Map<String, dynamic>>[];
    bool hasError = false;
    for (final line in r.stdout.toString().split('\n')) {
      // SEVERITY|TYPE|CODE|FILE|LINE|COL|LENGTH|MESSAGE
      final p = line.split('|');
      if (p.length >= 8) {
        final severity = p[0].toLowerCase();
        if (severity == 'error') hasError = true;
        warnings.add({
          'file': p[3],
          'message': p.sublist(7).join('|'),
          'severity': severity,
        });
      }
    }
    return {'has_error': hasError, 'warnings': warnings};
  } catch (_) {
    return {'has_error': false, 'warnings': <Map<String, dynamic>>[]};
  }
}
