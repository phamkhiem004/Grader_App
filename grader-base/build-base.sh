#!/usr/bin/env bash
# build-base.sh — Build ảnh nền dùng chung (chạy 1 lần, hoặc khi đổi pubspec.base.yaml)
# Dùng:  ./build-base.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DOCKER_BUILDKIT=1

echo "Building grading-base:latest (lần đầu có thể mất vài phút)..."
docker build -f "$HERE/Dockerfile.base" -t grading-base:latest "$HERE"
echo "✅ grading-base:latest đã sẵn sàng. Các đề thi sẽ build trong vài giây."
