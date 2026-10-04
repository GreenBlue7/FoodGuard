from fastapi import FastAPI

from app.schemas import (
    JudgeRequest,
    JudgeResponse,
    JudgeResult,
    Sentence,
    Span,
    Status
)

app = FastAPI(title="FoodGuard Judge", version="0.1.0")

@app.get("/health")
def health() -> dict:
    return {"status": "ok"}

@app.post("/judge")
def judge(request: JudgeRequest) -> JudgeResponse:
    # 가짜 응답
    text = request.ad_text
    return JudgeResponse(
        sentences=[Sentence(id="s1", text=text, start=0, end=len(text))],
        results=[
            JudgeResult(
                sentence_ids=["s1"],
                text=text,
                status=Status.REVIEW,
                reason="가짜 응답, 실제 판정 안했음",
                span=Span(start=0, end=len(text)),
            )
        ],
        model="stub",
        kb_version="stub",
        elapsed_ms=0,
    )