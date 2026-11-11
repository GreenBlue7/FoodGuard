"""판정 순서: 문장 나누기 → 사전 태그 → (사례 검색) → Gemini 판정 → 코드 검증 → 응답 형식으로 변환.

사전 태그는 의무 문구 제외와 코드 검증에만 쓰고, Gemini 에는 보여 주지 않는다.
문장이 많으면 BATCH_SIZE 개씩 나눠서 Gemini 를 여러 번 부른다.

mode A: 문구만 / mode B: + 사례 검색 / mode C: + 사례 검색 + 제품 정보
"""

import logging
import os
import re
import time
from difflib import SequenceMatcher
from pathlib import Path

from app.config import GEMINI_MODEL
from app.fact_check import _registry_block, check_facts
from app.llm import generate_json
from app.llm_schema import ConfirmOutput, Downgrade, LlmOutput, LlmVerdict, Recheck, RecheckOutput, Step
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
from app.page_rules import apply_page_rules
from app.verifier import VerifyContext, is_approved_wording, verify

logger = logging.getLogger("judge")

_PROMPTS = Path(__file__).parent / "prompts" / "service"
_PROMPT = (_PROMPTS / "judge_ab_v0.md").read_text(encoding="utf-8")  # mode A, B
_CASE_RULES = (_PROMPTS / "cases_v0.md").read_text(encoding="utf-8")  # mode B
_PROMPT_C = (_PROMPTS / "judge_c_v0.md").read_text(encoding="utf-8")  # mode C

# 시험용 스위치. after 면 사례를 Gemini 에 보여 주지 않고, 판정이 끝난 뒤 위반·검토 필요 문장에 근거로만 붙인다
CASES_AFTER = os.environ.get("CASE_PLACEMENT", "prompt") == "after"
if CASES_AFTER:
    _PROMPT_C = (_PROMPTS / "judge_c_after.md").read_text(encoding="utf-8")

# 시험용 스위치. v1 이면 등록 정보(원료·원문·주의사항·섭취 방법)를 쓰고, 상세페이지 내용 기준을 더한 프롬프트를 쓴다
JUDGE_V1 = os.environ.get("JUDGE_VARIANT", "v1") == "v1"
# 시험용 스위치. v2 면 공식 심의 기준(시행령 별표 1, 식약처 고시, 2025 공통심의기준)을 넣은 프롬프트와 페이지 규칙을 쓴다
RULES_V2 = os.environ.get("JUDGE_RULES", "v2") == "v2"
_RULES = "v2" if RULES_V2 else "v1"
if JUDGE_V1:
    _PROMPT_C = (_PROMPTS / f"judge_c_{_RULES}.md").read_text(encoding="utf-8")

# 시험용 스위치. 켜면 위반 판정만 한 번 더 확인해서, 분명하지 않으면 검토 필요로 내린다
JUDGE_CONFIRM = os.environ.get("JUDGE_CONFIRM", "1") == "1"
_PROMPT_CONFIRM = (_PROMPTS / f"judge_confirm_{_RULES}.md").read_text(encoding="utf-8")
CONTEXT_SIZE = 3  # 다시 확인할 때 앞뒤로 보여 줄 문장 수
CONFIRM_RUNS = int(os.environ.get("JUDGE_CONFIRM_RUNS", "2"))  # 위반 확인을 몇 번 물을지

# 시험용 스위치. 켜면 숫자·성분 주장을 등록 정보와 페이지 표시사항에 맞춰 보는 사실 대조를 한다
JUDGE_FACT = os.environ.get("JUDGE_FACT", "1") == "1"
# 위반을 확정하는 단계(다시 확인, 사실 대조)에만 쓰는 상위 모델. 비우면 1차 판정과 같은 모델을 쓴다
STRONG_MODEL = os.environ.get("JUDGE_STRONG_MODEL", "gemini-3.5-flash") or None
_DOWNGRADE_LABEL = {
    Downgrade.NO_BODY_EFFECT: "몸의 기능 효과를 말하지 않은 문장",
    Downgrade.PARAPHRASE: "인정받은 기능성을 바꿔 말한 표현",
    Downgrade.TRIAL: "인체적용시험 결과",
    Downgrade.HEALTH_INFO: "제품 효과와 이어지지 않은 건강 정보",
    Downgrade.QUALITY_SERVICE: "품질·원료 자랑이나 서비스 안내",
    Downgrade.TESTIMONIAL: "인정받은 기능성 범위 안의 효과를 말하는 후기",
    Downgrade.BROKEN_TEXT: "글자가 깨지거나 잘린 문장",
}


def _confirm_violations(sentences: list[Sentence], results: dict[str, JudgeResult], product: Product) -> None:
    """위반 판정을 엄격한 기준으로 다시 확인한다. 위반을 내리기만 하고, 올리거나 OK 로 바꾸지는 않는다."""
    flagged = [i for i, s in enumerate(sentences) if results[s.id].status is Status.VIOLATION]
    if not flagged:
        return
    blocks = []
    for i in flagged:
        sentence = sentences[i]
        before = " / ".join(s.text for s in sentences[max(0, i - CONTEXT_SIZE) : i]) or "없음"
        after = " / ".join(s.text for s in sentences[i + 1 : i + 1 + CONTEXT_SIZE]) or "없음"
        blocks.append(
            f"[{sentence.id}] {sentence.text}\n  앞: {before}\n  뒤: {after}\n  1차 판정 이유: {results[sentence.id].reason}"
        )
    items = []
    # 같은 입력에도 답이 흔들리므로 순서를 바꿔 여러 번 묻고, 한 번이라도 단정하기 어렵다고 하면 낮춘다
    for run in range(CONFIRM_RUNS):
        ordered = blocks if run % 2 == 0 else blocks[::-1]
        for first in range(0, len(ordered), BATCH_SIZE):
            prompt = _PROMPT_CONFIRM.replace("{{PRODUCT}}", _product_block(product)).replace(
                "{{ITEMS}}", "\n".join(ordered[first : first + BATCH_SIZE])
            )
            items.extend(generate_json(prompt, ConfirmOutput, model=STRONG_MODEL).items)
    flagged_ids = {sentences[i].id for i in flagged}
    for item in items:
        if item.sentence_id not in flagged_ids or item.decision is Downgrade.KEEP:
            continue  # 답이 빠진 문장은 1차 판정을 그대로 둔다
        if results[item.sentence_id].status is not Status.VIOLATION:
            continue  # 앞선 확인에서 이미 낮춘 문장
        old = results[item.sentence_id]
        results[item.sentence_id] = old.model_copy(
            update={
                "status": Status.REVIEW,
                "violation_type": None,
                "reason": f"{_DOWNGRADE_LABEL[item.decision]}이라 위반으로 단정하지 않았습니다. 직접 확인해 주세요. {item.reason} (1차 판단: {old.reason})",
            }
        )

# 시험용 스위치. 켜면 1차에서 정상으로 나온 문장 중 위험 신호가 있는 것을 다시 봐서, 애매하면 검토 필요로 올린다
JUDGE_RECHECK = os.environ.get("JUDGE_RECHECK", "1") == "1"
_PROMPT_RECHECK = (_PROMPTS / f"judge_recheck_{_RULES}.md").read_text(encoding="utf-8")
# 위험 신호: 후기 말투·따옴표, 효과를 장담하는 말, 흡수·순도·최초 같은 사실 주장, 권위, 증상 있는 대상
_RISK = re.compile(
    r"[\"“”‘’'「」]|후기|리뷰|님\s*\)|구매|재구매|먹어\s*보|먹고\s*나|먹으니|드셔\s*보|섭취\s*후|"
    r"(?:졌|었|았|였|했|되)(?:어요|어용|더라|네요|습니다)|느낌이|좋아요|좋았|"
    r"DOWN|UP\b|CUT|OUT|ZERO|FREE|타파|저격|싹|확실|마법|입증|공략|걱정\s*(?:없|끝|안녕)|"
    r"흡수|생체\s*이용|세포|해독|디톡스|순도|순수|오직|단일|100\s*%|최초|유일|권장|필수|꼭\s*(?:챙|드)|"
    r"식약처|식품의약품안전처|정부|특허|전문가|의사|약사|박사|교수|추천|"
    r"(?:하신|있으신|많으신|잦으신|느끼시는|걱정되시는|불편하신|힘드신)\s*분|"
    # 원산지, 판매량·순위, 유명인, 원료의 일반 효능, 질병 정보
    r"[가-힣]{1,4}산\s*(?:원료|원재료)|원산지|만\s*(?:고집|사용|담|넣)|"
    r"\d[\d,.]*\s*만\s*(?:명|개|병|포|박스|세트|통)|누적|판매\s*(?:량|\d)|\d\s*위\b|인기|베스트|선택한|"
    r"배우|연예인|셀럽|스타(?!일)|인플루언서|선수|"
    r"항산화|따뜻|노화|피로|질환|질병|발병|환자|장기",
    re.IGNORECASE,
)
_RECHECK_LABEL = {
    Recheck.TESTIMONIAL: "소비자 후기·체험기로 몸의 효과를 말하는 문장",
    Recheck.STRONGER_CLAIM: "인정받은 기능성을 장담하거나 과장한 표현",
    Recheck.UNAPPROVED_EFFECT: "인정받은 기능성에 없는 효과를 내비치는 표현",
    Recheck.FACT_CLAIM: "등록 정보로 확인해야 하는 사실 주장",
    Recheck.AUTHORITY: "기관·전문가가 인정·보증하는 것처럼 보이는 표현",
    Recheck.SYMPTOM_TARGET: "증상 개선을 내비치는 섭취 대상 표현",
    Recheck.DISEASE_INFO: "제품이 질병을 막는 것처럼 보일 수 있는 질병 정보",
}
# 이보다 짧은 조각("UP", "100%", "ZERO")은 앞뒤 문장에서 이미 다뤄지므로 다시 보지 않는다
_RECHECK_MIN_CHARS = 6
# 후기라도 맛·향·먹기 편함만 말하고 몸의 변화를 말하지 않으면 정상으로 둔다
_TASTE = re.compile(r"맛|향|냄새|비린|시큼|새콤|달콤|깔끔|목\s*넘김|알약|크기|작아서|먹기\s*편|마시기\s*편|간편|배송|포장")
_BODY = re.compile(
    r"좋아졌|편해졌|나아졌|달라졌|가벼워|개운|피곤|피로|몸|피부|눈|화장실|변비|잘\s*맞|차이|효과|변화|줄었|늘었|빠졌|덜\s|"
    r"아침|컨디션|활력|기운|잠|수치|혈당|혈압|관절|붓기|속이"
)


def _recheck_ok(sentences: list[Sentence], results: dict[str, JudgeResult], product: Product) -> None:
    """1차에서 정상으로 나온 위험 신호 문장을 다시 본다. 검토 필요로 올리기만 하고, 위반으로 올리지는 않는다."""
    picked = [
        i for i, s in enumerate(sentences)
        if results[s.id].status is Status.OK
        and _RISK.search(s.text)
        and len(_squash_text(s.text)) >= _RECHECK_MIN_CHARS
        and results[s.id].reason not in ("법정 의무 문구입니다.", "제품에 등록된 섭취 시 주의사항입니다.")
        and not (RULES_V2 and is_approved_wording(s.text, product.approved_functions))
    ]
    if not picked:
        return
    blocks = []
    for i in picked:
        sentence = sentences[i]
        before = " / ".join(s.text for s in sentences[max(0, i - CONTEXT_SIZE) : i]) or "없음"
        after = " / ".join(s.text for s in sentences[i + 1 : i + 1 + CONTEXT_SIZE]) or "없음"
        blocks.append(f"[{sentence.id}] {sentence.text}\n  앞: {before}\n  뒤: {after}")
    product_info = _product_block(product) + "\n" + "\n".join(_registry_block(product).splitlines()[1:])
    items = []
    for first in range(0, len(blocks), BATCH_SIZE):
        prompt = _PROMPT_RECHECK.replace("{{PRODUCT}}", product_info).replace(
            "{{ITEMS}}", "\n".join(blocks[first : first + BATCH_SIZE])
        )
        items.extend(generate_json(prompt, RecheckOutput, model=STRONG_MODEL).items)
    picked_ids = {sentences[i].id for i in picked}
    for item in items:
        if item.sentence_id not in picked_ids or item.decision is Recheck.OK:
            continue
        old = results[item.sentence_id]
        if item.decision is Recheck.TESTIMONIAL and _TASTE.search(old.text) and not _BODY.search(old.text):
            continue
        results[item.sentence_id] = old.model_copy(
            update={
                "status": Status.REVIEW,
                "reason": f"{_RECHECK_LABEL[item.decision]}이라 확인이 필요합니다. {item.reason}",
            }
        )


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

"""
_TAG_LABEL = {
    TagType.MANDATORY: "법정 의무 문구",
    TagType.DISEASE: "질병",
    TagType.DRUG: "의약품 오인",
    TagType.EXAGGERATION: "과장",
    TagType.TESTIMONIAL: "체험기",
}
"""

# Gemini 를 한 번 부를 때 보내는 문장 수. 한꺼번에 너무 많이 보내면 답을 빠뜨린다 (450문장에서 9문장만 답함)
BATCH_SIZE = 30

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
    lines = [
        f"- 제품 종류: {PRODUCT_LABEL[product.type]}",
        f"- 제품명: {', '.join(product.names) or '없음'}",
        f"- 인정받은 기능성: {functions}",
    ]
    if JUDGE_V1:
        # 식약처 등록 정보에 있는 값만 그대로 보여 준다
        if product.ingredients:
            lines.append(f"- 기능성 원료: {', '.join(product.ingredients)}")
        if product.function_source:
            source = product.function_source.replace("\n", " / ")
            lines.append(f"- 원료별 기능성 원문: {source}")
        if product.intake:
            lines.append(f"- 섭취 방법: {product.intake}")
    return "\n".join(lines)


def _squash_text(text: str) -> str:
    return re.sub(r"[\W_]+", "", text)


def _is_registered_caution(sentence: Sentence, product: Product | None) -> bool:
    """제품에 등록된 섭취 시 주의사항(또는 그 일부)을 그대로 옮긴 문장인지 본다."""
    if product is None or not product.cautions:
        return False
    text = _squash_text(re.sub(r"^\s*(?:\([가-하]\)|[①-⑳]|\d{1,2}[.)])", "", sentence.text))
    if len(text) < 6:
        return False
    for caution in product.cautions:
        squashed = _squash_text(caution)
        if text in squashed or SequenceMatcher(None, text, squashed).ratio() >= 0.85:
            return True
    return False


def _build_prompt(items: list[_Item], mode: Mode, product: Product | None) -> str:
    lines = []
    for sentence, _, cases in items:
        # 사전 태그는 프롬프트에 넣지 않는다 ("100% 국내산", "보장균수" 같은 정상 문장을 AI 가 의심하게 만들었다)
        lines.append(f"[{sentence.id}] {sentence.text}")
        if cases and not CASES_AFTER:
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


def _to_result(sentence: Sentence, verdict: LlmVerdict, hits: list[CaseHit], after_hits: list[CaseHit] = ()) -> JudgeResult:
    """검증을 거친 Gemini 의 답을 API 응답 형식으로 바꾼다. after_hits 는 판정 뒤에 붙이는 사례."""
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
    if after_hits and verdict.status in (Status.VIOLATION, Status.REVIEW):
        extra["evidence"] = [_evidence(hit) for hit in after_hits]
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
        elif JUDGE_V1 and _is_registered_caution(sentence, product):
            results[sentence.id] = _result(sentence, Status.OK, "제품에 등록된 섭취 시 주의사항입니다.")
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

        verdicts: dict[str, LlmVerdict] = {}
        for first in range(0, len(items), BATCH_SIZE):
            batch = items[first : first + BATCH_SIZE]
            output = generate_json(_build_prompt(batch, mode, product), LlmOutput)
            asked = {sentence.id for sentence, _, _ in batch}
            for v in output.verdicts:
                if v.sentence_id in asked:  # 이번에 묻지 않은 문장에 대한 답은 버린다
                    verdicts.setdefault(v.sentence_id, v)  # 같은 문장에 답이 여러 개면 첫 번째만
        for sentence, tags, cases in items:
            verdict = verdicts.get(sentence.id)
            if verdict is None:
                logger.warning("[%s] %s: Gemini 가 답을 빠뜨림", request.request_id, sentence.id)
                results[sentence.id] = _result(
                    sentence, Status.REVIEW, "판정 결과를 받지 못했습니다. 직접 확인이 필요합니다."
                )
                continue
            # after 일 때는 Gemini 가 사례를 못 봤으므로 사례 번호를 받지 않고, 이 제품에 쓸 수 있는 사례만 뒤에 붙인다
            usable_hits = [hit for hit, usable, _ in cases if usable]
            ctx = VerifyContext(
                allowed_evidence_ids=frozenset() if CASES_AFTER else frozenset(hit.id for hit in usable_hits),
                product_type=product.type if product else None,
                approved_functions=tuple(product.approved_functions) if product else (),
            )
            fixed, notes = verify(sentence, tags, verdict, ctx)
            if notes:
                logger.warning(
                    "[%s] %s 검증 기록: %s | 원래 답: %s",
                    request.request_id, sentence.id, "; ".join(notes), verdict.model_dump(mode="json"),
                )
            results[sentence.id] = _to_result(
                sentence, fixed, [hit for hit, _, _ in cases], usable_hits if CASES_AFTER else []
            )

    if JUDGE_CONFIRM and product is not None:
        _confirm_violations(sentences, results, product)
    if JUDGE_RECHECK and product is not None:
        _recheck_ok(sentences, results, product)
    if JUDGE_FACT and product is not None:
        # 위반 다시 확인 뒤에 한다. 사실 대조로 올린 위반은 근거가 등록 정보·페이지에 있어서 다시 내리지 않는다
        check_facts(sentences, results, product, request.ad_text, model=STRONG_MODEL)
    if RULES_V2 and product is not None:
        # 공식 기준 중 코드로 확인할 수 있는 것 (필수 안내 문구, 등록 원재료와 다른 '단일·오직', 출처 없는 1위)
        apply_page_rules(sentences, results, product, request.ad_text)

    return JudgeResponse(
        sentences=sentences,
        results=[results[s.id] for s in sentences],
        model=GEMINI_MODEL,
        kb_version=kb_version() if use_cases else "none",  # 사례 DB 기준일
        elapsed_ms=int((time.perf_counter() - started) * 1000),
    )