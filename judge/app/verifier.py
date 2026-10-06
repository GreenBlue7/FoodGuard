"""Gemini 의 답을 코드로 한 번 더 점검한다. 고치거나 눈여겨볼 내용은 notes 로 함께 돌려준다."""

from app.llm_schema import LlmVerdict, Step
from app.schemas import Sentence, Status
from app.tagger import Tag, TagType, tag_sentence

_RISKY_TAGS = {TagType.DISEASE, TagType.DRUG}

# True 로 바꾸면: 질병·의약품 오인 단어가 있는 문장을 AI 가 OK 라고 해도 REVIEW 로 올린다.
# 놓치는 위반은 줄지만 "감기 조심하세요" 같은 문장까지 REVIEW 가 된다. 개발용 데이터로 확인한 뒤 정한다.
RECHECK_OK_WITH_RISKY_WORDS = False


def _risky_words(text: str) -> list[str]:
    sentence = Sentence(id="tmp", text=text, start=0, end=len(text))
    return [t.text for t in tag_sentence(sentence) if t.type in _RISKY_TAGS]


def verify(
    sentence: Sentence, tags: list[Tag], verdict: LlmVerdict, allowed_evidence_ids: set[str]
) -> tuple[LlmVerdict, list[str]]:
    notes: list[str] = []
    status = verdict.status
    reason = verdict.reason.strip()
    problem_text = verdict.problem_text
    revision = verdict.revision.strip()

    if status is Status.HOLD:
        # 판단 보류는 제품 종류를 모를 때만 쓴다 (mode C)
        status = Status.REVIEW
        notes.append("HOLD -> REVIEW")

    if status is Status.VIOLATION and verdict.step is Step.NONE:
        # 위반 판정에는 판단 흐름의 어느 단계인지 근거가 있어야 한다
        status = Status.REVIEW
        notes.append("단계 없는 VIOLATION -> REVIEW")

    if status is Status.OK and verdict.step is not Step.NONE:
        status = Status.REVIEW
        notes.append(f"OK 인데 단계가 {verdict.step.value} -> REVIEW")

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

    # 근거로 든 사례는 이 문장에 실제로 보여 준 사례여야 한다 (지어낸 번호 차단)
    evidence_ids = [i for i in dict.fromkeys(verdict.evidence_ids) if i in allowed_evidence_ids]
    if len(evidence_ids) != len(verdict.evidence_ids):
        notes.append("보여 주지 않은 사례 번호를 근거로 들어 버림")
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
            "reason": reason,
            "problem_text": problem_text,
            "revision": revision,
            "evidence_ids": evidence_ids,
        }
    )
    return fixed, notes