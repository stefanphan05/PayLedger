import json
from pathlib import Path

from app.ingestion.chunk import Chunk
from app.shared.enums import SourceType

class LogChunker:
    """
    Turns a dump of JSON log lines into one chunk per payment flow

    The correlation id is the natural boundary: every line sharing one is a
    single payment's journey across both services.
    """

    # Spring's ECS format puts MDC values at the top level, but some setups
    # nest them under "labels", so both locations are checked.
    CORRELATION_ID_FIELD_NAMES = ("correlationId", "correlation_id")
    TRANSACTION_ID_FIELD_NAMES = ("transactionId", "transaction_id")

    def chunk_log_line(
        self,
        log_file_path: Path
    ) -> list[Chunk]:
        """Read the log dump and return one Chunk per correlation id."""
        entries_by_correlation_id = self._group_entries_by_correlation_id(log_file_path)

        return [
            self._build_chunk_for_one_flow(correlation_id, log_entries)
            for correlation_id, log_entries in entries_by_correlation_id.items()
        ]

    def _group_entries_by_correlation_id(
        self,
        log_file_path: Path
    ) -> dict[str, list[dict]]:
        """Parse every JSON line and bucket it by the correlation id it carries."""
        entries_by_correlation_id: dict[str, list[dict]] = {}

        for raw_line in log_file_path.read_text().splitlines():
            raw_line = raw_line.strip()

            # Spring prints a plain-text startup banner before logging switches
            # to JSON, so skip anything not starting with a brace
            if not raw_line.startswith("{"):
                continue

            try:
                log_entry = json.loads(raw_line)
            except json.JSONDecodeError:
                continue

            correlation_id = self._read_first_present_field(
                log_entry,
                self.CORRELATION_ID_FIELD_NAMES
            )

            # A line with no correlation id has nothing to search it by
            if correlation_id is None:
                continue

            entries_by_correlation_id.setdefault(correlation_id, []).append(log_entry)

        return entries_by_correlation_id

    def _build_chunk_for_one_flow(
        self,
        correlation_id: str,
        log_entries: list[dict]
    ) -> Chunk:
        """Render one payment's log lines into a single readable chunk"""
        log_entries.sort(key=lambda entry: entry.get("@timestamp", ""))

        transaction_id = self._find_transaction_id(log_entries)

        header_line = f"Log flow for correlation id {correlation_id}"
        if transaction_id is not None:
            header_line += f" (transaction {transaction_id})"

        rendered_lines = [self._render_log_line(entry) for entry in log_entries]

        return Chunk(
            source_type=SourceType.LOG,
            source_ref=f"correlation id {correlation_id}",
            content=header_line + "\n" + "\n".join(rendered_lines),
            correlation_id=correlation_id,
            transaction_id=transaction_id,
        )

    def _render_log_line(
        self,
        log_entry: dict
    ) -> str:
        """Flatten one JSON log entry into the readable form used in the docs.

        ECS nests its fields as objects - {"log": {"level": ...}} - rather than
        using dotted key names, so each value is read one level at a time.
        The ids are the exception: they come from the logging context and do sit
        at the top level.
        """
        timestamp = log_entry.get("@timestamp", "")
        logger_name = self._read_nested_field(log_entry, "log", "logger")

        return "{time}  {level:<5}  {service:<20}  {logger:<22}  {message}".format(
            time=timestamp[11:23],                      # HH:MM:SS.mmm only
            level=self._read_nested_field(log_entry, "log", "level"),
            service=self._read_nested_field(log_entry, "service", "name"),
            logger=logger_name.split(".")[-1],          # class name, not full package
            message=log_entry.get("message", ""),
        )

    def _read_nested_field(
        self,
        log_entry: dict,
        parent_key: str,
        child_key: str
    ) -> str:
        """Read one value out of ECS's nested structure, e.g. log -> level."""
        parent_object = log_entry.get(parent_key) or {}
        return str(parent_object.get(child_key, ""))

    def _find_transaction_id(
        self,
        log_entries: list[dict]
    ) -> str | None:
        """Return the first transaction id present across a flow's lines
        The earliest lines are logged before the payment exists, so the id only
        appears partway through the flow
        """
        for log_entry in log_entries:
            transaction_id = self._read_first_present_field(
                log_entry,
                self.TRANSACTION_ID_FIELD_NAMES
            )

            if transaction_id is not None:
                return transaction_id
        return None

    def _read_first_present_field(
        self,
        log_entry: dict,
        candidate_field_names: tuple[str, ...]
    ) -> str | None:
        """Look for a field at the top level, then nested under 'labels'."""
        nested_labels = log_entry.get("labels") or {}

        for field_name in candidate_field_names:
            if log_entry.get(field_name):
                return str(log_entry[field_name])
            if nested_labels.get(field_name):
                return str(nested_labels[field_name])

        return None