package httpserver

import (
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"transdot.local/transfer-assistant/server/internal/database"
	"transdot.local/transfer-assistant/server/internal/deviceauth"
	"transdot.local/transfer-assistant/server/internal/lantransfer"
	"transdot.local/transfer-assistant/server/internal/realtime"
)

func TestWebsocketRelaysAuthenticatedLANSignals(t *testing.T) {
	dataDir := t.TempDir()
	db, err := database.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	insertAuthenticatedDevice(t, db, "android-lan", deviceauth.AndroidMaster, "android-token")
	insertAuthenticatedDevice(t, db, "browser-lan", deviceauth.WindowsBrowser, "browser-token")

	hub := realtime.NewHub()
	broker := lantransfer.NewBroker("instance-1")
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	mux := http.NewServeMux()
	mux.HandleFunc("GET /ws", websocketEndpoint(deviceauth.NewService(db), hub, broker, logger))
	server := httptest.NewServer(mux)
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	android := dialLANSocket(t, ctx, server.URL, http.Header{"Authorization": []string{"Bearer android-token"}})
	defer android.CloseNow()
	browser := dialLANSocket(t, ctx, server.URL, http.Header{"Cookie": []string{browserCookieName + "=browser-token"}})
	defer browser.CloseNow()
	waitForConnections(t, ctx, hub, "android-lan", "browser-lan")

	writeLANSignal(t, ctx, android, lantransfer.ClientSignal{Type: lantransfer.SignalReady})
	writeLANSignal(t, ctx, browser, lantransfer.ClientSignal{Type: lantransfer.SignalReady})
	androidOnline := readLANEvent(t, ctx, android, lantransfer.SignalPeerOnline)
	browserOnline := readLANEvent(t, ctx, browser, lantransfer.SignalPeerOnline)
	androidSession := eventSessionID(t, androidOnline)
	if androidSession == "" || androidSession != eventSessionID(t, browserOnline) {
		t.Fatalf("online sessions differ: %#v %#v", androidOnline, browserOnline)
	}

	writeLANSignal(t, ctx, browser, lantransfer.ClientSignal{
		Type: lantransfer.SignalOffer, SessionID: androidSession,
		Data: json.RawMessage(`{"sdp":"offer"}`),
	})
	offerEvent := readLANEvent(t, ctx, android, lantransfer.SignalOffer)

	writeLANSignal(t, ctx, android, lantransfer.ClientSignal{
		Type: lantransfer.SignalAnswer, SessionID: androidSession,
		Data: json.RawMessage(`{"sdp":"answer"}`),
	})
	answerEvent := readLANEvent(t, ctx, browser, lantransfer.SignalAnswer)

	writeLANSignal(t, ctx, browser, lantransfer.ClientSignal{
		Type: lantransfer.SignalICE, SessionID: androidSession,
		Data: json.RawMessage(`{"candidate":"candidate:1 1 UDP 1 192.168.1.20 5000 typ host","sdp_mid":"0","sdp_mline_index":0}`),
	})
	iceEvent := readLANEvent(t, ctx, android, lantransfer.SignalICE)

	writeLANSignal(t, ctx, android, lantransfer.ClientSignal{
		Type: lantransfer.SignalConnected, SessionID: androidSession,
	})

	writeLANSignal(t, ctx, android, lantransfer.ClientSignal{
		Type: lantransfer.SignalICE, SessionID: androidSession,
		Data: json.RawMessage(`{"candidate":"candidate:1 1 UDP 1 203.0.113.2 5000 typ srflx","sdp_mid":"0","sdp_mline_index":0}`),
	})
	errorEvent := readLANEvent(t, ctx, android, "lan.error")
	errorData, _ := json.Marshal(errorEvent.Data)
	if !json.Valid(errorData) || !containsJSONValue(errorData, "LAN_NON_HOST_CANDIDATE") {
		t.Fatalf("LAN error = %s", errorData)
	}

	assertLANExchangePrivate(t, db, dataDir, []realtime.Event{
		androidOnline, browserOnline, offerEvent, answerEvent, iceEvent, errorEvent,
	})
}

func assertLANExchangePrivate(t *testing.T, db interface {
	QueryRow(query string, args ...any) *sql.Row
}, dataDir string, events []realtime.Event) {
	t.Helper()
	for _, table := range []string{"messages", "files", "upload_batches"} {
		var count int
		if err := db.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&count); err != nil {
			t.Fatalf("count %s: %v", table, err)
		}
		if count != 0 {
			t.Fatalf("%s gained %d rows during LAN signaling", table, count)
		}
	}
	for _, directory := range []string{"files", "thumbs", "tmp"} {
		entries, err := os.ReadDir(filepath.Join(dataDir, directory))
		if err != nil {
			t.Fatalf("read %s: %v", directory, err)
		}
		if len(entries) != 0 {
			t.Fatalf("LAN signaling wrote data files in %s: %#v", directory, entries)
		}
	}
	encoded, err := json.Marshal(events)
	if err != nil {
		t.Fatal(err)
	}
	lower := strings.ToLower(string(encoded))
	for _, forbidden := range []string{"file_offer", "filename", "mime", "sha256", "size_bytes"} {
		if strings.Contains(lower, forbidden) {
			t.Fatalf("LAN server event leaked %q: %s", forbidden, encoded)
		}
	}
}

func dialLANSocket(t *testing.T, ctx context.Context, serverURL string, header http.Header) *websocket.Conn {
	t.Helper()
	address, _ := url.Parse(serverURL)
	address.Scheme, address.Path = "ws", "/ws"
	connection, _, err := websocket.Dial(ctx, address.String(), &websocket.DialOptions{HTTPHeader: header})
	if err != nil {
		t.Fatalf("dial websocket: %v", err)
	}
	return connection
}

func writeLANSignal(t *testing.T, ctx context.Context, connection *websocket.Conn, signal lantransfer.ClientSignal) {
	t.Helper()
	if err := wsjson.Write(ctx, connection, signal); err != nil {
		t.Fatalf("write LAN signal: %v", err)
	}
}

func readLANEvent(t *testing.T, ctx context.Context, connection *websocket.Conn, expected string) realtime.Event {
	t.Helper()
	var event realtime.Event
	if err := wsjson.Read(ctx, connection, &event); err != nil {
		t.Fatalf("read LAN event: %v", err)
	}
	if event.Type != expected {
		t.Fatalf("event = %#v, want %s", event, expected)
	}
	return event
}

func eventSessionID(t *testing.T, event realtime.Event) string {
	t.Helper()
	encoded, _ := json.Marshal(event.Data)
	var payload struct {
		SessionID string `json:"session_id"`
	}
	if err := json.Unmarshal(encoded, &payload); err != nil {
		t.Fatal(err)
	}
	return payload.SessionID
}

func containsJSONValue(encoded []byte, expected string) bool {
	var value any
	if json.Unmarshal(encoded, &value) != nil {
		return false
	}
	var visit func(any) bool
	visit = func(current any) bool {
		switch typed := current.(type) {
		case string:
			return typed == expected
		case map[string]any:
			for _, item := range typed {
				if visit(item) {
					return true
				}
			}
		case []any:
			for _, item := range typed {
				if visit(item) {
					return true
				}
			}
		}
		return false
	}
	return visit(value)
}
