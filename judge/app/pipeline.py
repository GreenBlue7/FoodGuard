"""판정 순서: 문장 나누기 → 사전 태그 → Gemini 판정 → 응답 형식으로 변환 (현재 mode A)."""

import re
import time
from enum import Enum
from pathlib import Path

from pydantic import BaseModel

from app.config import GEMINI_MODEL
from app.llm import generate_json
from app.schemas import JudgeRequest, JudgeResponse, JudgeResult, Sentence, Span, Status
from app.splitter import split_sentences
from app.tagger import Tag, TagType, tag_sentence

PROMPT_VERSION = "judge_v0"
_PROMPT = (Path(__file__).parent / "prompts" / f"{PROMPT_VERSION}.md").read_text(encoding="utf-8")


class Step(str, Enum):
    NONE = "NONE"
    DISEASE = "DISEASE"
    DRUG = "DRUG"
    EXAGGERATION = "EXAGGERATION"
    DECEPTION = "DECEPTION"


class LlmVerdict(BaseModel):
    sentence_id: str
    status: Status
    step: Step
    reason: str
    problem_text: str
    revision: str


class LlmOutput(BaseModel):
    verdicts: list[LlmVerdict]


# 조항 번호는 AI 가 아니라 코드가 붙인다 (지어내지 못하게)
_VIOLATION_TYPE = {
    Step.DISEASE: "제8조 제1항 제1호",
    Step.DRUG: "제8조 제1항 제2호",
    Step.EXAGGERATION: "제8조 제1항 제4호",
    Step.DECEPTION: "제8조 제1항 제5호",
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


def _is_mandatory_only(sentence: Sentence, tags: list[Tag]) -> bool:
    mandatory = [t for t in tags if t.type is TagType.MANDATORY]
    if not mandatory:
        return False
    rest = sentence.text
    for tag in sorted(mandatory, key=lambda t: t.start, reverse=True):
        rest = rest[: tag.start - sentence.start] + rest[tag.end - sentence.start :]
    return _FILLER.sub("", rest) == ""


def _build_prompt(items: list[tuple[Sentence, list[Tag]]]) -> str:
    lines = []
    for sentence, tags in items:
        lines.append(f"[{sentence.id}] {sentence.text}")
        if tags:
            words = ", ".join(f"{_TAG_LABEL[t.type]}({t.text})" for t in tags)
            lines.append(f"  주의 단어: {words}")
    return _PROMPT.replace("{{SENTENCES}}", "\n".join(lines))


def _whole(sentence: Sentence) -> Span:
    return Span(start=sentence.start, end=sentence.end)


def _to_result(sentence: Sentence, verdict: LlmVerdict) -> JudgeResult:
    status = Status.REVIEW if verdict.status is Status.HOLD else verdict.status
    flagged = status in (Status.VIOLATION, Status.REVIEW)

    # 문제 구간이 문장 안에 실제로 있으면 그 위치를, 없으면 문장 전체를 가리킨다
    span = _whole(sentence)
    index = sentence.text.find(verdict.problem_text) if verdict.problem_text else -1
    if flagged and index >= 0:
        start = sentence.start + index
        span = Span(start=start, end=start + len(verdict.problem_text))

    return JudgeResult(
        sentence_ids=[sentence.id],
        text=sentence.text,
        status=status,
        violation_type=_VIOLATION_TYPE.get(verdict.step) if status is Status.VIOLATION else None,
        reason=verdict.reason,
        span=span,
        # 해당 단계가 없으면(제품 정보 확인이 필요한 경우 등) 고쳐 쓸 대상이 없다
        revision=(verdict.revision or None) if flagged and verdict.step is not Step.NONE else None,
    )


def run_judge(request: JudgeRequest) -> JudgeResponse:
    started = time.perf_counter()
    sentences = split_sentences(request.ad_text)

    results: dict[str, JudgeResult] = {}
    to_ask: list[tuple[Sentence, list[Tag]]] = []
    for sentence in sentences:
        tags = tag_sentence(sentence)
        if _is_mandatory_only(sentence, tags):
            # 판단 흐름 1단계: 법정 의무 문구는 AI 에 보내지 않고 제외한다
            results[sentence.id] = JudgeResult(
                sentence_ids=[sentence.id],
                text=sentence.text,
                status=Status.OK,
                reason="법정 의무 문구입니다.",
                span=_whole(sentence),
            )
        else:
            to_ask.append((sentence, tags))

    if to_ask:
        output = generate_json(_build_prompt(to_ask), LlmOutput)
        verdicts = {v.sentence_id: v for v in output.verdicts}
        for sentence, _ in to_ask:
            verdict = verdicts.get(sentence.id)
            if verdict:
                results[sentence.id] = _to_result(sentence, verdict)
            else:
                results[sentence.id] = JudgeResult(
                    sentence_ids=[sentence.id],
                    text=sentence.text,
                    status=Status.REVIEW,
                    reason="판정 결과를 받지 못했습니다. 직접 확인이 필요합니다.",
                    span=_whole(sentence),
                )

    return JudgeResponse(
        sentences=sentences,
        results=[results[s.id] for s in sentences],
        model=GEMINI_MODEL,
        kb_version="none",  # 사례 DB 기준일. mode B 에서 채운다
        elapsed_ms=int((time.perf_counter() - started) * 1000),
    )