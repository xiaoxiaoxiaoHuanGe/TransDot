package files

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

func (s *Service) RunCleanup(ctx context.Context, interval time.Duration, onError func(error)) {
	if interval <= 0 {
		interval = 5 * time.Minute
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.Cleanup(ctx); err != nil && onError != nil && !errors.Is(err, context.Canceled) {
				onError(err)
			}
		}
	}
}

func (s *Service) Cleanup(ctx context.Context) error {
	s.operationMu.Lock()
	defer s.operationMu.Unlock()
	return s.cleanupLocked(ctx, s.now().UTC())
}

func (s *Service) cleanupLocked(ctx context.Context, now time.Time) error {
	if _, err := s.db.ExecContext(ctx, `
		UPDATE pairing_sessions
		SET status = 'expired'
		WHERE status IN ('pending', 'approved') AND expires_at <= ?
	`, formatTime(now)); err != nil {
		return fmt.Errorf("expire pairing sessions: %w", err)
	}
	if err := s.expireUploadSessionsLocked(ctx, now); err != nil {
		return err
	}
	if err := s.expireFilesByTTLLocked(ctx, now); err != nil {
		return err
	}
	if err := s.deleteExpiredMetadataLocked(ctx, now); err != nil {
		return err
	}
	if err := s.removeStaleParts(now); err != nil {
		return err
	}
	return nil
}

func (s *Service) expireUploadSessionsLocked(ctx context.Context, now time.Time) error {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id FROM upload_batches WHERE status = 'pending' AND expires_at <= ?
	`, formatTime(now))
	if err != nil {
		return fmt.Errorf("query expired upload sessions: %w", err)
	}
	var batchIDs []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return fmt.Errorf("scan expired upload session: %w", err)
		}
		batchIDs = append(batchIDs, id)
	}
	if err := rows.Close(); err != nil {
		return fmt.Errorf("close expired upload sessions: %w", err)
	}
	for _, batchID := range batchIDs {
		uploadRows, err := s.db.QueryContext(ctx, `SELECT upload_id, thumbnail_key FROM files WHERE batch_id = ? AND status = 'uploading'`, batchID)
		if err != nil {
			return fmt.Errorf("query batch uploads: %w", err)
		}
		type abandonedUpload struct {
			uploadID     string
			thumbnailKey sql.NullString
		}
		var uploads []abandonedUpload
		for uploadRows.Next() {
			var upload abandonedUpload
			if err := uploadRows.Scan(&upload.uploadID, &upload.thumbnailKey); err != nil {
				uploadRows.Close()
				return fmt.Errorf("scan batch upload: %w", err)
			}
			uploads = append(uploads, upload)
		}
		uploadRows.Close()

		s.activityMu.Lock()
		active := false
		for _, upload := range uploads {
			if s.uploads[upload.uploadID] {
				active = true
				break
			}
		}
		if active {
			s.activityMu.Unlock()
			continue
		}
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			s.activityMu.Unlock()
			return fmt.Errorf("begin upload expiry: %w", err)
		}
		if _, err := tx.ExecContext(ctx, `
			UPDATE files
			SET status = 'failed', thumbnail_key = NULL, thumbnail_mime_type = NULL, thumbnail_size_bytes = NULL
			WHERE batch_id = ? AND status = 'uploading'
		`, batchID); err != nil {
			tx.Rollback()
			s.activityMu.Unlock()
			return fmt.Errorf("fail expired uploads: %w", err)
		}
		if _, err := tx.ExecContext(ctx, `
			UPDATE upload_batches SET status = 'expired', reserved_bytes = 0 WHERE id = ? AND status = 'pending'
		`, batchID); err != nil {
			tx.Rollback()
			s.activityMu.Unlock()
			return fmt.Errorf("expire upload batch: %w", err)
		}
		if err := tx.Commit(); err != nil {
			s.activityMu.Unlock()
			return fmt.Errorf("commit upload expiry: %w", err)
		}
		s.activityMu.Unlock()
		for _, upload := range uploads {
			_ = os.Remove(filepath.Join(s.config.DataDir, "tmp", upload.uploadID+".part"))
			_ = os.Remove(filepath.Join(s.config.DataDir, "tmp", upload.uploadID+".thumb.part"))
			if upload.thumbnailKey.Valid {
				_ = os.Remove(filepath.Join(s.config.DataDir, "thumbs", upload.thumbnailKey.String))
			}
		}
	}
	return nil
}

func (s *Service) expireFilesByTTLLocked(ctx context.Context, now time.Time) error {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id FROM files WHERE status = 'available' AND expires_at <= ? ORDER BY expires_at, id
	`, formatTime(now))
	if err != nil {
		return fmt.Errorf("query expired files: %w", err)
	}
	var fileIDs []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return fmt.Errorf("scan expired file: %w", err)
		}
		fileIDs = append(fileIDs, id)
	}
	rows.Close()
	for _, fileID := range fileIDs {
		s.activityMu.Lock()
		if s.downloads[fileID] > 0 {
			s.activityMu.Unlock()
			continue
		}
		_, err := s.expireFileLocked(ctx, fileID, "ttl", now)
		s.activityMu.Unlock()
		if err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) ensureCapacityLocked(ctx context.Context, required int64, now time.Time) error {
	if required > s.config.FilePoolMaxBytes {
		return ErrInsufficientStorage
	}
	used, reserved, err := s.storageUsage(ctx)
	if err != nil {
		return err
	}
	if used+reserved+required <= s.config.FilePoolMaxBytes {
		return nil
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT id FROM files WHERE status = 'available' ORDER BY upload_completed_at, id
	`)
	if err != nil {
		return fmt.Errorf("query capacity eviction candidates: %w", err)
	}
	var fileIDs []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return fmt.Errorf("scan capacity eviction candidate: %w", err)
		}
		fileIDs = append(fileIDs, id)
	}
	rows.Close()
	for _, fileID := range fileIDs {
		s.activityMu.Lock()
		if s.downloads[fileID] > 0 {
			s.activityMu.Unlock()
			continue
		}
		reclaimed, expireErr := s.expireFileLocked(ctx, fileID, "capacity", now)
		s.activityMu.Unlock()
		if expireErr != nil {
			return expireErr
		}
		used -= reclaimed
		if used+reserved+required <= s.config.FilePoolMaxBytes {
			return nil
		}
	}
	return ErrInsufficientStorage
}

func (s *Service) storageUsage(ctx context.Context) (int64, int64, error) {
	var used, reserved sql.NullInt64
	if err := s.db.QueryRowContext(ctx, `
		SELECT COALESCE(SUM(size_bytes), 0) FROM files WHERE status = 'available'
	`).Scan(&used); err != nil {
		return 0, 0, fmt.Errorf("read file pool usage: %w", err)
	}
	if err := s.db.QueryRowContext(ctx, `
		SELECT COALESCE(SUM(reserved_bytes), 0) FROM upload_batches WHERE status = 'pending'
	`).Scan(&reserved); err != nil {
		return 0, 0, fmt.Errorf("read upload reservations: %w", err)
	}
	return used.Int64, reserved.Int64, nil
}

func (s *Service) expireFileLocked(ctx context.Context, fileID, reason string, now time.Time) (int64, error) {
	var storageKey, messageID sql.NullString
	var size int64
	err := s.db.QueryRowContext(ctx, `
		SELECT storage_key, message_id, size_bytes FROM files WHERE id = ? AND status = 'available'
	`, fileID).Scan(&storageKey, &messageID, &size)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	if err != nil {
		return 0, fmt.Errorf("read file for expiry: %w", err)
	}
	result, err := s.db.ExecContext(ctx, `
		UPDATE files
		SET status = 'expired', storage_key = NULL, expired_at = ?, expired_reason = ?
		WHERE id = ? AND status = 'available'
	`, formatTime(now), reason, fileID)
	if err != nil {
		return 0, fmt.Errorf("expire file: %w", err)
	}
	affected, _ := result.RowsAffected()
	if affected != 1 {
		return 0, nil
	}
	if storageKey.Valid {
		_ = os.Remove(filepath.Join(s.config.DataDir, "files", storageKey.String))
	}
	if s.notify != nil && messageID.Valid {
		s.notify("file.expired", ExpiredEvent{FileID: fileID, MessageID: messageID.String, Reason: reason})
	}
	return size, nil
}

func (s *Service) deleteExpiredMetadataLocked(ctx context.Context, now time.Time) error {
	rows, err := s.db.QueryContext(ctx, `
		SELECT m.id, f.id, f.storage_key, f.thumbnail_key
		FROM messages m JOIN files f ON f.message_id = m.id
		WHERE m.metadata_expires_at IS NOT NULL AND m.metadata_expires_at <= ?
	`, formatTime(now))
	if err != nil {
		return fmt.Errorf("query expired file metadata: %w", err)
	}
	type expiredMetadata struct {
		messageID, fileID        string
		storageKey, thumbnailKey sql.NullString
	}
	var values []expiredMetadata
	for rows.Next() {
		var value expiredMetadata
		if err := rows.Scan(&value.messageID, &value.fileID, &value.storageKey, &value.thumbnailKey); err != nil {
			rows.Close()
			return fmt.Errorf("scan expired file metadata: %w", err)
		}
		values = append(values, value)
	}
	rows.Close()
	for _, value := range values {
		s.activityMu.Lock()
		if s.downloads[value.fileID] > 0 {
			s.activityMu.Unlock()
			continue
		}
		tx, err := s.db.BeginTx(ctx, nil)
		if err != nil {
			s.activityMu.Unlock()
			return fmt.Errorf("begin metadata cleanup: %w", err)
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM message_search_fts WHERE message_id = ?`, value.messageID); err != nil {
			tx.Rollback()
			s.activityMu.Unlock()
			return fmt.Errorf("delete expired search metadata: %w", err)
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM files WHERE id = ?`, value.fileID); err != nil {
			tx.Rollback()
			s.activityMu.Unlock()
			return fmt.Errorf("delete expired file metadata: %w", err)
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM messages WHERE id = ?`, value.messageID); err != nil {
			tx.Rollback()
			s.activityMu.Unlock()
			return fmt.Errorf("delete expired message metadata: %w", err)
		}
		if err := tx.Commit(); err != nil {
			s.activityMu.Unlock()
			return fmt.Errorf("commit metadata cleanup: %w", err)
		}
		s.activityMu.Unlock()
		if value.storageKey.Valid {
			_ = os.Remove(filepath.Join(s.config.DataDir, "files", value.storageKey.String))
		}
		if value.thumbnailKey.Valid {
			_ = os.Remove(filepath.Join(s.config.DataDir, "thumbs", value.thumbnailKey.String))
		}
	}
	return nil
}

func (s *Service) removeStaleParts(now time.Time) error {
	directory := filepath.Join(s.config.DataDir, "tmp")
	entries, err := os.ReadDir(directory)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("read temporary upload directory: %w", err)
	}
	cutoff := now.Add(-s.config.UploadSessionTTL - cleanupPartFileExtraTime)
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil || info.ModTime().After(cutoff) {
			continue
		}
		_ = os.Remove(filepath.Join(directory, entry.Name()))
	}
	return nil
}
