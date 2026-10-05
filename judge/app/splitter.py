import re

from app.schemas import Sentence

# 문장 끝: 문장부호(연속 가능) + 닫는 따옴표, 괄호(있으면), 바로 뒤가 공백이거나 줄 끝일 때만 끊는다.
# "1.5배", "No.1" 처럼 뒤에 글자가 붙어 있으면 끊지 않는다.
_SENTENCE_END = re.compile(r"[.!?。！？…]+[\"'”’)\]]*(?=\s|$)")
_LINE = re.compile(r"[^\r\n]+")

def split_sentences(text: str) -> list[Sentence]:
    # 광고 문구를 문장으로 나눔, start/end는 원문 기준 글자 순번(0부터, end 미포함)
    spans: list[tuple[int, int]] = []
    for line in _LINE.finditer(text):
        start = line.start()
        for match in _SENTENCE_END.finditer(text, line.start(), line.end()):
            spans.append((start, match.end()))
            start = match.end()
        spans.append((start, line.end()))

    sentences: list[Sentence] = []
    for start, end in spans:
        chunk = text[start:end]
        stripped = chunk.strip()
        if not stripped:
            continue
        start += len(chunk) - len(chunk.lstrip()) # 앞 공백만큼 시작 위치를 민다.
        sentences.append(
            Sentence(
                id=f"s{len(sentences) + 1}",
                text=stripped,
                start=start,
                end=start + len(stripped),
            )
        )
    return sentences