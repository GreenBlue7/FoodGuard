"""Gemini 호출. LLM_PROVIDER 로 Google 직접(gemini) 과 OpenRouter 경유(openrouter) 중 하나를 고른다.

두 길 모두 같은 Gemini 모델을 부르고, 같은 schema 모양의 답을 돌려준다. 판정 로직은 어느 길인지 모른다.
"""

import base64
import json
import os
import threading
import time
from typing import TypeVar

import httpx
from google import genai
from google.genai import errors, types
from pydantic import BaseModel, ValidationError

from app.config import GEMINI_API_KEY, GEMINI_MODEL, LLM_PROVIDER, OPENROUTER_API_KEY

T = TypeVar("T", bound=BaseModel)
Image = tuple[bytes, str]  # (이미지 내용, "image/jpeg" 같은 형식)

_RETRY_CODES = {408, 429, 500, 502, 503, 504}  # 시간 초과, 한도 초과, 서버 오류, 일시적 과부하
_MAX_TRIES = 3
_TIMEOUT_SECONDS = 180  # 연결이 끊겨도 무한정 기다리지 않도록 한 번 호출에 최대 3분
OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

_gemini_client: genai.Client | None = None
_openrouter_client: httpx.Client | None = None
# 여러 스레드가 동시에 처음 부를 때 클라이언트를 하나만 만들도록 잠근다.
# 둘 이상 만들어지면 전역 변수에 남지 못한 클라이언트가 정리되면서, 그것으로 보내던 요청이 "client has been closed" 로 실패한다
_client_lock = threading.Lock()
_usage_lock = threading.Lock()  # 동시에 부를 때 토큰 기록 줄이 섞이지 않게


class LlmError(Exception):
    """Gemini 호출이 끝내 실패했을 때."""


class _CallError(Exception):
    """두 길의 HTTP 오류를 같은 모양으로 바꾼 것. code 로 다시 시도할지 정한다."""

    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _get_gemini() -> genai.Client:
    global _gemini_client
    with _client_lock:
        if _gemini_client is None:
            if not GEMINI_API_KEY or not GEMINI_MODEL:
                raise LlmError(".env에 GEMINI_API_KEY, GEMINI_MODEL이 없습니다.")
            _gemini_client = genai.Client(
                api_key=GEMINI_API_KEY, http_options=types.HttpOptions(timeout=_TIMEOUT_SECONDS * 1000)
            )
        return _gemini_client


def _get_openrouter() -> httpx.Client:
    global _openrouter_client
    with _client_lock:
        if _openrouter_client is None:
            if not OPENROUTER_API_KEY or not GEMINI_MODEL:
                raise LlmError(".env에 OPENROUTER_API_KEY, GEMINI_MODEL이 없습니다.")
            _openrouter_client = httpx.Client(
                timeout=_TIMEOUT_SECONDS, headers={"Authorization": f"Bearer {OPENROUTER_API_KEY}"}
            )
        return _openrouter_client


def openrouter_model(model: str) -> str:
    # OpenRouter 는 회사 이름을 앞에 붙인다: gemini-3.6-flash -> google/gemini-3.6-flash
    return model if "/" in model else f"google/{model}"


def json_schema(schema: type[BaseModel]) -> dict:
    """pydantic 모델을 OpenRouter 에 보낼 JSON Schema 로 바꾼다.

    $defs/$ref 는 펼쳐서 넣는다 (Gemini 로 넘길 때 $ref 를 못 읽는 경우를 피함).
    title, default 같은 설명용 칸은 뺀다. properties 안의 이름은 칸 이름이라 건드리지 않는다.
    """
    raw = schema.model_json_schema()
    defs = raw.pop("$defs", {})

    def walk(node):
        if isinstance(node, list):
            return [walk(item) for item in node]
        if not isinstance(node, dict):
            return node
        if "$ref" in node:
            return walk(defs[node["$ref"].split("/")[-1]])
        out = {}
        for key, value in node.items():
            if key in ("title", "default"):
                continue
            if key == "properties":
                out[key] = {name: walk(sub) for name, sub in value.items()}
            else:
                out[key] = walk(value)
        return out

    return walk(raw)


def _log_usage(model: str, input_tokens: int, output_tokens: int, thinking_tokens: int, cost: float | None) -> None:
    # 시험용: 호출마다 토큰 수를 한 줄씩 남겨 실제 비용을 계산한다 (LLM_USAGE_LOG 가 있을 때만)
    path = os.environ.get("LLM_USAGE_LOG")
    if not path:
        return
    record = {
        "time": time.time(),
        "model": model,
        "input": input_tokens,
        "output": output_tokens,
        "thinking": thinking_tokens,
    }
    if cost is not None:
        record["cost"] = cost  # OpenRouter 가 알려 주는 실제 청구 금액($)
    with _usage_lock, open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(record) + "\n")


def _ask_gemini(prompt: str, schema: type[T], model: str, images: list[Image]) -> str:
    config = types.GenerateContentConfig(
        temperature=0,  # 같은 입력에 같은 답
        response_mime_type="application/json",
        response_schema=schema,
        automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
    )
    contents = [types.Part.from_bytes(data=data, mime_type=mime) for data, mime in images] + [prompt]
    try:
        response = _get_gemini().models.generate_content(
            model=model, contents=contents if images else prompt, config=config
        )
    except errors.APIError as e:
        raise _CallError(e.code, e.message or "") from e
    usage = response.usage_metadata
    if usage is not None:
        _log_usage(
            model,
            usage.prompt_token_count or 0,
            usage.candidates_token_count or 0,
            usage.thoughts_token_count or 0,
            None,
        )
    return response.text or ""


def _openrouter_body(prompt: str, schema: type[BaseModel], model: str, images: list[Image]) -> dict:
    if images:
        content = [
            {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{base64.b64encode(data).decode()}"}}
            for data, mime in images
        ] + [{"type": "text", "text": prompt}]
    else:
        content = prompt
    return {
        "model": openrouter_model(model),
        "messages": [{"role": "user", "content": content}],
        "temperature": 0,  # 같은 입력에 같은 답
        "response_format": {
            "type": "json_schema",
            "json_schema": {"name": schema.__name__, "strict": True, "schema": json_schema(schema)},
        },
        # JSON 형식 강제를 지원하는 곳으로만 보낸다
        "provider": {"require_parameters": True},
        # reasoning 은 보내지 않는다. OpenRouter 기본값이 Google 기본값과 같다
        # (3.6 Flash = medium, 3.1 Flash-Lite = minimal). 지금까지 평가도 Google 기본값으로 했다.
    }


def _ask_openrouter(prompt: str, schema: type[T], model: str, images: list[Image]) -> str:
    response = _get_openrouter().post(OPENROUTER_URL, json=_openrouter_body(prompt, schema, model, images))
    try:
        data = response.json()
    except ValueError:
        data = {}
    error = data.get("error") if isinstance(data, dict) else None
    if response.status_code != 200 or error:
        code = response.status_code
        if error and response.status_code == 200:
            # 200 인데 본문에 오류가 오는 경우 (중간에 Google 쪽이 실패)
            try:
                code = int(error.get("code", 502))
            except (TypeError, ValueError):
                code = 502
        message = (error or {}).get("message") or response.text[:200]
        raise _CallError(code, message)

    usage = data.get("usage") or {}
    completion = usage.get("completion_tokens") or 0
    thinking = (usage.get("completion_tokens_details") or {}).get("reasoning_tokens") or 0
    # completion_tokens 에 생각 토큰이 들어 있어서 빼고 남긴다 (Google 기록과 같은 기준)
    _log_usage(model, usage.get("prompt_tokens") or 0, max(completion - thinking, 0), thinking, usage.get("cost"))

    choices = data.get("choices") or []
    if not choices:
        raise _CallError(502, "OpenRouter 응답에 답이 없습니다.")
    return choices[0].get("message", {}).get("content") or ""


def generate_json(prompt: str, schema: type[T], model: str | None = None, images: list[Image] | None = None) -> T:
    # 프롬프트(와 이미지)를 보내고, schema 모양의 JSON 응답을 받아 검증해서 돌려준다.
    if LLM_PROVIDER == "openrouter":
        ask = _ask_openrouter
    elif LLM_PROVIDER == "gemini":
        ask = _ask_gemini
    else:
        raise LlmError(f"LLM_PROVIDER 는 gemini 또는 openrouter 여야 합니다 (지금: {LLM_PROVIDER}).")
    name = model or GEMINI_MODEL
    for attempt in range(1, _MAX_TRIES + 1):
        try:
            return schema.model_validate_json(ask(prompt, schema, name, images or []))
        except _CallError as e:
            if e.code not in _RETRY_CODES or attempt == _MAX_TRIES:
                raise LlmError(f"Gemini 호출 실패 ({e.code}): {e.message}") from e
            time.sleep(2 * attempt)  # 2초, 4초 기다렸다가 다시 시도
        except httpx.TransportError as e:
            # 연결이 끊기는 일시적 오류 (예: Connection reset by peer, 시간 초과)
            if attempt == _MAX_TRIES:
                raise LlmError(f"Gemini 연결 실패: {e}") from e
            time.sleep(15 * attempt)  # 네트워크가 잠깐 끊긴 경우를 위해 15초, 30초 기다림
        except ValidationError as e:
            if attempt == _MAX_TRIES:
                raise LlmError("Gemini 응답이 약속한 형식이 아닙니다.") from e
    raise LlmError("Gemini 호출 실패")
