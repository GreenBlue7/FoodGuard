"""제품 종류에 따라 코드가 정하는 규칙 (AI 에게 맡기지 않는 부분)."""

from app.llm_schema import Step
from app.retriever import CaseHit
from app.schemas import ProductType

PRODUCT_LABEL = {
    ProductType.HEALTH_FUNCTIONAL: "건강기능식품",
    ProductType.FUNCTIONAL_LABELED_FOOD: "기능성 표시 일반식품",
    ProductType.GENERAL_FOOD: "일반식품",
    ProductType.UNKNOWN: "알 수 없음",
}

# 이 단계는 해당 제품 종류일 때만 쓸 수 있다
STEP_REQUIRES = {
    Step.HEALTH_FOOD_CONFUSION: ProductType.GENERAL_FOOD,
    Step.UNAPPROVED_FUNCTION: ProductType.HEALTH_FUNCTIONAL,
}

_HEALTH_FOOD_CASE = "건강기능식품 오인·혼동"
_EXAGGERATION_CASE = "거짓·과장"


def case_applicability(hit: CaseHit, product_type: ProductType) -> tuple[bool, str]:
    """검색된 사례를 이 제품의 근거로 쓸 수 있는지와, 프롬프트에 적을 설명을 돌려준다."""
    label = PRODUCT_LABEL[product_type]
    if hit.violation_type == _HEALTH_FOOD_CASE:
        # 일반식품이 기능성을 내세워 적발된 사례
        if product_type is ProductType.GENERAL_FOOD:
            return True, f"이 제품({label})에 해당하는 사례"
        if product_type is ProductType.HEALTH_FUNCTIONAL:
            return False, f"이 제품({label})에는 적용되지 않음 (일반식품에서 문제가 된 사례)"
        return False, "제품 종류가 확인되지 않아 적용 여부를 알 수 없음"
    if hit.violation_type == _EXAGGERATION_CASE and product_type is ProductType.HEALTH_FUNCTIONAL:
        return True, "이 제품이 같은 기능성을 인정받았다면 적용되지 않음"
    return True, "제품 종류와 관계없이 참고할 수 있음"