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
    # 아래는 식약처 I0030 에서 그대로 가져오는 값 (없으면 비워 둔다)
    ingredients: list[str] = []  # 기능성 원료 (RAWMTRL_NM)
    function_source: str = ""  # 원료별로 적힌 기능성 원문 (PRIMARY_FNCLTY)
    cautions: list[str] = []  # 섭취 시 주의사항 (IFTKN_ATNT_MATR_CN)
    intake: str = ""  # 섭취 방법 (NTK_MTHD)
    other_ingredients: list[str] = []  # 기능성 원료 외 원재료 (INDIV·ETC·CAP_RAWMTRL_NM)
    standards: str = ""  # 기준규격, 성분별 표시량 (STDR_STND)

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
    url: str | None = None

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