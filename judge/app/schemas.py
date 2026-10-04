from enum import Enum

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

# API 명세

class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

class Mode(str, Enum):
    A = "A" # 문구만 입력
    B = "B" # 문구 + 사례
    C = "C" # 문구 + 사례 + 제품 정보

class ProductType(str, Enum):
    HEALTH_FUNCTIONAL = "HEALTH_FUNCTIONAL"
    FUNCTIONAL_LABELED_FOOD = "FUNCTIONAL_LABELED_FOOD"
    GENERAL_FOOD = "GENERAL_FOOD"
    UNKNOWN = "UNKNOWN"

class Status(str, Enum):
    VIOLATION = "VIOLATION"
    REVIEW = "REVIEW"
    OK = "OK"
    HOLD = "HOLD"

class Product(ApiModel):
    type: ProductType
    names: list[str] = []
    approved_functions: list[str] = []

class JudgeRequest(ApiModel):
    request_id: str
    mode: Mode
    product: Product | None = None
    ad_text: str = Field(min_length=1)

class Sentence(ApiModel):
    id: str
    text: str
    start: int
    end: int

class Span(ApiModel):
    start: int
    end: int

class Evidence(ApiModel):
    id: str
    title: str

class JudgeResult(ApiModel):
    sentence_ids: list[str]
    text: str
    status: Status
    violation_type: str | None = None
    reason: str
    evidence: list[Evidence] = []
    span: Span
    revision: str | None = None

class JudgeResponse(ApiModel):
    sentences: list[Sentence]
    results: list[JudgeResult]
    model: str
    kb_version: str
    elapsed_ms: int