import json
import os
import time
from typing import TypeVar

import httpx
from google import genai
from google.genai import errors, types
from pydantic import BaseModel, ValidationError

from app.config import GEMINI_API_KEY, GEMINI_MODEL

T = TypeVar("T", bound=BaseModel)

_RETRY_CODES = {429, 500, 503} # 한도 초과, 서버 오류, 일시적 과부화
_MAX_TRIES = 3

_client: genai.Client | None = None

class LlmError(Exception):
    """Gemini 호출이 끝내 실패했을 때."""

def _get_client() -> genai.Client:
    global _client
    if _client is None:
        if not GEMINI_API_KEY or not GEMINI_MODEL:
            raise LlmError(".env에 GEMINI_API_KEY, GEMINI_MODEL이 없습니다.")
        # 연결이 끊겨도 무한정 기다리지 않도록 한 번 호출에 최대 3분
        _client = genai.Client(api_key=GEMINI_API_KEY, http_options=types.HttpOptions(timeout=180_000))
    return _client

def _log_usage(model: str, response) -> None:
    # 시험용: 호출마다 토큰 수를 한 줄씩 남겨 실제 비용을 계산한다 (LLM_USAGE_LOG 가 있을 때만)
    path = os.environ.get("LLM_USAGE_LOG")
    usage = response.usage_metadata
    if not path or usage is None:
        return
    record = {
        "time": time.time(),
        "model": model,
        "input": usage.prompt_token_count or 0,
        "output": usage.candidates_token_count or 0,
        "thinking": usage.thoughts_token_count or 0,
    }
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(record) + "\n")

def generate_json(prompt: str, schema: type[T], model: str | None = None) -> T:
    # 프롬포트를 보내고, schema 모양의 JSON 응답을 받아 검증해서 돌려준다.
    client = _get_client()
    config = types.GenerateContentConfig(
        temperature=0, # 같은 입력에 같은 답
        response_mime_type="application/json",
        response_schema=schema,
        automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
    )
    for attempt in range(1, _MAX_TRIES + 1):
        try:
            response = client.models.generate_content(
                model=model or GEMINI_MODEL, contents=prompt, config=config
            )
            _log_usage(model or GEMINI_MODEL, response)
            return schema.model_validate_json(response.text or "")
        except errors.APIError as e:
            if e.code not in _RETRY_CODES or attempt == _MAX_TRIES:
                raise LlmError(f"Gemini 호출 실패 ({e.code}): {e.message}") from e
            time.sleep(2 * attempt) # 2초, 4초 기다렸다가 다시 시도
        except httpx.TransportError as e:
            # 연결이 끊기는 일시적 오류 (예: Connection reset by peer)
            if attempt == _MAX_TRIES:
                raise LlmError(f"Gemini 연결 실패: {e}") from e
            time.sleep(15 * attempt) # 네트워크가 잠깐 끊긴 경우를 위해 15초, 30초 기다림
        except ValidationError as e:
            if attempt == _MAX_TRIES:
                raise LlmError("Gemini 응답이 약속한 형식이 아닙니다.") from e
    raise LlmError("Gemini 호출 실패")
