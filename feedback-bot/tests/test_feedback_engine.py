from types import SimpleNamespace

from app.feedback_engine import generate_feedback_text, normalize_feedback_voice
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


def make_perfect_request() -> FeedbackRequest:
    return FeedbackRequest(
        student=Student(id="HE180010", name="Tran Thi B"),
        exam=Exam(code="PRM393_PE", title="Flutter Practical Exam", total_score=10),
        grading_result=GradingResult(score=10, passed_tests=2, failed_tests=0, total_tests=2),
        test_cases=[
            FeedbackTestCase(
                test_id="TC_DART_01",
                name="Model constructor",
                status="passed",
                skill_code="DART_CLASSES",
                skill_name="Class & constructor",
            ),
            FeedbackTestCase(
                test_id="TC_UI_01",
                name="Render UI",
                status="passed",
                skill_code="UI_WIDGETS",
                skill_name="Widget cơ bản",
            ),
        ],
    )


def make_invalid_request() -> FeedbackRequest:
    return FeedbackRequest(
        student=Student(id="HE180011", name="Le Van C"),
        exam=Exam(code="PRM393_PE", title="Flutter Practical Exam", total_score=10),
        grading_result=GradingResult(score=0, passed_tests=0, failed_tests=0, total_tests=0),
        test_cases=[],
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


def test_perfect_submission_does_not_require_teacher_review_when_rag_empty(monkeypatch):
    monkeypatch.setattr("app.feedback_engine.retrieve_context", lambda request, rule_data: [])

    class FakeClient:
        def __init__(self, timeout):
            self.timeout = timeout

        def chat(self, **kwargs):
            return SimpleNamespace(message=SimpleNamespace(content="Chào em, thầy/cô rất vui vì bài làm của em rất tốt."))

    monkeypatch.setattr("app.feedback_engine.Client", FakeClient)

    response = generate_feedback_text(make_perfect_request())

    assert response.teacher_review_required is False
    assert response.sources == []


def test_perfect_submission_skips_rag_and_llm(monkeypatch):
    def fail_retrieval(request, rule_data):
        raise AssertionError("RAG should not run for perfect submissions")

    def fail_llm(prompt):
        raise AssertionError("LLM should not run for perfect submissions")

    monkeypatch.setattr("app.feedback_engine.retrieve_context", fail_retrieval)
    monkeypatch.setattr("app.feedback_engine._call_llm", fail_llm)

    response = generate_feedback_text(make_perfect_request())

    assert response.teacher_review_required is False
    assert response.sources == []
    assert response.feedback_text


def test_invalid_submission_skips_rag_and_llm(monkeypatch):
    monkeypatch.setattr(
        "app.feedback_engine.retrieve_context",
        lambda request, rule_data: (_ for _ in ()).throw(AssertionError("RAG should not run")),
    )
    monkeypatch.setattr(
        "app.feedback_engine._call_llm",
        lambda prompt: (_ for _ in ()).throw(AssertionError("LLM should not run")),
    )

    response = generate_feedback_text(make_invalid_request())

    assert response.teacher_review_required is True
    assert response.sources == []
    assert response.feedback_text


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


def test_normalize_feedback_voice_fixes_reversed_teacher_student_roles():
    text = (
        "Chào thầy/cô, em đã xem bài của thầy/cô. "
        "Em thấy thầy/cô đã nắm phần nền tảng nhưng thầy/cô nên kiểm tra lại validation."
    )

    result = normalize_feedback_voice(text)

    assert "Chào em" in result
    assert "thầy/cô đã xem bài làm của em" in result
    assert "Thầy/cô thấy em đã nắm" in result
    assert "em nên kiểm tra lại validation" in result
    assert "em đã xem bài của thầy/cô" not in result.lower()
    assert "Chào thầy/cô" not in result


def test_generate_feedback_normalizes_llm_voice(monkeypatch):
    monkeypatch.setattr(
        "app.feedback_engine.retrieve_context",
        lambda request, rule_data: [{"source": "skills/test.md", "content": "context"}],
    )

    class FakeClient:
        def __init__(self, timeout):
            self.timeout = timeout

        def chat(self, **kwargs):
            return SimpleNamespace(
                message=SimpleNamespace(
                    content="Chào thầy/cô, em đã xem bài của thầy/cô và em tin thầy/cô có thể làm tốt hơn."
                )
            )

    monkeypatch.setattr("app.feedback_engine.Client", FakeClient)

    response = generate_feedback_text(make_request())

    assert response.feedback_text == "Chào em, thầy/cô đã xem bài làm của em và thầy/cô tin em có thể làm tốt hơn."
