package httpserver

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"transdot.local/transfer-assistant/server/internal/messages"
)

type messageService interface {
	CreateText(context.Context, string, string) (messages.Message, error)
	List(context.Context, string, int) (messages.Page, error)
	Delete(context.Context, string) error
	Search(context.Context, string) ([]messages.Message, error)
	Context(context.Context, string) (messages.Context, error)
}

type eventPublisher interface {
	Publish(string, any)
}

func listMessages(authService deviceAuthenticator, service messageService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := authenticateDevice(w, r, authService, logger); !ok {
			return
		}
		limit := messages.DefaultLimit
		if rawLimit := strings.TrimSpace(r.URL.Query().Get("limit")); rawLimit != "" {
			parsed, err := strconv.Atoi(rawLimit)
			if err != nil || parsed <= 0 {
				writeError(w, http.StatusBadRequest, "INVALID_LIMIT", "Limit must be a positive integer.")
				return
			}
			limit = parsed
		}
		page, err := service.List(r.Context(), r.URL.Query().Get("before"), limit)
		if err != nil {
			writeMessageError(w, err, logger)
			return
		}
		writeJSON(w, http.StatusOK, page)
	}
}

func createTextMessage(
	authService deviceAuthenticator,
	service messageService,
	publisher eventPublisher,
	logger *slog.Logger,
) http.HandlerFunc {
	type requestBody struct {
		Text string `json:"text"`
	}
	return func(w http.ResponseWriter, r *http.Request) {
		device, ok := authenticateDevice(w, r, authService, logger)
		if !ok {
			return
		}
		var request requestBody
		if err := decodeJSONBodyLimit(w, r, &request, messages.MaximumTextLen*6+1024); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Request body must be valid JSON.")
			return
		}
		message, err := service.CreateText(r.Context(), device.ID, request.Text)
		if err != nil {
			writeMessageError(w, err, logger)
			return
		}
		publisher.Publish("message.created", message)
		writeJSON(w, http.StatusCreated, message)
	}
}

func deleteMessage(
	authService deviceAuthenticator,
	service messageService,
	fileService fileService,
	publisher eventPublisher,
	logger *slog.Logger,
) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := authenticateDevice(w, r, authService, logger); !ok {
			return
		}
		messageID := strings.TrimSpace(r.PathValue("id"))
		if fileService != nil {
			if err := fileService.DeleteMessage(r.Context(), messageID); err != nil {
				writeFileError(w, err, logger)
				return
			}
		} else {
			if err := service.Delete(r.Context(), messageID); err != nil {
				writeMessageError(w, err, logger)
				return
			}
		}
		publisher.Publish("message.deleted", map[string]string{"message_id": messageID})
		w.WriteHeader(http.StatusNoContent)
	}
}

func searchMessages(authService deviceAuthenticator, service messageService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := authenticateDevice(w, r, authService, logger); !ok {
			return
		}
		results, err := service.Search(r.Context(), r.URL.Query().Get("q"))
		if err != nil {
			writeMessageError(w, err, logger)
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"results": results})
	}
}

func messageContext(authService deviceAuthenticator, service messageService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := authenticateDevice(w, r, authService, logger); !ok {
			return
		}
		result, err := service.Context(r.Context(), r.PathValue("id"))
		if err != nil {
			writeMessageError(w, err, logger)
			return
		}
		writeJSON(w, http.StatusOK, result)
	}
}

func writeMessageError(w http.ResponseWriter, err error, logger *slog.Logger) {
	switch {
	case errors.Is(err, messages.ErrEmptyText):
		writeError(w, http.StatusBadRequest, "TEXT_EMPTY", "Text message cannot be empty.")
	case errors.Is(err, messages.ErrTextTooLarge):
		writeError(w, http.StatusRequestEntityTooLarge, "TEXT_TOO_LARGE", "Text message exceeds 100 KB.")
	case errors.Is(err, messages.ErrInvalidUTF8):
		writeError(w, http.StatusBadRequest, "TEXT_INVALID_UTF8", "Text message must be valid UTF-8.")
	case errors.Is(err, messages.ErrInvalidCursor):
		writeError(w, http.StatusBadRequest, "CURSOR_INVALID", "Timeline cursor is invalid.")
	case errors.Is(err, messages.ErrInvalidSearch):
		writeError(w, http.StatusBadRequest, "SEARCH_INVALID", "Search query is invalid.")
	case errors.Is(err, messages.ErrNotFound):
		writeError(w, http.StatusNotFound, "MESSAGE_NOT_FOUND", "Message was not found.")
	default:
		logger.Error("message request", "error", err)
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
	}
}
