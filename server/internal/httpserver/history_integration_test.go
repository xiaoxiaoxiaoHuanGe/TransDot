package httpserver

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"
	"transdot.local/transfer-assistant/server/internal/database"
	"transdot.local/transfer-assistant/server/internal/deviceauth"
	transferfiles "transdot.local/transfer-assistant/server/internal/files"
	"transdot.local/transfer-assistant/server/internal/messages"
	"transdot.local/transfer-assistant/server/internal/realtime"
)

func TestHistoryProtectsRESTAttachmentsAndRealtime(t *testing.T) {
	dir := t.TempDir()
	db, err := database.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	insertAuthenticatedDevice(t, db, "master", deviceauth.AndroidMaster, "master-token")
	s := messages.NewService(db)
	ctx := context.Background()
	old, err := s.CreateText(ctx, "master", "private archive")
	if err != nil {
		t.Fatal(err)
	}
	hub := realtime.NewHub()
	defer hub.Close()
	files := transferfiles.NewService(db, transferfiles.Config{DataDir: dir, MaxFileBytes: 1024, MaxBatchBytes: 2048, MaxBatchItems: 20, FilePoolMaxBytes: 4096, FileTTL: time.Hour, FileMessageTTL: 24 * time.Hour, UploadSessionTTL: time.Hour}, hub.Publish)
	batch, err := files.CreateBatch(ctx, "master", []transferfiles.UploadItem{{Filename: "secret.txt", MIMEType: "text/plain", SizeBytes: 6, Kind: "file"}})
	if err != nil {
		t.Fatal(err)
	}
	attachment, err := files.CompleteUpload(ctx, batch.Uploads[0].UploadID, "master", 6, strings.NewReader("secret"))
	if err != nil {
		t.Fatal(err)
	}
	insertAuthenticatedDevice(t, db, "browser", deviceauth.WindowsBrowser, "browser-token")
	fresh, err := s.CreateText(ctx, "master", "public archive")
	if err != nil {
		t.Fatal(err)
	}
	handler := NewWithFiles(db, fakeSetupService{}, deviceauth.NewService(db), fakePairingService{}, s, files, hub, http.NotFoundHandler(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	request := func(method, path, body string, master bool) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		if master {
			r.Header.Set("Authorization", "Bearer master-token")
		} else {
			r.AddCookie(&http.Cookie{Name: browserCookieName, Value: "browser-token"})
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	for _, path := range []string{"/api/v1/messages", "/api/v1/search?q=archive", "/api/v1/messages/" + fresh.ID + "/context"} {
		w := request("GET", path, "", false)
		if !strings.Contains(w.Header().Get("Cache-Control"), "no-store") {
			t.Fatalf("history-sensitive response must not be cached: %s", path)
		}
		if w.Code != 200 || bytes.Contains(w.Body.Bytes(), []byte(old.ID)) || bytes.Contains(w.Body.Bytes(), []byte(attachment.ID)) {
			t.Fatalf("leaked %s: %d %s", path, w.Code, w.Body.String())
		}
	}
	for _, path := range []string{"/api/v1/messages/" + old.ID + "/context", attachment.File.DownloadURL, "/api/v1/files/" + attachment.File.ID + "/thumbnail"} {
		if w := request("GET", path, "", false); w.Code != 404 {
			t.Fatalf("read %s: %d %s", path, w.Code, w.Body.String())
		}
	}
	if w := request("DELETE", "/api/v1/messages/"+old.ID, "", false); w.Code != 404 {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := request("PATCH", "/api/v1/messages/"+old.ID, `{"text":"overwrite","revision":0}`, false); w.Code != 404 {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := request("PATCH", "/api/v1/history/browsers/browser", `{"decision":"approve"}`, false); w.Code != 401 {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := request("POST", "/api/v1/history/request", "", false); w.Code != 200 || !strings.Contains(w.Body.String(), "pending") {
		t.Fatal(w.Code, w.Body.String())
	}
	if w := request("GET", "/api/v1/history/browsers", "", true); w.Code != 200 || !strings.Contains(w.Body.String(), "pending") {
		t.Fatal(w.Code, w.Body.String())
	}

	server := httptest.NewServer(handler)
	defer server.Close()
	socketCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	socket, _, err := websocket.Dial(socketCtx, "ws"+strings.TrimPrefix(server.URL, "http")+"/ws", &websocket.DialOptions{HTTPHeader: http.Header{"Cookie": []string{browserCookieName + "=browser-token"}}})
	if err != nil {
		t.Fatal(err)
	}
	defer socket.CloseNow()
	waitForConnections(t, socketCtx, hub, "browser")
	hub.Publish("message.updated", old)
	hub.Publish("message.deleted", map[string]any{"message_id": old.ID, "sequence": s.Sequence(ctx, old.ID)})
	hub.Publish("file.expired", map[string]string{"message_id": attachment.ID, "file_id": attachment.File.ID})
	hub.Publish("message.created", fresh)
	var event struct {
		Type string           `json:"type"`
		Data messages.Message `json:"data"`
	}
	if err = wsjson.Read(socketCtx, socket, &event); err != nil {
		t.Fatal(err)
	}
	if event.Type != "message.created" || event.Data.ID != fresh.ID {
		t.Fatalf("realtime leaked %+v", event)
	}
	if w := request("PATCH", "/api/v1/history/browsers/browser", `{"decision":"approve"}`, true); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	w := request("GET", "/api/v1/messages", "", false)
	var page messages.Page
	if err = json.Unmarshal(w.Body.Bytes(), &page); err != nil || len(page.Messages) != 3 {
		t.Fatalf("approved: %s %v", w.Body.String(), err)
	}
	if w = request("PATCH", "/api/v1/messages/"+old.ID, `{"text":"updated archive","revision":0}`, false); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	if w = request("PATCH", "/api/v1/messages/"+old.ID, `{"text":"stale","revision":0}`, false); w.Code != 409 {
		t.Fatal(w.Code, w.Body.String())
	}
	if w = request("PATCH", "/api/v1/history/browsers/browser", `{"decision":"revoke"}`, true); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	if w = request("GET", attachment.File.DownloadURL, "", false); w.Code != 404 {
		t.Fatal(w.Code, w.Body.String())
	}
}
