from fastapi import APIRouter

from app.answering.answer_models import AnswerResponse, QuestionRequest
from app.answering.answer_service import AnswerService

class InsightsController:
    """HTTP surface of the insights service"""

    def __init__(self, answer_service: AnswerService):
        self.answer_service = answer_service
        self.router = APIRouter()
        self._register_routes()

    def _register_routes(self) -> None:
        """Bind the endpoints to their handlers"""

        @self.router.post("/insights/ask", response_model=AnswerResponse)
        def ask_question(request: QuestionRequest) -> AnswerResponse:
            return self.answer_service.answer_question(request.question)

        @self.router.get("/health")
        def check_health() -> dict:
            return {"status": "up"}