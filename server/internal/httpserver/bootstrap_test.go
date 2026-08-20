package httpserver

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/bootstrap"
	serverinstance "transdot.local/transfer-assistant/server/internal/instance"
	"transdot.local/transfer-assistant/server/internal/setup"
)

type fakeBootstrap struct {
	session bootstrap.Session
	claim   setup.ClaimResult
	poll    bootstrap.PollResult
	err     error
}

func (f fakeBootstrap) Create(context.Context) (bootstrap.Session, error) { return f.session, f.err }
func (f fakeBootstrap) Claim(context.Context, string, string) (setup.ClaimResult, error) {
	return f.claim, f.err
}
func (f fakeBootstrap) Poll(context.Context, string, string) (bootstrap.PollResult, error) {
	return f.poll, f.err
}

type fakeInstance struct{ identity serverinstance.Identity }

func (f fakeInstance) Get(context.Context) (serverinstance.Identity, error) { return f.identity, nil }

func TestCreateBootstrapSessionReturnsOneTimeQRWithoutOwnerToken(t *testing.T) {
	service := fakeBootstrap{session: bootstrap.Session{ID: "session-1", Secret: strings.Repeat("s", 43), BrowserToken: strings.Repeat("b", 43), ExpiresAt: time.Now().Add(time.Minute)}}
	instances := fakeInstance{identity: serverinstance.Identity{ID: "instance-1", Fingerprint: "7f3a-91c2"}}
	handler := createBootstrapSession(service, instances, "https://transfer.example.com", slog.New(slog.NewTextHandler(io.Discard, nil)))
	request := httptest.NewRequest(http.MethodPost, "/api/v1/bootstrap/sessions", nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d", response.Code)
	}
	body := response.Body.String()
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
	if payload["kind"] != "bootstrap" || payload["instance_id"] != "instance-1" {
		t.Fatalf("payload = %#v", payload)
	}
	if strings.Contains(body, "OWNER_SETUP_TOKEN") || strings.Contains(body, "owner-token") {
		t.Fatalf("response leaks owner token: %s", body)
	}
	if cookie := response.Header().Get("Set-Cookie"); !strings.Contains(cookie, bootstrapCookieName) || !strings.Contains(cookie, "HttpOnly") {
		t.Fatalf("cookie = %q", cookie)
	}
}
