package database

import (
	"context"
	"database/sql"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

const databaseFilename = "transfer.db"

func Open(dataDir string) (*sql.DB, error) {
	for _, directory := range []string{"database", "files", "thumbs", "tmp"} {
		if err := os.MkdirAll(filepath.Join(dataDir, directory), 0o750); err != nil {
			return nil, fmt.Errorf("create data directory %q: %w", directory, err)
		}
	}

	databasePath := filepath.Join(dataDir, "database", databaseFilename)
	dsn := (&url.URL{
		Scheme: "file",
		Path:   filepath.ToSlash(databasePath),
		RawQuery: url.Values{
			"_pragma": []string{
				"busy_timeout(5000)",
				"foreign_keys(ON)",
				"journal_mode(WAL)",
			},
		}.Encode(),
	}).String()

	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open sqlite database: %w", err)
	}

	// A single writer connection keeps the V1 migration and transaction model
	// deterministic. This can be revisited after real workload measurements.
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	db.SetConnMaxLifetime(0)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("ping sqlite database: %w", err)
	}
	if err := migrate(ctx, db); err != nil {
		db.Close()
		return nil, fmt.Errorf("apply database migrations: %w", err)
	}

	return db, nil
}
