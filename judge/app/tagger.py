import json
import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

from app.schemas import Sentence

_TAGS_FILE = Path(__file__).parent / "data" / "tags.json"


class TagType(str, Enum):
    MANDATORY = "MANDATORY" # 법정 의무 문구
    DISEASE = "DISEASE" # 질병
    DRUG = "DRUG" # 의약품 오인
    EXAGGERATION = "EXAGGERATION" # 과장
    TESTIMONIAL = "TESTIMONIAL" # 체험기

@dataclass(frozen=True)
class Tag:
    type: TagType
    text: str # 실제로 걸린 글자
    start: int # 원문 기준 위치
    end: int

def _keyword_pattern(keyword: str) -> str:
    # 띄어쓰기가 달라도 똑같이 잡을 수 있게
    return r"\s*".join(re.escape(ch) for ch in keyword if not ch.isspace())

def _load_rules() -> dict[TagType, re.Pattern[str]]:
    raw = json.loads(_TAGS_FILE.read_text(encoding="utf-8"))
    rules = {}
    for tag_type in TagType:
        entry = raw[tag_type.value]
        keywords = sorted(entry["keywords"], key=len, reverse=True) # 긴 단어 먼저
        parts = [_keyword_pattern(k) for k in keywords] + entry["patterns"]
        # 단어가 하나도 없으면 아무것도 잡지 않는 패턴
        rules[tag_type] = re.compile("|".join(f"(?:{p})" for p in parts) or r"(?!)")
    return rules


_RULES = _load_rules()

def tag_sentence(sentence: Sentence) -> list[Tag]:
    # 문장에 들어 있는 주의 단어를 표시, 판정은 아님
    text = sentence.text
    tags: list[Tag] = []
    for tag_type in TagType:
        for match in _RULES[tag_type].finditer(text):
            tags.append(
                Tag(
                    type=tag_type,
                    text=match.group(),
                    start=sentence.start + match.start(),
                    end=sentence.start + match.end(),
                )
            )
        if tag_type is TagType.MANDATORY:
            # 의무 문구 안의 단어가 다른 태그로 잡히지 않도록
            text = _RULES[tag_type].sub(lambda m: " " * len(m.group()), text)
    return tags