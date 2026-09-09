from app.retrieval.retrieval_repository import RetrievalRepository
from app.retrieval.search_result import SearchResult
from app.shared.config import TOP_K
from app.shared.embedding_service import EmbeddingService

class RetrievalService:
    """Finds the chunks most likely to answer a question.

   Uses two strategies together, because the two kinds of question this
   service handles are genuinely different problems:

       "why did transaction abc-123 fail?"   -> a lookup by identifier
       "why optimistic locking?"             -> a search by meaning

   An identifier cannot be found by meaning, and a concept cannot be found by
   exact match, so both run and their results are combined.
   """

    def __init__(
        self,
        retrieval_repository: RetrievalRepository,
        embedding_service: EmbeddingService,
    ):
        self.retrieval_repository = retrieval_repository
        self.embedding_service = embedding_service

    def retrieve_relevant_chunks(
        self,
        question: str,
        maximum_semantic_results: int = TOP_K
    ) -> list[SearchResult]:
        """Return exact-match results first, then the closest by meaning"""
        exact_match_results = (
            self.retrieval_repository.find_chunks_by_identifiers_in_question(question)
        )

        question_embedding = self.embedding_service.embed_text(question)

        semantic_results = self.retrieval_repository.find_nearest_chunks_by_embedding(
            question_embedding=question_embedding,
            maximum_results=maximum_semantic_results,
            excluded_chunk_ids=[result.id for result in exact_match_results],
        )

        return exact_match_results + semantic_results