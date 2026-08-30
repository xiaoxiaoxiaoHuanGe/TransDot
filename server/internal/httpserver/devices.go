package httpserver

import (
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"transdot.local/transfer-assistant/server/internal/deviceauth"
	"transdot.local/transfer-assistant/server/internal/devices"
)

type renameDeviceRequest struct {
	DisplayName string `json:"display_name"`
}

func requireMaster(w http.ResponseWriter, r *http.Request, auth deviceAuthenticator, logger *slog.Logger) (deviceauth.Device, bool) {
	if strings.TrimSpace(r.Header.Get("Authorization")) == "" {
		writeError(w, http.StatusForbidden, "MASTER_REQUIRED", "Android Master authentication is required.")
		return deviceauth.Device{}, false
	}
	return authenticateMaster(w, r, auth, logger)
}

func listBrowserDevices(auth deviceAuthenticator, service deviceManagementService, maximum int, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := requireMaster(w, r, auth, logger); !ok {
			return
		}
		listed, err := service.ListActiveBrowsers(r.Context())
		if err != nil {
			logger.Error("list browser devices", "error", err)
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"devices": listed, "active_count": len(listed), "maximum_count": maximum})
	}
}

func renameBrowserDevice(auth deviceAuthenticator, service deviceManagementService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := requireMaster(w, r, auth, logger); !ok {
			return
		}
		var request renameDeviceRequest
		if err := decodeJSONBody(w, r, &request); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Request body must be valid JSON.")
			return
		}
		device, err := service.RenameBrowser(r.Context(), r.PathValue("id"), request.DisplayName)
		writeDeviceResult(w, device, err, logger)
	}
}

func revokeBrowserDevice(auth deviceAuthenticator, service deviceManagementService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, ok := requireMaster(w, r, auth, logger); !ok {
			return
		}
		if err := service.RevokeBrowser(r.Context(), r.PathValue("id")); err != nil {
			writeDeviceError(w, err, logger)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func renameSelfDevice(auth deviceAuthenticator, service deviceManagementService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie(browserCookieName)
		if err != nil || strings.TrimSpace(cookie.Value) == "" {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "Browser authentication is required.")
			return
		}
		current, err := auth.Authenticate(r.Context(), cookie.Value, deviceauth.WindowsBrowser)
		if !handleBrowserAuthenticationError(w, err, logger) {
			return
		}
		var request renameDeviceRequest
		if err := decodeJSONBody(w, r, &request); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Request body must be valid JSON.")
			return
		}
		device, err := service.RenameSelf(r.Context(), current.ID, request.DisplayName)
		writeDeviceResult(w, device, err, logger)
	}
}

func writeDeviceResult(w http.ResponseWriter, device devices.BrowserDevice, err error, logger *slog.Logger) bool {
	if err != nil {
		writeDeviceError(w, err, logger)
		return false
	}
	writeJSON(w, http.StatusOK, device)
	return true
}

func writeDeviceError(w http.ResponseWriter, err error, logger *slog.Logger) {
	switch {
	case errors.Is(err, devices.ErrInvalidDisplayName):
		writeError(w, http.StatusBadRequest, "INVALID_DEVICE_NAME", "Device name must contain 1 to 64 characters without control characters.")
	case errors.Is(err, devices.ErrDeviceNotFound):
		writeError(w, http.StatusNotFound, "DEVICE_NOT_FOUND", "Browser device was not found.")
	default:
		logger.Error("manage browser device", "error", err)
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
	}
}
