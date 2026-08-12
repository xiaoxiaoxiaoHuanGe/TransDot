package setup

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"testing"

	"transdot.local/transfer-assistant/server/internal/database"
)

const testSetupToken = "0123456789abcdef0123456789abcdef"

func TestClaimCreatesSingleAndroidMasterWithoutStoringPlaintext(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	defer db.Close()

	service := NewService(db, testSetupToken)
	service.random = bytes.NewReader(bytes.Repeat([]byte{0x5a}, masterTokenBytes))

	result, err := service.Claim(context.Background(), testSetupToken)
	if err != nil {
		t.Fatalf("Claim() error = %v", err)
	}
	decoded, err := base64.RawURLEncoding.DecodeString(result.MasterToken)
	if err != nil {
		t.Fatalf("decode master token: %v", err)
	}
	if len(decoded) != masterTokenBytes {
		t.Fatalf("decoded master token length = %d, want %d", len(decoded), masterTokenBytes)
	}

	var storedHash []byte
	var deviceType string
	if err := db.QueryRow("SELECT device_type, token_hash FROM devices WHERE id = ?", result.DeviceID).Scan(&deviceType, &storedHash); err != nil {
		t.Fatalf("query device: %v", err)
	}
	if deviceType != "android_master" {
		t.Fatalf("device_type = %q", deviceType)
	}
	expectedHash := sha256.Sum256([]byte(result.MasterToken))
	if !bytes.Equal(storedHash, expectedHash[:]) {
		t.Fatal("stored token hash does not match SHA-256(master token)")
	}
	if bytes.Contains(storedHash, []byte(result.MasterToken)) {
		t.Fatal("stored token hash contains the plaintext master token")
	}

	initialized, err := service.Status(context.Background())
	if err != nil || !initialized {
		t.Fatalf("Status() = %v, %v; want true, nil", initialized, err)
	}
}

func TestClaimRejectsWrongSetupTokenWithoutInitializing(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	defer db.Close()

	service := NewService(db, testSetupToken)
	_, err = service.Claim(context.Background(), "wrong-token")
	if !errors.Is(err, ErrInvalidSetupToken) {
		t.Fatalf("Claim() error = %v, want ErrInvalidSetupToken", err)
	}

	initialized, err := service.Status(context.Background())
	if err != nil || initialized {
		t.Fatalf("Status() = %v, %v; want false, nil", initialized, err)
	}
}

func TestClaimCannotRunTwice(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	defer db.Close()

	service := NewService(db, testSetupToken)
	if _, err := service.Claim(context.Background(), testSetupToken); err != nil {
		t.Fatalf("first Claim() error = %v", err)
	}
	if _, err := service.Claim(context.Background(), testSetupToken); !errors.Is(err, ErrAlreadyInitialized) {
		t.Fatalf("second Claim() error = %v, want ErrAlreadyInitialized", err)
	}

	var activeMasters int
	if err := db.QueryRow("SELECT COUNT(*) FROM devices WHERE device_type = 'android_master' AND revoked_at IS NULL").Scan(&activeMasters); err != nil {
		t.Fatalf("count active masters: %v", err)
	}
	if activeMasters != 1 {
		t.Fatalf("active master count = %d, want 1", activeMasters)
	}
}
