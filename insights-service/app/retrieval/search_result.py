from dataclasses import dataclass
from enum import Enum

from app.shared.enums import SourceType

class SearchStrategy(str, Enum):
    """How a result was found."""
    EXACT = "EXACT"
    SEMANTIC = "SEMANTIC"

@dataclass
class SearchResult:
    """A chunk that came back from a search."""
    id: int
    source_type: SourceType
    source_ref: str
    content: str
    search_strategy: SearchStrategy
    distance: float | None = None      # set for SEMANTIC results only