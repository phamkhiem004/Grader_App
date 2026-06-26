import pytest
from pydantic import ValidationError

from app.schemas import Exam, FeedbackRequest, GradingResult, Student, TestCase as FeedbackTestCase


def test_rejects_score_greater_than_total_score():
    with pytest.raises(ValidationError):
        FeedbackRequest(
            student=Student(id="HE180001"),
            exam=Exam(code="PRM393_PE", title="Flutter Practical Exam", total_score=10),
            grading_result=GradingResult(score=11, passed_tests=1, failed_tests=0, total_tests=1),
            test_cases=[
                FeedbackTestCase(
                    test_id="TC_UI_001",
                    name="Render login screen",
                    status="passed",
                    skill_code="ui_layout",
                )
            ],
        )


def test_rejects_negative_test_weight():
    with pytest.raises(ValidationError):
        FeedbackTestCase(
            test_id="TC_UI_001",
            name="Render login screen",
            status="passed",
            skill_code="ui_layout",
            weight=-1,
        )


def test_accepts_final_web_app_test_case_shape():
    test_case = FeedbackTestCase(
        test_id="TC_COLL_02",
        name="nextId danh sach tuan tu",
        status="failed",
        score=0.0,
        max_score=0.3,
        difficulty="basic",
        category="DART_ESSENTIALS",
        assignment="Bai 1",
        description="Luong chinh",
        expected="max + 1",
        actual="1",
        skill="Dart Collections",
        skill_code="DART_COLLECTIONS",
        skill_name="Collection & logic thuan",
        category_label="Dart Essentials",
        difficulty_label="Co ban",
        error={"code": "VALUE_MISMATCH", "message": "nextId phai bang max + 1"},
    )

    assert test_case.effective_skill_code == "DART_COLLECTIONS"
    assert test_case.effective_weight == 0.3
    assert test_case.earned_score == 0.0
    assert test_case.error_code == "VALUE_MISMATCH"
