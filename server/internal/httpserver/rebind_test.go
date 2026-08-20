package httpserver

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/deviceauth"
	serverinstance "transdot.local/transfer-assistant/server/internal/instance"
	"transdot.local/transfer-assistant/server/internal/rebind"
)

type fakeRebind struct {
	session     rebind.Session
	claim       rebind.ClaimResult
	status      string
	err         error
	createCalls int
}

func (f *fakeRebind) Create(context.Context, string) (rebind.Session, error) {
	f.createCalls++
	return f.session, f.err
}
func (f *fakeRebind) Claim(context.Context, string, string, string) (rebind.ClaimResult, error) {
	return f.claim, f.err
}
func (f *fakeRebind) Poll(context.Context, string, string) (string, error) { return f.status, f.err }

func TestCreateRebindSessionReturnsAuthenticatedQR(t *testing.T) {
	service := &fakeRebind{session: rebind.Session{ID: "session-1", Secret: strings.Repeat("s", 43), ExpiresAt: time.Now().Add(time.Minute)}}
	auth := fakeAuthService{device: deviceauth.Device{ID: "browser-1", Type: deviceauth.WindowsBrowser}}
	handler := createRebindSession(auth, service, fakeInstance{identity: testIdentity()}, "https://transfer.example.com", testLogger())
	request := httptest.NewRequest(http.MethodPost, "/api/v1/rebind/sessions", nil)
	request.AddCookie(&http.Cookie{Name: browserCookieName, Value: "browser-token"})
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d body=%s", response.Code, response.Body.String())
	}
	var envelope struct {
		QRPayload string `json:"qr_payload"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(envelope.QRPayload), &payload); err != nil {
		t.Fatal(err)
	}
	if payload["kind"] != "rebind" || payload["instance_id"] != "instance-1" {
		t.Fatalf("payload = %#v", payload)
	}
}

func TestCreateRebindSessionRejectsInsecureOriginBeforeCreatingSession(t *testing.T) {
	tests := []struct {
		name          string
		configuredURL string
	}{
		{name: "http", configuredURL: "http://transfer.example.com"},
		{name: "userinfo", configuredURL: "https://user@transfer.example.com"},
		{name: "path", configuredURL: "https://transfer.example.com/rebind"},
		{name: "query", configuredURL: "https://transfer.example.com?next=/"},
		{name: "fragment", configuredURL: "https://transfer.example.com#fragment"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			service := &fakeRebind{session: rebind.Session{ID: "session-1", Secret: strings.Repeat("s", 43), ExpiresAt: time.Now().Add(time.Minute)}}
			auth := fakeAuthService{device: deviceauth.Device{ID: "browser-1", Type: deviceauth.WindowsBrowser}}
			handler := createRebindSession(auth, service, fakeInstance{identity: testIdentity()}, tt.configuredURL, testLogger())
			request := httptest.NewRequest(http.MethodPost, "/api/v1/rebind/sessions", nil)
			request.AddCookie(&http.Cookie{Name: browserCookieName, Value: "browser-token"})
			response := httptest.NewRecorder()

			handler.ServeHTTP(response, request)

			if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "INVALID_PUBLIC_URL") {
				t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
			}
			if service.createCalls != 0 {
				t.Fatalf("Create calls = %d, want 0", service.createCalls)
			}
		})
	}
}

func TestCreateRebindSessionUsesHTTPSForDirectTLS(t *testing.T) {
	service := &fakeRebind{session: rebind.Session{ID: "session-1", Secret: strings.Repeat("s", 43), ExpiresAt: time.Now().Add(time.Minute)}}
	auth := fakeAuthService{device: deviceauth.Device{ID: "browser-1", Type: deviceauth.WindowsBrowser}}
	handler := createRebindSession(auth, service, fakeInstance{identity: testIdentity()}, "", testLogger())
	request := httptest.NewRequest(http.MethodPost, "/api/v1/rebind/sessions", nil)
	request.Host = "transfer.example.com"
	request.TLS = &tls.ConnectionState{}
	request.AddCookie(&http.Cookie{Name: browserCookieName, Value: "browser-token"})
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d body=%s", response.Code, response.Body.String())
	}
	var envelope struct {
		QRPayload string `json:"qr_payload"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(envelope.QRPayload), &payload); err != nil {
		t.Fatal(err)
	}
	if payload["server_url"] != "https://transfer.example.com" {
		t.Fatalf("server_url = %q", payload["server_url"])
	}
}

func TestCreateRebindSessionRequiresDirectTLSWithoutConfiguredURL(t *testing.T) {
	service := &fakeRebind{session: rebind.Session{ID: "session-1", Secret: strings.Repeat("s", 43), ExpiresAt: time.Now().Add(time.Minute)}}
	auth := fakeAuthService{device: deviceauth.Device{ID: "browser-1", Type: deviceauth.WindowsBrowser}}
	handler := createRebindSession(auth, service, fakeInstance{identity: testIdentity()}, "", testLogger())
	request := httptest.NewRequest(http.MethodPost, "/api/v1/rebind/sessions", nil)
	request.Header.Set("X-Forwarded-Proto", "https")
	request.AddCookie(&http.Cookie{Name: browserCookieName, Value: "browser-token"})
	response := httptest.NewRecorder()

	handler.ServeHTTP(response, request)

	if response.Code != http.StatusBadRequest || !strings.Contains(response.Body.String(), "HTTPS_REQUIRED") {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	if service.createCalls != 0 {
		t.Fatalf("Create calls = %d, want 0", service.createCalls)
	}
}

func TestRebindStatusDistinguishesInvalidSessionFromServerFailure(t *testing.T) {
	auth := fakeAuthService{device: deviceauth.Device{ID: "browser-1", Type: deviceauth.WindowsBrowser}}
	tests := []struct {
		name string
		err  error
		want int
	}{
		{name: "invalid", err: rebind.ErrInvalid, want: http.StatusBadRequest},
		{name: "database", err: errors.New("database unavailable"), want: http.StatusInternalServerError},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			handler := rebindStatus(auth, &fakeRebind{err: tt.err}, testLogger())
			request := httptest.NewRequest(http.MethodGet, "/api/v1/rebind/sessions/session-1/status", nil)
			request.AddCookie(&http.Cookie{Name: browserCookieName, Value: "browser-token"})
			request.SetPathValue("id", "session-1")
			response := httptest.NewRecorder()

			handler.ServeHTTP(response, request)

			if response.Code != tt.want {
				t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
			}
		})
	}
}

func testIdentity() serverinstance.Identity {
	return serverinstance.Identity{ID: "instance-1", Fingerprint: "7f3a-91c2"}
}
func testLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }
