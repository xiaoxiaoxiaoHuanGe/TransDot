package deviceauth

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

const (
	AndroidMaster  = "android_master"
	WindowsBrowser = "windows_browser"
)

var (
	ErrUnauthorized  = errors.New("device token is invalid")
	ErrDeviceRevoked = errors.New("device token has been revoked")
)

type Device struct {
	ID   string
	Type string
}

type Service struct {
	db *sql.DB
}

func NewService(db *sql.DB) *Service {
	return &Service{db: db}
}

func (s *Service) Authenticate(ctx context.Context, token, expectedType string) (Device, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return Device{}, ErrUnauthorized
	}

	tokenHash := sha256.Sum256([]byte(token))
	var device Device
	var revokedAt sql.NullString
	err := s.db.QueryRowContext(ctx, `
		SELECT id, device_type, revoked_at
		FROM devices
		WHERE token_hash = ?
	`, tokenHash[:]).Scan(&device.ID, &device.Type, &revokedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Device{}, ErrUnauthorized
	}
	if err != nil {
		return Device{}, fmt.Errorf("query device token: %w", err)
	}
	if revokedAt.Valid {
		return Device{}, ErrDeviceRevoked
	}
	if device.Type != expectedType {
		return Device{}, ErrUnauthorized
	}

	if _, err := s.db.ExecContext(ctx, `
		UPDATE devices
		SET last_seen_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
		WHERE id = ? AND revoked_at IS NULL
	`, device.ID); err != nil {
		return Device{}, fmt.Errorf("update device last seen: %w", err)
	}

	return device, nil
}
