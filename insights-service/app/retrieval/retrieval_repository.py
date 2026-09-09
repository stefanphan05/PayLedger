import re

from app.retrieval.search_result import SearchResult, SearchStrategy
from app.shared.database import DatabaseConnectionFactory
from app.shared.enums import SourceType

from pgvector.utils import Vector

class RetrievalRepository:
    """Every read of the chunks table."""

    # Correlation ids are 1-64 characters of letters, digits, dash and underscore
    # (ADR-0011); transaction ids are UUIDs. Requiring at least one digit is what
    # separates an identifier from an ordinary word — "demo-5" and a UUID both
    # qualify, "transaction" and "happened" do not.
    IDENTIFIER_CANDIDATE_PATTERN = re.compile(
        r"\b(?=[A-Za-z0-9_-]*\d)[A-Za-z0-9][A-Za-z0-9_-]{3,63}\b"
    )

    def __init__(self, connection_factory: DatabaseConnectionFactory):
        self.connection_factory = connection_factory

    def find_chunks_by_identifiers_in_question(
        self,
        question: str
    ) -> list[SearchResult]:
        """Find log flows whose correlation or transaction id appears in the question.

       This is a plain exact match, not a search. A UUID carries no meaning to
       embed, so semantic similarity cannot reliably find one — the vectors for
       two different ids are essentially noise. Any question naming a specific
       payment is answered by a lookup instead.
       """
        candidate_identifiers = self.IDENTIFIER_CANDIDATE_PATTERN.findall(question)
        if not candidate_identifiers:
            return []

        with self.connection_factory.create_connection() as database_connection:
            with database_connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT id, source_type, source_ref, content
                    FROM chunks
                    WHERE correlation_id = ANY(%s) OR transaction_id = ANY(%s)
                    ORDER BY id
                    """,
                    (candidate_identifiers, candidate_identifiers),
                )
                return [
                    SearchResult(
                        id=chunk_id,
                        source_type=SourceType(source_type),
                        source_ref=source_ref,
                        content=content,
                        search_strategy=SearchStrategy.EXACT,
                    )
                    for chunk_id, source_type, source_ref, content in cursor.fetchall()
                ]

    def find_nearest_chunks_by_embedding(
        self,
        question_embedding: list[float],
        maximum_results: int,
        excluded_chunk_ids: list[int]
    ) -> list[SearchResult]:
        """Find the chunks closest in meaning to the question.

       '<=>' is pgvector's cosine distance operator: 0 means identical, 2 means
       opposite, so ascending order puts the closest match first.

       excluded_chunk_ids keeps chunks already found by exact match from being
       returned a second time.
       """
        with self.connection_factory.create_connection() as database_connection:
            with database_connection.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT id, source_type, source_ref, content,
                           embedding <=> %s AS distance
                    FROM chunks
                    WHERE NOT (id = ANY(%s))
                    ORDER BY distance
                        LIMIT %s
                    """,
                    (Vector(question_embedding), excluded_chunk_ids, maximum_results),
                )
                return [
                    SearchResult(
                        id=chunk_id,
                        source_type=SourceType(source_type),
                        source_ref=source_ref,
                        content=content,
                        search_strategy=SearchStrategy.SEMANTIC,
                        distance=distance,
                    )
                    for chunk_id, source_type, source_ref, content, distance
                    in cursor.fetchall()
                ]