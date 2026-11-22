"""이미지 검사: 이미지 → 글자·위치 읽기 → /judge 와 같은 판정 → 박스·번호를 그린 페이지."""

import io
import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor

from PIL import Image, ImageOps, UnidentifiedImageError

from app import text_locate
from app.box_fit import fit_box
from app.config import GEMINI_MODEL
from app.image_pages import APPROVED, Mark, PageCut, make_cuts, merge_boxes, pad_box, render_page, to_page_box
from app.image_schemas import (
    ExcludedLine,
    ImageJudgeRequest,
    ImageJudgeResponse,
    ImageJudgeResult,
    Location,
    Page,
    UnreadRegion,
)
from app.llm import LlmError
from app.ocr import OCR_MODEL, Line, Reading, build_ad_text, content_range, group_lines, lines_in, read_image, split_lines
from app.pipeline import run_judge
from app.schemas import JudgeRequest, JudgeResult, Status

logger = logging.getLogger(__name__)

ALLOWED_FORMATS = {"JPEG", "PNG", "WEBP"}
MAX_PIXELS = 200_000_000  # 가로×세로. 세로 3만 px 캡처(3420×28800 ≈ 1억)도 들어오게
Image.MAX_IMAGE_PIXELS = MAX_PIXELS  # 긴 상세페이지에서 Pillow 가 경고·오류를 내지 않게 (위 한도는 load_image 가 따로 막음)
APPROVED_REASON = "인정받은 기능성:"  # pipeline 이 인정 기능성과 맞춘 OK 문장의 이유에 붙이는 말
# 박스를 이미지의 실제 글자 위치에 맞출지 (box_fit). 끄면 Gemini 좌표 그대로
FIT_BOXES = os.environ.get("JUDGE_FIT_BOXES", "1") == "1"


class ImageInputError(ValueError):
    """이미지 형식·크기가 맞지 않을 때 (400)."""


class NoAdTextError(ValueError):
    """이미지에서 판정할 광고 문구를 하나도 찾지 못했을 때 (422)."""


def load_image(data: bytes) -> Image.Image:
    try:
        image = Image.open(io.BytesIO(data))
        if image.format not in ALLOWED_FORMATS:
            raise ImageInputError("JPG, PNG, WEBP 이미지만 올릴 수 있습니다.")
        width, height = image.size
        if width * height > MAX_PIXELS:
            raise ImageInputError(f"이미지가 너무 큽니다 ({width}×{height}).")
        image = ImageOps.exif_transpose(image)  # 휴대폰 사진의 회전 정보 반영
        return image.convert("RGB")
    except ImageInputError:
        raise
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as e:
        raise ImageInputError("이미지 파일을 열 수 없습니다.") from e


def mark_of(result: JudgeResult) -> str | None:
    # 페이지에 그릴 종류. 인정 기능성 범위라 정상인 문장은 녹색으로 보여 준다 (11월 실험에서 정한 것)
    if result.status in (Status.VIOLATION, Status.REVIEW, Status.HOLD):
        return result.status.value
    if APPROVED_REASON in result.reason:
        return APPROVED
    return None


def _squash(text: str) -> str:
    return "".join(text.split())


def _local_positions(images: list[Image.Image]) -> list[list[text_locate.OcrBox]]:
    # 로컬 OCR 로 줄 위치 읽기. 실패해도 검사는 계속한다 (그때는 box_fit·Gemini 박스로)
    out = []
    for index, image in enumerate(images):
        try:
            out.append(text_locate.read_positions(image, *content_range(image)) if text_locate.LOCAL_OCR else [])
        except Exception:
            logger.exception("이미지 %d 로컬 OCR 실패. Gemini 위치로 그린다", index)
            out.append([])
    return out


class _Viewer:
    """화면에 쓸 줄 위치. ① 로컬 OCR 에서 같은 글자 줄을 찾고(text_locate), ② 못 찾으면 실제 글자 픽셀에 맞추고(box_fit),
    ③ 그것도 안 되면 Gemini 박스 그대로."""

    def __init__(self, images: list[Image.Image], readings: list[Reading], positions: list[list[text_locate.OcrBox]]):
        self.images, self.readings, self.positions = images, readings, positions
        self.cache: dict[tuple, list[int]] = {}
        self.text_boxes: dict[int, list[list[int]]] = {}

    def neighbors(self, image_index: int) -> list[list[int]]:
        # 여백이 넘지 말아야 할 이 이미지의 모든 글자 줄 (로컬 OCR 이 찾은 줄 + 화면에 쓸 줄 위치)
        if image_index not in self.text_boxes:
            self.text_boxes[image_index] = [p.box for p in self.positions[image_index]] + [
                self.box(line) for line in self.readings[image_index].lines
            ]
        return self.text_boxes[image_index]

    def box(self, line: Line) -> list[int]:
        key = (line.image_index, tuple(line.box), line.text)
        if key not in self.cache:
            reading = self.readings[line.image_index]
            found = text_locate.locate_line(line.text, line.box, self.positions[line.image_index], reading.content_width)
            if found is None and FIT_BOXES:
                found = fit_box(self.images[line.image_index], line.box, reading.left, reading.right, line.text.count("\n") + 1)
            self.cache[key] = found or line.box
        return self.cache[key]

    def boxes(self, lines: list[Line], padded: bool) -> list[tuple[int, list[int]]]:
        # 줄들을 이미지별로 모아, 이어진 줄은 박스 하나로 합치고 (그릴 박스면) 여백을 준다. 원본 좌표
        out = []
        for image_index in sorted({line.image_index for line in lines}):
            reading = self.readings[image_index]
            for box in merge_boxes([self.box(line) for line in lines if line.image_index == image_index]):
                if padded:
                    box = pad_box(box, reading.content_width, box[3] - box[1], self.neighbors(image_index))
                out.append((image_index, box))
        return out


def _locate(cuts: list[PageCut], image_index: int, box: list[int]) -> list[Location]:
    return [
        Location(page=cut.index, box=page_box)
        for cut in cuts
        if cut.image_index == image_index and (page_box := to_page_box(cut, box)) is not None
    ]


def judge_images(request: ImageJudgeRequest, files: list[bytes]) -> ImageJudgeResponse:
    started = time.perf_counter()
    images = [load_image(data) for data in files]

    # 1) 글자·위치 읽기. Gemini 가 글자를 읽는 동안 로컬 OCR 로 정확한 줄 위치를 같이 읽는다
    with ThreadPoolExecutor(max_workers=1) as pool:
        local = pool.submit(_local_positions, images)
        readings: list[Reading] = [read_image(image, index) for index, image in enumerate(images)]
        positions = local.result()
    if all(reading.failed_pieces == reading.pieces for reading in readings):
        raise LlmError("이미지 글자를 하나도 읽지 못했습니다.")

    # 2) 광고 아닌 줄 거르기, 줄을 문장으로 묶기 (이미지마다 따로, 올린 순서대로 이어 붙임)
    groups: list[list[Line]] = []
    excluded: list[tuple[Line, str]] = []
    for reading in readings:
        kept, dropped = split_lines(reading.lines, reading.content_width)
        groups.extend(group_lines(kept))
        excluded.extend(dropped)
    ad_text, ranges = build_ad_text(groups)
    if not ad_text.strip():
        raise NoAdTextError("이미지에서 광고 문구를 찾지 못했습니다.")

    # 3) /judge 와 같은 판정
    judged = run_judge(
        JudgeRequest(request_id=request.request_id, mode=request.mode, product=request.product, ad_text=ad_text)
    )

    # 4) 화면용 페이지 나누기 (모든 줄을 피해서 자른다. 줄 위치는 화면에 쓸 위치 + 로컬 OCR 이 찾은 모든 글자)
    viewer = _Viewer(images, readings, positions)
    cuts: list[PageCut] = []
    for image_index, reading in enumerate(readings):
        avoid = [viewer.box(l) for l in reading.lines] + [p.box for p in positions[image_index]]
        cuts.extend(make_cuts(image_index, len(cuts), reading.left, reading.right, reading.height, avoid))

    # 5) 판정 결과 → 표시 종류, 번호, 위치. 같은 표시·같은 문제 문구는 번호 하나로 묶는다
    results: list[ImageJudgeResult] = []
    marks: list[Mark] = []
    numbers: dict[tuple[str, str], int] = {}
    for result in judged.results:
        mark = mark_of(result)
        lines = lines_in(result.span.start, result.span.end, ranges)
        located = [loc for image_index, box in viewer.boxes(lines, padded=mark is not None) for loc in _locate(cuts, image_index, box)]
        number = None
        if mark is not None:
            key = (mark, _squash(ad_text[result.span.start : result.span.end]))
            number = numbers.setdefault(key, len(numbers) + 1)
            marks.extend(Mark(loc.page, loc.box, mark, number) for loc in located)
        results.append(ImageJudgeResult(**result.model_dump(), mark=mark, number=number, locations=located))

    unread_regions = [
        UnreadRegion(page=loc.page, top=loc.box[1], bottom=loc.box[3])
        for image_index, reading in enumerate(readings)
        for top, bottom in reading.unread
        for loc in _locate(cuts, image_index, [reading.left, top, reading.right, bottom])
    ]
    excluded_lines = [
        ExcludedLine(text=line.text, reason=reason, page=locs[0].page, box=locs[0].box)
        for line, reason in excluded
        if (locs := _locate(cuts, line.image_index, viewer.box(line)))
    ]

    # 6) 페이지 그리기
    pages = [
        Page(
            index=cut.index,
            image_index=cut.image_index,
            width=cut.width,
            height=cut.height,
            image=render_page(
                images[cut.image_index],
                cut,
                [m for m in marks if m.page == cut.index],
                [(region.top, region.bottom) for region in unread_regions if region.page == cut.index],
            ),
        )
        for cut in cuts
    ]

    return ImageJudgeResponse(
        pages=pages,
        ad_text=ad_text,
        sentences=judged.sentences,
        results=results,
        excluded_lines=excluded_lines,
        unread_regions=unread_regions,
        model=judged.model,
        ocr_model=OCR_MODEL or GEMINI_MODEL,
        kb_version=judged.kb_version,
        elapsed_ms=int((time.perf_counter() - started) * 1000),
    )
