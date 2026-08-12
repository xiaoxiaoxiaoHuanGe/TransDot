package deviceauth

import (
	"context"
	"crypto/sha256"
	"errors"
	"testing"

	"transdot.local/transfer-assistant/server/internal/database"
)

func TestAuthenticateActiveDevice(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	defer db.Close()

	token := "browser-token"
	tokenHash := sha256.Sum256([]byte(token))
	if _, err := db.Exec(`
		INSERT INTO devices (id, device_type, token_hash)
		VALUES ('browser-1', 'windows_browser', ?)
	`, tokenHash[:]); err != nil {
		t.Fatalf("insert device: %v", err)
	}

	service := NewService(db)
	device, err := service.Authenticate(context.Background(), token, WindowsBrowser)
	if err != nil {
		t.Fatalf("Authenticate() error = %v", err)
	}
	if device.ID != "browser-1" || device.Type != WindowsBrowser {
		t.Fatalf("device = %+v", device)
	}
}

func TestAuthenticateRejectsWrongDeviceType(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	defer db.Close()

	tokenHash := sha256.Sum256([]byte("master-token"))
	if _, err := db.Exec(`
		INSERT INTO devices (id, device_type, token_hash)
		VALUES ('master-1', 'android_master', ?)
	`, tokenHash[:]); err != nil {
		t.Fatalf("insert device: %v", err)
	}

	_, err = NewService(db).Authenticate(context.Background(), "master-token", WindowsBrowser)
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("Authenticate() error = %v, want ErrUnauthorized", err)
	}
}

func TestAuthenticateReportsRevokedDevice(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	defer db.Close()

	tokenHash := sha256.Sum256([]byte("old-browser-token"))
	if _, err := db.Exec(`
		INSERT INTO devices (id, device_type, token_hash, revoked_at)
		VALUES ('browser-old', 'windows_browser', ?, '2026-01-01T00:00:00Z')
	`, tokenHash[:]); err != nil {
		t.Fatalf("insert device: %v", err)
	}

	_, err = NewService(db).Authenticate(context.Background(), "old-browser-token", WindowsBrowser)
	if !errors.Is(err, ErrDeviceRevoked) {
		t.Fatalf("Authenticate() error = %v, want ErrDeviceRevoked", err)
	}
}
