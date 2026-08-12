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
	return New(db, fakeSetupService{}, web, logger)
}

type fakeSetupService struct {
	initialized bool
	result      setup.ClaimResult
	err         error
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
	server := New(fakeDatabase{}, fakeSetupService{initialized: true}, http.NotFoundHandler(), logger)
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
	server := New(fakeDatabase{}, service, http.NotFoundHandler(), logger)
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
	server := New(fakeDatabase{}, fakeSetupService{err: setup.ErrInvalidSetupToken}, http.NotFoundHandler(), logger)
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
