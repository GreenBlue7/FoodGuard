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

class Downgrade(str, Enum):
    """위반 다시 확인에서 검토 필요로 내리는 이유. 여기 없는 이유로는 내리지 않는다."""

    KEEP = "KEEP"  # 분명한 위반, 그대로 둠
    NO_BODY_EFFECT = "NO_BODY_EFFECT"  # 몸의 기능 효과를 말하지 않음 (일반식품의 용도·상황 설명 등)
    PARAPHRASE = "PARAPHRASE"  # 인정받은 기능성을 바꿔 말함
    TRIAL = "TRIAL"  # 인체적용시험 결과·조건 설명
    HEALTH_INFO = "HEALTH_INFO"  # 제품 효과와 이어지지 않은 질병·기관 설명
    QUALITY_SERVICE = "QUALITY_SERVICE"  # 원료·품질·제조 자랑, 판매량 같은 사실, 상담 안내
    TESTIMONIAL = "TESTIMONIAL"  # 인정 기능성 범위 안의 효과만 말하는 후기 (v2 확인 프롬프트에서만 씀)
    BROKEN_TEXT = "BROKEN_TEXT"  # 글자가 깨지거나 잘려 뜻이 분명하지 않음


class ConfirmItem(BaseModel):
    sentence_id: str
    decision: Downgrade
    reason: str


class ConfirmOutput(BaseModel):
    items: list[ConfirmItem]


class FactVerdict(str, Enum):
    CONTRADICT = "CONTRADICT"
    CONSISTENT = "CONSISTENT"
    UNVERIFIED = "UNVERIFIED"


class FactItem(BaseModel):
    """사실 대조: 문장 속 주장이 등록 정보·페이지 표시사항과 맞는지."""

    sentence_id: str
    verdict: FactVerdict
    claim: str
    evidence: str
    reason: str


class FactOutput(BaseModel):
    items: list[FactItem]


class Recheck(str, Enum):
    """정상으로 나온 문장을 다시 볼 때 검토 필요로 올리는 이유. 여기 없는 이유로는 올리지 않는다."""

    OK = "OK"  # 그대로 정상
    TESTIMONIAL = "TESTIMONIAL"  # 소비자 후기·체험기로 몸의 효과를 말함
    STRONGER_CLAIM = "STRONGER_CLAIM"  # 인정받은 기능성을 단정·과장해서 말함 (DOWN, CUT, 마법, 입증 등)
    UNAPPROVED_EFFECT = "UNAPPROVED_EFFECT"  # 인정받지 않은 몸의 효과를 내비침 (흡수율, 세포 활성 등)
    FACT_CLAIM = "FACT_CLAIM"  # 등록 정보로 확인해야 하는 사실 주장 (순도·100%·오직·최초·권장량)
    AUTHORITY = "AUTHORITY"  # 식약처·전문가·특허가 제품을 인정·보증하는 것처럼 보임
    SYMPTOM_TARGET = "SYMPTOM_TARGET"  # 증상이 있는 사람을 대상으로 해 증상 개선을 내비침
    DISEASE_INFO = "DISEASE_INFO"  # 질병의 원인·환자 수·위험성을 설명해 제품이 질병을 막는 것처럼 보일 수 있음


class RecheckItem(BaseModel):
    sentence_id: str
    decision: Recheck
    reason: str


class RecheckOutput(BaseModel):
    items: list[RecheckItem]
