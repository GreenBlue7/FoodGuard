"""Gemini 에게 요구하는 답의 양식 (API 응답과는 다른 내부용)."""

from enum import Enum

from pydantic import BaseModel

from app.schemas import Status


class Step(str, Enum):
    NONE = "NONE"
    DISEASE = "DISEASE"
    DRUG = "DRUG"
    HEALTH_FOOD_CONFUSION = "HEALTH_FOOD_CONFUSION"  # mode C, 일반식품일 때만
    UNAPPROVED_FUNCTION = "UNAPPROVED_FUNCTION"  # mode C, 건강기능식품일 때만
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
    matched_function: str = ""  # mode C: 문장의 기능성과 뜻이 같은 '인정받은 기능성' 항목


class LlmOutput(BaseModel):
    verdicts: list[LlmVerdict]