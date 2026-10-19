package messages

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"unicode/utf8"
)

type viewerKey struct{}

// WithViewer scopes all message operations to this authenticated device.
func WithViewer(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, viewerKey{}, id)
}

// Hex encoding keeps the authenticated viewer identifier a SQL literal, never SQL syntax.
func visibility(ctx context.Context) string {
	id, _ := ctx.Value(viewerKey{}).(string)
	if id == "" {
		return "1=1"
	}
	// IDs are generated UUIDs, but binding through a hex literal also supports test IDs safely.
	return fmt.Sprintf(`EXISTS (SELECT 1 FROM devices viewer WHERE viewer.id=CAST(X'%x' AS TEXT) AND viewer.revoked_at IS NULL AND
 (viewer.device_type='android_master' OR viewer.history_allowed=1 OR EXISTS
 (SELECT 1 FROM message_order mo WHERE mo.message_id=m.id AND mo.sequence>viewer.history_boundary)))`, []byte(id))
}

var ErrConflict = errors.New("message was modified by another device")
var ErrNotText = errors.New("only text messages can be edited")

func (s *Service) Edit(ctx context.Context, id, content string, revision int) (Message, error) {
	if !utf8.ValidString(content) {
		return Message{}, ErrInvalidUTF8
	}
	if strings.TrimSpace(content) == "" {
		return Message{}, ErrEmptyText
	}
	if len([]byte(content)) > MaximumTextLen {
		return Message{}, ErrTextTooLarge
	}
	if _, err := s.Get(ctx, id); err != nil {
		return Message{}, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Message{}, err
	}
	defer tx.Rollback()
	var kind string
	var current int
	if err = tx.QueryRowContext(ctx, `SELECT type,revision FROM messages m WHERE m.id=? AND m.deleted_at IS NULL AND `+visibility(ctx), id).Scan(&kind, &current); errors.Is(err, sql.ErrNoRows) {
		return Message{}, ErrNotFound
	} else if err != nil {
		return Message{}, err
	}
	if kind != TypeText {
		return Message{}, ErrNotText
	}
	if current != revision {
		return Message{}, ErrConflict
	}
	if _, err = tx.ExecContext(ctx, `UPDATE messages SET text_content=?,edited_at=?,revision=revision+1 WHERE id=?`, content, formatTime(s.now()), id); err != nil {
		return Message{}, err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE message_search_fts SET text_content=? WHERE message_id=?`, content, id); err != nil {
		return Message{}, err
	}
	if err = tx.Commit(); err != nil {
		return Message{}, err
	}
	return s.Get(ctx, id)
}

type HistoryAccess struct {
	DeviceID    string  `json:"device_id"`
	DisplayName string  `json:"display_name"`
	CreatedAt   string  `json:"created_at"`
	Allowed     bool    `json:"allowed"`
	Status      string  `json:"status"`
	RequestedAt *string `json:"requested_at"`
}

func (s *Service) History(ctx context.Context, id string) (HistoryAccess, error) {
	var access HistoryAccess
	err := s.db.QueryRowContext(ctx, `SELECT id,display_name,created_at,history_allowed,history_request,history_requested_at FROM devices WHERE id=? AND device_type='windows_browser' AND revoked_at IS NULL`, id).Scan(&access.DeviceID, &access.DisplayName, &access.CreatedAt, &access.Allowed, &access.Status, &access.RequestedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return access, ErrNotFound
	}
	return access, err
}
func (s *Service) Histories(ctx context.Context) ([]HistoryAccess, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id,display_name,created_at,history_allowed,history_request,history_requested_at FROM devices WHERE device_type='windows_browser' AND revoked_at IS NULL ORDER BY history_request='pending' DESC,created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []HistoryAccess{}
	for rows.Next() {
		var a HistoryAccess
		if err = rows.Scan(&a.DeviceID, &a.DisplayName, &a.CreatedAt, &a.Allowed, &a.Status, &a.RequestedAt); err != nil {
			return nil, err
		}
		result = append(result, a)
	}
	return result, rows.Err()
}
func (s *Service) RequestHistory(ctx context.Context, id string) (HistoryAccess, error) {
	if _, err := s.History(ctx, id); err != nil {
		return HistoryAccess{}, err
	}
	_, err := s.db.ExecContext(ctx, `UPDATE devices SET history_request='pending',history_requested_at=? WHERE id=? AND history_allowed=0 AND history_request!='pending'`, formatTime(s.now()), id)
	if err != nil {
		return HistoryAccess{}, err
	}
	return s.History(ctx, id)
}
func (s *Service) DecideHistory(ctx context.Context, id, decision string) error {
	if _, err := s.History(ctx, id); err != nil {
		return err
	}
	var allowed bool
	var status string
	switch decision {
	case "approve":
		allowed = true
		status = "approved"
	case "reject":
		status = "rejected"
	case "revoke":
		status = "none"
	default:
		return ErrInvalidSearch
	}
	_, err := s.db.ExecContext(ctx, `UPDATE devices SET history_allowed=?,history_request=? WHERE id=? AND revoked_at IS NULL`, allowed, status, id)
	return err
}
func (s *Service) CanReadFile(ctx context.Context, id string) bool {
	var count int
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM files f JOIN messages m ON f.message_id=m.id WHERE f.id=? AND m.deleted_at IS NULL AND `+visibility(ctx), id).Scan(&count)
	return err == nil && count == 1
}
func (s *Service) CanReadSequence(ctx context.Context, id string, sequence int64) bool {
	var allowed bool
	err := s.db.QueryRowContext(ctx, `SELECT device_type='android_master' OR history_allowed=1 OR ? > history_boundary FROM devices WHERE id=? AND revoked_at IS NULL`, sequence, id).Scan(&allowed)
	return err == nil && allowed
}
func (s *Service) Sequence(ctx context.Context, id string) int64 {
	var seq int64
	_ = s.db.QueryRowContext(ctx, `SELECT sequence FROM message_order WHERE message_id=?`, id).Scan(&seq)
	return seq
}
