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
	if applied != 10 {
		t.Fatalf("migration count = %d, want 10", applied)
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
