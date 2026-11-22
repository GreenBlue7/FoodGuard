"""이미지 검사를 Gemini·DB 없이 확인한다.

글자 읽기(ocr.read_piece)와 판정(run_judge)을 가짜로 바꿔 끼우고, 자르기·합치기·거르기·묶기·페이지·박스·API 를 본다.
가짜 읽기는 '정답 줄'(원본 좌표)을 조각 좌표(0~1000)로 바꿔 돌려준다. 조각 경계에 걸친 줄은 잘린 글자로 돌려준다.
"""

import base64
import io

import pytest
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw

import app.image_judge as image_judge
import app.ocr as ocr
import app.text_locate as text_locate
from app.box_fit import fit_box
from app.image_pages import COLORS, CUT_MARGIN, Mark, PageCut, pad_box, page_ranges, render_page
from app.image_schemas import ImageJudgeRequest
from app.llm import LlmError
from app.main import app
from app.ocr import Line, OcrLine, OcrOutput
from app.schemas import JudgeResponse, JudgeResult, Span, Status
from app.splitter import split_sentences
from app.text_locate import OcrBox, locate, locate_line

WIDTH, HEIGHT = 500, 2000
LEFT, RIGHT = 50, 450  # 회색 내용 영역. 좌우는 흰 여백
CONTENT = RIGHT - LEFT

# (왼쪽 x, 위 y, 오른쪽 x, 아래 y, 글자) — 원본 이미지 좌표
TRUTH = [
    (60, 100, 400, 124, "면역력 증진에 도움을 줄 수"),
    (60, 130, 380, 154, "있는 홍삼 농축액입니다."),
    (60, 300, 420, 330, "환절기 감기 걱정 끝!"),
    (60, 590, 400, 614, "매일 아침 한 포로 간편하게"),  # 두 번째 조각(600~)에서는 위가 잘린다
    (60, 650, 400, 674, "6년근 홍삼만 담았습니다."),  # 첫 조각과 두 번째 조각에 모두 온전히 들어간다
    (60, 1500, 300, 1520, "영양정보 총 내용량 30g"),
    (60, 1600, 200, 1604, "작은 포장 글자"),
    (60, 1800, 420, 1830, "피로 개선에 좋은 홍삼"),
]


def make_image(height: int = HEIGHT) -> Image.Image:
    image = Image.new("RGB", (WIDTH, height), "white")
    draw = ImageDraw.Draw(image)
    draw.rectangle([LEFT, 0, RIGHT - 1, height], fill=(230, 230, 230))
    return image


def png_bytes(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


class FakeReader:
    """read_piece 대신 쓴다. 이미지별 정답 줄을 받아, 넘어온 조각 위치(이미지 번호, 위 y)로 답한다.

    조각을 동시에 읽어 부르는 순서가 매번 달라도 같은 조각에는 같은 답을 준다.
    """

    def __init__(self, truths: list[list[tuple]], heights: list[int], fail: set[tuple[int, int]] = frozenset()):
        piece_height = int(CONTENT * ocr.PIECE_RATIO)
        self.truths = truths
        self.tops = [ocr.piece_tops(height, piece_height) for height in heights]
        self.fail = fail  # (이미지 번호, 조각 번호) 를 넣으면 그 조각은 끝내 실패한다
        self.asked: list[tuple[int, int]] = []  # 불린 조각 (이미지 번호, 위 y)

    def __call__(self, piece: Image.Image, image_index: int, top: int) -> list[OcrLine]:
        self.asked.append((image_index, top))
        truth = self.truths[image_index]
        if (image_index, self.tops[image_index].index(top)) in self.fail:
            raise LlmError("가짜 실패")
        lines = []
        bottom = top + piece.height
        for x1, y1, x2, y2, text in truth:
            if y2 <= top or y1 >= bottom:
                continue
            cut = y1 < top or y2 > bottom
            c1, c2 = max(y1, top), min(y2, bottom)
            lines.append(
                OcrLine(
                    text=text[: len(text) // 2] if cut else text,  # 잘린 줄은 앞 절반만 읽힌 것으로
                    box_2d=[
                        round((c1 - top) * 1000 / piece.height),
                        round((x1 - LEFT) * 1000 / CONTENT),
                        round((c2 - top) * 1000 / piece.height),
                        round((x2 - LEFT) * 1000 / CONTENT),
                    ],
                )
            )
        return lines


def fake_run_judge(request):
    """'감기' 가 든 문장만 위반, 나머지는 정상으로 답하는 가짜 판정."""
    sentences = split_sentences(request.ad_text)
    results = []
    for s in sentences:
        if "감기" in s.text:
            start = s.start + s.text.find("감기 걱정 끝")
            results.append(
                JudgeResult(sentence_ids=[s.id], text=s.text, status=Status.VIOLATION, violation_type="제8조 제1항 제1호",
                            reason="질병 예방 표현", span=Span(start=start, end=start + len("감기 걱정 끝")))
            )
        else:
            results.append(JudgeResult(sentence_ids=[s.id], text=s.text, status=Status.OK, reason="정상",
                                       span=Span(start=s.start, end=s.end)))
    return JudgeResponse(sentences=sentences, results=results, model="가짜", kb_version="2025-12-19", elapsed_ms=1)


@pytest.fixture
def fake(monkeypatch):
    def install(truths=(TRUTH,), heights=(HEIGHT,), fail=frozenset(), positions=None):
        reader = FakeReader(list(truths), list(heights), fail)
        monkeypatch.setattr(ocr, "read_piece", reader)
        monkeypatch.setattr(image_judge, "run_judge", fake_run_judge)
        # 로컬 OCR 대신: 이미지 순서대로 positions 를 돌려준다 (기본은 아무것도 못 찾음 → box_fit·Gemini 박스)
        answers = iter(positions or [[] for _ in truths])
        monkeypatch.setattr(text_locate, "read_positions", lambda image, left, right: next(answers))
        return reader

    return install


def request() -> ImageJudgeRequest:
    return ImageJudgeRequest(request_id="t", mode="C", product={"type": "HEALTH_FUNCTIONAL", "names": ["홍삼"]})


# ---------------------------------------------------------------- 조각내고 읽기


def test_pieces_cover_whole_image_and_overlap():
    tops = ocr.piece_tops(2000, 720)
    assert tops[0] == 0 and tops[-1] + 720 == 2000
    assert all(b - a < 720 for a, b in zip(tops, tops[1:]))  # 이웃 조각이 겹친다


def test_short_image_is_one_piece():
    assert ocr.piece_tops(500, 720) == [0]


def test_content_range_ignores_side_margins():
    assert ocr.content_range(make_image()) == (LEFT, RIGHT)


def test_read_image_merges_overlap_and_keeps_uncut_reading(fake):
    fake()
    reading = ocr.read_image(make_image())
    texts = [line.text for line in reading.lines]
    assert texts.count("6년근 홍삼만 담았습니다.") == 1  # 두 조각에서 읽혀도 한 번만
    assert "매일 아침 한 포로 간편하게" in texts  # 잘린 쪽이 아니라 온전한 쪽을 남긴다
    assert "매일 아침 한" not in texts
    assert len(reading.lines) == len(TRUTH)
    assert reading.unread == [] and reading.failed_pieces == 0


def test_boxes_come_back_in_original_coordinates(fake):
    fake()
    line = next(l for l in ocr.read_image(make_image()).lines if l.text == "환절기 감기 걱정 끝!")
    assert line.box == pytest.approx([60, 300, 420, 330], abs=2)


def test_failed_piece_becomes_unread_region_and_rest_is_kept(fake):
    fake(fail={(0, 1)})  # 두 번째 조각(y 600~1320)이 끝내 실패
    reading = ocr.read_image(make_image())
    assert reading.failed_pieces == 1
    assert reading.unread == [(720, 1200)]  # 이웃 조각이 덮은 부분은 빼고 남은 구간만
    assert "6년근 홍삼만 담았습니다." in [l.text for l in reading.lines]  # 첫 조각에서 읽힘


def test_read_piece_sends_jpeg_to_ocr_model(monkeypatch):
    sent = {}

    def generate_json(prompt, schema, model=None, images=None):
        sent.update(prompt=prompt, schema=schema, model=model, images=images)
        return OcrOutput(lines=[OcrLine(text="가", box_2d=[0, 0, 10, 10])])

    monkeypatch.setattr(ocr, "generate_json", generate_json)
    lines = ocr.read_piece(make_image(300))
    assert lines[0].text == "가"
    assert sent["schema"] is OcrOutput and sent["model"] == ocr.OCR_MODEL
    data, mime = sent["images"][0]
    assert mime == "image/jpeg" and data[:2] == b"\xff\xd8"


SAME = "6년근 홍삼만 담았습니다."


def piece_line(y1: int, piece_top: int, edge: int) -> Line:
    return Line(text=SAME, box=[60, y1, 400, y1 + 24], piece_top=piece_top, piece_height=720, edge=edge)


def test_same_text_read_at_different_heights_by_two_pieces_is_merged():
    # Gemini 가 같은 줄을 조각마다 50px 다르게 읽어도 한 줄로 합치고, 경계에서 먼 쪽을 남긴다
    merged = ocr.merge_lines([piece_line(650, 0, edge=46), piece_line(700, 600, edge=100)])
    assert [line.box[1] for line in merged] == [700]


def test_same_text_outside_other_piece_is_not_merged():
    # 두 번째 줄의 위치는 첫 조각(0~720)이 볼 수 없는 곳이라 다른 줄이다
    merged = ocr.merge_lines([piece_line(690, 0, edge=6), piece_line(740, 600, edge=140)])
    assert len(merged) == 2


def test_merge_picks_the_closest_twin():
    # 같은 문구가 가까이 두 번 있으면 높이가 가장 가까운 줄과 합친다
    merged = ocr.merge_lines([piece_line(650, 0, edge=46), piece_line(690, 0, edge=6), piece_line(685, 600, edge=85)])
    assert sorted(line.box[1] for line in merged) == [650, 685]


def text_image() -> Image.Image:
    # 회색 바탕에 검은 '글자 줄' 두 개 (y 300~330, 360~390)
    image = make_image(600)
    draw = ImageDraw.Draw(image)
    for top in (300, 360):
        for x in range(70, 400, 40):
            draw.rectangle([x, top, x + 30, top + 30], fill=(20, 20, 20))
    return image


def test_box_is_moved_onto_the_real_text():
    # Gemini 박스가 아래로 12px, 오른쪽으로 20px 밀려 있어도 실제 글자 줄에 맞춘다
    fitted = fit_box(text_image(), [90, 312, 420, 342], LEFT, RIGHT)
    assert fitted == pytest.approx([70, 300, 421, 331], abs=2)
    assert all(type(value) is int for value in fitted)


def test_box_far_from_any_text_line_is_left_alone():
    # 줄 높이보다 멀리 밀린 박스는 옆 줄에 잘못 붙을 수 있어서 옮기지 않는다
    assert fit_box(text_image(), [70, 330, 400, 360], LEFT, RIGHT) is None


def test_text_on_photo_is_left_alone():
    # 바탕이 고르지 않으면(사진) 맞추지 않는다
    import random

    random.seed(1)
    image = Image.new("RGB", (WIDTH, 600))
    image.putdata([tuple(random.randrange(256) for _ in range(3)) for _ in range(WIDTH * 600)])
    assert fit_box(image, [70, 300, 400, 330], 0, WIDTH) is None


# ---------------------------------------------------------------- 로컬 OCR 위치로 박스 옮기기 (text_locate)


def test_locate_finds_same_text_even_two_lines_away():
    # Gemini 박스가 두 줄 아래(y 390)에 있어도 글자가 같은 줄(y 300)을 찾는다. 바로 옆 줄은 글자가 달라서 고르지 않는다
    boxes = [OcrBox("환절기 감기 걱정 끝!", [60, 300, 420, 330]), OcrBox("매일 아침 한 포로 간편하게", [60, 350, 400, 380]),
             OcrBox("6년근 홍삼만 담았습니다.", [60, 400, 400, 430])]
    assert locate("환절기 감기 걱정 끝!", [60, 390, 420, 420], boxes, CONTENT) == [60, 300, 420, 330]


def test_locate_picks_the_closest_when_text_repeats():
    boxes = [OcrBox("에브리타임", [60, 100, 200, 130]), OcrBox("에브리타임", [60, 190, 200, 220])]
    assert locate("에브리타임", [60, 170, 200, 200], boxes, CONTENT) == [60, 190, 200, 220]


def test_locate_joins_words_on_one_row_but_not_a_far_table_cell():
    boxes = [OcrBox("환절기 감기", [60, 300, 200, 330]), OcrBox("걱정 끝!", [215, 300, 330, 330]),
             OcrBox("감기", [900, 300, 950, 330])]
    assert locate("환절기 감기 걱정 끝!", [60, 310, 330, 340], boxes, 1000) == [60, 300, 330, 330]


def test_locate_stops_when_all_text_is_found():
    # 같은 낱말이 나란히 있으면 하나만
    boxes = [OcrBox("키즈유산균", [60, 300, 200, 330]), OcrBox("키즈유산균", [220, 300, 360, 330])]
    assert locate("키즈유산균", [200, 300, 340, 330], boxes, CONTENT) in ([60, 300, 200, 330], [220, 300, 360, 330])


def test_locate_cuts_a_longer_ocr_line_to_the_matching_part():
    # 로컬 OCR 이 옆 문구까지 한 줄로 읽었으면 맞은 글자 비율만큼만
    box = locate("환절기 감기", [60, 300, 200, 330], [OcrBox("환절기 감기 걱정 끝", [60, 300, 380, 330])], CONTENT)
    assert box[0] == 60 and box[1:4:2] == [300, 330] and 200 <= box[2] <= 260


def test_locate_gives_up_on_different_or_too_short_text():
    boxes = [OcrBox("혈맥흐를", [60, 300, 200, 330]), OcrBox("A", [60, 300, 80, 330])]
    assert locate("혈액 흐름", [60, 300, 200, 330], boxes, CONTENT) is None
    assert locate("A", [60, 300, 80, 330], boxes, CONTENT) is None
    assert locate("면역력 증진", [60, 300, 200, 330], [], CONTENT) is None


def test_locate_line_joins_each_line_of_a_multi_line_reading():
    boxes = [OcrBox("면역력 증진에 도움을 줄 수", [60, 100, 400, 124]), OcrBox("있는 홍삼 농축액입니다.", [60, 130, 380, 154])]
    assert locate_line("면역력 증진에 도움을 줄 수\n있는 홍삼 농축액입니다.", [60, 120, 400, 170], boxes, CONTENT) == [60, 100, 400, 154]
    assert locate_line("면역력 증진에 도움을 줄 수\n없는 줄", [60, 120, 400, 170], boxes, CONTENT) is None


class FakeEngine:
    """RapidOCR 대신: 조각마다 맨 위에서 10px 아래에 '글자' 한 줄이 있다고 답한다."""

    def __init__(self):
        self.calls = []

    def __call__(self, array):
        self.calls.append(array.shape)
        h, w = array.shape[:2]

        class Result:
            boxes = [[[0, 10], [w - 1, 10], [w - 1, 40], [0, 40]], [[0, h - 20], [w - 1, h - 20], [w - 1, h - 1], [0, h - 1]]]
            txts = ["위쪽 글자", "아래쪽 글자"]

        return Result()


def test_read_positions_reads_strips_and_keeps_each_line_once(monkeypatch):
    engine = FakeEngine()
    monkeypatch.setattr(text_locate, "_get_engine", lambda: engine)
    boxes = text_locate.read_positions(make_image(1000), LEFT, RIGHT)
    strip = int(CONTENT * text_locate.STRIP_RATIO)
    step = int(strip * (1 - text_locate.STRIP_OVERLAP))
    assert all(shape[1] == CONTENT for shape in engine.calls)
    # 원본 좌표로 바뀌고, 겹친 부분에서 두 번 읽힌 줄은 한 번만 남는다
    tops = sorted(b.box[1] for b in boxes)
    assert tops[0] == 10 and boxes[0].box[0] == LEFT and boxes[0].box[2] == RIGHT - 1
    assert len(tops) == len(set(tops))
    assert all(b - a >= step - strip // 2 for a, b in zip(tops, tops[1:]))


def test_violation_box_follows_local_ocr_position(fake):
    # Gemini 는 y 300 이라 했지만 로컬 OCR 은 같은 글자를 y 380 에서 찾았다 → 박스는 380 쪽에 그린다
    fake(positions=[[OcrBox("환절기 감기 걱정 끝!", [60, 380, 420, 410])]])
    response = image_judge.judge_images(request(), [png_bytes(make_image())])
    [violation] = [r for r in response.results if r.status is Status.VIOLATION]
    assert violation.locations[0].box == pytest.approx([0, 370, 380, 420], abs=2)


def test_local_ocr_failure_falls_back_to_gemini_boxes(fake, monkeypatch):
    fake()

    def broken(image, left, right):
        raise RuntimeError("모델 없음")

    monkeypatch.setattr(text_locate, "read_positions", broken)
    response = image_judge.judge_images(request(), [png_bytes(make_image())])
    [violation] = [r for r in response.results if r.status is Status.VIOLATION]
    assert violation.locations[0].box == pytest.approx([0, 290, 380, 340], abs=2)


def test_strange_boxes_are_dropped():
    assert ocr.to_original(OcrLine(text="가", box_2d=[1, 2, 3]), 0, 100, 100, 0, 0) is None
    assert ocr.to_original(OcrLine(text="가", box_2d=[50, 50, 40, 60]), 0, 100, 100, 0, 0) is None
    assert ocr.to_original(OcrLine(text=" ", box_2d=[0, 0, 10, 10]), 0, 100, 100, 0, 0) is None


# ---------------------------------------------------------------- 거르기, 묶기


def line(text: str, x1: int, y1: int, x2: int, y2: int) -> Line:
    return Line(text=text, box=[x1, y1, x2, y2])


def test_notice_and_small_lines_are_excluded_but_claims_are_kept():
    kept, excluded = ocr.split_lines(
        [
            line("영양정보 총 내용량 30g", 0, 0, 100, 20),
            line("작은 포장 글자", 0, 30, 100, 33),
            line("면역 증진", 0, 40, 100, 43),  # 작아도 효능 단어가 있으면 판정
            line("6년근 홍삼만 담았습니다.", 0, 50, 300, 74),
        ],
        content_width=400,
    )
    assert [l.text for l in kept] == ["면역 증진", "6년근 홍삼만 담았습니다."]
    assert [(l.text, reason) for l, reason in excluded] == [("영양정보 총 내용량 30g", "NOTICE"), ("작은 포장 글자", "SMALL")]


def test_lines_are_grouped_until_sentence_end():
    groups = ocr.group_lines(
        [
            line("면역력 증진에 도움을 줄 수", 60, 100, 400, 124),
            line("있는 홍삼 농축액입니다.", 60, 130, 380, 154),
            line("매일 아침 한 포로", 60, 160, 380, 184),  # 앞 줄이 '다.' 로 끝나서 새 문장
            line("큰 제목", 60, 400, 380, 480),  # 글자 크기가 크게 달라 따로
        ]
    )
    assert [[l.text for l in g] for g in groups] == [
        ["면역력 증진에 도움을 줄 수", "있는 홍삼 농축액입니다."],
        ["매일 아침 한 포로"],
        ["큰 제목"],
    ]


def test_ad_text_positions_point_back_to_lines():
    first, second, third = line("감기", 0, 0, 10, 10), line("걱정 끝", 0, 12, 10, 22), line("홍삼", 0, 100, 10, 110)
    text, ranges = ocr.build_ad_text([[first, second], [third]])
    assert text == "감기 걱정 끝\n홍삼\n"
    start = text.find("걱정")
    assert ocr.lines_in(start, start + 2, ranges) == [second]
    assert ocr.lines_in(0, len("감기 걱정 끝"), ranges) == [first, second]


# ---------------------------------------------------------------- 화면용 페이지


def test_pages_do_not_overlap_cover_image_and_never_cut_text():
    boxes = [[x1, y1, x2, y2] for x1, y1, x2, y2, _ in TRUTH]
    ranges = page_ranges(HEIGHT, CONTENT, boxes)
    assert ranges[0][0] == 0 and ranges[-1][1] == HEIGHT
    assert all(a[1] == b[0] for a, b in zip(ranges, ranges[1:]))  # 빈틈·겹침 없음
    for _, bottom in ranges[:-1]:
        assert not any(y1 - CUT_MARGIN < bottom < y2 + CUT_MARGIN for _, y1, _, y2 in boxes)
    assert len(ranges) == 3


def test_short_image_is_one_page():
    assert page_ranges(900, 500, []) == [(0, 900)]


def test_page_cut_falls_back_when_text_is_everywhere():
    # 글자 박스가 빈틈없이 이어지면 원래 자리에서 자른다 (무한히 올라가지 않음)
    boxes = [[0, y, 100, y + 30] for y in range(0, 3000, 25)]
    ranges = page_ranges(3000, 500, boxes)
    assert ranges[0] == (0, 1000)


# ---------------------------------------------------------------- 전체 흐름


def decode(data_url: str) -> Image.Image:
    assert data_url.startswith("data:image/jpeg;base64,")
    return Image.open(io.BytesIO(base64.b64decode(data_url.split(",", 1)[1])))


def test_judge_images_end_to_end(fake):
    fake()
    response = image_judge.judge_images(request(), [png_bytes(make_image())])

    assert "면역력 증진에 도움을 줄 수 있는 홍삼 농축액입니다." in response.ad_text  # 두 줄이 한 문장으로
    assert "영양정보" not in response.ad_text and "작은 포장 글자" not in response.ad_text
    assert {(l.text, l.reason) for l in response.excluded_lines} == {
        ("영양정보 총 내용량 30g", "NOTICE"),
        ("작은 포장 글자", "SMALL"),
    }

    assert [p.index for p in response.pages] == [0, 1, 2]
    for page in response.pages:
        assert decode(page.image).size == (page.width, page.height) and page.width == CONTENT

    violations = [r for r in response.results if r.status is Status.VIOLATION]
    assert len(violations) == 1 and violations[0].number == 1 and violations[0].mark == "VIOLATION"
    [location] = violations[0].locations
    assert location.page == 0
    # 글자 높이 30 의 35% ≈ 10px 여백을 준 박스, 페이지 좌표 (왼쪽 여백 50 을 빼고, 페이지 밖은 자름)
    assert location.box == pytest.approx([0, 290, 380, 340], abs=2)

    ok = [r for r in response.results if r.status is Status.OK]
    assert all(r.number is None and r.mark is None for r in ok)
    assert all(r.locations for r in ok)  # 정상 문장도 누르면 이동할 수 있게 위치를 준다
    assert response.unread_regions == []


def test_violation_box_is_drawn_in_red(fake):
    fake()
    response = image_judge.judge_images(request(), [png_bytes(make_image())])
    page = decode(response.pages[0].image).convert("RGB")
    r, g, b = page.getpixel((2, 325))  # 위반 박스의 왼쪽 선 (번호 배지 아래)
    assert r > 150 and g < 100 and b < 100


def test_padding_stops_halfway_to_the_next_text_line():
    # 내용 너비 1000 → 여백 최대 30. 아래 줄이 10px 밑에 있으면 아래 여백은 5, 위·옆은 그대로
    title, below = [100, 100, 500, 200], [100, 210, 400, 250]
    assert pad_box(title, 1000, 100) == [70, 70, 530, 230]
    assert pad_box(title, 1000, 100, [below]) == [70, 70, 530, 205]
    # 겹쳐 있으면 여백 0, 박스 안에 든 줄과 멀리 있는 줄은 상관없음
    assert pad_box(title, 1000, 100, [[100, 195, 400, 250]])[3] == 200
    assert pad_box(title, 1000, 100, [[120, 110, 480, 190], [100, 900, 400, 950]]) == [70, 70, 530, 230]
    # 옆 칸도 같은 규칙
    assert pad_box(title, 1000, 100, [[520, 120, 700, 180]])[2] == 510


def test_box_edge_does_not_erase_text_inside_the_box():
    # 흰 테두리는 바깥쪽에만. 색 선 바로 안쪽 픽셀은 원래 그림(검정) 그대로
    image = Image.new("RGB", (200, 200), "black")
    cut = PageCut(index=0, image_index=0, left=0, right=200, top=0, bottom=200, scale=1.0)
    page = decode(render_page(image, cut, [Mark(page=0, box=[50, 50, 150, 150], kind="VIOLATION", number=1)], []))
    page = page.convert("RGB")
    assert page.getpixel((100, 52))[0] > 150  # 색 선
    assert page.getpixel((100, 58)) == (0, 0, 0) or max(page.getpixel((100, 58))) < 30  # 선 안쪽은 그대로
    assert min(page.getpixel((100, 47))) > 200  # 선 바깥은 흰 테두리


def approved_judge(request):
    """면역력 문장은 인정 기능성이라 정상, 감기 문장은 위반으로 답하는 가짜 판정."""
    response = fake_run_judge(request)
    for result in response.results:
        if "면역력" in result.text:
            result.reason = "정상 (인정받은 기능성: 면역력 증진에 도움을 줄 수 있음)"
    return response


def test_approved_sentence_gets_one_green_box_over_its_lines(fake, monkeypatch):
    fake()
    monkeypatch.setattr(image_judge, "run_judge", approved_judge)
    response = image_judge.judge_images(request(), [png_bytes(make_image())])
    approved = next(r for r in response.results if "면역력" in r.text)
    assert approved.status is Status.OK and approved.mark == "APPROVED" and approved.number == 1
    # 이어진 두 줄(y 100~124, 130~154)이 박스 하나로 합쳐지고 여백이 붙는다
    [location] = approved.locations
    assert location.box == pytest.approx([0, 88, 362, 166], abs=2)
    page = decode(response.pages[0].image).convert("RGB")
    r, g, b = page.getpixel((361, 130))  # 녹색 박스의 오른쪽 선
    assert g > 100 and r < 100
    assert next(r for r in response.results if r.status is Status.VIOLATION).number == 2


def test_same_problem_text_shares_one_number(fake):
    # 두 이미지에 같은 문제 문구("감기 걱정 끝")가 있으면 번호 하나, 위치 두 곳
    second_truth = [(60, 50, 400, 80, "감기 걱정 끝!")]
    fake(truths=[TRUTH, second_truth], heights=[HEIGHT, 400])
    response = image_judge.judge_images(request(), [png_bytes(make_image()), png_bytes(make_image(400))])
    assert [(p.index, p.image_index) for p in response.pages] == [(0, 0), (1, 0), (2, 0), (3, 1)]
    numbered = [(r.number, r.locations[0].page) for r in response.results if r.number]
    assert numbered == [(1, 0), (1, 3)]  # 두 번째 이미지는 마지막 페이지


def test_unread_region_is_reported_on_its_page(fake):
    fake(fail={(0, 1)})
    response = image_judge.judge_images(request(), [png_bytes(make_image())])
    assert [(u.page, u.top, u.bottom) for u in response.unread_regions] == [(0, 720, 800), (1, 0, 400)]


def test_wide_page_is_shrunk_and_boxes_follow(fake, monkeypatch):
    monkeypatch.setattr("app.image_pages.DISPLAY_MAX_WIDTH", 200)  # 내용 너비 400 → 절반
    fake()
    response = image_judge.judge_images(request(), [png_bytes(make_image())])
    assert response.pages[0].width == 200
    location = next(r for r in response.results if r.number == 1).locations[0]
    assert location.box == pytest.approx([0, 145, 190, 170], abs=2)  # 여백 준 박스 [0, 290, 380, 340] 의 절반


# ---------------------------------------------------------------- API


@pytest.fixture
def client():
    return TestClient(app)


def post(client, files, request_json='{"requestId":"t","mode":"C","product":{"type":"HEALTH_FUNCTIONAL"}}'):
    return client.post("/judge/image", data={"request": request_json}, files=[("images", f) for f in files])


def test_api_returns_camel_case_pages_and_locations(fake, client):
    fake()
    response = post(client, [("page.png", png_bytes(make_image()), "image/png")])
    assert response.status_code == 200
    body = response.json()
    assert body["pages"][0]["imageIndex"] == 0
    violation = next(r for r in body["results"] if r["status"] == "VIOLATION")
    assert violation["number"] == 1 and violation["mark"] == "VIOLATION" and violation["locations"][0]["page"] == 0
    assert {"adText", "excludedLines", "unreadRegions", "ocrModel", "kbVersion"} <= body.keys()


def test_api_rejects_non_image(fake, client):
    fake()
    assert post(client, [("a.txt", b"hello", "text/plain")]).status_code == 400


def test_api_rejects_bad_request_json(fake, client):
    fake()
    assert post(client, [("page.png", png_bytes(make_image()), "image/png")], request_json="{").status_code == 400


def test_api_rejects_too_many_images(fake, client):
    fake()
    small = png_bytes(make_image(10))
    assert post(client, [("p.png", small, "image/png")] * 11).status_code == 400


def test_api_returns_422_when_no_ad_text(fake, client):
    fake(truths=[[]])
    assert post(client, [("page.png", png_bytes(make_image()), "image/png")]).status_code == 422


def test_api_returns_503_when_every_piece_fails(fake, client):
    fake(fail={(0, n) for n in range(10)})
    assert post(client, [("page.png", png_bytes(make_image()), "image/png")]).status_code == 503
