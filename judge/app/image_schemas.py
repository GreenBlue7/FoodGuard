"""이미지 검사(/judge/image) API 명세. 판정 부분은 /judge 와 같은 모양이고, 페이지·위치 칸만 더한다."""

from app.schemas import ApiModel, JudgeResult, Mode, Product, Sentence


class ImageJudgeRequest(ApiModel):
    # /judge 요청에서 adText 만 빠진 것. 글자는 이미지에서 읽는다
    request_id: str
    mode: Mode
    product: Product | None = None


class Page(ApiModel):
    """화면에 한 장씩 보여 줄 페이지. 박스와 번호가 이미 그려져 있다."""

    index: int  # 0부터, 위→아래 순서
    image_index: int  # 몇 번째로 올린 이미지에서 나왔는지
    width: int  # image 의 실제 픽셀 크기
    height: int
    image: str  # "data:image/jpeg;base64,..."


class Location(ApiModel):
    page: int
    box: list[int]  # 그 페이지 이미지 픽셀 기준 [왼쪽 x, 위 y, 오른쪽 x, 아래 y]


class ImageJudgeResult(JudgeResult):
    # 페이지에 그린 표시 종류: VIOLATION(빨강), REVIEW(주황), HOLD(파랑), APPROVED(녹색, 인정 기능성 범위라 정상). 그 밖의 OK 는 None
    mark: str | None = None
    # 페이지에 그린 번호. 같은 표시·같은 문제 문구면 같은 번호 (위치는 여러 개). mark 가 None 이면 None
    number: int | None = None
    # 이 판정의 위치. mark 가 있으면 그려진 박스 그대로, 없으면 해당 줄 위치. 여러 줄·여러 페이지면 여러 개
    locations: list[Location] = []


class ExcludedLine(ApiModel):
    """판정에서 뺀 줄. reason: NOTICE(영양정보·판매원 같은 안내 문구), SMALL(포장 사진 속 작은 글자)."""

    text: str
    reason: str
    page: int
    box: list[int]


class UnreadRegion(ApiModel):
    """끝내 글자를 읽지 못한 구간. 페이지에는 회색으로 덮여 있다."""

    page: int
    top: int
    bottom: int


class ImageJudgeResponse(ApiModel):
    pages: list[Page]
    ad_text: str  # 이미지에서 읽어 판정에 넣은 전체 글자. sentences·span 위치는 이 글자 기준
    sentences: list[Sentence]
    results: list[ImageJudgeResult]
    excluded_lines: list[ExcludedLine]
    unread_regions: list[UnreadRegion]
    model: str
    ocr_model: str
    kb_version: str
    elapsed_ms: int
