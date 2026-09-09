import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[3] / ".env")

DB_URL = os.getenv(
    "INSIGHTS_DB_URL",
    "postgresql://user:password@localhost:5434/insights_db",
)

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")

EMBEDDING_MODEL = "all-MiniLM-L6-v2"
EMBEDDING_DIMENSIONS = 384

ANSWER_MODEL = "gemini-3.5-flash"

LOG_FILE = os.getenv("LOG_FILE", "data/logs.jsonl")
DOCS_ROOT = os.getenv("DOCS_ROOT", "..")

TOP_K = 3