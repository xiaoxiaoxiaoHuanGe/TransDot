package realtime

import (
	"sync"
	"time"

	"github.com/google/uuid"
)

const eventBufferSize = 64

type Event struct {
	EventID   string    `json:"event_id"`
	Type      string    `json:"type"`
	Timestamp time.Time `json:"timestamp"`
	Data      any       `json:"data"`
}

type Subscription struct {
	deviceID string
	events   chan Event
	replaced chan Event
	shutdown chan struct{}
}

func (s *Subscription) Events() <-chan Event      { return s.events }
func (s *Subscription) Replaced() <-chan Event    { return s.replaced }
func (s *Subscription) Shutdown() <-chan struct{} { return s.shutdown }

type Hub struct {
	mu      sync.RWMutex
	clients map[string]map[*Subscription]struct{}
	closed  bool
}

func NewHub() *Hub {
	return &Hub{clients: make(map[string]map[*Subscription]struct{})}
}

func (h *Hub) Subscribe(deviceID string) *Subscription {
	subscription := &Subscription{
		deviceID: deviceID,
		events:   make(chan Event, eventBufferSize),
		replaced: make(chan Event, 1),
		shutdown: make(chan struct{}, 1),
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		subscription.shutdown <- struct{}{}
		return subscription
	}
	if h.clients[deviceID] == nil {
		h.clients[deviceID] = make(map[*Subscription]struct{})
	}
	h.clients[deviceID][subscription] = struct{}{}
	return subscription
}

func (h *Hub) Unsubscribe(subscription *Subscription) {
	if subscription == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	clients := h.clients[subscription.deviceID]
	delete(clients, subscription)
	if len(clients) == 0 {
		delete(h.clients, subscription.deviceID)
	}
}

func (h *Hub) ConnectionCount(deviceID string) int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients[deviceID])
}

func (h *Hub) Publish(eventType string, data any) {
	event := newEvent(eventType, data)
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, deviceClients := range h.clients {
		for client := range deviceClients {
			select {
			case client.events <- event:
			default:
				// REST is the source of truth. A slow client reconciles after reconnect.
			}
		}
	}
}

func (h *Hub) PublishTo(deviceID, eventType string, data any) bool {
	event := newEvent(eventType, data)
	h.mu.RLock()
	defer h.mu.RUnlock()
	deviceClients := h.clients[deviceID]
	for client := range deviceClients {
		select {
		case client.events <- event:
		default:
		}
	}
	return len(deviceClients) > 0
}

func (h *Hub) RevokeDevices(deviceIDs []string) {
	if len(deviceIDs) == 0 {
		return
	}
	event := newEvent("device.replaced", map[string]string{"reason": "windows_replaced"})
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, deviceID := range deviceIDs {
		clients := h.clients[deviceID]
		delete(h.clients, deviceID)
		for client := range clients {
			select {
			case client.replaced <- event:
			default:
			}
		}
	}
}

func (h *Hub) Close() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return
	}
	h.closed = true
	for _, deviceClients := range h.clients {
		for client := range deviceClients {
			select {
			case client.shutdown <- struct{}{}:
			default:
			}
		}
	}
	h.clients = make(map[string]map[*Subscription]struct{})
}

func newEvent(eventType string, data any) Event {
	return Event{
		EventID:   uuid.NewString(),
		Type:      eventType,
		Timestamp: time.Now().UTC(),
		Data:      data,
	}
}
