package httpserver

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

func TestAttemptLimiterReportsRemainingWindow(t *testing.T) {
	limiter := newAttemptLimiter(1, 10*time.Second)
	started := time.Date(2026, 8, 15, 0, 0, 0, 0, time.UTC)
	if allowed, wait := limiter.AllowWithRetryAfter("client", started); !allowed || wait != 0 {
		t.Fatalf("first=(%v,%v), want (true,0)", allowed, wait)
	}
	if allowed, wait := limiter.AllowWithRetryAfter("client", started.Add(2500*time.Millisecond)); allowed || wait != 7500*time.Millisecond {
		t.Fatalf("limited=(%v,%v), want (false,7.5s)", allowed, wait)
	}
}

func TestAttemptLimiterResetsAfterWindow(t *testing.T) {
	limiter := newAttemptLimiter(1, 10*time.Second)
	started := time.Date(2026, 8, 15, 0, 0, 0, 0, time.UTC)
	limiter.AllowWithRetryAfter("client", started)
	if allowed, wait := limiter.AllowWithRetryAfter("client", started.Add(10*time.Second)); !allowed || wait != 0 {
		t.Fatalf("reset=(%v,%v), want (true,0)", allowed, wait)
	}
}

func TestCreatePairingSessionReportsRetryAfter(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	handler := createPairingSession(fakePairingService{}, newAttemptLimiter(1, 2*time.Minute), logger)

	firstRequest := httptest.NewRequest(http.MethodPost, "/api/v1/pairing/sessions", nil)
	firstRequest.RemoteAddr = "192.0.2.8:1234"
	handler.ServeHTTP(httptest.NewRecorder(), firstRequest)

	secondRequest := httptest.NewRequest(http.MethodPost, "/api/v1/pairing/sessions", nil)
	secondRequest.RemoteAddr = "192.0.2.8:5678"
	secondResponse := httptest.NewRecorder()
	handler.ServeHTTP(secondResponse, secondRequest)

	if secondResponse.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 429", secondResponse.Code)
	}
	seconds, err := strconv.Atoi(secondResponse.Header().Get("Retry-After"))
	if err != nil || seconds < 1 || seconds > 120 {
		t.Fatalf("Retry-After = %q, want integer in [1,120]", secondResponse.Header().Get("Retry-After"))
	}
}
