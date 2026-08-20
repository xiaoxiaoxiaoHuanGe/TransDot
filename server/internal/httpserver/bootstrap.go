package httpserver

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"transdot.local/transfer-assistant/server/internal/bootstrap"
	"transdot.local/transfer-assistant/server/internal/setup"
)

const bootstrapCookieName = "transfer_bootstrap_v1"

type bootstrapService interface {
	Create(context.Context) (bootstrap.Session, error)
	Claim(context.Context, string, string) (setup.ClaimResult, error)
	Poll(context.Context, string, string) (bootstrap.PollResult, error)
}

func createBootstrapSession(service bootstrapService, instances instanceService, configuredURL string, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, err := service.Create(r.Context())
		if errors.Is(err, bootstrap.ErrAlreadyInitialized) {
			writeError(w, http.StatusConflict, "ALREADY_INITIALIZED", "Server is already initialized.")
			return
		}
		if err != nil {
			logger.Error("create bootstrap session", "error", err)
			writeError(w, 500, "INTERNAL_ERROR", "Internal server error.")
			return
		}
		identity, err := instances.Get(r.Context())
		if err != nil {
			writeError(w, 500, "INTERNAL_ERROR", "Internal server error.")
			return
		}
		serverURL := configuredURL
		if serverURL == "" {
			scheme := "http"
			if r.TLS != nil {
				scheme = "https"
			}
			serverURL = scheme + "://" + r.Host
		}
		payload, _ := json.Marshal(map[string]any{"v": 2, "kind": "bootstrap", "server_url": serverURL, "instance_id": identity.ID, "instance_fingerprint": identity.Fingerprint, "bootstrap_session_id": session.ID, "bootstrap_secret": session.Secret, "expires_at": session.ExpiresAt.UTC().Format(time.RFC3339Nano)})
		setBootstrapCookie(w, session.BrowserToken, session.ExpiresAt)
		writeJSON(w, http.StatusCreated, map[string]any{"session_id": session.ID, "qr_payload": string(payload), "instance_fingerprint": identity.Fingerprint, "expires_at": session.ExpiresAt.UTC().Format(time.RFC3339Nano), "poll_interval_seconds": 2})
	}
}

func claimBootstrap(service bootstrapService, logger *slog.Logger) http.HandlerFunc {
	type request struct {
		SessionID string `json:"bootstrap_session_id"`
		Secret    string `json:"bootstrap_secret"`
	}
	return func(w http.ResponseWriter, r *http.Request) {
		var body request
		if decodeJSONBody(w, r, &body) != nil {
			writeError(w, 400, "INVALID_REQUEST", "Request body must be valid JSON.")
			return
		}
		result, err := service.Claim(r.Context(), strings.TrimSpace(body.SessionID), strings.TrimSpace(body.Secret))
		switch {
		case err == nil:
			writeJSON(w, http.StatusCreated, result)
		case errors.Is(err, bootstrap.ErrExpired):
			writeError(w, http.StatusGone, "BOOTSTRAP_EXPIRED", "Bootstrap session expired.")
		case errors.Is(err, bootstrap.ErrConsumed):
			writeError(w, http.StatusConflict, "BOOTSTRAP_CONSUMED", "Bootstrap session was already used.")
		case errors.Is(err, bootstrap.ErrAlreadyInitialized):
			writeError(w, http.StatusConflict, "ALREADY_INITIALIZED", "Server is already initialized.")
		case errors.Is(err, bootstrap.ErrInvalid):
			writeError(w, http.StatusBadRequest, "BOOTSTRAP_INVALID", "Bootstrap credential is invalid.")
		default:
			logger.Error("claim bootstrap session", "error", err)
			writeError(w, 500, "INTERNAL_ERROR", "Internal server error.")
		}
	}
}

func bootstrapStatus(service bootstrapService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie(bootstrapCookieName)
		if err != nil {
			writeError(w, 401, "BOOTSTRAP_INVALID", "Bootstrap session is invalid.")
			return
		}
		result, err := service.Poll(r.Context(), r.PathValue("id"), cookie.Value)
		if err != nil {
			logger.Error("poll bootstrap session", "error", err)
			writeError(w, 400, "BOOTSTRAP_INVALID", "Bootstrap session is invalid.")
			return
		}
		if result.BrowserToken != "" {
			setBrowserCookie(w, result.BrowserToken)
			clearBootstrapCookie(w)
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": result.Status})
	}
}

func setBootstrapCookie(w http.ResponseWriter, token string, expiry time.Time) {
	http.SetCookie(w, &http.Cookie{Name: bootstrapCookieName, Value: token, Path: "/api/v1/bootstrap", Expires: expiry, MaxAge: max(1, int(time.Until(expiry).Seconds())), HttpOnly: true, Secure: true, SameSite: http.SameSiteStrictMode})
}
func clearBootstrapCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{Name: bootstrapCookieName, Path: "/api/v1/bootstrap", MaxAge: -1, Expires: time.Unix(1, 0), HttpOnly: true, Secure: true, SameSite: http.SameSiteStrictMode})
}

func limitBootstrap(limiter *attemptLimiter, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		allowed, wait := limiter.AllowWithRetryAfter(remoteIP(r.RemoteAddr), time.Now())
		if !allowed {
			seconds := max(1, int((wait+time.Second-1)/time.Second))
			w.Header().Set("Retry-After", strconv.Itoa(seconds))
			writeError(w, http.StatusTooManyRequests, "RATE_LIMITED", "Too many bootstrap attempts. Try again later.")
			return
		}
		next(w, r)
	}
}
