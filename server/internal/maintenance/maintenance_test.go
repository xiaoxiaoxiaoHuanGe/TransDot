package maintenance

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"transdot.local/transfer-assistant/server/internal/database"
)

func TestInspectAndVerifyValidDataWithoutMutatingIt(t *testing.T) {
	dataDir := validDataDir(t)
	databasePath := filepath.Join(dataDir, "database", "transfer.db")
	before, err := os.Stat(databasePath)
	if err != nil {
		t.Fatal(err)
	}

	inspection, err := Inspect(context.Background(), dataDir)
	if err != nil {
		t.Fatalf("Inspect() error = %v", err)
	}
	if inspection.SchemaVersion != 11 || inspection.InstanceID != "instance-test" || inspection.InstanceFingerprint != "A1B2-C3D4" {
		t.Fatalf("inspection = %+v", inspection)
	}
	report, err := Verify(context.Background(), dataDir, 11)
	if err != nil {
		t.Fatalf("Verify() error = %v", err)
	}
	if !report.OK || len(report.Warnings) != 0 {
		t.Fatalf("report = %+v", report)
	}
	after, err := os.Stat(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	if !after.ModTime().Equal(before.ModTime()) || after.Size() != before.Size() {
		t.Fatal("verification mutated the database")
	}
}

func TestVerifyRejectsNewerSchema(t *testing.T) {
	dataDir := validDataDir(t)
	_, err := Verify(context.Background(), dataDir, 10)
	if err == nil || !strings.Contains(err.Error(), "BACKUP_SCHEMA_TOO_NEW") {
		t.Fatalf("Verify() error = %v", err)
	}
}

func TestVerifyMissingDatabaseDoesNotCreateAnything(t *testing.T) {
	dataDir := t.TempDir()
	_, err := Verify(context.Background(), dataDir, 11)
	if err == nil || !strings.Contains(err.Error(), "transfer.db") {
		t.Fatalf("Verify() error = %v", err)
	}
	if _, statErr := os.Stat(filepath.Join(dataDir, "database")); !os.IsNotExist(statErr) {
		t.Fatalf("read-only verification created database directory: %v", statErr)
	}
}

func TestVerifyRejectsSymbolicLinks(t *testing.T) {
	dataDir := validDataDir(t)
	target := filepath.Join(t.TempDir(), "outside")
	if err := os.WriteFile(target, []byte("outside"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, filepath.Join(dataDir, "files", "link")); err != nil {
		t.Skipf("symbolic links unavailable: %v", err)
	}
	if _, err := Verify(context.Background(), dataDir, 11); err == nil || !strings.Contains(err.Error(), "symbolic link") {
		t.Fatalf("Verify() error = %v", err)
	}
}

func TestVerifyChecksFilesAndOnlyWarnsForMissingThumbnail(t *testing.T) {
	dataDir := validDataDir(t)
	db, err := database.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO devices(id, device_type, token_hash, display_name, created_at) VALUES ('browser-1','windows_browser',zeroblob(32),'Browser','2026-08-30T00:00:00Z');
		INSERT INTO upload_batches(id, source_device_id, status, item_count, total_bytes, reserved_bytes, created_at, expires_at) VALUES ('batch-1','browser-1','completed',1,4,0,'2026-08-30T00:00:00Z','2099-08-30T00:00:00Z');
		INSERT INTO messages(id,type,batch_id,source_device_id,created_at) VALUES ('message-1','file','batch-1','browser-1','2026-08-30T00:00:00Z');
		INSERT INTO files(id,upload_id,message_id,batch_id,source_device_id,kind,original_filename,mime_type,size_bytes,storage_key,thumbnail_key,thumbnail_size_bytes,status,created_at,expires_at) VALUES ('file-1','upload-1','message-1','batch-1','browser-1','file','x.bin','application/octet-stream',4,'blob-1','thumb-1',3,'available','2026-08-30T00:00:00Z','2099-08-30T00:00:00Z')`)
	if err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dataDir, "files", "blob-1"), []byte("data"), 0o600); err != nil {
		t.Fatal(err)
	}
	report, err := Verify(context.Background(), dataDir, 11)
	if err != nil {
		t.Fatalf("missing thumbnail must only warn: %v", err)
	}
	if len(report.Warnings) != 1 || !strings.Contains(report.Warnings[0], "thumb-1") {
		t.Fatalf("warnings = %v", report.Warnings)
	}
	if err := os.Remove(filepath.Join(dataDir, "files", "blob-1")); err != nil {
		t.Fatal(err)
	}
	if _, err := Verify(context.Background(), dataDir, 11); err == nil || !strings.Contains(err.Error(), "blob-1") {
		t.Fatalf("missing original error = %v", err)
	}
}

func validDataDir(t *testing.T) string {
	t.Helper()
	dataDir := t.TempDir()
	db, err := database.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`UPDATE app_state SET initialized = 1 WHERE id = 1;
		INSERT INTO server_instance(id, instance_id, instance_fingerprint, created_at) VALUES (1, 'instance-test', 'A1B2-C3D4', '2026-08-30T00:00:00Z')`); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	return dataDir
}
