package httpserver

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"transdot.local/transfer-assistant/server/internal/setup"
)

type databasePinger interface {
	PingContext(context.Context) error
}

type setupService interface {
	Status(context.Context) (bool, error)
	Claim(context.Context, string) (setup.ClaimResult, error)
}

func New(db databasePinger, setupService setupService, webHandler http.Handler, logger *slog.Logger) http.Handler {
	mux := http.NewServeMux()
	setupLimiter := newAttemptLimiter(5, 5*time.Minute)
	mux.HandleFunc("GET /healthz", healthz(db))
	mux.HandleFunc("GET /api/v1/setup/status", setupStatus(setupService, logger))
	mux.HandleFunc("POST /api/v1/setup/claim", setupClaim(setupService, setupLimiter, logger))
	mux.HandleFunc("/api/", apiNotFound)
	mux.HandleFunc("/ws", http.NotFound)
	mux.Handle("/", webHandler)

	return recoverRequests(logger, logRequests(logger, mux))
}

func setupStatus(service setupService, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		initialized, err := service.Status(r.Context())
		if err != nil {
			logger.Error("read setup status", "error", err)
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"initialized": initialized})
	}
}

func setupClaim(service setupService, limiter *attemptLimiter, logger *slog.Logger) http.HandlerFunc {
	type claimRequest struct {
		SetupToken string `json:"setup_token"`
	}

	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		clientIP := remoteIP(r.RemoteAddr)
		if !limiter.Allow(clientIP, time.Now()) {
			writeError(w, http.StatusTooManyRequests, "RATE_LIMITED", "Too many setup attempts. Try again later.")
			return
		}

		r.Body = http.MaxBytesReader(w, r.Body, 8*1024)
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()

		var request claimRequest
		if err := decoder.Decode(&request); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Request body must be valid JSON.")
			return
		}
		if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Request body must contain one JSON object.")
			return
		}
		if strings.TrimSpace(request.SetupToken) == "" {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Setup token is required.")
			return
		}

		result, err := service.Claim(r.Context(), strings.TrimSpace(request.SetupToken))
		switch {
		case err == nil:
			writeJSON(w, http.StatusCreated, result)
		case errors.Is(err, setup.ErrInvalidSetupToken):
			writeError(w, http.StatusUnauthorized, "SETUP_TOKEN_INVALID", "Setup token is invalid.")
		case errors.Is(err, setup.ErrAlreadyInitialized):
			writeError(w, http.StatusConflict, "ALREADY_INITIALIZED", "Server is already initialized.")
		default:
			logger.Error("claim server owner", "error", err)
			writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error.")
		}
	}
}

func healthz(db databasePinger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()

		if err := db.PingContext(ctx); err != nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}

func apiNotFound(w http.ResponseWriter, _ *http.Request) {
	writeError(w, http.StatusNotFound, "NOT_FOUND", "API endpoint not found.")
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]any{
		"error": map[string]string{"code": code, "message": message},
	})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

type attemptWindow struct {
	started time.Time
	count   int
}

type attemptLimiter struct {
	mu       sync.Mutex
	limit    int
	duration time.Duration
	windows  map[string]attemptWindow
}

func newAttemptLimiter(limit int, duration time.Duration) *attemptLimiter {
	return &attemptLimiter{limit: limit, duration: duration, windows: make(map[string]attemptWindow)}
}

func (l *attemptLimiter) Allow(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	window, exists := l.windows[key]
	if !exists || now.Sub(window.started) >= l.duration {
		l.windows[key] = attemptWindow{started: now, count: 1}
		return true
	}
	if window.count >= l.limit {
		return false
	}
	window.count++
	l.windows[key] = window
	return true
}

func remoteIP(remoteAddress string) string {
	host, _, err := net.SplitHostPort(remoteAddress)
	if err == nil {
		return host
	}
	return remoteAddress
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

func logRequests(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started := time.Now()
		recorder := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(recorder, r)
		logger.Info("http request",
			"method", r.Method,
			"path", r.URL.Path,
			"status", recorder.status,
			"duration_ms", time.Since(started).Milliseconds(),
		)
	})
}

func recoverRequests(logger *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				logger.Error("http handler panic", "error", recovered, "stack", string(debug.Stack()))
				writeJSON(w, http.StatusInternalServerError, map[string]any{
					"error": map[string]string{
						"code":    "INTERNAL_ERROR",
						"message": "Internal server error.",
					},
				})
			}
		}()
		next.ServeHTTP(w, r)
	})
}
