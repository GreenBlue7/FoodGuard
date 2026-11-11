"""사실 대조: 광고 속 숫자·성분 주장을 식약처 등록 정보와 같은 페이지의 표시사항에 맞춰 본다.

판정 뒤에 따로 한 번 부른다. 분명히 어긋나고, 근거로 든 글이 등록 정보나 페이지에 실제로 있을 때만 위반으로 올린다.
"""

import re
from pathlib import Path

from app.llm import generate_json
from app.llm_schema import FactOutput, FactVerdict
from app.schemas import JudgeResult, Product, Sentence, Status

_PROMPT = (Path(__file__).parent / "prompts" / "service" / "fact_check_v1.md").read_text(encoding="utf-8")

# 사실 확인이 필요한 강조 주장: 비율·개수, 넣지 않음, 오직·단일·순수, 비타민·미네랄
# 단순 함량 숫자(mg, g)는 대부분 영양정보 표의 일부라서 보지 않는다 (글자 인식이 깨진 표 조각을 주장으로 잘못 봄)
_CLAIM = re.compile(
    r"\d+\s*(?:%|％|종|가지)|FREE|ZERO|프리|무첨가|무가당|첨가하지|넣지\s*않|배제|충족|오직|단일|순수|순도|미네랄",
    re.IGNORECASE,
)
_HANGUL = re.compile(r"[가-힣]")

# 1일 영양성분 기준치 (「식품등의 표시기준」). 등록 표시량을 이 값으로 나눈 비율을 코드가 계산해서 넘긴다
_DAILY_VALUE = {
    "비타민a": (700, "μg"), "비타민d": (10, "μg"), "비타민e": (11, "mg"), "비타민k": (70, "μg"),
    "비타민c": (100, "mg"), "비타민b1": (1.2, "mg"), "티아민": (1.2, "mg"), "비타민b2": (1.4, "mg"),
    "리보플라빈": (1.4, "mg"), "나이아신": (15, "mg"), "비타민b6": (1.5, "mg"), "엽산": (400, "μg"),
    "비타민b12": (2.4, "μg"), "판토텐산": (5, "mg"), "비오틴": (30, "μg"), "칼슘": (700, "mg"),
    "마그네슘": (315, "mg"), "철": (12, "mg"), "아연": (8.5, "mg"), "구리": (0.8, "mg"),
    "셀렌": (55, "μg"), "셀레늄": (55, "μg"), "요오드": (150, "μg"), "망간": (3, "mg"),
    "몰리브덴": (25, "μg"), "크롬": (30, "μg"),
}
_STANDARD = re.compile(r"([가-힣A-Za-z0-9 ]+?)\s*[:：]\s*표[시기]량\s*\(\s*([\d.,]+)\s*(mg|㎎|μg|ug|㎍|g)")
_TO_MG = {"mg": 1, "㎎": 1, "μg": 0.001, "ug": 0.001, "㎍": 0.001, "g": 1000}


def _daily_value_lines(standards: str) -> list[str]:
    """기준규격의 표시량을 1일 영양성분 기준치와 비교한 비율 (보통 표시량은 1일 섭취량 기준)."""
    lines = []
    for name, amount, unit in _STANDARD.findall(standards):
        key = re.sub(r"\s+", "", name).lower()
        if key not in _DAILY_VALUE:
            continue
        reference, ref_unit = _DAILY_VALUE[key]
        mg = float(amount.replace(",", "")) * _TO_MG[unit]
        ratio = mg / (reference * _TO_MG[ref_unit]) * 100
        lines.append(f"{name.strip()}: 표시량 {amount}{unit} = 1일 영양성분 기준치({reference}{ref_unit})의 {ratio:.0f}%")
    return lines


def _is_table_fragment(text: str) -> bool:
    # 한글이 거의 없고 숫자가 많으면 영양정보 표 조각으로 본다
    digits = sum(ch.isdigit() for ch in text)
    return len(_HANGUL.findall(text)) < 4 or digits / max(len(text), 1) > 0.3


_VIOLATION_TYPE = "제8조 제1항 제4호"  # 별표 1 제4호 다목: 표시사항을 사실과 다르게 표현
MAX_CLAIMS = 60  # 한 번에 확인할 문장 수의 상한


def _squash(text: str) -> str:
    return re.sub(r"[\W_]+", "", text).lower()


def _registry_block(product: Product) -> str:
    lines = [f"- 제품명: {', '.join(product.names) or '없음'}"]
    if product.ingredients:
        lines.append(f"- 기능성 원료: {', '.join(product.ingredients)}")
    if product.other_ingredients:
        lines.append(f"- 그 밖의 원재료: {', '.join(product.other_ingredients)}")
    if product.standards:
        lines.append(f"- 기준규격(표시량): {product.standards.replace(chr(10), ' / ')}")
        ratios = _daily_value_lines(product.standards)
        if ratios:
            lines.append(f"- 표시량의 1일 영양성분 기준치 대비 비율 (코드 계산): {' / '.join(ratios)}")
    if product.function_source:
        lines.append(f"- 기능성 원문: {product.function_source.replace(chr(10), ' / ')}")
    return "\n".join(lines)


_MINERALS = ("칼슘", "마그네슘", "철", "아연", "구리", "셀렌", "셀레늄", "요오드", "망간", "몰리브덴", "크롬", "칼륨")
_VITAMINS = ("비타민", "나이아신", "판토텐산", "비오틴", "엽산", "티아민", "리보플라빈")
_ALL_HUNDRED = re.compile(r"(\d+)\s*종\s*100\s*%|100\s*%\s*\*?\s*(?:충족|채워|채운)")
_VITAMIN_MINERAL_COUNT = re.compile(r"(\d+)\s*(?:가지|종)\s*비타민\s*[·ㆍ,/&+\s]*\s*미네랄")


def _ratios(standards: str) -> dict[str, tuple[str, float]]:
    """기준규격 표시량을 1일 영양성분 기준치로 나눈 비율. 키는 띄어쓰기를 뺀 소문자 이름."""
    found = {}
    for name, amount, unit in _STANDARD.findall(standards):
        key = re.sub(r"\s+", "", name).lower()
        if key in _DAILY_VALUE:
            reference, ref_unit = _DAILY_VALUE[key]
            mg = float(amount.replace(",", "")) * _TO_MG[unit]
            found[key] = (name.strip(), mg / (reference * _TO_MG[ref_unit]) * 100)
    return found


def _kind(ingredient: str) -> str | None:
    name = re.sub(r"\s+", "", ingredient)
    if name.startswith(_VITAMINS):
        return "비타민"
    if any(name.startswith(mineral) for mineral in _MINERALS):
        return "미네랄"
    return None


def _rule_check(text: str, product: Product) -> str | None:
    """코드로 계산해서 분명히 어긋나는 주장이면 이유를 돌려준다. AI 를 쓰지 않아 매번 같은 결과가 나온다."""
    ratios = _ratios(product.standards)
    hundred = _ALL_HUNDRED.search(text)
    if hundred and ratios:
        # "N종 100%", "100% 충족": 기준치가 있는 영양성분이 모두 100% 이상이어야 한다
        short = [f"{name} {ratio:.0f}%" for name, ratio in ratios.values() if ratio < 99.5]
        if short:
            return f"1일 영양성분 기준치 대비 100%에 못 미치는 성분이 있습니다: {', '.join(short)} (등록 표시량 기준 계산)"
        if hundred.group(1) and int(hundred.group(1)) > len(ratios):
            return f"기준치가 정해진 영양성분은 {len(ratios)}종뿐입니다 (등록 표시량 기준)"
    count = _VITAMIN_MINERAL_COUNT.search(text)
    if count and product.ingredients:
        kinds = [_kind(i) for i in product.ingredients]
        vitamins, minerals = kinds.count("비타민"), kinds.count("미네랄")
        if minerals == 0:
            return f"등록된 기능성 원료에 미네랄이 없습니다 (비타민 {vitamins}종)"
        if vitamins + minerals != int(count.group(1)):
            return f"등록된 기능성 원료의 비타민·미네랄은 {vitamins + minerals}종입니다 (비타민 {vitamins}, 미네랄 {minerals})"
    return None


def check_facts(
    sentences: list[Sentence], results: dict[str, JudgeResult], product: Product, ad_text: str, model: str | None = None
) -> list[str]:
    """코드 계산으로 어긋나는 주장은 위반으로, AI 가 어긋난다고 본 애매한 주장은 검토 필요로 올린다. 바꾼 문장 번호를 돌려준다."""
    if not (product.ingredients or product.other_ingredients or product.standards):
        return []  # 등록 정보가 없으면 대조할 근거가 없다
    changed = []
    for sentence in sentences:
        if results[sentence.id].status is Status.VIOLATION or _is_table_fragment(sentence.text):
            continue
        reason = _rule_check(sentence.text, product)
        if reason:
            results[sentence.id] = results[sentence.id].model_copy(
                update={
                    "status": Status.VIOLATION,
                    "violation_type": _VIOLATION_TYPE,
                    "reason": f"표시사항이나 등록 정보와 다릅니다. {reason}",
                }
            )
            changed.append(sentence.id)
    claims = [
        s for s in sentences
        if _CLAIM.search(s.text) and not _is_table_fragment(s.text) and results[s.id].status is Status.OK
    ]
    if not claims:
        return changed
    claims = claims[:MAX_CLAIMS]
    prompt = (
        _PROMPT.replace("{{REGISTRY}}", _registry_block(product))
        .replace("{{PAGE}}", ad_text)
        .replace("{{CLAIMS}}", "\n".join(f"[{s.id}] {s.text}" for s in claims))
    )
    output = generate_json(prompt, FactOutput, model=model)
    sources = _squash(_registry_block(product) + ad_text)
    by_id = {s.id: s for s in claims}
    for item in output.items:
        sentence = by_id.get(item.sentence_id)
        if sentence is None or item.verdict is not FactVerdict.CONTRADICT:
            continue
        evidence = _squash(item.evidence)
        if len(evidence) < 2 or evidence not in sources:
            continue  # 근거로 든 글이 등록 정보나 페이지에 없으면 지어낸 것일 수 있다
        old = results[sentence.id]
        span_start = sentence.text.find(item.claim) if item.claim else -1
        # AI 가 찾은 어긋남은 해석이 갈릴 수 있어 위반으로 단정하지 않는다
        update = {
            "status": Status.REVIEW,
            "reason": f"표시사항이나 등록 정보와 다를 수 있습니다. 확인해 주세요. {item.reason} (근거: {item.evidence})",
        }
        if span_start >= 0:
            update["span"] = old.span.model_copy(
                update={"start": sentence.start + span_start, "end": sentence.start + span_start + len(item.claim)}
            )
        results[sentence.id] = old.model_copy(update=update)
        changed.append(sentence.id)
    return changed
