package pairing

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
	"math/big"
	"strings"
	"time"

	"github.com/google/uuid"
)

const (
	StatusPending  = "pending"
	StatusApproved = "approved"
	StatusRejected = "rejected"
	StatusExpired  = "expired"
	StatusConsumed = "consumed"

	randomTokenBytes   = 32
	maxFailedAttempts  = 5
	maxCodeValue       = 1_000_000
	maxCodeInsertTries = 10
	timestampFormat    = "2006-01-02T15:04:05.000000000Z07:00"
)

var (
	ErrNotInitialized      = errors.New("server has no Android master")
	ErrInvalidPairing      = errors.New("pairing credential is invalid")
	ErrPairingExpired      = errors.New("pairing session is expired")
	ErrReplacementRequired = errors.New("an active Windows browser must be replaced")
)

type Session struct {
	ID           string
	Code         string
	QRSecret     string
	BrowserToken string
	ExpiresAt    time.Time
}

type Credential struct {
	SessionID string
	QRSecret  string
	Code      string
}

type PollResult struct {
	Status       string
	BrowserToken string
}

type Service struct {
	db     *sql.DB
	ttl    time.Duration
	random io.Reader
	now    func() time.Time
}

func NewService(db *sql.DB, ttl time.Duration) *Service {
	return &Service{db: db, ttl: ttl, random: cryptorand.Reader, now: time.Now}
}

func (s *Service) Create(ctx context.Context) (Session, error) {
	now := s.now().UTC()
	if err := s.expireOldSessions(ctx, now); err != nil {
		return Session{}, err
	}

	var masterCount int
	if err := s.db.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM devices
		WHERE device_type = 'android_master' AND revoked_at IS NULL
	`).Scan(&masterCount); err != nil {
		return Session{}, fmt.Errorf("count Android masters: %w", err)
	}
	if masterCount != 1 {
		return Session{}, ErrNotInitialized
	}

	for attempt := 0; attempt < maxCodeInsertTries; attempt++ {
		code, err := s.randomCode()
		if err != nil {
			return Session{}, err
		}
		qrSecret, err := s.randomToken()
		if err != nil {
			return Session{}, err
		}
		browserToken, err := s.randomToken()
		if err != nil {
			return Session{}, err
		}

		codeHash := sha256.Sum256([]byte(code))
		qrSecretHash := sha256.Sum256([]byte(qrSecret))
		browserTokenHash := sha256.Sum256([]byte(browserToken))
		session := Session{
			ID:           uuid.NewString(),
			Code:         code,
			QRSecret:     qrSecret,
			BrowserToken: browserToken,
			ExpiresAt:    now.Add(s.ttl),
		}

		_, err = s.db.ExecContext(ctx, `
			INSERT INTO pairing_sessions (
				id, code_hash, qr_secret_hash, browser_token_hash,
				created_at, expires_at
			) VALUES (?, ?, ?, ?, ?, ?)
		`, session.ID, codeHash[:], qrSecretHash[:], browserTokenHash[:],
			formatTime(now), formatTime(session.ExpiresAt))
		if err == nil {
			return session, nil
		}
		if !strings.Contains(err.Error(), "UNIQUE constraint failed: pairing_sessions.code_hash") {
			return Session{}, fmt.Errorf("create pairing session: %w", err)
		}
	}

	return Session{}, errors.New("could not generate a unique pairing code")
}

func (s *Service) Approve(ctx context.Context, credential Credential, masterDeviceID string, replaceExisting bool) error {
	now := s.now().UTC()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin pairing approval: %w", err)
	}
	defer tx.Rollback()

	session, err := s.findAndValidateSession(ctx, tx, credential, now)
	if err != nil {
		return err
	}

	var activeBrowsers int
	if err := tx.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM devices
		WHERE device_type = 'windows_browser' AND revoked_at IS NULL
	`).Scan(&activeBrowsers); err != nil {
		return fmt.Errorf("count active Windows browsers: %w", err)
	}
	if activeBrowsers > 0 && !replaceExisting {
		return ErrReplacementRequired
	}

	replacementAllowed := 0
	if replaceExisting {
		replacementAllowed = 1
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE pairing_sessions
		SET status = 'approved',
		    replacement_allowed = CASE
		        WHEN replacement_allowed = 1 OR ? = 1 THEN 1 ELSE 0
		    END,
		    approved_by_device_id = ?, approved_at = ?
		WHERE id = ? AND status IN ('pending', 'approved')
	`, replacementAllowed, masterDeviceID, formatTime(now), session.id); err != nil {
		return fmt.Errorf("approve pairing session: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit pairing approval: %w", err)
	}
	return nil
}

func (s *Service) Reject(ctx context.Context, credential Credential) error {
	now := s.now().UTC()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin pairing rejection: %w", err)
	}
	defer tx.Rollback()

	session, err := s.findAndValidateSession(ctx, tx, credential, now)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE pairing_sessions
		SET status = 'rejected', rejected_at = ?
		WHERE id = ? AND status IN ('pending', 'approved')
	`, formatTime(now), session.id); err != nil {
		return fmt.Errorf("reject pairing session: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit pairing rejection: %w", err)
	}
	return nil
}

func (s *Service) Poll(ctx context.Context, sessionID, browserToken string) (PollResult, error) {
	now := s.now().UTC()
	browserToken = strings.TrimSpace(browserToken)
	if strings.TrimSpace(sessionID) == "" || browserToken == "" {
		return PollResult{}, ErrInvalidPairing
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return PollResult{}, fmt.Errorf("begin pairing poll: %w", err)
	}
	defer tx.Rollback()

	var status, expiresAtRaw string
	var storedBrowserHash []byte
	var replacementAllowed int
	var browserDeviceID sql.NullString
	err = tx.QueryRowContext(ctx, `
		SELECT status, expires_at, browser_token_hash,
		       replacement_allowed, browser_device_id
		FROM pairing_sessions WHERE id = ?
	`, sessionID).Scan(
		&status, &expiresAtRaw, &storedBrowserHash,
		&replacementAllowed, &browserDeviceID,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return PollResult{}, ErrInvalidPairing
	}
	if err != nil {
		return PollResult{}, fmt.Errorf("query pairing poll: %w", err)
	}

	providedHash := sha256.Sum256([]byte(browserToken))
	if subtle.ConstantTimeCompare(providedHash[:], storedBrowserHash) != 1 {
		return PollResult{}, ErrInvalidPairing
	}

	expiresAt, err := time.Parse(time.RFC3339Nano, expiresAtRaw)
	if err != nil {
		return PollResult{}, fmt.Errorf("parse pairing expiry: %w", err)
	}
	if (status == StatusPending || status == StatusApproved) && !now.Before(expiresAt) {
		if _, err := tx.ExecContext(ctx, `
			UPDATE pairing_sessions SET status = 'expired'
			WHERE id = ? AND status IN ('pending', 'approved')
		`, sessionID); err != nil {
			return PollResult{}, fmt.Errorf("expire pairing session: %w", err)
		}
		if err := tx.Commit(); err != nil {
			return PollResult{}, fmt.Errorf("commit pairing expiry: %w", err)
		}
		return PollResult{Status: StatusExpired}, nil
	}

	switch status {
	case StatusPending, StatusRejected, StatusExpired:
		return PollResult{Status: status}, nil
	case StatusConsumed:
		if !browserDeviceID.Valid {
			return PollResult{}, ErrInvalidPairing
		}
		var active int
		if err := tx.QueryRowContext(ctx, `
			SELECT COUNT(*) FROM devices
			WHERE id = ? AND token_hash = ? AND revoked_at IS NULL
		`, browserDeviceID.String, providedHash[:]).Scan(&active); err != nil {
			return PollResult{}, fmt.Errorf("verify consumed browser device: %w", err)
		}
		if active != 1 {
			return PollResult{Status: StatusRejected}, nil
		}
		return PollResult{Status: StatusApproved, BrowserToken: browserToken}, nil
	case StatusApproved:
		return s.consumeApprovedSession(ctx, tx, sessionID, providedHash[:], browserToken, replacementAllowed, now)
	default:
		return PollResult{}, fmt.Errorf("unknown pairing status %q", status)
	}
}

type sessionRow struct {
	id             string
	status         string
	expiresAt      time.Time
	failedAttempts int
}

func (s *Service) findAndValidateSession(
	ctx context.Context,
	tx *sql.Tx,
	credential Credential,
	now time.Time,
) (sessionRow, error) {
	var row sessionRow
	var expiresAtRaw string
	var storedSecretHash []byte

	if strings.TrimSpace(credential.SessionID) != "" {
		err := tx.QueryRowContext(ctx, `
			SELECT id, status, expires_at, failed_attempts, qr_secret_hash
			FROM pairing_sessions WHERE id = ?
		`, strings.TrimSpace(credential.SessionID)).Scan(
			&row.id, &row.status, &expiresAtRaw, &row.failedAttempts, &storedSecretHash,
		)
		if errors.Is(err, sql.ErrNoRows) {
			return sessionRow{}, ErrInvalidPairing
		}
		if err != nil {
			return sessionRow{}, fmt.Errorf("query pairing session: %w", err)
		}
		providedHash := sha256.Sum256([]byte(strings.TrimSpace(credential.QRSecret)))
		if subtle.ConstantTimeCompare(providedHash[:], storedSecretHash) != 1 {
			if err := s.recordFailedAttempt(ctx, tx, row); err != nil {
				return sessionRow{}, err
			}
			return sessionRow{}, ErrInvalidPairing
		}
	} else {
		code := strings.ReplaceAll(strings.TrimSpace(credential.Code), " ", "")
		if len(code) != 6 || strings.Trim(code, "0123456789") != "" {
			return sessionRow{}, ErrInvalidPairing
		}
		codeHash := sha256.Sum256([]byte(code))
		err := tx.QueryRowContext(ctx, `
			SELECT id, status, expires_at, failed_attempts
			FROM pairing_sessions
			WHERE code_hash = ? AND status IN ('pending', 'approved')
			ORDER BY created_at DESC LIMIT 1
		`, codeHash[:]).Scan(&row.id, &row.status, &expiresAtRaw, &row.failedAttempts)
		if errors.Is(err, sql.ErrNoRows) {
			if err := s.recordFailedManualAttempt(ctx, tx, now); err != nil {
				return sessionRow{}, err
			}
			return sessionRow{}, ErrInvalidPairing
		}
		if err != nil {
			return sessionRow{}, fmt.Errorf("query pairing code: %w", err)
		}
	}

	parsedExpiry, err := time.Parse(time.RFC3339Nano, expiresAtRaw)
	if err != nil {
		return sessionRow{}, fmt.Errorf("parse pairing expiry: %w", err)
	}
	row.expiresAt = parsedExpiry
	if !now.Before(row.expiresAt) {
		if _, err := tx.ExecContext(ctx, `
			UPDATE pairing_sessions SET status = 'expired'
			WHERE id = ? AND status IN ('pending', 'approved')
		`, row.id); err != nil {
			return sessionRow{}, fmt.Errorf("expire pairing credential: %w", err)
		}
		if err := tx.Commit(); err != nil {
			return sessionRow{}, fmt.Errorf("commit pairing expiry: %w", err)
		}
		return sessionRow{}, ErrPairingExpired
	}
	if row.status != StatusPending && row.status != StatusApproved {
		return sessionRow{}, ErrInvalidPairing
	}
	return row, nil
}

func (s *Service) recordFailedManualAttempt(ctx context.Context, tx *sql.Tx, now time.Time) error {
	if _, err := tx.ExecContext(ctx, `
		UPDATE pairing_sessions
		SET failed_attempts = CASE
		        WHEN expires_at > ? THEN MIN(failed_attempts + 1, ?)
		        ELSE failed_attempts
		    END,
		    status = CASE
		        WHEN expires_at <= ? OR failed_attempts + 1 >= ? THEN 'expired'
		        ELSE status
		    END
		WHERE status IN ('pending', 'approved')
	`, formatTime(now), maxFailedAttempts, formatTime(now), maxFailedAttempts); err != nil {
		return fmt.Errorf("record failed manual pairing attempt: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit failed manual pairing attempt: %w", err)
	}
	return nil
}

func (s *Service) recordFailedAttempt(ctx context.Context, tx *sql.Tx, row sessionRow) error {
	nextAttempts := row.failedAttempts + 1
	status := row.status
	if nextAttempts >= maxFailedAttempts {
		nextAttempts = maxFailedAttempts
		status = StatusExpired
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE pairing_sessions SET failed_attempts = ?, status = ? WHERE id = ?
	`, nextAttempts, status, row.id); err != nil {
		return fmt.Errorf("record failed pairing attempt: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit failed pairing attempt: %w", err)
	}
	return nil
}

func (s *Service) consumeApprovedSession(
	ctx context.Context,
	tx *sql.Tx,
	sessionID string,
	browserTokenHash []byte,
	browserToken string,
	replacementAllowed int,
	now time.Time,
) (PollResult, error) {
	var activeBrowsers int
	if err := tx.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM devices
		WHERE device_type = 'windows_browser' AND revoked_at IS NULL
	`).Scan(&activeBrowsers); err != nil {
		return PollResult{}, fmt.Errorf("count browsers during pairing consumption: %w", err)
	}
	if activeBrowsers > 0 && replacementAllowed != 1 {
		if _, err := tx.ExecContext(ctx, `
			UPDATE pairing_sessions
			SET status = 'rejected', rejected_at = ?
			WHERE id = ? AND status = 'approved'
		`, formatTime(now), sessionID); err != nil {
			return PollResult{}, fmt.Errorf("reject unsafe browser replacement: %w", err)
		}
		if err := tx.Commit(); err != nil {
			return PollResult{}, fmt.Errorf("commit unsafe browser replacement rejection: %w", err)
		}
		return PollResult{Status: StatusRejected}, nil
	}

	if activeBrowsers > 0 {
		if _, err := tx.ExecContext(ctx, `
			UPDATE devices
			SET revoked_at = ?
			WHERE device_type = 'windows_browser' AND revoked_at IS NULL
		`, formatTime(now)); err != nil {
			return PollResult{}, fmt.Errorf("revoke previous Windows browser: %w", err)
		}
	}

	browserDeviceID := uuid.NewString()
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO devices (id, device_type, token_hash)
		VALUES (?, 'windows_browser', ?)
	`, browserDeviceID, browserTokenHash); err != nil {
		return PollResult{}, fmt.Errorf("create Windows browser device: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
		UPDATE pairing_sessions
		SET status = 'consumed', browser_device_id = ?, consumed_at = ?
		WHERE id = ? AND status = 'approved'
	`, browserDeviceID, formatTime(now), sessionID); err != nil {
		return PollResult{}, fmt.Errorf("consume pairing session: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return PollResult{}, fmt.Errorf("commit pairing consumption: %w", err)
	}

	return PollResult{Status: StatusApproved, BrowserToken: browserToken}, nil
}

func (s *Service) expireOldSessions(ctx context.Context, now time.Time) error {
	if _, err := s.db.ExecContext(ctx, `
		UPDATE pairing_sessions SET status = 'expired'
		WHERE status IN ('pending', 'approved') AND expires_at <= ?
	`, formatTime(now)); err != nil {
		return fmt.Errorf("expire old pairing sessions: %w", err)
	}
	if _, err := s.db.ExecContext(ctx, `
		DELETE FROM pairing_sessions
		WHERE status IN ('rejected', 'expired', 'consumed') AND expires_at <= ?
	`, formatTime(now.Add(-time.Hour))); err != nil {
		return fmt.Errorf("delete old pairing sessions: %w", err)
	}
	return nil
}

func (s *Service) randomToken() (string, error) {
	raw := make([]byte, randomTokenBytes)
	if _, err := io.ReadFull(s.random, raw); err != nil {
		return "", fmt.Errorf("generate pairing secret: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func (s *Service) randomCode() (string, error) {
	value, err := cryptorand.Int(s.random, big.NewInt(maxCodeValue))
	if err != nil {
		return "", fmt.Errorf("generate pairing code: %w", err)
	}
	return fmt.Sprintf("%06d", value.Int64()), nil
}

func formatTime(value time.Time) string {
	return value.UTC().Format(timestampFormat)
}
