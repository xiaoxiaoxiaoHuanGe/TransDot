package rebind

import (
	"context"
	cryptorand "crypto/rand"
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
)

const (
	StatusPending  = "pending"
	StatusExpired  = "expired"
	StatusConsumed = "consumed"
)

var (
	ErrNotInitialized = errors.New("server is not initialized")
	ErrInvalid        = errors.New("invalid rebind credential")
	ErrExpired        = errors.New("rebind session expired")
	ErrConsumed       = errors.New("rebind session consumed")
)

type Session struct {
	ID        string
	Secret    string
	ExpiresAt time.Time
}

type ClaimResult struct {
	DeviceID            string `json:"device_id"`
	MasterToken         string `json:"master_token"`
	InstanceID          string `json:"instance_id"`
	InstanceFingerprint string `json:"instance_fingerprint"`
}

type Service struct {
	db                  *sql.DB
	instanceID          string
	instanceFingerprint string
	ttl                 time.Duration
	random              io.Reader
	now                 func() time.Time
	onDevicesRevoked    func([]string)
}

func NewService(db *sql.DB, instanceID, instanceFingerprint string, ttl time.Duration, onDevicesRevoked ...func([]string)) *Service {
	service := &Service{db: db, instanceID: instanceID, instanceFingerprint: instanceFingerprint, ttl: ttl, random: cryptorand.Reader, now: time.Now}
	if len(onDevicesRevoked) > 0 {
		service.onDevicesRevoked = onDevicesRevoked[0]
	}
	return service
}

func (s *Service) Create(ctx context.Context, browserDeviceID string) (Session, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Session{}, err
	}
	defer tx.Rollback()
	var initialized int
	if err = tx.QueryRowContext(ctx, `SELECT initialized FROM app_state WHERE id=1`).Scan(&initialized); err != nil {
		return Session{}, err
	}
	if initialized != 1 {
		return Session{}, ErrNotInitialized
	}
	secret, err := s.token()
	if err != nil {
		return Session{}, fmt.Errorf("generate rebind secret: %w", err)
	}
	now := s.now().UTC()
	session := Session{ID: uuid.NewString(), Secret: secret, ExpiresAt: now.Add(s.ttl)}
	hash := sha256.Sum256([]byte(secret))
	owner := strings.TrimSpace(browserDeviceID)
	if _, err = tx.ExecContext(ctx, `UPDATE rebind_sessions SET status='expired' WHERE browser_device_id=? AND status='pending'`, owner); err != nil {
		return Session{}, fmt.Errorf("expire previous rebind sessions: %w", err)
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO rebind_sessions(id, instance_id, secret_hash, browser_device_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)`, session.ID, s.instanceID, hash[:], owner, now.Format(time.RFC3339Nano), session.ExpiresAt.Format(time.RFC3339Nano))
	if err != nil {
		return Session{}, fmt.Errorf("create rebind session: %w", err)
	}
	if err = tx.Commit(); err != nil {
		return Session{}, err
	}
	return session, nil
}

func (s *Service) Claim(ctx context.Context, sessionID, secret, instanceID string) (ClaimResult, error) {
	masterToken, err := s.token()
	if err != nil {
		return ClaimResult{}, fmt.Errorf("generate master token: %w", err)
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ClaimResult{}, err
	}
	defer tx.Rollback()
	var initialized int
	if err = tx.QueryRowContext(ctx, `SELECT initialized FROM app_state WHERE id=1`).Scan(&initialized); err != nil {
		return ClaimResult{}, err
	}
	if initialized != 1 {
		return ClaimResult{}, ErrNotInitialized
	}
	var storedInstance, status, expiresRaw string
	var storedHash []byte
	err = tx.QueryRowContext(ctx, `SELECT instance_id, secret_hash, status, expires_at FROM rebind_sessions WHERE id=?`, strings.TrimSpace(sessionID)).Scan(&storedInstance, &storedHash, &status, &expiresRaw)
	if errors.Is(err, sql.ErrNoRows) {
		return ClaimResult{}, ErrInvalid
	}
	if err != nil {
		return ClaimResult{}, err
	}
	provided := sha256.Sum256([]byte(strings.TrimSpace(secret)))
	if storedInstance != s.instanceID || strings.TrimSpace(instanceID) != s.instanceID || subtle.ConstantTimeCompare(provided[:], storedHash) != 1 {
		return ClaimResult{}, ErrInvalid
	}
	if status == StatusExpired {
		return ClaimResult{}, ErrExpired
	}
	if status != StatusPending {
		return ClaimResult{}, ErrConsumed
	}
	expires, err := time.Parse(time.RFC3339Nano, expiresRaw)
	if err != nil {
		return ClaimResult{}, err
	}
	if !s.now().UTC().Before(expires) {
		_, _ = tx.ExecContext(ctx, `UPDATE rebind_sessions SET status='expired' WHERE id=?`, sessionID)
		_ = tx.Commit()
		return ClaimResult{}, ErrExpired
	}
	rows, err := tx.QueryContext(ctx, `SELECT id FROM devices WHERE device_type='android_master' AND revoked_at IS NULL`)
	if err != nil {
		return ClaimResult{}, err
	}
	var revoked []string
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return ClaimResult{}, err
		}
		revoked = append(revoked, id)
	}
	if err = rows.Close(); err != nil {
		return ClaimResult{}, err
	}
	now := s.now().UTC().Format(time.RFC3339Nano)
	if _, err = tx.ExecContext(ctx, `UPDATE devices SET revoked_at=? WHERE device_type='android_master' AND revoked_at IS NULL`, now); err != nil {
		return ClaimResult{}, err
	}
	deviceID := uuid.NewString()
	masterHash := sha256.Sum256([]byte(masterToken))
	if _, err = tx.ExecContext(ctx, `INSERT INTO devices(id, device_type, token_hash) VALUES (?, 'android_master', ?)`, deviceID, masterHash[:]); err != nil {
		return ClaimResult{}, err
	}
	result, err := tx.ExecContext(ctx, `UPDATE rebind_sessions SET status='consumed', consumed_at=? WHERE id=? AND status='pending'`, now, sessionID)
	if err != nil {
		return ClaimResult{}, err
	}
	if count, _ := result.RowsAffected(); count != 1 {
		return ClaimResult{}, ErrConsumed
	}
	if _, err = tx.ExecContext(ctx, `UPDATE rebind_sessions SET status='expired' WHERE id<>? AND status='pending'`, sessionID); err != nil {
		return ClaimResult{}, err
	}
	if err = tx.Commit(); err != nil {
		return ClaimResult{}, err
	}
	if len(revoked) > 0 && s.onDevicesRevoked != nil {
		s.onDevicesRevoked(revoked)
	}
	return ClaimResult{DeviceID: deviceID, MasterToken: masterToken, InstanceID: s.instanceID, InstanceFingerprint: s.instanceFingerprint}, nil
}

func (s *Service) Poll(ctx context.Context, sessionID, browserDeviceID string) (string, error) {
	var status, expiresRaw, owner string
	err := s.db.QueryRowContext(ctx, `SELECT status, expires_at, browser_device_id FROM rebind_sessions WHERE id=?`, strings.TrimSpace(sessionID)).Scan(&status, &expiresRaw, &owner)
	if errors.Is(err, sql.ErrNoRows) {
		return "", ErrInvalid
	}
	if err != nil {
		return "", err
	}
	if owner != strings.TrimSpace(browserDeviceID) {
		return "", ErrInvalid
	}
	expires, err := time.Parse(time.RFC3339Nano, expiresRaw)
	if err != nil {
		return "", err
	}
	if status == StatusPending && !s.now().UTC().Before(expires) {
		if _, err = s.db.ExecContext(ctx, `UPDATE rebind_sessions SET status='expired' WHERE id=? AND status='pending'`, sessionID); err != nil {
			return "", err
		}
		return StatusExpired, nil
	}
	return status, nil
}

func (s *Service) token() (string, error) {
	raw := make([]byte, 32)
	if _, err := io.ReadFull(s.random, raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}
