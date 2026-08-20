package rebind

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/database"
	"transdot.local/transfer-assistant/server/internal/setup"
)

func TestClaimRotatesMasterAndIsSingleUse(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err = setup.NewService(db, "owner").Claim(context.Background(), "owner"); err != nil {
		t.Fatal(err)
	}
	insertBrowser(t, db, "browser-1")
	service := NewService(db, "instance-1", "fingerprint-1", 2*time.Minute)
	session, err := service.Create(context.Background(), "browser-1")
	if err != nil {
		t.Fatal(err)
	}
	claimed, err := service.Claim(context.Background(), session.ID, session.Secret, "instance-1")
	if err != nil {
		t.Fatal(err)
	}
	if claimed.DeviceID == "" || claimed.MasterToken == "" {
		t.Fatalf("claim = %#v", claimed)
	}
	if _, err = service.Claim(context.Background(), session.ID, session.Secret, "instance-1"); !errors.Is(err, ErrConsumed) {
		t.Fatalf("second claim error = %v", err)
	}
	var active int
	if err = db.QueryRow(`SELECT COUNT(*) FROM devices WHERE device_type='android_master' AND revoked_at IS NULL`).Scan(&active); err != nil {
		t.Fatal(err)
	}
	if active != 1 {
		t.Fatalf("active masters = %d", active)
	}
}

func TestCreateRequiresInitializedServer(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	_, err = NewService(db, "instance-1", "fingerprint-1", time.Minute).Create(context.Background(), "browser-1")
	if !errors.Is(err, ErrNotInitialized) {
		t.Fatalf("Create error = %v", err)
	}
}

func TestClaimRejectsWrongInstanceAndExpiredSession(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err = setup.NewService(db, "owner").Claim(context.Background(), "owner"); err != nil {
		t.Fatal(err)
	}
	insertBrowser(t, db, "browser-1")
	service := NewService(db, "instance-1", "fingerprint-1", time.Minute)
	session, err := service.Create(context.Background(), "browser-1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = service.Claim(context.Background(), session.ID, session.Secret, "other"); !errors.Is(err, ErrInvalid) {
		t.Fatalf("instance error = %v", err)
	}
	service.now = func() time.Time { return session.ExpiresAt.Add(time.Second) }
	if _, err = service.Claim(context.Background(), session.ID, session.Secret, "instance-1"); !errors.Is(err, ErrExpired) {
		t.Fatalf("expiry error = %v", err)
	}
}

func TestSuccessfulClaimExpiresOtherPendingSessions(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err = setup.NewService(db, "owner").Claim(context.Background(), "owner"); err != nil {
		t.Fatal(err)
	}
	insertBrowser(t, db, "browser-1")
	service := NewService(db, "instance-1", "fingerprint-1", time.Minute)
	first, err := service.Create(context.Background(), "browser-1")
	if err != nil {
		t.Fatal(err)
	}
	second, err := service.Create(context.Background(), "browser-1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = service.Claim(context.Background(), second.ID, second.Secret, "instance-1"); err != nil {
		t.Fatal(err)
	}
	if _, err = service.Claim(context.Background(), first.ID, first.Secret, "instance-1"); !errors.Is(err, ErrExpired) {
		t.Fatalf("older session claim error = %v", err)
	}
}

func TestCreateExpiresPreviousPendingSessionForBrowser(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err = setup.NewService(db, "owner").Claim(context.Background(), "owner"); err != nil {
		t.Fatal(err)
	}
	insertBrowser(t, db, "browser-1")
	service := NewService(db, "instance-1", "fingerprint-1", time.Minute)
	first, err := service.Create(context.Background(), "browser-1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = service.Create(context.Background(), "browser-1"); err != nil {
		t.Fatal(err)
	}

	if _, err = service.Claim(context.Background(), first.ID, first.Secret, "instance-1"); !errors.Is(err, ErrExpired) {
		t.Fatalf("refreshed session claim error = %v, want ErrExpired", err)
	}
}

func TestClaimRequiresServerToRemainInitialized(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	claimed, err := setup.NewService(db, "owner").Claim(context.Background(), "owner")
	if err != nil {
		t.Fatal(err)
	}
	insertBrowser(t, db, "browser-1")
	service := NewService(db, "instance-1", "fingerprint-1", time.Minute)
	session, err := service.Create(context.Background(), "browser-1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec(`UPDATE app_state SET initialized=0 WHERE id=1`); err != nil {
		t.Fatal(err)
	}

	if _, err = service.Claim(context.Background(), session.ID, session.Secret, "instance-1"); !errors.Is(err, ErrNotInitialized) {
		t.Fatalf("Claim error = %v, want ErrNotInitialized", err)
	}
	var revokedAt sql.NullString
	if err = db.QueryRow(`SELECT revoked_at FROM devices WHERE id=?`, claimed.DeviceID).Scan(&revokedAt); err != nil {
		t.Fatal(err)
	}
	if revokedAt.Valid {
		t.Fatalf("old master revoked_at = %q", revokedAt.String)
	}
}

func TestClaimRollsBackRevocationWhenNewMasterCannotBeCreated(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	claimed, err := setup.NewService(db, "owner").Claim(context.Background(), "owner")
	if err != nil {
		t.Fatal(err)
	}
	insertBrowser(t, db, "browser-1")
	service := NewService(db, "instance-1", "fingerprint-1", time.Minute)
	session, err := service.Create(context.Background(), "browser-1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = db.Exec(`CREATE TRIGGER reject_rebound_master BEFORE INSERT ON devices WHEN NEW.device_type='android_master' BEGIN SELECT RAISE(ABORT, 'reject master'); END`); err != nil {
		t.Fatal(err)
	}

	if _, err = service.Claim(context.Background(), session.ID, session.Secret, "instance-1"); err == nil {
		t.Fatal("Claim succeeded despite rejected master insert")
	}
	var revokedAt sql.NullString
	if err = db.QueryRow(`SELECT revoked_at FROM devices WHERE id=?`, claimed.DeviceID).Scan(&revokedAt); err != nil {
		t.Fatal(err)
	}
	if revokedAt.Valid {
		t.Fatalf("old master revoked_at = %q", revokedAt.String)
	}
	var status string
	if err = db.QueryRow(`SELECT status FROM rebind_sessions WHERE id=?`, session.ID).Scan(&status); err != nil {
		t.Fatal(err)
	}
	if status != StatusPending {
		t.Fatalf("session status = %q, want pending", status)
	}
}

func TestPollReturnsDatabaseErrorBeforeOwnershipCheck(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	service := NewService(db, "instance-1", "fingerprint-1", time.Minute)
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}

	_, err = service.Poll(context.Background(), "session-1", "browser-1")
	if err == nil || errors.Is(err, ErrInvalid) {
		t.Fatalf("Poll error = %v, want database error", err)
	}
}

func insertBrowser(t *testing.T, db *sql.DB, id string) {
	t.Helper()
	hash := sha256.Sum256([]byte("browser-token"))
	if _, err := db.Exec(`INSERT INTO devices(id, device_type, token_hash) VALUES (?, 'windows_browser', ?)`, id, hash[:]); err != nil {
		t.Fatal(err)
	}
}
