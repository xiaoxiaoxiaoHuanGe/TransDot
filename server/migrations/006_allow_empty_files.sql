CREATE TABLE upload_batches_v2 (
    id TEXT PRIMARY KEY,
    source_device_id TEXT NOT NULL REFERENCES devices(id),
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'completed', 'failed', 'expired')),
    item_count INTEGER NOT NULL CHECK (item_count BETWEEN 1 AND 20),
    total_bytes INTEGER NOT NULL CHECK (total_bytes >= 0),
    reserved_bytes INTEGER NOT NULL CHECK (reserved_bytes >= 0),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    completed_at TEXT
);

CREATE TABLE files_v2 (
    id TEXT PRIMARY KEY,
    upload_id TEXT NOT NULL UNIQUE,
    message_id TEXT UNIQUE REFERENCES messages(id) ON DELETE SET NULL,
    batch_id TEXT NOT NULL REFERENCES upload_batches_v2(id),
    source_device_id TEXT NOT NULL REFERENCES devices(id),
    kind TEXT NOT NULL CHECK (kind IN ('image', 'file')),
    original_filename TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    storage_key TEXT UNIQUE,
    thumbnail_key TEXT UNIQUE,
    thumbnail_mime_type TEXT,
    thumbnail_size_bytes INTEGER,
    status TEXT NOT NULL DEFAULT 'uploading'
        CHECK (status IN ('uploading', 'available', 'expired', 'failed', 'deleted')),
    created_at TEXT NOT NULL,
    upload_completed_at TEXT,
    expires_at TEXT,
    expired_at TEXT,
    expired_reason TEXT CHECK (expired_reason IS NULL OR expired_reason IN ('ttl', 'capacity')),
    deleted_at TEXT
);

INSERT INTO upload_batches_v2 SELECT * FROM upload_batches;
INSERT INTO files_v2 SELECT * FROM files;

DROP TABLE files;
DROP TABLE upload_batches;

ALTER TABLE upload_batches_v2 RENAME TO upload_batches;
ALTER TABLE files_v2 RENAME TO files;

CREATE INDEX upload_batches_expiry ON upload_batches (status, expires_at);
CREATE INDEX files_batch ON files (batch_id);
CREATE INDEX files_status_expiry ON files (status, expires_at);
CREATE INDEX files_message ON files (message_id);
