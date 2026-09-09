from pydantic import BaseModel

from app.shared.enums import SourceType

class QuestionRequest(BaseModel):
    """Incoming body of POST /insights/ask."""
    question: str

class CitedSource(BaseModel):
    """One source the answer actually referenced, via its [n] marker."""
    citation_number: int
    source_ref: str
    source_type: SourceType

class AnswerResponse(BaseModel):
    """Outgoing body of POST /insights/ask."""
    answer: str
    cited_sources: list[CitedSource]
