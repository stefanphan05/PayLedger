import re

from google import genai

from app.answering.answer_models import AnswerResponse, CitedSource
from app.retrieval.retrieval_service import RetrievalService
from app.retrieval.search_result import SearchResult
from app.shared.config import ANSWER_MODEL, GEMINI_API_KEY

class AnswerService:
    """Put the retrieved chunks and the question to the language model

    The only class in the application that knows which model provider is in use
    """
    SYSTEM_INSTRUCTION = """You answer questions about PayLedger, a two-service
payment system, using only the numbered sources provided with each question.

Answer from those sources only. If they do not contain the answer, say so plainly
do not fall back on general knowledge about payment systems.

Cite every claim with the source number in square brackets, like [2]. When explaining
a failed payment, quote the actual log lines and name the actual rejection reason.
Keep answers short and concrete."""

    CITATION_MARKER_PATTERN = re.compile(r"\[(\d+)\]")

    def __init__(self, retrieval_service: RetrievalService):
        self.retrieval_service = retrieval_service
        self._gemini_client: genai.Client | None = None

    @property
    def gemini_client(self) -> genai.Client:
        """Create the client on first use, with a clear error if unconfigured."""
        existing_client = self._gemini_client
        if existing_client is not None:
            return existing_client

        if not GEMINI_API_KEY:
            raise RuntimeError(
                "GEMINI_API_KEY is not set. Add it to the .env file at the "
                "repository root, or export it in your shell."
            )

        created_client = genai.Client(api_key=GEMINI_API_KEY)
        self._gemini_client = created_client
        return created_client

    def answer_question(self, question: str) -> AnswerResponse:
        """Retrieve context, ask the model, and report which sources it used"""
        retrieved_chunks = self.retrieval_service.retrieve_relevant_chunks(question)

        if not retrieved_chunks:
            return AnswerResponse(
                answer="Nothing has been ingested yet.",
                cited_sources=[]
            )

        numbered_sources_text = self._format_sources_with_numbers(retrieved_chunks)

        interaction = self.gemini_client.interactions.create(
            model=ANSWER_MODEL,
            system_instruction=self.SYSTEM_INSTRUCTION,
            input=[
                {"type": "text", "text": f"Sources:\n\n{numbered_sources_text}"},
                {"type": "text", "text": f"Question: {question}"},
            ]
        )

        answer_text = interaction.output_text or "No answer was generated."

        return AnswerResponse(
            answer=answer_text,
            cited_sources=self._extract_cited_sources(answer_text, retrieved_chunks)
        )

    def _format_sources_with_numbers(
        self,
        retrieved_chunks: list[SearchResult]
    ) -> str:
        """Number the chunks so the model has something stable to cite"""
        return "\n\n".join(
            f"[{source_number}] ({chunk.source_ref})\n{chunk.content}"
            for source_number, chunk in enumerate(retrieved_chunks, start=1)
        )

    def _extract_cited_sources(
        self,
        answer_text: str,
        retrieved_chunks: list[SearchResult]
    ) -> list[CitedSource]:
        """Map the [n] markers in the answer back to the chunks they refer to

        Only sources the model actually cited are reported."""
        cited_numbers = sorted(
            {int(marker) for marker in self.CITATION_MARKER_PATTERN.findall(answer_text)}
        )

        return [
            CitedSource(
                citation_number=source_number,
                source_ref=retrieved_chunks[source_number - 1].source_ref,
                source_type=retrieved_chunks[source_number - 1].source_type,
            )
            for source_number in cited_numbers
            if 1 <= source_number <= len(retrieved_chunks)
        ]