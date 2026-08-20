package config

import (
	"testing"
	"time"
)

func TestLoadDefaults(t *testing.T) {
	t.Setenv("PORT", "")
	t.Setenv("DATA_DIR", "")
	t.Setenv("OWNER_SETUP_TOKEN", "0123456789abcdef0123456789abcdef")
	t.Setenv("PAIRING_TTL_SECONDS", "")
	t.Setenv("MAX_FILE_BYTES", "")
	t.Setenv("MAX_BATCH_BYTES", "")
	t.Setenv("MAX_BATCH_ITEMS", "")
	t.Setenv("FILE_POOL_MAX_BYTES", "")
	t.Setenv("FILE_TTL_HOURS", "")
	t.Setenv("FILE_MESSAGE_TTL_DAYS", "")
	t.Setenv("UPLOAD_SESSION_TTL_MINUTES", "")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.Port != 5757 {
		t.Fatalf("Port = %d, want 5757", cfg.Port)
	}
	if cfg.DataDir != "/app/data" {
		t.Fatalf("DataDir = %q, want /app/data", cfg.DataDir)
	}
	if cfg.ListenAddress() != "0.0.0.0:5757" {
		t.Fatalf("ListenAddress() = %q", cfg.ListenAddress())
	}
	if cfg.PairingTTL.Seconds() != 120 {
		t.Fatalf("PairingTTL = %v, want 120s", cfg.PairingTTL)
	}
	if cfg.MaxFileBytes != 314572800 || cfg.MaxBatchBytes != 524288000 || cfg.MaxBatchItems != 20 {
		t.Fatalf("upload limits = %d/%d/%d", cfg.MaxFileBytes, cfg.MaxBatchBytes, cfg.MaxBatchItems)
	}
	if cfg.FilePoolMaxBytes != 1073741824 || cfg.FileTTL != 24*time.Hour || cfg.FileMessageTTL != 30*24*time.Hour || cfg.UploadSessionTTL != 30*time.Minute {
		t.Fatalf("file lifecycle defaults are incorrect: %+v", cfg)
	}
}

func TestLoadRequiresStrongOwnerSetupToken(t *testing.T) {
	t.Setenv("OWNER_SETUP_TOKEN", "too-short")

	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want OWNER_SETUP_TOKEN validation error")
	}
}

func TestLoadAllowsBootstrapWithoutOwnerSetupToken(t *testing.T) {
	t.Setenv("OWNER_SETUP_TOKEN", "")
	if _, err := Load(); err != nil {
		t.Fatalf("Load() error = %v", err)
	}
}

func TestLoadRejectsInvalidPort(t *testing.T) {
	t.Setenv("PORT", "70000")
	t.Setenv("OWNER_SETUP_TOKEN", "0123456789abcdef0123456789abcdef")

	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want invalid port error")
	}
}

func TestLoadRejectsInvalidPairingTTL(t *testing.T) {
	t.Setenv("OWNER_SETUP_TOKEN", "0123456789abcdef0123456789abcdef")
	t.Setenv("PAIRING_TTL_SECONDS", "0")

	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want invalid pairing TTL error")
	}
}

func TestLoadValidatesPublicURL(t *testing.T) {
	t.Setenv("OWNER_SETUP_TOKEN", "0123456789abcdef0123456789abcdef")
	t.Setenv("PUBLIC_URL", "https://transfer.example.com:8443")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.PublicURL != "https://transfer.example.com:8443" {
		t.Fatalf("PublicURL = %q", cfg.PublicURL)
	}

	t.Setenv("PUBLIC_URL", "https://transfer.example.com/path")
	if _, err := Load(); err == nil {
		t.Fatal("Load() accepted PUBLIC_URL with a path")
	}
}
