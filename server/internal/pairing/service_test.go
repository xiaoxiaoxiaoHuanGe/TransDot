package pairing

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"sync"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/database"
	"transdot.local/transfer-assistant/server/internal/deviceauth"
)

func TestPairingApprovalCreatesAuthenticatedBrowser(t *testing.T) {
	db := testDatabaseWithMaster(t)
	service := NewService(db, 2*time.Minute, 10, nil)
	ctx := context.Background()

	session, err := service.Create(ctx, "Chrome · Windows")
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}
	if len(session.Code) != 6 || len(session.QRSecret) != 43 || len(session.BrowserToken) != 43 {
		t.Fatalf("session secrets have unexpected lengths: %+v", session)
	}
	if session.DeviceName != "Chrome · Windows" {
		t.Fatalf("DeviceName = %q", session.DeviceName)
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

func TestPairingAddsBrowsersWithoutReplacingExisting(t *testing.T) {
	db := testDatabaseWithMaster(t)
	var events []string
	service := NewService(db, 2*time.Minute, 10, func(eventType string, _ any) {
		events = append(events, eventType)
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

	second, err := service.Create(ctx, "Office")
	if err != nil {
		t.Fatalf("Create() second error = %v", err)
	}
	credential := Credential{Code: second.Code}
	if err := service.Approve(ctx, credential, "master-1", true); err != nil {
		t.Fatalf("Approve() error = %v", err)
	}
	if _, err := service.Poll(ctx, second.ID, second.BrowserToken); err != nil {
		t.Fatalf("consume replacement browser: %v", err)
	}

	if _, err := authService.Authenticate(ctx, first.BrowserToken, deviceauth.WindowsBrowser); err != nil {
		t.Fatalf("old browser auth error = %v", err)
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
	if activeBrowsers != 2 {
		t.Fatalf("active browser count = %d, want 2", activeBrowsers)
	}
	if oldDevice.ID == "" || len(events) != 2 || events[0] != "device.created" || events[1] != "device.created" {
		t.Fatalf("created events = %v", events)
	}
}

func TestPairingRejectsBrowserBeyondConfiguredLimit(t *testing.T) {
	db := testDatabaseWithMaster(t)
	service := NewService(db, 2*time.Minute, 1, nil)
	ctx := context.Background()
	first := createAndApprove(t, service, false)
	if _, err := service.Poll(ctx, first.ID, first.BrowserToken); err != nil {
		t.Fatalf("consume first browser: %v", err)
	}
	second, err := service.Create(ctx, "Second")
	if err != nil {
		t.Fatal(err)
	}
	err = service.Approve(ctx, Credential{SessionID: second.ID, QRSecret: second.QRSecret}, "master-1", false)
	if !errors.Is(err, ErrBrowserLimitReached) {
		t.Fatalf("Approve() error = %v, want ErrBrowserLimitReached", err)
	}
	var active int
	if err := db.QueryRow(`SELECT COUNT(*) FROM devices WHERE device_type='windows_browser' AND revoked_at IS NULL`).Scan(&active); err != nil || active != 1 {
		t.Fatalf("active browsers = %d, %v", active, err)
	}
}

func TestConcurrentPairingConsumptionCannotExceedLimit(t *testing.T) {
	db := testDatabaseWithMaster(t)
	service := NewService(db, 2*time.Minute, 1, nil)
	first := createAndApprove(t, service, false)
	second := createAndApprove(t, service, false)

	var wg sync.WaitGroup
	errorsFound := make(chan error, 2)
	for _, session := range []Session{first, second} {
		wg.Add(1)
		go func(session Session) {
			defer wg.Done()
			_, err := service.Poll(context.Background(), session.ID, session.BrowserToken)
			errorsFound <- err
		}(session)
	}
	wg.Wait()
	close(errorsFound)
	var succeeded, limited int
	for err := range errorsFound {
		switch {
		case err == nil:
			succeeded++
		case errors.Is(err, ErrBrowserLimitReached):
			limited++
		default:
			t.Fatalf("unexpected Poll error: %v", err)
		}
	}
	if succeeded != 1 || limited != 1 {
		t.Fatalf("concurrent results succeeded/limited = %d/%d", succeeded, limited)
	}
}

func TestFiveWrongQRSecretsExpireSession(t *testing.T) {
	db := testDatabaseWithMaster(t)
	service := NewService(db, 2*time.Minute, 10, nil)
	ctx := context.Background()
	session, err := service.Create(ctx, "Browser")
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
	service := NewService(db, 2*time.Minute, 10, nil)
	ctx := context.Background()
	session, err := service.Create(ctx, "Browser")
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

	_, err = NewService(db, 2*time.Minute, 10, nil).Create(context.Background(), "Browser")
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
	session, err := service.Create(context.Background(), "Browser")
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
