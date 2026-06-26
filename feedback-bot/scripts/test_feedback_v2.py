import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT_DIR))

import json

from app.schemas import FeedbackRequest
from app.rule_engine import build_rule_based_evidence
from app.rag_retriever import retrieve_context
from app.prompt_builder import build_student_feedback_prompt
from app.feedback_engine import generate_feedback_text


def main():
    input_path = Path("examples/sample_result.json")

    raw_data = json.loads(input_path.read_text(encoding="utf-8"))

    request = FeedbackRequest(**raw_data)

    print("\n=== 1. Parsed FeedbackRequest OK ===")
    print(request.student.id, request.exam.code)

    rule_data = build_rule_based_evidence(request)

    print("\n=== 2. Rule Data ===")
    print(json.dumps(rule_data, ensure_ascii=False, indent=2))

    rag_context = retrieve_context(request, rule_data)

    print("\n=== 3. RAG Context ===")
    print(f"Số context lấy được: {len(rag_context)}")

    for item in rag_context:
        print("- Source:", item.get("source"))
        print("  Preview:", item.get("content", "")[:200].replace("\n", " "))
        print()

    prompt = build_student_feedback_prompt(request, rule_data, rag_context)

    print("\n=== 4. Prompt Preview ===")
    print(prompt[:2000])
    print("\n...[prompt preview truncated]")

    response = generate_feedback_text(request)

    print("\n=== 5. Final FeedbackTextResponse ===")
    print(response.model_dump_json(indent=2))


if __name__ == "__main__":
    main()