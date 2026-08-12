package httpserver

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/database"
	"transdot.local/transfer-assistant/server/internal/deviceauth"
	transferfiles "transdot.local/transfer-assistant/server/internal/files"
	"transdot.local/transfer-assistant/server/internal/messages"
	"transdot.local/transfer-assistant/server/internal/realtime"
)

func TestRealFileAPIFlow(t *testing.T) {
	dataDir := t.TempDir()
	db, err := database.Open(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	masterToken := "file-api-master-token"
	insertAuthenticatedDevice(t, db, "file-master", deviceauth.AndroidMaster, masterToken)
	hub := realtime.NewHub()
	fileService := transferfiles.NewService(db, transferfiles.Config{
		DataDir: dataDir, MaxFileBytes: 1024, MaxBatchBytes: 2048, MaxBatchItems: 20,
		FilePoolMaxBytes: 4096, FileTTL: time.Hour, FileMessageTTL: 24 * time.Hour,
		UploadSessionTTL: 30 * time.Minute,
	}, hub.Publish)
	handler := NewWithFiles(
		db, fakeSetupService{}, deviceauth.NewService(db), fakePairingService{},
		messages.NewService(db), fileService, hub, http.NotFoundHandler(),
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	)
	server := httptest.NewServer(handler)
	defer server.Close()

	batchBody, _ := json.Marshal(map[string]any{"items": []map[string]any{{
		"filename": "proof.jpg", "mime_type": "image/jpeg", "size_bytes": 5, "kind": "image",
	}}})
	request, _ := http.NewRequest(http.MethodPost, server.URL+"/api/v1/upload-batches", bytes.NewReader(batchBody))
	request.Header.Set("Authorization", "Bearer "+masterToken)
	request.Header.Set("Content-Type", "application/json")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	var batch transferfiles.UploadBatch
	decodeResponse(t, response, http.StatusCreated, &batch)
	if len(batch.Uploads) != 1 {
		t.Fatalf("upload tickets = %+v", batch.Uploads)
	}
	ticket := batch.Uploads[0]

	thumbnail := []byte{0xff, 0xd8, 0xff, 0xd9}
	request, _ = http.NewRequest(http.MethodPut, server.URL+ticket.ThumbnailUploadURL, bytes.NewReader(thumbnail))
	request.Header.Set("Authorization", "Bearer "+masterToken)
	request.Header.Set("Content-Type", "image/jpeg")
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	decodeResponse(t, response, http.StatusNoContent, nil)

	request, _ = http.NewRequest(http.MethodPut, server.URL+ticket.UploadURL, bytes.NewBufferString("image"))
	request.Header.Set("Authorization", "Bearer "+masterToken)
	request.Header.Set("Content-Type", "image/jpeg")
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	var created messages.Message
	decodeResponse(t, response, http.StatusCreated, &created)
	if created.File == nil || created.File.ThumbnailURL == "" {
		t.Fatalf("created file message = %+v", created)
	}

	response, err = http.Get(server.URL + created.File.DownloadURL)
	if err != nil {
		t.Fatal(err)
	}
	decodeResponse(t, response, http.StatusUnauthorized, nil)
	request, _ = http.NewRequest(http.MethodGet, server.URL+created.File.DownloadURL, nil)
	request.Header.Set("Authorization", "Bearer "+masterToken)
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	contents, _ := io.ReadAll(response.Body)
	response.Body.Close()
	if response.StatusCode != http.StatusOK || string(contents) != "image" || response.Header.Get("Content-Disposition") == "" {
		t.Fatalf("download = %d/%q/%q", response.StatusCode, contents, response.Header.Get("Content-Disposition"))
	}

	request, _ = http.NewRequest(http.MethodDelete, server.URL+"/api/v1/messages/"+created.ID, nil)
	request.Header.Set("Authorization", "Bearer "+masterToken)
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	decodeResponse(t, response, http.StatusNoContent, nil)
}
