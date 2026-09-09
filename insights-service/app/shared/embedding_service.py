from sentence_transformers import SentenceTransformer

from app.shared.config import EMBEDDING_MODEL

class EmbeddingService:
    """Turn text into vectors"""

    MAXIMUM_WORDS_BEFORE_TRUNCATION = 190

    def __init__(self):
        # Loads the ~90 MB model into memory. Slow enough that this class is
        # constructed once and shared, never per request.
        self.model = SentenceTransformer(EMBEDDING_MODEL)

    def embed_text(self, text: str) -> list[float]:
        """Turn a single string into a 384-dimension vector."""
        vector = self.model.encode([text], normalize_embeddings=True)[0]
        return vector.tolist()

    def embed_batch(self, texts: list[str]) -> list[list[float]]:
        """Turn a list of strings into vectors, for bulk database inserts."""
        vectors = self.model.encode(texts, normalize_embeddings=True)
        return [vector.tolist() for vector in vectors]