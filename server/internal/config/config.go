package config

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

const (
	defaultPort         = 5757
	defaultDataDir      = "/app/data"
	minSetupTokenLength = 32
)

type Config struct {
	Port            int
	DataDir         string
	OwnerSetupToken string
}

func Load() (Config, error) {
	cfg := Config{
		Port:    defaultPort,
		DataDir: defaultDataDir,
	}

	if value := strings.TrimSpace(os.Getenv("PORT")); value != "" {
		port, err := strconv.Atoi(value)
		if err != nil || port < 1 || port > 65535 {
			return Config{}, fmt.Errorf("PORT must be a number between 1 and 65535")
		}
		cfg.Port = port
	}

	if value := strings.TrimSpace(os.Getenv("DATA_DIR")); value != "" {
		absolute, err := filepath.Abs(value)
		if err != nil {
			return Config{}, fmt.Errorf("resolve DATA_DIR: %w", err)
		}
		cfg.DataDir = filepath.Clean(absolute)
	}

	cfg.OwnerSetupToken = strings.TrimSpace(os.Getenv("OWNER_SETUP_TOKEN"))
	if len(cfg.OwnerSetupToken) < minSetupTokenLength {
		return Config{}, fmt.Errorf("OWNER_SETUP_TOKEN must contain at least %d characters", minSetupTokenLength)
	}

	return cfg, nil
}

func (c Config) ListenAddress() string {
	return net.JoinHostPort("0.0.0.0", strconv.Itoa(c.Port))
}
