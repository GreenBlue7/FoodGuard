"""Gemini 에게 요구하는 답의 양식 (API 응답과는 다른 내부용)."""

from enum import Enum

from pydantic import BaseModel

from app.schemas import Status


class Step(str, Enum):
    NONE = "NONE"
    DISEASE = "DISEASE"
    DRUG = "DRUG"
    EXAGGERATION = "EXAGGERATION"
    DECEPTION = "DECEPTION"
    COMPARISON = "COMPARISON"


class LlmVerdict(BaseModel):
    sentence_id: str
    status: Status
    step: Step
    reason: str
    problem_text: str
    revision: str
    evidence_ids: list[str] = []


class LlmOutput(BaseModel):
    verdicts: list[LlmVerdict]