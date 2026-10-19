ALTER TABLE messages ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE messages ADD COLUMN edited_at TEXT;

CREATE TABLE message_order (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id TEXT NOT NULL UNIQUE REFERENCES messages(id) ON DELETE CASCADE
);
INSERT INTO message_order(message_id) SELECT id FROM messages ORDER BY created_at, id;
CREATE TRIGGER messages_order_insert AFTER INSERT ON messages BEGIN
    INSERT INTO message_order(message_id) VALUES(NEW.id);
END;

ALTER TABLE devices ADD COLUMN history_allowed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN history_boundary INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN history_request TEXT NOT NULL DEFAULT 'none'
    CHECK(history_request IN ('none','pending','approved','rejected'));
ALTER TABLE devices ADD COLUMN history_requested_at TEXT;
UPDATE devices SET history_allowed = 1;
CREATE TRIGGER devices_history_boundary AFTER INSERT ON devices BEGIN
    UPDATE devices SET history_boundary = COALESCE((SELECT MAX(seq) FROM sqlite_sequence WHERE name='message_order'),0)
    WHERE id = NEW.id;
END;
