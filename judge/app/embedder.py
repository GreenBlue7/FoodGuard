"""문장을 벡터로 바꾼다 (bge-m3, 1024차원)."""

from functools import lru_cache

MODEL_NAME = "BAAI/bge-m3"
DIMENSION = 1024


@lru_cache(maxsize=1)
def _model():
    # 모델을 불러오는 데 몇 초 걸리므로, 처음 쓸 때 한 번만 불러온다
    from sentence_transformers import SentenceTransformer

    return SentenceTransformer(MODEL_NAME)


def embed(texts: list[str]) -> list[list[float]]:
    """문장마다 길이 1 로 맞춘 벡터를 돌려준다 (내적이 곧 코사인 유사도)."""
    vectors = _model().encode(texts, normalize_embeddings=True)
    return [vector.tolist() for vector in vectors]