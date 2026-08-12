package config

import "testing"

func TestLoadDefaults(t *testing.T) {
	t.Setenv("PORT", "")
	t.Setenv("DATA_DIR", "")

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
}

func TestLoadRejectsInvalidPort(t *testing.T) {
	t.Setenv("PORT", "70000")

	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want invalid port error")
	}
}
