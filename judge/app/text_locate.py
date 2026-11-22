"""Gemini 가 읽은 줄의 위치를 로컬 OCR(RapidOCR, 한국어) 결과에서 글자 내용으로 찾아 바꾼다. API 호출 없음.

Gemini 는 글자를 잘 읽지만 위치가 수십~수백 px 틀릴 때가 많다. 로컬 OCR 은 위치가 정확하다.
그래서 Gemini 줄마다 근처의 OCR 줄 중 글자가 같은 것을 찾아 그 위치를 쓴다. 못 찾으면 None (Gemini 박스 그대로).
"""

import logging
import os
import re
import threading
from dataclasses import dataclass
from difflib import SequenceMatcher

import numpy as np
from PIL import Image

logger = logging.getLogger(__name__)

# 로컬 OCR 로 위치를 찾을지. 끄면 box_fit → Gemini 박스 순서로만 쓴다
LOCAL_OCR = os.environ.get("JUDGE_LOCAL_OCR", "1") == "1"
STRIP_RATIO = 1.0  # 로컬 OCR 에 넣는 조각 높이 / 내용 너비
STRIP_OVERLAP = 0.15  # 조각끼리 겹치는 비율. 겹친 부분의 줄은 가운데 기준으로 한 조각에서만 쓴다

SEARCH_RATIO = 0.25  # 위아래로 내용 너비의 이 배까지 찾는다 (Gemini 위치 오차가 몇백 px 일 때도 있음)
MIN_PRECISION = 0.6  # OCR 줄 글자 중 이 비율 이상이 Gemini 줄과 같아야 같은 줄의 일부로 본다
MIN_RECALL = 0.6  # Gemini 줄 글자 중 이 비율 이상을 찾아야 위치를 바꾼다
WORD_GAP = 2.0  # 같은 가로줄이라도 글자 높이의 이 배보다 멀리 떨어진 조각은 다른 문구로 본다 (표의 옆 칸 등)
DISTANCE_PENALTY = 0.3  # 글자가 똑같이 맞는 줄이 여럿이면 Gemini 위치에 가까운 줄 (찾는 범위 끝이면 점수 -0.3)

_KEEP = re.compile(r"[0-9A-Za-z가-힣]")


@dataclass
class OcrBox:
    text: str
    box: list[int]


def _norm(text: str) -> str:
    return "".join(_KEEP.findall(text)).lower()


def _match(gem: str, ocr: str) -> tuple[int, int, int]:
    # (같은 글자 수, OCR 줄에서 맞은 첫 글자 위치, 끝 위치)
    blocks = [b for b in SequenceMatcher(None, gem, ocr, autojunk=False).get_matching_blocks() if b.size]
    if not blocks:
        return 0, 0, 0
    return sum(b.size for b in blocks), blocks[0].b, blocks[-1].b + blocks[-1].size


def _part(box: OcrBox, start: int, end: int, length: int) -> list[int]:
    # OCR 줄이 Gemini 줄보다 길면(옆 문구까지 한 줄로 읽음) 맞은 글자 비율만큼만 가로로 자른다
    x1, y1, x2, y2 = box.box
    if length == 0 or (start == 0 and end == length):
        return list(box.box)
    w = x2 - x1
    return [int(x1 + w * start / length), y1, int(x1 + w * end / length), y2]


def _cluster(row: list, length: int) -> list:
    # 가로줄에서 가장 많이 맞은 조각부터 좌우로 가까운 조각을 붙인다. Gemini 글자를 다 찾으면 멈춘다 (같은 낱말이 나란히 있을 때)
    row = sorted(row, key=lambda h: h[2][0])
    core = max(range(len(row)), key=lambda i: row[i][1])
    height = row[core][2][3] - row[core][2][1]
    lo = hi = core
    same = row[core][1]
    while same < length:
        gaps = []
        if lo > 0:
            gaps.append((row[lo][2][0] - row[lo - 1][2][2], -1))
        if hi < len(row) - 1:
            gaps.append((row[hi + 1][2][0] - row[hi][2][2], 1))
        if not gaps or min(gaps)[0] > WORD_GAP * height:
            break
        side = min(gaps)[1]
        if side < 0:
            lo -= 1
            same += row[lo][1]
        else:
            hi += 1
            same += row[hi][1]
    return row[lo : hi + 1]


def locate(text: str, box: list[int], boxes: list[OcrBox], content_width: int) -> list[int] | None:
    """한 줄(줄바꿈 없는 글자)의 OCR 위치 [x1, y1, x2, y2]. 못 찾으면 None."""
    gem = _norm(text)
    if len(gem) < 2:
        return None
    center = (box[1] + box[3]) / 2
    search = SEARCH_RATIO * content_width
    hits = []  # (OCR 줄, 같은 글자 수, 자른 박스)
    for ocr_box in boxes:
        ocr_center = (ocr_box.box[1] + ocr_box.box[3]) / 2
        if abs(ocr_center - center) > search:
            continue
        ocr = _norm(ocr_box.text)
        if not ocr:
            continue
        same, start, end = _match(gem, ocr)
        if same < 2:
            continue
        if same / len(ocr) >= MIN_PRECISION:
            hits.append((ocr_box, same, _part(ocr_box, start, end, len(ocr))))
        elif same / len(gem) >= MIN_RECALL and (same / (end - start) if end > start else 0) >= MIN_PRECISION:
            hits.append((ocr_box, same, _part(ocr_box, start, end, len(ocr))))
    if not hits:
        return None
    # 같은 높이(가로줄)에 있는 조각끼리 묶고, Gemini 글자를 가장 많이 덮는 가로줄을 고른다
    rows: list[list] = []
    for hit in sorted(hits, key=lambda h: (h[2][1] + h[2][3]) / 2):
        cy = (hit[2][1] + hit[2][3]) / 2
        if rows and rows[-1][-1][2][1] <= cy <= rows[-1][-1][2][3]:
            rows[-1].append(hit)
        else:
            rows.append([hit])

    def score(row):
        recall = min(1.0, sum(h[1] for h in row) / len(gem))
        cy = sum((h[2][1] + h[2][3]) / 2 for h in row) / len(row)
        return recall - DISTANCE_PENALTY * abs(cy - center) / search, recall

    rows = [_cluster(row, len(gem)) for row in rows]
    best = max(rows, key=lambda row: score(row)[0])
    if score(best)[1] < MIN_RECALL:
        return None
    return [min(h[2][0] for h in best), min(h[2][1] for h in best), max(h[2][2] for h in best), max(h[2][3] for h in best)]


def locate_line(text: str, box: list[int], boxes: list[OcrBox], content_width: int) -> list[int] | None:
    # Gemini 줄에 줄바꿈이 있으면 줄마다 찾아 합친다. 하나라도 못 찾으면 None
    parts = [part for part in text.split("\n") if part.strip()]
    found = [locate(part, box, boxes, content_width) for part in parts]
    if not found or any(f is None for f in found):
        return None
    return [min(f[0] for f in found), min(f[1] for f in found), max(f[2] for f in found), max(f[3] for f in found)]


_engine = None
_engine_lock = threading.Lock()


def _get_engine():
    # 처음 쓸 때 한 번만 만든다 (모델 불러오기). 라이브러리가 없으면 ImportError
    global _engine
    with _engine_lock:
        if _engine is None:
            from rapidocr import LangRec, ModelType, OCRVersion, RapidOCR

            _engine = RapidOCR(
                params={
                    "Rec.lang_type": LangRec.KOREAN,
                    "Rec.ocr_version": OCRVersion.PPOCRV5,
                    "Rec.model_type": ModelType.MOBILE,
                    "Global.use_cls": False,
                    "Global.log_level": "warning",
                }
            )
        return _engine


def warm_up() -> None:
    """모델을 미리 불러온다 (Docker 빌드 때 한 번). 글자 인식 모델은 처음 읽을 때 내려받으므로 빈 이미지를 한 번 읽힌다."""
    _get_engine()(np.full((48, 320, 3), 255, np.uint8), use_det=True, use_cls=False, use_rec=False)
    _get_engine()(np.full((48, 320, 3), 255, np.uint8), use_det=False, use_cls=False, use_rec=True)


def read_positions(image: Image.Image, left: int, right: int) -> list[OcrBox]:
    """이미지 내용 부분을 조각내 로컬 OCR 로 읽고, 줄마다 글자와 원본 좌표를 돌려준다."""
    engine = _get_engine()
    width = right - left
    strip = max(1, int(width * STRIP_RATIO))
    step = max(1, int(strip * (1 - STRIP_OVERLAP)))
    half = (strip - step) // 2
    out: list[OcrBox] = []
    top = 0
    while True:
        bottom = min(image.height, top + strip)
        result = engine(np.asarray(image.crop((left, top, right, bottom))))
        own_top = top + (half if top else 0)
        own_bottom = bottom - (half if bottom < image.height else 0)
        for quad, text in zip(result.boxes if result.boxes is not None else [], result.txts or []):
            xs = [p[0] + left for p in quad]
            ys = [p[1] + top for p in quad]
            if own_top <= sum(ys) / len(ys) < own_bottom:
                out.append(OcrBox(text, [int(min(xs)), int(min(ys)), int(max(xs)), int(max(ys))]))
        if bottom >= image.height:
            return out
        top += step
