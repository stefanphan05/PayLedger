-- install the pgvector extension in PostgreSQL
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE chunks (
    id BIGSERIAL PRIMARY KEY,
    source_type TEXT NOT NULL,           -- 'LOG' or 'DOC'
    source_ref TEXT NOT NULL,            -- file#heading, or the correlation id
    correlation_id TEXT,                 -- null for docs
    transaction_id TEXT,                 -- null for docs
    content TEXT NOT NULL,
    embedding vector(384) NOT NULL
);

CREATE INDEX chunks_correlation_id_idx ON chunks (correlation_id);
CREATE INDEX chunks_transaction_id_idx ON chunks (transaction_id);