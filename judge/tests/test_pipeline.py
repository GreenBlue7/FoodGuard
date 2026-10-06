"""판정 흐름 전체를 Gemini 와 DB 없이 확인한다 (둘 다 가짜로 바꿔 끼운다)."""

from datetime import date

import pytest

import app.pipeline as pipeline
from app.llm_schema import LlmOutput, LlmVerdict
from app.retriever import CaseHit
from app.schemas import JudgeRequest, Status

AD = "홍삼으로 면역력 증진에 도움을 줄 수 있습니다.\n환절기 감기 걱정 끝!\n본 제품은 질병의 예방 및 치료를 위한 의약품이 아닙니다."
HEALTH_FOOD_CASE = CaseHit(
    "mfds-1-01", "면역력 증진", "건강기능식품 오인·혼동", "제8조 제1항 제3호", "일반식품",
    date(2024, 5, 29), "보도자료", "https://example.com/1", 0.68,
)
DISEASE_CASE = CaseHit(
    "mfds-2-01", "감기예방", "질병 예방·치료 효능", "제8조 제1항 제1호", "일반식품",
    date(2025, 6, 9), "보도자료", "https://example.com/2", 0.65,
)
HF_PRODUCT = {"type": "HEALTH_FUNCTIONAL", "names": ["홍삼정"], "approved_functions": ["면역력 증진"]}
GENERAL_PRODUCT = {"type": "GENERAL_FOOD", "names": ["홍삼차"]}


def verdict(sentence_id: str, **fields) -> LlmVerdict:
    base = {"status": "OK", "step": "NONE", "reason": "이유", "problem_text": "", "revision": ""}
    return LlmVerdict(sentence_id=sentence_id, **{**base, **fields})


@pytest.fixture
def fake(monkeypatch):
    """Gemini 와 사례 검색을 가짜로 바꾸고, 호출 내용을 기록한다."""
    calls = {"prompts": [], "searches": []}
    answers: list[LlmVerdict] = []

    def generate_json(prompt, schema):
        calls["prompts"].append(prompt)
        return LlmOutput(verdicts=list(answers))

    def search_cases(texts):
        calls["searches"].append(texts)
        return [[HEALTH_FOOD_CASE] if "면역력" in t else [DISEASE_CASE] if "감기" in t else [] for t in texts]

    monkeypatch.setattr(pipeline, "generate_json", generate_json)
    monkeypatch.setattr(pipeline, "search_cases", search_cases)
    monkeypatch.setattr(pipeline, "kb_version", lambda: "2025-11-06")
    calls["answers"] = answers
    return calls


def run(mode: str, product: dict | None = None, ad: str = AD):
    return pipeline.run_judge(JudgeRequest(request_id="t", mode=mode, product=product, ad_text=ad))


def test_의무_문구만_있는_문장은_Gemini_에_보내지_않는다(fake):
    response = run("A")
    assert "의약품이 아닙니다" not in fake["prompts"][0].split("# 검토할 문장")[1]
    assert (response.results[2].status, response.results[2].reason) == (Status.OK, "법정 의무 문구입니다.")


def test_문구_전체가_의무_문구면_Gemini_를_호출하지_않는다(fake):
    response = run("C", HF_PRODUCT, ad="본 제품은 질병의 예방 및 치료를 위한 의약품이 아닙니다.")
    assert fake["prompts"] == []
    assert [r.status for r in response.results] == [Status.OK]


def test_답이_빠진_문장은_검토_필요로_채운다(fake):
    fake["answers"].append(verdict("s1"))
    response = run("A")
    assert len(response.results) == len(response.sentences) == 3
    assert response.results[1].status is Status.REVIEW


def test_mode_A_는_사례를_찾지_않고_제품_정보를_쓰지_않는다(fake):
    response = run("A", HF_PRODUCT)
    assert fake["searches"] == []
    assert "이 제품의 정보" not in fake["prompts"][0]
    assert "# 참고 사례" not in fake["prompts"][0]
    assert response.kb_version == "none"


def test_mode_B_는_사례를_붙이고_제품_정보는_쓰지_않는다(fake):
    fake["answers"].append(verdict("s2", status="VIOLATION", step="DISEASE", evidence_ids=["mfds-2-01", "mfds-9-99"]))
    response = run("B", HF_PRODUCT)
    prompt = fake["prompts"][0]
    assert "(mfds-2-01) '감기예방'" in prompt and "# 참고 사례" in prompt
    assert "이 제품의 정보" not in prompt and "적용:" not in prompt.split("# 검토할 문장")[1]
    evidence = response.results[1].evidence
    assert [(e.id, e.url) for e in evidence] == [("mfds-2-01", "https://example.com/2")]
    assert response.kb_version == "2025-11-06"


def test_mode_C_건강기능식품은_인정_기능성과_사례_적용_여부를_쓴다(fake):
    fake["answers"].append(verdict("s1", matched_function="면역력 증진", evidence_ids=["mfds-1-01"]))
    response = run("C", HF_PRODUCT)
    prompt = fake["prompts"][0]
    assert "- 제품 종류: 건강기능식품" in prompt and "- 인정받은 기능성: 면역력 증진" in prompt
    assert "적용: 이 제품(건강기능식품)에는 적용되지 않음" in prompt
    result = response.results[0]
    assert result.status is Status.OK and result.evidence == []
    assert result.reason.endswith("(인정받은 기능성: 면역력 증진)")


def test_mode_C_일반식품은_건기식_오인으로_제3호가_붙는다(fake):
    fake["answers"].append(
        verdict("s1", status="VIOLATION", step="HEALTH_FOOD_CONFUSION", problem_text="면역력 증진에 도움",
                revision="홍삼의 깊은 맛", evidence_ids=["mfds-1-01"])
    )
    result = run("C", GENERAL_PRODUCT).results[0]
    assert (result.status, result.violation_type) == (Status.VIOLATION, "제8조 제1항 제3호")
    assert [e.id for e in result.evidence] == ["mfds-1-01"]
    assert AD[result.span.start : result.span.end] == "면역력 증진에 도움"


def test_mode_C_제품_정보가_없으면_판단_보류를_허용한다(fake):
    fake["answers"].extend(
        [
            verdict("s1", status="HOLD", revision="쓰면 안 되는 수정안", evidence_ids=["mfds-1-01"]),
            verdict("s2", status="VIOLATION", step="DISEASE"),
        ]
    )
    response = run("C")
    assert "- 제품 종류: 알 수 없음" in fake["prompts"][0]
    hold = response.results[0]
    assert (hold.status, hold.revision, hold.evidence) == (Status.HOLD, None, [])
    assert "제품 종류를 확인" in hold.reason
    assert response.results[1].status is Status.VIOLATION


def test_프롬프트에_자리표시가_남지_않는다(fake):
    for mode, product in [("A", None), ("B", None), ("C", HF_PRODUCT), ("C", None)]:
        run(mode, product)
    assert all("{{" not in prompt for prompt in fake["prompts"])