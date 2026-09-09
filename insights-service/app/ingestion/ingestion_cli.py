from app.main import ApplicationContext

def main() -> None:
    """Rebuild the searchable corpus from the log dump and the documents.

    Run by hand from the insights-service directory:
        python -m app.ingestion.ingestion_cli
    """
    application_context = ApplicationContext()
    counts_by_source_type = application_context.ingestion_service.ingest_all_sources()

    for source_type, chunk_count in sorted(counts_by_source_type.items()):
        print(f"stored {chunk_count} {source_type} chunks")


if __name__ == "__main__":
    main()