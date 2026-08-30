package devices

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/database"
)

func TestNormalizeDisplayName(t *testing.T) {
	tests := []struct {
		name string
		want string
		err  error
	}{
		{name: "  Chrome · Windows  ", want: "Chrome · Windows"},
		{name: "书房电脑", want: "书房电脑"},
		{name: "   ", err: ErrInvalidDisplayName},
		{name: "line\nbreak", err: ErrInvalidDisplayName},
		{name: "tab\tname", err: ErrInvalidDisplayName},
		{name: string(make([]rune, 65)), err: ErrInvalidDisplayName},
	}
	for _, test := range tests {
		got, err := NormalizeDisplayName(test.name)
		if !errors.Is(err, test.err) || got != test.want {
			t.Errorf("NormalizeDisplayName(%q) = %q, %v; want %q, %v", test.name, got, err, test.want, test.err)
		}
	}
}

func TestListRenameAndRevokeBrowser(t *testing.T) {
	db := deviceTestDatabase(t)
	now := time.Date(2026, 8, 30, 12, 0, 0, 0, time.UTC)
	insertDevice(t, db, "master-1", "android_master", "Master", now.Add(-time.Hour), nil)
	seenOlder := now.Add(-20 * time.Minute)
	seenNewer := now.Add(-5 * time.Minute)
	insertDevice(t, db, "browser-old", "windows_browser", "Office", now.Add(-2*time.Hour), &seenOlder)
	insertDevice(t, db, "browser-new", "windows_browser", "Home", now.Add(-time.Hour), &seenNewer)

	var revoked []string
	var events []string
	service := NewService(db, func(ids []string) { revoked = append(revoked, ids...) }, func(eventType string, _ any) {
		events = append(events, eventType)
	})
	devices, err := service.ListActiveBrowsers(context.Background())
	if err != nil || len(devices) != 2 || devices[0].ID != "browser-new" {
		t.Fatalf("ListActiveBrowsers() = %+v, %v", devices, err)
	}

	updated, err := service.RenameBrowser(context.Background(), "browser-old", "  书房电脑  ")
	if err != nil || updated.DisplayName != "书房电脑" {
		t.Fatalf("RenameBrowser() = %+v, %v", updated, err)
	}
	if _, err := service.RenameBrowser(context.Background(), "master-1", "Nope"); !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("RenameBrowser(master) error = %v", err)
	}

	if err := service.RevokeBrowser(context.Background(), "browser-old"); err != nil {
		t.Fatalf("RevokeBrowser() error = %v", err)
	}
	if err := service.RevokeBrowser(context.Background(), "browser-old"); err != nil {
		t.Fatalf("idempotent RevokeBrowser() error = %v", err)
	}
	if len(revoked) != 1 || revoked[0] != "browser-old" {
		t.Fatalf("revoked callbacks = %v", revoked)
	}
	if len(events) != 2 || events[0] != "device.updated" || events[1] != "device.revoked" {
		t.Fatalf("events = %v", events)
	}
}

func TestRenameSelfOnlyAllowsActiveBrowser(t *testing.T) {
	db := deviceTestDatabase(t)
	now := time.Now().UTC()
	insertDevice(t, db, "master-1", "android_master", "Master", now, nil)
	insertDevice(t, db, "browser-1", "windows_browser", "Browser", now, nil)
	service := NewService(db, nil, nil)
	if _, err := service.RenameSelf(context.Background(), "browser-1", "Laptop"); err != nil {
		t.Fatalf("RenameSelf(browser) error = %v", err)
	}
	if _, err := service.RenameSelf(context.Background(), "master-1", "Laptop"); !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("RenameSelf(master) error = %v", err)
	}
}

func deviceTestDatabase(t *testing.T) *sql.DB {
	t.Helper()
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func insertDevice(t *testing.T, db *sql.DB, id, deviceType, displayName string, createdAt time.Time, lastSeen *time.Time) {
	t.Helper()
	hash := sha256.Sum256([]byte(id))
	var seen any
	if lastSeen != nil {
		seen = lastSeen.Format(time.RFC3339Nano)
	}
	_, err := db.Exec(`INSERT INTO devices (id, device_type, token_hash, display_name, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)`,
		id, deviceType, hash[:], displayName, createdAt.Format(time.RFC3339Nano), seen)
	if err != nil {
		t.Fatalf("insert device %s: %v", id, err)
	}
}
