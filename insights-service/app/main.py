from fastapi import FastAPI

from app.answering.answer_service import AnswerService
from app.answering.insights_controller import InsightsController
from app.ingestion.ingestion_repository import IngestionRepository
from app.ingestion.ingestion_service import IngestionService
from app.ingestion.log_chunker import LogChunker
from app.ingestion.markdown_chunker import MarkdownChunker
from app.retrieval.retrieval_repository import RetrievalRepository
from app.retrieval.retrieval_service import RetrievalService
from app.shared.database import DatabaseConnectionFactory
from app.shared.embedding_service import EmbeddingService

class ApplicationContext:
    def __init__(self):
        # --- shared ---------------------------------------------------------
        # EmbeddingService loads a 90 MB model, so it is built once here and
        # handed to both features rather than constructed twice.
        self.connection_factory = DatabaseConnectionFactory()
        self.embedding_service = EmbeddingService()

        # --- ingestion ------------------------------------------------------
        self.ingestion_service = IngestionService(
            ingestion_repository=IngestionRepository(self.connection_factory),
            embedding_service=self.embedding_service,
            log_chunker=LogChunker(),
            markdown_chunker=MarkdownChunker(),
        )

        # --- retrieval ------------------------------------------------------
        self.retrieval_service = RetrievalService(
            retrieval_repository=RetrievalRepository(self.connection_factory),
            embedding_service=self.embedding_service,
        )

        # --- answering ------------------------------------------------------
        self.answer_service = AnswerService(retrieval_service=self.retrieval_service)

def create_application() -> FastAPI:
    """Build the FastAPI application and attach the controllers to it."""
    application_context = ApplicationContext()

    application = FastAPI(title="PayLedger Insights")
    insights_controller = InsightsController(
        answer_service=application_context.answer_service
    )
    application.include_router(insights_controller.router)

    return application


app = create_application()