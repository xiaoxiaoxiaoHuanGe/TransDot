package messages

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"testing"
	"time"

	"transdot.local/transfer-assistant/server/internal/database"
)

func TestTextTimelineUsesStableCursor(t *testing.T) {
	db := testDatabase(t)
	service := NewService(db)
	setIncrementingClock(service)
	ctx := context.Background()

	for index := 0; index < 55; index++ {
		if _, err := service.CreateText(ctx, "android-1", fmt.Sprintf("message %02d", index)); err != nil {
			t.Fatalf("CreateText(%d) error = %v", index, err)
		}
	}

	first, err := service.List(ctx, "", 50)
	if err != nil {
		t.Fatalf("List() error = %v", err)
	}
	if len(first.Messages) != 50 || first.NextBefore == "" {
		t.Fatalf("first page = %d messages, cursor %q; want 50 and cursor", len(first.Messages), first.NextBefore)
	}
	if got := *first.Messages[0].TextContent; got != "message 05" {
		t.Fatalf("first page oldest = %q, want message 05", got)
	}

	second, err := service.List(ctx, first.NextBefore, 50)
	if err != nil {
		t.Fatalf("List(second) error = %v", err)
	}
	if len(second.Messages) != 5 || second.NextBefore != "" {
		t.Fatalf("second page = %d messages, cursor %q; want 5 and no cursor", len(second.Messages), second.NextBefore)
	}
	if got := *second.Messages[0].TextContent; got != "message 00" {
		t.Fatalf("second page oldest = %q, want message 00", got)
	}
}

func TestSearchDeleteAndContext(t *testing.T) {
	db := testDatabase(t)
	service := NewService(db)
	setIncrementingClock(service)
	ctx := context.Background()
	created := make([]Message, 0, 45)

	for index := 0; index < 45; index++ {
		content := fmt.Sprintf("ordinary note %02d", index)
		if index == 22 {
			content = "important project milestone"
		}
		message, err := service.CreateText(ctx, "android-1", content)
		if err != nil {
			t.Fatalf("CreateText(%d) error = %v", index, err)
		}
		created = append(created, message)
	}

	results, err := service.Search(ctx, "important project")
	if err != nil || len(results) != 1 || results[0].ID != created[22].ID {
		t.Fatalf("Search() = %+v, %v; want target message", results, err)
	}
	messageContext, err := service.Context(ctx, created[22].ID)
	if err != nil {
		t.Fatalf("Context() error = %v", err)
	}
	if len(messageContext.Messages) != 41 || messageContext.Messages[20].ID != created[22].ID {
		t.Fatalf("context size/target = %d/%s; want 41/target at index 20", len(messageContext.Messages), messageContext.Messages[20].ID)
	}

	if err := service.Delete(ctx, created[22].ID); err != nil {
		t.Fatalf("Delete() error = %v", err)
	}
	results, err = service.Search(ctx, "important")
	if err != nil || len(results) != 0 {
		t.Fatalf("Search() after delete = %+v, %v; want no ghost result", results, err)
	}
	if _, err := service.Context(ctx, created[22].ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Context(deleted) error = %v, want not found", err)
	}
}

func TestTextValidation(t *testing.T) {
	service := NewService(testDatabase(t))
	ctx := context.Background()

	if _, err := service.CreateText(ctx, "android-1", "   \n"); !errors.Is(err, ErrEmptyText) {
		t.Fatalf("empty CreateText() error = %v", err)
	}
	tooLarge := make([]byte, MaximumTextLen+1)
	for index := range tooLarge {
		tooLarge[index] = 'a'
	}
	if _, err := service.CreateText(ctx, "android-1", string(tooLarge)); !errors.Is(err, ErrTextTooLarge) {
		t.Fatalf("large CreateText() error = %v", err)
	}
	if _, err := service.List(ctx, "not-a-cursor", 50); !errors.Is(err, ErrInvalidCursor) {
		t.Fatalf("invalid List() error = %v", err)
	}
	if _, err := service.Search(ctx, `*** ""`); !errors.Is(err, ErrInvalidSearch) {
		t.Fatalf("punctuation-only Search() error = %v", err)
	}
}

func testDatabase(t *testing.T) *sql.DB {
	t.Helper()
	db, err := database.Open(t.TempDir())
	if err != nil {
		t.Fatalf("database.Open() error = %v", err)
	}
	t.Cleanup(func() { db.Close() })
	tokenHash := sha256.Sum256([]byte("android-token"))
	if _, err := db.Exec(`
		INSERT INTO devices (id, device_type, token_hash)
		VALUES ('android-1', 'android_master', ?)
	`, tokenHash[:]); err != nil {
		t.Fatalf("insert test device: %v", err)
	}
	return db
}

func setIncrementingClock(service *Service) {
	now := time.Date(2026, 8, 12, 12, 0, 0, 0, time.UTC)
	service.now = func() time.Time {
		result := now
		now = now.Add(time.Millisecond)
		return result
	}
}
