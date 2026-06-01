#!/usr/bin/env bash
# prune-grader.sh — Dọn rác Docker AN TOÀN cho app chấm thi
# Chỉ xóa: image dangling (<none>) + build cache. KHÔNG đụng volume / dự án khác.
set -e
echo "== Trước =="
docker system df

docker image prune -f   >/dev/null   # xóa image <none> (vd đề build lại)
docker builder prune -f >/dev/null   # xóa build cache cũ

echo ""; echo "== Sau =="
docker system df

echo ""; echo "== Ảnh đề hiện có (xóa đề không dùng: docker rmi grading-env-<maDe>) =="
docker images "grading-env-*" --format "  {{.Repository}}  ({{.Size}})"
echo "  (Mỗi ảnh đề dùng CHUNG layer nền ~4.5GB — không nhân lên theo số đề.)"
