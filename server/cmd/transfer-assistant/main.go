package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"transdot.local/transfer-assistant/server/internal/config"
	"transdot.local/transfer-assistant/server/internal/database"
	"transdot.local/transfer-assistant/server/internal/deviceauth"
	transferfiles "transdot.local/transfer-assistant/server/internal/files"
	"transdot.local/transfer-assistant/server/internal/httpserver"
	"transdot.local/transfer-assistant/server/internal/messages"
	"transdot.local/transfer-assistant/server/internal/pairing"
	"transdot.local/transfer-assistant/server/internal/realtime"
	"transdot.local/transfer-assistant/server/internal/setup"
	"transdot.local/transfer-assistant/server/internal/webui"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	cfg, err := config.Load()
	if err != nil {
		logger.Error("invalid configuration", "error", err)
		os.Exit(1)
	}

	db, err := database.Open(cfg.DataDir)
	if err != nil {
		logger.Error("database initialization failed", "error", err)
		os.Exit(1)
	}
	defer db.Close()
	setupService := setup.NewService(db, cfg.OwnerSetupToken)
	authService := deviceauth.NewService(db)
	hub := realtime.NewHub()
	pairingService := pairing.NewService(db, cfg.PairingTTL, hub.RevokeDevices)
	messageService := messages.NewService(db)
	fileService := transferfiles.NewService(db, transferfiles.Config{
		DataDir:          cfg.DataDir,
		MaxFileBytes:     cfg.MaxFileBytes,
		MaxBatchBytes:    cfg.MaxBatchBytes,
		MaxBatchItems:    cfg.MaxBatchItems,
		FilePoolMaxBytes: cfg.FilePoolMaxBytes,
		FileTTL:          cfg.FileTTL,
		FileMessageTTL:   cfg.FileMessageTTL,
		UploadSessionTTL: cfg.UploadSessionTTL,
	}, hub.Publish)

	webHandler, err := webui.NewHandler()
	if err != nil {
		logger.Error("web assets initialization failed", "error", err)
		os.Exit(1)
	}

	server := &http.Server{
		Addr:              cfg.ListenAddress(),
		Handler:           httpserver.NewWithFiles(db, setupService, authService, pairingService, messageService, fileService, hub, webHandler, logger),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	shutdownContext, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go fileService.RunCleanup(shutdownContext, 5*time.Minute, func(err error) {
		logger.Error("periodic cleanup failed", "error", err)
	})

	go func() {
		<-shutdownContext.Done()
		logger.Info("shutdown requested")
		hub.Close()

		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(ctx); err != nil {
			logger.Error("graceful shutdown failed", "error", err)
		}
	}()

	logger.Info("server starting", "address", cfg.ListenAddress(), "data_dir", cfg.DataDir)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Error("server stopped unexpectedly", "error", err)
		os.Exit(1)
	}
	logger.Info("server stopped")
}
