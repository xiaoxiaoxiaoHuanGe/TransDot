package bootstrap

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/google/uuid"
	"transdot.local/transfer-assistant/server/internal/setup"
)

const (
	StatusPending  = "pending"
	StatusApproved = "approved"
	StatusExpired  = "expired"
)

var (
	ErrAlreadyInitialized = errors.New("server is already initialized")
	ErrInvalid            = errors.New("invalid bootstrap credential")
	ErrExpired            = errors.New("bootstrap session expired")
	ErrConsumed           = errors.New("bootstrap session consumed")
)

type Session struct {
	ID, Secret, BrowserToken string
	ExpiresAt                time.Time
}
type PollResult struct{ Status, BrowserToken string }
type Service struct {
	db         *sql.DB
	instanceID string
	ttl        time.Duration
	random     io.Reader
	now        func() time.Time
}

func NewService(db *sql.DB, instanceID string, ttl time.Duration) *Service {
	return &Service{db: db, instanceID: instanceID, ttl: ttl, random: rand.Reader, now: time.Now}
}

func (s *Service) token() (string, error) {
	raw := make([]byte, 32)
	if _, err := io.ReadFull(s.random, raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func (s *Service) Create(ctx context.Context) (Session, error) {
	var initialized int
	if err := s.db.QueryRowContext(ctx, `SELECT initialized FROM app_state WHERE id = 1`).Scan(&initialized); err != nil {
		return Session{}, err
	}
	if initialized == 1 {
		return Session{}, ErrAlreadyInitialized
	}
	secret, err := s.token()
	if err != nil {
		return Session{}, err
	}
	browserToken, err := s.token()
	if err != nil {
		return Session{}, err
	}
	now, expires := s.now().UTC(), s.now().UTC().Add(s.ttl)
	secretHash, browserHash := sha256.Sum256([]byte(secret)), sha256.Sum256([]byte(browserToken))
	session := Session{ID: uuid.NewString(), Secret: secret, BrowserToken: browserToken, ExpiresAt: expires}
	_, err = s.db.ExecContext(ctx, `INSERT INTO bootstrap_sessions(id, instance_id, secret_hash, browser_token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)`, session.ID, s.instanceID, secretHash[:], browserHash[:], now.Format(time.RFC3339Nano), expires.Format(time.RFC3339Nano))
	if err != nil {
		return Session{}, fmt.Errorf("create bootstrap session: %w", err)
	}
	return session, nil
}

func (s *Service) Claim(ctx context.Context, sessionID, secret string) (setup.ClaimResult, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return setup.ClaimResult{}, err
	}
	defer tx.Rollback()
	var instanceID, status, expiresRaw string
	var storedHash []byte
	err = tx.QueryRowContext(ctx, `SELECT instance_id, status, expires_at, secret_hash FROM bootstrap_sessions WHERE id = ?`, strings.TrimSpace(sessionID)).Scan(&instanceID, &status, &expiresRaw, &storedHash)
	if errors.Is(err, sql.ErrNoRows) {
		return setup.ClaimResult{}, ErrInvalid
	}
	if err != nil {
		return setup.ClaimResult{}, err
	}
	provided := sha256.Sum256([]byte(strings.TrimSpace(secret)))
	if instanceID != s.instanceID || subtle.ConstantTimeCompare(provided[:], storedHash) != 1 {
		return setup.ClaimResult{}, ErrInvalid
	}
	if status != StatusPending {
		return setup.ClaimResult{}, ErrConsumed
	}
	expires, err := time.Parse(time.RFC3339Nano, expiresRaw)
	if err != nil {
		return setup.ClaimResult{}, err
	}
	if !s.now().UTC().Before(expires) {
		_, _ = tx.ExecContext(ctx, `UPDATE bootstrap_sessions SET status='expired' WHERE id=?`, sessionID)
		_ = tx.Commit()
		return setup.ClaimResult{}, ErrExpired
	}
	result, err := tx.ExecContext(ctx, `UPDATE app_state SET initialized=1, updated_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=1 AND initialized=0`)
	if err != nil {
		return setup.ClaimResult{}, err
	}
	rows, _ := result.RowsAffected()
	if rows != 1 {
		return setup.ClaimResult{}, ErrAlreadyInitialized
	}
	masterToken, err := s.token()
	if err != nil {
		return setup.ClaimResult{}, err
	}
	masterHash := sha256.Sum256([]byte(masterToken))
	deviceID := uuid.NewString()
	if _, err = tx.ExecContext(ctx, `INSERT INTO devices(id, device_type, token_hash) VALUES (?, 'android_master', ?)`, deviceID, masterHash[:]); err != nil {
		return setup.ClaimResult{}, err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE bootstrap_sessions SET status='approved', consumed_at=? WHERE id=? AND status='pending'`, s.now().UTC().Format(time.RFC3339Nano), sessionID); err != nil {
		return setup.ClaimResult{}, err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE bootstrap_sessions SET status='expired' WHERE id<>? AND status='pending'`, sessionID); err != nil {
		return setup.ClaimResult{}, err
	}
	if err = tx.Commit(); err != nil {
		return setup.ClaimResult{}, err
	}
	return setup.ClaimResult{DeviceID: deviceID, MasterToken: masterToken}, nil
}

func (s *Service) Poll(ctx context.Context, sessionID, browserToken string) (PollResult, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return PollResult{}, err
	}
	defer tx.Rollback()
	var status, expiresRaw string
	var storedHash []byte
	var browserDevice sql.NullString
	err = tx.QueryRowContext(ctx, `SELECT status, expires_at, browser_token_hash, browser_device_id FROM bootstrap_sessions WHERE id=?`, sessionID).Scan(&status, &expiresRaw, &storedHash, &browserDevice)
	if errors.Is(err, sql.ErrNoRows) {
		return PollResult{}, ErrInvalid
	}
	if err != nil {
		return PollResult{}, err
	}
	provided := sha256.Sum256([]byte(browserToken))
	if subtle.ConstantTimeCompare(provided[:], storedHash) != 1 {
		return PollResult{}, ErrInvalid
	}
	expires, _ := time.Parse(time.RFC3339Nano, expiresRaw)
	if status == StatusPending && !s.now().UTC().Before(expires) {
		_, _ = tx.ExecContext(ctx, `UPDATE bootstrap_sessions SET status='expired' WHERE id=?`, sessionID)
		_ = tx.Commit()
		return PollResult{Status: StatusExpired}, nil
	}
	if status == StatusPending || status == StatusExpired {
		return PollResult{Status: status}, nil
	}
	if browserDevice.Valid {
		return PollResult{Status: StatusApproved, BrowserToken: browserToken}, nil
	}
	deviceID := uuid.NewString()
	if _, err = tx.ExecContext(ctx, `INSERT INTO devices(id, device_type, token_hash) VALUES (?, 'windows_browser', ?)`, deviceID, provided[:]); err != nil {
		return PollResult{}, err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE bootstrap_sessions SET status='consumed', browser_device_id=? WHERE id=?`, deviceID, sessionID); err != nil {
		return PollResult{}, err
	}
	if err = tx.Commit(); err != nil {
		return PollResult{}, err
	}
	return PollResult{Status: StatusApproved, BrowserToken: browserToken}, nil
}
