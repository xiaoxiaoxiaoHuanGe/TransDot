package devices

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

const (
	DefaultBrowserName = "浏览器设备"
	MaximumNameRunes   = 64
	timestampFormat    = "2006-01-02T15:04:05.000000000Z07:00"
)

var (
	ErrInvalidDisplayName = errors.New("device display name is invalid")
	ErrDeviceNotFound     = errors.New("browser device not found")
)

type BrowserDevice struct {
	ID          string     `json:"id"`
	DisplayName string     `json:"display_name"`
	CreatedAt   time.Time  `json:"created_at"`
	LastSeenAt  *time.Time `json:"last_seen_at"`
}

type Service struct {
	db        *sql.DB
	onRevoked func([]string)
	publish   func(string, any)
	now       func() time.Time
}

func NewService(db *sql.DB, onRevoked func([]string), publish func(string, any)) *Service {
	return &Service{db: db, onRevoked: onRevoked, publish: publish, now: time.Now}
}

func NormalizeDisplayName(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" || !utf8.ValidString(value) || utf8.RuneCountInString(value) > MaximumNameRunes {
		return "", ErrInvalidDisplayName
	}
	for _, character := range value {
		if unicode.IsControl(character) {
			return "", ErrInvalidDisplayName
		}
	}
	return value, nil
}

func (s *Service) ListActiveBrowsers(ctx context.Context) ([]BrowserDevice, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT id, display_name, created_at, last_seen_at
		FROM devices
		WHERE device_type = 'windows_browser' AND revoked_at IS NULL
		ORDER BY COALESCE(last_seen_at, created_at) DESC, created_at DESC, id DESC
	`)
	if err != nil {
		return nil, fmt.Errorf("list active browser devices: %w", err)
	}
	defer rows.Close()
	result := make([]BrowserDevice, 0)
	for rows.Next() {
		device, err := scanBrowser(rows.Scan)
		if err != nil {
			return nil, fmt.Errorf("scan active browser device: %w", err)
		}
		result = append(result, device)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate active browser devices: %w", err)
	}
	return result, nil
}

func (s *Service) CountActiveBrowsers(ctx context.Context) (int, error) {
	var count int
	if err := s.db.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM devices
		WHERE device_type = 'windows_browser' AND revoked_at IS NULL
	`).Scan(&count); err != nil {
		return 0, fmt.Errorf("count active browser devices: %w", err)
	}
	return count, nil
}

func (s *Service) RenameBrowser(ctx context.Context, deviceID, displayName string) (BrowserDevice, error) {
	return s.rename(ctx, deviceID, displayName)
}

func (s *Service) RenameSelf(ctx context.Context, deviceID, displayName string) (BrowserDevice, error) {
	return s.rename(ctx, deviceID, displayName)
}

func (s *Service) rename(ctx context.Context, deviceID, displayName string) (BrowserDevice, error) {
	normalized, err := NormalizeDisplayName(displayName)
	if err != nil {
		return BrowserDevice{}, err
	}
	result, err := s.db.ExecContext(ctx, `
		UPDATE devices SET display_name = ?
		WHERE id = ? AND device_type = 'windows_browser' AND revoked_at IS NULL
	`, normalized, strings.TrimSpace(deviceID))
	if err != nil {
		return BrowserDevice{}, fmt.Errorf("rename browser device: %w", err)
	}
	changed, err := result.RowsAffected()
	if err != nil {
		return BrowserDevice{}, fmt.Errorf("read renamed browser count: %w", err)
	}
	if changed != 1 {
		return BrowserDevice{}, ErrDeviceNotFound
	}
	device, err := s.browserByID(ctx, deviceID, false)
	if err != nil {
		return BrowserDevice{}, err
	}
	if s.publish != nil {
		s.publish("device.updated", map[string]string{"id": device.ID, "display_name": device.DisplayName})
	}
	return device, nil
}

func (s *Service) RevokeBrowser(ctx context.Context, deviceID string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin browser revocation: %w", err)
	}
	defer tx.Rollback()
	var revokedAt sql.NullString
	err = tx.QueryRowContext(ctx, `
		SELECT revoked_at FROM devices WHERE id = ? AND device_type = 'windows_browser'
	`, strings.TrimSpace(deviceID)).Scan(&revokedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrDeviceNotFound
	}
	if err != nil {
		return fmt.Errorf("find browser to revoke: %w", err)
	}
	if revokedAt.Valid {
		return nil
	}
	if _, err := tx.ExecContext(ctx, `UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`, formatTime(s.now().UTC()), strings.TrimSpace(deviceID)); err != nil {
		return fmt.Errorf("revoke browser device: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit browser revocation: %w", err)
	}
	if s.onRevoked != nil {
		s.onRevoked([]string{strings.TrimSpace(deviceID)})
	}
	if s.publish != nil {
		s.publish("device.revoked", map[string]string{"id": strings.TrimSpace(deviceID)})
	}
	return nil
}

func (s *Service) browserByID(ctx context.Context, deviceID string, includeRevoked bool) (BrowserDevice, error) {
	query := `SELECT id, display_name, created_at, last_seen_at FROM devices WHERE id = ? AND device_type = 'windows_browser'`
	if !includeRevoked {
		query += ` AND revoked_at IS NULL`
	}
	device, err := scanBrowser(s.db.QueryRowContext(ctx, query, strings.TrimSpace(deviceID)).Scan)
	if errors.Is(err, sql.ErrNoRows) {
		return BrowserDevice{}, ErrDeviceNotFound
	}
	if err != nil {
		return BrowserDevice{}, fmt.Errorf("read browser device: %w", err)
	}
	return device, nil
}

func scanBrowser(scan func(...any) error) (BrowserDevice, error) {
	var device BrowserDevice
	var createdAtRaw string
	var lastSeenRaw sql.NullString
	if err := scan(&device.ID, &device.DisplayName, &createdAtRaw, &lastSeenRaw); err != nil {
		return BrowserDevice{}, err
	}
	createdAt, err := time.Parse(time.RFC3339Nano, createdAtRaw)
	if err != nil {
		return BrowserDevice{}, fmt.Errorf("parse browser created_at: %w", err)
	}
	device.CreatedAt = createdAt
	if lastSeenRaw.Valid {
		lastSeen, err := time.Parse(time.RFC3339Nano, lastSeenRaw.String)
		if err != nil {
			return BrowserDevice{}, fmt.Errorf("parse browser last_seen_at: %w", err)
		}
		device.LastSeenAt = &lastSeen
	}
	return device, nil
}

func formatTime(value time.Time) string {
	return value.UTC().Format(timestampFormat)
}
