from enum import Enum

class SourceType(str, Enum):
    """Where a chunk came from.=
    Lives in shared because ingestion writes it and retrieval reads it back.
    """
    LOG = "LOG"
    DOC = "DOC"