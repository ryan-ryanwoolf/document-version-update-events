CREATE TABLE IF NOT EXISTS document_state (
    document_id VARCHAR(100) PRIMARY KEY,
    last_event_id VARCHAR(100) NOT NULL,
    latest_sequence BIGINT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'document_state'
          AND column_name = 'search_vector'
    ) THEN
        ALTER TABLE document_state
            ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
                setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
                setweight(to_tsvector('english', coalesce(body, '')), 'B')
            ) STORED;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'document_state_last_event_id_key'
          AND conrelid = 'document_state'::regclass
    ) THEN
        ALTER TABLE document_state
            ADD CONSTRAINT document_state_last_event_id_key UNIQUE (last_event_id);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_document_state_document_sequence
    ON document_state (document_id, latest_sequence);

CREATE INDEX IF NOT EXISTS idx_document_state_search_vector
    ON document_state
    USING GIN (search_vector);

CREATE TABLE IF NOT EXISTS pending_revision_event (
    event_id VARCHAR(100) PRIMARY KEY,
    document_id VARCHAR(100) NOT NULL,
    sequence BIGINT NOT NULL,
    event_timestamp TIMESTAMP NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT now()
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_pending_document_sequence'
          AND conrelid = 'pending_revision_event'::regclass
    ) THEN
        ALTER TABLE pending_revision_event
            ADD CONSTRAINT uq_pending_document_sequence UNIQUE (document_id, sequence);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_pending_document_sequence
    ON pending_revision_event (document_id, sequence);
