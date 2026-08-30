package database

import (
	"database/sql"
	"os"
	"path/filepath"
	"testing"
)

func TestOpenCreatesDataLayoutAndRunsMigrations(t *testing.T) {
	dataDir := t.TempDir()

	db, err := Open(dataDir)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer db.Close()

	for _, directory := range []string{"database", "files", "thumbs", "tmp"} {
		if info, err := os.Stat(filepath.Join(dataDir, directory)); err != nil || !info.IsDir() {
			t.Fatalf("data directory %q was not created", directory)
		}
	}

	var applied int
	if err := db.QueryRow("SELECT COUNT(*) FROM schema_migrations WHERE version = 1").Scan(&applied); err != nil {
		t.Fatalf("query schema_migrations: %v", err)
	}
	if applied != 1 {
		t.Fatalf("migration version 1 count = %d, want 1", applied)
	}

	var initialized int
	if err := db.QueryRow("SELECT initialized FROM app_state WHERE id = 1").Scan(&initialized); err != nil {
		t.Fatalf("query app_state: %v", err)
	}
	if initialized != 0 {
		t.Fatalf("initialized = %d, want 0", initialized)
	}
}

func TestOpenIsIdempotent(t *testing.T) {
	dataDir := t.TempDir()

	db, err := Open(dataDir)
	if err != nil {
		t.Fatalf("first Open() error = %v", err)
	}
	if err := db.Close(); err != nil {
		t.Fatalf("first Close() error = %v", err)
	}

	db, err = Open(dataDir)
	if err != nil {
		t.Fatalf("second Open() error = %v", err)
	}
	defer db.Close()

	var applied int
	if err := db.QueryRow("SELECT COUNT(*) FROM schema_migrations").Scan(&applied); err != nil {
		t.Fatalf("query schema_migrations: %v", err)
	}
	if applied != 11 {
		t.Fatalf("migration count = %d, want 11", applied)
	}
}

func TestMultiBrowserMigrationPreservesExistingBrowserAndMasterUniqueness(t *testing.T) {
	dataDir := t.TempDir()
	databaseDir := filepath.Join(dataDir, "database")
	if err := os.MkdirAll(databaseDir, 0o750); err != nil {
		t.Fatal(err)
	}
	raw, err := sql.Open("sqlite", filepath.Join(databaseDir, databaseFilename))
	if err != nil {
		t.Fatal(err)
	}
	token := make([]byte, 32)
	if _, err := raw.Exec(createMigrationsTable); err != nil {
		t.Fatal(err)
	}
	if _, err := raw.Exec(`CREATE TABLE devices (
		id TEXT PRIMARY KEY,
		device_type TEXT NOT NULL CHECK (device_type IN ('android_master', 'windows_browser')),
		token_hash BLOB NOT NULL CHECK (length(token_hash) = 32),
		created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
		last_seen_at TEXT,
		revoked_at TEXT
	)`); err != nil {
		t.Fatal(err)
	}
	if _, err := raw.Exec(`CREATE UNIQUE INDEX devices_one_active_per_type ON devices (device_type) WHERE revoked_at IS NULL`); err != nil {
		t.Fatal(err)
	}
	if _, err := raw.Exec(`CREATE TABLE pairing_sessions (
		id TEXT PRIMARY KEY,
		code_hash BLOB NOT NULL,
		qr_secret_hash BLOB NOT NULL,
		browser_token_hash BLOB NOT NULL,
		status TEXT NOT NULL DEFAULT 'pending',
		failed_attempts INTEGER NOT NULL DEFAULT 0,
		replacement_allowed INTEGER NOT NULL DEFAULT 0,
		approved_by_device_id TEXT,
		browser_device_id TEXT,
		created_at TEXT NOT NULL,
		expires_at TEXT NOT NULL,
		approved_at TEXT,
		rejected_at TEXT,
		consumed_at TEXT
	)`); err != nil {
		t.Fatal(err)
	}
	for version := 1; version <= 10; version++ {
		if _, err := raw.Exec(`INSERT INTO schema_migrations(version, name) VALUES (?, ?)`, version, "legacy"); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := raw.Exec(`INSERT INTO devices (id, device_type, token_hash) VALUES ('master-1', 'android_master', ?)`, token); err != nil {
		t.Fatalf("insert master: %v", err)
	}
	if _, err := raw.Exec(`INSERT INTO devices (id, device_type, token_hash) VALUES ('browser-1', 'windows_browser', ?)`, append([]byte(nil), token...)); err != nil {
		t.Fatalf("insert first browser: %v", err)
	}
	if err := raw.Close(); err != nil {
		t.Fatal(err)
	}

	db, err := Open(dataDir)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer db.Close()
	if _, err := db.Exec(`INSERT INTO devices (id, device_type, token_hash) VALUES ('browser-2', 'windows_browser', ?)`, append([]byte(nil), token...)); err != nil {
		t.Fatalf("insert second browser: %v", err)
	}
	if _, err := db.Exec(`INSERT INTO devices (id, device_type, token_hash) VALUES ('master-2', 'android_master', ?)`, append([]byte(nil), token...)); err == nil {
		t.Fatal("second active Android master unexpectedly succeeded")
	}

	var masterName, browserName string
	if err := db.QueryRow(`SELECT display_name FROM devices WHERE id = 'master-1'`).Scan(&masterName); err != nil {
		t.Fatalf("read master name: %v", err)
	}
	if err := db.QueryRow(`SELECT display_name FROM devices WHERE id = 'browser-1'`).Scan(&browserName); err != nil {
		t.Fatalf("read browser name: %v", err)
	}
	if masterName != "Android Master" || browserName != "浏览器设备" {
		t.Fatalf("migrated names = %q/%q", masterName, browserName)
	}
}

func TestOpenRepairsInstanceTableWhenVersionSevenIsAlreadyOccupied(t *testing.T) {
	dataDir := t.TempDir()
	databaseDir := filepath.Join(dataDir, "database")
	if err := os.MkdirAll(databaseDir, 0o750); err != nil {
		t.Fatal(err)
	}
	raw, err := sql.Open("sqlite", filepath.Join(databaseDir, databaseFilename))
	if err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Exec(createMigrationsTable); err != nil {
		t.Fatal(err)
	}
	if _, err = raw.Exec(`INSERT INTO schema_migrations(version, name) VALUES (7, '007_direct_transfers.sql')`); err != nil {
		t.Fatal(err)
	}
	if err = raw.Close(); err != nil {
		t.Fatal(err)
	}

	db, err := Open(dataDir)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer db.Close()
	var table string
	if err := db.QueryRow(`SELECT name FROM sqlite_master WHERE type='table' AND name='server_instance'`).Scan(&table); err != nil {
		t.Fatalf("server_instance table was not repaired: %v", err)
	}
}
