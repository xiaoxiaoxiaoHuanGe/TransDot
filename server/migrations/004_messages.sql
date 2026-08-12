CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL CHECK (type IN ('text', 'image', 'file')),
    batch_id TEXT,
    source_device_id TEXT NOT NULL REFERENCES devices(id),
    text_content TEXT,
    created_at TEXT NOT NULL,
    deleted_at TEXT,
    metadata_expires_at TEXT,
    CHECK (type != 'text' OR text_content IS NOT NULL)
);

CREATE INDEX messages_timeline
    ON messages (created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE VIRTUAL TABLE message_search_fts USING fts5(
    message_id UNINDEXED,
    text_content,
    original_filename,
    tokenize = 'unicode61 remove_diacritics 2'
);
