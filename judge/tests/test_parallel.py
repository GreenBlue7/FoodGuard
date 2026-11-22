"""동시에 불러도 차례로 부를 때와 결과가 같은지 확인한다 (Gemini·DB 없이, 비용 0).

가짜 Gemini 는 프롬프트 내용만 보고 답을 정하고, 매번 무작위로 조금씩 늦게 답한다.
그래서 동시에 부르면 답이 오는 순서가 매번 달라진다. 그래도 동시 개수 1 과 4 의 결과가 같아야 한다.
"""

import random
import re
import threading
import time

import pytest

import app.ocr as ocr
import app.parallel as parallel
import app.pipeline as pipeline
from app.llm import LlmError
from app.llm_schema import (
    ConfirmItem,
    ConfirmOutput,
    Downgrade,
    FactOutput,
    LlmOutput,
    LlmVerdict,
    Recheck,
    RecheckItem,
    RecheckOutput,
)
from app.ocr import OcrLine
from app.schemas import JudgeRequest

from test_image import HEIGHT, TRUTH, FakeReader, make_image


def _wait():
    time.sleep(random.uniform(0, 0.02))


# ---------------------------------------------------------------- 도우미


def test_ordered_map_keeps_order(monkeypatch):
    monkeypatch.setattr(parallel, "PARALLEL", 4)

    def slow_square(n):
        _wait()
        return n * n

    assert parallel.ordered_map(slow_square, range(20)) == [n * n for n in range(20)]


def test_ordered_map_runs_at_most_parallel_at_once(monkeypatch):
    monkeypatch.setattr(parallel, "PARALLEL", 3)
    running, most = 0, 0
    lock = threading.Lock()

    def work(_):
        nonlocal running, most
        with lock:
            running += 1
            most = max(most, running)
        time.sleep(0.02)
        with lock:
            running -= 1

    parallel.ordered_map(work, range(12))
    assert most == 3


def test_ordered_map_stops_on_error_and_cancels_waiting_calls(monkeypatch):
    monkeypatch.setattr(parallel, "PARALLEL", 2)
    started = []

    def work(n):
        started.append(n)
        if n == 0:
            raise LlmError("가짜 실패")
        time.sleep(0.05)
        return n

    with pytest.raises(LlmError):
        parallel.ordered_map(work, range(20))
    assert len(started) < 20  # 대기 중이던 호출은 보내지 않는다


def test_parallel_1_calls_in_order(monkeypatch):
    monkeypatch.setattr(parallel, "PARALLEL", 1)
    seen = []
    parallel.ordered_map(seen.append, range(5))
    assert seen == [0, 1, 2, 3, 4]


# ---------------------------------------------------------------- 글자 읽기


def _read(monkeypatch, workers, fail=frozenset()):
    monkeypatch.setattr(parallel, "PARALLEL", workers)
    reader = FakeReader([TRUTH], [HEIGHT], fail)

    def slow_reader(piece, image_index, top):
        _wait()
        return reader(piece, image_index, top)

    monkeypatch.setattr(ocr, "read_piece", slow_reader)
    return ocr.read_image(make_image(), 0), reader


@pytest.mark.parametrize("fail", [frozenset(), {(0, 1)}])
def test_read_image_same_result_in_parallel(monkeypatch, fail):
    alone, _ = _read(monkeypatch, 1, fail)
    for _ in range(5):
        together, reader = _read(monkeypatch, 4, fail)
        assert together == alone
        assert sorted(reader.asked) == sorted(set(reader.asked))  # 조각마다 한 번씩만 읽는다


# ---------------------------------------------------------------- 판정


PRODUCT = {
    "type": "HEALTH_FUNCTIONAL",
    "names": ["홍삼정"],
    "approved_functions": ["면역력 증진"],
    "ingredients": ["홍삼농축액"],
    "standards": "진세노사이드 Rg1, Rb1 및 Rg3의 합 3mg",
}
# 위반 문장(감기), 후기 문장(위험 신호 → 다시 보기), 숫자 주장(사실 대조), 평범한 문장을 섞는다
AD = "\n".join(
    [
        f"{n}번째 감기 걱정 끝 문장입니다" if n % 3 == 0
        else f"{n}번째 먹어보니 피곤함이 줄었어요 후기입니다" if n % 3 == 1
        else f"{n}번째 홍삼 농축액 100% 문장입니다"
        for n in range(1, 41)
    ]
)


def _ids(prompt: str) -> list[str]:
    return re.findall(r"^\[(s\d+)\]", prompt, flags=re.M)


def fake_generate_json(prompt, schema, model=None):
    """프롬프트만 보고 답을 정한다. 순서가 결과에 드러나도록, 답의 이유에 프롬프트의 첫 문장 번호를 넣는다."""
    _wait()
    ids = _ids(prompt.split("# 검토할 문장")[-1])
    first = ids[0] if ids else "-"
    if schema is LlmOutput:
        verdicts = []
        for sentence_id in ids:
            line = re.search(rf"^\[{sentence_id}\] (.*)$", prompt, flags=re.M).group(1)
            if "감기" in line:
                verdicts.append(LlmVerdict(sentence_id=sentence_id, status="VIOLATION", step="DISEASE",
                                           reason="질병 예방", problem_text="감기 걱정 끝", revision="고쳐 쓴 문장"))
            else:
                verdicts.append(LlmVerdict(sentence_id=sentence_id, status="OK", step="NONE", reason="정상",
                                           problem_text="", revision=""))
        return LlmOutput(verdicts=verdicts)
    if schema is ConfirmOutput:
        # 짝수 번째 문장만 낮춘다. 같은 문장이 두 번(순서를 바꾼 두 번째 묻기) 답해지므로, 먼저 반영된 쪽의 이유가 남는다
        return ConfirmOutput(items=[
            ConfirmItem(sentence_id=i, decision=Downgrade.QUALITY_SERVICE if int(i[1:]) % 2 == 0 else Downgrade.KEEP,
                        reason=f"첫 문장 {first}")
            for i in ids
        ])
    if schema is RecheckOutput:
        # 후기 문장만 검토 필요로 올린다. 나머지(100% 문장)는 정상으로 남아 사실 대조로 간다
        return RecheckOutput(items=[
            RecheckItem(sentence_id=i, reason=f"첫 문장 {first}",
                        decision=Recheck.TESTIMONIAL if re.search(rf"^\[{i}\] .*후기", prompt, flags=re.M) else Recheck.OK)
            for i in ids
        ])
    if schema is FactOutput:
        return FactOutput(items=[])
    raise AssertionError(schema)


def _judge(monkeypatch, workers):
    monkeypatch.setattr(parallel, "PARALLEL", workers)
    monkeypatch.setattr(pipeline, "BATCH_SIZE", 4)  # 묶음을 여러 개로 만든다
    monkeypatch.setattr(pipeline, "generate_json", fake_generate_json)
    monkeypatch.setattr("app.fact_check.generate_json", fake_generate_json)
    monkeypatch.setattr(pipeline, "search_cases", lambda texts: [[] for _ in texts])
    monkeypatch.setattr(pipeline, "kb_version", lambda: "2025-11-06")
    response = pipeline.run_judge(JudgeRequest(request_id="t", mode="C", product=PRODUCT, ad_text=AD))
    return response.model_dump(exclude={"elapsed_ms"})


def test_run_judge_same_result_in_parallel(monkeypatch):
    alone = _judge(monkeypatch, 1)
    statuses = {r["status"] for r in alone["results"]}
    assert {"VIOLATION", "REVIEW", "OK"} <= {getattr(s, "value", s) for s in statuses}  # 세 단계가 모두 일했다
    for _ in range(5):
        assert _judge(monkeypatch, 4) == alone


# ---------------------------------------------------------------- Gemini 연결


@pytest.mark.parametrize("provider", ["gemini", "openrouter"])
def test_first_calls_at_once_make_one_client(monkeypatch, provider):
    """동시에 처음 불러도 클라이언트는 하나만 만든다. 둘 이상이면 남지 못한 쪽이 닫히면서 요청이 실패한다."""
    import app.llm as llm

    made = []

    class SlowClient:
        def __init__(self, *args, **kwargs):
            time.sleep(0.05)  # 만드는 사이에 다른 스레드가 끼어들 틈을 준다
            made.append(self)

    monkeypatch.setattr(parallel, "PARALLEL", 8)
    monkeypatch.setattr(llm, "GEMINI_API_KEY", "test-key")
    monkeypatch.setattr(llm, "OPENROUTER_API_KEY", "test-key")
    monkeypatch.setattr(llm, "GEMINI_MODEL", "gemini-3.1-flash-lite")
    if provider == "gemini":
        monkeypatch.setattr(llm, "_gemini_client", None)
        monkeypatch.setattr(llm.genai, "Client", SlowClient)
        get = llm._get_gemini
    else:
        monkeypatch.setattr(llm, "_openrouter_client", None)
        monkeypatch.setattr(llm.httpx, "Client", SlowClient)
        get = llm._get_openrouter

    clients = parallel.ordered_map(lambda _: get(), range(8))
    assert len(made) == 1
    assert all(client is made[0] for client in clients)
