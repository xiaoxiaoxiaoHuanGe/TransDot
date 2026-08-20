package lantransfer

import (
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

const (
	AndroidMaster  = "android_master"
	WindowsBrowser = "windows_browser"

	SignalReady       = "lan.ready"
	SignalPeerOnline  = "lan.peer_online"
	SignalPeerOffline = "lan.peer_offline"
	SignalOffer       = "lan.offer"
	SignalAnswer      = "lan.answer"
	SignalICE         = "lan.ice"
	SignalConnected   = "lan.connected"
	SignalCancel      = "lan.cancel"
	SignalCancelled   = "lan.cancelled"
	SignalLeave       = "lan.leave"
)

var (
	ErrSessionBusy      = errors.New("LAN session is busy")
	ErrSessionInvalid   = errors.New("LAN session is invalid")
	ErrSignalForbidden  = errors.New("LAN signal is forbidden for this device")
	ErrNonHostCandidate = errors.New("only Host ICE candidates are allowed")
	ErrSignalInvalid    = errors.New("LAN signal is invalid")
)

type Device struct{ ID, Type string }

type ClientSignal struct {
	Type      string          `json:"type"`
	SessionID string          `json:"session_id,omitempty"`
	Timestamp time.Time       `json:"timestamp"`
	Data      json.RawMessage `json:"data,omitempty"`
}

type ServerSignal struct {
	Type      string    `json:"type"`
	SessionID string    `json:"session_id,omitempty"`
	Timestamp time.Time `json:"timestamp"`
	Data      any       `json:"data,omitempty"`
}

type Delivery struct {
	DeviceID string
	Signal   ServerSignal
}

type session struct {
	id, androidID, browserID string
	state                    string
	createdAt, connectedAt   time.Time
}

type Broker struct {
	mu         sync.Mutex
	instanceID string
	ready      map[string]Device
	active     *session
}

func NewBroker(instanceID string) *Broker {
	return &Broker{instanceID: instanceID, ready: make(map[string]Device)}
}

func (b *Broker) Ready(device Device, now time.Time) []Delivery {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.expire(now)
	if !validDevice(device) {
		return nil
	}
	b.ready[device.Type] = device
	android, androidOK := b.ready[AndroidMaster]
	browser, browserOK := b.ready[WindowsBrowser]
	if !androidOK || !browserOK {
		return nil
	}
	if b.active != nil {
		if b.active.androidID == android.ID && b.active.browserID == browser.ID {
			return nil
		}
		return nil
	}
	b.active = &session{id: uuid.NewString(), androidID: android.ID, browserID: browser.ID, state: "negotiating", createdAt: now}
	signal := ServerSignal{Type: SignalPeerOnline, SessionID: b.active.id, Timestamp: now}
	return []Delivery{{DeviceID: android.ID, Signal: signal}, {DeviceID: browser.ID, Signal: signal}}
}

func (b *Broker) Handle(device Device, signal ClientSignal, now time.Time) ([]Delivery, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.expire(now)
	if b.active == nil || signal.SessionID == "" || signal.SessionID != b.active.id || !b.inSession(device) {
		return nil, ErrSessionInvalid
	}
	target := b.other(device)
	switch signal.Type {
	case SignalOffer:
		if device.Type != WindowsBrowser || !validSDP(signal.Data) {
			return nil, ErrSignalForbidden
		}
	case SignalAnswer:
		if device.Type != AndroidMaster || !validSDP(signal.Data) {
			return nil, ErrSignalForbidden
		}
	case SignalICE:
		candidate, ok := candidateValue(signal.Data)
		if !ok {
			return nil, ErrSignalInvalid
		}
		if !isHostCandidate(candidate) {
			return nil, ErrNonHostCandidate
		}
	case SignalConnected:
		b.active.state, b.active.connectedAt = "connected", now
		return nil, nil
	case SignalCancel:
		result := []Delivery{{DeviceID: target, Signal: ServerSignal{Type: SignalCancelled, SessionID: signal.SessionID, Timestamp: now}}}
		b.active = nil
		return result, nil
	case SignalLeave:
		return b.leaveLocked(device.ID, now), nil
	default:
		return nil, ErrSignalInvalid
	}
	return []Delivery{{DeviceID: target, Signal: ServerSignal{Type: signal.Type, SessionID: signal.SessionID, Timestamp: now, Data: signal.Data}}}, nil
}

func (b *Broker) Leave(deviceID string) []Delivery {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.leaveLocked(deviceID, time.Now().UTC())
}

func (b *Broker) leaveLocked(deviceID string, now time.Time) []Delivery {
	for role, device := range b.ready {
		if device.ID == deviceID {
			delete(b.ready, role)
		}
	}
	if b.active == nil {
		return nil
	}
	var target string
	if b.active.androidID == deviceID {
		target = b.active.browserID
	} else if b.active.browserID == deviceID {
		target = b.active.androidID
	} else {
		return nil
	}
	id := b.active.id
	b.active = nil
	return []Delivery{{DeviceID: target, Signal: ServerSignal{Type: SignalPeerOffline, SessionID: id, Timestamp: now}}}
}

func (b *Broker) expire(now time.Time) {
	if b.active == nil {
		return
	}
	if b.active.state == "negotiating" && now.Sub(b.active.createdAt) >= 2*time.Minute {
		b.active = nil
		return
	}
	if b.active.state == "connected" && now.Sub(b.active.connectedAt) >= 12*time.Hour {
		b.active = nil
	}
}

func (b *Broker) inSession(device Device) bool {
	return (device.Type == AndroidMaster && b.active.androidID == device.ID) || (device.Type == WindowsBrowser && b.active.browserID == device.ID)
}
func (b *Broker) other(device Device) string {
	if device.Type == AndroidMaster {
		return b.active.browserID
	}
	return b.active.androidID
}
func validDevice(device Device) bool {
	return device.ID != "" && (device.Type == AndroidMaster || device.Type == WindowsBrowser)
}

func validSDP(raw json.RawMessage) bool {
	var data struct {
		SDP string `json:"sdp"`
	}
	return json.Unmarshal(raw, &data) == nil && strings.TrimSpace(data.SDP) != ""
}
func candidateValue(raw json.RawMessage) (string, bool) {
	var data struct {
		Candidate     string  `json:"candidate"`
		SDPMid        *string `json:"sdp_mid"`
		SDPMLineIndex *int    `json:"sdp_mline_index"`
	}
	if json.Unmarshal(raw, &data) != nil || strings.TrimSpace(data.Candidate) == "" {
		return "", false
	}
	if data.SDPMLineIndex == nil {
		return "", false
	}
	if data.SDPMid != nil && strings.TrimSpace(*data.SDPMid) == "" {
		return "", false
	}
	if data.SDPMLineIndex != nil && *data.SDPMLineIndex < 0 {
		return "", false
	}
	return data.Candidate, true
}
func isHostCandidate(candidate string) bool {
	fields := strings.Fields(strings.ToLower(candidate))
	for index := 0; index+1 < len(fields); index++ {
		if fields[index] == "typ" {
			return fields[index+1] == "host"
		}
	}
	return false
}
