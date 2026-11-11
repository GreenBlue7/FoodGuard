"""공식 기준 중 코드로 확인할 수 있는 것을 페이지 전체를 보고 적용한다. AI 를 부르지 않아 매번 같은 결과가 나온다.

근거
- 시행령 별표 1 제1호 라목 2): 질병정보는 "해당 질병정보는 제품과 직접적인 관련이 없습니다"를 함께 적어야 한다
- 2025 건강기능식품 표시·광고 공통심의기준 Ⅲ-2-가-(5): 인체시험 결과에는 "인체시험결과가 모든 사람에게 동일하게 적용되는 것은 아님"
- 같은 기준 Ⅳ-4: 체지방 감소 제품은 "적절한 운동, 식이조절과 함께 섭취"를 명시
- 식약처 고시 「식품등의 부당한 표시 또는 광고의 내용 기준」 제2조제3호카목·제4호나목: 다른 물질이 있는데 "100%", 조사 정보 없는 "1위"
- 식약처 고시 「일반식품 기능성 표시 규정」 제6조제3항: 기능성 광고에는 "본 제품은 건강기능식품이 아닙니다"
"""

import re

from app.schemas import JudgeResult, Product, ProductType, Sentence, Span, Status
from app.tagger import TagType, tag_sentence
from app.verifier import is_approved_wording

_DISEASE_NOTICE = re.compile(r"제품(?:정보)?\s*(?:과|와)\s*(?:는\s*)?(?:직접\s*(?:적인|적으로)?\s*)?관련(?:이|성이|성)?\s*없")
_TRIAL = re.compile(r"인체\s*적용\s*시험|인체\s*시험|임상\s*시험|섭취군|대조군|위약군|플라시보")
_TRIAL_NOTICE = re.compile(r"모든\s*사람에게\s*동일하게\s*적용")
_DIET = re.compile(r"체지방|다이어트")
_DIET_NOTICE = re.compile(r"운동")
_DIET_NOTICE2 = re.compile(r"식이\s*조절|식단|식사\s*조절|식습관")
_NOT_HFF = re.compile(r"건강\s*기능\s*식품이\s*아닙니다")
_SINGLE = re.compile(r"단일\s*(?:성분|원료)|오직\s*[가-힣A-Za-z0-9 ]{1,12}만|순수(?:하게)?\s*[가-힣A-Za-z0-9 ]{1,12}만\s*(?:담|넣|사용)")
_RANK = re.compile(r"(?<![\d.])1\s*위|No\.?\s*1\b|넘버\s*원|NO\.?\s*1\b", re.IGNORECASE)
_RANK_SOURCE = re.compile(r"조사|기관|기준|출처|집계|리서치|닐슨|칸타|\d{4}\s*(?:년|\.)|\d{1,2}\s*월|소비자\s*만족|대상|어워드|수상|선정")

_LURE = re.compile(r"한방(?!울)|韓方|특수\s*제법|주문\s*쇄도|단체\s*추천")
_DECEIVE_LAW = "제8조 제1항 제5호"
_DISEASE_LAW = "제8조 제1항 제1호"
_FALSE_LAW = "제8조 제1항 제4호"
_COMPARE_LAW = "제8조 제1항 제7호"
_CONFUSION_LAW = "제8조 제1항 제3호"


def _has_disease_word(text: str) -> bool:
    sentence = Sentence(id="tmp", text=text, start=0, end=len(text))
    return any(t.type is TagType.DISEASE for t in tag_sentence(sentence))


def _set(results: dict[str, JudgeResult], sentence: Sentence, status: Status, reason: str, law: str | None = None,
         revision: str | None = None) -> None:
    old = results[sentence.id]
    update = {"status": status, "reason": reason}
    if status is Status.VIOLATION:
        update["violation_type"] = law
    if revision:
        update["revision"] = revision
    if old.status is Status.OK:
        update["span"] = Span(start=sentence.start, end=sentence.end)
    results[sentence.id] = old.model_copy(update=update)


def apply_page_rules(sentences: list[Sentence], results: dict[str, JudgeResult], product: Product, ad_text: str) -> list[str]:
    """바꾼 문장 번호를 돌려준다. 위반은 그대로 두고, 정상·검토 필요만 바꾼다."""
    changed: list[str] = []
    approved = product.approved_functions

    # 1. 질병정보 안내 문구 (법령). 질병 단어가 있어 검토 필요로 남은 문장이 대상
    if not _DISEASE_NOTICE.search(ad_text):
        for s in sentences:
            r = results[s.id]
            if r.status is Status.REVIEW and _has_disease_word(s.text) and not is_approved_wording(s.text, approved):
                _set(results, s, Status.VIOLATION,
                     "질병 정보를 쓰면서 \"해당 질병정보는 제품과 직접적인 관련이 없습니다\"라는 안내가 페이지에 없습니다. "
                     "질병정보로 질병과의 연관성을 암시하는 광고로 볼 수 있습니다(시행령 별표 1 제1호 라목). " + r.reason,
                     _DISEASE_LAW)
                changed.append(s.id)

    # 2. 인체시험 결과 안내 문구 (건강기능식품 심의기준)
    if product.type is ProductType.HEALTH_FUNCTIONAL and not _TRIAL_NOTICE.search(ad_text):
        trial = [s for s in sentences if _TRIAL.search(s.text) and results[s.id].status is not Status.VIOLATION]
        if trial:
            s = trial[0]
            _set(results, s, Status.REVIEW,
                 "인체시험 결과를 쓰면 \"기능성 원료인 ○○의 인체시험결과\"와 \"인체시험결과가 모든 사람에게 동일하게 적용되는 것은 아님\"을 "
                 "함께 적어야 하는데 페이지에서 찾지 못했습니다(2025 공통심의기준 Ⅲ-2-가-(5)).")
            changed.append(s.id)

    # 3. 체지방 감소 제품의 운동·식이조절 안내 (건강기능식품 심의기준)
    if (
        product.type is ProductType.HEALTH_FUNCTIONAL
        and any("체지방" in f for f in approved)
        and not (_DIET_NOTICE.search(ad_text) and _DIET_NOTICE2.search(ad_text))
    ):
        diet = [s for s in sentences if _DIET.search(s.text) and results[s.id].status is Status.OK]
        if diet:
            s = diet[0]
            _set(results, s, Status.REVIEW,
                 "체지방 감소 제품 광고에는 적절한 운동, 식이조절과 함께 섭취해야 한다는 내용을 적어야 하는데 페이지에서 찾지 못했습니다"
                 "(2025 공통심의기준 Ⅳ-4).")
            changed.append(s.id)

    # 4. 기능성 표시 일반식품의 "건강기능식품이 아닙니다" (식약처 고시)
    if product.type is ProductType.FUNCTIONAL_LABELED_FOOD and not _NOT_HFF.search(ad_text):
        claims = [s for s in sentences if results[s.id].status is Status.OK and "인정받은 기능성:" in results[s.id].reason]
        if claims:
            s = claims[0]
            _set(results, s, Status.VIOLATION,
                 "기능성을 광고하는 일반식품에는 \"본 제품은 건강기능식품이 아닙니다.\"라는 문구가 있어야 하는데 페이지에서 찾지 못했습니다"
                 "(일반식품 기능성 표시 규정 제6조제3항). 건강기능식품으로 오인될 수 있습니다.",
                 _CONFUSION_LAW)
            changed.append(s.id)

    # 5. 등록 원재료와 다른 "단일·오직" (식약처 고시)
    # 기능성 원료의 짧은 이름 (예: "밀크씨슬(카르두스 마리아누스) 추출물" → "밀크씨슬")
    names = {re.split(r"[\s(（]", i.strip())[0] for i in product.ingredients if i.strip()}
    names = {n for n in names if len(n) >= 2}
    others = [o for o in product.other_ingredients if o.strip() and not any(n in o for n in names)]
    if others and names:
        for s in sentences:
            m = _SINGLE.search(s.text)
            # "오직 건강만을 생각하여"처럼 원료를 말하지 않는 '오직'은 뺀다
            if not m or not (m.group(0).startswith("단일") or any(n in m.group(0) for n in names)):
                continue
            if results[s.id].status is not Status.VIOLATION:
                _set(results, s, Status.VIOLATION,
                     f"등록된 원재료에 기능성 원료 외 다른 원재료({', '.join(others[:3])}{' 등' if len(others) > 3 else ''})가 있어 "
                     "'단일·오직'이라는 표현은 사실과 다릅니다(부당 표시·광고 고시 제2조제3호카목, 시행령 별표 1 제4호 다목).",
                     _FALSE_LAW)
                changed.append(s.id)

    # 7. 시행령에 이름이 적힌 현혹 표현 (시행령 별표 1 제5호 다목)
    for s in sentences:
        m = _LURE.search(s.text)
        if m and results[s.id].status is not Status.VIOLATION:
            _set(results, s, Status.VIOLATION,
                 f"'{m.group(0)}'은(는) 소비자를 현혹하는 표현으로 시행령에 명시되어 있습니다(시행령 별표 1 제5호 다목).",
                 _DECEIVE_LAW)
            changed.append(s.id)

    # 6. 조사 정보 없는 "1위" (식약처 고시)
    for i, s in enumerate(sentences):
        if results[s.id].status is Status.VIOLATION or not _RANK.search(s.text):
            continue
        if re.search(r"\d\s*%", s.text):
            continue  # "1위 눈건강 38.8%" 처럼 설문·통계 자료의 순위는 제품 순위가 아니다
        window = sentences[max(0, i - 1): i + 2]
        near = " ".join(x.text for x in window)
        if _RANK_SOURCE.search(near):
            continue
        if any("*" in x.text for x in window) and any(
            x.text.lstrip().startswith(("*", "※")) and _RANK_SOURCE.search(x.text) for x in sentences
        ):
            continue  # "1위*" 처럼 별표를 달고 페이지 아래 각주에 조사 정보를 적은 경우
        _set(results, s, Status.VIOLATION,
             "조사대상·조사기관·기간을 밝히지 않은 순위 표현입니다(부당 표시·광고 고시 제2조제4호나목).",
             _COMPARE_LAW)
        changed.append(s.id)

    return changed
