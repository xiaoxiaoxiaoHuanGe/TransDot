package httpserver

import (
	"bytes"
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
	"unicode/utf8"

	"transdot.local/transfer-assistant/server/internal/deviceauth"
	"transdot.local/transfer-assistant/server/internal/lantransfer"
	"transdot.local/transfer-assistant/server/internal/pairing"
	"transdot.local/transfer-assistant/server/internal/realtime"
	"transdot.local/transfer-assistant/server/internal/setup"
)

type databasePinger interface {
	PingContext(context.Context) error
}

type setupService interface {
	Status(context.Context) (bool, error)
	Claim(context.Context, string) (setup.ClaimResult, error)
}

type deviceAuthenticator interface {
	Authenticate(context.Context, string, string) (deviceauth.Device, error)
}

type pairingService interface {
	Create(context.Context) (pairing.Session, error)
	Approve(context.Context, pairing.Credential, string, bool) error
	Reject(context.Context, pairing.Credential) error
	Poll(context.Context, string, string) (pairing.PollResult, error)
}

func New(
	db databasePinger,
	setupService setupService,
	authService deviceAuthenticator,
	pairingService pairingService,
	messageService messageService,
	hub *realtime.Hub,
	webHandler http.Handler,
	logger *slog.Logger,
) http.Handler {
	return newHandler(db, setupService, authService, pairingService, messageService, nil, hub, webHandler, logger)
}

func NewWithFiles(
	db databasePinger,
	setupService setupService,
	authService deviceAuthenticator,
	pairingService pairingService,
	messageService messageService,
	fileService fileService,
	hub *realtime.Hub,
	webHandler http.Handler,
	logger *slog.Logger,
) http.Handler {
	return newHandler(db, setupService, authService, pairingService, messageService, fileService, hub, webHandler, logger)
}

func NewWithFeatures(
	db databasePinger, setupService setupService, authService deviceAuthenticator,
	pairingService pairingService, messageService messageService, fileService fileService,
	instances instanceService, publicURL string, hub *realtime.Hub, webHandler http.Handler, logger *slog.Logger,
) http.Handler {
	return newHandlerWithInstance(db, setupService, authService, pairingService, messageService, fileService, instances, publicURL, hub, webHandler, logger)
}

func NewComplete(
	db databasePinger, setupService setupService, authService deviceAuthenticator,
	pairingService pairingService, bootstrapService bootstrapService, messageService messageService, fileService fileService,
	instances instanceService, publicURL string, lanBroker *lantransfer.Broker, hub *realtime.Hub, webHandler http.Handler, logger *slog.Logger,
) http.Handler {
	return newHandlerComplete(db, setupService, authService, pairingService, bootstrapService, nil, messageService, fileService, instances, publicURL, lanBroker, hub, webHandler, logger)
}

func NewCompleteWithRebind(
	db databasePinger, setupService setupService, authService deviceAuthenticator,
	pairingService pairingService, bootstrapService bootstrapService, rebindService rebindService, messageService messageService, fileService fileService,
	instances instanceService, publicURL string, lanBroker *lantransfer.Broker, hub *realtime.Hub, webHandler http.Handler, logger *slog.Logger,
) http.Handler {
	return newHandlerComplete(db, setupService, authService, pairingService, bootstrapService, rebindService, messageService, fileService, instances, publicURL, lanBroker, hub, webHandler, logger)
}

func newHandler(
	db databasePinger,
	setupService setupService,
	authService deviceAuthenticator,
	pairingService pairingService,
	messageService messageService,
	fileService fileService,
	hub *realtime.Hub,
	webHandler http.Handler,
	logger *slog.Logger,
) http.Handler {
	return newHandlerWithInstance(db, setupService, authService, pairingService, messageService, fileService, nil, "", hub, webHandler, logger)
}

func newHandlerWithInstance(
	db databasePinger, setupService setupService, authService deviceAuthenticator,
	pairingService pairingService, messageService messageService, fileService fileService,
	instances instanceService, publicURL string, hub *realtime.Hub, webHandler http.Handler, logger *slog.Logger,
) http.Handler {
	return newHandlerComplete(db, setupService, authService, pairingService, nil, nil, messageService, fileService, instances, publicURL, nil, hub, webHandler, logger)
}

func newHandlerComplete(
	db databasePinger, setupService setupService, authService deviceAuthenticator,
	pairingService pairingService, bootstrapService bootstrapService, rebindService rebindService, messageService messageService, fileService fileService,
	instances instanceService, publicURL string, lanBroker *lantransfer.Broker, hub *realtime.Hub, webHandler http.Handler, logger *slog.Logger,
) http.Handler {
	mux := http.NewServeMux()
	setupLimiter := newAttemptLimiter(5, 5*time.Minute)
	pairingCreateLimiter := newAttemptLimiter(10, 2*time.Minute)
	pairingActionLimiter := newAttemptLimiter(15, 2*time.Minute)
	bootstrapCreateLimiter := newAttemptLimiter(10, 2*time.Minute)
	bootstrapClaimLimiter := newAttemptLimiter(5, 5*time.Minute)
	rebindCreateLimiter := newAttemptLimiter(10, 2*time.Minute)
	rebindClaimLimiter := newAttemptLimiter(5, 5*time.Minute)
	mux.HandleFunc("GET /healthz", healthz(db))
	mux.HandleFunc("GET /api/v1/setup/status", setupStatus(setupService, logger))
	if instances != nil {
		mux.HandleFunc("GET /api/v1/instance/info", instanceInfo(instances, setupService, publicURL, logger))
	}
	if rebindService != nil && instances != nil {
		mux.HandleFunc("POST /api/v1/rebind/sessions", limitBootstrap(rebindCreateLimiter, createRebindSession(authService, rebindService, instances, publicURL, logger)))
		mux.HandleFunc("POST /api/v1/rebind/claim", limitBootstrap(rebindClaimLimiter, claimRebind(rebindService, logger)))
		mux.HandleFunc("GET /api/v1/rebind/sessions/{id}/status", rebindStatus(authService, rebindService, logger))
	}
	if bootstrapService != nil && instances != nil {
		mux.HandleFunc("POST /api/v1/bootstrap/sessions", limitBootstrap(bootstrapCreateLimiter, createBootstrapSession(bootstrapService, instances, publicURL, logger)))
		mux.HandleFunc("POST /api/v1/bootstrap/claim", limitBootstrap(bootstrapClaimLimiter, claimBootstrap(bootstrapService, logger)))
		mux.HandleFunc("GET /api/v1/bootstrap/sessions/{id}/status", bootstrapStatus(bootstrapService, logger))
	}
	mux.HandleFunc("POST /api/v1/setup/claim", setupClaim(setupService, setupLimiter, logger))
	mux.HandleFunc("GET /api/v1/auth/session", browserSession(authService, logger))
	if instances != nil {
		mux.HandleFunc("POST /api/v1/pairing/sessions", createPairingSessionWithInstance(pairingService, pairingCreateLimiter, instances, publicURL, logger))
	} else {
		mux.HandleFunc("POST /api/v1/pairing/sessions", createPairingSession(pairingService, pairingCreateLimiter, logger))
	}
	mux.HandleFunc("GET /api/v1/pairing/sessions/{id}/status", pairingStatus(pairingService, logger))
	mux.HandleFunc("POST /api/v1/pairing/approve", approvePairing(authService, pairingService, pairingActionLimiter, logger))
	mux.HandleFunc("POST /api/v1/pairing/reject", rejectPairing(authService, pairingService, pairingActionLimiter, logger))
	mux.HandleFunc("GET /api/v1/messages", listMessages(authService, messageService, logger))
	mux.HandleFunc("POST /api/v1/messages/text", createTextMessage(authService, messageService, hub, logger))
	mux.HandleFunc("DELETE /api/v1/messages/{id}", deleteMessage(authService, messageService, fileService, hub, logger))
	mux.HandleFunc("GET /api/v1/messages/{id}/context", messageContext(authService, messageService, logger))
	mux.HandleFunc("GET /api/v1/search", searchMessages(authService, messageService, logger))
	if fileService != nil {
		mux.HandleFunc("POST /api/v1/upload-batches", createUploadBatch(authService, fileService, logger))
		mux.HandleFunc("PUT /api/v1/uploads/{id}", uploadFile(authService, fileService, hub, logger))
		mux.HandleFunc("PUT /api/v1/uploads/{id}/thumbnail", uploadThumbnail(authService, fileService, logger))
		mux.HandleFunc("GET /api/v1/files/{id}/download", downloadFile(authService, fileService, logger))
		mux.HandleFunc("GET /api/v1/files/{id}/thumbnail", serveThumbnail(authService, fileService, logger))
	}
	mux.HandleFunc("/api/", apiNotFound)
	mux.HandleFunc("GET /ws", websocketEndpoint(authService, hub, lanBroker, logger))
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

		var request claimRequest
		if err := decodeJSONBody(w, r, &request); err != nil {
			writeError(w, http.StatusBadRequest, "INVALID_REQUEST", "Request body must be valid JSON.")
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

func decodeJSONBody(w http.ResponseWriter, r *http.Request, destination any) error {
	return decodeJSONBodyLimit(w, r, destination, 8*1024)
}

func decodeJSONBodyLimit(w http.ResponseWriter, r *http.Request, destination any, maximumBytes int64) error {
	r.Body = http.MaxBytesReader(w, r.Body, maximumBytes)
	contents, err := io.ReadAll(r.Body)
	if err != nil {
		return err
	}
	if !utf8.Valid(contents) {
		return errors.New("request body must be valid UTF-8")
	}
	decoder := json.NewDecoder(bytes.NewReader(contents))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("request body must contain one JSON object")
	}
	return nil
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
	mu        sync.Mutex
	limit     int
	duration  time.Duration
	windows   map[string]attemptWindow
	lastSweep time.Time
}

func newAttemptLimiter(limit int, duration time.Duration) *attemptLimiter {
	return &attemptLimiter{limit: limit, duration: duration, windows: make(map[string]attemptWindow)}
}

func (l *attemptLimiter) Allow(key string, now time.Time) bool {
	allowed, _ := l.AllowWithRetryAfter(key, now)
	return allowed
}

func (l *attemptLimiter) AllowWithRetryAfter(key string, now time.Time) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.lastSweep.IsZero() || now.Sub(l.lastSweep) >= l.duration {
		for existingKey, existingWindow := range l.windows {
			if now.Sub(existingWindow.started) >= l.duration {
				delete(l.windows, existingKey)
			}
		}
		l.lastSweep = now
	}

	window, exists := l.windows[key]
	if !exists && len(l.windows) >= maxAttemptLimiterEntries {
		return false, l.duration
	}
	if !exists || now.Sub(window.started) >= l.duration {
		l.windows[key] = attemptWindow{started: now, count: 1}
		return true, 0
	}
	if window.count >= l.limit {
		return false, window.started.Add(l.duration).Sub(now)
	}
	window.count++
	l.windows[key] = window
	return true, 0
}

const maxAttemptLimiterEntries = 10_000

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

func (r *statusRecorder) Unwrap() http.ResponseWriter {
	return r.ResponseWriter
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
