package instance

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"

	"github.com/google/uuid"
)

type Identity struct {
	ID          string `json:"instance_id"`
	Fingerprint string `json:"instance_fingerprint"`
}

type Service struct{ db *sql.DB }

func NewService(db *sql.DB) *Service { return &Service{db: db} }

func (s *Service) Get(ctx context.Context) (Identity, error) {
	var identity Identity
	err := s.db.QueryRowContext(ctx, `SELECT instance_id, instance_fingerprint FROM server_instance WHERE id = 1`).Scan(&identity.ID, &identity.Fingerprint)
	if err == nil {
		return identity, nil
	}
	if err != sql.ErrNoRows {
		return Identity{}, fmt.Errorf("read server instance: %w", err)
	}

	id := uuid.NewString()
	sum := sha256.Sum256([]byte(id))
	fingerprint := hex.EncodeToString(sum[:4])[:4] + "-" + hex.EncodeToString(sum[:4])[4:8]
	if _, err := s.db.ExecContext(ctx, `INSERT INTO server_instance(id, instance_id, instance_fingerprint) VALUES (1, ?, ?)`, id, fingerprint); err != nil {
		return Identity{}, fmt.Errorf("create server instance: %w", err)
	}
	return Identity{ID: id, Fingerprint: fingerprint}, nil
}
