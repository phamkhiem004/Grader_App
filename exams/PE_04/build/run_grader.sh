#!/bin/bash
set -e

echo "🚀 Khởi động môi trường chấm bài..."

Xvfb :99 -screen 0 1920x1080x24 &
XVFB_PID=$!
export DISPLAY=:99
sleep 1

echo "✅ Virtual display :99 sẵn sàng"

# Luôn dùng pubspec base của GV — không merge pubspec SV
# SV chỉ được dùng package GV đã cài sẵn trong image
echo "📦 Dùng pubspec base..."
cp /app/pubspec_base.yaml /app/pubspec.yaml
flutter pub get --offline

echo "🧪 Bắt đầu chấm bài..."
dart run test/grader.dart

kill $XVFB_PID 2>/dev/null || true
echo "✅ Hoàn tất"