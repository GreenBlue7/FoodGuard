from fastapi import FastAPI, HTTPException

from app.llm import LlmError
from app.pipeline import run_judge
from app.schemas import JudgeRequest, JudgeResponse

app = FastAPI(title="FoodGuard Judge", version="0.1.0")


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/judge")
def judge(request: JudgeRequest) -> JudgeResponse:
    try:
        return run_judge(request)
    except LlmError as e:
        raise HTTPException(status_code=503, detail=f"판정을 완료하지 못했습니다: {e}")