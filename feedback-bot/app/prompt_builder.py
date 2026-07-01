# request + rule_data + rag_context
#         ↓
# lọc dữ liệu
#         ↓
# tạo safe_grading_data
#         ↓
# ghép RAG text
#         ↓
# tạo prompt cuối cho Qwen3

import json
from app.schemas import FeedbackRequest
from app.config import FEEDBACK_MAX_EVIDENCE_ITEMS, FEEDBACK_MAX_RAG_CHARS_PER_ITEM


MAX_RAG_CHARS_PER_ITEM = FEEDBACK_MAX_RAG_CHARS_PER_ITEM
MAX_EVIDENCE_ITEMS = FEEDBACK_MAX_EVIDENCE_ITEMS


def limit_text(text: str, max_chars: int = MAX_RAG_CHARS_PER_ITEM) -> str:
    if not text:
        return ""

    text = text.strip()

    if len(text) <= max_chars:
        return text

    return text[:max_chars].strip() + "\n...[đã rút gọn]"


def build_rag_text(rag_context: list[dict]) -> str:
    if not rag_context:
        return "Không có tài liệu RAG phù hợp được tìm thấy."

    return "\n".join(
        [
            f"Nguồn: {item.get('source', 'unknown')}\n"
            f"Nội dung:\n{limit_text(item.get('content', ''))}"
            for item in rag_context
        ]
    )


def build_analyze_summary(request: FeedbackRequest) -> dict | None:
    if not request.analyze_result:
        return None

    important_warnings = []

    for warning in request.analyze_result.warnings:
        severity = (warning.severity or "").lower()

        if severity in ["warning", "error"]:
            important_warnings.append(warning.model_dump())

    return {
        "has_error": request.analyze_result.has_error,
        "important_warnings": important_warnings,
    }

def select_important_evidence(rule_data: dict, max_items: int = MAX_EVIDENCE_ITEMS) -> dict:
    evidence = rule_data.get("evidence", [])
    skill_summary = rule_data.get("skill_summary", [])

    if len(evidence) <= max_items:
        return {
            "items": evidence,
            "omitted_count": 0,
            "note": "Tất cả evidence đều được đưa vào prompt."
        }

    weak_skill_codes = [
        item["skill_code"]
        for item in skill_summary
        if item.get("status") in ["weak", "needs_improvement", "needs_review"]
    ]

    selected = []
    selected_ids = set()

    # Ưu tiên lấy evidence đại diện cho từng skill yếu
    for skill_code in weak_skill_codes:
        skill_evidence = [
            item for item in evidence
            if item.get("skill_code") == skill_code
        ]

        skill_evidence = sorted(
            skill_evidence,
            key=lambda item: (
                item.get("status") == "error",
                item.get("weight", 1.0)
            ),
            reverse=True
        )

        for item in skill_evidence[:2]:
            if item["test_id"] not in selected_ids and len(selected) < max_items:
                selected.append(item)
                selected_ids.add(item["test_id"])

    # Nếu vẫn còn chỗ, lấy thêm evidence quan trọng nhất còn lại
    remaining = [
        item for item in evidence
        if item["test_id"] not in selected_ids
    ]

    remaining = sorted(
        remaining,
        key=lambda item: (
            item.get("status") == "error",
            item.get("weight", 1.0)
        ),
        reverse=True
    )

    for item in remaining:
        if len(selected) >= max_items:
            break

        selected.append(item)
        selected_ids.add(item["test_id"])

    return {
        "items": selected,
        "omitted_count": len(evidence) - len(selected),
        "note": (
            f"Có {len(evidence) - len(selected)} evidence không được đưa trực tiếp vào prompt "
            "do giới hạn độ dài. Hãy dựa thêm vào skill_summary để nhận xét tổng quan."
        )
    }

def build_grounding_facts(rule_data: dict) -> str:
    """Dựng DỮ LIỆU THẬT (bám chặt) từ kết quả chấm: kỹ năng ĐÃ ĐẠT + DANH SÁCH LỖI THẬT.
    LLM phải nhận xét đúng theo đây — không bịa, không lấy từ tài liệu."""
    skill_summary = rule_data.get("skill_summary", [])
    selected_evidence = select_important_evidence(rule_data)
    evidence = selected_evidence.get("items", [])

    passed = []
    for s in skill_summary:
        if s.get("passed", 0) > 0:
            name = s.get("skill_name") or s.get("skill") or s.get("skill_code")
            if name and name not in passed:
                passed.append(name)

    lines = ["KỸ NĂNG EM ĐÃ ĐẠT (CHỈ được khen trong số này):"]
    lines += [f"- {n}" for n in passed] or ["- (không có kỹ năng nào đạt rõ ràng)"]

    lines.append("")
    lines.append("CÁC LỖI THỰC TẾ TỪ TESTCASE (phần 'cần cải thiện' CHỈ được nói về các lỗi này — đúng chỗ sai, không thêm lỗi nào khác):")
    if evidence:
        for i, ev in enumerate(evidence, 1):
            sk = ev.get("skill_name") or ev.get("skill") or ev.get("skill_code") or "?"
            hidden = " (test ẩn — chỉ nói khái quát, không tiết lộ chi tiết)" if ev.get("is_hidden") else ""
            comment = (ev.get("comment") or "").strip()
            lines.append(f"{i}. [{sk}] {comment}{hidden}")
    else:
        lines.append("- (không có lỗi cụ thể nào trong dữ liệu → nhận xét ở mức tổng quan, nói rõ là tham khảo)")
    if selected_evidence.get("omitted_count", 0) > 0:
        lines.append(
            f"- Còn {selected_evidence['omitted_count']} lỗi khác không liệt kê trực tiếp do giới hạn độ dài; "
            "hãy nhận xét tổng quan theo skill_summary, không bịa chi tiết mới."
        )
    return "\n".join(lines)


def build_student_feedback_prompt(
    request: FeedbackRequest,
    rule_data: dict,
    rag_context: list[dict]
) -> str:
    rag_text = build_rag_text(rag_context)
    grounding = build_grounding_facts(rule_data)
    warnings = rule_data.get("data_quality_warnings", [])
    warn_text = ("\nLƯU Ý CHẤT LƯỢNG DỮ LIỆU: " + " ".join(warnings)
                 + " → nói rõ nhận xét chỉ mang tính tham khảo." if warnings else "")
    note_text = (f"\nNgữ cảnh đề: {request.teacher_note}" if request.teacher_note else "")
    score = request.grading_result.score

    # CHẾ ĐỘ theo SỐ LỖI THẬT → directive + ví dụ riêng.
    # VÍ DỤ dùng placeholder [...] để model nhỏ KHÔNG thể chép nguyên — buộc phải điền dữ liệu thật.
    n_fail = len(rule_data.get("evidence", []))
    selected_fail = len(select_important_evidence(rule_data).get("items", []))
    if n_fail == 0:
        mode_block = (
            "CHẾ ĐỘ BÀI: KHÔNG CÓ LỖI NÀO (đạt điểm cao). Lời nhận xét CHỈ gồm: ghi nhận em làm tốt các kỹ năng "
            "ĐÃ ĐẠT ở trên, giải thích ngắn vì sao kết quả đó đáng ghi nhận, rồi gợi ý 2-3 hướng học NÂNG CAO tiếp theo "
            "(nói rõ là hướng phát triển thêm, không phải lỗi). "
            "TUYỆT ĐỐI KHÔNG viết câu nào kiểu 'cần cải thiện / chưa tốt / cần ôn lại / chưa tối ưu / cần chú ý / "
            "tập trung kiểm soát lỗi' — vì bài KHÔNG có lỗi. Đừng bịa nhược điểm."
        )
        example = (
            "Chào em, thầy/cô đã xem bài và rất vui vì em đạt [ĐIỂM] và vượt qua [SỐ TEST] bài kiểm tra. "
            "Em thể hiện vững vàng ở [KỸ NĂNG ĐÃ ĐẠT — lấy từ dữ liệu thật], cho thấy em nắm chắc kiến thức. "
            "Vì bài không còn lỗi nào, thầy/cô không ghi nhận điểm cần sửa cụ thể; em có thể tự tin tìm hiểu thêm "
            "[HƯỚNG NÂNG CAO PHÙ HỢP] và tự viết thêm test để kiểm tra các trường hợp biên. "
            "Thầy/cô tin em đang đi rất đúng hướng, cứ giữ phong độ này nhé!"
        )
    else:
        mode_block = (
            f"CHẾ ĐỘ BÀI: CÓ {n_fail} LỖI THẬT (xem 'CÁC LỖI THỰC TẾ'). Phần cần cải thiện chỉ nói về ĐÚNG các "
            "lỗi đó, mỗi lỗi kèm 1 gợi ý sửa cụ thể. KHÔNG thêm bất kỳ lỗi nào ngoài danh sách; KHÔNG nói chung "
            "chung kiểu 'có thể chưa tối ưu' cho kỹ năng đã đạt."
            + (f" Prompt chỉ liệt kê {selected_fail} lỗi quan trọng nhất; nếu còn lỗi bị lược bớt, chỉ tổng hợp theo nhóm kỹ năng."
               if selected_fail < n_fail else "")
        )
        example = (
            "Chào em, thầy/cô đã xem bài và thấy em đã nắm khá chắc ở [KỸ NĂNG ĐÃ ĐẠT — lấy từ dữ liệu thật], "
            "đây là điểm đáng ghi nhận. Về phần [KỸ NĂNG CÓ LỖI — lấy từ CÁC LỖI THỰC TẾ], "
            "[MÔ TẢ LỖI CỤ THỂ TỪ DỮ LIỆU]; em thử [GỢI Ý SỬA CỤ THỂ] xem sao nhé. "
            "Em đã có nền tốt, cố gắng thêm chút ở phần này là bài sẽ tròn trịa hơn. Cố lên em nhé!"
        )
    total = request.exam.total_score
    passed_n = request.grading_result.passed_tests
    total_n = request.grading_result.total_tests

    return f"""
Vai trò của người viết: GIÁO VIÊN chấm thi. Người nhận: SINH VIÊN vừa làm bài thi Flutter PRM393.

================ DỮ LIỆU THẬT TỪ KẾT QUẢ CHẤM — BÁM CHẶT VÀO ĐÂY ================
Điểm: {score}/{total}. Số test đạt: {passed_n}/{total_n}.{note_text}{warn_text}

{grounding}
==============================================================================

>>> {mode_block}

QUY TẮC BÁM SÁT (bắt buộc):
- "DỮ LIỆU THẬT" ở trên chỉ để ĐỌC — KHÔNG phải định dạng để chép. Viết VĂN XUÔI liền mạch.
- Chỉ khen kỹ năng trong "KỸ NĂNG EM ĐÃ ĐẠT"; chỉ nêu lỗi trong "CÁC LỖI THỰC TẾ". KHÔNG bịa lỗi/kỹ năng/điểm.
- KHÔNG khẳng định em đã dùng kỹ thuật/widget/thư viện nào nếu dữ liệu không nêu.
- KHÔNG nhắc tên tài liệu/nguồn (Mastering Flutter, Chương..., tên file .md, "theo RAG"). Lời khuyên tự nhiên, không dẫn nguồn.

KIẾN THỨC NỀN (chỉ để DIỄN GIẢI vì sao lỗi xảy ra và GỢI Ý cách sửa — KHÔNG phải bằng chứng việc em đã làm, KHÔNG được trích dẫn):
{rag_text}

VÍ DỤ GIỌNG VĂN (chỉ tham khảo CẤU TRÚC và GIỌNG — các chỗ [...] là placeholder, phải THAY bằng dữ liệu thật ở mục "DỮ LIỆU THẬT" bên trên, KHÔNG được chép nguyên):
\"\"\"
{example}
\"\"\"

CÁCH VIẾT:
- Tiếng Việt, giọng ấm áp, tự nhiên, cụ thể, khích lệ.
- XƯNG HÔ (bắt buộc): người viết LÀ GIÁO VIÊN — tự xưng "thầy/cô"; người nhận LÀ SINH VIÊN — luôn gọi là "em".
- TUYỆT ĐỐI KHÔNG đảo vai: KHÔNG viết "em đã xem/chấm/nhận xét bài của thầy/cô"; KHÔNG dùng "thầy/cô" để chỉ sinh viên; KHÔNG gọi sinh viên là "tôi/bạn/mình". Nếu KIẾN THỨC NỀN có cách xưng khác thì BỎ QUA.
- KHÔNG chèn câu mang giọng sinh viên (vd "em rất mong nhận xét", "xin cảm ơn") — đây là lời GIÁO VIÊN gửi sinh viên.
- 4–6 ĐOẠN VĂN LIỀN MẠCH, khoảng 180–320 từ như một lá thư ngắn: mở đầu ghi nhận → điểm làm tốt → phân tích từng lỗi thật cần cải thiện (mỗi lỗi kèm nguyên nhân có thể và 1 gợi ý sửa cụ thể) → cách tự kiểm tra lại → động viên.
- Nếu bài không có lỗi, vẫn viết đủ chi tiết: ghi nhận kết quả, nêu kỹ năng đã thể hiện tốt, giải thích vì sao đáng khen, rồi gợi ý hướng nâng cao; KHÔNG biến hướng nâng cao thành lỗi.
- KHÔNG tiêu đề in đậm, KHÔNG đánh số, KHÔNG gạch đầu dòng, KHÔNG markdown, KHÔNG JSON — chỉ văn xuôi liền mạch.
- Test ẩn: chỉ nói khái quát. Nếu dữ liệu quá ít/cảnh báo chất lượng: nói rõ nhận xét chỉ mang tính tham khảo.

NHẮC LẠI (bắt buộc): giáo viên tự xưng "thầy/cô", sinh viên là "em" — KHÔNG tự gọi giáo viên là "em", KHÔNG gọi sinh viên là "thầy/cô/tôi/bạn"; viết LIỀN MẠCH như một lá thư ngắn, KHÔNG đánh số, KHÔNG in đậm, KHÔNG gạch đầu dòng.

Bây giờ viết lời nhận xét (chỉ trả về phần văn xuôi):
"""
