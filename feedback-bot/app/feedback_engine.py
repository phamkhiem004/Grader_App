import json
import logging
import os
import re
import urllib.request

from ollama import Client

from app.config import (
    FEEDBACK_MODEL_NAME, OLLAMA_TIMEOUT_SECONDS,
    FEEDBACK_PROVIDER, OPENAI_API_KEY, OPENAI_MODEL, OPENAI_BASE_URL,
)
from app.schemas import FeedbackRequest, FeedbackTextResponse
from app.rule_engine import build_rule_based_evidence
from app.rag_retriever import retrieve_context
from app.prompt_builder import build_student_feedback_prompt


logger = logging.getLogger(__name__)

## xem xet viec co can giang vien can thiep khong
def should_require_teacher_review(
    request: FeedbackRequest,
    rag_context: list[dict],
    rule_data: dict
) -> bool:
    if not request.test_cases:
        return True

    if request.grading_result.total_tests <= 0:
        return True

    if rule_data.get("data_quality_warnings"):
        return True

    if len(rag_context) == 0:
        return True

    return False

## de phong khi ollama loi/ ollama ko sinh ra cau tra loi
def build_fallback_feedback(request: FeedbackRequest, rule_data: dict) -> str:
    score = request.grading_result.score
    total = request.exam.total_score
    passed = request.grading_result.passed_tests
    total_tests = request.grading_result.total_tests

    feedback = (
        f"Chào em, thầy/cô đã xem bài làm của em — bài đạt {score}/{total} điểm "
        f"và vượt qua {passed}/{total_tests} test case. "
    )

    if total_tests <= 0:
        feedback += (
            "Hiện tại hệ thống chưa có đủ dữ liệu test case để đưa ra nhận xét chi tiết. "
            "Vì vậy, feedback này chỉ mang tính tổng quan và cần giảng viên kiểm tra thêm."
        )
        return feedback

    if rule_data.get("strengths"):
        feedback += (
            "Điểm tích cực là bài làm đã đáp ứng được một số yêu cầu nhất định của đề. "
        )

    if rule_data.get("weaknesses"):
        feedback += (
            "Tuy nhiên, bài làm vẫn còn một số phần cần cải thiện. "
            + " ".join(rule_data["weaknesses"][:2])
            + " "
        )

    if rule_data.get("recommendations"):
        feedback += (
            "Để cải thiện, em nên "
            + " ".join(rule_data["recommendations"][:2])
        )

    return feedback


SYSTEM_PROMPT = (
    "Bạn là GIÁO VIÊN chấm thi, viết lời nhận xét gửi cho sinh viên sau bài thi Flutter PRM393. "
    "Văn phong ấm áp, tự nhiên, cụ thể, xây dựng — như một lá thư ngắn, KHÔNG phải báo cáo. "
    "XƯNG HÔ (bắt buộc): tự xưng 'thầy/cô', gọi sinh viên là 'em'. "
    "TUYỆT ĐỐI KHÔNG tự gọi mình là 'em'; KHÔNG gọi sinh viên là 'tôi/bạn/mình'. "
    "Chỉ dùng dữ liệu chấm và tài liệu được cung cấp; không bịa đặt; không chê bai."
)


def _chat_ollama(prompt: str) -> str:
    """LOCAL qua Ollama — miễn phí nhưng chậm trên CPU (tuần tự)."""
    client = Client(timeout=OLLAMA_TIMEOUT_SECONDS)
    response = client.chat(
        model=FEEDBACK_MODEL_NAME,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
        options={
            # Nhiệt độ THẤP để model BÁM dữ liệu, ít "sáng tác"/bịa (đổi từ 0.55 → 0.25).
            "temperature": 0.25, "top_p": 0.85, "repeat_penalty": 1.15,
            "num_ctx": 4096, "num_predict": 600,
            "num_thread": os.cpu_count() or 4,
        },
        keep_alive="30m",
    )
    return response.message.content or ""


def _chat_openai(prompt: str) -> str:
    """API tương thích OpenAI (gpt-4o-mini...) — NHANH + chạy SONG SONG được (cho hàng loạt bài)."""
    is_reasoning = OPENAI_MODEL.lower().startswith(("gpt-5", "o1", "o3", "o4"))
    body = {
        "model": OPENAI_MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ],
    }
    if is_reasoning:                      # gpt-5/o-series: không nhận temperature, dùng max_completion_tokens
        body["max_completion_tokens"] = 900
    else:
        body["temperature"] = 0.55
        body["top_p"] = 0.9
        body["max_tokens"] = 700
    req = urllib.request.Request(
        OPENAI_BASE_URL.rstrip("/") + "/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {OPENAI_API_KEY}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=OLLAMA_TIMEOUT_SECONDS) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    return (payload["choices"][0]["message"]["content"] or "")


def _call_llm(prompt: str) -> str:
    if FEEDBACK_PROVIDER == "openai" and OPENAI_API_KEY:
        return _chat_openai(prompt)
    return _chat_ollama(prompt)


def build_perfect_feedback(request: FeedbackRequest, rule_data: dict) -> str:
    """Nhận xét CHUẨN cho bài KHÔNG có lỗi (pass hết) — dựng từ kỹ năng THẬT đã đạt, KHÔNG bịa.
    Dùng khi model nhỏ lỡ bịa nhược điểm cho bài hoàn hảo → thay vào cho chắc chắn đúng."""
    gr = request.grading_result
    skill_summary = rule_data.get("skill_summary", [])
    names = [s.get("skill_name") or s.get("skill") for s in skill_summary if s.get("passed", 0) > 0]
    names = [n for n in names if n]
    skills_txt = ", ".join(names[:4]) if names else "các kỹ năng trong bài"
    return (
        f"Chào em, thầy/cô rất vui vì bài làm của em rất tốt — em đạt {gr.score}/{request.exam.total_score} điểm và vượt qua "
        f"{gr.passed_tests}/{gr.total_tests} bài kiểm tra. Em đã thể hiện vững vàng ở {skills_txt}, cho thấy em "
        f"nắm chắc kiến thức nền tảng. Bài làm không còn lỗi nào, vì vậy em hoàn toàn có thể tự tin tìm hiểu sâu "
        f"hơn để nâng cao kỹ năng. Thầy/cô tin em đang đi rất đúng hướng, cứ giữ phong độ này nhé!"
    )


def generate_feedback_text(request: FeedbackRequest) -> FeedbackTextResponse:
    rule_data = build_rule_based_evidence(request) ##chay rule engine
    review_reasons = list(rule_data.get("data_quality_warnings", []))

    try:
        rag_context = retrieve_context(request, rule_data) ##doc rag
    except Exception as exc:
        logger.exception("Failed to retrieve RAG context")
        rag_context = []
        review_reasons.append(f"Không lấy được tài liệu RAG: {type(exc).__name__}.")

    prompt = build_student_feedback_prompt(request, rule_data, rag_context) ##doc qua file quy dinh prompt cho llm

    teacher_review_required = should_require_teacher_review(request, rag_context, rule_data)

    try:
        feedback_text = (_call_llm(prompt) or "").strip()
        # Model reasoning (qwen3...) co the chen <think>...</think> -> bo di cho sach
        feedback_text = re.sub(r"<think>.*?</think>", "", feedback_text, flags=re.DOTALL).strip()

        if not feedback_text:
            feedback_text = build_fallback_feedback(request, rule_data)
            teacher_review_required = True
            review_reasons.append("LLM trả về nội dung rỗng.")

    except Exception as exc:
        logger.exception("Failed to generate feedback")
        feedback_text = build_fallback_feedback(request, rule_data)
        teacher_review_required = True
        review_reasons.append(f"Không sinh được feedback bằng LLM: {type(exc).__name__}.")

    # Sửa nhẹ xưng hô lỡ "bạn" → "em" (model nhỏ đôi khi trượt; trong nhận xét "bạn" luôn là sinh viên).
    if feedback_text:
        for a, b in (("của bạn", "của em"), ("bạn đã", "em đã"), (" bạn ", " em "),
                     (" bạn,", " em,"), (" bạn.", " em."), (" bạn!", " em!")):
            feedback_text = feedback_text.replace(a, b)

    # LƯỚI AN TOÀN: bài KHÔNG có lỗi (pass hết) nhưng feedback lại nói "cần cải thiện/..." → model nhỏ BỊA.
    # → THAY bằng nhận xét chuẩn (template đúng, bám kỹ năng thật) để output luôn chính xác, không bịa.
    n_fail = sum(1 for tc in request.test_cases if tc.status in ("failed", "error"))
    if n_fail == 0 and request.test_cases and feedback_text:
        low = feedback_text.lower()
        flags = ["cần cải thiện", "chưa tối ưu", "cần ôn lại", "cần chú ý", "khắc phục",
                 "kiểm soát lỗi", "còn hạn chế", "điểm yếu", "chưa tốt", "lỗi sai", "một số lỗi", "gặp lỗi"]
        if any(f in low for f in flags):
            feedback_text = build_perfect_feedback(request, rule_data)
            review_reasons.append("Bản LLM có dấu hiệu bịa nhược điểm cho bài không lỗi → đã thay bằng nhận xét chuẩn.")

 ## lay danh sach source tu rag context da duoc dung
    sources = list(
        dict.fromkeys(
            [item.get("source", "unknown") for item in rag_context]
        )
    )

    return FeedbackTextResponse(
        student_id=request.student.id,
        score_summary=f"{request.grading_result.score}/{request.exam.total_score}",
        feedback_text=feedback_text,
        teacher_review_required=teacher_review_required,
        sources=sources,
        review_reasons=review_reasons
    )
