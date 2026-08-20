package httpserver

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"transdot.local/transfer-assistant/server/internal/deviceauth"
	"transdot.local/transfer-assistant/server/internal/lantransfer"
	"transdot.local/transfer-assistant/server/internal/realtime"
)

const websocketReadLimit = 64 * 1024

func websocketEndpoint(
	authService deviceAuthenticator,
	hub *realtime.Hub,
	lanBroker *lantransfer.Broker,
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
		if lanBroker != nil {
			defer publishLANDeliveries(hub, lanBroker.Leave(device.ID))
		}
		readDone := make(chan error, 1)
		go readWebsocket(connection, device, hub, lanBroker, readDone)
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

func readWebsocket(connection *websocket.Conn, device deviceauth.Device, hub *realtime.Hub, broker *lantransfer.Broker, done chan<- error) {
	for {
		messageType, contents, err := connection.Read(context.Background())
		if err != nil {
			done <- err
			return
		}
		if broker == nil || messageType != websocket.MessageText {
			continue
		}
		var signal lantransfer.ClientSignal
		decoder := json.NewDecoder(bytes.NewReader(contents))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&signal); err != nil {
			publishLANError(hub, device.ID, "LAN_SIGNAL_INVALID")
			continue
		}
		lanDevice := lantransfer.Device{ID: device.ID, Type: device.Type}
		if signal.Type == lantransfer.SignalReady {
			publishLANDeliveries(hub, broker.Ready(lanDevice, time.Now().UTC()))
			continue
		}
		deliveries, err := broker.Handle(lanDevice, signal, time.Now().UTC())
		if err != nil {
			publishLANError(hub, device.ID, lanErrorCode(err))
			continue
		}
		publishLANDeliveries(hub, deliveries)
	}
}

func publishLANDeliveries(hub *realtime.Hub, deliveries []lantransfer.Delivery) {
	for _, delivery := range deliveries {
		hub.PublishTo(delivery.DeviceID, delivery.Signal.Type, map[string]any{
			"session_id": delivery.Signal.SessionID,
			"data":       delivery.Signal.Data,
		})
	}
}

func publishLANError(hub *realtime.Hub, deviceID, code string) {
	hub.PublishTo(deviceID, "lan.error", map[string]any{"code": code})
}

func lanErrorCode(err error) string {
	switch {
	case errors.Is(err, lantransfer.ErrNonHostCandidate):
		return "LAN_NON_HOST_CANDIDATE"
	case errors.Is(err, lantransfer.ErrSignalForbidden):
		return "LAN_SIGNAL_FORBIDDEN"
	case errors.Is(err, lantransfer.ErrSessionBusy):
		return "LAN_SESSION_BUSY"
	case errors.Is(err, lantransfer.ErrSessionInvalid):
		return "LAN_SESSION_INVALID"
	default:
		return "LAN_SIGNAL_INVALID"
	}
}

func writeWebsocketEvent(connection *websocket.Conn, event realtime.Event) error {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return wsjson.Write(ctx, connection, event)
}
