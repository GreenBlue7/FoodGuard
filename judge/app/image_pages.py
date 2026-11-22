"""화면에 보여 줄 페이지를 만든다: 겹치지 않게 자르고, 판정 박스와 번호를 그려 JPEG 로 돌려준다.

글자 읽기용 조각(ocr.py, 서로 겹침)과는 따로 자른다. 같은 내용이 두 번 보이지 않게, 글자 줄 사이 빈 곳에서 자른다.
박스 모양은 11월 실험(data_test/ocr_render_spike.py)에서 정한 것을 따른다:
글자 높이에 맞춘 여백(좌표가 조금 틀려도 글자를 덮음), 이어진 줄은 박스 하나, 둥근 박스 + 흰 테두리 + 동그란 번호 배지.
"""

import base64
import io
from dataclasses import dataclass

from PIL import Image, ImageDraw, ImageFont

PAGE_RATIO = 2.0  # 페이지 높이 / 내용 너비. 이보다 많이 길면 여러 페이지로 나눈다
PAGE_SLACK = 1.25  # 남은 길이가 목표의 이 배 이하면 더 자르지 않는다 (아주 짧은 마지막 페이지 방지)
CUT_MARGIN = 6  # 글자 박스 위아래로 이만큼은 자르지 않는다 (박스가 글자보다 조금 작게 나올 때가 있음)
DISPLAY_MAX_WIDTH = 1000  # 화면용 페이지 최대 너비(px). 더 넓으면 줄인다
JPEG_QUALITY = 80

# 표시 종류별 색. APPROVED = 인정받은 기능성 범위라 정상인 문장 (녹색)
APPROVED = "APPROVED"
COLORS = {
    "VIOLATION": (220, 20, 40),  # 빨강
    "REVIEW": (240, 140, 0),  # 주황
    "HOLD": (40, 90, 220),  # 파랑
    APPROVED: (20, 150, 80),  # 녹색
}
HALO = (255, 255, 255)

# 여백: 글자 높이에 비례. 최소·최대는 내용 너비 대비 (실험: 내용 너비 2000px 에서 24~60px)
PADDING_RATIO = 0.35
MIN_PADDING_RATIO = 0.012
MAX_PADDING_RATIO = 0.03
MERGE_GAP_RATIO = 1.0  # 한 결과의 줄 사이 간격이 글자 높이 이하면 박스 하나로 합친다

# 화면용 페이지(가로 최대 1000px) 기준 굵기. 실험의 원본 기준 값(선 8, 테두리 8, 배지 46)을 절반으로
LINE_WIDTH = 4
HALO_WIDTH = 4
CORNER_RADIUS = 8
BADGE_RADIUS = 20
BADGE_FONT_SIZE = 24
UNREAD_FILL = (120, 120, 120, 110)  # 읽지 못한 구간을 덮는 반투명 회색


@dataclass
class PageCut:
    """원본 이미지의 어느 부분이 몇 번째 페이지인지."""

    index: int  # 전체 페이지 번호 (0부터)
    image_index: int
    left: int
    top: int
    right: int
    bottom: int
    scale: float  # 화면용으로 줄인 비율

    @property
    def width(self) -> int:
        return round((self.right - self.left) * self.scale)

    @property
    def height(self) -> int:
        return round((self.bottom - self.top) * self.scale)


# ---------------------------------------------------------------- 페이지 나누기


def _safe_cut(y: int, boxes: list[list[int]], lowest: int) -> int:
    # y 가 글자 박스를 가로지르면 그 박스 위로 올린다. lowest 보다 더 올라가야 하면 원래 자리에서 자른다
    candidate = y
    moved = True
    while moved:
        moved = False
        for _, top, _, bottom in boxes:
            if top - CUT_MARGIN < candidate < bottom + CUT_MARGIN:
                candidate = top - CUT_MARGIN
                moved = True
        if candidate <= lowest:
            return y
    return candidate


def page_ranges(height: int, content_width: int, boxes: list[list[int]]) -> list[tuple[int, int]]:
    """이미지 한 장을 겹치지 않는 세로 구간들로 나눈다. 짧은 이미지는 그대로 한 페이지다."""
    target = max(1, int(content_width * PAGE_RATIO))
    cuts = [0]
    while height - cuts[-1] > target * PAGE_SLACK:
        want = cuts[-1] + target
        cuts.append(_safe_cut(want, boxes, lowest=cuts[-1] + target // 2))
    cuts.append(height)
    return list(zip(cuts, cuts[1:]))


def make_cuts(image_index: int, first_page: int, left: int, right: int, height: int, boxes: list[list[int]]) -> list[PageCut]:
    scale = min(1.0, DISPLAY_MAX_WIDTH / max(1, right - left))
    return [
        PageCut(first_page + offset, image_index, left, top, right, bottom, scale)
        for offset, (top, bottom) in enumerate(page_ranges(height, right - left, boxes))
    ]


def to_page_box(cut: PageCut, box: list[int]) -> list[int] | None:
    # 원본 좌표 박스를 이 페이지의 화면 좌표로 바꾼다. 페이지와 겹치지 않으면 None
    x1, y1, x2, y2 = box
    if y2 <= cut.top or y1 >= cut.bottom:
        return None
    s = cut.scale
    clipped = [
        max(x1, cut.left) - cut.left,
        max(y1, cut.top) - cut.top,
        min(x2, cut.right) - cut.left,
        min(y2, cut.bottom) - cut.top,
    ]
    if clipped[2] <= clipped[0] or clipped[3] <= clipped[1]:
        return None
    return [round(value * s) for value in clipped]


# ---------------------------------------------------------------- 박스 모양 (원본 좌표)


def merge_boxes(boxes: list[list[int]]) -> list[list[int]]:
    # 한 결과의 여러 줄이 이어져 있으면 박스 하나로 합친다
    merged: list[list[int]] = []
    for box in sorted(boxes, key=lambda b: b[1]):
        if merged:
            last = merged[-1]
            if box[1] - last[3] <= MERGE_GAP_RATIO * (box[3] - box[1]):
                merged[-1] = [min(last[0], box[0]), last[1], max(last[2], box[2]), max(last[3], box[3])]
                continue
        merged.append(list(box))
    return merged


def pad_box(box: list[int], content_width: int, line_height: int, neighbors: list[list[int]] = ()) -> list[int]:
    # 글자가 가려지지 않고 좌표가 조금 틀려도 글자를 덮도록, 한 줄 높이에 맞춰 여백을 준다.
    # 단, 바로 위·아래·옆 글자 줄(neighbors)과의 간격 절반까지만. 큰 제목 박스가 밑의 작은 줄을 가로지르지 않게
    padding = int(min(MAX_PADDING_RATIO * content_width, max(MIN_PADDING_RATIO * content_width, line_height * PADDING_RATIO)))
    x1, y1, x2, y2 = box
    top = bottom = left = right = padding
    for nx1, ny1, nx2, ny2 in neighbors:
        # 가운데가 박스 안에 있는 줄은 이 박스에 든 글자라 건너뛴다
        cx, cy = (nx1 + nx2) / 2, (ny1 + ny2) / 2
        if nx1 < x2 and nx2 > x1:  # 가로로 겹치면 위·아래 이웃
            if cy < y1:
                top = min(top, max(0, (y1 - ny2) // 2))
            elif cy > y2:
                bottom = min(bottom, max(0, (ny1 - y2) // 2))
        if ny1 < y2 and ny2 > y1:  # 세로로 겹치면 왼쪽·오른쪽 이웃
            if cx < x1:
                left = min(left, max(0, (x1 - nx2) // 2))
            elif cx > x2:
                right = min(right, max(0, (nx1 - x2) // 2))
    return [x1 - left, y1 - top, x2 + right, y2 + bottom]


# ---------------------------------------------------------------- 그리기


@dataclass
class Mark:
    """페이지 위에 그릴 박스 하나."""

    page: int
    box: list[int]  # 페이지 화면 좌표
    kind: str  # COLORS 의 열쇠
    number: int


def _font(size: int) -> ImageFont.ImageFont:
    try:
        return ImageFont.load_default(size=size)
    except TypeError:  # 아주 오래된 Pillow
        return ImageFont.load_default()


def _draw_box(draw: ImageDraw.ImageDraw, mark: Mark) -> None:
    # 어떤 배경에서도 보이도록 색 테두리 바깥에 흰 테두리를 두른다.
    # 흰 테두리는 바깥쪽에만 그린다 (안쪽까지 덮으면 박스 끝에 걸친 이웃 줄 글자가 지워진다)
    color = COLORS[mark.kind]
    x1, y1, x2, y2 = mark.box
    draw.rounded_rectangle(
        (x1 - HALO_WIDTH, y1 - HALO_WIDTH, x2 + HALO_WIDTH, y2 + HALO_WIDTH),
        radius=CORNER_RADIUS + HALO_WIDTH, outline=HALO, width=HALO_WIDTH,
    )
    draw.rounded_rectangle((x1, y1, x2, y2), radius=CORNER_RADIUS, outline=color, width=LINE_WIDTH)


def _draw_badge(draw: ImageDraw.ImageDraw, mark: Mark, page_size: tuple[int, int], font) -> None:
    # 박스 왼쪽 위 모서리의 번호 배지
    color = COLORS[mark.kind]
    x1, y1 = mark.box[0], mark.box[1]
    # 배지가 페이지 밖으로 잘리지 않게 안쪽으로 민다
    edge = BADGE_RADIUS + 3
    cx = min(max(x1, edge), page_size[0] - edge)
    cy = min(max(y1, edge), page_size[1] - edge)
    draw.ellipse((cx - edge, cy - edge, cx + edge, cy + edge), fill=HALO)
    draw.ellipse((cx - BADGE_RADIUS, cy - BADGE_RADIUS, cx + BADGE_RADIUS, cy + BADGE_RADIUS), fill=color)
    draw.text((cx, cy), str(mark.number), fill=HALO, font=font, anchor="mm")


def render_page(image: Image.Image, cut: PageCut, marks: list[Mark], unread: list[tuple[int, int]]) -> str:
    """페이지를 잘라 박스·번호·못 읽은 구간을 그리고 data URL(JPEG) 로 돌려준다."""
    page = image.crop((cut.left, cut.top, cut.right, cut.bottom))
    if cut.scale < 1:
        page = page.resize((cut.width, cut.height), Image.LANCZOS)
    page = page.convert("RGBA")

    if unread:
        overlay = Image.new("RGBA", page.size, (0, 0, 0, 0))
        shade = ImageDraw.Draw(overlay)
        for top, bottom in unread:
            shade.rectangle([0, top, page.width, bottom], fill=UNREAD_FILL)
        page = Image.alpha_composite(page, overlay)

    draw = ImageDraw.Draw(page)
    font = _font(BADGE_FONT_SIZE)
    # 나중에 그린 것이 위에 온다. 번호가 큰 것부터 그려 작은 번호(위쪽 문장)가 위에 보이게.
    # 배지는 박스 선을 모두 그린 뒤에 그린다 (붙어 있는 박스의 선이 아래 박스 배지를 가리지 않게)
    ordered = sorted(marks, key=lambda m: -m.number)
    for mark in ordered:
        _draw_box(draw, mark)
    for mark in ordered:
        _draw_badge(draw, mark, page.size, font)

    buffer = io.BytesIO()
    page.convert("RGB").save(buffer, format="JPEG", quality=JPEG_QUALITY)
    return "data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode()
