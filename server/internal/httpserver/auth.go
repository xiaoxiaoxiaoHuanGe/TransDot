package httpserver

import (
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"transdot.local/transfer-assistant/server/internal/deviceauth"
)

const (
	browserCookieName = "transfer_browser_v1"
	browserCookieAge  = 365 * 24 * time.Hour
	pairingCookieName = "transfer_pairing_v1"
)

func browserSession(authService deviceAuthenticator, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie(browserCookieName)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "Browser is not paired.")
			return
		}
		device, err := authService.Authenticate(r.Context(), cookie.Value, deviceauth.WindowsBrowser)
		if !handleAuthenticationError(w, err, logger) {
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"authenticated": true,
			"device_id":     device.ID,
			"device_type":   device.Type,
		})
	}
}

func authenticateDevice(w http.ResponseWriter, r *http.Request, authService deviceAuthenticator, logger *slog.Logger) (deviceauth.Device, bool) {
	header := strings.TrimSpace(r.Header.Get("Authorization"))
	if header != "" {
		scheme, token, found := strings.Cut(header, " ")
		if !found || !strings.EqualFold(scheme, "Bearer") || strings.TrimSpace(token) == "" {
			writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "Authentication is required.")
			return deviceauth.Device{}, false
		}
		device, err := authService.Authenticate(r.Context(), strings.TrimSpace(token), deviceauth.AndroidMaster)
		if !handleAuthenticationError(w, err, logger) {
			return deviceauth.Device{}, false
		}
		return device, true
	}

	cookie, err := r.Cookie(browserCookieName)
	if err != nil || strings.TrimSpace(cookie.Value) == "" {
		writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "Authentication is required.")
		return deviceauth.Device{}, false
	}
	device, err := authService.Authenticate(r.Context(), cookie.Value, deviceauth.WindowsBrowser)
	if !handleAuthenticationError(w, err, logger) {
		return deviceauth.Device{}, false
	}
	return device, true
}

func authenticateMaster(w http.ResponseWriter, r *http.Request, authService deviceAuthenticator, logger *slog.Logger) (deviceauth.Device, bool) {
	header := strings.TrimSpace(r.Header.Get("Authorization"))
	scheme, token, found := strings.Cut(header, " ")
	if !found || !strings.EqualFold(scheme, "Bearer") || strings.TrimSpace(token) == "" {
		writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "Android Master authentication is required.")
		return deviceauth.Device{}, false
	}
	device, err := authService.Authenticate(r.Context(), strings.TrimSpace(token), deviceauth.AndroidMaster)
	if !handleAuthenticationError(w, err, logger) {
		return deviceauth.Device{}, false
	}
	return device, true
}

func handleAuthenticationError(w http.ResponseWriter, err error, logger *slog.Logger) bool {
	switch {
	case err == nil:
		return true
	case errors.Is(err, deviceauth.ErrDeviceRevoked):
		writeError(w, http.StatusUnauthorized, "DEVICE_REVOKED", "This device has been revoked.")
	case errors.Is(err, deviceauth.ErrUnauthorized):
		writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "Authentication is required.")
	default:
		logger.Error("authenticate device", "error", err)
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
	}
	return false
}

func setBrowserCookie(w http.ResponseWriter, token string) {
	http.SetCookie(w, &http.Cookie{
		Name:     browserCookieName,
		Value:    token,
		Path:     "/",
		MaxAge:   int(browserCookieAge.Seconds()),
		Expires:  time.Now().UTC().Add(browserCookieAge),
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteStrictMode,
	})
}
