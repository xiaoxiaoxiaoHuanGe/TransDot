package realtime

import (
	"testing"
	"time"
)

func TestHubPublishesRevokesAndShutsDown(t *testing.T) {
	hub := NewHub()
	first := hub.Subscribe("browser-1")
	second := hub.Subscribe("android-1")
	if hub.ConnectionCount("browser-1") != 1 {
		t.Fatal("browser subscription was not registered")
	}

	hub.Publish("message.created", map[string]string{"id": "message-1"})
	for name, subscription := range map[string]*Subscription{"first": first, "second": second} {
		select {
		case event := <-subscription.Events():
			if event.Type != "message.created" || event.EventID == "" || event.Timestamp.IsZero() {
				t.Fatalf("%s event = %+v", name, event)
			}
		case <-time.After(time.Second):
			t.Fatalf("%s did not receive published event", name)
		}
	}

	hub.RevokeDevices([]string{"browser-1"})
	select {
	case event := <-first.Replaced():
		if event.Type != "device.replaced" {
			t.Fatalf("replacement event = %+v", event)
		}
	case <-time.After(time.Second):
		t.Fatal("revoked browser did not receive replacement event")
	}
	if hub.ConnectionCount("browser-1") != 0 {
		t.Fatal("revoked browser remained registered")
	}

	hub.Close()
	select {
	case <-second.Shutdown():
	case <-time.After(time.Second):
		t.Fatal("active connection did not receive shutdown")
	}
}

func TestPublishToOnlyReachesTargetDevice(t *testing.T) {
	hub := NewHub()
	target := hub.Subscribe("target")
	other := hub.Subscribe("other")

	if !hub.PublishTo("target", "lan.peer_online", map[string]string{"session_id": "session-1"}) {
		t.Fatal("target was not connected")
	}
	select {
	case event := <-target.Events():
		if event.Type != "lan.peer_online" {
			t.Fatalf("target event = %#v", event)
		}
	case <-time.After(time.Second):
		t.Fatal("target received nothing")
	}
	select {
	case event := <-other.Events():
		t.Fatalf("other received %#v", event)
	default:
	}
	if hub.PublishTo("missing", "lan.peer_online", nil) {
		t.Fatal("missing target reported connected")
	}
}
