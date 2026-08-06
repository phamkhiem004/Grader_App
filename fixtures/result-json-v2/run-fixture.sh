#!/usr/bin/env bash
# Chấm bộ fixture bằng engine chung và đối chiếu với kết quả kỳ vọng.
#
#   ./run-fixture.sh              # chấm cả 4 bài
#   ./run-fixture.sh medium       # chấm 1 bài
#
# Yêu cầu: Docker đang chạy và đã có ảnh `grading-base:latest`
# (build bằng grader-base/build-base.ps1).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
ENGINE="$REPO/grader/src/main/resources/common-testcase-engine"
BUILD="$HERE/.build"
IMAGE="${GRADER_IMAGE:-grading-base:latest}"

# Docker trên Windows cần đường dẫn kiểu ổ đĩa; Git Bash cho ra dạng /d/... .
to_host_path() {
  case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) (cd "$1" && pwd -W) ;;
    *) printf '%s' "$1" ;;
  esac
}

VARIANTS=("$@")
if [ ${#VARIANTS[@]} -eq 0 ]; then
  VARIANTS=(high medium sloppy broken-boot broken-compile)
fi

# Thư mục testcase = engine chung (grader + exam_test) + matrix của đề fixture.
rm -rf "$BUILD/test"
mkdir -p "$BUILD/test" "$BUILD/out"
cp "$ENGINE/grader.dart" "$ENGINE/exam_test.dart" "$BUILD/test/"
cp "$HERE/exam/skills_matrix.json" "$BUILD/test/"

TEST_HOST="$(to_host_path "$BUILD/test")"
failures=0

for variant in "${VARIANTS[@]}"; do
  lib="$HERE/submissions/$variant/lib"
  if [ ! -d "$lib" ]; then
    echo "!! Không có bài nộp: $variant" >&2
    failures=$((failures + 1))
    continue
  fi

  echo "=== $variant ==="
  raw="$BUILD/out/$variant.log"
  MSYS_NO_PATHCONV=1 docker run --rm --name "fixture-$variant" \
    --memory 4g --cpus 2 \
    -v "$(to_host_path "$lib"):/app/lib" \
    -v "$TEST_HOST:/app/test" \
    "$IMAGE" ./run_grader.sh >"$raw" 2>&1 || true

  sed -n '/GRADE_RESULT_START/,/GRADE_RESULT_END/p' "$raw" \
    | grep -v -- '--- GRADE_RESULT' >"$BUILD/out/$variant.json" || true

  if ! python "$HERE/check_fixture.py" "$variant" \
      "$BUILD/out/$variant.json" "$HERE/expected/$variant.json"; then
    failures=$((failures + 1))
  fi
done

echo
if [ "$failures" -eq 0 ]; then
  echo "TẤT CẢ khớp kỳ vọng."
else
  echo "$failures bài LỆCH kỳ vọng."
fi
exit "$failures"
