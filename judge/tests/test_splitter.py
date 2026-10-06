import pytest

from app.splitter import split_sentences


def texts(ad: str) -> list[str]:
    return [s.text for s in split_sentences(ad)]


def test_문장부호_뒤_공백에서_나눈다():
    sentences = split_sentences("환절기 감기 걱정 끝! 홍삼으로 면역력 증진에 도움을 줄 수 있습니다.")
    assert [(s.id, s.text, s.start, s.end) for s in sentences] == [
        ("s1", "환절기 감기 걱정 끝!", 0, 12),
        ("s2", "홍삼으로 면역력 증진에 도움을 줄 수 있습니다.", 13, 39),
    ]


def test_줄바꿈에서_나누고_빈_줄과_앞뒤_공백은_뺀다():
    assert texts("면역력 UP\n하루 한 포로 활력 충전\n\n  지금 주문하세요  ") == [
        "면역력 UP",
        "하루 한 포로 활력 충전",
        "지금 주문하세요",
    ]


def test_소수점과_No1_에서는_나누지_않는다():
    assert texts("하루 1.5배 더! 판매 No.1 제품입니다. 함량 3.5g.") == [
        "하루 1.5배 더!",
        "판매 No.1 제품입니다.",
        "함량 3.5g.",
    ]


def test_연속된_문장부호는_하나로_본다():
    assert texts("정말 효과가 있을까요?? 드셔보세요... 놀랍습니다!!") == [
        "정말 효과가 있을까요??",
        "드셔보세요...",
        "놀랍습니다!!",
    ]


def test_닫는_따옴표는_앞_문장에_붙는다():
    assert texts('고객 후기: "3일 만에 나았어요!" 라고 합니다.') == [
        '고객 후기: "3일 만에 나았어요!"',
        "라고 합니다.",
    ]


def test_문장부호가_없으면_한_문장이다():
    assert texts("홍삼 농축액 100%") == ["홍삼 농축액 100%"]


def test_공백만_있으면_문장이_없다():
    assert split_sentences("   \n  ") == []


@pytest.mark.parametrize(
    "ad",
    [
        "환절기 감기 걱정 끝! 홍삼으로 면역력 증진에 도움을 줄 수 있습니다.",
        "면역력 UP\n하루 한 포로 활력 충전\n\n  지금 주문하세요  ",
        "첫 줄입니다.\r\n둘째 줄",
        "🔥특가🔥 면역력 UP! 지금 구매",
        '고객 후기: "3일 만에 나았어요!" 라고 합니다.',
    ],
)
def test_위치는_항상_원문과_일치한다(ad):
    sentences = split_sentences(ad)
    assert sentences
    for s in sentences:
        assert ad[s.start : s.end] == s.text
    assert [s.id for s in sentences] == [f"s{i}" for i in range(1, len(sentences) + 1)]