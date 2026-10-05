from fastapi import FastAPI

from app.schemas import (
    JudgeRequest,
    JudgeResponse,
    JudgeResult,
    Span,
    Status
)
from app.splitter import split_sentences

app = FastAPI(title="FoodGuard Judge", version="0.1.0")

@app.get("/health")
def health() -> dict:
    return {"status": "ok"}

@app.post("/judge")
def judge(request: JudgeRequest) -> JudgeResponse:
    # 가짜 응답
    sentences = split_sentences(request.ad_text)
    results = [
        JudgeResult(
            sentence_ids=[s.id],
            text=s.text,
            status=Status.REVIEW,
            reason="가짜 응답, 실제 판정 안했음",
            span=Span(start=s.start, end=s.end),
        )
        for s in sentences
    ]
    return JudgeResponse(
        sentences=sentences,
        results=results,
        model="stub",
        kb_version="stub",
        elapsed_ms=0,
    )