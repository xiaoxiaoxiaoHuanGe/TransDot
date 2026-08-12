package pairing

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/database"
	"transdot.local/transfer-assistant/server/internal/deviceauth"
)

func TestPairingApprovalCreatesAuthenticatedBrowser(t *testing.T) {
	db := testDatabaseWithMaster(t)
	service := NewService(db, 2*time.Minute)
	ctx := context.Background()

	session, err := service.Create(ctx)
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}
	if len(session.Code) != 6 || len(session.QRSecret) != 43 || len(session.BrowserToken) != 43 {
		t.Fatalf("session secrets have unexpected lengths: %+v", session)
	}

	pending, err := service.Poll(ctx, session.ID, session.BrowserToken)
	if err != nil || pending.Status != StatusPending {
		t.Fatalf("Poll() = %+v, %v; want pending", pending, err)
	}

	credential := Credential{SessionID: session.ID, QRSecret: session.QRSecret}
	if err := service.Approve(ctx, credential, "master-1", false); err != nil {
		t.Fatalf("Approve() error = %v", err)
	}
	approved, err := service.Poll(ctx, session.ID, session.BrowserToken)
	if err != nil {
		t.Fatalf("Poll() after approval error = %v", err)
	}
	if approved.Status != StatusApproved || approved.BrowserToken != session.BrowserToken {
		t.Fatalf("approved poll = %+v", approved)
	}

	device, err := deviceauth.NewService(db).Authenticate(ctx, session.BrowserToken, deviceauth.WindowsBrowser)
	if err != nil || device.Type != deviceauth.WindowsBrowser {
		t.Fatalf("browser Authenticate() = %+v, %v", device, err)
	}
	if retry, err := service.Poll(ctx, session.ID, session.BrowserToken); err != nil || retry.Status != StatusApproved {
		t.Fatalf("retry Poll() = %+v, %v; want approved", retry, err)
	}
}

func TestPairingReplacementRevokesOldBrowserAtomically(t *testing.T) {
	db := testDatabaseWithMaster(t)
	var revokedDeviceIDs []string
	service := NewService(db, 2*time.Minute, func(deviceIDs []string) {
		revokedDeviceIDs = append(revokedDeviceIDs, deviceIDs...)
	})
	ctx := context.Background()

	first := createAndApprove(t, service, false)
	if _, err := service.Poll(ctx, first.ID, first.BrowserToken); err != nil {
		t.Fatalf("consume first browser: %v", err)
	}
	authService := deviceauth.NewService(db)
	oldDevice, err := authService.Authenticate(ctx, first.BrowserToken, deviceauth.WindowsBrowser)
	if err != nil {
		t.Fatalf("authenticate first browser before replacement: %v", err)
	}

	second, err := service.Create(ctx)
	if err != nil {
		t.Fatalf("Create() second error = %v", err)
	}
	credential := Credential{Code: second.Code}
	if err := service.Approve(ctx, credential, "master-1", false); !errors.Is(err, ErrReplacementRequired) {
		t.Fatalf("Approve() error = %v, want ErrReplacementRequired", err)
	}
	if err := service.Approve(ctx, credential, "master-1", true); err != nil {
		t.Fatalf("Approve(replace) error = %v", err)
	}
	if _, err := service.Poll(ctx, second.ID, second.BrowserToken); err != nil {
		t.Fatalf("consume replacement browser: %v", err)
	}

	_, oldDeviceErr := authService.Authenticate(ctx, first.BrowserToken, deviceauth.WindowsBrowser)
	if !errors.Is(oldDeviceErr, deviceauth.ErrDeviceRevoked) {
		t.Fatalf("old browser auth error = %v, want revoked", oldDeviceErr)
	}
	if _, err := authService.Authenticate(ctx, second.BrowserToken, deviceauth.WindowsBrowser); err != nil {
		t.Fatalf("new browser auth error = %v", err)
	}

	var activeBrowsers int
	if err := db.QueryRow(`
		SELECT COUNT(*) FROM devices
		WHERE device_type = 'windows_browser' AND revoked_at IS NULL
	`).Scan(&activeBrowsers); err != nil {
		t.Fatalf("count active browsers: %v", err)
	}
	if activeBrowsers != 1 {
		t.Fatalf("active browser count = %d, want 1", activeBrowsers)
	}
	if len(revokedDeviceIDs) != 1 || revokedDeviceIDs[0] != oldDevice.ID {
		t.Fatalf("revoked notification = %v, want [%s]", revokedDeviceIDs, oldDevice.ID)
	}
}

func TestFiveWrongQRSecretsExpireSession(t *testing.T) {
	db := testDatabaseWithMaster(t)
	service := NewService(db, 2*time.Minute)
	ctx := context.Background()
	session, err := service.Create(ctx)
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}

	credential := Credential{SessionID: session.ID, QRSecret: "wrong-secret"}
	for attempt := 0; attempt < 5; attempt++ {
		if err := service.Approve(ctx, credential, "master-1", false); !errors.Is(err, ErrInvalidPairing) {
			t.Fatalf("attempt %d error = %v, want invalid", attempt+1, err)
		}
	}
	result, err := service.Poll(ctx, session.ID, session.BrowserToken)
	if err != nil || result.Status != StatusExpired {
		t.Fatalf("Poll() = %+v, %v; want expired", result, err)
	}
}

func TestFiveWrongManualCodesExpireActiveSession(t *testing.T) {
	db := testDatabaseWithMaster(t)
	service := NewService(db, 2*time.Minute)
	ctx := context.Background()
	session, err := service.Create(ctx)
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}

	wrongCode := "000000"
	if session.Code == wrongCode {
		wrongCode = "000001"
	}
	credential := Credential{Code: wrongCode}
	for attempt := 0; attempt < 5; attempt++ {
		if err := service.Approve(ctx, credential, "master-1", false); !errors.Is(err, ErrInvalidPairing) {
			t.Fatalf("attempt %d error = %v, want invalid", attempt+1, err)
		}
	}
	result, err := service.Poll(ctx, session.ID, session.BrowserToken)
	if err != nil || result.Status != StatusExpired {
		t.Fatalf("Poll() = %+v, %v; want expired", result, err)
	}
}

func TestCreateRequiresAndroidMaster(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	defer db.Close()

	_, err = NewService(db, 2*time.Minute).Create(context.Background())
	if !errors.Is(err, ErrNotInitialized) {
		t.Fatalf("Create() error = %v, want ErrNotInitialized", err)
	}
}

func testDatabaseWithMaster(t *testing.T) *sql.DB {
	t.Helper()
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	t.Cleanup(func() { db.Close() })
	tokenHash := sha256.Sum256([]byte("master-token"))
	if _, err := db.Exec(`
		INSERT INTO devices (id, device_type, token_hash)
		VALUES ('master-1', 'android_master', ?)
	`, tokenHash[:]); err != nil {
		t.Fatalf("insert master: %v", err)
	}
	return db
}

func createAndApprove(t *testing.T, service *Service, replace bool) Session {
	t.Helper()
	session, err := service.Create(context.Background())
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}
	if err := service.Approve(
		context.Background(),
		Credential{SessionID: session.ID, QRSecret: session.QRSecret},
		"master-1",
		replace,
	); err != nil {
		t.Fatalf("Approve() error = %v", err)
	}
	return session
}
