package bootstrap

import (
	"context"
	"errors"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/database"
)

func TestBootstrapClaimIsSingleUseAndCreatesMaster(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	service := NewService(db, "instance-1", 2*time.Minute)

	session, err := service.Create(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	claimed, err := service.Claim(context.Background(), session.ID, session.Secret)
	if err != nil {
		t.Fatal(err)
	}
	if claimed.MasterToken == "" || claimed.DeviceID == "" {
		t.Fatalf("invalid claim: %#v", claimed)
	}
	if _, err := service.Claim(context.Background(), session.ID, session.Secret); !errors.Is(err, ErrConsumed) && !errors.Is(err, ErrAlreadyInitialized) {
		t.Fatalf("second claim error = %v", err)
	}

	poll, err := service.Poll(context.Background(), session.ID, session.BrowserToken)
	if err != nil {
		t.Fatal(err)
	}
	if poll.Status != StatusApproved || poll.BrowserToken == "" {
		t.Fatalf("poll = %#v", poll)
	}
}

func TestBootstrapRejectsWrongSecret(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	service := NewService(db, "instance-1", 2*time.Minute)
	session, err := service.Create(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.Claim(context.Background(), session.ID, "wrong"); !errors.Is(err, ErrInvalid) {
		t.Fatalf("Claim error = %v", err)
	}
}
