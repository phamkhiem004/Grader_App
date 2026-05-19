import 'dart:io';

void main() async {
  final baseFile = File('/app/pubspec_base.yaml');
  final studentFile = File('/app/pubspec_student.yaml');
  final outputFile = File('/app/pubspec.yaml');

  final baseContent = await baseFile.readAsString();
  final studentContent = await studentFile.readAsString();

  // Lấy environment sdk từ base
  final sdkVersion = _extractSdkVersion(baseContent);

  // Lấy dependencies từ student (bỏ flutter sdk block)
  final studentDeps = _extractDependencies(studentContent);

  // Lấy dependencies từ base (luôn thắng)
  final baseDeps = _extractDependencies(baseContent);

  // Merge: base đè student
  final merged = {...studentDeps, ...baseDeps};

  // Viết pubspec.yaml hoàn chỉnh
  final buffer = StringBuffer();
  buffer.writeln('name: exam_project');
  buffer.writeln('description: Grading template');
  buffer.writeln('publish_to: none');
  buffer.writeln('version: 1.0.0+1');
  buffer.writeln('');
  buffer.writeln('environment:');
  buffer.writeln("  sdk: '$sdkVersion'");
  buffer.writeln('');
  buffer.writeln('dependencies:');
  buffer.writeln('  flutter:');
  buffer.writeln('    sdk: flutter');

  // Thêm các dependency khác (bỏ qua flutter vì đã viết tay)
  for (final entry in merged.entries) {
    if (entry.key == 'flutter') continue;
    if (entry.value is Map) {
      // Block dependency (sdk: flutter kiểu)
      buffer.writeln('  ${entry.key}:');
      buffer.writeln('    sdk: flutter');
    } else {
      buffer.writeln('  ${entry.key}: ${entry.value}');
    }
  }

  buffer.writeln('');
  buffer.writeln('dev_dependencies:');
  buffer.writeln('  flutter_test:');
  buffer.writeln('    sdk: flutter');
  buffer.writeln('  flutter_lints: ^4.0.0');
  buffer.writeln('');
  buffer.writeln('flutter:');
  buffer.writeln('  uses-material-design: true');

  await outputFile.writeAsString(buffer.toString());
  print('✅ pubspec.yaml đã được tạo');
  print('   SDK: $sdkVersion');
  print('   Dependencies: ${merged.length}');
}

// Lấy sdk version từ environment block
String _extractSdkVersion(String yaml) {
  bool inEnv = false;
  for (final line in yaml.split('\n')) {
    if (line.trim() == 'environment:') {
      inEnv = true;
      continue;
    }
    if (inEnv) {
      if (line.isNotEmpty && !line.startsWith(' ') && !line.startsWith('\t'))
        break;
      if (line.contains('sdk:') && !line.trim().startsWith('#')) {
        final val = line
            .split('sdk:')
            .last
            .trim()
            .replaceAll("'", '')
            .replaceAll('"', '');
        // Bỏ qua nếu là "flutter" (dependency block)
        if (!val.contains('flutter')) return val;
      }
    }
  }
  return '>=3.0.0 <4.0.0';
}

// Lấy dependencies dạng key: version (bỏ block sdk: flutter)
Map<String, dynamic> _extractDependencies(String yaml) {
  final result = <String, dynamic>{};
  bool inDeps = false;
  String? currentKey;

  for (final line in yaml.split('\n')) {
    // Detect bắt đầu block dependencies
    if (line.trim() == 'dependencies:') {
      inDeps = true;
      continue;
    }

    // Detect kết thúc block
    if (inDeps &&
        line.isNotEmpty &&
        !line.startsWith(' ') &&
        !line.startsWith('\t')) {
      inDeps = false;
      currentKey = null;
      continue;
    }

    if (!inDeps) continue;
    if (line.trim().isEmpty || line.trim().startsWith('#')) continue;

    // Dòng có 2 space indent → key chính
    if (line.startsWith('  ') && !line.startsWith('    ')) {
      final parts = line.trim().split(':');
      final key = parts[0].trim();
      final val = parts.length > 1 ? parts.sublist(1).join(':').trim() : '';

      if (key.isEmpty) continue;
      currentKey = key;

      if (val.isNotEmpty) {
        // Dạng đơn: provider: ^6.0.0
        result[key] = val;
        currentKey = null;
      }
      // val rỗng → là block, đợi dòng tiếp theo
    }
    // Dòng có 4 space indent → sub-value của block
    else if (line.startsWith('    ') && currentKey != null) {
      if (line.trim().startsWith('sdk:')) {
        result[currentKey!] = {'sdk': 'flutter'};
      }
      // Bỏ qua các sub-value khác (path, git, v.v.)
    }
  }

  return result;
}
