"""OpenRouter 경유 호출을 인터넷·크레딧 없이 확인한다 (가짜 OpenRouter 서버를 끼운다)."""

import json

import httpx
import pytest

import app.llm as llm
from app.llm_schema import ConfirmOutput, LlmOutput

ANSWER = {
    "verdicts": [
        {
            "sentence_id": "s1",
            "status": "VIOLATION",
            "step": "DISEASE",
            "reason": "질병 예방 표현",
            "problem_text": "감기 걱정 끝",
            "revision": "",
        }
    ]
}


def ok_body(content: dict | str, usage: dict | None = None) -> dict:
    text = content if isinstance(content, str) else json.dumps(content, ensure_ascii=False)
    return {
        "choices": [{"message": {"role": "assistant", "content": text}}],
        "usage": usage
        or {
            "prompt_tokens": 1000,
            "completion_tokens": 300,
            "completion_tokens_details": {"reasoning_tokens": 100},
            "cost": 0.0012,
        },
    }


@pytest.fixture
def server(monkeypatch):
    """가짜 OpenRouter. replies 에 (상태 코드, 본문) 을 차례로 넣으면 그 순서로 답한다."""
    state = {"requests": [], "replies": []}

    def handle(request: httpx.Request) -> httpx.Response:
        state["requests"].append(request)
        status, body = state["replies"].pop(0)
        return httpx.Response(status, json=body)

    monkeypatch.setattr(llm, "LLM_PROVIDER", "openrouter")
    monkeypatch.setattr(llm, "OPENROUTER_API_KEY", "test-key")
    monkeypatch.setattr(llm, "GEMINI_MODEL", "gemini-3.1-flash-lite")
    monkeypatch.setattr(
        llm,
        "_openrouter_client",
        httpx.Client(transport=httpx.MockTransport(handle), headers={"Authorization": "Bearer test-key"}),
    )
    monkeypatch.setattr(llm.time, "sleep", lambda seconds: None)
    return state


def sent(state, index: int = 0) -> dict:
    return json.loads(state["requests"][index].content)


def test_answer_is_parsed_into_schema(server):
    server["replies"].append((200, ok_body(ANSWER)))
    output = llm.generate_json("프롬프트", LlmOutput)
    assert output.verdicts[0].sentence_id == "s1"
    assert output.verdicts[0].status.value == "VIOLATION"


def test_request_keeps_judge_settings(server):
    server["replies"].append((200, ok_body({"items": []})))
    llm.generate_json("프롬프트", ConfirmOutput, model="gemini-3.6-flash")
    body = sent(server)
    assert server["requests"][0].url == llm.OPENROUTER_URL
    assert server["requests"][0].headers["authorization"] == "Bearer test-key"
    assert body["model"] == "google/gemini-3.6-flash"
    assert body["temperature"] == 0
    assert body["messages"] == [{"role": "user", "content": "프롬프트"}]
    assert body["provider"] == {"require_parameters": True}
    assert "reasoning" not in body  # 생각 수준은 Google 기본값 그대로
    assert body["response_format"]["type"] == "json_schema"


def test_default_model_is_used_when_none_given(server):
    server["replies"].append((200, ok_body(ANSWER)))
    llm.generate_json("프롬프트", LlmOutput)
    assert sent(server)["model"] == "google/gemini-3.1-flash-lite"


def test_schema_has_no_refs_or_titles():
    schema = llm.json_schema(LlmOutput)
    text = json.dumps(schema)
    assert "$ref" not in text and "$defs" not in text
    assert '"title"' not in text and '"default"' not in text
    verdict = schema["properties"]["verdicts"]["items"]
    assert verdict["properties"]["status"] == {"enum": ["VIOLATION", "REVIEW", "OK", "HOLD"], "type": "string"}
    assert "sentence_id" in verdict["required"]


def test_field_named_title_is_kept():
    from pydantic import BaseModel

    class Book(BaseModel):
        title: str

    assert llm.json_schema(Book)["properties"] == {"title": {"type": "string"}}


def test_retries_on_temporary_error_then_succeeds(server):
    server["replies"] += [(502, {"error": {"code": 502, "message": "upstream"}}), (200, ok_body(ANSWER))]
    output = llm.generate_json("프롬프트", LlmOutput)
    assert output.verdicts[0].sentence_id == "s1"
    assert len(server["requests"]) == 2


def test_error_inside_200_is_retried(server):
    server["replies"] += [(200, {"error": {"code": 503, "message": "overloaded"}}), (200, ok_body(ANSWER))]
    llm.generate_json("프롬프트", LlmOutput)
    assert len(server["requests"]) == 2


def test_bad_request_fails_without_retry(server):
    server["replies"].append((400, {"error": {"code": 400, "message": "bad schema"}}))
    with pytest.raises(llm.LlmError, match="400"):
        llm.generate_json("프롬프트", LlmOutput)
    assert len(server["requests"]) == 1


def test_out_of_credit_fails_without_retry(server):
    server["replies"].append((402, {"error": {"code": 402, "message": "Insufficient credits"}}))
    with pytest.raises(llm.LlmError, match="402"):
        llm.generate_json("프롬프트", LlmOutput)
    assert len(server["requests"]) == 1


def test_broken_json_is_retried_then_fails(server):
    server["replies"] += [(200, ok_body("{not json"))] * 3
    with pytest.raises(llm.LlmError, match="형식"):
        llm.generate_json("프롬프트", LlmOutput)
    assert len(server["requests"]) == 3


def test_usage_log_separates_thinking_and_keeps_cost(server, tmp_path, monkeypatch):
    log = tmp_path / "usage.jsonl"
    monkeypatch.setenv("LLM_USAGE_LOG", str(log))
    server["replies"].append((200, ok_body(ANSWER)))
    llm.generate_json("프롬프트", LlmOutput, model="gemini-3.6-flash")
    record = json.loads(log.read_text().strip())
    assert record["model"] == "gemini-3.6-flash"
    assert (record["input"], record["output"], record["thinking"]) == (1000, 200, 100)
    assert record["cost"] == 0.0012


def test_images_are_sent_before_prompt(server):
    server["replies"].append((200, ok_body(ANSWER)))
    llm.generate_json("이미지 속 글자", LlmOutput, images=[(b"\xff\xd8jpeg", "image/jpeg")])
    content = sent(server)["messages"][0]["content"]
    assert content[0]["type"] == "image_url"
    assert content[0]["image_url"]["url"].startswith("data:image/jpeg;base64,")
    assert content[1] == {"type": "text", "text": "이미지 속 글자"}


def test_missing_key_is_reported(monkeypatch):
    monkeypatch.setattr(llm, "LLM_PROVIDER", "openrouter")
    monkeypatch.setattr(llm, "OPENROUTER_API_KEY", "")
    monkeypatch.setattr(llm, "_openrouter_client", None)
    with pytest.raises(llm.LlmError, match="OPENROUTER_API_KEY"):
        llm.generate_json("프롬프트", LlmOutput)


def test_unknown_provider_is_reported(monkeypatch):
    monkeypatch.setattr(llm, "LLM_PROVIDER", "claude")
    with pytest.raises(llm.LlmError, match="LLM_PROVIDER"):
        llm.generate_json("프롬프트", LlmOutput)
