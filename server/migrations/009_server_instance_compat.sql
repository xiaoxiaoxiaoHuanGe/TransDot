CREATE TABLE IF NOT EXISTS server_instance (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    instance_id TEXT NOT NULL UNIQUE,
    instance_fingerprint TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
