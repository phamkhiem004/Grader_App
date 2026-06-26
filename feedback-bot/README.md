# PRM393 Feedback Bot

Local FastAPI service that generates Vietnamese feedback for PRM393 Flutter practical exam results. The flow is:

1. Validate grading input with Pydantic.
2. Build rule-based strengths, weaknesses, recommendations, and safe evidence.
3. Retrieve relevant RAG guidance from local ChromaDB.
4. Ask Ollama to write student-facing feedback.
5. Fall back to deterministic feedback and require teacher review when RAG or LLM fails.

## Setup

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

Install and start Ollama, then pull the default models:

```powershell
ollama pull qwen3:14b
ollama pull bge-m3
```

Optional `.env` values:

```env
FEEDBACK_MODEL_NAME=qwen3:14b
EMBED_MODEL_NAME=bge-m3
OLLAMA_TIMEOUT_SECONDS=60
```

## Build RAG Data

Run this after editing files in `data/rag_docs`:

```powershell
python scripts/ingest_rag.py
```

`data/chroma_db/` is generated output and is ignored by Git.

## Run API

```powershell
uvicorn app.main:app --reload
```

Health check:

```powershell
Invoke-RestMethod http://localhost:8000/
```

Generate feedback:

```powershell
Invoke-RestMethod `
  -Method Post `
  -ContentType "application/json" `
  -InFile examples/sample_result.json `
  http://localhost:8000/feedback/generate
```

## Tests

```powershell
pytest
```

The automated tests mock RAG and Ollama paths, so they do not require live models.
