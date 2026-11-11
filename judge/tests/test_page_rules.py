"""페이지 전체를 보고 적용하는 공식 기준 규칙 (AI 없이 코드로만)."""

from app.page_rules import apply_page_rules
from app.schemas import JudgeResult, Product, ProductType, Sentence, Span, Status
from app.splitter import split_sentences
from app.verifier import is_approved_wording

EYE = "노화로 인해 감소될 수 있는 황반색소밀도를 유지하여 눈 건강에 도움을 줄 수 있음"
HF = Product(type=ProductType.HEALTH_FUNCTIONAL, names=["눈엔"], approved_functions=[EYE])


def judge(ad: str, product: Product = HF, status: Status = Status.OK) -> tuple[list[Sentence], dict[str, JudgeResult]]:
    sentences = split_sentences(ad)
    results = {
        s.id: JudgeResult(sentence_ids=[s.id], text=s.text, status=status, reason="1차", span=Span(start=s.start, end=s.end))
        for s in sentences
    }
    apply_page_rules(sentences, results, product, ad)
    return sentences, results


def statuses(ad: str, product: Product = HF, status: Status = Status.OK) -> list[Status]:
    sentences, results = judge(ad, product, status)
    return [results[s.id].status for s in sentences]


def test_출처_없는_1위는_위반():
    assert statuses("판매량 1위 루테인입니다.") == [Status.VIOLATION]


def test_조사기관이_바로_옆에_있는_1위는_그대로():
    assert statuses("판매량 1위 루테인입니다.\n2025년 닐슨 조사 기준") == [Status.OK, Status.OK]


def test_한방_같은_시행령_현혹_표현은_위반():
    assert statuses("전통 한방 비법으로 달였습니다.") == [Status.VIOLATION]


def test_질병정보_안내_문구가_없으면_질병_단어가_든_검토_필요는_위반():
    assert statuses("당뇨병은 흔한 질환입니다.", status=Status.REVIEW) == [Status.VIOLATION]


def test_질병정보_안내_문구가_있으면_검토_필요로_둔다():
    ad = "당뇨병은 흔한 질환입니다.\n※ 해당 질병정보는 제품과 직접적인 관련이 없습니다."
    assert statuses(ad, status=Status.REVIEW)[0] is Status.REVIEW


def test_이미_위반인_문장은_바꾸지_않는다():
    sentences, results = judge("전통 한방 비법으로 달였습니다.", status=Status.VIOLATION)
    assert results[sentences[0].id].reason == "1차"


def test_인정_기능성_문구를_그대로_쓴_문장():
    assert is_approved_wording("노화로 인해 감소될 수 있는 황반색소밀도를 유지하여 눈 건강에 도움을 줄 수 있습니다", [EYE])
    assert not is_approved_wording("황반색소밀도를 확실하게 지켜 줍니다", [EYE])
