import re
from pathlib import Path

from app.ingestion.chunk import Chunk
from app.shared.enums import SourceType

class MarkdownChunker:
    """Splits a markdown document into one chunk per heading section

    Splits on '####' as well as '##' for a specific reason: the embedding model
    truncates input at 256 word-pieces and does so silently. Several ADR
    sections exceed that, so their tails would never reach the embedding and
    would be unsearchable.

    Splitting at '####' also improves retrieval, because the ADRs use that level
    for 'Option A / Option B / Option C'. Each alternative becomes its own
    chunk, so "why not pessimistic locking" matches one option instead of
    competing with two others inside the same block of text.
    """

    HEADING_PATTERN = re.compile(r"^(#{2,4} .+)$", flags=re.MULTILINE)

    def chunk_markdown_file(self, markdown_file_path: Path, repository_root: Path) -> list[Chunk]:
        """Return one Chunk per section of the given markdown file."""
        markdown_text = markdown_file_path.read_text()
        path_relative_to_repository = markdown_file_path.relative_to(repository_root)
        document_title = self._extract_document_title(markdown_text, markdown_file_path)

        # re.split with a capturing group returns the text the first heading then alternating heading/body pairs
        split_parts = self.HEADING_PATTERN.split(markdown_text)
        text_before_first_heading = split_parts[0]
        heading_and_body_parts = split_parts[1:]

        chunks: list[Chunk] = []

        if text_before_first_heading.strip():
            chunks.append(
                self._build_chunk(
                    path_relative_to_repository=path_relative_to_repository,
                    document_title=document_title,
                    section_name=document_title,
                    section_body=text_before_first_heading
                )
            )

        # Tracks the most recent '##' so a '####' can be labelled with its
        # parents, e.g. "Alternatives considered / Option A - ..."
        current_top_level_section_name = document_title

        for heading_text, section_body in zip(
            heading_and_body_parts[0::2],
            heading_and_body_parts[1::2]
        ):
            heading_level = len(heading_text) - len(heading_text.lstrip("#"))
            heading_name = heading_text.lstrip("# ").strip()

            if heading_level == 2:
                current_top_level_section_name = heading_name
                section_name = heading_name
            else:
                section_name = f"{current_top_level_section_name} / {heading_name}"

            # A '##' immediately followed by a '###' has an empty body. A chunk
            # containing only a heading would add noise to every search
            if not section_body.strip():
                continue

            chunks.append(
                self._build_chunk(
                    path_relative_to_repository=path_relative_to_repository,
                    document_title=document_title,
                    section_name=section_name,
                    section_body=section_body
                )
            )

        return chunks

    def _build_chunk(
        self,
        path_relative_to_repository: Path,
        document_title: str,
        section_name: str,
        section_body: str
    ) -> Chunk:
        """Assemble one chunk, prefixed with where it came from.

        The prefix costs a few tokens and buys two things
        - The embedding picks up the document's subjects even when the section body never repeats it
        - The model can see the provenance in the text itself
        """
        content = (
            f"From {path_relative_to_repository} ({document_title}), "
            f"section '{section_name}':\n\n{section_body.strip()}"
        )

        return Chunk(
            source_type=SourceType.DOC,
            source_ref=f"{path_relative_to_repository} — {section_name}",
            content=content,
        )

    def _extract_document_title(
            self, markdown_text: str, markdown_file_path: Path
    ) -> str:
        """Use the document's single '#' heading, falling back to the filename."""
        for line in markdown_text.splitlines():
            if line.startswith("# "):
                return line.lstrip("# ").strip()
        return markdown_file_path.stem