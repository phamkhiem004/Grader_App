from types import SimpleNamespace

from app.feedback_engine import generate_feedback_text
from app.schemas import Exam, FeedbackRequest, GradingResult, Student, TestCase as FeedbackTestCase


def make_request() -> FeedbackRequest:
    return FeedbackRequest(
        student=Student(id="HE180001", name="Nguyen Van A"),
        exam=Exam(code="PRM393_PE", title="Flutter Practical Exam", total_score=10),
        grading_result=GradingResult(score=7, passed_tests=1, failed_tests=1, total_tests=2),
        test_cases=[
            FeedbackTestCase(
                test_id="TC_UI_001",
                name="Render login screen",
                status="passed",
                skill_code="ui_layout",
                skill_name="Flutter UI",
            ),
            FeedbackTestCase(
                test_id="TC_FORM_001",
                name="Validate empty email",
                status="failed",
                skill_code="form_validation",
                skill_name="Form validation",
                actual="Khong hien thi thong bao loi.",
            ),
        ],
    )


def make_final_shape_request() -> FeedbackRequest:
    return FeedbackRequest(
        student=Student(id="HE123456", name="5,6d"),
        exam=Exam(code="PE_50", title="PE", total_score=10),
        grading_result=GradingResult(
            score=4.95,
            total_raw_score=4.95,
            passed_tests=1,
            failed_tests=1,
            total_tests=2,
        ),
        test_cases=[
            FeedbackTestCase(
                test_id="TC_CLASS_01",
                name="Khoi tao Expense",
                status="passed",
                score=0.25,
                max_score=0.25,
                difficulty="basic",
                category="DART_ESSENTIALS",
                assignment="Bai 1",
                description="Constructor khong crash",
                expected="Thanh cong",
                skill="Dart Model",
                skill_code="DART_CLASSES",
                skill_name="Class & constructor",
                category_label="Dart Essentials",
                difficulty_label="Co ban",
            ),
            FeedbackTestCase(
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
            ),
        ],
        competency_assessment=[
            {
                "category": "DART_ESSENTIALS",
                "label": "Dart Essentials",
                "passed_weight": 0.25,
                "total_weight": 0.55,
                "ratio": 0.45,
                "level": "YEU",
                "passed_tests": 1,
                "total_tests": 2,
                "by_difficulty": {"basic": "1/2"},
                "weak_skills": ["DART_COLLECTIONS"],
            }
        ],
        teacher_note="PE",
    )


def test_generate_feedback_uses_llm_when_available(monkeypatch):
    monkeypatch.setattr(
        "app.feedback_engine.retrieve_context",
        lambda request, rule_data: [{"source": "skills/test.md", "content": "context"}],
    )

    class FakeClient:
        def __init__(self, timeout):
            self.timeout = timeout

        def chat(self, **kwargs):
            return SimpleNamespace(message=SimpleNamespace(content="Feedback tu LLM"))

    monkeypatch.setattr("app.feedback_engine.Client", FakeClient)

    response = generate_feedback_text(make_request())

    assert response.feedback_text == "Feedback tu LLM"
    assert response.teacher_review_required is False
    assert response.sources == ["skills/test.md"]
    assert response.review_reasons == []


def test_generate_feedback_falls_back_when_rag_fails(monkeypatch):
    def broken_retrieval(request, rule_data):
        raise RuntimeError("vector store down")

    monkeypatch.setattr("app.feedback_engine.retrieve_context", broken_retrieval)

    class FakeClient:
        def __init__(self, timeout):
            self.timeout = timeout

        def chat(self, **kwargs):
            return SimpleNamespace(message=SimpleNamespace(content=""))

    monkeypatch.setattr("app.feedback_engine.Client", FakeClient)

    response = generate_feedback_text(make_request())

    assert response.teacher_review_required is True
    assert "7.0/10.0" in response.feedback_text
    assert any("RAG" in reason for reason in response.review_reasons)
    assert any("rong" in reason.lower() or "rỗng" in reason for reason in response.review_reasons)


def test_generate_feedback_accepts_final_web_app_shape(monkeypatch):
    monkeypatch.setattr(
        "app.feedback_engine.retrieve_context",
        lambda request, rule_data: [{"source": "skills/test.md", "content": "context"}],
    )

    class FakeClient:
        def __init__(self, timeout):
            self.timeout = timeout

        def chat(self, **kwargs):
            return SimpleNamespace(message=SimpleNamespace(content="Feedback final shape"))

    monkeypatch.setattr("app.feedback_engine.Client", FakeClient)

    response = generate_feedback_text(make_final_shape_request())

    assert response.feedback_text == "Feedback final shape"
    assert response.score_summary == "4.95/10.0"
    assert response.teacher_review_required is False
