package httpserver

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"transdot.local/transfer-assistant/server/internal/pairing"
)

type pairingActionRequest struct {
	SessionID       string `json:"session_id"`
	QRSecret        string `json:"qr_secret"`
	PairingCode     string `json:"pairing_code"`
	ReplaceExisting bool   `json:"replace_existing"`
}

func createPairingSession(service pairingService, limiter *attemptLimiter, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		allowed, retryAfter := limiter.AllowWithRetryAfter(remoteIP(r.RemoteAddr), time.Now())
		if !allowed {
			seconds := max(1, int((retryAfter+time.Second-1)/time.Second))
			w.Header().Set("Retry-After", strconv.Itoa(seconds))
			writeError(w, http.StatusTooManyRequests, "RATE_LIMITED", "Too many pairing sessions. Try again later.")
			return
		}
		session, err := service.Create(r.Context())
		switch {
		case err == nil:
			qrPayload, marshalErr := json.Marshal(map[string]any{
				"v":          1,
				"session_id": session.ID,
				"qr_secret":  session.QRSecret,
			})
			if marshalErr != nil {
				logger.Error("marshal pairing QR payload", "error", marshalErr)
				writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
				return
			}
			setPairingCookie(w, session.BrowserToken, session.ExpiresAt)
			writeJSON(w, http.StatusCreated, map[string]any{
				"session_id":            session.ID,
				"pairing_code":          session.Code,
				"qr_payload":            string(qrPayload),
				"expires_at":            session.ExpiresAt.UTC().Format(time.RFC3339Nano),
				"poll_interval_seconds": 2,
			})
		case errors.Is(err, pairing.ErrNotInitialized):
			writeError(w, http.StatusConflict, "SETUP_REQUIRED", "Android Master setup is required first.")
		default:
			logger.Error("create pairing session", "error", err)
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
		}
	}
}

func pairingStatus(service pairingService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie(pairingCookieName)
		if err != nil {
			writeError(w, http.StatusUnauthorized, "PAIRING_INVALID", "Pairing session is invalid.")
			return
		}
		result, err := service.Poll(r.Context(), r.PathValue("id"), cookie.Value)
		if err != nil {
			writePairingError(w, err, logger)
			return
		}
		if result.BrowserToken != "" {
			setBrowserCookie(w, result.BrowserToken)
			clearPairingCookie(w)
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": result.Status})
	}
}

func approvePairing(
	authService deviceAuthenticator,
	service pairingService,
	limiter *attemptLimiter,
	logger *slog.Logger,
) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !limiter.Allow(remoteIP(r.RemoteAddr), time.Now()) {
			writeError(w, http.StatusTooManyRequests, "RATE_LIMITED", "Too many pairing attempts. Try again later.")
			return
		}
		master, ok := authenticateMaster(w, r, authService, logger)
		if !ok {
			return
		}
		var request pairingActionRequest
		if err := decodeJSONBody(w, r, &request); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Request body must be valid JSON.")
			return
		}
		err := service.Approve(r.Context(), pairingCredential(request), master.ID, request.ReplaceExisting)
		if err != nil {
			writePairingError(w, err, logger)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": pairing.StatusApproved})
	}
}

func rejectPairing(
	authService deviceAuthenticator,
	service pairingService,
	limiter *attemptLimiter,
	logger *slog.Logger,
) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !limiter.Allow(remoteIP(r.RemoteAddr), time.Now()) {
			writeError(w, http.StatusTooManyRequests, "RATE_LIMITED", "Too many pairing attempts. Try again later.")
			return
		}
		if _, ok := authenticateMaster(w, r, authService, logger); !ok {
			return
		}
		var request pairingActionRequest
		if err := decodeJSONBody(w, r, &request); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Request body must be valid JSON.")
			return
		}
		if err := service.Reject(r.Context(), pairingCredential(request)); err != nil {
			writePairingError(w, err, logger)
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": pairing.StatusRejected})
	}
}

func pairingCredential(request pairingActionRequest) pairing.Credential {
	return pairing.Credential{
		SessionID: strings.TrimSpace(request.SessionID),
		QRSecret:  strings.TrimSpace(request.QRSecret),
		Code:      strings.ReplaceAll(strings.TrimSpace(request.PairingCode), " ", ""),
	}
}

func writePairingError(w http.ResponseWriter, err error, logger *slog.Logger) {
	switch {
	case errors.Is(err, pairing.ErrPairingExpired):
		writeError(w, http.StatusGone, "PAIRING_EXPIRED", "Pairing session has expired.")
	case errors.Is(err, pairing.ErrInvalidPairing):
		writeError(w, http.StatusBadRequest, "PAIRING_INVALID", "Pairing session or credential is invalid.")
	case errors.Is(err, pairing.ErrReplacementRequired):
		writeError(w, http.StatusConflict, "WINDOWS_REPLACEMENT_REQUIRED", "An active Windows browser already exists.")
	default:
		logger.Error("pairing request", "error", err)
		writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
	}
}

func setPairingCookie(w http.ResponseWriter, token string, expiresAt time.Time) {
	maxAge := int(time.Until(expiresAt).Seconds())
	if maxAge < 1 {
		maxAge = 1
	}
	http.SetCookie(w, &http.Cookie{
		Name:     pairingCookieName,
		Value:    token,
		Path:     "/api/v1/pairing",
		MaxAge:   maxAge,
		Expires:  expiresAt.UTC(),
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteStrictMode,
	})
}

func clearPairingCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     pairingCookieName,
		Value:    "",
		Path:     "/api/v1/pairing",
		MaxAge:   -1,
		Expires:  time.Unix(1, 0).UTC(),
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteStrictMode,
	})
}
