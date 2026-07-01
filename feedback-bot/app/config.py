import os
from pathlib import Path

from dotenv import load_dotenv


BASE_DIR = Path(__file__).resolve().parents[1]
DATA_DIR = BASE_DIR / "data"
RAG_DOCS_DIR = DATA_DIR / "rag_docs"
CHROMA_DIR = DATA_DIR / "chroma_db"
COMMENT_BANK_PATH = DATA_DIR / "comment_bank.json"

load_dotenv(BASE_DIR / ".env")

FEEDBACK_MODEL_NAME = os.getenv("FEEDBACK_MODEL_NAME", "qwen3:14b")
EMBED_MODEL_NAME = os.getenv("EMBED_MODEL_NAME", "bge-m3")

# ── Nhà cung cấp model sinh feedback ─────────────────────────────────────────
#  "ollama" (mặc định): chạy LOCAL, miễn phí nhưng CHẬM trên CPU (vài phút/bài).
#  "openai": gọi API tương thích OpenAI (gpt-4o-mini...) → NHANH ~100x + chạy song song
#            → phù hợp khi cần nhận xét HÀNG LOẠT (vài trăm–nghìn bài). Cần OPENAI_API_KEY.
#  OPENAI_BASE_URL có thể trỏ tới endpoint tương thích (Groq, OpenRouter, vLLM, Ollama /v1...).
FEEDBACK_PROVIDER = os.getenv("FEEDBACK_PROVIDER", "ollama").strip().lower()
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini").strip()
OPENAI_BASE_URL = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").strip()


def _get_float_env(name: str, default: float) -> float:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default

    try:
        return float(raw_value)
    except ValueError:
        return default


def _get_int_env(name: str, default: int) -> int:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default

    try:
        return int(raw_value)
    except ValueError:
        return default


def _get_bool_env(name: str, default: bool) -> bool:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default

    value = raw_value.strip().lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off"}:
        return False
    return default


OLLAMA_TIMEOUT_SECONDS = _get_float_env("OLLAMA_TIMEOUT_SECONDS", 60.0)
OLLAMA_NUM_PREDICT = _get_int_env("OLLAMA_NUM_PREDICT", 650)
OPENAI_FEEDBACK_MAX_TOKENS = _get_int_env("OPENAI_FEEDBACK_MAX_TOKENS", 800)

# Fast paths: skip RAG/LLM when the result is already deterministic enough.
FEEDBACK_FAST_PERFECT = _get_bool_env("FEEDBACK_FAST_PERFECT", True)
FEEDBACK_FAST_INVALID = _get_bool_env("FEEDBACK_FAST_INVALID", True)

# Smaller prompts make local Ollama feedback much faster while keeping enough evidence.
FEEDBACK_MAX_RAG_CHARS_PER_ITEM = _get_int_env("FEEDBACK_MAX_RAG_CHARS_PER_ITEM", 900)
FEEDBACK_MAX_EVIDENCE_ITEMS = _get_int_env("FEEDBACK_MAX_EVIDENCE_ITEMS", 8)
FEEDBACK_RAG_K_SMALL = _get_int_env("FEEDBACK_RAG_K_SMALL", 3)
FEEDBACK_RAG_K_MEDIUM = _get_int_env("FEEDBACK_RAG_K_MEDIUM", 4)
FEEDBACK_RAG_K_LARGE = _get_int_env("FEEDBACK_RAG_K_LARGE", 5)
