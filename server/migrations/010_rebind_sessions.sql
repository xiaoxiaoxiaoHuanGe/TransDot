CREATE TABLE rebind_sessions (
    id TEXT PRIMARY KEY,
    instance_id TEXT NOT NULL,
    secret_hash BLOB NOT NULL CHECK (length(secret_hash) = 32),
    browser_device_id TEXT NOT NULL REFERENCES devices(id),
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'expired', 'consumed')),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    consumed_at TEXT
);
CREATE INDEX rebind_sessions_expiry ON rebind_sessions(status, expires_at);
