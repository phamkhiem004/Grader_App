from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.schemas import FeedbackRequest, FeedbackTextResponse
from app.feedback_engine import generate_feedback_text
from app.config import FEEDBACK_PROVIDER, FEEDBACK_MODEL_NAME, OPENAI_MODEL


app = FastAPI(
    title="PRM393 Local Feedback Bot",
    description="Local AI service for generating natural student feedback",
    version="2.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:3000",
        "http://localhost:5173"
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/")
def health_check():
    return {
        "status": "ok",
        "service": "PRM393 Local Feedback Bot",
        "version": "2.0.0",
        "provider": FEEDBACK_PROVIDER,                         # ollama | openai
        "model": OPENAI_MODEL if FEEDBACK_PROVIDER == "openai" else FEEDBACK_MODEL_NAME,
    }


@app.post("/feedback/generate", response_model=FeedbackTextResponse)
def create_feedback(request: FeedbackRequest):
    return generate_feedback_text(request)