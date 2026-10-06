"""적발 사례 CSV 를 읽어 임베딩과 함께 cases 테이블에 넣는다.

사용법 (judge 폴더에서):
    python -m scripts.load_cases "사례파일.csv"
같은 id 가 이미 있으면 새 내용으로 덮어쓴다.
"""

import csv
import sys
from datetime import date

import psycopg
from pgvector import Vector
from pgvector.psycopg import register_vector

from app.config import DATABASE_URL
from app.embedder import embed

# 검색용 사례는 2025년까지만. 2026년 사례는 평가용이라 넣지 않는다.
LAST_ALLOWED_DATE = date(2025, 12, 31)

_UPSERT = """
INSERT INTO cases (id, phrase, violation_type, article, product_type, note,
                   case_date, source_title, source_url, embedding)
VALUES (%(id)s, %(phrase)s, %(violation_type)s, %(article)s, %(product_type)s, %(note)s,
        %(date)s, %(source_title)s, %(source_url)s, %(embedding)s)
ON CONFLICT (id) DO UPDATE SET
    phrase = EXCLUDED.phrase, violation_type = EXCLUDED.violation_type,
    article = EXCLUDED.article, product_type = EXCLUDED.product_type, note = EXCLUDED.note,
    case_date = EXCLUDED.case_date, source_title = EXCLUDED.source_title,
    source_url = EXCLUDED.source_url, embedding = EXCLUDED.embedding
"""


def read_cases(path: str) -> list[dict]:
    with open(path, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    for number, row in enumerate(rows, start=2):  # 1번 줄은 칸 이름
        if not row["phrase"].strip() or not row["source_url"].strip():
            raise ValueError(f"{number}번 줄: phrase 또는 source_url 이 비어 있습니다.")
        if date.fromisoformat(row["date"]) > LAST_ALLOWED_DATE:
            raise ValueError(f"{number}번 줄: {row['date']} 은 2025년 이후라 넣을 수 없습니다.")
    return rows


def load_cases(conn: psycopg.Connection, rows: list[dict]) -> int:
    vectors = embed([row["phrase"] for row in rows])
    with conn.cursor() as cur:
        for row, vector in zip(rows, vectors):
            cur.execute(_UPSERT, {**row, "embedding": Vector(vector)})
    return len(rows)


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit('사용법: python -m scripts.load_cases "사례파일.csv"')
    rows = read_cases(sys.argv[1])
    print(f"{len(rows)}줄을 읽었습니다. 임베딩을 계산합니다...")
    with psycopg.connect(DATABASE_URL) as conn:
        register_vector(conn)
        count = load_cases(conn, rows)
        total = conn.execute("SELECT count(*) FROM cases").fetchone()[0]
    print(f"{count}줄을 넣었습니다. 지금 cases 테이블은 모두 {total}줄입니다.")


if __name__ == "__main__":
    main()