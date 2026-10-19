package messages

import (
	"context"
	"crypto/sha256"
	"errors"
	"testing"
)

func TestHistoryBoundaryApprovalAndRevocation(t *testing.T) {
	db := testDatabase(t)
	s := NewService(db)
	ctx := context.Background()
	old, err := s.CreateText(ctx, "android-1", "secret archive")
	if err != nil {
		t.Fatal(err)
	}
	hash := sha256.Sum256([]byte("browser-token"))
	if _, err := db.Exec(`INSERT INTO devices(id,device_type,token_hash) VALUES('new-browser','windows_browser',?)`, hash[:]); err != nil {
		t.Fatal(err)
	}
	viewer := WithViewer(ctx, "new-browser")
	fresh, _ := s.CreateText(ctx, "android-1", "fresh archive")
	page, err := s.List(viewer, "", 50)
	if err != nil || len(page.Messages) != 1 || page.Messages[0].ID != fresh.ID {
		t.Fatalf("restricted page: %+v %v", page, err)
	}
	results, err := s.Search(viewer, "archive")
	if err != nil || len(results) != 1 {
		t.Fatalf("restricted search: %+v %v", results, err)
	}
	if _, err := s.Context(viewer, old.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("context leaked: %v", err)
	}
	if err := s.Delete(viewer, old.ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("delete allowed: %v", err)
	}
	if _, err := s.Edit(viewer, old.ID, "changed", 0); !errors.Is(err, ErrNotFound) {
		t.Fatalf("edit allowed: %v", err)
	}
	if _, err := s.RequestHistory(viewer, "new-browser"); err != nil {
		t.Fatal(err)
	}
	if err := s.DecideHistory(ctx, "new-browser", "approve"); err != nil {
		t.Fatal(err)
	}
	page, err = s.List(viewer, "", 50)
	if err != nil || len(page.Messages) != 2 {
		t.Fatalf("approved page: %+v %v", page, err)
	}
	if err := s.DecideHistory(ctx, "new-browser", "revoke"); err != nil {
		t.Fatal(err)
	}
	page, err = s.List(viewer, "", 50)
	if err != nil || len(page.Messages) != 1 {
		t.Fatalf("revoked page: %+v %v", page, err)
	}
}

func TestEditTextUpdatesSearchAndRejectsStaleRevision(t *testing.T) {
	s := NewService(testDatabase(t))
	ctx := context.Background()
	original, err := s.CreateText(ctx, "android-1", "obsoleteword")
	if err != nil {
		t.Fatal(err)
	}
	edited, err := s.Edit(ctx, original.ID, "replacementword", 0)
	if err != nil || edited.Revision != 1 || edited.EditedAt == nil || !edited.CreatedAt.Equal(original.CreatedAt) {
		t.Fatalf("edited: %+v %v", edited, err)
	}
	if _, err := s.Edit(ctx, original.ID, "overwrite", 0); !errors.Is(err, ErrConflict) {
		t.Fatalf("stale edit: %v", err)
	}
	old, _ := s.Search(ctx, "obsoleteword")
	fresh, _ := s.Search(ctx, "replacementword")
	if len(old) != 0 || len(fresh) != 1 {
		t.Fatalf("search index: old=%d fresh=%d", len(old), len(fresh))
	}
	if _, err := s.Edit(ctx, original.ID, "  ", 1); !errors.Is(err, ErrEmptyText) {
		t.Fatalf("empty edit: %v", err)
	}
}
