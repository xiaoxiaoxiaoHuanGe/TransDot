CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    device_type TEXT NOT NULL CHECK (device_type IN ('android_master', 'windows_browser')),
    token_hash BLOB NOT NULL CHECK (length(token_hash) = 32),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    last_seen_at TEXT,
    revoked_at TEXT
);

CREATE UNIQUE INDEX devices_one_active_per_type
    ON devices (device_type)
    WHERE revoked_at IS NULL;
