"""Gemini 호출을 여러 개 동시에 보낸다. 결과는 항상 넣은 순서대로 돌려준다.

호출은 대부분 Gemini 의 답을 기다리는 시간이라, 스레드 몇 개로 동시에 보내면 기다리는 시간이 겹쳐서 빨라진다.
결과 순서가 넣은 순서와 같으므로, 차례로 부를 때와 같은 결과를 같은 순서로 합칠 수 있다.
"""

import os
from collections.abc import Callable, Iterable
from concurrent.futures import ThreadPoolExecutor
from typing import TypeVar

# 한 번에 동시에 보낼 호출 수. 1 이면 하나씩 차례로 부른다 (저장된 응답을 순서대로 다시 쓸 때)
PARALLEL = max(1, int(os.environ.get("JUDGE_PARALLEL", "4")))

A = TypeVar("A")
B = TypeVar("B")


def ordered_map(function: Callable[[A], B], items: Iterable[A]) -> list[B]:
    """items 마다 function 을 동시에 부르고, 결과를 items 순서대로 모은다.

    하나가 오류를 내면 아직 시작하지 않은 호출은 취소하고 그 오류를 그대로 올린다 (차례로 부를 때처럼 멈춘다).
    """
    items = list(items)
    workers = min(PARALLEL, len(items))
    if workers <= 1:
        return [function(item) for item in items]
    pool = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="judge")
    try:
        futures = [pool.submit(function, item) for item in items]
        return [future.result() for future in futures]
    finally:
        # 정상이면 모두 끝난 뒤라 바로 닫힌다. 오류면 대기 중인 호출만 취소하고, 이미 보낸 호출이 끝나기를 기다린다
        pool.shutdown(wait=True, cancel_futures=True)
