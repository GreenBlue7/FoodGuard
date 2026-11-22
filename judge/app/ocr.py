"""상세페이지 이미지에서 광고 문구와 위치를 읽는다.

data_test 실험(ocr_full_spike, ocr_filter, ocr_geo_group)을 옮긴 것이다. 조각 크기·겹침·합치기·거르기·묶기 규칙은 실험과 같다.
흐름: 조각내기 → 조각마다 Gemini 로 글자·위치 읽기 → 겹친 줄 합치기 → 광고 아닌 줄 거르기 → 줄을 문장으로 묶기
"""

import io
import logging
import os
import re
from dataclasses import dataclass, field, replace
from difflib import SequenceMatcher

from PIL import Image, ImageChops
from pydantic import BaseModel

from app.llm import LlmError, generate_json
from app.parallel import ordered_map

logger = logging.getLogger(__name__)

# 글자 읽기에 쓰는 모델. 비우면 판정 1차 모델(GEMINI_MODEL)과 같다. 실험에서 3.1 Flash-Lite 가 3.5 보다 정확하고 안정적이었다
OCR_MODEL = os.environ.get("JUDGE_OCR_MODEL", "") or None

PROMPT = """이미지에 보이는 글자를 한 줄씩 모두 읽어 주세요.
작은 글씨, 표, 각주도 빠뜨리지 마세요. 읽은 글자는 고치지 말고 보이는 그대로 적으세요.
각 줄의 위치를 box_2d 에 [ymin, xmin, ymax, xmax] 로 적으세요.
좌표는 이미지 크기를 0~1000 으로 환산한 값입니다."""

# --- 조각내기 ---
PIECE_RATIO = 1.8  # 조각 높이 / 내용 너비. 이미지 크기가 달라도 같은 비율로 자른다
OVERLAP_RATIO = 1 / 6  # 조각 높이 대비 겹침. 글자 줄 높이보다 커야 잘린 줄을 다른 조각에서 온전히 읽는다
MIN_CONTENT_SHARE = 0.15  # 세로줄에서 이 비율 이상이 배경과 다르면 내용으로 본다
PIECE_JPEG_QUALITY = 90

# --- 겹친 줄 합치기 ---
MIN_BOX_OVERLAP = 0.5
MIN_TEXT_SIMILARITY = 0.6
NO_EDGE = 10**9
SAME_TEXT_MIN_LENGTH = 6
# 두 조각이 같은 줄의 위치를 조각 높이의 이 비율까지 다르게 볼 수 있다.
# 실험(0.04)보다 넓혔다 (Gemini 좌표가 100px 넘게 어긋나는 일이 흔함). 대신 두 줄이 서로 상대 조각 범위 안에 있어야 한다.
# 저장된 응답 34장에서 같은 줄이 두 번 남는 경우가 138 → 79 로 줄었다
SAME_TEXT_Y_RATIO = 0.10
PIECE_RANGE_SLACK = 0.03  # 줄이 상대 조각 범위 안에 있는지 볼 때 허용하는 여유 (조각 높이 대비)
LEADING_MARK = re.compile(r"^[·•\-*※]+")  # 같은 줄인데 기호만 다르게 읽히는 경우가 있다

# --- 광고 아닌 줄 거르기 (AI 분류는 쓰지 않는다) ---
MIN_HEIGHT_RATIO = 0.013  # 글자 높이가 내용 너비의 이 비율보다 작으면 포장·인증서 글자로 본다
NOTICE_WORDS = re.compile(
    r"영양정보|영양성분|kcal|소비기한|유통기한|내포장재질"
    r"|제조원|판매원|소비자상담|고객상담|이상 ?사례|보관방법|원재료명|원료명 및 함량|포장 ?단위|식품유형"
    r"|알레르기|전자레인지|직사광선|심의필|공정거래위원회|불량식품|교환 및 반품|배송|결제|청약철회"
    r"|www\.|\d{2,4}-\d{3,4}-\d{4}"
)
# 영양 성분 이름은 광고 문장에도 나오므로 표의 행처럼 짧은 줄일 때만 뺀다
NUTRITION_ROW = re.compile(r"^\W*(나트륨|탄수화물|당류|단백질|지방|트랜스지방|포화지방|콜레스테롤|열량)[\s\d.,%gmk미만이하]*$")
CLAIM_WORDS = re.compile(
    r"면역|피로|개선|치료|예방|완화|증진|효능|효과|혈액|혈당|혈압|기억력|항산화|다이어트|체지방|뼈|관절|장 ?건강|활력"
)
NOTICE = "NOTICE"  # 영양정보·판매원 같은 안내 문구
SMALL = "SMALL"  # 포장 사진 속 작은 글자

# --- 줄을 문장으로 묶기 (AI 없이 좌표 규칙만) ---
SENTENCE_END = re.compile(r"(다|요|죠)[.!?]?[\"”’')]*$|[.!?][\"”’')]*$")
LIST_START = re.compile(r"^[·•\-*※#]")
MIN_JOIN_HEIGHT_RATIO = 0.7  # 글자 크기가 너무 다르면 제목과 본문처럼 다른 내용이다
MAX_JOIN_HEIGHT_RATIO = 1.45
MAX_GAP_RATIO = 1.1  # 줄 사이 간격이 글자 높이보다 많이 크면 끊는다
MIN_JOIN_OVERLAP = 0.5  # 가로로 절반 이상 겹쳐야 같은 칸이다
SHORT_WORD = 6  # 곡선 문구처럼 낱말이 따로 읽힌 경우만 가로로 합친다
MAX_SIDE_GAP_RATIO = 1.5
RECENT_GROUPS = 40  # 같은 높이의 다른 칸을 찾을 수 있을 만큼 최근 묶음을 본다


class OcrLine(BaseModel):
    text: str
    box_2d: list[int]


class OcrOutput(BaseModel):
    lines: list[OcrLine]


@dataclass
class Line:
    """읽은 줄 하나. box 는 원본 이미지 픽셀 기준 [왼쪽 x, 위 y, 오른쪽 x, 아래 y]."""

    text: str
    box: list[int]
    image_index: int = 0
    piece_top: int = 0
    piece_height: int = 0
    edge: int = NO_EDGE  # 조각이 잘린 경계까지의 거리


@dataclass
class Reading:
    """이미지 한 장을 읽은 결과."""

    lines: list[Line]
    left: int  # 내용(좌우 여백을 뺀 부분)의 가로 범위
    right: int
    height: int
    pieces: int
    failed_pieces: int
    unread: list[tuple[int, int]] = field(default_factory=list)  # 끝내 읽지 못한 세로 구간 (위 y, 아래 y)

    @property
    def content_width(self) -> int:
        return self.right - self.left


# ---------------------------------------------------------------- 조각내고 읽기


def content_range(image: Image.Image) -> tuple[int, int]:
    # 좌우 여백을 뺀 내용의 가로 범위를 찾는다. 작은 떠 있는 버튼은 평균에서 무시된다
    background = Image.new("RGB", image.size, image.getpixel((0, image.height // 2)))
    difference = ImageChops.difference(image, background).convert("L").point(lambda value: 255 if value > 16 else 0)
    columns = difference.resize((image.width, 1), Image.BOX).tobytes()
    filled = [x for x, value in enumerate(columns) if value >= MIN_CONTENT_SHARE * 255]
    if not filled:
        return 0, image.width
    return filled[0], filled[-1] + 1


def piece_tops(image_height: int, piece_height: int) -> list[int]:
    # 조각의 시작 위치. 마지막 조각은 이미지 끝에 맞춘다
    if image_height <= piece_height:
        return [0]
    step = piece_height - int(piece_height * OVERLAP_RATIO)
    tops = list(range(0, image_height - piece_height, step))
    tops.append(image_height - piece_height)
    return tops


def read_piece(piece: Image.Image, image_index: int = 0, top: int = 0) -> list[OcrLine]:
    # 조각 하나를 Gemini 에 보내 줄마다 글자와 0~1000 좌표를 받는다
    # image_index, top(조각 위치)은 Gemini 에 보내지 않는다. 동시에 읽으면 부르는 순서가 매번 달라서,
    # 가짜 읽기(테스트)와 저장본 다시 쓰기가 어느 조각인지 위치로 알아보도록 같이 넘긴다
    buffer = io.BytesIO()
    piece.save(buffer, format="JPEG", quality=PIECE_JPEG_QUALITY)
    output = generate_json(PROMPT, OcrOutput, model=OCR_MODEL, images=[(buffer.getvalue(), "image/jpeg")])
    return output.lines


def to_original(line: OcrLine, left: int, width: int, piece_height: int, top: int, image_index: int) -> Line | None:
    # 조각의 0~1000 좌표를 원본 이미지의 픽셀 좌표로 바꾼다. 모양이 이상한 좌표는 버린다
    if len(line.box_2d) != 4 or not line.text.strip():
        return None
    ymin, xmin, ymax, xmax = (min(max(value, 0), 1000) for value in line.box_2d)
    if xmax <= xmin or ymax <= ymin:
        return None
    return Line(
        text=line.text,
        box=[
            round(xmin * width / 1000) + left,
            round(ymin * piece_height / 1000) + top,
            round(xmax * width / 1000) + left,
            round(ymax * piece_height / 1000) + top,
        ],
        image_index=image_index,
        piece_top=top,
        piece_height=piece_height,
    )


def edge_distance(line: Line, top: int, piece_height: int, is_first: bool, is_last: bool) -> int:
    # 조각이 잘린 경계까지의 거리. 경계 근처 글자는 잘려서 틀리게 읽힐 수 있다
    above = NO_EDGE if is_first else line.box[1] - top
    below = NO_EDGE if is_last else top + piece_height - line.box[3]
    return min(above, below)


def _bare(text: str) -> str:
    return LEADING_MARK.sub("", text.replace(" ", ""))


def _inside_piece(line: Line, other: Line) -> bool:
    # line 의 가운데가 other 를 읽은 조각의 세로 범위 안에 있는가. 그 조각이 실제로 읽을 수 있었던 위치인지 본다
    slack = PIECE_RANGE_SLACK * other.piece_height
    center = (line.box[1] + line.box[3]) / 2
    return other.piece_top - slack <= center <= other.piece_top + other.piece_height + slack


def same_line(a: Line, b: Line) -> bool:
    # 조각마다 좌표가 조금 달라지므로, 가까운 위치의 같은 글자를 같은 줄로 본다
    ax1, ay1, ax2, ay2 = a.box
    bx1, by1, bx2, by2 = b.box
    x_overlap = max(0, min(ax2, bx2) - max(ax1, bx1)) / max(1, min(ax2 - ax1, bx2 - bx1))
    y_distance = abs((ay1 + ay2) - (by1 + by2)) / 2
    heights = (ay2 - ay1, by2 - by1)
    text_a, text_b = _bare(a.text), _bare(b.text)
    if x_overlap < MIN_BOX_OVERLAP:
        return False
    if (
        text_a == text_b
        and len(text_a) >= SAME_TEXT_MIN_LENGTH
        and a.piece_top != b.piece_top
        and y_distance <= SAME_TEXT_Y_RATIO * a.piece_height
        and _inside_piece(a, b)
        and _inside_piece(b, a)
    ):
        return True
    if y_distance > max(heights):
        return False
    if text_a in text_b or text_b in text_a:
        return True
    return y_distance <= min(heights) and SequenceMatcher(None, text_a, text_b).ratio() >= MIN_TEXT_SIMILARITY


def _center_gap(a: Line, b: Line) -> float:
    return abs((a.box[1] + a.box[3]) - (b.box[1] + b.box[3])) / 2


def merge_lines(lines: list[Line]) -> list[Line]:
    # 같은 줄이 두 조각에서 읽히면 경계에서 더 먼 쪽을, 거리가 같으면 글자가 긴 쪽을 남긴다
    # 같은 줄 후보가 여럿이면 높이가 가장 가까운 것과 합친다 (같은 문구가 가까이 두 번 있는 페이지)
    merged: list[Line] = []
    for line in lines:
        candidates = [index for index, kept in enumerate(merged) if same_line(kept, line)]
        twin = min(candidates, key=lambda index: _center_gap(merged[index], line), default=None)
        if twin is None:
            merged.append(line)
            continue
        if (line.edge, len(line.text)) > (merged[twin].edge, len(merged[twin].text)):
            merged[twin] = line
    return merged


def _uncovered(height: int, covered: list[tuple[int, int]]) -> list[tuple[int, int]]:
    # 0~height 중 읽은 조각이 덮지 못한 구간
    gaps, cursor = [], 0
    for top, bottom in sorted(covered):
        if top > cursor:
            gaps.append((cursor, top))
        cursor = max(cursor, bottom)
    if cursor < height:
        gaps.append((cursor, height))
    return gaps


def read_image(image: Image.Image, image_index: int = 0) -> Reading:
    """이미지 한 장을 조각내 읽는다. 일부 조각이 끝내 실패해도 멈추지 않고, 못 읽은 구간을 unread 에 남긴다."""
    left, right = content_range(image)
    piece_height = max(1, int((right - left) * PIECE_RATIO))
    tops = piece_tops(image.height, piece_height)

    width = right - left

    def read_or_none(top: int) -> list[OcrLine] | None:
        # 조각은 읽을 때 잘라서, 동시에 읽는 조각 수만큼만 메모리에 둔다
        # 실패는 조각마다 잡는다. 한 조각이 실패해도 다른 조각은 계속 읽는다
        piece = image.crop((left, top, right, min(top + piece_height, image.height)))
        try:
            return read_piece(piece, image_index, top)
        except LlmError as e:
            logger.warning("이미지 %d 조각 y=%d 읽기 실패: %s", image_index, top, e)
            return None

    # 조각을 동시에 읽는다. 결과는 조각 순서대로 오므로 아래 합치기는 차례로 읽을 때와 같다
    answers = ordered_map(read_or_none, tops)

    lines: list[Line] = []
    covered: list[tuple[int, int]] = []
    failed = 0
    for index, (top, raw) in enumerate(zip(tops, answers)):
        if raw is None:
            failed += 1
            continue
        height = min(top + piece_height, image.height) - top
        covered.append((top, top + height))
        for item in raw:
            line = to_original(item, left, width, height, top, image_index)
            if line is not None:
                line.edge = edge_distance(line, top, height, index == 0, index == len(tops) - 1)
                lines.append(line)

    merged = sorted(merge_lines(lines), key=lambda line: (line.box[1], line.box[0]))
    unread = _uncovered(image.height, covered) if failed else []
    return Reading(merged, left, right, image.height, len(tops), failed, unread)


# ---------------------------------------------------------------- 광고 아닌 줄 거르기


def line_height(line: Line) -> float:
    # 여러 줄이 한 박스로 묶여 나오면 한 줄 높이로 환산한다
    return (line.box[3] - line.box[1]) / (line.text.count("\n") + 1)


def exclude_reason(line: Line, content_width: int) -> str | None:
    if CLAIM_WORDS.search(line.text):
        return None  # 효능 단어가 있으면 크기와 상관없이 판정한다
    if NOTICE_WORDS.search(line.text) or NUTRITION_ROW.match(line.text.strip()):
        return NOTICE
    if line_height(line) < MIN_HEIGHT_RATIO * content_width:
        return SMALL
    return None


def split_lines(lines: list[Line], content_width: int) -> tuple[list[Line], list[tuple[Line, str]]]:
    # (판정할 줄, (뺀 줄, 이유)) 로 나눈다
    kept, excluded = [], []
    for line in lines:
        reason = exclude_reason(line, content_width)
        if reason is None:
            kept.append(line)
        else:
            excluded.append((line, reason))
    return kept, excluded


# ---------------------------------------------------------------- 줄을 문장으로 묶기


def _height(line: Line) -> int:
    return line.box[3] - line.box[1]


def _x_overlap(a: Line, b: Line) -> float:
    left = max(a.box[0], b.box[0])
    right = min(a.box[2], b.box[2])
    narrow = min(a.box[2] - a.box[0], b.box[2] - b.box[0])
    return max(0, right - left) / max(1, narrow)


def _join_gap(previous: Line, current: Line) -> int | None:
    # 이어지는 줄이면 줄 사이 간격을, 아니면 None 을 돌려준다
    if SENTENCE_END.search(previous.text.strip()) or LIST_START.match(current.text.strip()):
        return None
    previous_height, current_height = _height(previous), _height(current)
    if not MIN_JOIN_HEIGHT_RATIO <= current_height / max(1, previous_height) <= MAX_JOIN_HEIGHT_RATIO:
        return None
    gap = current.box[1] - previous.box[3]
    if gap < -0.3 * previous_height or gap > MAX_GAP_RATIO * min(previous_height, current_height):
        return None
    if _x_overlap(previous, current) < MIN_JOIN_OVERLAP:
        return None
    return gap


def _side_by_side(left: Line, right: Line) -> bool:
    if len(left.text) > SHORT_WORD or len(right.text) > SHORT_WORD:
        return False
    if not re.search("[가-힣]", left.text) or not re.search("[가-힣]", right.text):
        return False
    size = max(_height(left), _height(right))
    center_gap = abs(left.box[1] + left.box[3] - right.box[1] - right.box[3]) / 2
    side_gap = right.box[0] - left.box[2]
    return center_gap <= size and -size <= side_gap <= MAX_SIDE_GAP_RATIO * size


def _join_side_words(lines: list[Line]) -> list[Line]:
    # "혈액", "흐름"처럼 같은 줄의 짧은 낱말이 나뉘어 읽히면 하나로 합친다
    joined: list[Line] = []
    for line in sorted(lines, key=lambda l: (l.box[0], l.box[1])):
        partner = next((index for index, kept in enumerate(joined) if _side_by_side(kept, line)), None)
        if partner is None:
            joined.append(replace(line, box=list(line.box)))
            continue
        kept = joined[partner]
        kept.text = f"{kept.text} {line.text}"
        kept.box = [
            min(kept.box[0], line.box[0]),
            min(kept.box[1], line.box[1]),
            max(kept.box[2], line.box[2]),
            max(kept.box[3], line.box[3]),
        ]
    return joined


def group_lines(lines: list[Line]) -> list[list[Line]]:
    # 같은 이미지의 줄을 위에서 아래로 보며, 이어지는 줄끼리 한 묶음(문장)으로 모은다
    groups: list[list[Line]] = []
    for line in sorted(_join_side_words(lines), key=lambda l: (l.box[1], l.box[0])):
        best_group, best_gap = None, None
        for group in groups[-RECENT_GROUPS:]:
            gap = _join_gap(group[-1], line)
            if gap is not None and (best_gap is None or gap < best_gap):
                best_group, best_gap = group, gap
        if best_group is None:
            groups.append([line])
        else:
            best_group.append(line)
    return groups


def build_ad_text(groups: list[list[Line]]) -> tuple[str, list[tuple[int, int, Line]]]:
    # 묶음을 한 줄씩 이어 붙이고, 글자 위치마다 어느 줄에서 왔는지 기록한다 (판정 결과를 박스로 되돌릴 때 씀)
    parts: list[str] = []
    ranges: list[tuple[int, int, Line]] = []
    position = 0
    for group in groups:
        for index, line in enumerate(group):
            text = line.text.replace("\n", " ")
            separator = " " if index < len(group) - 1 else "\n"
            parts.append(text + separator)
            ranges.append((position, position + len(text), line))
            position += len(text) + 1
    return "".join(parts), ranges


def lines_in(start: int, end: int, ranges: list[tuple[int, int, Line]]) -> list[Line]:
    # 판정 글자 범위 [start, end) 에 걸친 줄들
    return [line for first, last, line in ranges if first < end and start < last]
