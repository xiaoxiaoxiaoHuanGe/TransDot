package httpserver

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"transdot.local/transfer-assistant/server/internal/realtime"
)

const websocketReadLimit = 1024

func websocketEndpoint(
	authService deviceAuthenticator,
	hub *realtime.Hub,
	logger *slog.Logger,
) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		device, ok := authenticateDevice(w, r, authService, logger)
		if !ok {
			return
		}
		connection, err := websocket.Accept(w, r, &websocket.AcceptOptions{
			CompressionMode: websocket.CompressionDisabled,
		})
		if err != nil {
			logger.Warn("accept websocket", "error", err)
			return
		}
		defer connection.CloseNow()
		connection.SetReadLimit(websocketReadLimit)

		subscription := hub.Subscribe(device.ID)
		defer hub.Unsubscribe(subscription)
		readDone := make(chan error, 1)
		go readWebsocket(connection, readDone)
		pingTicker := time.NewTicker(30 * time.Second)
		defer pingTicker.Stop()

		for {
			select {
			case event := <-subscription.Events():
				if err := writeWebsocketEvent(connection, event); err != nil {
					return
				}
			case event := <-subscription.Replaced():
				_ = writeWebsocketEvent(connection, event)
				_ = connection.Close(websocket.StatusPolicyViolation, "device replaced")
				return
			case <-subscription.Shutdown():
				_ = connection.Close(websocket.StatusGoingAway, "server shutdown")
				return
			case <-pingTicker.C:
				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				err := connection.Ping(ctx)
				cancel()
				if err != nil {
					return
				}
			case <-readDone:
				return
			}
		}
	}
}

func readWebsocket(connection *websocket.Conn, done chan<- error) {
	for {
		_, _, err := connection.Read(context.Background())
		if err != nil {
			done <- err
			return
		}
	}
}

func writeWebsocketEvent(connection *websocket.Conn, event realtime.Event) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return wsjson.Write(ctx, connection, event)
}
