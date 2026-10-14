-- The Docker init script runs only on an empty volume; the migration must also work
-- on a fresh machine, in CI and in Testcontainers.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_type   TEXT        NOT NULL CHECK (source_type IN ('DOC', 'ISSUE')),
    source_id     TEXT        NOT NULL,  -- file path in the repo or issue number
    source_url    TEXT        NOT NULL,  -- link used to cite the source in answers
    title         TEXT,
    source_ref    TEXT,                  -- git tag/commit or issue updated_at
    content_hash  CHAR(64)    NOT NULL,  -- SHA-256 (hex) of the document text
    ingested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_document_source UNIQUE (source_type, source_id)
);

CREATE TABLE chunk (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id      BIGINT       NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    chunk_index      INTEGER      NOT NULL CHECK (chunk_index >= 0),
    content          TEXT         NOT NULL CHECK (content ~ '\S'), -- at least one non-whitespace character
    section          TEXT,                  -- heading path, e.g. "Ollama > Chat Options"
    token_count      INTEGER      NOT NULL CHECK (token_count > 0),
    embedding        vector(1024) NOT NULL,
    embedding_model  TEXT         NOT NULL, -- e.g. bge-m3
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_chunk_document_index UNIQUE (document_id, chunk_index)
);

CREATE INDEX idx_chunk_embedding_hnsw ON chunk
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
