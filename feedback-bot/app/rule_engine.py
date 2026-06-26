import json
from functools import lru_cache
from typing import Any

from app.config import COMMENT_BANK_PATH
from app.schemas import FeedbackRequest


DEFAULT_COMMENTS = {
    "passed": [
        "Em đã đáp ứng được một phần yêu cầu của kỹ năng này."
    ],
    "failed": [
        "Bài làm chưa đáp ứng đầy đủ yêu cầu của kỹ năng này."
    ],
    "recommendations": [
        "Em nên ôn lại yêu cầu liên quan và tự kiểm thử thêm các trường hợp biên."
    ],
}


@lru_cache(maxsize=1)
def load_comment_bank() -> dict:
    with COMMENT_BANK_PATH.open("r", encoding="utf-8") as f:
        return json.load(f)


def get_level(score: float, total_score: float) -> str:
    if total_score <= 0:
        return "Không xác định"

    ratio = score / total_score

    if ratio >= 0.9:
        return "Xuất sắc"
    if ratio >= 0.8:
        return "Tốt"
    if ratio >= 0.65:
        return "Khá"
    if ratio >= 0.5:
        return "Trung bình"
    return "Cần cải thiện"


def build_data_quality_warnings(request: FeedbackRequest) -> list[str]:
    warnings = []

    if request.test_cases_are_partial:
        return warnings

    actual_total = len(request.test_cases)
    actual_passed = sum(1 for tc in request.test_cases if tc.status == "passed")
    actual_failed_or_error = sum(
        1 for tc in request.test_cases if tc.status in ["failed", "error"]
    )

    if request.grading_result.total_tests != actual_total:
        warnings.append(
            f"Số lượng test case chi tiết ({actual_total}) không khớp với total_tests "
            f"({request.grading_result.total_tests})."
        )

    if request.grading_result.passed_tests != actual_passed:
        warnings.append(
            f"Số test passed chi tiết ({actual_passed}) không khớp với passed_tests "
            f"({request.grading_result.passed_tests})."
        )

    if request.grading_result.failed_tests != actual_failed_or_error:
        warnings.append(
            f"Số test failed/error chi tiết ({actual_failed_or_error}) không khớp với failed_tests "
            f"({request.grading_result.failed_tests})."
        )

    return warnings


def build_skill_summary(request: FeedbackRequest) -> list[dict[str, Any]]:
    summary_map: dict[str, dict[str, Any]] = {}

    for test_case in request.test_cases:
        skill_code = test_case.effective_skill_code

        if skill_code not in summary_map:
            summary_map[skill_code] = {
                "skill_code": skill_code,
                "skill_name": test_case.skill_name,
                "skill": test_case.skill,
                "category": test_case.category,
                "category_label": test_case.category_label,
                "passed": 0,
                "failed": 0,
                "skipped": 0,
                "errors": 0,
                "total": 0,
                "earned_score": 0.0,
                "passed_weight": 0.0,
                "failed_weight": 0.0,
                "skipped_weight": 0.0,
                "error_weight": 0.0,
                "total_weight": 0.0,
                "status": "unknown",
            }

        item = summary_map[skill_code]
        weight = test_case.effective_weight

        item["total"] += 1
        item["earned_score"] += test_case.earned_score
        item["total_weight"] += weight

        if test_case.status == "passed":
            item["passed"] += 1
            item["passed_weight"] += weight
        elif test_case.status == "failed":
            item["failed"] += 1
            item["failed_weight"] += weight
        elif test_case.status == "error":
            item["errors"] += 1
            item["error_weight"] += weight
        elif test_case.status == "skipped":
            item["skipped"] += 1
            item["skipped_weight"] += weight

    for item in summary_map.values():
        total_weight = item["total_weight"]
        failed_weight = item["failed_weight"] + item["error_weight"]

        if total_weight <= 0:
            item["status"] = "unknown"
        elif item["errors"] > 0:
            item["status"] = "needs_review"
        elif failed_weight == 0:
            item["status"] = "strong"
        elif failed_weight / total_weight >= 0.5:
            item["status"] = "weak"
        else:
            item["status"] = "needs_improvement"

    return list(summary_map.values())


def build_safe_evidence_comment(test_case) -> str:
    if test_case.is_hidden:
        return (
            test_case.student_safe_summary
            or f"Một số test ẩn của kỹ năng {test_case.effective_skill_code} chưa đạt."
        )

    reason = (
        test_case.student_safe_summary
        or test_case.error_message
        or test_case.actual
        or test_case.description
        or "Test case chưa đạt yêu cầu."
    )

    parts = [f"{test_case.name}: {reason}"]

    if test_case.expected:
        parts.append(f"Yêu cầu: {test_case.expected}")
    if test_case.actual and test_case.actual != reason:
        parts.append(f"Kết quả thực tế: {test_case.actual}")
    if test_case.error_code:
        parts.append(f"Mã lỗi: {test_case.error_code}")

    return " | ".join(parts)


def build_rule_based_evidence(request: FeedbackRequest) -> dict:
    comment_bank = load_comment_bank()

    strengths = []
    weaknesses = []
    recommendations = []
    evidence = []

    for test_case in request.test_cases:
        skill_code = test_case.effective_skill_code
        skill_comments = comment_bank.get(skill_code, DEFAULT_COMMENTS)

        if test_case.status == "passed":
            strengths.extend(skill_comments.get("passed", []))

        elif test_case.status in ["failed", "error"]:
            weaknesses.extend(skill_comments.get("failed", []))
            recommendations.extend(skill_comments.get("recommendations", []))

            evidence.append({
                "test_id": test_case.test_id,
                "skill_code": skill_code,
                "skill_name": test_case.skill_name,
                "skill": test_case.skill,
                "category": test_case.category,
                "category_label": test_case.category_label,
                "difficulty": test_case.difficulty,
                "difficulty_label": test_case.difficulty_label,
                "score": test_case.score,
                "max_score": test_case.max_score,
                "error_code": test_case.error_code,
                "status": test_case.status,
                "weight": test_case.effective_weight,
                "comment": build_safe_evidence_comment(test_case),
                "is_hidden": test_case.is_hidden,
            })

    evidence.sort(
        key=lambda item: (
            item["status"] == "error",
            item["weight"],
        ),
        reverse=True
    )

    return {
        "level": get_level(
            request.grading_result.score,
            request.exam.total_score
        ),
        "skill_summary": build_skill_summary(request),
        "competency_assessment": [
            item.model_dump()
            for item in request.competency_assessment
        ],
        "strengths": list(dict.fromkeys(strengths)),
        "weaknesses": list(dict.fromkeys(weaknesses)),
        "recommendations": list(dict.fromkeys(recommendations)),
        "evidence": evidence,
        "data_quality_warnings": build_data_quality_warnings(request),
    }
