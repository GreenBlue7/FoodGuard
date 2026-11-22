"""Gemini 가 준 줄 박스를 이미지의 실제 글자 위치에 맞춘다. AI 호출 없음.

Gemini 는 글자는 잘 읽지만 위치는 수십~수백 px 틀릴 때가 많다. 그래서 글자 내용은 Gemini 것을 쓰고,
위치는 박스 주변 이미지에서 배경과 다른 픽셀이 모인 '글자 줄'을 찾아 맞춘다.
배경이 고르지 않거나(사진 위 글자) 글자 줄을 확실히 못 찾으면 None 을 돌려주고, 그때는 Gemini 박스를 그대로 쓴다.
"""

import numpy as np
from PIL import Image

SEARCH_Y = 1.5  # 위아래로 줄 높이의 몇 배까지 찾아볼지
SEARCH_X = 1.0  # 좌우로 줄 높이의 몇 배까지
INK_DIFF = 60  # 배경색과 이만큼(0~255) 넘게 다르면 글자 픽셀로 본다
PLAIN_DIFF = 25  # 테두리 픽셀이 배경색과 이만큼 이하로 다르면 '고른 배경'
MIN_PLAIN_SHARE = 0.7  # 찾는 영역 테두리의 이 비율 이상이 고른 배경이어야 맞춘다 (사진 위 글자 거르기)
MIN_ROW_INK = 0.01  # 한 가로줄에서 이 비율 이상 글자 픽셀이 있으면 글자가 있는 줄
ROW_GAP_RATIO = 0.12  # 줄 높이의 이 비율 이하의 빈 가로줄은 같은 글자 줄 안의 틈으로 본다
WORD_GAP_RATIO = 1.0  # 글자 줄 높이의 이 비율 이하의 빈 세로줄은 같은 문장 안의 띄어쓰기로 본다
# 찾은 글자 줄 가운데가 Gemini 박스 가운데에서 줄 높이의 이 배 넘게 멀면 버린다 (Gemini 박스 그대로).
# 크게 밀린 박스를 멀리 옮기면 '옆 줄'에 딱 맞게 붙는 일이 있었다 (틀린 줄인데 맞아 보여서 더 위험). 그래서 반 줄 남짓만 옮긴다
MAX_CENTER_SHIFT = 0.6
MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO = 0.4, 2.0  # 찾은 글자 줄 높이 / Gemini 박스 높이 (한 줄일 때)
MIN_WIDTH_RATIO, MAX_WIDTH_RATIO = 0.4, 1.8  # 찾은 너비 / Gemini 박스 너비


def _runs(mask: np.ndarray, max_gap: int) -> list[tuple[int, int]]:
    # True 가 이어진 구간들 [시작, 끝). max_gap 이하의 False 틈은 이어 붙인다
    runs: list[list[int]] = []
    for index in np.flatnonzero(mask):
        if runs and index - runs[-1][1] <= max_gap:
            runs[-1][1] = index + 1
        else:
            runs.append([index, index + 1])
    return [(start, end) for start, end in runs]


def fit_box(image: Image.Image, box: list[int], left: int, right: int, lines_in_box: int = 1) -> list[int] | None:
    """box 근처의 실제 글자 범위를 찾아 [x1, y1, x2, y2] 로 돌려준다. 못 찾으면 None."""
    x1, y1, x2, y2 = box
    height, width = y2 - y1, x2 - x1
    if height <= 2 or width <= 2:
        return None
    line_height = height / lines_in_box
    wx1 = max(left, int(x1 - SEARCH_X * line_height))
    wx2 = min(right, int(x2 + SEARCH_X * line_height))
    wy1 = max(0, int(y1 - SEARCH_Y * line_height))
    wy2 = min(image.height, int(y2 + SEARCH_Y * line_height))
    if wx2 - wx1 < 4 or wy2 - wy1 < 4:
        return None
    window = np.asarray(image.crop((wx1, wy1, wx2, wy2)), dtype=np.int16)

    # 배경색 = 찾는 영역 테두리의 가운데 값. 테두리가 고르지 않으면 사진 위 글자로 보고 맞추지 않는다
    border = np.concatenate([window[0], window[-1], window[:, 0], window[:, -1]])
    background = np.median(border, axis=0)
    if (np.abs(border - background).max(axis=1) <= PLAIN_DIFF).mean() < MIN_PLAIN_SHARE:
        return None
    ink = np.abs(window - background).max(axis=2) > INK_DIFF

    # 1) 세로: Gemini 박스의 가로 범위 안에서 글자가 있는 가로줄들을 '글자 줄'로 묶는다
    bx1, bx2 = max(0, x1 - wx1), min(ink.shape[1], x2 - wx1)
    if bx2 - bx1 < 2:
        return None
    rows = ink[:, bx1:bx2].mean(axis=1) >= MIN_ROW_INK
    bands = _runs(rows, max(1, int(ROW_GAP_RATIO * line_height)))
    if not bands:
        return None
    center = (y1 + y2) / 2 - wy1
    if lines_in_box > 1:
        # 여러 줄이 한 박스로 온 경우: 박스와 겹치는 글자 줄을 모두 합친다
        overlapping = [b for b in bands if b[1] > y1 - wy1 and b[0] < y2 - wy1]
        if not overlapping:
            return None
        top, bottom = overlapping[0][0], overlapping[-1][1]
        if not MIN_HEIGHT_RATIO <= (bottom - top) / height <= MAX_HEIGHT_RATIO:
            return None
    else:
        sized = [b for b in bands if MIN_HEIGHT_RATIO <= (b[1] - b[0]) / height <= MAX_HEIGHT_RATIO]
        if not sized:
            return None
        top, bottom = min(sized, key=lambda b: abs((b[0] + b[1]) / 2 - center))
    if abs((top + bottom) / 2 - center) > MAX_CENTER_SHIFT * line_height:
        return None

    # 2) 가로: 찾은 글자 줄에서 글자가 있는 세로줄을 낱말 단위로 묶고, Gemini 박스와 겹치는 것만 합친다
    columns = ink[top:bottom].any(axis=0)
    words = _runs(columns, max(1, int(WORD_GAP_RATIO * (bottom - top) / max(1, lines_in_box))))
    hits = [w for w in words if w[1] > bx1 and w[0] < bx2]
    if not hits:
        return None
    start, end = hits[0][0], hits[-1][1]
    if not MIN_WIDTH_RATIO <= (end - start) / width <= MAX_WIDTH_RATIO:
        return None
    return [int(wx1 + start), int(wy1 + top), int(wx1 + end), int(wy1 + bottom)]
