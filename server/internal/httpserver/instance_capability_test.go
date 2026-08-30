package httpserver

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	serverinstance "transdot.local/transfer-assistant/server/internal/instance"
)

type capabilityInstanceService struct{}

func (capabilityInstanceService) Get(context.Context) (serverinstance.Identity, error) {
	return serverinstance.Identity{ID: "instance-1", Fingerprint: "ABCD-1234"}, nil
}

func TestInstanceInfoAdvertisesMultiBrowserCapability(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	request := httptest.NewRequest(http.MethodGet, "/api/v1/instance/info", nil)
	response := httptest.NewRecorder()
	instanceInfo(capabilityInstanceService{}, fakeSetupService{initialized: true}, "https://example.test", logger)(response, request)
	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"capabilities":["multi_browser_v1"]`) {
		t.Fatalf("response = %d/%s", response.Code, response.Body.String())
	}
}
