"""Gemini 의 답을 코드로 한 번 더 점검한다. 고치거나 눈여겨볼 내용은 notes 로 함께 돌려준다."""

import re
from dataclasses import dataclass

from app.llm_schema import LlmVerdict, Step
from app.product_rules import STEP_REQUIRES
from app.schemas import ProductType, Sentence, Status
from app.tagger import Tag, TagType, tag_sentence

_RISKY_TAGS = {TagType.DISEASE, TagType.DRUG}

# True 로 바꾸면: 질병·의약품 오인 단어가 있는 문장을 AI 가 OK 라고 해도 REVIEW 로 올린다.
# 놓치는 위반은 줄지만 "감기 조심하세요" 같은 문장까지 REVIEW 가 된다. 개발용 데이터로 확인한 뒤 정한다.
RECHECK_OK_WITH_RISKY_WORDS = False


@dataclass(frozen=True)
class VerifyContext:
    allowed_evidence_ids: frozenset[str] = frozenset()  # 이 문장의 근거로 쓸 수 있는 사례 번호
    product_type: ProductType | None = None  # None 이면 제품 정보를 쓰지 않는 mode (A, B)
    approved_functions: tuple[str, ...] = ()

def _squash(text: str) -> str:
    return "".join(text.split())

def _function_core(text: str) -> str:
    """기능성 문구에서 번호, 문장부호, 띄어쓰기, 끝의 '~에 도움을 줄 수 있음'을 뺀 핵심만 남긴다."""
    text = re.sub(r"[①-⑳]|[\W_]+", "", text)
    return re.sub(r"(?:에)?도움을(?:줄수있음|줄수있습니다|줌)$", "", text)


import os

# 시험용 스위치. v2 면 인정 기능성 문구를 그대로 옮긴 문장을 정상으로 둔다 (질병 위험 감소 기능성 포함, 시행령 별표 1 제1호 라목 1))
RULES_V2 = os.environ.get("JUDGE_RULES", "v2") == "v2"
_HELP = re.compile(r"(?:에)?도움을(?:줄수있음|줄수있습니다|줌|줍니다)|에필요(?:함|합니다)?")
_LABEL = re.compile(r"영양기능정보|기능성내용|기능정보|기능성")
_SOURCE_LINE = re.compile(r"^\s*[*※]?\s*(?:출처|자료|참고\s*문헌|참고)\s*[:：]")
_VAGUE_HEALTH = re.compile(r"몸에\s*좋|건강에\s*좋|건강한|건강을\s*(?:위한|챙기)|웰빙")
_BODY_PART = re.compile(
    r"눈|목|간|장\s*건강|위|뼈|관절|피부|혈|면역|피로|체지방|지방|다이어트|살|두뇌|뇌|기억|혈당|혈압|콜레스테롤|변|소화|수면|키|성장|"
    r"감기|질병|예방|치료|해독|디톡스"
)
# 대상 없이 효과·방향만 말하는 짧은 조각 (영어 한 단어 또는 효과 동사 하나)
_FRAGMENT = re.compile(r"[A-Za-z]{1,8}|차단|감소|증가|개선|억제|타파|저격|케어|관리|완화|해결|강화|활성")


def _wording_core(text: str) -> str:
    text = re.sub(r"[①-⑳]|[\W_]+", "", text)
    return _HELP.sub("", _LABEL.sub("", text))


def is_approved_wording(text: str, approved_functions) -> bool:
    """문장이 인정받은 기능성 문구(여러 개를 이어 쓴 것 포함)를 그대로 옮긴 것인지 본다. 다른 주장이 붙으면 아니다."""
    rest = _wording_core(text)
    if len(rest) < 6:
        return False
    cores = sorted({_wording_core(f) for f in approved_functions if len(_wording_core(f)) >= 4}, key=len, reverse=True)
    found = False
    for core in cores:
        if core in rest:
            rest = rest.replace(core, "")
            found = True
    # 이어 쓴 문구 사이의 "및", "또는", 원료 이름 같은 짧은 글자만 남아야 한다
    return found and len(rest) <= 4


def _risky_words(text: str) -> list[str]:
    sentence = Sentence(id="tmp", text=text, start=0, end=len(text))
    return [t.text for t in tag_sentence(sentence) if t.type in _RISKY_TAGS]


def verify(
    sentence: Sentence, tags: list[Tag], verdict: LlmVerdict, ctx: VerifyContext
) -> tuple[LlmVerdict, list[str]]:
    notes: list[str] = []
    status = verdict.status
    step = verdict.step
    reason = verdict.reason.strip()
    problem_text = verdict.problem_text
    revision = verdict.revision.strip()
    # 인정 기능성 대조는 제품 정보를 쓰는 mode 에서만 한다
    matched_function = verdict.matched_function.strip() if ctx.product_type is not None else ""

    if status is Status.HOLD and ctx.product_type is not ProductType.UNKNOWN:
        # 판단 보류는 mode C 에서 제품 종류를 모를 때만 쓴다
        status = Status.REVIEW
        notes.append("HOLD -> REVIEW")

    if ctx.product_type is not None and step in STEP_REQUIRES and ctx.product_type not in STEP_REQUIRES[step]:
        # 제품 종류에 맞지 않는 단계 (예: 건강기능식품인데 '건기식 오인'). 제품 정보를 쓰지 않는 mode 에서는 추측을 허용한다
        notes.append(f"제품 종류에 맞지 않는 단계 {step.value} 를 뺌")
        step = Step.NONE

    if (
        ctx.product_type is ProductType.FUNCTIONAL_LABELED_FOOD
        and not ctx.approved_functions
        and step is Step.HEALTH_FOOD_CONFUSION
    ):
        # 허용 문구를 모르면 넘었는지 판단할 수 없다. 판매자에게 자율심의 문구 입력을 안내한다
        status = Status.REVIEW
        step = Step.NONE
        reason = f"기능성 표시 일반식품의 자율심의 허용 문구를 입력하면 정확히 판정할 수 있습니다. (AI 판단: {reason})"
        notes.append("허용 문구 없는 기능성 표시 일반식품 -> REVIEW")

    if (
        RULES_V2
        and ctx.product_type is not None
        and status is not Status.OK
        and is_approved_wording(sentence.text, ctx.approved_functions)
    ):
        # 인정받은 기능성 문구를 그대로 쓴 문장은 질병 이름이 들어 있어도 정상이다 (예: "골다공증발생 위험 감소에 도움을 줌")
        notes.append(f"인정 문구 그대로인 문장 {status.value} -> OK")
        status = Status.OK
        step = Step.NONE
        revision = ""
        reason = "인정받은 기능성 문구를 그대로 쓴 문장입니다."

    if RULES_V2 and status is not Status.OK and _SOURCE_LINE.match(sentence.text):
        # "* 출처: ○○" 처럼 근거를 밝히는 줄은 광고 주장이 아니다
        notes.append(f"출처 표시 줄 {status.value} -> OK")
        status, step, revision = Status.OK, Step.NONE, ""
        reason = "자료의 출처를 밝히는 줄입니다."

    if (
        RULES_V2
        and ctx.product_type is ProductType.GENERAL_FOOD
        and status is Status.VIOLATION
        and _VAGUE_HEALTH.search(sentence.text)
        and not _BODY_PART.search(sentence.text)
    ):
        # 부위·기능·질병 없이 막연한 건강만 말하면 검토 필요 (식약처 2026.6 단속 사례는 "목에 좋은"처럼 부위가 있는 표현)
        notes.append("막연한 건강 표현 VIOLATION -> REVIEW")
        status = Status.REVIEW
        reason = f"부위·기능을 말하지 않는 막연한 건강 표현이라 위반으로 단정하지 않았습니다. 건강기능식품으로 오인되지 않는지 확인해 주세요. (AI 판단: {reason})"

    if RULES_V2 and status is Status.VIOLATION and _FRAGMENT.fullmatch(re.sub(r"[\W_\d]+", "", sentence.text)):
        # "DOWN", "차단" 처럼 대상 없이 효과 단어만 있는 조각은 뜻이 앞뒤 그림·문장에 달려 있어 단정하지 않는다
        # ("혈당조절", "총명탕" 처럼 짧아도 뜻이 분명한 표현은 그대로 둔다)
        status = Status.REVIEW
        reason = f"짧은 조각이라 앞뒤 내용과 함께 확인이 필요합니다. (AI 판단: {reason})"
        notes.append("짧은 조각 VIOLATION -> REVIEW")

    if (
        RULES_V2
        and status is Status.VIOLATION
        and re.search(r"흡수\s*(?:율|력|률)|흡수가\s*(?:잘|빠|좋)|흡수를\s*(?:높|돕)|흡수에\s*용이|생체\s*이용", sentence.text)
        and not re.search(r"보다|대비|다른|일반|기존|\d\s*배", sentence.text)
    ):
        # 근거가 없는 흡수율 주장은 검토 필요. 다른 원료·제품과 비교할 때만 위반 (2025 공통심의기준 Ⅲ-2-하)
        status = Status.REVIEW
        reason = f"흡수율 주장은 SCIE급 인체시험 등 근거 자료가 있어야 쓸 수 있습니다. 근거를 확인해 주세요. (AI 판단: {reason})"
        notes.append("비교 없는 흡수율 VIOLATION -> REVIEW")

    if status is Status.VIOLATION and step in (Step.DISEASE, Step.UNAPPROVED_FUNCTION):
        # 인정받은 기능성 문구가 문장에 그대로 있으면 위반으로 단정하지 않는다 (예: "골다공증 발생 위험 감소에 도움")
        # 인정 문구에 다른 주장이 붙어 있을 수 있어서 OK 가 아니라 REVIEW 로 둔다
        sentence_core = _function_core(sentence.text)
        quoted = [f for f in ctx.approved_functions if len(_function_core(f)) >= 6 and _function_core(f) in sentence_core]
        if quoted:
            status = Status.REVIEW
            reason = f"인정받은 기능성 문구({quoted[0]})가 들어 있어 위반으로 단정하지 않았습니다. 직접 확인해 주세요. (AI 판단: {reason})"
            notes.append("인정 문구가 든 문장의 위반 -> REVIEW")

    if status is Status.VIOLATION and step is Step.NONE:
        # 위반 판정에는 판단 흐름의 어느 단계인지 근거가 있어야 한다
        status = Status.REVIEW
        notes.append("단계 없는 VIOLATION -> REVIEW")

    if status is Status.OK and step is not Step.NONE:
        # 살펴본 단계를 적어 둔 것뿐이다. 판정은 그대로 두고 단계만 비운다
        notes.append(f"OK 인데 단계가 {step.value} (단계만 비움)")
        step = Step.NONE

    if matched_function:
        # 띄어쓰기만 다른 것은 같은 것으로 본다 ("피로 개선" = "피로개선")
        # 목록 항목 뒤에 불필요한 글자가 붙은 경우와, 긴 항목의 일부만 적은 경우도 그 항목으로 본다
        squashed = _squash(matched_function)
        same = [f for f in ctx.approved_functions if squashed.startswith(_squash(f)) or squashed in _squash(f)]
        if not same:
            # 번호·문장부호·"~에 도움을 줄 수 있음"만 다른 경우와, 목록 항목 여러 개를 이어 적은 경우도 받는다
            # (예: "①유익한 유산균 증식②유해균 억제 또는 배변활동 원활"). 목록에 없는 말이 남으면 받지 않는다
            core = _function_core(matched_function)
            items = sorted(((f, _function_core(f)) for f in ctx.approved_functions), key=lambda p: -len(p[1]))
            rest = core
            for f, item in items:
                if item and item in rest:
                    rest = rest.replace(item, "")
                    same.append(f)
            if not same or len(rest) > 2:
                same = [f for f, item in items if core and core in item]
        if same:
            matched_function = same[0]

    if matched_function and matched_function not in ctx.approved_functions:
        # 인정받은 기능성 목록에 없는 값을 근거로 들었다
        notes.append(f"인정 기능성 목록에 없는 값({matched_function})을 근거로 듦")
        matched_function = ""
        if status is Status.OK:
            status = Status.REVIEW
            reason = f"인정받은 기능성 목록에서 근거를 확인하지 못했습니다. (AI 판단: {reason})"

    if status is Status.OK:
        words = [t.text for t in tags if t.type in _RISKY_TAGS]
        if words and RECHECK_OK_WITH_RISKY_WORDS:
            status = Status.REVIEW
            reason = f"'{', '.join(words)}' 표현이 들어 있어 직접 확인이 필요합니다. (AI 판단: {reason})"
            notes.append("주의 단어가 있는데 OK -> REVIEW")
        elif words:
            notes.append(f"주의 단어({', '.join(words)})가 있는데 OK (판정은 그대로 둠)")

    if problem_text and problem_text not in sentence.text:
        problem_text = ""  # 문장에 없는 글자면 문장 전체를 가리키게 한다
        notes.append("문제 구간이 문장에 없음")

    if revision:
        words = _risky_words(revision)
        if words:
            # 수정안에 질병·의약품 오인 단어가 있으면 제안하지 않는다
            revision = ""
            notes.append(f"수정안에 주의 단어({', '.join(words)})가 있어 버림")

    # 근거로 든 사례는 이 문장에 보여 준, 이 제품에 적용되는 사례여야 한다 (지어낸 번호 차단)
    evidence_ids = [i for i in dict.fromkeys(verdict.evidence_ids) if i in ctx.allowed_evidence_ids]
    if len(evidence_ids) != len(verdict.evidence_ids):
        notes.append("근거로 쓸 수 없는 사례 번호를 버림")
    if status is Status.OK and evidence_ids:
        evidence_ids = []  # 적발 사례를 '문제없음'의 근거로 붙이지 않는다
        notes.append("OK 판정이라 근거 사례를 뺌")

    if not reason:
        if status is Status.OK:
            reason = "해당하는 위반 유형이 없습니다."
        else:
            reason = "판정 이유가 제공되지 않았습니다. 직접 확인이 필요합니다."
        notes.append("이유 없음")

    fixed = verdict.model_copy(
        update={
            "status": status,
            "step": step,
            "reason": reason,
            "problem_text": problem_text,
            "revision": revision,
            "evidence_ids": evidence_ids,
            "matched_function": matched_function,
        }
    )
    return fixed, notes