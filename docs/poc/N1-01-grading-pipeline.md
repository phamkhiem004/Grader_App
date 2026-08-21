# N1-01 — Thứ tự thực thi trong container chấm

## Mục tiêu

`grader-base/run_grader.sh` là bộ điều phối duy nhất trong container. Mọi bộ đề
dùng chung thứ tự dưới đây; runner riêng của đề chỉ cung cấp hành vi hoặc unit
test, không tự thay đổi thứ tự pipeline.

```text
STATIC_ANALYSIS → BEHAVIOR_REPLAY → UNIT_TEST → RESULT_MERGE
```

Các tầng độc lập. Static hoặc behavior lỗi không được tự động ngăn unit test
chạy. Việc quy lỗi cho sinh viên, bộ testcase hay môi trường chỉ thực hiện ở
`RESULT_MERGE` sau khi đã có log và exit code của từng tầng.

## Hợp đồng từng tầng

| Tầng | Runner mặc định | Đầu ra |
|---|---|---|
| `STATIC_ANALYSIS` | `/app/scripts/static_checks.dart` | `/tmp/grader-pipeline/static-analysis.json` |
| `BEHAVIOR_REPLAY` | `/app/test/grader.dart` | stdout/stderr riêng trong `/tmp/grader-pipeline` |
| `UNIT_TEST` | `/app/test/unit_grader.dart` | `/tmp/grader-pipeline/unit-tests.json` |
| `RESULT_MERGE` | `/app/scripts/merge_grade_results.dart` | Một khối `GRADE_RESULT` cuối cùng |

`STATIC_ANALYSIS`, `UNIT_TEST` và `RESULT_MERGE` là runner tùy chọn trong giai
đoạn chuyển đổi. Nếu chưa tồn tại, pipeline ghi `SKIPPED`. `BEHAVIOR_REPLAY` là
runner bắt buộc và tiếp tục hoạt động giống hệ thống Golden hiện tại.

Mỗi tầng được bao bởi marker máy đọc:

```text
###GRADER_PIPELINE_STAGE### {"stage":"STATIC_ANALYSIS","event":"START"}
###GRADER_PIPELINE_STAGE### {"stage":"STATIC_ANALYSIS","event":"END","exit_code":0}
```

Tầng không có runner phát `event=SKIPPED`; đây không phải lỗi của sinh viên.

## Quy tắc xuất kết quả

Backend hiện đọc khối `--- GRADE_RESULT_START ---` đầu tiên. Vì vậy khi merger
đã tồn tại, chỉ output của `RESULT_MERGE` được công bố ra stdout của container.
Output legacy của behavior (kể cả marker cũ) vẫn được giữ trong file log để
merger đọc, nhưng không được phát thêm ra stdout.

Trong chế độ chuyển đổi chưa có merger, script phát nguyên output của
`/app/test/grader.dart` để bảo toàn kết quả chấm Golden hiện có.

Sau khi sửa `run_grader.sh`, phải build lại `grading-base:latest`; backend chạy
`docker run` từ image này nên thay đổi source chưa được bake vào image sẽ chưa
ảnh hưởng tới phiên chấm thật:

```powershell
docker build -f grader-base/Dockerfile.base -t grading-base:latest grader-base
```

## Biến môi trường

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `GRADER_ANALYZE_LIB` | `true` | Bật/tắt tầng phân tích tĩnh |
| `GRADER_ANALYSIS_TIMEOUT_SECONDS` | `45` | Timeout riêng do static runner sử dụng |
| `GRADER_SOURCE_DIR` | `/app/lib` | Chỉ quét mã nguồn sinh viên |
| `GRADER_PIPELINE_DIR` | `/tmp/grader-pipeline` | Thư mục artifact tạm của một bài |
| `GRADER_STATIC_RUNNER` | `/app/scripts/static_checks.dart` | Cho phép thay runner static khi kiểm thử |
| `GRADER_BEHAVIOR_RUNNER` | `/app/test/grader.dart` | Runner Golden của bộ đề |
| `GRADER_UNIT_RUNNER` | `/app/test/unit_grader.dart` | Runner unit do N3 cung cấp |
| `GRADER_MERGE_RUNNER` | `/app/scripts/merge_grade_results.dart` | Runner hợp nhất kết quả |

## Trách nhiệm bàn giao tiếp theo

- N1-05/N1-06: cung cấp `static_checks.dart`; lint không đạt vẫn trả fragment
  hợp lệ, còn lỗi công cụ/timeout trả exit code khác 0.
- N1-07: cung cấp merger và ghép 3–5 tiêu chí static vào kết quả chung.
- N3: cung cấp `unit_grader.dart` và fragment unit test theo schema thống nhất.
- N5-01: chốt các trường của một dòng tiêu chí để merger phát đúng schema.

## Tiêu chí nghiệm thu N1-01

1. Marker thể hiện đúng thứ tự bốn tầng.
2. Thiếu static/unit/merger không làm bài Golden hiện tại mất điểm.
3. Một tầng lỗi không ngăn tầng tiếp theo được gọi.
4. Khi chưa có merger, exit code và `GRADE_RESULT` vẫn lấy từ behavior runner.
5. Không tầng nào ngoài merger phát thêm khối `GRADE_RESULT` khi merger hoạt động.

Đã xác minh trong `grading-base:latest`: static trả exit code 7 nhưng behavior
và unit vẫn chạy; merger nhận đủ artifact và stdout chỉ có một khối kết quả.
Chế độ chưa có merger cũng giữ nguyên kết quả và exit code của behavior runner.
