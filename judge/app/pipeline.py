"""판정 순서: 문장 나누기 → 사전 태그 → (사례 검색) → Gemini 판정 → 코드 검증 → 응답 형식으로 변환.

mode A: 문구만 / mode B: + 사례 검색 / mode C: + 사례 검색 + 제품 정보
"""

import logging
import re
import time
from pathlib import Path

from app.config import GEMINI_MODEL
from app.llm import generate_json
from app.llm_schema import LlmOutput, LlmVerdict, Step
from app.product_rules import PRODUCT_LABEL, case_applicability
from app.retriever import CaseHit, kb_version, search_cases
from app.schemas import (
    Evidence,
    JudgeRequest,
    JudgeResponse,
    JudgeResult,
    Mode,
    Product,
    ProductType,
    Sentence,
    Span,
    Status,
)
from app.splitter import split_sentences
from app.tagger import Tag, TagType, tag_sentence
from app.verifier import VerifyContext, verify

logger = logging.getLogger("judge")

_PROMPTS = Path(__file__).parent / "prompts"
_PROMPT = (_PROMPTS / "judge_v0.md").read_text(encoding="utf-8")  # mode A, B
_CASE_RULES = (_PROMPTS / "cases_v0.md").read_text(encoding="utf-8")  # mode B
_PROMPT_C = (_PROMPTS / "judge_c_v0.md").read_text(encoding="utf-8")  # mode C

# 조항 번호는 AI 가 아니라 코드가 붙인다 (지어내지 못하게)
_VIOLATION_TYPE = {
    Step.DISEASE: "제8조 제1항 제1호",
    Step.DRUG: "제8조 제1항 제2호",
    Step.HEALTH_FOOD_CONFUSION: "제8조 제1항 제3호",
    Step.UNAPPROVED_FUNCTION: "제8조 제1항 제4호",  # 식약처는 '인정하지 않은 기능성'을 거짓·과장으로 분류
    Step.EXAGGERATION: "제8조 제1항 제4호",
    Step.DECEPTION: "제8조 제1항 제5호",
    Step.COMPARISON: "제8조 제1항 제7호",
}

_TAG_LABEL = {
    TagType.MANDATORY: "법정 의무 문구",
    TagType.DISEASE: "질병",
    TagType.DRUG: "의약품 오인",
    TagType.EXAGGERATION: "과장",
    TagType.TESTIMONIAL: "체험기",
}

# 의무 문구 앞뒤에 붙어도 되는 말: "본 제품은", 문장부호, 공백
_FILLER = re.compile(r"(?:본|이)\s*제품은|[\W_]+")

# 문장, 태그, (사례, 이 제품에 쓸 수 있는지, 설명) 목록
_Item = tuple[Sentence, list[Tag], list[tuple[CaseHit, bool, str]]]


def _is_mandatory_only(sentence: Sentence, tags: list[Tag]) -> bool:
    mandatory = [t for t in tags if t.type is TagType.MANDATORY]
    if not mandatory:
        return False
    rest = sentence.text
    for tag in sorted(mandatory, key=lambda t: t.start, reverse=True):
        rest = rest[: tag.start - sentence.start] + rest[tag.end - sentence.start :]
    return _FILLER.sub("", rest) == ""


def _product_block(product: Product) -> str:
    functions = " / ".join(product.approved_functions) or "없음"
    return "\n".join(
        [
            f"- 제품 종류: {PRODUCT_LABEL[product.type]}",
            f"- 제품명: {', '.join(product.names) or '없음'}",
            f"- 인정받은 기능성: {functions}",
        ]
    )


def _build_prompt(items: list[_Item], mode: Mode, product: Product | None) -> str:
    lines = []
    for sentence, tags, cases in items:
        lines.append(f"[{sentence.id}] {sentence.text}")
        if tags:
            words = ", ".join(f"{_TAG_LABEL[t.type]}({t.text})" for t in tags)
            lines.append(f"  주의 단어: {words}")
        if cases:
            lines.append("  참고 사례:")
            for hit, _, applicability in cases:
                lines.append(
                    f"    - ({hit.id}) '{hit.phrase}' : {hit.violation_type}, "
                    f"제품 종류 {hit.product_type}, {hit.case_date:%Y-%m} 적발"
                )
                if product is not None:
                    lines.append(f"      적용: {applicability}")
    if product is not None:
        prompt = _PROMPT_C.replace("{{PRODUCT}}", _product_block(product))
    else:
        prompt = _PROMPT.replace("{{CASE_RULES}}", _CASE_RULES if mode is Mode.B else "")
    return prompt.replace("{{SENTENCES}}", "\n".join(lines))


def _evidence(hit: CaseHit) -> Evidence:
    title = f"'{hit.phrase}' 적발 ({hit.violation_type}, 식약처 {hit.case_date:%Y-%m-%d})"
    return Evidence(id=hit.id, title=title, url=hit.source_url)


def _result(sentence: Sentence, status: Status, reason: str, **extra) -> JudgeResult:
    extra.setdefault("span", Span(start=sentence.start, end=sentence.end))
    return JudgeResult(sentence_ids=[sentence.id], text=sentence.text, status=status, reason=reason, **extra)


def _to_result(sentence: Sentence, verdict: LlmVerdict, hits: list[CaseHit]) -> JudgeResult:
    """검증을 거친 Gemini 의 답을 API 응답 형식으로 바꾼다."""
    extra = {}
    if verdict.status is not Status.OK and verdict.problem_text:
        start = sentence.start + sentence.text.find(verdict.problem_text)
        extra["span"] = Span(start=start, end=start + len(verdict.problem_text))
    if verdict.status is Status.VIOLATION:
        extra["violation_type"] = _VIOLATION_TYPE.get(verdict.step)
    if verdict.status in (Status.VIOLATION, Status.REVIEW) and verdict.step is not Step.NONE:
        # 해당 단계가 없으면(제품 정보 확인이 필요한 경우 등) 고쳐 쓸 대상이 없다
        extra["revision"] = verdict.revision or None
    by_id = {hit.id: hit for hit in hits}
    extra["evidence"] = [_evidence(by_id[i]) for i in verdict.evidence_ids]
    reason = verdict.reason
    if verdict.status is Status.HOLD:
        reason = "건강기능식품인지에 따라 판정이 달라지는 표현입니다. 제품 종류를 확인해 주세요."
    elif verdict.status is Status.OK and verdict.matched_function:
        reason = f"{reason} (인정받은 기능성: {verdict.matched_function})"
    return _result(sentence, verdict.status, reason, **extra)


def run_judge(request: JudgeRequest) -> JudgeResponse:
    started = time.perf_counter()
    mode = request.mode
    use_cases = mode in (Mode.B, Mode.C)
    # mode C 에서만 제품 정보를 쓴다. 정보가 없으면 종류를 모르는 것으로 본다.
    product = (request.product or Product(type=ProductType.UNKNOWN)) if mode is Mode.C else None
    sentences = split_sentences(request.ad_text)

    results: dict[str, JudgeResult] = {}
    to_ask: list[tuple[Sentence, list[Tag]]] = []
    for sentence in sentences:
        tags = tag_sentence(sentence)
        if _is_mandatory_only(sentence, tags):
            # 판단 흐름 1단계: 법정 의무 문구는 AI 에 보내지 않고 제외한다
            results[sentence.id] = _result(sentence, Status.OK, "법정 의무 문구입니다.")
        else:
            to_ask.append((sentence, tags))

    if to_ask:
        if use_cases:
            all_hits = search_cases([sentence.text for sentence, _ in to_ask])
        else:
            all_hits = [[] for _ in to_ask]
        items: list[_Item] = []
        for (sentence, tags), hits in zip(to_ask, all_hits):
            if product is not None:
                # 사례가 이 제품에 적용되는지는 코드가 정한다
                cases = [(hit, *case_applicability(hit, product.type)) for hit in hits]
            else:
                cases = [(hit, True, "") for hit in hits]
            items.append((sentence, tags, cases))

        output = generate_json(_build_prompt(items, mode, product), LlmOutput)
        verdicts: dict[str, LlmVerdict] = {}
        for v in output.verdicts:
            verdicts.setdefault(v.sentence_id, v)  # 같은 문장에 답이 여러 개면 첫 번째만
        for sentence, tags, cases in items:
            verdict = verdicts.get(sentence.id)
            if verdict is None:
                logger.warning("[%s] %s: Gemini 가 답을 빠뜨림", request.request_id, sentence.id)
                results[sentence.id] = _result(
                    sentence, Status.REVIEW, "판정 결과를 받지 못했습니다. 직접 확인이 필요합니다."
                )
                continue
            ctx = VerifyContext(
                allowed_evidence_ids=frozenset(hit.id for hit, usable, _ in cases if usable),
                product_type=product.type if product else None,
                approved_functions=tuple(product.approved_functions) if product else (),
            )
            fixed, notes = verify(sentence, tags, verdict, ctx)
            if notes:
                logger.warning(
                    "[%s] %s 검증 기록: %s | 원래 답: %s",
                    request.request_id, sentence.id, "; ".join(notes), verdict.model_dump(mode="json"),
                )
            results[sentence.id] = _to_result(sentence, fixed, [hit for hit, _, _ in cases])

    return JudgeResponse(
        sentences=sentences,
        results=[results[s.id] for s in sentences],
        model=GEMINI_MODEL,
        kb_version=kb_version() if use_cases else "none",  # 사례 DB 기준일
        elapsed_ms=int((time.perf_counter() - started) * 1000),
    )