package httpserver

import (
	"context"
	"log/slog"
	"net/http"

	serverinstance "transdot.local/transfer-assistant/server/internal/instance"
)

type instanceService interface {
	Get(context.Context) (serverinstance.Identity, error)
}

func instanceInfo(instances instanceService, setup setupService, publicURL string, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		identity, err := instances.Get(r.Context())
		if err != nil {
			logger.Error("read server instance", "error", err)
			writeError(w, 500, "INTERNAL_ERROR", "Internal server error.")
			return
		}
		initialized, err := setup.Status(r.Context())
		if err != nil {
			logger.Error("read setup status", "error", err)
			writeError(w, 500, "INTERNAL_ERROR", "Internal server error.")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"instance_id": identity.ID, "instance_fingerprint": identity.Fingerprint,
			"initialized": initialized, "public_url": publicURL,
		})
	}
}
