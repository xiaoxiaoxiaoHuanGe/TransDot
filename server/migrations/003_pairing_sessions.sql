CREATE TABLE pairing_sessions (
    id TEXT PRIMARY KEY,
    code_hash BLOB NOT NULL CHECK (length(code_hash) = 32),
    qr_secret_hash BLOB NOT NULL CHECK (length(qr_secret_hash) = 32),
    browser_token_hash BLOB NOT NULL CHECK (length(browser_token_hash) = 32),
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'approved', 'rejected', 'expired', 'consumed')),
    failed_attempts INTEGER NOT NULL DEFAULT 0
        CHECK (failed_attempts BETWEEN 0 AND 5),
    replacement_allowed INTEGER NOT NULL DEFAULT 0
        CHECK (replacement_allowed IN (0, 1)),
    approved_by_device_id TEXT REFERENCES devices(id),
    browser_device_id TEXT REFERENCES devices(id),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    approved_at TEXT,
    rejected_at TEXT,
    consumed_at TEXT
);

CREATE UNIQUE INDEX pairing_sessions_pending_code
    ON pairing_sessions (code_hash)
    WHERE status IN ('pending', 'approved');

CREATE INDEX pairing_sessions_expiry
    ON pairing_sessions (status, expires_at);
