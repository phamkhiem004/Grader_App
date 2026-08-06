#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""So kết quả chấm một bài fixture với file kỳ vọng.

    python check_fixture.py <tên bài> <kết quả.json> <kỳ vọng.json>

Chỉ so ĐIỂM, DANH SÁCH test hỏng và cách chia `failed`/`not_run` — đó là phần phải bất
biến qua các phase. Nội dung `actual` cố tình KHÔNG so, vì P5 sẽ thay đổi nó.

`not_run_ids` được ghim riêng vì nó mang một khẳng định NHÂN QUẢ: test chưa có cơ hội chạy,
khác hẳn đã chạy và không đạt. Chỉ đếm số lượng thì không bắt được lỗi gán sai test nào.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path


def summarize(result: dict) -> dict:
    cases = result.get("test_cases") or []
    return {
        "diem": result.get("diem"),
        "passed": result.get("soTestPass"),
        "failed": result.get("soTestFail"),
        "total": result.get("tongSoTest"),
        # failed_ids giữ nghĩa cũ = mọi test không passed, not_run là tập con (SPEC mục 2).
        "failed_ids": sorted(str(tc.get("test_id")) for tc in cases
                             if tc.get("status") != "passed"),
        "not_run_ids": sorted(str(tc.get("test_id")) for tc in cases
                              if tc.get("status") == "not_run"),
    }


def main() -> int:
    name, actual_path, expected_path = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])

    text = actual_path.read_text(encoding="utf-8").strip() if actual_path.exists() else ""
    if not text:
        print(f"  [LỆCH] {name}: grader không xuất khối GRADE_RESULT "
              f"(xem {actual_path.with_suffix('.log').name})")
        return 1

    got = summarize(json.loads(text))
    if not expected_path.exists():
        print(f"  [MỚI]  {name}: chưa có file kỳ vọng, ghi lại kết quả hiện tại.")
        expected_path.parent.mkdir(parents=True, exist_ok=True)
        expected_path.write_text(json.dumps(got, ensure_ascii=False, indent=2) + "\n",
                                 encoding="utf-8")
        return 0

    want = json.loads(expected_path.read_text(encoding="utf-8"))
    diffs = [f"{key}: {got.get(key)!r} != {want.get(key)!r}"
             for key in ("diem", "passed", "failed", "total", "failed_ids", "not_run_ids")
             if got.get(key) != want.get(key)]
    if diffs:
        print(f"  [LỆCH] {name}: " + "; ".join(diffs))
        return 1

    print(f"  [KHỚP] {name}: điểm {got['diem']}, {got['passed']}/{got['total']} đạt")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
