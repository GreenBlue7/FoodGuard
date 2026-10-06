from app.splitter import split_sentences
from app.tagger import TagType, tag_sentence


def tags(text: str) -> list[tuple[TagType, str]]:
    (sentence,) = split_sentences(text)
    return [(t.type, t.text) for t in tag_sentence(sentence)]


def test_질병_단어를_찾고_위치는_원문_기준이다():
    ad = "배송비 무료\n환절기 감기 걱정 끝!"
    sentence = split_sentences(ad)[1]
    (tag,) = tag_sentence(sentence)
    assert (tag.type, tag.text) == (TagType.DISEASE, "감기")
    assert ad[tag.start : tag.end] == "감기"


def test_주의_단어가_없으면_빈_목록이다():
    assert tags("하루 한 포로 활력 충전") == []


def test_의무_문구_안의_단어는_다른_태그로_잡히지_않는다():
    assert tags("본 제품은 질병의 예방 및 치료를 위한 의약품이 아닙니다.") == [
        (TagType.MANDATORY, "질병의 예방 및 치료를 위한 의약품이 아닙니다")
    ]


def test_의무_문구_뒤에_붙은_위반_표현은_잡는다():
    found = tags("본 제품은 건강기능식품이 아닙니다 그래도 당뇨 치료에 좋아요")
    assert (TagType.MANDATORY, "건강기능식품이 아닙니다") in found
    assert (TagType.DISEASE, "당뇨") in found
    assert (TagType.DRUG, "치료") in found


def test_띄어쓰기가_달라도_잡는다():
    assert (TagType.TESTIMONIAL, "먹고나서") in tags("먹고나서 달라졌어요")
    assert (TagType.EXAGGERATION, "부작용없") in tags("부작용없는 천연 원료")


def test_암은_낱말일_때만_잡는다():
    assert (TagType.DISEASE, "암 예방") in tags("암 예방에 탁월")
    assert (TagType.DISEASE, "위암") in tags("위암 환자도 OK")
    assert tags("암시하는 바가 크다") == []
    assert tags("명암이 뚜렷") == []


def test_기간_표현은_체험기로_잡는다():
    assert (TagType.TESTIMONIAL, "3일 만에") in tags("먹고 3일 만에 효과")
    assert (TagType.TESTIMONIAL, "2주만에") in tags("2주만에 변화")