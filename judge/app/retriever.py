"""문장과 비슷한 적발 사례를 cases 테이블에서 찾는다."""

from dataclasses import dataclass
from datetime import date

import psycopg
from pgvector import Vector
from pgvector.psycopg import register_vector

from app.config import DATABASE_URL
from app.embedder import embed

TOP_K = 3  # 문장 하나에 보여 줄 사례 수
MIN_SCORE = 0.6  # 이 값보다 덜 비슷하면 사례로 쓰지 않는다 (관계없는 문장끼리도 0.4~0.5 가 나옴)
class RetrievalError(Exception):
    """사례 DB 에 접속하거나 검색하지 못했을 때."""


@dataclass(frozen=True)
class CaseHit:
    id: str
    phrase: str
    violation_type: str
    article: str
    product_type: str
    case_date: date
    source_title: str
    source_url: str
    score: float  # 코사인 유사도 (1 에 가까울수록 비슷함)


# 같은 문구(띄어쓰기만 다른 것 포함)가 여러 번 나오면 가장 최근 것 하나만 쓴다
_SEARCH = """
SELECT id, phrase, violation_type, article, product_type, case_date, source_title, source_url, score
FROM (
    SELECT DISTINCT ON (replace(phrase, ' ', '')) *, 1 - (embedding <=> %(vector)s) AS score
    FROM cases
    ORDER BY replace(phrase, ' ', ''), case_date DESC
) AS latest
WHERE score >= %(min_score)s
ORDER BY score DESC
LIMIT %(top_k)s
"""


def search_cases(texts: list[str], top_k: int = TOP_K, min_score: float = MIN_SCORE) -> list[list[CaseHit]]:
    """문장마다 비슷한 사례 목록을 돌려준다. 결과의 순서는 texts 와 같다."""
    if not texts:
        return []
    vectors = embed(texts)
    try:
        with psycopg.connect(DATABASE_URL) as conn:
            register_vector(conn)
            return [
                [
                    CaseHit(*row)
                    for row in conn.execute(
                        _SEARCH, {"vector": Vector(vector), "min_score": min_score, "top_k": top_k}
                    )
                ]
                for vector in vectors
            ]
    except psycopg.Error as e:
        raise RetrievalError(f"사례 검색 실패: {e}") from e


def kb_version() -> str:
    """사례 DB 의 기준일 (가장 최근 사례의 날짜). 사례가 없으면 'none'."""
    try:
        with psycopg.connect(DATABASE_URL) as conn:
            latest = conn.execute("SELECT max(case_date) FROM cases").fetchone()[0]
    except psycopg.Error as e:
        raise RetrievalError(f"사례 DB 접속 실패: {e}") from e
    return latest.isoformat() if latest else "none"