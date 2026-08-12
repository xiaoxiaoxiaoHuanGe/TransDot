package setup

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

	"github.com/google/uuid"
)

const masterTokenBytes = 32

var (
	ErrAlreadyInitialized = errors.New("server is already initialized")
	ErrInvalidSetupToken  = errors.New("invalid owner setup token")
)

type ClaimResult struct {
	DeviceID    string `json:"device_id"`
	MasterToken string `json:"master_token"`
}

type Service struct {
	db                    *sql.DB
	ownerSetupTokenDigest [sha256.Size]byte
	random                io.Reader
}

func NewService(db *sql.DB, ownerSetupToken string) *Service {
	return &Service{
		db:                    db,
		ownerSetupTokenDigest: sha256.Sum256([]byte(ownerSetupToken)),
		random:                rand.Reader,
	}
}

func (s *Service) Status(ctx context.Context) (bool, error) {
	var initialized int
	if err := s.db.QueryRowContext(ctx, "SELECT initialized FROM app_state WHERE id = 1").Scan(&initialized); err != nil {
		return false, fmt.Errorf("query app initialization state: %w", err)
	}
	return initialized == 1, nil
}

func (s *Service) Claim(ctx context.Context, providedSetupToken string) (ClaimResult, error) {
	providedDigest := sha256.Sum256([]byte(providedSetupToken))
	if subtle.ConstantTimeCompare(providedDigest[:], s.ownerSetupTokenDigest[:]) != 1 {
		return ClaimResult{}, ErrInvalidSetupToken
	}

	rawMasterToken := make([]byte, masterTokenBytes)
	if _, err := io.ReadFull(s.random, rawMasterToken); err != nil {
		return ClaimResult{}, fmt.Errorf("generate master token: %w", err)
	}
	masterToken := base64.RawURLEncoding.EncodeToString(rawMasterToken)
	masterTokenHash := sha256.Sum256([]byte(masterToken))
	deviceID := uuid.NewString()

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return ClaimResult{}, fmt.Errorf("begin setup transaction: %w", err)
	}
	defer tx.Rollback()

	result, err := tx.ExecContext(ctx, `
		UPDATE app_state
		SET initialized = 1,
		    updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
		WHERE id = 1 AND initialized = 0
	`)
	if err != nil {
		return ClaimResult{}, fmt.Errorf("claim app state: %w", err)
	}
	rowsAffected, err := result.RowsAffected()
	if err != nil {
		return ClaimResult{}, fmt.Errorf("read claim result: %w", err)
	}
	if rowsAffected != 1 {
		return ClaimResult{}, ErrAlreadyInitialized
	}

	if _, err := tx.ExecContext(ctx, `
		INSERT INTO devices (id, device_type, token_hash)
		VALUES (?, 'android_master', ?)
	`, deviceID, masterTokenHash[:]); err != nil {
		return ClaimResult{}, fmt.Errorf("create android master device: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return ClaimResult{}, fmt.Errorf("commit setup transaction: %w", err)
	}

	return ClaimResult{DeviceID: deviceID, MasterToken: masterToken}, nil
}
