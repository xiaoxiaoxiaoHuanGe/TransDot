package httpserver

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"

	"transdot.local/transfer-assistant/server/internal/deviceauth"
	"transdot.local/transfer-assistant/server/internal/messages"
	"transdot.local/transfer-assistant/server/internal/realtime"
)

func editTextMessage(auth deviceAuthenticator, service *messages.Service, hub *realtime.Hub, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := authenticateDevice(w, r, auth, logger); !ok {
			return
		}
		var body struct {
			Text     string `json:"text"`
			Revision *int   `json:"revision"`
		}
		if err := decodeJSONBodyLimit(w, r, &body, messages.MaximumTextLen*6+1024); err != nil || body.Revision == nil {
			writeError(w, 400, "INVALID_REQUEST", "Text and revision are required.")
			return
		}
		message, err := service.Edit(r.Context(), r.PathValue("id"), body.Text, *body.Revision)
		if err != nil {
			writeMessageError(w, err, logger)
			return
		}
		hub.Publish("message.updated", message)
		writeJSON(w, http.StatusOK, message)
	}
}

func historyAccess(auth deviceAuthenticator, s *messages.Service, hub *realtime.Hub, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		device, ok := authenticateDevice(w, r, auth, logger)
		if !ok {
			return
		}
		if device.Type != deviceauth.WindowsBrowser {
			writeError(w, 403, "BROWSER_REQUIRED", "Browser authentication is required.")
			return
		}
		var access messages.HistoryAccess
		var err error
		if r.Method == http.MethodPost {
			access, err = s.RequestHistory(r.Context(), device.ID)
		} else {
			access, err = s.History(r.Context(), device.ID)
		}
		if err != nil {
			writeMessageError(w, err, logger)
			return
		}
		if r.Method == http.MethodPost {
			hub.Publish("history.changed", access)
		}
		w.Header().Set("Cache-Control", "private, no-store")
		writeJSON(w, 200, access)
	}
}
func historyDecisions(auth deviceAuthenticator, s *messages.Service, hub *realtime.Hub, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := authenticateMaster(w, r, auth, logger); !ok {
			return
		}
		if r.Method == http.MethodGet {
			items, err := s.Histories(r.Context())
			if err != nil {
				writeMessageError(w, err, logger)
				return
			}
			w.Header().Set("Cache-Control", "private, no-store")
			writeJSON(w, 200, map[string]any{"devices": items})
			return
		}
		var body struct {
			Decision string `json:"decision"`
		}
		if err := decodeJSONBody(w, r, &body); err != nil {
			writeError(w, 400, "INVALID_REQUEST", "Decision is required.")
			return
		}
		if body.Decision != "approve" && body.Decision != "reject" && body.Decision != "revoke" {
			writeError(w, 400, "INVALID_REQUEST", "Invalid decision.")
			return
		}
		id := r.PathValue("id")
		if err := s.DecideHistory(r.Context(), id, body.Decision); err != nil {
			writeMessageError(w, err, logger)
			return
		}
		access, err := s.History(r.Context(), id)
		if err != nil {
			writeMessageError(w, err, logger)
			return
		}
		hub.Publish("history.changed", access)
		writeJSON(w, 200, access)
	}
}
func guardFileAccess(auth deviceAuthenticator, service messageService, next http.HandlerFunc, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := authenticateDevice(w, r, auth, logger); !ok {
			return
		}
		if s, ok := service.(*messages.Service); ok && !s.CanReadFile(r.Context(), r.PathValue("id")) {
			writeError(w, 404, "MESSAGE_NOT_FOUND", "Message was not found.")
			return
		}
		next(w, r)
	}
}
func visibleEvent(ctx context.Context, s *messages.Service, d deviceauth.Device, event realtime.Event) bool {
	if d.Type == deviceauth.AndroidMaster {
		return true
	}
	if event.Type == "history.changed" {
		a, ok := event.Data.(messages.HistoryAccess)
		return ok && a.DeviceID == d.ID
	}
	if !strings.HasPrefix(event.Type, "message.") && event.Type != "file.expired" {
		return true
	}
	var data struct {
		ID        string `json:"id"`
		MessageID string `json:"message_id"`
		Sequence  int64  `json:"sequence"`
	}
	raw, err := json.Marshal(event.Data)
	if err != nil || json.Unmarshal(raw, &data) != nil {
		return false
	}
	if data.Sequence > 0 {
		return s.CanReadSequence(ctx, d.ID, data.Sequence)
	}
	id := data.ID
	if id == "" {
		id = data.MessageID
	}
	if id == "" {
		return false
	}
	_, err = s.Get(messages.WithViewer(ctx, d.ID), id)
	return err == nil
}
