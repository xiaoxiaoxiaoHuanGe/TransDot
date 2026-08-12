package httpserver

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/deviceauth"
	"transdot.local/transfer-assistant/server/internal/pairing"
	"transdot.local/transfer-assistant/server/internal/setup"
)

type fakeDatabase struct {
	err error
}

func (db fakeDatabase) PingContext(context.Context) error {
	return db.err
}

func testServer(db fakeDatabase) http.Handler {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	web := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("web"))
	})
	return New(db, fakeSetupService{}, fakeAuthService{}, fakePairingService{}, web, logger)
}

type fakeSetupService struct {
	initialized bool
	result      setup.ClaimResult
	err         error
}

type fakeAuthService struct {
	device deviceauth.Device
	err    error
}

func (s fakeAuthService) Authenticate(context.Context, string, string) (deviceauth.Device, error) {
	return s.device, s.err
}

type fakePairingService struct {
	session pairing.Session
	poll    pairing.PollResult
	err     error
}

func (s fakePairingService) Create(context.Context) (pairing.Session, error) {
	return s.session, s.err
}

func (s fakePairingService) Approve(context.Context, pairing.Credential, string, bool) error {
	return s.err
}

func (s fakePairingService) Reject(context.Context, pairing.Credential) error {
	return s.err
}

func (s fakePairingService) Poll(context.Context, string, string) (pairing.PollResult, error) {
	return s.poll, s.err
}

func (s fakeSetupService) Status(context.Context) (bool, error) {
	return s.initialized, s.err
}

func (s fakeSetupService) Claim(context.Context, string) (setup.ClaimResult, error) {
	return s.result, s.err
}

func TestHealthz(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	response := httptest.NewRecorder()

	testServer(fakeDatabase{}).ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", response.Code)
	}
	if strings.TrimSpace(response.Body.String()) != `{"status":"ok"}` {
		t.Fatalf("body = %q", response.Body.String())
	}
}

func TestSetupStatus(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := New(fakeDatabase{}, fakeSetupService{initialized: true}, fakeAuthService{}, fakePairingService{}, http.NotFoundHandler(), logger)
	request := httptest.NewRequest(http.MethodGet, "/api/v1/setup/status", nil)
	response := httptest.NewRecorder()

	server.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", response.Code)
	}
	if strings.TrimSpace(response.Body.String()) != `{"initialized":true}` {
		t.Fatalf("body = %q", response.Body.String())
	}
}

func TestSetupClaimReturnsMasterToken(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	service := fakeSetupService{result: setup.ClaimResult{DeviceID: "device-1", MasterToken: "master-token"}}
	server := New(fakeDatabase{}, service, fakeAuthService{}, fakePairingService{}, http.NotFoundHandler(), logger)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/setup/claim", strings.NewReader(`{"setup_token":"owner-token"}`))
	request.RemoteAddr = "192.0.2.1:1234"
	response := httptest.NewRecorder()

	server.ServeHTTP(response, request)

	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", response.Code, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), `"master_token":"master-token"`) {
		t.Fatalf("body = %q", response.Body.String())
	}
}

func TestSetupClaimMapsInvalidToken(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := New(fakeDatabase{}, fakeSetupService{err: setup.ErrInvalidSetupToken}, fakeAuthService{}, fakePairingService{}, http.NotFoundHandler(), logger)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/setup/claim", strings.NewReader(`{"setup_token":"wrong"}`))
	request.RemoteAddr = "192.0.2.2:1234"
	response := httptest.NewRecorder()

	server.ServeHTTP(response, request)

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", response.Code)
	}
	if !strings.Contains(response.Body.String(), `"code":"SETUP_TOKEN_INVALID"`) {
		t.Fatalf("body = %q", response.Body.String())
	}
}

func TestCreatePairingSessionSetsProtectedCookie(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	service := fakePairingService{session: pairing.Session{
		ID:           "session-1",
		Code:         "538219",
		QRSecret:     strings.Repeat("s", 43),
		BrowserToken: strings.Repeat("b", 43),
		ExpiresAt:    time.Now().Add(2 * time.Minute),
	}}
	server := New(fakeDatabase{}, fakeSetupService{}, fakeAuthService{}, service, http.NotFoundHandler(), logger)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/pairing/sessions", nil)
	request.RemoteAddr = "192.0.2.4:1234"
	response := httptest.NewRecorder()

	server.ServeHTTP(response, request)

	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body = %s", response.Code, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), `"pairing_code":"538219"`) || !strings.Contains(response.Body.String(), `"qr_payload"`) {
		t.Fatalf("body = %q", response.Body.String())
	}
	cookies := response.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("cookie count = %d, want 1", len(cookies))
	}
	cookie := cookies[0]
	if cookie.Name != pairingCookieName || !cookie.HttpOnly || !cookie.Secure || cookie.SameSite != http.SameSiteStrictMode {
		t.Fatalf("pairing cookie is not protected: %+v", cookie)
	}
}

func TestApprovePairingRequiresMasterBearer(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := New(fakeDatabase{}, fakeSetupService{}, fakeAuthService{err: deviceauth.ErrUnauthorized}, fakePairingService{}, http.NotFoundHandler(), logger)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/pairing/approve", strings.NewReader(`{"pairing_code":"538219"}`))
	request.RemoteAddr = "192.0.2.5:1234"
	response := httptest.NewRecorder()

	server.ServeHTTP(response, request)

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", response.Code)
	}
}

func TestApprovedPairingStatusSetsBrowserCookie(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	service := fakePairingService{poll: pairing.PollResult{
		Status:       pairing.StatusApproved,
		BrowserToken: strings.Repeat("b", 43),
	}}
	server := New(fakeDatabase{}, fakeSetupService{}, fakeAuthService{}, service, http.NotFoundHandler(), logger)
	request := httptest.NewRequest(http.MethodGet, "/api/v1/pairing/sessions/session-1/status", nil)
	request.AddCookie(&http.Cookie{Name: pairingCookieName, Value: strings.Repeat("b", 43)})
	response := httptest.NewRecorder()

	server.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body = %s", response.Code, response.Body.String())
	}
	var browserCookie *http.Cookie
	for _, cookie := range response.Result().Cookies() {
		if cookie.Name == browserCookieName && cookie.Value != "" {
			browserCookie = cookie
		}
	}
	if browserCookie == nil || !browserCookie.HttpOnly || !browserCookie.Secure || browserCookie.SameSite != http.SameSiteStrictMode {
		t.Fatalf("browser cookie is missing or unprotected: %+v", browserCookie)
	}
}

func TestHealthzDoesNotLeakDatabaseError(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	response := httptest.NewRecorder()

	testServer(fakeDatabase{err: errors.New("secret database path")}).ServeHTTP(response, request)

	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", response.Code)
	}
	if strings.Contains(response.Body.String(), "secret") {
		t.Fatalf("health response leaked database error: %q", response.Body.String())
	}
}

func TestAPIPathDoesNotFallBackToSPA(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/api/v1/not-yet-built", nil)
	response := httptest.NewRecorder()

	testServer(fakeDatabase{}).ServeHTTP(response, request)

	if response.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", response.Code)
	}
	if !strings.Contains(response.Header().Get("Content-Type"), "application/json") {
		t.Fatalf("Content-Type = %q, want JSON", response.Header().Get("Content-Type"))
	}
}
