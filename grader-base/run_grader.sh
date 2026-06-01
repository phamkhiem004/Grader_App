#!/bin/bash
set -e

# flutter_tester chạy headless; Xvfb chỉ để phòng test nào cần display ảo.
Xvfb :99 -screen 0 1920x1080x24 >/dev/null 2>&1 &
export DISPLAY=:99

# KHÔNG pub get lại: pubspec + packages đã được resolve & "đóng băng" trong ảnh nền
# (grader.dart gọi `flutter test --no-pub`). Bỏ bước này giúp mỗi bài nhanh hơn vài giây.
echo "🧪 Bắt đầu chấm bài..."
dart run test/grader.dart

echo "✅ Hoàn tất"
