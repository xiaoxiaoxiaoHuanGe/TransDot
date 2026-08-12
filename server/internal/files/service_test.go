package files

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/database"
	"transdot.local/transfer-assistant/server/internal/messages"
)

func TestUploadThumbnailTimelineSearchDownloadAndDelete(t *testing.T) {
	service, db, dataDir := testService(t, testConfig(t, 1024))
	ctx := context.Background()
	batch, err := service.CreateBatch(ctx, "android-1", []UploadItem{{
		Filename: "holiday photo.jpg", MIMEType: "image/jpeg", SizeBytes: 5, Kind: KindImage,
	}})
	if err != nil || len(batch.Uploads) != 1 || batch.ReservedBytes != 5 {
		t.Fatalf("CreateBatch() = %+v, %v", batch, err)
	}
	ticket := batch.Uploads[0]
	thumbnail := []byte{0xff, 0xd8, 0xff, 0xd9}
	if err := service.UploadThumbnail(ctx, ticket.UploadID, "android-1", "image/jpeg", int64(len(thumbnail)), bytes.NewReader(thumbnail)); err != nil {
		t.Fatalf("UploadThumbnail() error = %v", err)
	}
	message, err := service.CompleteUpload(ctx, ticket.UploadID, "android-1", 5, bytes.NewBufferString("hello"))
	if err != nil {
		t.Fatalf("CompleteUpload() error = %v", err)
	}
	if message.Type != messages.TypeImage || message.File == nil || message.File.ThumbnailURL == "" || message.File.OriginalFilename != "holiday photo.jpg" {
		t.Fatalf("completed message = %+v", message)
	}
	results, err := messages.NewService(db).Search(ctx, "holiday")
	if err != nil || len(results) != 1 || results[0].ID != message.ID {
		t.Fatalf("filename search = %+v, %v", results, err)
	}
	download, err := service.OpenDownload(ctx, ticket.FileID)
	if err != nil {
		t.Fatalf("OpenDownload() error = %v", err)
	}
	contents, _ := io.ReadAll(download.Reader)
	download.Release()
	if string(contents) != "hello" {
		t.Fatalf("download contents = %q", contents)
	}
	preview, err := service.OpenThumbnail(ctx, ticket.FileID)
	if err != nil {
		t.Fatalf("OpenThumbnail() error = %v", err)
	}
	previewContents, _ := io.ReadAll(preview.Reader)
	preview.Close()
	if !bytes.Equal(previewContents, thumbnail) {
		t.Fatalf("thumbnail contents = %x", previewContents)
	}
	if err := service.DeleteMessage(ctx, message.ID); err != nil {
		t.Fatalf("DeleteMessage() error = %v", err)
	}
	if _, err := service.OpenDownload(ctx, ticket.FileID); !errors.Is(err, ErrFileNotFound) {
		t.Fatalf("download after delete error = %v", err)
	}
	var messageCount int
	if err := db.QueryRow(`SELECT COUNT(*) FROM messages WHERE id = ?`, message.ID).Scan(&messageCount); err != nil || messageCount != 0 {
		t.Fatalf("message count after delete = %d, %v", messageCount, err)
	}
	files, _ := os.ReadDir(filepath.Join(dataDir, "files"))
	thumbs, _ := os.ReadDir(filepath.Join(dataDir, "thumbs"))
	if len(files) != 0 || len(thumbs) != 0 {
		t.Fatalf("stored files remain after delete: %d/%d", len(files), len(thumbs))
	}
}

func TestValidationIncompleteAndOwnership(t *testing.T) {
	service, _, _ := testService(t, testConfig(t, 10))
	ctx := context.Background()
	if _, err := service.CreateBatch(ctx, "android-1", nil); !errors.Is(err, ErrEmptyBatch) {
		t.Fatalf("empty batch error = %v", err)
	}
	if _, err := service.CreateBatch(ctx, "android-1", []UploadItem{{Filename: "big.zip", MIMEType: "application/zip", SizeBytes: 11, Kind: KindFile}}); !errors.Is(err, ErrFileTooLarge) {
		t.Fatalf("large file error = %v", err)
	}
	batch, err := service.CreateBatch(ctx, "android-1", []UploadItem{{Filename: "small.bin", MIMEType: "application/octet-stream", SizeBytes: 5, Kind: KindFile}})
	if err != nil {
		t.Fatal(err)
	}
	uploadID := batch.Uploads[0].UploadID
	if _, err := service.CompleteUpload(ctx, uploadID, "windows-1", 5, bytes.NewBufferString("hello")); !errors.Is(err, ErrUploadNotFound) {
		t.Fatalf("foreign upload error = %v", err)
	}
	if _, err := service.CompleteUpload(ctx, uploadID, "android-1", 4, bytes.NewBufferString("nope")); !errors.Is(err, ErrUploadIncomplete) {
		t.Fatalf("wrong length error = %v", err)
	}
	if _, err := service.CompleteUpload(ctx, uploadID, "android-1", 5, bytes.NewBufferString("tiny")); !errors.Is(err, ErrUploadIncomplete) {
		t.Fatalf("short body error = %v", err)
	}
	emptyBatch, err := service.CreateBatch(ctx, "android-1", []UploadItem{{Filename: "empty.txt", MIMEType: "text/plain", SizeBytes: 0, Kind: KindFile}})
	if err != nil {
		t.Fatalf("empty file batch error = %v", err)
	}
	emptyMessage, err := service.CompleteUpload(ctx, emptyBatch.Uploads[0].UploadID, "android-1", 0, bytes.NewReader(nil))
	if err != nil || emptyMessage.File == nil || emptyMessage.File.SizeBytes != 0 {
		t.Fatalf("empty file upload = %+v, %v", emptyMessage, err)
	}
}

func TestCapacityExpiryAndActiveDownloadProtection(t *testing.T) {
	cfg := testConfig(t, 8)
	cfg.FilePoolMaxBytes = 10
	service, db, _ := testService(t, cfg)
	ctx := context.Background()
	clock := time.Date(2026, 8, 13, 1, 0, 0, 0, time.UTC)
	service.now = func() time.Time { return clock }
	firstBatch, _ := service.CreateBatch(ctx, "android-1", []UploadItem{{Filename: "first.bin", MIMEType: "application/octet-stream", SizeBytes: 6, Kind: KindFile}})
	first, err := service.CompleteUpload(ctx, firstBatch.Uploads[0].UploadID, "android-1", 6, bytes.NewBufferString("first!"))
	if err != nil {
		t.Fatal(err)
	}
	active, err := service.OpenDownload(ctx, firstBatch.Uploads[0].FileID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.CreateBatch(ctx, "android-1", []UploadItem{{Filename: "second.bin", MIMEType: "application/octet-stream", SizeBytes: 6, Kind: KindFile}}); !errors.Is(err, ErrInsufficientStorage) {
		t.Fatalf("capacity with active download error = %v", err)
	}
	active.Release()
	secondBatch, err := service.CreateBatch(ctx, "android-1", []UploadItem{{Filename: "second.bin", MIMEType: "application/octet-stream", SizeBytes: 6, Kind: KindFile}})
	if err != nil || len(secondBatch.Uploads) != 1 {
		t.Fatalf("batch after release = %+v, %v", secondBatch, err)
	}
	var status, reason string
	if err := db.QueryRow(`SELECT status, expired_reason FROM files WHERE message_id = ?`, first.ID).Scan(&status, &reason); err != nil || status != "expired" || reason != "capacity" {
		t.Fatalf("evicted file = %q/%q, %v", status, reason, err)
	}
}

func TestCleanupExpiresUploadFileAndMetadata(t *testing.T) {
	cfg := testConfig(t, 20)
	cfg.UploadSessionTTL = time.Minute
	cfg.FileTTL = time.Hour
	cfg.FileMessageTTL = 2 * time.Hour
	var events []ExpiredEvent
	service, db, _ := testServiceWithNotify(t, cfg, func(eventType string, data any) {
		if eventType == "file.expired" {
			events = append(events, data.(ExpiredEvent))
		}
	})
	ctx := context.Background()
	clock := time.Date(2026, 8, 13, 2, 0, 0, 0, time.UTC)
	service.now = func() time.Time { return clock }
	abandoned, _ := service.CreateBatch(ctx, "android-1", []UploadItem{{Filename: "abandoned", MIMEType: "application/octet-stream", SizeBytes: 3, Kind: KindFile}})
	completedBatch, _ := service.CreateBatch(ctx, "android-1", []UploadItem{{Filename: "kept", MIMEType: "application/octet-stream", SizeBytes: 4, Kind: KindFile}})
	completed, err := service.CompleteUpload(ctx, completedBatch.Uploads[0].UploadID, "android-1", 4, bytes.NewBufferString("kept"))
	if err != nil {
		t.Fatal(err)
	}
	clock = clock.Add(90 * time.Minute)
	if err := service.Cleanup(ctx); err != nil {
		t.Fatal(err)
	}
	var batchStatus string
	if err := db.QueryRow(`SELECT status FROM upload_batches WHERE id = ?`, abandoned.ID).Scan(&batchStatus); err != nil || batchStatus != "expired" {
		t.Fatalf("abandoned batch status = %q, %v", batchStatus, err)
	}
	if len(events) != 1 || events[0].MessageID != completed.ID {
		t.Fatalf("expiry events = %+v", events)
	}
	clock = clock.Add(time.Hour)
	if err := service.Cleanup(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := messages.NewService(db).Get(ctx, completed.ID); !errors.Is(err, messages.ErrNotFound) {
		t.Fatalf("message after metadata expiry error = %v", err)
	}
}

func testConfig(t *testing.T, maxFile int64) Config {
	t.Helper()
	return Config{
		DataDir: t.TempDir(), MaxFileBytes: maxFile, MaxBatchBytes: maxFile * 3,
		MaxBatchItems: 20, FilePoolMaxBytes: maxFile * 10,
		FileTTL: time.Hour, FileMessageTTL: 2 * time.Hour, UploadSessionTTL: 30 * time.Minute,
	}
}

func testService(t *testing.T, cfg Config) (*Service, *sql.DB, string) {
	return testServiceWithNotify(t, cfg, nil)
}

func testServiceWithNotify(t *testing.T, cfg Config, notify NotifyFunc) (*Service, *sql.DB, string) {
	t.Helper()
	db, err := database.Open(cfg.DataDir)
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	t.Cleanup(func() { db.Close() })
	hash := sha256.Sum256([]byte("token"))
	if _, err := db.Exec(`INSERT INTO devices (id, device_type, token_hash) VALUES ('android-1', 'android_master', ?)`, hash[:]); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO devices (id, device_type, token_hash) VALUES ('windows-1', 'windows_browser', ?)`, hash[:]); err != nil {
		t.Fatal(err)
	}
	return NewService(db, cfg, notify), db, cfg.DataDir
}
