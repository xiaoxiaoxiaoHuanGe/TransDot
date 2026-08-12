package config

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	defaultPort                          = 5757
	defaultDataDir                       = "/app/data"
	minSetupTokenLength                  = 32
	defaultPairingTTL                    = 120
	defaultMaxFileBytes            int64 = 314572800
	defaultMaxBatchBytes           int64 = 524288000
	defaultMaxBatchItems                 = 20
	defaultFilePoolMaxBytes        int64 = 1073741824
	defaultFileTTLHours                  = 24
	defaultFileMessageTTLDays            = 30
	defaultUploadSessionTTLMinutes       = 30
)

type Config struct {
	Port             int
	DataDir          string
	OwnerSetupToken  string
	PairingTTL       time.Duration
	MaxFileBytes     int64
	MaxBatchBytes    int64
	MaxBatchItems    int
	FilePoolMaxBytes int64
	FileTTL          time.Duration
	FileMessageTTL   time.Duration
	UploadSessionTTL time.Duration
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

	pairingTTLSeconds, err := positiveIntFromEnv("PAIRING_TTL_SECONDS", defaultPairingTTL)
	if err != nil {
		return Config{}, err
	}
	cfg.PairingTTL = time.Duration(pairingTTLSeconds) * time.Second

	if cfg.MaxFileBytes, err = positiveInt64FromEnv("MAX_FILE_BYTES", defaultMaxFileBytes); err != nil {
		return Config{}, err
	}
	if cfg.MaxBatchBytes, err = positiveInt64FromEnv("MAX_BATCH_BYTES", defaultMaxBatchBytes); err != nil {
		return Config{}, err
	}
	if cfg.MaxBatchItems, err = positiveIntFromEnv("MAX_BATCH_ITEMS", defaultMaxBatchItems); err != nil {
		return Config{}, err
	}
	if cfg.FilePoolMaxBytes, err = positiveInt64FromEnv("FILE_POOL_MAX_BYTES", defaultFilePoolMaxBytes); err != nil {
		return Config{}, err
	}
	fileTTLHours, err := positiveIntFromEnv("FILE_TTL_HOURS", defaultFileTTLHours)
	if err != nil {
		return Config{}, err
	}
	cfg.FileTTL = time.Duration(fileTTLHours) * time.Hour
	fileMessageTTLDays, err := positiveIntFromEnv("FILE_MESSAGE_TTL_DAYS", defaultFileMessageTTLDays)
	if err != nil {
		return Config{}, err
	}
	cfg.FileMessageTTL = time.Duration(fileMessageTTLDays) * 24 * time.Hour
	uploadTTLMinutes, err := positiveIntFromEnv("UPLOAD_SESSION_TTL_MINUTES", defaultUploadSessionTTLMinutes)
	if err != nil {
		return Config{}, err
	}
	cfg.UploadSessionTTL = time.Duration(uploadTTLMinutes) * time.Minute

	if cfg.MaxBatchBytes < cfg.MaxFileBytes {
		return Config{}, fmt.Errorf("MAX_BATCH_BYTES must be greater than or equal to MAX_FILE_BYTES")
	}
	if cfg.FilePoolMaxBytes < cfg.MaxFileBytes {
		return Config{}, fmt.Errorf("FILE_POOL_MAX_BYTES must be greater than or equal to MAX_FILE_BYTES")
	}
	if cfg.MaxBatchItems > defaultMaxBatchItems {
		return Config{}, fmt.Errorf("MAX_BATCH_ITEMS cannot exceed %d", defaultMaxBatchItems)
	}
	if cfg.FileMessageTTL < cfg.FileTTL {
		return Config{}, fmt.Errorf("FILE_MESSAGE_TTL_DAYS must not expire before FILE_TTL_HOURS")
	}

	return cfg, nil
}

func positiveIntFromEnv(name string, defaultValue int) (int, error) {
	rawValue := strings.TrimSpace(os.Getenv(name))
	if rawValue == "" {
		return defaultValue, nil
	}
	value, err := strconv.Atoi(rawValue)
	if err != nil || value <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer", name)
	}
	return value, nil
}

func positiveInt64FromEnv(name string, defaultValue int64) (int64, error) {
	rawValue := strings.TrimSpace(os.Getenv(name))
	if rawValue == "" {
		return defaultValue, nil
	}
	value, err := strconv.ParseInt(rawValue, 10, 64)
	if err != nil || value <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer", name)
	}
	return value, nil
}

func (c Config) ListenAddress() string {
	return net.JoinHostPort("0.0.0.0", strconv.Itoa(c.Port))
}
