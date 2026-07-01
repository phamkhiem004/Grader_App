from functools import lru_cache
from typing import List, Optional

from langchain_chroma import Chroma
from langchain_ollama import OllamaEmbeddings

from app.config import (
    CHROMA_DIR,
    EMBED_MODEL_NAME,
    FEEDBACK_RAG_K_LARGE,
    FEEDBACK_RAG_K_MEDIUM,
    FEEDBACK_RAG_K_SMALL,
)
from app.schemas import FeedbackRequest


@lru_cache(maxsize=1)
def get_embeddings() -> OllamaEmbeddings:
    return OllamaEmbeddings(model=EMBED_MODEL_NAME)


@lru_cache(maxsize=1)
def get_vectorstore() -> Chroma:
    return Chroma(
        persist_directory=str(CHROMA_DIR),
        embedding_function=get_embeddings()
    )


def reset_vectorstore_cache() -> None:
    get_embeddings.cache_clear()
    get_vectorstore.cache_clear()


def get_focus_skills(rule_data: dict) -> tuple[list[str], list[str]]:
    skill_summary = rule_data.get("skill_summary", [])

    weak_skills = []
    strong_skills = []

    for item in skill_summary:
        skill_code = item.get("skill_code")
        status = item.get("status")

        if not skill_code:
            continue

        if status in ["weak", "needs_improvement", "needs_review"]:
            weak_skills.append(skill_code)
        elif status == "strong":
            strong_skills.append(skill_code)

    return weak_skills, strong_skills


def build_rag_query(request: FeedbackRequest, rule_data: dict) -> str:
    weak_skills, strong_skills = get_focus_skills(rule_data)
    evidence = rule_data.get("evidence", [])
    competencies = rule_data.get("competency_assessment", [])

    evidence_summaries = [
        " | ".join(
            part for part in [
                item.get("skill_code"),
                item.get("category_label") or item.get("category"),
                item.get("difficulty_label") or item.get("difficulty"),
                item.get("error_code"),
                item.get("comment", ""),
            ]
            if part
        )
        for item in evidence[:10]
        if item.get("comment")
    ]

    weak_competencies = [
        f"{item.get('label') or item.get('category')}: {item.get('level')} "
        f"({item.get('passed_tests')}/{item.get('total_tests')} tests, "
        f"{item.get('passed_weight')}/{item.get('total_weight')} điểm), "
        f"weak_skills={', '.join(item.get('weak_skills', []))}"
        for item in competencies
        if item.get("weak_skills") or float(item.get("ratio") or 0) < 0.65
    ]

    query = f"""
Bài thi Flutter PRM393.

Mục tiêu bài thi:
{request.teacher_note or ""}

Kết quả tổng quan:
- Điểm: {request.grading_result.score}/{request.exam.total_score}
- Raw score: {request.grading_result.total_raw_score}
- Số test pass: {request.grading_result.passed_tests}/{request.grading_result.total_tests}
- Số test fail: {request.grading_result.failed_tests}/{request.grading_result.total_tests}

Kỹ năng làm tốt:
{", ".join(strong_skills)}

Kỹ năng cần cải thiện:
{", ".join(weak_skills)}

Nhóm năng lực yếu:
{"; ".join(weak_competencies)}

Bằng chứng lỗi chính:
{"; ".join(evidence_summaries)}
"""

    return query.strip()


def determine_retrieval_k(rule_data: dict) -> int:
    skill_summary = rule_data.get("skill_summary", [])

    weak_count = sum(
        1
        for item in skill_summary
        if item.get("status") in ["weak", "needs_improvement", "needs_review"]
    )

    if weak_count <= 1:
        return FEEDBACK_RAG_K_SMALL
    if weak_count <= 3:
        return FEEDBACK_RAG_K_MEDIUM
    return FEEDBACK_RAG_K_LARGE


def retrieve_context(
    request: FeedbackRequest,
    rule_data: dict,
    k: Optional[int] = None
) -> List[dict]:
    if not CHROMA_DIR.exists():
        return []

    vectorstore = get_vectorstore()
    query = build_rag_query(request, rule_data)
    final_k = k if k is not None else determine_retrieval_k(rule_data)
    docs = vectorstore.similarity_search(query, k=final_k)

    return [
        {
            "source": doc.metadata.get("source", "unknown"),
            "content": doc.page_content,
        }
        for doc in docs
    ]
