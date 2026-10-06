"""정해 둔 문구 묶음을 실제 Gemini 로 판정해 보고, 기대한 판정과 비교한다.

사용법 (judge 폴더에서):
    python -m scripts.check_scenarios          # 전부
    python -m scripts.check_scenarios 건기식    # 이름에 '건기식'이 들어간 것만

기대 판정은 정답이 아니라 '이 정도면 타당하다'고 본 범위다. 어긋난 줄은 직접 보고 판단한다.
Gemini 를 시나리오마다 한 번씩 호출한다.
"""

import sys
import time

from app.llm import LlmError
from app.pipeline import run_judge
from app.retriever import RetrievalError
from app.schemas import JudgeRequest, JudgeResponse, Status

V, R, OK, H = Status.VIOLATION, Status.REVIEW, Status.OK, Status.HOLD

BASIC = (
    "환절기 감기 걱정 끝! 홍삼으로 면역력 증진에 도움을 줄 수 있습니다.\n"
    "본 제품은 질병의 예방 및 치료를 위한 의약품이 아닙니다.\n"
    "먹고 3일 만에 달라졌어요! 부작용 없는 100% 천연\n"
    "하루 한 포로 활력 충전"
)
PRODUCT_AD = "홍삼으로 면역력 증진에 도움을 줄 수 있습니다.\n환절기 감기 걱정 끝!\n집중력 향상에 좋아요\n배송비 무료"
HF = {"type": "HEALTH_FUNCTIONAL", "names": ["6년근 고려홍삼정"], "approved_functions": ["면역력 증진", "피로개선"]}
GENERAL = {"type": "GENERAL_FOOD", "names": ["우리 홍삼차"]}
LABELED = {"type": "FUNCTIONAL_LABELED_FOOD", "names": ["기능성 표시 홍삼차"]}

# (이름, mode, 제품 정보, 광고 문구, 문장별로 받아들일 수 있는 판정)
SCENARIOS = [
    ("A 기본", "A", None, BASIC, [{V}, {R}, {OK}, {V, R}, {V, R}, {OK}]),
    ("A 질병 단어가 있지만 위반이 아닌 문장", "A", None,
     "감기 조심하세요, 고객님!\n당뇨 환자는 섭취 전 의사와 상담하세요.\n탈모 샴푸와 함께 구매하면 배송비 무료",
     [{OK}, {OK}, {OK}]),
    ("A 의무 문구와 위반이 섞인 문구", "A", None,
     "본 제품은 질병의 예방 및 치료를 위한 의약품이 아닙니다.\n"
     "본 제품은 건강기능식품이 아닙니다. 하지만 고혈압 치료에 효과가 있습니다.",
     [{OK}, {OK}, {V}]),
    ("A 비교·과장·추천", "A", None,
     "타사 제품보다 흡수율 3배\n의사들이 추천하는 바로 그 제품\n다이어트 중에도 맛있게",
     [{V, R}, {V, R}, {OK}]),
    ("A 이모지와 문장부호 없는 문구", "A", None, "🔥특가🔥 면역력 UP! 지금 구매\n★★★★★ 재구매율 1위", [{V, R, OK}] * 3),
    ("B 사례 검색", "B", None,
     "환절기 감기 걱정 끝!\n아이 키 쑥쑥 크는 성장 영양제\n감기 조심하세요, 고객님!\n몸속 독소를 싹 빼주는 해독 주스\n배송비 무료",
     [{V}, {R}, {OK}, {V, R}, {OK}]),
    ("C 건기식", "C", HF, PRODUCT_AD, [{OK}, {V}, {V}, {OK}]),
    ("C 건기식: 인정받은 기능성이지만 과장", "C", HF,
     "면역력 증진 효과 100% 보장\n피로개선에 도움을 줄 수 있습니다", [{V, R}, {OK}]),
    ("C 일반식품", "C", GENERAL, PRODUCT_AD, [{V}, {V}, {V}, {OK}]),
    ("C 기능성 표시 일반식품", "C", LABELED, PRODUCT_AD, [{R}, {V}, {R}, {OK}]),
    ("C 제품 정보 없음", "C", None, PRODUCT_AD, [{H}, {V}, {H}, {OK}]),
]


def broken_rules(ad: str, mode: str, response: JudgeResponse) -> list[str]:
    """판정 내용과 상관없이 항상 지켜져야 하는 형식 규칙을 확인한다."""
    problems = []
    if len(response.results) != len(response.sentences):
        problems.append("문장 수와 결과 수가 다름")
    for sentence, result in zip(response.sentences, response.results):
        name = sentence.id
        if ad[sentence.start : sentence.end] != sentence.text:
            problems.append(f"{name}: 문장 위치가 원문과 다름")
        if not (sentence.start <= result.span.start < result.span.end <= sentence.end):
            problems.append(f"{name}: 문제 구간이 문장 밖")
        if (result.status is V) != (result.violation_type is not None):
            problems.append(f"{name}: 위반 판정과 조항 번호가 맞지 않음")
        if result.status in (OK, H) and result.revision:
            problems.append(f"{name}: {result.status.value} 인데 수정안이 있음")
        if result.status is OK and result.evidence:
            problems.append(f"{name}: OK 인데 근거 사례가 있음")
        if mode == "A" and result.evidence:
            problems.append(f"{name}: mode A 인데 근거 사례가 있음")
        if any(not e.url for e in result.evidence):
            problems.append(f"{name}: 출처 주소가 없는 근거 사례")
    return problems


def main() -> None:
    keyword = sys.argv[1] if len(sys.argv) > 1 else ""
    chosen = [s for s in SCENARIOS if keyword in s[0]]
    off, rule_breaks, failed = 0, 0, 0
    for index, (name, mode, product, ad, expected) in enumerate(chosen):
        if index:
            time.sleep(4)  # 무료 구간의 분당 호출 한도를 넘지 않게
        print(f"\n■ {name}  (mode {mode})")
        try:
            response = run_judge(JudgeRequest(request_id=f"check-{index + 1}", mode=mode, product=product, ad_text=ad))
        except (LlmError, RetrievalError) as e:
            failed += 1
            print(f"   실행 실패: {e}")
            continue
        if len(expected) != len(response.results):
            print(f"   ! 기대한 문장 수({len(expected)})와 실제({len(response.results)})가 다름")
        for i, result in enumerate(response.results):
            allowed = expected[i] if i < len(expected) else set(Status)
            good = result.status in allowed
            off += not good
            want = "/".join(s.value for s in sorted(allowed, key=lambda s: s.value))
            extra = f" {result.violation_type}" if result.violation_type else ""
            cases = f" 근거 {len(result.evidence)}건" if result.evidence else ""
            print(f"   {'○' if good else '✗'} {result.status.value:<9}{extra}{cases} | {result.text[:34]}")
            if not good:
                print(f"       기대: {want} | 이유: {result.reason}")
        for problem in broken_rules(ad, mode, response):
            rule_breaks += 1
            print(f"   !! 형식 규칙 위반: {problem}")
        print(f"   ({response.elapsed_ms}ms)")
    print(f"\n시나리오 {len(chosen)}개 | 기대와 다른 판정 {off}건 | 형식 규칙 위반 {rule_breaks}건 | 실행 실패 {failed}건")
    if rule_breaks or failed:
        sys.exit(1)


if __name__ == "__main__":
    main()