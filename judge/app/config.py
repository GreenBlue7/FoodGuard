import os

from dotenv import find_dotenv, load_dotenv

load_dotenv(find_dotenv())

# Gemini 를 어디로 부를지: gemini = Google 에 직접 (개발용 무료 키), openrouter = OpenRouter 경유 (시연·평가용 크레딧)
LLM_PROVIDER = os.getenv("LLM_PROVIDER", "gemini").strip().lower()
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
OPENROUTER_API_KEY = os.getenv("OPENROUTER_API_KEY", "")
# 모델 이름은 어느 쪽이든 Google 이름 그대로 쓴다 (예: gemini-3.1-flash-lite). OpenRouter 용 google/ 은 llm.py 가 붙인다
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "")
DATABASE_URL = os.getenv("DATABASE_URL", "")
