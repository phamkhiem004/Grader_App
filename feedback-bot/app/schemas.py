## dinh nghia du lieu
from typing import Dict, List, Optional, Literal
from pydantic import BaseModel, Field, model_validator

TestStatus = Literal["passed", "failed", "skipped", "error"]

class Student(BaseModel):
    id: str = Field(min_length=1)
    name: Optional[str] = None


class Exam(BaseModel):
    code: str = Field(min_length=1)
    title: str = Field(min_length=1)
    total_score: float = Field(gt=0)


class GradingResult(BaseModel):
    score: float = Field(ge=0)
    # Tong diem THO (truoc khi quy ve thang total_score). Web hien khong gui field nay →
    # mac dinh bang score de cau truy van RAG khong hien thi "None".
    total_raw_score: Optional[float] = Field(default=None, ge=0)
    passed_tests: int = Field(ge=0)
    failed_tests: int = Field(ge=0)
    total_tests: int = Field(ge=0)

    @model_validator(mode="after")
    def default_total_raw_score(self):
        if self.total_raw_score is None:
            self.total_raw_score = self.score
        return self


class TestError(BaseModel):
    code: Optional[str] = None
    message: Optional[str] = None


class TestCase(BaseModel):
    test_id: str = Field(min_length=1)
    name: str = Field(min_length=1)
    status: TestStatus
    skill: Optional[str] = None
    skill_code: Optional[str] = Field(default=None, min_length=1)
    skill_name: Optional[str] = None
    weight: Optional[float] = Field(default=None, gt=0)  ##importance level
    score: Optional[float] = Field(default=None, ge=0)
    max_score: Optional[float] = Field(default=None, gt=0)
    difficulty: Optional[str] = None
    difficulty_label: Optional[str] = None
    category: Optional[str] = None
    category_label: Optional[str] = None
    assignment: Optional[str] = None
    error: Optional[TestError] = None
    is_hidden: bool = False
    description: Optional[str] = None
    expected: Optional[str] = None
    actual: Optional[str] = None
    student_safe_summary: Optional[str] = None

    @model_validator(mode="after")
    def validate_skill_code(self):
        if not self.skill_code:
            raise ValueError("skill_code is required")
        return self

    @property
    def effective_skill_code(self) -> str:
        return self.skill_code or "unknown_skill"

    @property
    def effective_weight(self) -> float:
        return float(self.weight or self.max_score or 1.0)

    @property
    def earned_score(self) -> float:
        if self.score is not None:
            return float(self.score)
        if self.status == "passed":
            return self.effective_weight
        return 0.0

    @property
    def error_code(self) -> Optional[str]:
        return self.error.code if self.error else None

    @property
    def error_message(self) -> Optional[str]:
        return self.error.message if self.error else None


class AnalyzeWarning(BaseModel):
    file: Optional[str] = None
    message: str = Field(min_length=1)
    severity: Optional[str] = None


class AnalyzeResult(BaseModel):
    has_error: bool = False
    warnings: List[AnalyzeWarning] = Field(default_factory=list)


class CompetencyAssessment(BaseModel):
    category: str = Field(min_length=1)
    label: Optional[str] = None
    passed_weight: float = Field(ge=0)
    total_weight: float = Field(ge=0)
    ratio: float = Field(ge=0)
    level: Optional[str] = None
    passed_tests: int = Field(ge=0)
    total_tests: int = Field(ge=0)
    by_difficulty: Dict[str, str] = Field(default_factory=dict)
    weak_skills: List[str] = Field(default_factory=list)


class FeedbackRequest(BaseModel):
    student: Student
    exam: Exam
    grading_result: GradingResult

    # In v2, this should contain all available test cases.
    test_cases: List[TestCase]

    # Keep this false for your current direction.
    # If later you only send failed/important tests, set this to true.
    test_cases_are_partial: bool = False

    competency_assessment: List[CompetencyAssessment] = Field(default_factory=list)
    analyze_result: Optional[AnalyzeResult] = None
    teacher_note: Optional[str] = None

    @model_validator(mode="after")
    def validate_grading_consistency(self):
        if self.grading_result.score > self.exam.total_score:
            raise ValueError("grading_result.score cannot exceed exam.total_score")

        counted_tests = self.grading_result.passed_tests + self.grading_result.failed_tests
        if counted_tests > self.grading_result.total_tests:
            raise ValueError("passed_tests + failed_tests cannot exceed total_tests")

        if not self.test_cases_are_partial and self.test_cases:
            if self.grading_result.total_tests != len(self.test_cases):
                raise ValueError("total_tests must match test_cases length when test_cases_are_partial is false")

        return self

class SkillSummary(BaseModel):
    skill_code: str
    skill_name: Optional[str] = None

    passed: int = 0
    failed: int = 0
    skipped: int = 0
    errors: int = 0
    total: int = 0

    passed_weight: float = 0.0
    failed_weight: float = 0.0
    skipped_weight: float = 0.0
    error_weight: float = 0.0
    total_weight: float = 0.0

    status: str = "unknown"


class EvidenceItem(BaseModel):
    test_id: str
    skill_code: str
    skill_name: Optional[str] = None
    status: TestStatus
    weight: float = 1.0
    comment: str
    is_hidden: bool = False


class FeedbackTextResponse(BaseModel):
    student_id: str
    score_summary: str
    feedback_text: str
    teacher_review_required: bool = False
    sources: List[str] = Field(default_factory=list)
    review_reasons: List[str] = Field(default_factory=list)   

# Optional legacy structured response.
# You can keep this for debugging, but the final output should use FeedbackTextResponse.
class FeedbackResponse(BaseModel):
    student_id: str
    score_summary: str
    level: str
    overall_feedback: str
    strengths: List[str]
    weaknesses: List[str]
    recommendations: List[str]
    evidence: List[EvidenceItem]
    teacher_review_required: bool = False
