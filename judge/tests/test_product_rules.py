from datetime import date

import pytest

from app.product_rules import case_applicability
from app.retriever import CaseHit
from app.schemas import ProductType


def hit(violation_type: str, product_type: str = "일반식품") -> CaseHit:
    return CaseHit(
        id="mfds-0-01",
        phrase="면역력 증진",
        violation_type=violation_type,
        article="",
        product_type=product_type,
        case_date=date(2025, 1, 1),
        source_title="",
        source_url="https://example.com",
        score=0.7,
    )


@pytest.mark.parametrize(
    "product_type, usable",
    [
        (ProductType.GENERAL_FOOD, True),
        (ProductType.HEALTH_FUNCTIONAL, False),
        (ProductType.FUNCTIONAL_LABELED_FOOD, False),
        (ProductType.UNKNOWN, False),
    ],
)
def test_건기식_오인_사례는_일반식품에만_근거로_쓴다(product_type, usable):
    assert case_applicability(hit("건강기능식품 오인·혼동"), product_type)[0] is usable


@pytest.mark.parametrize("violation_type", ["질병 예방·치료 효능", "의약품 오인·혼동", "소비자 기만", "거짓·과장"])
@pytest.mark.parametrize("product_type", list(ProductType))
def test_그_밖의_사례는_제품_종류와_관계없이_쓸_수_있다(violation_type, product_type):
    assert case_applicability(hit(violation_type), product_type)[0] is True


def test_설명에_제품_종류가_들어간다():
    assert "건강기능식품" in case_applicability(hit("건강기능식품 오인·혼동"), ProductType.HEALTH_FUNCTIONAL)[1]
    assert "일반식품" in case_applicability(hit("건강기능식품 오인·혼동"), ProductType.GENERAL_FOOD)[1]