CREATE TABLE bootstrap_sessions (
    id TEXT PRIMARY KEY,
    instance_id TEXT NOT NULL,
    secret_hash BLOB NOT NULL CHECK (length(secret_hash) = 32),
    browser_token_hash BLOB NOT NULL CHECK (length(browser_token_hash) = 32),
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'expired', 'consumed')),
    browser_device_id TEXT REFERENCES devices(id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    consumed_at TEXT
);
CREATE INDEX bootstrap_sessions_expiry ON bootstrap_sessions(status, expires_at);
