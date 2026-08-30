package maintenance

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	_ "modernc.org/sqlite"
)

type Inspection struct {
	AppVersion          string `json:"app_version"`
	GitCommit           string `json:"git_commit"`
	SchemaVersion       int    `json:"schema_version"`
	InstanceID          string `json:"instance_id"`
	InstanceFingerprint string `json:"instance_fingerprint"`
}

var AppVersion = "dev"
var GitCommit = "unknown"

type VerifyReport struct {
	OK                  bool     `json:"ok"`
	SchemaVersion       int      `json:"schema_version"`
	InstanceID          string   `json:"instance_id"`
	InstanceFingerprint string   `json:"instance_fingerprint"`
	Warnings            []string `json:"warnings"`
}

func Inspect(ctx context.Context, dataDir string) (Inspection, error) {
	db, err := openReadOnly(dataDir)
	if err != nil {
		return Inspection{}, err
	}
	defer db.Close()
	return inspectDB(ctx, db)
}

func Verify(ctx context.Context, dataDir string, maxSchema int) (VerifyReport, error) {
	if maxSchema < 1 {
		return VerifyReport{}, errors.New("max schema must be positive")
	}
	if err := rejectSymlinks(dataDir); err != nil {
		return VerifyReport{}, err
	}
	db, err := openReadOnly(dataDir)
	if err != nil {
		return VerifyReport{}, err
	}
	defer db.Close()

	var integrity string
	if err := db.QueryRowContext(ctx, "PRAGMA integrity_check").Scan(&integrity); err != nil {
		return VerifyReport{}, fmt.Errorf("sqlite integrity check: %w", err)
	}
	if integrity != "ok" {
		return VerifyReport{}, fmt.Errorf("sqlite integrity check failed: %s", integrity)
	}
	rows, err := db.QueryContext(ctx, "PRAGMA foreign_key_check")
	if err != nil {
		return VerifyReport{}, fmt.Errorf("sqlite foreign key check: %w", err)
	}
	if rows.Next() {
		rows.Close()
		return VerifyReport{}, errors.New("sqlite foreign key check failed")
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return VerifyReport{}, fmt.Errorf("sqlite foreign key check: %w", err)
	}
	rows.Close()

	inspection, err := inspectDB(ctx, db)
	if err != nil {
		return VerifyReport{}, err
	}
	if inspection.SchemaVersion > maxSchema {
		return VerifyReport{}, fmt.Errorf("BACKUP_SCHEMA_TOO_NEW: schema %d exceeds supported schema %d", inspection.SchemaVersion, maxSchema)
	}
	if err := verifySingletons(ctx, db); err != nil {
		return VerifyReport{}, err
	}
	warnings, err := verifyStoredFiles(ctx, db, dataDir)
	if err != nil {
		return VerifyReport{}, err
	}
	return VerifyReport{
		OK: true, SchemaVersion: inspection.SchemaVersion, InstanceID: inspection.InstanceID,
		InstanceFingerprint: inspection.InstanceFingerprint, Warnings: warnings,
	}, nil
}

func openReadOnly(dataDir string) (*sql.DB, error) {
	databasePath := filepath.Join(dataDir, "database", "transfer.db")
	info, err := os.Lstat(databasePath)
	if err != nil {
		return nil, fmt.Errorf("database/transfer.db: %w", err)
	}
	if !info.Mode().IsRegular() {
		return nil, errors.New("database/transfer.db is not a regular file")
	}
	abs, err := filepath.Abs(databasePath)
	if err != nil {
		return nil, fmt.Errorf("resolve database path: %w", err)
	}
	uriPath := filepath.ToSlash(abs)
	if filepath.VolumeName(abs) != "" && !strings.HasPrefix(uriPath, "/") {
		uriPath = "/" + uriPath
	}
	u := &url.URL{Scheme: "file", Path: uriPath}
	query := u.Query()
	query.Set("mode", "ro")
	query.Set("immutable", "1")
	query.Add("_pragma", "foreign_keys(ON)")
	u.RawQuery = query.Encode()
	db, err := sql.Open("sqlite", u.String())
	if err != nil {
		return nil, fmt.Errorf("open sqlite read-only: %w", err)
	}
	db.SetMaxOpenConns(1)
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, fmt.Errorf("open sqlite read-only: %w", err)
	}
	return db, nil
}

func inspectDB(ctx context.Context, db *sql.DB) (Inspection, error) {
	result := Inspection{AppVersion: AppVersion, GitCommit: GitCommit}
	if err := db.QueryRowContext(ctx, "SELECT COALESCE(MAX(version), 0) FROM schema_migrations").Scan(&result.SchemaVersion); err != nil {
		return result, fmt.Errorf("read schema version: %w", err)
	}
	if err := db.QueryRowContext(ctx, "SELECT instance_id, instance_fingerprint FROM server_instance WHERE id = 1").Scan(&result.InstanceID, &result.InstanceFingerprint); err != nil {
		return result, fmt.Errorf("read server instance: %w", err)
	}
	if strings.TrimSpace(result.InstanceID) == "" || strings.TrimSpace(result.InstanceFingerprint) == "" {
		return result, errors.New("server_instance contains an empty identity")
	}
	return result, nil
}

func verifySingletons(ctx context.Context, db *sql.DB) error {
	var count, invalid int
	if err := db.QueryRowContext(ctx, "SELECT COUNT(*), COALESCE(SUM(CASE WHEN id != 1 OR initialized NOT IN (0,1) THEN 1 ELSE 0 END),0) FROM app_state").Scan(&count, &invalid); err != nil {
		return fmt.Errorf("verify app_state: %w", err)
	}
	if count != 1 || invalid != 0 {
		return fmt.Errorf("app_state must contain exactly one legal row (found %d)", count)
	}
	if err := db.QueryRowContext(ctx, "SELECT COUNT(*), COALESCE(SUM(CASE WHEN id != 1 OR instance_id = '' OR instance_fingerprint = '' THEN 1 ELSE 0 END),0) FROM server_instance").Scan(&count, &invalid); err != nil {
		return fmt.Errorf("verify server_instance: %w", err)
	}
	if count != 1 || invalid != 0 {
		return fmt.Errorf("server_instance must contain exactly one legal row (found %d)", count)
	}
	return nil
}

func rejectSymlinks(dataDir string) error {
	info, err := os.Lstat(dataDir)
	if err != nil {
		return fmt.Errorf("inspect data directory: %w", err)
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
		return errors.New("data directory must be a real directory")
	}
	return filepath.WalkDir(dataDir, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("symbolic link is not allowed: %s", filepath.ToSlash(path))
		}
		return nil
	})
}

func verifyStoredFiles(ctx context.Context, db *sql.DB, dataDir string) ([]string, error) {
	rows, err := db.QueryContext(ctx, `
		SELECT id, storage_key, size_bytes, thumbnail_key, thumbnail_size_bytes
		FROM files
		WHERE status = 'available' AND deleted_at IS NULL
		  AND (expires_at IS NULL OR datetime(expires_at) > datetime('now'))
	`)
	if err != nil {
		return nil, fmt.Errorf("read active file records: %w", err)
	}
	defer rows.Close()
	var warnings []string
	for rows.Next() {
		var id, storageKey string
		var size int64
		var thumbnailKey sql.NullString
		var thumbnailSize sql.NullInt64
		if err := rows.Scan(&id, &storageKey, &size, &thumbnailKey, &thumbnailSize); err != nil {
			return nil, fmt.Errorf("scan file record: %w", err)
		}
		if err := verifyFile(filepath.Join(dataDir, "files"), storageKey, size); err != nil {
			return nil, fmt.Errorf("file %s (%s): %w", id, storageKey, err)
		}
		if thumbnailKey.Valid {
			expected := int64(-1)
			if thumbnailSize.Valid {
				expected = thumbnailSize.Int64
			}
			if err := verifyFile(filepath.Join(dataDir, "thumbs"), thumbnailKey.String, expected); err != nil {
				warnings = append(warnings, fmt.Sprintf("thumbnail %s for file %s: %v", thumbnailKey.String, id, err))
			}
		}
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("read active file records: %w", err)
	}
	return warnings, nil
}

func verifyFile(root, key string, expectedSize int64) error {
	if key == "" || filepath.Base(key) != key || key == "." || key == ".." {
		return errors.New("unsafe storage key")
	}
	path := filepath.Join(root, key)
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return errors.New("not a regular file")
	}
	if expectedSize >= 0 && info.Size() != expectedSize {
		return fmt.Errorf("size is %d, expected %d", info.Size(), expectedSize)
	}
	return nil
}
