package files

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"mime"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"

	"transdot.local/transfer-assistant/server/internal/messages"
)

const timestampFormat = "2006-01-02T15:04:05.000000000Z07:00"

type Service struct {
	db     *sql.DB
	config Config
	notify NotifyFunc
	now    func() time.Time

	operationMu sync.Mutex
	activityMu  sync.Mutex
	uploads     map[string]bool
	downloads   map[string]int
}

func NewService(db *sql.DB, cfg Config, notify NotifyFunc) *Service {
	return &Service{
		db:        db,
		config:    cfg,
		notify:    notify,
		now:       time.Now,
		uploads:   make(map[string]bool),
		downloads: make(map[string]int),
	}
}

func (s *Service) CreateBatch(ctx context.Context, sourceDeviceID string, requested []UploadItem) (UploadBatch, error) {
	items, totalBytes, err := s.validateItems(requested)
	if err != nil {
		return UploadBatch{}, err
	}

	s.operationMu.Lock()
	defer s.operationMu.Unlock()
	now := s.now().UTC()
	if err := s.cleanupLocked(ctx, now); err != nil {
		return UploadBatch{}, err
	}
	if err := s.ensureCapacityLocked(ctx, totalBytes, now); err != nil {
		return UploadBatch{}, err
	}

	batchID := uuid.NewString()
	expiresAt := now.Add(s.config.UploadSessionTTL)
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return UploadBatch{}, fmt.Errorf("begin upload batch: %w", err)
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO upload_batches
		    (id, source_device_id, status, item_count, total_bytes, reserved_bytes, created_at, expires_at)
		VALUES (?, ?, 'pending', ?, ?, ?, ?, ?)
	`, batchID, sourceDeviceID, len(items), totalBytes, totalBytes, formatTime(now), formatTime(expiresAt)); err != nil {
		return UploadBatch{}, fmt.Errorf("insert upload batch: %w", err)
	}

	tickets := make([]UploadTicket, 0, len(items))
	for _, item := range items {
		fileID := uuid.NewString()
		uploadID := uuid.NewString()
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO files
			    (id, upload_id, batch_id, source_device_id, kind, original_filename, mime_type, size_bytes, status, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'uploading', ?)
		`, fileID, uploadID, batchID, sourceDeviceID, item.Kind, item.Filename, item.MIMEType, item.SizeBytes, formatTime(now)); err != nil {
			return UploadBatch{}, fmt.Errorf("insert upload item: %w", err)
		}
		ticket := UploadTicket{
			FileID:    fileID,
			UploadID:  uploadID,
			Filename:  item.Filename,
			MIMEType:  item.MIMEType,
			SizeBytes: item.SizeBytes,
			Kind:      item.Kind,
			UploadURL: "/api/v1/uploads/" + uploadID,
		}
		if item.Kind == KindImage {
			ticket.ThumbnailUploadURL = ticket.UploadURL + "/thumbnail"
		}
		tickets = append(tickets, ticket)
	}
	if err := tx.Commit(); err != nil {
		return UploadBatch{}, fmt.Errorf("commit upload batch: %w", err)
	}
	return UploadBatch{
		ID:            batchID,
		Status:        "pending",
		TotalBytes:    totalBytes,
		ReservedBytes: totalBytes,
		ExpiresAt:     expiresAt,
		Uploads:       tickets,
	}, nil
}

func (s *Service) validateItems(requested []UploadItem) ([]UploadItem, int64, error) {
	if len(requested) == 0 {
		return nil, 0, ErrEmptyBatch
	}
	if len(requested) > s.config.MaxBatchItems {
		return nil, 0, ErrTooManyFiles
	}
	items := make([]UploadItem, 0, len(requested))
	var total int64
	for _, item := range requested {
		filename, err := normalizeFilename(item.Filename)
		if err != nil || item.SizeBytes < 0 || !utf8.ValidString(item.MIMEType) {
			return nil, 0, ErrInvalidItem
		}
		if item.SizeBytes > s.config.MaxFileBytes {
			return nil, 0, ErrFileTooLarge
		}
		kind := strings.ToLower(strings.TrimSpace(item.Kind))
		if kind != KindImage && kind != KindFile {
			return nil, 0, ErrInvalidItem
		}
		mimeType := strings.TrimSpace(item.MIMEType)
		if mimeType == "" {
			mimeType = "application/octet-stream"
		}
		if len([]byte(mimeType)) > maximumMIMETypeBytes {
			return nil, 0, ErrInvalidItem
		}
		if parsed, _, parseErr := mime.ParseMediaType(mimeType); parseErr != nil || parsed == "" {
			return nil, 0, ErrInvalidItem
		}
		if total > s.config.MaxBatchBytes-item.SizeBytes {
			return nil, 0, ErrBatchTooLarge
		}
		total += item.SizeBytes
		items = append(items, UploadItem{Filename: filename, MIMEType: mimeType, SizeBytes: item.SizeBytes, Kind: kind})
	}
	if total > s.config.MaxBatchBytes {
		return nil, 0, ErrBatchTooLarge
	}
	return items, total, nil
}

func normalizeFilename(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" || !utf8.ValidString(value) {
		return "", ErrInvalidItem
	}
	value = strings.ReplaceAll(value, "\\", "/")
	value = filepath.Base(value)
	if value == "." || value == ".." || value == "" || len([]byte(value)) > maximumFilenameBytes {
		return "", ErrInvalidItem
	}
	for _, character := range value {
		if character == 0 || unicode.IsControl(character) {
			return "", ErrInvalidItem
		}
	}
	return value, nil
}

type pendingUpload struct {
	FileID         string
	BatchID        string
	SourceDeviceID string
	Kind           string
	Filename       string
	MIMEType       string
	SizeBytes      int64
	Status         string
	ExpiresAt      string
}

func (s *Service) CompleteUpload(ctx context.Context, uploadID, sourceDeviceID string, contentLength int64, body io.Reader) (messages.Message, error) {
	pending, release, err := s.beginUpload(ctx, uploadID, sourceDeviceID, contentLength, false)
	if err != nil {
		return messages.Message{}, err
	}
	defer release()

	temporaryPath := filepath.Join(s.config.DataDir, "tmp", uploadID+".part")
	_ = os.Remove(temporaryPath)
	output, err := os.OpenFile(temporaryPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o640)
	if err != nil {
		return messages.Message{}, fmt.Errorf("create temporary upload: %w", err)
	}
	removeTemporary := true
	defer func() {
		_ = output.Close()
		if removeTemporary {
			_ = os.Remove(temporaryPath)
		}
	}()

	written, copyErr := io.Copy(output, io.LimitReader(body, pending.SizeBytes+1))
	if copyErr != nil {
		return messages.Message{}, fmt.Errorf("stream upload: %w", copyErr)
	}
	if written != pending.SizeBytes {
		return messages.Message{}, ErrUploadIncomplete
	}
	if err := output.Sync(); err != nil {
		return messages.Message{}, fmt.Errorf("sync upload: %w", err)
	}
	if err := output.Close(); err != nil {
		return messages.Message{}, fmt.Errorf("close upload: %w", err)
	}

	storageKey := uuid.NewString()
	destination := filepath.Join(s.config.DataDir, "files", storageKey)
	if err := os.Rename(temporaryPath, destination); err != nil {
		return messages.Message{}, fmt.Errorf("commit upload file: %w", err)
	}
	removeTemporary = false
	committed := false
	defer func() {
		if !committed {
			_ = os.Remove(destination)
		}
	}()

	now := s.now().UTC()
	messageID := uuid.NewString()
	fileExpiresAt := now.Add(s.config.FileTTL)
	metadataExpiresAt := now.Add(s.config.FileMessageTTL)
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return messages.Message{}, fmt.Errorf("begin upload completion: %w", err)
	}
	defer tx.Rollback()
	messageType := pending.Kind
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO messages (id, type, batch_id, source_device_id, created_at, metadata_expires_at)
		VALUES (?, ?, ?, ?, ?, ?)
	`, messageID, messageType, pending.BatchID, sourceDeviceID, formatTime(now), formatTime(metadataExpiresAt)); err != nil {
		return messages.Message{}, fmt.Errorf("create file message: %w", err)
	}
	result, err := tx.ExecContext(ctx, `
		UPDATE files
		SET message_id = ?, storage_key = ?, status = 'available', upload_completed_at = ?, expires_at = ?
		WHERE upload_id = ? AND source_device_id = ? AND status = 'uploading'
	`, messageID, storageKey, formatTime(now), formatTime(fileExpiresAt), uploadID, sourceDeviceID)
	if err != nil {
		return messages.Message{}, fmt.Errorf("complete upload record: %w", err)
	}
	affected, _ := result.RowsAffected()
	if affected != 1 {
		return messages.Message{}, ErrUploadConflict
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO message_search_fts (message_id, text_content, original_filename)
		VALUES (?, '', ?)
	`, messageID, pending.Filename); err != nil {
		return messages.Message{}, fmt.Errorf("index file message: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE upload_batches
		SET reserved_bytes = MAX(0, reserved_bytes - ?),
		    status = CASE WHEN NOT EXISTS (
		        SELECT 1 FROM files WHERE batch_id = ? AND status = 'uploading'
		    ) THEN 'completed' ELSE status END,
		    completed_at = CASE WHEN NOT EXISTS (
		        SELECT 1 FROM files WHERE batch_id = ? AND status = 'uploading'
		    ) THEN ? ELSE completed_at END
		WHERE id = ? AND status = 'pending'
	`, pending.SizeBytes, pending.BatchID, pending.BatchID, formatTime(now), pending.BatchID); err != nil {
		return messages.Message{}, fmt.Errorf("update upload batch: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return messages.Message{}, fmt.Errorf("commit upload completion: %w", err)
	}
	committed = true
	message, err := messages.NewService(s.db).Get(ctx, messageID)
	if err != nil {
		return messages.Message{}, fmt.Errorf("read completed file message: %w", err)
	}
	return message, nil
}

func (s *Service) UploadThumbnail(ctx context.Context, uploadID, sourceDeviceID, contentType string, contentLength int64, body io.Reader) error {
	pending, release, err := s.beginUpload(ctx, uploadID, sourceDeviceID, contentLength, true)
	if err != nil {
		return err
	}
	defer release()
	if pending.Kind != KindImage || contentLength <= 0 || contentLength > MaximumThumbnailBytes {
		return ErrThumbnailInvalid
	}
	mediaType, _, err := mime.ParseMediaType(contentType)
	if err != nil || !strings.EqualFold(mediaType, "image/jpeg") {
		return ErrThumbnailInvalid
	}

	temporaryPath := filepath.Join(s.config.DataDir, "tmp", uploadID+".thumb.part")
	_ = os.Remove(temporaryPath)
	output, err := os.OpenFile(temporaryPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o640)
	if err != nil {
		return fmt.Errorf("create temporary thumbnail: %w", err)
	}
	removeTemporary := true
	defer func() {
		_ = output.Close()
		if removeTemporary {
			_ = os.Remove(temporaryPath)
		}
	}()
	written, err := io.Copy(output, io.LimitReader(body, MaximumThumbnailBytes+1))
	if err != nil {
		return fmt.Errorf("stream thumbnail: %w", err)
	}
	if written != contentLength || written > MaximumThumbnailBytes {
		return ErrThumbnailInvalid
	}
	if err := output.Sync(); err != nil {
		return fmt.Errorf("sync thumbnail: %w", err)
	}
	if err := output.Close(); err != nil {
		return fmt.Errorf("close thumbnail: %w", err)
	}
	thumbnailKey := uuid.NewString() + ".jpg"
	destination := filepath.Join(s.config.DataDir, "thumbs", thumbnailKey)
	if err := os.Rename(temporaryPath, destination); err != nil {
		return fmt.Errorf("commit thumbnail: %w", err)
	}
	removeTemporary = false
	var previous sql.NullString
	if err := s.db.QueryRowContext(ctx, `SELECT thumbnail_key FROM files WHERE upload_id = ?`, uploadID).Scan(&previous); err != nil {
		_ = os.Remove(destination)
		return fmt.Errorf("read previous thumbnail: %w", err)
	}
	result, err := s.db.ExecContext(ctx, `
		UPDATE files SET thumbnail_key = ?, thumbnail_mime_type = 'image/jpeg', thumbnail_size_bytes = ?
		WHERE upload_id = ? AND source_device_id = ? AND kind = 'image' AND status IN ('uploading', 'available')
	`, thumbnailKey, written, uploadID, sourceDeviceID)
	if err != nil {
		_ = os.Remove(destination)
		return fmt.Errorf("save thumbnail: %w", err)
	}
	affected, _ := result.RowsAffected()
	if affected != 1 {
		_ = os.Remove(destination)
		return ErrUploadNotFound
	}
	if previous.Valid {
		_ = os.Remove(filepath.Join(s.config.DataDir, "thumbs", previous.String))
	}
	return nil
}

func (s *Service) beginUpload(ctx context.Context, uploadID, sourceDeviceID string, contentLength int64, thumbnail bool) (pendingUpload, func(), error) {
	uploadID = strings.TrimSpace(uploadID)
	if uploadID == "" || contentLength < 0 {
		return pendingUpload{}, nil, ErrUploadIncomplete
	}
	s.activityMu.Lock()
	defer s.activityMu.Unlock()
	if s.uploads[uploadID] {
		return pendingUpload{}, nil, ErrUploadConflict
	}
	var value pendingUpload
	err := s.db.QueryRowContext(ctx, `
		SELECT f.id, f.batch_id, f.source_device_id, f.kind, f.original_filename,
		       f.mime_type, f.size_bytes, f.status, b.expires_at
		FROM files f JOIN upload_batches b ON b.id = f.batch_id
		WHERE f.upload_id = ?
	`, uploadID).Scan(&value.FileID, &value.BatchID, &value.SourceDeviceID, &value.Kind,
		&value.Filename, &value.MIMEType, &value.SizeBytes, &value.Status, &value.ExpiresAt)
	if errors.Is(err, sql.ErrNoRows) || value.SourceDeviceID != sourceDeviceID {
		return pendingUpload{}, nil, ErrUploadNotFound
	}
	if err != nil {
		return pendingUpload{}, nil, fmt.Errorf("read upload ticket: %w", err)
	}
	expiresAt, err := time.Parse(time.RFC3339Nano, value.ExpiresAt)
	if err != nil {
		return pendingUpload{}, nil, fmt.Errorf("parse upload expiry: %w", err)
	}
	if s.now().UTC().After(expiresAt) {
		return pendingUpload{}, nil, ErrUploadExpired
	}
	if thumbnail {
		if value.Status != "uploading" && value.Status != "available" {
			return pendingUpload{}, nil, ErrUploadConflict
		}
	} else {
		if value.Status != "uploading" {
			return pendingUpload{}, nil, ErrUploadConflict
		}
		if contentLength != value.SizeBytes {
			return pendingUpload{}, nil, ErrUploadIncomplete
		}
	}
	s.uploads[uploadID] = true
	return value, func() {
		s.activityMu.Lock()
		delete(s.uploads, uploadID)
		s.activityMu.Unlock()
	}, nil
}

func (s *Service) OpenDownload(ctx context.Context, fileID string) (*Download, error) {
	fileID = strings.TrimSpace(fileID)
	if fileID == "" {
		return nil, ErrFileNotFound
	}
	s.activityMu.Lock()
	defer s.activityMu.Unlock()
	var filename, mimeType, status string
	var size int64
	var storageKey sql.NullString
	err := s.db.QueryRowContext(ctx, `
		SELECT original_filename, mime_type, size_bytes, storage_key, status
		FROM files WHERE id = ? AND deleted_at IS NULL
	`, fileID).Scan(&filename, &mimeType, &size, &storageKey, &status)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrFileNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("read download file: %w", err)
	}
	if status == "expired" {
		return nil, ErrFileExpired
	}
	if status != "available" || !storageKey.Valid {
		return nil, ErrFileNotFound
	}
	reader, err := os.Open(filepath.Join(s.config.DataDir, "files", storageKey.String))
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrFileNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("open download file: %w", err)
	}
	s.downloads[fileID]++
	return &Download{
		FileID: fileID, Filename: filename, MIMEType: mimeType, SizeBytes: size,
		ModTime: s.now().UTC(), Reader: reader,
		ReleaseFunc: func() {
			s.activityMu.Lock()
			defer s.activityMu.Unlock()
			s.downloads[fileID]--
			if s.downloads[fileID] <= 0 {
				delete(s.downloads, fileID)
			}
		},
	}, nil
}

func (s *Service) OpenThumbnail(ctx context.Context, fileID string) (*Thumbnail, error) {
	fileID = strings.TrimSpace(fileID)
	var key, mimeType sql.NullString
	var size sql.NullInt64
	err := s.db.QueryRowContext(ctx, `
		SELECT thumbnail_key, thumbnail_mime_type, thumbnail_size_bytes
		FROM files WHERE id = ? AND deleted_at IS NULL
	`, fileID).Scan(&key, &mimeType, &size)
	if errors.Is(err, sql.ErrNoRows) || !key.Valid || !mimeType.Valid || !size.Valid {
		return nil, ErrFileNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("read thumbnail: %w", err)
	}
	reader, err := os.Open(filepath.Join(s.config.DataDir, "thumbs", key.String))
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrFileNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("open thumbnail: %w", err)
	}
	return &Thumbnail{FileID: fileID, MIMEType: mimeType.String, SizeBytes: size.Int64, ModTime: s.now().UTC(), Reader: reader}, nil
}

func (s *Service) DeleteMessage(ctx context.Context, messageID string) error {
	messageID = strings.TrimSpace(messageID)
	if messageID == "" {
		return ErrMessageNotFound
	}
	s.operationMu.Lock()
	defer s.operationMu.Unlock()
	var storageKey, thumbnailKey sql.NullString
	var fileID sql.NullString
	err := s.db.QueryRowContext(ctx, `
		SELECT f.id, f.storage_key, f.thumbnail_key
		FROM messages m LEFT JOIN files f ON f.message_id = m.id
		WHERE m.id = ? AND m.deleted_at IS NULL
	`, messageID).Scan(&fileID, &storageKey, &thumbnailKey)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrMessageNotFound
	}
	if err != nil {
		return fmt.Errorf("read message files for deletion: %w", err)
	}
	if fileID.Valid {
		s.activityMu.Lock()
		defer s.activityMu.Unlock()
	}
	now := formatTime(s.now().UTC())
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin message deletion: %w", err)
	}
	defer tx.Rollback()
	if fileID.Valid {
		if _, err := tx.ExecContext(ctx, `
			UPDATE files SET status = 'deleted', deleted_at = ? WHERE id = ?
		`, now, fileID.String); err != nil {
			return fmt.Errorf("mark file deleted: %w", err)
		}
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM message_search_fts WHERE message_id = ?`, messageID); err != nil {
		return fmt.Errorf("delete message search index: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM messages WHERE id = ?`, messageID); err != nil {
		return fmt.Errorf("delete message: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit message deletion: %w", err)
	}
	if storageKey.Valid {
		_ = os.Remove(filepath.Join(s.config.DataDir, "files", storageKey.String))
	}
	if thumbnailKey.Valid {
		_ = os.Remove(filepath.Join(s.config.DataDir, "thumbs", thumbnailKey.String))
	}
	return nil
}

func formatTime(value time.Time) string {
	return value.UTC().Format(timestampFormat)
}
