package httpserver

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"transdot.local/transfer-assistant/server/internal/deviceauth"
	"transdot.local/transfer-assistant/server/internal/rebind"
)

type rebindService interface {
	Create(context.Context, string) (rebind.Session, error)
	Claim(context.Context, string, string, string) (rebind.ClaimResult, error)
	Poll(context.Context, string, string) (string, error)
}

func createRebindSession(auth deviceAuthenticator, service rebindService, instances instanceService, configuredURL string, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		browser, ok := authenticateBrowser(w, r, auth, logger)
		if !ok {
			return
		}
		hasConfiguredURL := strings.TrimSpace(configuredURL) != ""
		serverURL, err := rebindServerURL(r, configuredURL)
		if err != nil {
			if !hasConfiguredURL {
				writeError(w, http.StatusBadRequest, "HTTPS_REQUIRED", "HTTPS is required to create a rebind QR code.")
				return
			}
			writeError(w, http.StatusBadRequest, "INVALID_PUBLIC_URL", "PUBLIC_URL must be an HTTPS origin.")
			return
		}
		session, err := service.Create(r.Context(), browser.ID)
		if errors.Is(err, rebind.ErrNotInitialized) {
			writeError(w, http.StatusConflict, "REBINDS_NOT_ALLOWED", "Server is not initialized.")
			return
		}
		if err != nil {
			logger.Error("create rebind session", "error", err)
			writeError(w, 500, "INTERNAL_ERROR", "Internal server error.")
			return
		}
		identity, err := instances.Get(r.Context())
		if err != nil {
			logger.Error("read instance for rebind QR", "error", err)
			writeError(w, 500, "INTERNAL_ERROR", "Internal server error.")
			return
		}
		payload, _ := json.Marshal(map[string]any{"v": 2, "kind": "rebind", "server_url": serverURL, "instance_id": identity.ID, "instance_fingerprint": identity.Fingerprint, "rebind_session_id": session.ID, "rebind_secret": session.Secret, "expires_at": session.ExpiresAt.UTC().Format(time.RFC3339Nano)})
		writeJSON(w, http.StatusCreated, map[string]any{"session_id": session.ID, "qr_payload": string(payload), "expires_at": session.ExpiresAt.UTC().Format(time.RFC3339Nano), "poll_interval_seconds": 2})
	}
}

func rebindServerURL(r *http.Request, configuredURL string) (string, error) {
	configuredURL = strings.TrimSpace(configuredURL)
	if configuredURL != "" {
		return httpsOrigin(configuredURL)
	}
	if r.TLS == nil {
		return "", errors.New("HTTPS is required")
	}
	return httpsOrigin("https://" + r.Host)
}

func httpsOrigin(rawURL string) (string, error) {
	u, err := url.Parse(rawURL)
	if err != nil || !strings.EqualFold(u.Scheme, "https") || u.Host == "" || u.Hostname() == "" || u.User != nil || u.Path != "" || u.RawPath != "" || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
		return "", errors.New("not an HTTPS origin")
	}
	return "https://" + u.Host, nil
}

func claimRebind(service rebindService, logger *slog.Logger) http.HandlerFunc {
	type request struct {
		SessionID  string `json:"rebind_session_id"`
		Secret     string `json:"rebind_secret"`
		InstanceID string `json:"instance_id"`
	}
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		var body request
		if decodeJSONBody(w, r, &body) != nil {
			writeError(w, 400, "INVALID_REQUEST", "Request body must be valid JSON.")
			return
		}
		result, err := service.Claim(r.Context(), strings.TrimSpace(body.SessionID), strings.TrimSpace(body.Secret), strings.TrimSpace(body.InstanceID))
		switch {
		case err == nil:
			writeJSON(w, http.StatusCreated, result)
		case errors.Is(err, rebind.ErrExpired):
			writeError(w, http.StatusGone, "REBINDS_EXPIRED", "Rebind session expired.")
		case errors.Is(err, rebind.ErrConsumed):
			writeError(w, http.StatusConflict, "REBINDS_CONSUMED", "Rebind session was already used.")
		case errors.Is(err, rebind.ErrInvalid):
			writeError(w, http.StatusBadRequest, "REBINDS_INVALID", "Rebind credential is invalid.")
		case errors.Is(err, rebind.ErrNotInitialized):
			writeError(w, http.StatusConflict, "REBINDS_NOT_ALLOWED", "Server is not initialized.")
		default:
			logger.Error("claim rebind session", "error", err)
			writeError(w, 500, "INTERNAL_ERROR", "Internal server error.")
		}
	}
}

func rebindStatus(auth deviceAuthenticator, service rebindService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		browser, ok := authenticateBrowser(w, r, auth, logger)
		if !ok {
			return
		}
		status, err := service.Poll(r.Context(), r.PathValue("id"), browser.ID)
		if errors.Is(err, rebind.ErrInvalid) {
			writeError(w, http.StatusBadRequest, "REBINDS_INVALID", "Rebind session is invalid.")
			return
		}
		if err != nil {
			logger.Error("poll rebind session", "error", err)
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": status})
	}
}

func authenticateBrowser(w http.ResponseWriter, r *http.Request, auth deviceAuthenticator, logger *slog.Logger) (deviceauth.Device, bool) {
	cookie, err := r.Cookie(browserCookieName)
	if err != nil || strings.TrimSpace(cookie.Value) == "" {
		writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "Browser is not paired.")
		return deviceauth.Device{}, false
	}
	device, err := auth.Authenticate(r.Context(), cookie.Value, deviceauth.WindowsBrowser)
	if !handleAuthenticationError(w, err, logger) {
		return deviceauth.Device{}, false
	}
	return device, true
}
