from dataclasses import dataclass

from app.shared.enums import SourceType

@dataclass
class Chunk:
    """One searchable unit, produced by a chunker and stored by ingestion.

    Either a single payment's log flow, or one section of a document. This is
    ingestion's model — retrieval never returns a Chunk, it returns a
    SearchResult, so the two features do not share a model here.
    """

    source_type: SourceType
    source_ref: str                      # what to cite: a file section, or a correlation id
    content: str                         # the text that gets embedded
    correlation_id: str | None = None    # logs only
    transaction_id: str | None = None    # logs only
