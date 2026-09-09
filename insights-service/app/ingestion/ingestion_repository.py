from app.ingestion.chunk import Chunk
from app.shared.database import DatabaseConnectionFactory

class IngestionRepository:
    """Every write to the chunks table

    Separate from RetrievalRepository on purpose. The two features touch the same
    table but change for entirely different reasons - this one changes when the
    shape of what gets stored changes, the other when search changes
    """

    def __init__(self, connection_factory: DatabaseConnectionFactory):
        self.connection_factory = connection_factory

    def delete_all_chunks(self) -> None:
        """Empty the table before a re-ingestion

        Ingestion rebuilds from scratch each run. Incremental updates are not
        worth the complexity while ingestion is a manual step
        """
        with self.connection_factory.create_connection() as database_connection:
            with database_connection.cursor() as cursor:
                cursor.execute("TRUNCATE chunks")

            database_connection.commit()

    def save_all_chunks(self, chunks: list[Chunk], embedding_vectors: list[list[float]]) -> None:
        """Insert every chunk together with its embedding, in one batch"""
        rows_to_insert = [
            (
                chunk.source_type.value,
                chunk.source_ref,
                chunk.correlation_id,
                chunk.transaction_id,
                chunk.content,
                embedding_vector
            )
            for chunk, embedding_vector in zip(chunks, embedding_vectors)
        ]

        with self.connection_factory.create_connection() as database_connection:
            with database_connection.cursor() as cursor:
                cursor.executemany(
                    """
                    INSERT INTO chunks
                    (source_type, source_ref, correlation_id,
                     transaction_id, content, embedding)
                    VALUES (%s, %s, %s, %s, %s, %s)
                    """,
                    rows_to_insert,
                )
            database_connection.commit()

    def count_chunks_by_source_type(self) -> dict[str, int]:
        """Row counts per source type, to sanity-check an ingestion run."""
        with self.connection_factory.create_connection() as database_connection:
            with database_connection.cursor() as cursor:
                cursor.execute(
                    "SELECT source_type, count(*) FROM chunks GROUP BY source_type"
                )
                return dict(cursor.fetchall())
