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


def is_perfect_submission(request: FeedbackRequest) -> bool:
    """Bài không có testcase lỗi và điểm đạt tối đa theo dữ liệu chấm."""
    if not request.test_cases or request.grading_result.total_tests <= 0:
        return False

    has_failed = any(tc.status in ("failed", "error") for tc in request.test_cases)
    if has_failed:
        return False

    gr = request.grading_result
    score_full = gr.score >= request.exam.total_score - 0.01
    tests_full = gr.passed_tests == gr.total_tests
    return score_full or tests_full


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

    # Bài hoàn hảo không nên bị gắn "cần GV xem lại" chỉ vì RAG không tìm được tài liệu phù hợp.
    # Với bài không lỗi, nhận xét có thể dựng chắc chắn từ skill_summary/result_json.
    if is_perfect_submission(request):
        return False

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
        f"Chào em, thầy/cô đã xem kỹ bài làm của em. Bài đạt {score}/{total} điểm "
        f"và vượt qua {passed}/{total_tests} test case, đây là cơ sở để thầy/cô nhận xét mức độ hoàn thành của em. "
    )

    if total_tests <= 0:
        feedback += (
            "Hiện tại hệ thống chưa có đủ dữ liệu test case để đưa ra nhận xét chi tiết theo từng kỹ năng. "
            "Vì vậy, feedback này chỉ mang tính tổng quan và cần giảng viên kiểm tra thêm trước khi gửi chính thức."
        )
        return feedback

    if rule_data.get("strengths"):
        feedback += (
            "Điểm tích cực là bài làm đã đáp ứng được một số yêu cầu quan trọng của đề, cho thấy em có nền tảng để tiếp tục hoàn thiện bài. "
        )

    if rule_data.get("weaknesses"):
        feedback += (
            "Tuy nhiên, bài làm vẫn còn một số phần cần cải thiện cụ thể. "
            + " ".join(rule_data["weaknesses"][:2])
            + " "
        )

    if rule_data.get("recommendations"):
        feedback += (
            "Để cải thiện, em nên rà lại từng yêu cầu chưa đạt, chạy thử thêm các trường hợp biên và so sánh kết quả thực tế với yêu cầu đề bài. "
            + " ".join(rule_data["recommendations"][:3])
        )

    return feedback


SYSTEM_PROMPT = (
    "Vai trò của người viết là GIÁO VIÊN chấm thi, đang viết lời nhận xét gửi cho sinh viên sau bài thi Flutter PRM393. "
    "Văn phong ấm áp, tự nhiên, cụ thể, xây dựng — như một lá thư ngắn, KHÔNG phải báo cáo. "
    "XƯNG HÔ (bắt buộc): giáo viên tự xưng 'thầy/cô'; sinh viên luôn được gọi là 'em'. "
    "TUYỆT ĐỐI KHÔNG tự gọi giáo viên là 'em'; KHÔNG gọi sinh viên là 'thầy/cô', 'tôi', 'bạn' hoặc 'mình'. "
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
            "num_ctx": 4096, "num_predict": 900,
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
        body["max_completion_tokens"] = 1200
    else:
        body["temperature"] = 0.55
        body["top_p"] = 0.9
        body["max_tokens"] = 1100
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
        f"Chào em, thầy/cô đã xem kỹ bài làm của em và rất vui vì kết quả lần này thật sự tốt. Em đạt "
        f"{gr.score}/{request.exam.total_score} điểm và vượt qua {gr.passed_tests}/{gr.total_tests} bài kiểm tra, "
        f"điều đó cho thấy bài làm đáp ứng đầy đủ các yêu cầu mà hệ thống chấm tự động kiểm tra.\n\n"
        f"Điểm đáng ghi nhận nhất là em thể hiện vững vàng ở {skills_txt}. Những kỹ năng này thường đòi hỏi em phải "
        f"nắm đúng cấu trúc code, đặt tên và tổ chức hàm/lớp nhất quán với yêu cầu đề, đồng thời xử lý được các trường "
        f"hợp kiểm thử mà bài đưa ra. Việc pass toàn bộ testcase cho thấy em không chỉ làm được phần chính mà còn giữ "
        f"được độ ổn định của bài làm.\n\n"
        f"Vì bài hiện không còn lỗi nào trong dữ liệu chấm, thầy/cô không ghi nhận điểm cần sửa cụ thể. Hướng phát triển "
        f"tiếp theo của em là đọc lại lời giải của mình để tối ưu cách đặt tên, tách hàm rõ hơn, và tự bổ sung thêm một vài "
        f"trường hợp kiểm thử ngoài đề để rèn tư duy kiểm thử. Nếu tiếp tục giữ cách làm cẩn thận như vậy, em sẽ xử lý tốt "
        f"hơn các bài có yêu cầu dài hoặc nhiều màn hình hơn.\n\n"
        f"Thầy/cô đánh giá cao sự chỉn chu của em trong bài này. Em đang đi đúng hướng, hãy giữ phong độ và tiếp tục luyện "
        f"thêm các bài có nhiều ràng buộc hơn để nâng kỹ năng Flutter/Dart lên mức chắc chắn hơn nhé."
    )


def normalize_feedback_voice(text: str) -> str:
    """Chuẩn hóa xưng hô sau khi LLM sinh để tránh đảo vai giáo viên/sinh viên."""
    if not text:
        return ""

    normalized = text.strip()

    role_replacements = [
        (r"\b[Cc]hào thầy/cô\b", "Chào em"),
        (r"\b[Kk]ính chào thầy/cô\b", "Chào em"),
        (r"\b[Ee]m đã xem bài(?: làm)? của thầy/cô", "Thầy/cô đã xem bài làm của em"),
        (r"\b[Ee]m đã chấm bài(?: làm)? của thầy/cô", "Thầy/cô đã chấm bài làm của em"),
        (r"\b[Ee]m nhận xét bài(?: làm)? của thầy/cô", "Thầy/cô nhận xét bài làm của em"),
        (r"\b[Ee]m gửi nhận xét này cho thầy/cô", "Thầy/cô gửi nhận xét này cho em"),
        (r"\b[Ee]m rất vui vì thầy/cô", "Thầy/cô rất vui vì em"),
        (r"\b[Ee]m thấy thầy/cô", "Thầy/cô thấy em"),
        (r"\b[Ee]m tin thầy/cô", "Thầy/cô tin em"),
        (r"\b[Tt]ôi đã xem bài(?: làm)? của em", "Thầy/cô đã xem bài làm của em"),
        (r"\b[Tt]ôi đã chấm bài(?: làm)? của em", "Thầy/cô đã chấm bài làm của em"),
        (r"\b[Tt]ôi nhận xét bài(?: làm)? của em", "Thầy/cô nhận xét bài làm của em"),
        (r"\b[Tt]ôi gửi nhận xét này cho em", "Thầy/cô gửi nhận xét này cho em"),
        (r"\b[Tt]ôi rất vui vì em", "Thầy/cô rất vui vì em"),
        (r"\b[Tt]ôi thấy em", "Thầy/cô thấy em"),
        (r"\b[Tt]ôi tin em", "Thầy/cô tin em"),
    ]
    for pattern, replacement in role_replacements:
        normalized = re.sub(pattern, replacement, normalized)

    student_reference_replacements = [
        (r"bài(?: làm)? của thầy/cô", "bài làm của em"),
        (r"kết quả của thầy/cô", "kết quả của em"),
        (r"phần làm bài của thầy/cô", "phần làm bài của em"),
        (r"thầy/cô đã hoàn thành", "em đã hoàn thành"),
        (r"thầy/cô đã nắm", "em đã nắm"),
        (r"thầy/cô đã làm", "em đã làm"),
        (r"thầy/cô cần", "em cần"),
        (r"thầy/cô nên", "em nên"),
        (r"thầy/cô hãy", "em hãy"),
        (r"thầy/cô thử", "em thử"),
        (r"thầy/cô có thể", "em có thể"),
    ]
    for pattern, replacement in student_reference_replacements:
        normalized = re.sub(pattern, replacement, normalized, flags=re.IGNORECASE)

    second_person_replacements = [
        (r"\bcủa bạn\b", "của em"),
        (r"\bbạn đã\b", "em đã"),
        (r"\bbạn cần\b", "em cần"),
        (r"\bbạn nên\b", "em nên"),
        (r"\bbạn hãy\b", "em hãy"),
        (r"\bbạn thử\b", "em thử"),
        (r"\bbạn có thể\b", "em có thể"),
        (r"\bcủa mình\b", "của em"),
        (r"\bmình đã\b", "em đã"),
        (r"\bmình cần\b", "em cần"),
        (r"\bmình nên\b", "em nên"),
    ]
    for pattern, replacement in second_person_replacements:
        normalized = re.sub(pattern, replacement, normalized, flags=re.IGNORECASE)

    normalized = normalized.replace("Chào em, Thầy/cô", "Chào em, thầy/cô")
    normalized = normalized.replace(" và Thầy/cô", " và thầy/cô")

    paragraphs = [re.sub(r"[ \t]+", " ", part).strip() for part in re.split(r"\n{2,}", normalized)]
    return "\n\n".join(part for part in paragraphs if part)


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

    feedback_text = normalize_feedback_voice(feedback_text)

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
