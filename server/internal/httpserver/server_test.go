package httpserver

import (
	"bytes"
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"transdot.local/transfer-assistant/server/internal/deviceauth"
	"transdot.local/transfer-assistant/server/internal/messages"
	"transdot.local/transfer-assistant/server/internal/pairing"
	"transdot.local/transfer-assistant/server/internal/realtime"
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
	return New(db, fakeSetupService{}, fakeAuthService{}, fakePairingService{}, fakeMessageService{}, realtime.NewHub(), web, logger)
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

type fakeMessageService struct {
	message messages.Message
	page    messages.Page
	context messages.Context
	results []messages.Message
	err     error
}

func (s fakeMessageService) CreateText(context.Context, string, string) (messages.Message, error) {
	return s.message, s.err
}

func (s fakeMessageService) List(context.Context, string, int) (messages.Page, error) {
	return s.page, s.err
}

func (s fakeMessageService) Delete(context.Context, string) error {
	return s.err
}

func (s fakeMessageService) Search(context.Context, string) ([]messages.Message, error) {
	return s.results, s.err
}

func (s fakeMessageService) Context(context.Context, string) (messages.Context, error) {
	return s.context, s.err
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
	server := New(fakeDatabase{}, fakeSetupService{initialized: true}, fakeAuthService{}, fakePairingService{}, fakeMessageService{}, realtime.NewHub(), http.NotFoundHandler(), logger)
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
	server := New(fakeDatabase{}, service, fakeAuthService{}, fakePairingService{}, fakeMessageService{}, realtime.NewHub(), http.NotFoundHandler(), logger)
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
	server := New(fakeDatabase{}, fakeSetupService{err: setup.ErrInvalidSetupToken}, fakeAuthService{}, fakePairingService{}, fakeMessageService{}, realtime.NewHub(), http.NotFoundHandler(), logger)
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
	server := New(fakeDatabase{}, fakeSetupService{}, fakeAuthService{}, service, fakeMessageService{}, realtime.NewHub(), http.NotFoundHandler(), logger)
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
	server := New(fakeDatabase{}, fakeSetupService{}, fakeAuthService{err: deviceauth.ErrUnauthorized}, fakePairingService{}, fakeMessageService{}, realtime.NewHub(), http.NotFoundHandler(), logger)
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
	server := New(fakeDatabase{}, fakeSetupService{}, fakeAuthService{}, service, fakeMessageService{}, realtime.NewHub(), http.NotFoundHandler(), logger)
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

func TestMessageEndpointsRequireAuthentication(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/api/v1/messages", nil)
	response := httptest.NewRecorder()

	testServer(fakeDatabase{}).ServeHTTP(response, request)

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", response.Code)
	}
}

func TestCreateTextMessageAcceptsAndroidBearer(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	expected := messages.Message{ID: "message-1", Type: messages.TypeText, SourceDeviceID: "android-1"}
	server := New(
		fakeDatabase{}, fakeSetupService{},
		fakeAuthService{device: deviceauth.Device{ID: "android-1", Type: deviceauth.AndroidMaster}},
		fakePairingService{}, fakeMessageService{message: expected}, realtime.NewHub(),
		http.NotFoundHandler(), logger,
	)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/messages/text", strings.NewReader(`{"text":"hello"}`))
	request.Header.Set("Authorization", "Bearer master-token")
	response := httptest.NewRecorder()

	server.ServeHTTP(response, request)

	if response.Code != http.StatusCreated || !strings.Contains(response.Body.String(), `"id":"message-1"`) {
		t.Fatalf("status/body = %d/%s", response.Code, response.Body.String())
	}
}

func TestCreateTextMessageRejectsInvalidUTF8(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := New(
		fakeDatabase{}, fakeSetupService{},
		fakeAuthService{device: deviceauth.Device{ID: "android-1", Type: deviceauth.AndroidMaster}},
		fakePairingService{}, fakeMessageService{}, realtime.NewHub(),
		http.NotFoundHandler(), logger,
	)
	body := append([]byte(`{"text":"`), 0xff)
	body = append(body, []byte(`"}`)...)
	request := httptest.NewRequest(http.MethodPost, "/api/v1/messages/text", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer master-token")
	response := httptest.NewRecorder()

	server.ServeHTTP(response, request)

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", response.Code)
	}
}

func TestAuthenticatedWebsocketReceivesEventsAndReplacement(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	hub := realtime.NewHub()
	server := New(
		fakeDatabase{}, fakeSetupService{},
		fakeAuthService{device: deviceauth.Device{ID: "browser-1", Type: deviceauth.WindowsBrowser}},
		fakePairingService{}, fakeMessageService{}, hub,
		http.NotFoundHandler(), logger,
	)
	httpServer := httptest.NewServer(server)
	defer httpServer.Close()
	websocketURL, err := url.Parse(httpServer.URL)
	if err != nil {
		t.Fatalf("parse test URL: %v", err)
	}
	websocketURL.Scheme = "ws"
	websocketURL.Path = "/ws"
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	connection, _, err := websocket.Dial(ctx, websocketURL.String(), &websocket.DialOptions{
		HTTPHeader: http.Header{"Cookie": []string{browserCookieName + "=browser-token"}},
	})
	if err != nil {
		t.Fatalf("websocket.Dial() error = %v", err)
	}
	defer connection.CloseNow()
	for hub.ConnectionCount("browser-1") != 1 {
		select {
		case <-ctx.Done():
			t.Fatal("websocket subscription was not registered")
		default:
			time.Sleep(time.Millisecond)
		}
	}

	hub.Publish("message.created", map[string]string{"id": "message-1"})
	var created realtime.Event
	if err := wsjson.Read(ctx, connection, &created); err != nil || created.Type != "message.created" {
		t.Fatalf("created event = %+v, %v", created, err)
	}
	hub.RevokeDevices([]string{"browser-1"})
	var replaced realtime.Event
	if err := wsjson.Read(ctx, connection, &replaced); err != nil || replaced.Type != "device.replaced" {
		t.Fatalf("replaced event = %+v, %v", replaced, err)
	}
	_, _, err = connection.Read(ctx)
	if websocket.CloseStatus(err) != websocket.StatusPolicyViolation {
		t.Fatalf("close status = %v, error = %v", websocket.CloseStatus(err), err)
	}
}
