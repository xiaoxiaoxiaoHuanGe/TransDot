package instance

import (
	"context"
	"testing"

	"transdot.local/transfer-assistant/server/internal/database"
)

func TestIdentityIsStableForDatabase(t *testing.T) {
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	service := NewService(db)
	first, err := service.Get(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	second, err := service.Get(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatalf("identity changed: %#v != %#v", first, second)
	}
	if first.ID == "" || len(first.Fingerprint) != 9 || first.Fingerprint[4] != '-' {
		t.Fatalf("invalid identity: %#v", first)
	}
}

func TestNewDatabaseGetsNewIdentity(t *testing.T) {
	read := func(dir string) Identity {
		db, err := database.Open(dir)
		if err != nil {
			t.Fatal(err)
		}
		defer db.Close()
		identity, err := NewService(db).Get(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		return identity
	}
	if first, second := read(t.TempDir()), read(t.TempDir()); first.ID == second.ID {
		t.Fatalf("new databases share instance ID %q", first.ID)
	}
}
