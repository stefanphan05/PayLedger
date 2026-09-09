from pathlib import Path

from app.ingestion.chunk import Chunk
from app.ingestion.ingestion_repository import IngestionRepository
from app.ingestion.log_chunker import LogChunker
from app.ingestion.markdown_chunker import MarkdownChunker
from app.shared.config import DOCS_ROOT, LOG_FILE
from app.shared.embedding_service import EmbeddingService

class IngestionService:
    """Builds the searchable corpus: read sources, chunk. embed, store"""
    def __init__(
        self,
        ingestion_repository: IngestionRepository,
        embedding_service: EmbeddingService,
        log_chunker: LogChunker,
        markdown_chunker: MarkdownChunker,
    ):
        self.ingestion_repository = ingestion_repository
        self.embedding_service = embedding_service
        self.log_chunker = log_chunker
        self.markdown_chunker = markdown_chunker

    def ingest_all_sources(self) -> dict[str, int]:
        """Re-ingest everything from scratch. Returns counts per source type"""
        chunks = self._collect_chunks_from_all_sources()
        if not chunks:
            raise RuntimeError("nothing to ingest - check LOG_FILE and DOCS_ROOT")

        self._warn_about_chunks_that_will_be_truncated(chunks)

        print(f"embedding {len(chunks)} chunks...")
        embedding_vectors = self.embedding_service.embed_batch(
            [chunk.content for chunk in chunks]
        )

        self.ingestion_repository.delete_all_chunks()
        self.ingestion_repository.save_all_chunks(chunks, embedding_vectors)

        return self.ingestion_repository.count_chunks_by_source_type()

    def _collect_chunks_from_all_sources(self) -> list[Chunk]:
        """Gather chunks from the log dump and from every markdown document"""
        chunks: list[Chunk] = []

        log_file_path = Path(LOG_FILE)
        if log_file_path.exists():
            chunks.extend(self.log_chunker.chunk_log_line(log_file_path))
        else:
            print(f"no log file at {log_file_path} — skipping logs")

        repository_root = Path(DOCS_ROOT).resolve()
        markdown_file_paths = [
            repository_root / "README.md",
            *sorted((repository_root / "docs").rglob("*.md"))
        ]

        for markdown_file_path in markdown_file_paths:
            if markdown_file_path.exists():
                chunks.extend(
                    self.markdown_chunker.chunk_markdown_file(
                        markdown_file_path,
                        repository_root
                    )
                )

        return chunks

    def _warn_about_chunks_that_will_be_truncated(self, chunks: list[Chunk]) -> None:
        """Report chunks the embedding model will silently cut short

        The model stops at 256 word-pieces and says nothing about it, so this
        is the only warning that part of a document is unsearchable.
        """
        word_limit = EmbeddingService.MAXIMUM_WORDS_BEFORE_TRUNCATION

        for chunk in chunks:
            word_count = len(chunk.content.split())
            if word_count > word_limit:
                print(f"  ! {word_count} words, tail will be dropped: {chunk.source_ref}")