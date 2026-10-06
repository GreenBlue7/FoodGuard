from app.llm_schema import LlmVerdict, Step
from app.schemas import ProductType, Status
from app.splitter import split_sentences
from app.tagger import tag_sentence
from app.verifier import VerifyContext, verify

HF = VerifyContext(product_type=ProductType.HEALTH_FUNCTIONAL, approved_functions=("면역력 증진", "피로개선"))
GENERAL = VerifyContext(product_type=ProductType.GENERAL_FOOD)
UNKNOWN = VerifyContext(product_type=ProductType.UNKNOWN)
NO_PRODUCT = VerifyContext()  # mode A, B


def check(text: str, ctx: VerifyContext = NO_PRODUCT, **answer) -> tuple[LlmVerdict, list[str]]:
    (sentence,) = split_sentences(text)
    fields = {"status": "OK", "step": "NONE", "reason": "이유", "problem_text": "", "revision": ""}
    verdict = LlmVerdict(sentence_id=sentence.id, **{**fields, **answer})
    return verify(sentence, tag_sentence(sentence), verdict, ctx)


def test_정상적인_답은_그대로_둔다():
    fixed, notes = check(
        "환절기 감기 걱정 끝!", status="VIOLATION", step="DISEASE", problem_text="감기 걱정 끝", revision="환절기 건강 관리"
    )
    assert (fixed.status, fixed.step, fixed.revision) == (Status.VIOLATION, Step.DISEASE, "환절기 건강 관리")
    assert notes == []


def test_단계_없는_위반은_검토_필요로_낮춘다():
    fixed, _ = check("최고의 품질", status="VIOLATION", step="NONE")
    assert fixed.status is Status.REVIEW


def test_문제없음인데_단계가_있으면_검토_필요로_올린다():
    fixed, _ = check("하루 한 포로 활력 충전", status="OK", step="EXAGGERATION")
    assert fixed.status is Status.REVIEW


def test_문장에_없는_문제_구간은_비운다():
    fixed, _ = check("먹고 3일 만에 달라졌어요!", status="VIOLATION", step="DECEPTION", problem_text="삼일만에")
    assert (fixed.status, fixed.problem_text) == (Status.VIOLATION, "")


def test_수정안에_질병_단어가_있으면_버린다():
    fixed, _ = check(
        "고혈압 치료에 효과가 있습니다", status="VIOLATION", step="DISEASE", revision="고혈압 환자의 식이조절에 도움"
    )
    assert fixed.revision == ""


def test_이유가_비면_기본_문구로_채운다():
    assert check("상쾌한 아침", reason="  ")[0].reason == "해당하는 위반 유형이 없습니다."
    assert "직접 확인" in check("면역력에 도움", status="REVIEW", reason="")[0].reason


def test_질병_단어가_있어도_문제없음_판정은_그대로_둔다():
    fixed, notes = check("감기 조심하세요, 고객님!")
    assert fixed.status is Status.OK
    assert any("주의 단어" in note for note in notes)


def test_판단_보류는_제품_종류를_모를_때만_허용한다():
    assert check("면역력에 도움", UNKNOWN, status="HOLD")[0].status is Status.HOLD
    for ctx in (NO_PRODUCT, HF, GENERAL):
        assert check("면역력에 도움", ctx, status="HOLD")[0].status is Status.REVIEW


def test_제품_종류에_맞지_않는_단계는_검토_필요로_낮춘다():
    for ctx in (HF, UNKNOWN, NO_PRODUCT):
        fixed, _ = check("면역력에 도움", ctx, status="VIOLATION", step="HEALTH_FOOD_CONFUSION")
        assert (fixed.status, fixed.step) == (Status.REVIEW, Step.NONE)
    for ctx in (GENERAL, UNKNOWN, NO_PRODUCT):
        fixed, _ = check("집중력 향상", ctx, status="VIOLATION", step="UNAPPROVED_FUNCTION")
        assert (fixed.status, fixed.step) == (Status.REVIEW, Step.NONE)


def test_제품_종류에_맞는_단계는_유지한다():
    assert check("면역력에 도움", GENERAL, status="VIOLATION", step="HEALTH_FOOD_CONFUSION")[0].status is Status.VIOLATION
    assert check("집중력 향상", HF, status="VIOLATION", step="UNAPPROVED_FUNCTION")[0].status is Status.VIOLATION


def test_인정_기능성은_목록에_있는_값만_근거가_된다():
    fixed, _ = check("면역력 증진에 도움", HF, matched_function="면역력 증진")
    assert (fixed.status, fixed.matched_function) == (Status.OK, "면역력 증진")
    fixed, _ = check("면역력 증진에 도움", HF, matched_function="면역 기능 강화")
    assert (fixed.status, fixed.matched_function) == (Status.REVIEW, "")


def test_근거_사례는_허용된_번호만_남긴다():
    ctx = VerifyContext(allowed_evidence_ids=frozenset({"mfds-1-01"}))
    fixed, _ = check(
        "환절기 감기 걱정 끝!", ctx, status="VIOLATION", step="DISEASE", evidence_ids=["mfds-1-01", "mfds-9-99", "mfds-1-01"]
    )
    assert fixed.evidence_ids == ["mfds-1-01"]


def test_문제없음_판정에는_근거_사례를_붙이지_않는다():
    ctx = VerifyContext(allowed_evidence_ids=frozenset({"mfds-1-01"}))
    assert check("감기 조심하세요, 고객님!", ctx, evidence_ids=["mfds-1-01"])[0].evidence_ids == []