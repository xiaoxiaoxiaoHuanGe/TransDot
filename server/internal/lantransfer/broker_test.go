package lantransfer

import (
	"encoding/json"
	"errors"
	"sync"
	"testing"
	"time"
)

var testStart = time.Date(2026, 8, 20, 0, 0, 0, 0, time.UTC)

func TestBrokerCreatesSessionOnlyForMasterAndBrowser(t *testing.T) {
	broker := NewBroker("instance-1")
	if deliveries := broker.Ready(Device{ID: "android", Type: AndroidMaster}, testStart); len(deliveries) != 0 {
		t.Fatalf("first ready deliveries = %#v", deliveries)
	}
	deliveries := broker.Ready(Device{ID: "browser", Type: WindowsBrowser}, testStart.Add(time.Second))
	if len(deliveries) != 2 {
		t.Fatalf("peer deliveries = %#v", deliveries)
	}
	if deliveries[0].Signal.Type != SignalPeerOnline || deliveries[1].Signal.Type != SignalPeerOnline {
		t.Fatalf("unexpected signals = %#v", deliveries)
	}
	if deliveries[0].Signal.SessionID == "" || deliveries[0].Signal.SessionID != deliveries[1].Signal.SessionID {
		t.Fatalf("session IDs do not match: %#v", deliveries)
	}
}

func TestBrokerRoutesOfferOnlyFromBrowser(t *testing.T) {
	broker, sessionID := readyBroker(t)
	offer := json.RawMessage(`{"sdp":"offer-value"}`)
	deliveries, err := broker.Handle(Device{ID: "browser", Type: WindowsBrowser}, ClientSignal{
		Type: SignalOffer, SessionID: sessionID, Data: offer,
	}, testStart.Add(2*time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if len(deliveries) != 1 || deliveries[0].DeviceID != "android" || deliveries[0].Signal.Type != SignalOffer {
		t.Fatalf("deliveries = %#v", deliveries)
	}
	if string(deliveries[0].Signal.Data.(json.RawMessage)) != string(offer) {
		t.Fatalf("offer changed: %#v", deliveries[0].Signal.Data)
	}
	if _, err := broker.Handle(Device{ID: "android", Type: AndroidMaster}, ClientSignal{
		Type: SignalOffer, SessionID: sessionID, Data: offer,
	}, testStart.Add(3*time.Second)); !errors.Is(err, ErrSignalForbidden) {
		t.Fatalf("Android offer error = %v", err)
	}
}

func TestBrokerRejectsNonHostCandidate(t *testing.T) {
	broker, sessionID := readyBroker(t)
	_, err := broker.Handle(Device{ID: "android", Type: AndroidMaster}, ClientSignal{
		Type: SignalICE, SessionID: sessionID,
		Data: json.RawMessage(`{"candidate":"candidate:1 1 UDP 1 203.0.113.2 5000 typ srflx"}`),
	}, testStart.Add(2*time.Second))
	if !errors.Is(err, ErrNonHostCandidate) {
		t.Fatalf("error = %v", err)
	}

	deliveries, err := broker.Handle(Device{ID: "android", Type: AndroidMaster}, ClientSignal{
		Type: SignalICE, SessionID: sessionID,
		Data: json.RawMessage(`{"candidate":"candidate:2 1 UDP 1 host.local 5001 typ host"}`),
	}, testStart.Add(3*time.Second))
	if err != nil || len(deliveries) != 1 || deliveries[0].DeviceID != "browser" {
		t.Fatalf("host candidate result = %#v, %v", deliveries, err)
	}
}

func TestBrokerExpiresNegotiationAndNotifiesOnLeave(t *testing.T) {
	broker, sessionID := readyBroker(t)
	if _, err := broker.Handle(Device{ID: "browser", Type: WindowsBrowser}, ClientSignal{
		Type: SignalOffer, SessionID: sessionID, Data: json.RawMessage(`{"sdp":"late"}`),
	}, testStart.Add(2*time.Minute+time.Second)); !errors.Is(err, ErrSessionInvalid) {
		t.Fatalf("expired session error = %v", err)
	}

	broker, _ = readyBroker(t)
	deliveries := broker.Leave("android")
	if len(deliveries) != 1 || deliveries[0].DeviceID != "browser" || deliveries[0].Signal.Type != SignalPeerOffline {
		t.Fatalf("leave deliveries = %#v", deliveries)
	}
}

func TestBrokerReadyAndLeaveAreConcurrentSafe(t *testing.T) {
	broker := NewBroker("instance-1")
	var wait sync.WaitGroup
	for index := 0; index < 20; index++ {
		wait.Add(2)
		go func() {
			defer wait.Done()
			broker.Ready(Device{ID: "android", Type: AndroidMaster}, testStart)
		}()
		go func() {
			defer wait.Done()
			broker.Leave("android")
		}()
	}
	wait.Wait()
}

func readyBroker(t *testing.T) (*Broker, string) {
	t.Helper()
	broker := NewBroker("instance-1")
	broker.Ready(Device{ID: "android", Type: AndroidMaster}, testStart)
	deliveries := broker.Ready(Device{ID: "browser", Type: WindowsBrowser}, testStart.Add(time.Second))
	if len(deliveries) != 2 {
		t.Fatalf("ready deliveries = %#v", deliveries)
	}
	return broker, deliveries[0].Signal.SessionID
}
