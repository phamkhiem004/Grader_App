#!/usr/bin/env bash
set -uo pipefail

# Bộ điều phối duy nhất của container chấm. Thứ tự này là hợp đồng giữa N1, N3
# và bộ Golden; không đưa logic riêng của từng đề vào file này.
readonly PIPELINE_MARKER='###GRADER_PIPELINE_STAGE###'
readonly PIPELINE_DIR="${GRADER_PIPELINE_DIR:-/tmp/grader-pipeline}"
readonly STATIC_RUNNER="${GRADER_STATIC_RUNNER:-/app/scripts/static_checks.dart}"
readonly BEHAVIOR_RUNNER="${GRADER_BEHAVIOR_RUNNER:-/app/test/grader.dart}"
readonly UNIT_RUNNER="${GRADER_UNIT_RUNNER:-/app/test/unit_grader.dart}"
readonly MERGE_RUNNER="${GRADER_MERGE_RUNNER:-/app/scripts/merge_grade_results.dart}"

readonly STATIC_FRAGMENT="$PIPELINE_DIR/static-analysis.json"
readonly STATIC_STDOUT="$PIPELINE_DIR/static-analysis.stdout.log"
readonly STATIC_STDERR="$PIPELINE_DIR/static-analysis.stderr.log"
readonly BEHAVIOR_STDOUT="$PIPELINE_DIR/behavior.stdout.log"
readonly BEHAVIOR_STDERR="$PIPELINE_DIR/behavior.stderr.log"
readonly UNIT_FRAGMENT="$PIPELINE_DIR/unit-tests.json"
readonly UNIT_STDOUT="$PIPELINE_DIR/unit-tests.stdout.log"
readonly UNIT_STDERR="$PIPELINE_DIR/unit-tests.stderr.log"

mkdir -p "$PIPELINE_DIR"

xvfb_pid=''
cleanup() {
  if [[ -n "$xvfb_pid" ]]; then
    kill "$xvfb_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# flutter_tester chạy headless; Xvfb chỉ dự phòng cho test cần display ảo.
if command -v Xvfb >/dev/null 2>&1; then
  Xvfb :99 -screen 0 1920x1080x24 >/dev/null 2>&1 &
  xvfb_pid=$!
  export DISPLAY=:99
fi

emit_start() {
  printf '%s {"stage":"%s","event":"START"}\n' "$PIPELINE_MARKER" "$1"
}

emit_end() {
  printf '%s {"stage":"%s","event":"END","exit_code":%s}\n' \
    "$PIPELINE_MARKER" "$1" "$2"
}

emit_skip() {
  printf '%s {"stage":"%s","event":"SKIPPED","reason":"%s"}\n' \
    "$PIPELINE_MARKER" "$1" "$2"
}

# Không dùng set -e giữa các tầng: một tầng lỗi không được làm mất cơ hội chấm
# của tầng sau. Mỗi tầng ghi stdout/stderr riêng để bước hợp nhất phân loại đúng.
run_stage() {
  local stage="$1"
  local stdout_file="$2"
  local stderr_file="$3"
  shift 3

  emit_start "$stage"
  "$@" >"$stdout_file" 2>"$stderr_file"
  local exit_code=$?
  emit_end "$stage" "$exit_code"
  return "$exit_code"
}

echo "Bắt đầu pipeline chấm bài..."

# 1. PHÂN TÍCH TĨNH ---------------------------------------------------------
# N1-05 sẽ cung cấp runner này. Lint không đạt vẫn phải trả fragment và exit 0;
# exit khác 0 chỉ dành cho lỗi công cụ/timeout để bước hợp nhất phân loại.
static_code=0
if [[ "${GRADER_ANALYZE_LIB:-true}" == "false" ]]; then
  emit_skip 'STATIC_ANALYSIS' 'DISABLED'
elif [[ ! -f "$STATIC_RUNNER" ]]; then
  emit_skip 'STATIC_ANALYSIS' 'RUNNER_NOT_AVAILABLE'
else
  run_stage 'STATIC_ANALYSIS' "$STATIC_STDOUT" "$STATIC_STDERR" \
    env \
      GRADER_SOURCE_DIR="${GRADER_SOURCE_DIR:-/app/lib}" \
      GRADER_FRAGMENT_OUTPUT="$STATIC_FRAGMENT" \
      GRADER_ANALYSIS_TIMEOUT_SECONDS="${GRADER_ANALYSIS_TIMEOUT_SECONDS:-45}" \
      dart "$STATIC_RUNNER"
  static_code=$?
fi

# 2. CHẤM HÀNH VI GOLDEN ----------------------------------------------------
# Đây là runner đang dùng ở production. Luôn chạy kể cả static analysis lỗi.
behavior_code=0
if [[ ! -f "$BEHAVIOR_RUNNER" ]]; then
  emit_start 'BEHAVIOR_REPLAY'
  printf 'Không tìm thấy behavior runner: %s\n' "$BEHAVIOR_RUNNER" >&2
  behavior_code=127
  emit_end 'BEHAVIOR_REPLAY' "$behavior_code"
else
  run_stage 'BEHAVIOR_REPLAY' "$BEHAVIOR_STDOUT" "$BEHAVIOR_STDERR" \
    dart "$BEHAVIOR_RUNNER"
  behavior_code=$?
fi

# 3. KIỂM THỬ ĐƠN VỊ --------------------------------------------------------
# N3 cung cấp file tùy chọn này. Bộ Golden chưa có unit runner vẫn chấm bình
# thường và ghi rõ SKIPPED, không coi là sinh viên làm sai.
unit_code=0
if [[ ! -f "$UNIT_RUNNER" ]]; then
  emit_skip 'UNIT_TEST' 'RUNNER_NOT_AVAILABLE'
else
  run_stage 'UNIT_TEST' "$UNIT_STDOUT" "$UNIT_STDERR" \
    env GRADER_FRAGMENT_OUTPUT="$UNIT_FRAGMENT" dart "$UNIT_RUNNER"
  unit_code=$?
fi

# 4. HỢP NHẤT KẾT QUẢ -------------------------------------------------------
# Khi N1-07/N3 hoàn tất, merger đọc ba artifact và là thành phần DUY NHẤT được
# phát marker GRADE_RESULT. Trước thời điểm đó, giữ nguyên output behavior để
# không làm thay đổi kết quả của các bộ Golden hiện tại.
if [[ -f "$MERGE_RUNNER" ]]; then
  emit_start 'RESULT_MERGE'
  env \
    GRADER_STATIC_FRAGMENT="$STATIC_FRAGMENT" \
    GRADER_STATIC_STDOUT="$STATIC_STDOUT" \
    GRADER_STATIC_STDERR="$STATIC_STDERR" \
    GRADER_STATIC_EXIT_CODE="$static_code" \
    GRADER_BEHAVIOR_STDOUT="$BEHAVIOR_STDOUT" \
    GRADER_BEHAVIOR_STDERR="$BEHAVIOR_STDERR" \
    GRADER_BEHAVIOR_EXIT_CODE="$behavior_code" \
    GRADER_UNIT_FRAGMENT="$UNIT_FRAGMENT" \
    GRADER_UNIT_STDOUT="$UNIT_STDOUT" \
    GRADER_UNIT_STDERR="$UNIT_STDERR" \
    GRADER_UNIT_EXIT_CODE="$unit_code" \
    dart "$MERGE_RUNNER"
  merge_code=$?
  emit_end 'RESULT_MERGE' "$merge_code"
  exit "$merge_code"
fi

emit_skip 'RESULT_MERGE' 'RUNNER_NOT_AVAILABLE'

# Chế độ tương thích: grader.dart hiện tại vẫn là nguồn kết quả duy nhất.
if [[ -f "$BEHAVIOR_STDOUT" ]]; then
  cat "$BEHAVIOR_STDOUT"
fi
if [[ -s "$BEHAVIOR_STDERR" ]]; then
  cat "$BEHAVIOR_STDERR" >&2
fi

exit "$behavior_code"
