package httpserver

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"transdot.local/transfer-assistant/server/internal/database"
	"transdot.local/transfer-assistant/server/internal/deviceauth"
	"transdot.local/transfer-assistant/server/internal/messages"
	"transdot.local/transfer-assistant/server/internal/realtime"
)

func TestRealMessageFlowAcrossRESTWebsocketSearchDeleteAndRestart(t *testing.T) {
	dataDir := t.TempDir()
	db, err := database.Open(dataDir)
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	masterToken := "master-integration-token"
	browserToken := "browser-integration-token"
	insertAuthenticatedDevice(t, db, "master-integration", deviceauth.AndroidMaster, masterToken)
	insertAuthenticatedDevice(t, db, "browser-integration", deviceauth.WindowsBrowser, browserToken)

	hub := realtime.NewHub()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	handler := New(
		db, fakeSetupService{}, deviceauth.NewService(db), fakePairingService{},
		messages.NewService(db), hub, http.NotFoundHandler(), logger,
	)
	server := httptest.NewServer(handler)
	websocketURL, err := url.Parse(server.URL)
	if err != nil {
		t.Fatalf("parse websocket URL: %v", err)
	}
	websocketURL.Scheme = "ws"
	websocketURL.Path = "/ws"
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	browserSocket, _, err := websocket.Dial(ctx, websocketURL.String(), &websocket.DialOptions{
		HTTPHeader: http.Header{"Cookie": []string{browserCookieName + "=" + browserToken}},
	})
	if err != nil {
		t.Fatalf("dial browser websocket: %v", err)
	}
	defer browserSocket.CloseNow()
	androidSocket, _, err := websocket.Dial(ctx, websocketURL.String(), &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": []string{"Bearer " + masterToken}},
	})
	if err != nil {
		t.Fatalf("dial Android websocket: %v", err)
	}
	defer androidSocket.CloseNow()
	waitForConnections(t, ctx, hub, "browser-integration", "master-integration")

	fromBrowser := postText(t, server.URL, browserToken, false, "hello from Windows")
	assertRealtimeType(t, ctx, browserSocket, "message.created")
	assertRealtimeType(t, ctx, androidSocket, "message.created")
	fromAndroid := postText(t, server.URL, masterToken, true, "reply from Android")
	assertRealtimeType(t, ctx, browserSocket, "message.created")
	assertRealtimeType(t, ctx, androidSocket, "message.created")

	request, _ := http.NewRequest(http.MethodGet, server.URL+"/api/v1/messages?limit=50", nil)
	request.AddCookie(&http.Cookie{Name: browserCookieName, Value: browserToken})
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("list messages: %v", err)
	}
	var page messages.Page
	decodeResponse(t, response, http.StatusOK, &page)
	if len(page.Messages) != 2 || page.Messages[0].ID != fromBrowser.ID || page.Messages[1].ID != fromAndroid.ID {
		t.Fatalf("timeline = %+v, want browser then Android", page.Messages)
	}

	request, _ = http.NewRequest(http.MethodGet, server.URL+"/api/v1/search?q=reply", nil)
	request.Header.Set("Authorization", "Bearer "+masterToken)
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("search messages: %v", err)
	}
	var search struct {
		Results []messages.Message `json:"results"`
	}
	decodeResponse(t, response, http.StatusOK, &search)
	if len(search.Results) != 1 || search.Results[0].ID != fromAndroid.ID {
		t.Fatalf("search results = %+v", search.Results)
	}

	request, _ = http.NewRequest(http.MethodGet, server.URL+"/api/v1/messages/"+fromAndroid.ID+"/context", nil)
	request.AddCookie(&http.Cookie{Name: browserCookieName, Value: browserToken})
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("message context: %v", err)
	}
	var messageContext messages.Context
	decodeResponse(t, response, http.StatusOK, &messageContext)
	if messageContext.TargetMessageID != fromAndroid.ID || len(messageContext.Messages) != 2 {
		t.Fatalf("message context = %+v", messageContext)
	}

	request, _ = http.NewRequest(http.MethodDelete, server.URL+"/api/v1/messages/"+fromBrowser.ID, nil)
	request.Header.Set("Authorization", "Bearer "+masterToken)
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("delete message: %v", err)
	}
	decodeResponse(t, response, http.StatusNoContent, nil)
	assertRealtimeType(t, ctx, browserSocket, "message.deleted")
	assertRealtimeType(t, ctx, androidSocket, "message.deleted")

	hub.Close()
	server.Close()
	if err := db.Close(); err != nil {
		t.Fatalf("close first database: %v", err)
	}
	db, err = database.Open(dataDir)
	if err != nil {
		t.Fatalf("reopen database: %v", err)
	}
	defer db.Close()
	persisted, err := messages.NewService(db).List(context.Background(), "", 50)
	if err != nil || len(persisted.Messages) != 1 || persisted.Messages[0].ID != fromAndroid.ID {
		t.Fatalf("persisted messages = %+v, %v", persisted.Messages, err)
	}
}

func insertAuthenticatedDevice(t *testing.T, db interface {
	Exec(string, ...any) (sql.Result, error)
}, id, deviceType, token string) {
	t.Helper()
	tokenHash := sha256.Sum256([]byte(token))
	if _, err := db.Exec(`INSERT INTO devices (id, device_type, token_hash) VALUES (?, ?, ?)`, id, deviceType, tokenHash[:]); err != nil {
		t.Fatalf("insert %s device: %v", deviceType, err)
	}
}

func postText(t *testing.T, baseURL, token string, android bool, text string) messages.Message {
	t.Helper()
	body, _ := json.Marshal(map[string]string{"text": text})
	request, _ := http.NewRequest(http.MethodPost, baseURL+"/api/v1/messages/text", bytes.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	if android {
		request.Header.Set("Authorization", "Bearer "+token)
	} else {
		request.AddCookie(&http.Cookie{Name: browserCookieName, Value: token})
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("post text: %v", err)
	}
	var created messages.Message
	decodeResponse(t, response, http.StatusCreated, &created)
	return created
}

func decodeResponse(t *testing.T, response *http.Response, expectedStatus int, destination any) {
	t.Helper()
	defer response.Body.Close()
	if response.StatusCode != expectedStatus {
		body, _ := io.ReadAll(response.Body)
		t.Fatalf("HTTP status = %d, want %d; body = %s", response.StatusCode, expectedStatus, body)
	}
	if destination != nil {
		if err := json.NewDecoder(response.Body).Decode(destination); err != nil {
			t.Fatalf("decode HTTP response: %v", err)
		}
	}
}

func assertRealtimeType(t *testing.T, ctx context.Context, connection *websocket.Conn, expected string) {
	t.Helper()
	var event realtime.Event
	if err := wsjson.Read(ctx, connection, &event); err != nil {
		t.Fatalf("read realtime event: %v", err)
	}
	if event.Type != expected || event.EventID == "" || event.Timestamp.IsZero() {
		t.Fatalf("realtime event = %+v, want %s envelope", event, expected)
	}
}

func waitForConnections(t *testing.T, ctx context.Context, hub *realtime.Hub, deviceIDs ...string) {
	t.Helper()
	for {
		ready := true
		for _, deviceID := range deviceIDs {
			ready = ready && hub.ConnectionCount(deviceID) == 1
		}
		if ready {
			return
		}
		select {
		case <-ctx.Done():
			t.Fatal("websocket clients were not registered")
		default:
			time.Sleep(time.Millisecond)
		}
	}
}
