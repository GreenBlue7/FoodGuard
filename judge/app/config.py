import os

from dotenv import find_dotenv, load_dotenv

load_dotenv(find_dotenv())

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
GEMINI_MODEL = os.getenv("GEMINI_MODEL", "")
DATABASE_URL = os.getenv("DATABASE_URL", "")