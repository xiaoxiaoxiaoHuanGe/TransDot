package config

import "testing"

func TestLoadDefaults(t *testing.T) {
	t.Setenv("PORT", "")
	t.Setenv("DATA_DIR", "")
	t.Setenv("OWNER_SETUP_TOKEN", "0123456789abcdef0123456789abcdef")
	t.Setenv("PAIRING_TTL_SECONDS", "")

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
}

func TestLoadRequiresStrongOwnerSetupToken(t *testing.T) {
	t.Setenv("OWNER_SETUP_TOKEN", "too-short")

	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want OWNER_SETUP_TOKEN validation error")
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
