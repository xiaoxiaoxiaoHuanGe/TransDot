package httpserver

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"mime"
	"net/http"

	transferfiles "transdot.local/transfer-assistant/server/internal/files"
	"transdot.local/transfer-assistant/server/internal/messages"
)

type fileService interface {
	CreateBatch(context.Context, string, []transferfiles.UploadItem) (transferfiles.UploadBatch, error)
	CompleteUpload(context.Context, string, string, int64, io.Reader) (messages.Message, error)
	UploadThumbnail(context.Context, string, string, string, int64, io.Reader) error
	OpenDownload(context.Context, string) (*transferfiles.Download, error)
	OpenThumbnail(context.Context, string) (*transferfiles.Thumbnail, error)
	DeleteMessage(context.Context, string) error
}

func createUploadBatch(authService deviceAuthenticator, service fileService, logger *slog.Logger) http.HandlerFunc {
	type requestBody struct {
		Items []transferfiles.UploadItem `json:"items"`
	}
	return func(w http.ResponseWriter, r *http.Request) {
		device, ok := authenticateDevice(w, r, authService, logger)
		if !ok {
			return
		}
		var request requestBody
		if err := decodeJSONBodyLimit(w, r, &request, 64*1024); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Request body must be valid JSON.")
			return
		}
		batch, err := service.CreateBatch(r.Context(), device.ID, request.Items)
		if err != nil {
			writeFileError(w, err, logger)
			return
		}
		writeJSON(w, http.StatusCreated, batch)
	}
}

func uploadFile(authService deviceAuthenticator, service fileService, publisher eventPublisher, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		device, ok := authenticateDevice(w, r, authService, logger)
		if !ok {
			return
		}
		if r.Header.Get("Content-Length") == "" || r.ContentLength < 0 {
			writeError(w, http.StatusLengthRequired, "UPLOAD_INCOMPLETE", "Content-Length is required.")
			return
		}
		message, err := service.CompleteUpload(r.Context(), r.PathValue("id"), device.ID, r.ContentLength, r.Body)
		if err != nil {
			writeFileError(w, err, logger)
			return
		}
		publisher.Publish("message.created", message)
		writeJSON(w, http.StatusCreated, message)
	}
}

func uploadThumbnail(authService deviceAuthenticator, service fileService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		device, ok := authenticateDevice(w, r, authService, logger)
		if !ok {
			return
		}
		if r.ContentLength <= 0 {
			writeError(w, http.StatusLengthRequired, "THUMBNAIL_INVALID", "Content-Length is required.")
			return
		}
		if err := service.UploadThumbnail(r.Context(), r.PathValue("id"), device.ID, r.Header.Get("Content-Type"), r.ContentLength, r.Body); err != nil {
			writeFileError(w, err, logger)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func downloadFile(authService deviceAuthenticator, service fileService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := authenticateDevice(w, r, authService, logger); !ok {
			return
		}
		download, err := service.OpenDownload(r.Context(), r.PathValue("id"))
		if err != nil {
			writeFileError(w, err, logger)
			return
		}
		defer download.Release()
		w.Header().Set("Content-Type", download.MIMEType)
		w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": download.Filename}))
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "private, no-store")
		http.ServeContent(w, r, download.Filename, download.ModTime, download.Reader)
	}
}

func serveThumbnail(authService deviceAuthenticator, service fileService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := authenticateDevice(w, r, authService, logger); !ok {
			return
		}
		thumbnail, err := service.OpenThumbnail(r.Context(), r.PathValue("id"))
		if err != nil {
			writeFileError(w, err, logger)
			return
		}
		defer thumbnail.Close()
		w.Header().Set("Content-Type", thumbnail.MIMEType)
		w.Header().Set("Content-Disposition", "inline")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "private, max-age=300")
		http.ServeContent(w, r, thumbnail.FileID+".jpg", thumbnail.ModTime, thumbnail.Reader)
	}
}

func writeFileError(w http.ResponseWriter, err error, logger *slog.Logger) {
	switch {
	case errors.Is(err, transferfiles.ErrEmptyBatch), errors.Is(err, transferfiles.ErrInvalidItem):
		writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Upload batch is invalid.")
	case errors.Is(err, transferfiles.ErrTooManyFiles):
		writeError(w, http.StatusBadRequest, "TOO_MANY_FILES", "Upload batch contains too many files.")
	case errors.Is(err, transferfiles.ErrFileTooLarge):
		writeError(w, http.StatusRequestEntityTooLarge, "FILE_TOO_LARGE", "File exceeds the configured size limit.")
	case errors.Is(err, transferfiles.ErrBatchTooLarge):
		writeError(w, http.StatusRequestEntityTooLarge, "BATCH_TOO_LARGE", "Upload batch exceeds the configured size limit.")
	case errors.Is(err, transferfiles.ErrInsufficientStorage):
		writeError(w, http.StatusInsufficientStorage, "INSUFFICIENT_STORAGE", "Temporary file storage is full.")
	case errors.Is(err, transferfiles.ErrUploadNotFound):
		writeError(w, http.StatusNotFound, "UPLOAD_NOT_FOUND", "Upload ticket was not found.")
	case errors.Is(err, transferfiles.ErrUploadExpired):
		writeError(w, http.StatusGone, "UPLOAD_EXPIRED", "Upload session has expired.")
	case errors.Is(err, transferfiles.ErrUploadIncomplete):
		writeError(w, http.StatusBadRequest, "UPLOAD_INCOMPLETE", "Uploaded content does not match the declared size.")
	case errors.Is(err, transferfiles.ErrUploadConflict):
		writeError(w, http.StatusConflict, "UPLOAD_CONFLICT", "Upload is already active or complete.")
	case errors.Is(err, transferfiles.ErrThumbnailInvalid):
		writeError(w, http.StatusBadRequest, "THUMBNAIL_INVALID", "Thumbnail must be a JPEG no larger than 5 MB.")
	case errors.Is(err, transferfiles.ErrFileExpired):
		writeError(w, http.StatusGone, "FILE_EXPIRED", "File has expired.")
	case errors.Is(err, transferfiles.ErrFileNotFound):
		writeError(w, http.StatusNotFound, "FILE_NOT_FOUND", "File was not found.")
	case errors.Is(err, transferfiles.ErrMessageNotFound), errors.Is(err, messages.ErrNotFound):
		writeError(w, http.StatusNotFound, "MESSAGE_NOT_FOUND", "Message was not found.")
	default:
		logger.Error("file request", "error", err)
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
	}
}
