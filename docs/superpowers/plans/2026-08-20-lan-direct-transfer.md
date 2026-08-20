# LAN Direct Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an independent Android-to-Web LAN transfer mode that sends file bytes over an encrypted WebRTC DataChannel without storing file content or history on the TransDot server.

**Architecture:** Extend the authenticated `/ws` connection with a typed, size-limited signaling protocol backed by an in-memory one-Android/one-browser broker. The browser is always the WebRTC offerer, Android uses pinned libwebrtc, and both platforms share an ordered control/binary file protocol with 64 KiB chunks, incremental SHA-256, queueing, and explicit backpressure.

**Tech Stack:** Go 1.26, coder/websocket, React 19, TypeScript 7, native browser WebRTC and File System Access API, `@noble/hashes:2.3.0`, Kotlin/JVM 17, Jetpack Compose, OkHttp 5.4, `io.github.webrtc-sdk:android:144.7559.12`.

## Global Constraints

- Work only on `codex/c`; preserve `.gocache/` and `.gomodcache/` as untracked local caches.
- LAN transfer is a separate mode and must not change existing timeline upload/download semantics.
- Android and Chrome/Edge must both be online and in LAN mode; Web is always the offerer.
- Use `iceServers: []`, relay only Host ICE candidates, and fail connection attempts after 8 seconds.
- Never invoke `/api/v1/upload-batches` or other cloud file APIs from a LAN failure path.
- Maximum queue length is 20 files; maximum file size is exactly `2 * 1024 * 1024 * 1024` bytes; no resume or folders.
- Transfer one file at a time using reliable ordered DataChannel messages and 64 KiB binary chunks.
- Web asks for a receive directory once and automatically receives while permission remains granted.
- Android automatically receives to its configured destination and uses a foreground service only during active transfer.
- File metadata and SHA-256 travel only inside the DTLS-protected DataChannel, never through server signaling.
- No database migration and no additional server listening port.

---

### Task 1: In-Memory LAN Signaling Broker

**Files:**
- Create: `server/internal/lantransfer/broker.go`
- Create: `server/internal/lantransfer/broker_test.go`

**Interfaces:**
- Produces: `lantransfer.NewBroker(instanceID string) *Broker`
- Produces: `(*Broker).Ready(device Device, now time.Time) []Delivery`
- Produces: `(*Broker).Handle(device Device, signal ClientSignal, now time.Time) ([]Delivery, error)`
- Produces: `(*Broker).Leave(deviceID string) []Delivery`
- Produces: `ClientSignal`, `ServerSignal`, `Delivery`, `ErrSessionBusy`, `ErrSessionInvalid`, `ErrSignalForbidden`, `ErrNonHostCandidate`, and `ErrSignalInvalid`.

- [ ] **Step 1: Write failing broker lifecycle and authorization tests**

```go
func TestBrokerCreatesSessionOnlyForMasterAndBrowser(t *testing.T) {
    broker := NewBroker("instance-1")
    if deliveries := broker.Ready(Device{ID: "android", Type: "android_master"}, time.Unix(1, 0)); len(deliveries) != 0 {
        t.Fatalf("first ready deliveries = %#v", deliveries)
    }
    deliveries := broker.Ready(Device{ID: "browser", Type: "windows_browser"}, time.Unix(2, 0))
    if len(deliveries) != 2 || deliveries[0].Signal.Type != "lan.peer_online" {
        t.Fatalf("peer deliveries = %#v", deliveries)
    }
    if deliveries[0].Signal.SessionID == "" || deliveries[0].Signal.SessionID != deliveries[1].Signal.SessionID {
        t.Fatalf("session IDs do not match: %#v", deliveries)
    }
}

func TestBrokerRejectsNonHostCandidateAndWrongSender(t *testing.T) {
    broker, sessionID := readyBroker(t)
    _, err := broker.Handle(Device{ID: "android", Type: "android_master"}, ClientSignal{
        Type: "lan.ice", SessionID: sessionID,
        Data: json.RawMessage(`{"candidate":"candidate:1 1 UDP 1 203.0.113.2 5000 typ srflx"}`),
    }, time.Now())
    if !errors.Is(err, ErrNonHostCandidate) { t.Fatalf("error = %v", err) }
}
```

- [ ] **Step 2: Run broker tests and confirm the package is missing**

Run: `cd server && go test ./internal/lantransfer -count=1`

Expected: FAIL because `server/internal/lantransfer` does not exist.

- [ ] **Step 3: Implement the typed broker state machine**

```go
type Device struct { ID, Type string }
type ClientSignal struct { Type, SessionID string; Timestamp time.Time; Data json.RawMessage }
type ServerSignal struct { Type, SessionID string; Timestamp time.Time; Data any }
type Delivery struct { DeviceID string; Signal ServerSignal }

type session struct {
    id, androidID, browserID string
    state string // negotiating or connected
    createdAt time.Time
}

type Broker struct {
    mu sync.Mutex
    instanceID string
    ready map[string]Device
    active *session
}
```

Allow only one ready device for each of `android_master` and `windows_browser`. Create a UUID when both roles are ready. Permit `lan.offer` only from the browser, `lan.answer` only from Android, `lan.ice` from either role when its parsed candidate contains `typ host`, `lan.connected` from either role, and `lan.cancel`/`lan.leave` from either role. Expire negotiating state after 2 minutes and connected state after 12 hours. Copy raw SDP/ICE only into the opposite device delivery and never log it.

- [ ] **Step 4: Add table tests for all allowed and rejected transitions**

Cover duplicate ready, browser-only offer, Android-only answer, both-role ICE, unknown fields/types, wrong session, busy session, negotiation expiry, connected expiry, `Leave`, and concurrent `Ready`/`Leave` under `go test -race`.

- [ ] **Step 5: Run broker tests**

Run: `cd server && go test -race ./internal/lantransfer -count=1`

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add server/internal/lantransfer
git commit -m "feat: add LAN signaling broker"
```

### Task 2: Bidirectional Authenticated WebSocket Signaling

**Files:**
- Modify: `server/internal/realtime/hub.go`
- Modify: `server/internal/realtime/hub_test.go`
- Modify: `server/internal/httpserver/websocket.go`
- Modify: `server/internal/httpserver/server.go`
- Modify: `server/internal/httpserver/server_test.go`
- Modify: `server/cmd/transfer-assistant/main.go`
- Create: `server/internal/httpserver/websocket_lan_test.go`

**Interfaces:**
- Consumes: Task 1 `*lantransfer.Broker` and signal types.
- Produces: `(*Hub).PublishTo(deviceID, eventType string, data any) bool`.
- Produces: `/ws` client Envelope parsing with a 64 KiB read limit.

- [ ] **Step 1: Write failing targeted-Hub tests**

```go
func TestPublishToOnlyReachesTargetDevice(t *testing.T) {
    hub := NewHub()
    target := hub.Subscribe("target")
    other := hub.Subscribe("other")
    if !hub.PublishTo("target", "lan.peer_online", map[string]string{"session_id": "s"}) {
        t.Fatal("target was not connected")
    }
    select { case <-target.Events(): case <-time.After(time.Second): t.Fatal("target received nothing") }
    select { case event := <-other.Events(): t.Fatalf("other received %#v", event); default: }
}
```

- [ ] **Step 2: Run the targeted-Hub test and observe the missing method failure**

Run: `cd server && go test ./internal/realtime -run TestPublishToOnlyReachesTargetDevice -count=1`

Expected: FAIL with `hub.PublishTo undefined`.

- [ ] **Step 3: Implement `PublishTo` without changing broadcast behavior**

Use the existing per-device subscription map, create one event, non-blockingly enqueue it to all subscriptions for only that device, and return whether the device had a subscription.

- [ ] **Step 4: Write failing WebSocket integration tests**

Create two authenticated test sockets. Send `lan.ready` from both, assert both receive the same server-created session ID, send a browser Offer and assert only Android receives it, then send a `srflx` candidate and assert `lan.error` with `LAN_NON_HOST_CANDIDATE`. Send a 65 KiB frame and assert policy closure without affecting the REST health endpoint.

- [ ] **Step 5: Replace the discard-only WebSocket reader with typed signaling dispatch**

```go
type lanEnvelope struct {
    Type string `json:"type"`
    SessionID string `json:"session_id,omitempty"`
    Timestamp time.Time `json:"timestamp"`
    Data json.RawMessage `json:"data,omitempty"`
}

const websocketReadLimit = 64 * 1024
```

Pass a process-scoped broker into `NewComplete`, translate authenticated `deviceauth.Device` into `lantransfer.Device`, publish broker deliveries through `Hub.PublishTo`, call `Leave` on disconnect, and preserve ping, replacement, shutdown, and existing timeline events.

- [ ] **Step 6: Wire the broker in `main.go`**

Create it from the already loaded persistent server instance ID and pass it into the complete HTTP handler. Do not add config or ports.

- [ ] **Step 7: Run server tests and race detector**

Run: `cd server && go test -race ./internal/realtime ./internal/lantransfer ./internal/httpserver ./cmd/transfer-assistant -count=1`

Expected: PASS.

- [ ] **Step 8: Commit Task 2**

```bash
git add server/cmd server/internal/httpserver server/internal/realtime
git commit -m "feat: relay authenticated LAN signals"
```

### Task 3: Web File Protocol and Receive Directory Store

**Files:**
- Modify: `web/package.json`
- Modify: `web/package-lock.json`
- Create: `web/src/lan/protocol.ts`
- Create: `web/src/lan/protocol.test.ts`
- Create: `web/src/lan/directoryStore.ts`
- Create: `web/src/lan/directoryStore.test.ts`
- Create: `web/src/lan/types.ts`

**Interfaces:**
- Produces: `MAX_LAN_FILES`, `MAX_LAN_FILE_BYTES`, `LAN_CHUNK_BYTES`.
- Produces: `encodeControl`, `parseControl`, `sanitizeFilename`, `uniqueFilename`.
- Produces: `IncrementalFileReceiver` and `streamFile` using `@noble/hashes/sha2.js`.
- Produces: `loadDirectory`, `saveDirectory`, `ensureDirectoryPermission`.

- [ ] **Step 1: Install and pin the incremental hash dependency**

Run: `cd web && npm install @noble/hashes@2.3.0 --save-exact`

Expected: `package.json` and lockfile contain exactly `2.3.0`.

- [ ] **Step 2: Write failing protocol tests**

```ts
it('rejects a file larger than 2 GiB', () => {
  expect(() => parseControl(JSON.stringify({
    type: 'file_offer', file_id: crypto.randomUUID(), name: 'large.bin',
    mime: 'application/octet-stream', size: 2 * 1024 ** 3 + 1,
  }))).toThrowError('FILE_TOO_LARGE')
})

it('sanitizes traversal and preserves an extension', () => {
  expect(sanitizeFilename('../a\\b?.txt')).toBe('a_b_.txt')
})
```

Add exact vectors for empty files, Chinese names, 20/21 queue items, 64 KiB chunking, SHA-256 of `abc`, malformed JSON, illegal transitions, cancellation, and duplicate names.

- [ ] **Step 3: Run tests and confirm missing module failures**

Run: `cd web && npm test -- src/lan/protocol.test.ts`

Expected: FAIL because `protocol.ts` does not exist.

- [ ] **Step 4: Implement protocol and streaming primitives**

```ts
export const MAX_LAN_FILES = 20
export const MAX_LAN_FILE_BYTES = 2 * 1024 * 1024 * 1024
export const LAN_CHUNK_BYTES = 64 * 1024
export const HIGH_WATER_BYTES = 4 * 1024 * 1024
export const LOW_WATER_BYTES = 1024 * 1024
```

Use discriminated control-frame unions. Hash every outgoing and incoming chunk incrementally with `sha256.create()`. Make the receiver accept only the byte count declared by `file_offer`, close on exact completion, and delete the output on cancel, oversize, count mismatch, or hash mismatch.

- [ ] **Step 5: Write failing directory-store tests with a fake IndexedDB and fake handles**

Test persisted handle load/save, granted permission, prompt permission requiring user activation, revoked permission, same-name suffixes, and partial-file removal.

- [ ] **Step 6: Implement IndexedDB directory persistence**

Store one handle under database `transdot-lan`, object store `settings`, key `receive-directory`. Never serialize or send a local path. Return a typed `permission-required` result instead of prompting outside a click handler.

- [ ] **Step 7: Run Web unit tests and typecheck**

Run: `cd web && npm test && npm run typecheck`

Expected: PASS.

- [ ] **Step 8: Commit Task 3**

```bash
git add web/package.json web/package-lock.json web/src/lan
git commit -m "feat: add Web LAN file protocol"
```

### Task 4: Web Signaling, WebRTC Peer, and LAN Transfer View

**Files:**
- Create: `web/src/lan/signaling.ts`
- Create: `web/src/lan/signaling.test.ts`
- Create: `web/src/lan/peer.ts`
- Create: `web/src/lan/peer.test.ts`
- Create: `web/src/lan/LanTransferView.tsx`
- Create: `web/src/lan/LanTransferView.test.tsx`
- Modify: `web/src/App.tsx`
- Modify: `web/src/styles.css`

**Interfaces:**
- Consumes: Task 3 protocol and directory APIs.
- Produces: `LanSignalingClient`, `LanPeer`, and `LanTransferView`.
- Produces: a Timeline header command that opens/closes the separate LAN view.

- [ ] **Step 1: Write failing signaling tests**

Test `lan.ready` on open, server-created session acceptance, Offer creation only after `lan.peer_online`, Host-only local ICE emission, ignored non-Host remote ICE, reconnect returning to waiting state, and `lan.leave` on close.

- [ ] **Step 2: Implement `LanSignalingClient` over the authenticated existing-origin WSS endpoint**

Use the current cookie-authenticated `/ws`. Keep timeline events and LAN signals as distinct discriminated envelopes. Do not put file metadata in a WSS message.

- [ ] **Step 3: Write failing peer tests with injected fake `RTCPeerConnection` and DataChannel**

Assert `iceServers: []`, ordered reliable channel creation, 8-second timeout, `bufferedAmount` pause at 4 MiB and resume at 1 MiB, one-file-at-a-time queue, hash verification before advancing, and no calls to the global REST `request` function.

- [ ] **Step 4: Implement `LanPeer`**

Expose a state subscription with `waiting`, `connecting`, `connected`, `transferring`, `failed`, and `closed`. Keep connection and transfer state outside React, inject timers/peer factory for deterministic tests, and close all resources idempotently.

- [ ] **Step 5: Write failing LAN view interaction tests**

Cover first-time “选择接收文件夹”, permission-revoked recovery, waiting for Android, 8-second connecting state, connected status, multi-file selection, current progress/speed, per-file retry, cancel, and no cloud fallback button.

- [ ] **Step 6: Implement the unframed work-focused LAN view**

Add an icon+text “局域网快传” command in the existing timeline toolbar. Reuse existing typography, color, spacing, responsive breakpoints, and icon conventions. Keep stable progress-row dimensions, make filenames wrap safely, and avoid nested cards. The view owns one `LanPeer` and disposes it when returning to the timeline.

- [ ] **Step 7: Run Web verification**

Run: `cd web && npm test && npm run build`

Expected: all tests and production build PASS.

- [ ] **Step 8: Commit Task 4**

```bash
git add web/src/App.tsx web/src/styles.css web/src/lan
git commit -m "feat: add Web LAN transfer experience"
```

### Task 5: Android Shared LAN Protocol and File Store

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/com/transdot/transferassistant/lan/LanProtocol.kt`
- Create: `android/app/src/main/java/com/transdot/transferassistant/lan/LanFileStore.kt`
- Create: `android/app/src/test/java/com/transdot/transferassistant/lan/LanProtocolTest.kt`
- Create: `android/app/src/test/java/com/transdot/transferassistant/lan/LanFileStoreTest.kt`

**Interfaces:**
- Produces: sealed `LanControlFrame`, `LanTransferQueue`, and constants matching Task 3.
- Produces: `LanFileStore.inspect`, `openSource`, `openDestination`, `deletePartial`.

- [ ] **Step 1: Pin libwebrtc and verify Gradle resolves it**

Add:

```kotlin
implementation("io.github.webrtc-sdk:android:144.7559.12")
```

Run: `cd android && ./gradlew --no-daemon :app:dependencies --configuration debugRuntimeClasspath`

Expected: output contains `io.github.webrtc-sdk:android:144.7559.12` with no version substitution.

- [ ] **Step 2: Write failing cross-platform protocol-vector tests**

Use the same JSON vectors as Task 3. Assert 64 KiB chunk constant, 20-file queue, 2 GiB boundary using `Long`, empty file SHA-256, Chinese filename preservation, traversal cleanup, state transitions, and hash mismatch cleanup.

- [ ] **Step 3: Run the focused tests and confirm missing types**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests '*LanProtocolTest'`

Expected: FAIL because LAN protocol classes do not exist.

- [ ] **Step 4: Implement protocol types and queue state machine**

```kotlin
const val LAN_CHUNK_BYTES = 64 * 1024
const val MAX_LAN_FILES = 20
const val MAX_LAN_FILE_BYTES = 2L * 1024 * 1024 * 1024

sealed interface LanControlFrame {
    val fileId: String?
    data class FileOffer(override val fileId: String, val name: String, val mime: String, val size: Long) : LanControlFrame
    data class FileComplete(override val fileId: String, val sha256: String) : LanControlFrame
}
```

Implement strict JSON parsing for `file_offer`, `file_accept`, `file_reject`, `file_complete`, `file_verified`, `file_failed`, `queue_complete`, and `transfer_cancel`. Reject unknown fields and unknown frame types. Permit only one active file, require matching `file_id` on every transition, and expose the stable errors `FILE_TOO_LARGE`, `TOO_MANY_FILES`, `LAN_PROTOCOL_ERROR`, `FILE_HASH_MISMATCH`, and `TRANSFER_CANCELLED`.

- [ ] **Step 5: Write file-store tests using fake ContentResolver streams**

Cover metadata lookup, unknown size rejection, available-space failure, unique names, zero-byte output, write error, cancellation, hash mismatch, and deletion of partial output.

- [ ] **Step 6: Implement streaming `LanFileStore`**

Use `ContentResolver` and the existing download destination configuration. Never convert a 2 GiB input to a byte array. Compute SHA-256 with `MessageDigest` while reading/writing and expose progress as `Long` byte counts.

- [ ] **Step 7: Run Android focused tests**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests '*Lan*'`

Expected: PASS.

- [ ] **Step 8: Commit Task 5**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/transdot/transferassistant/lan android/app/src/test/java/com/transdot/transferassistant/lan
git commit -m "feat: add Android LAN file protocol"
```

### Task 6: Android Signaling and libwebrtc Peer Engine

**Files:**
- Create: `android/app/src/main/java/com/transdot/transferassistant/lan/LanSignalingClient.kt`
- Create: `android/app/src/main/java/com/transdot/transferassistant/lan/LanPeerEngine.kt`
- Create: `android/app/src/test/java/com/transdot/transferassistant/lan/LanSignalingClientTest.kt`
- Create: `android/app/src/test/java/com/transdot/transferassistant/lan/LanPeerEngineTest.kt`

**Interfaces:**
- Consumes: existing `StoredSession`, OkHttp client patterns, and Task 5 protocol/store.
- Produces: `LanSignalingClient.events: Flow<LanSignalEvent>` and `LanPeerEngine.state: StateFlow<LanPeerState>`.

- [ ] **Step 1: Write failing signaling tests using OkHttp MockWebServer or an injected fake WebSocket transport**

Assert Bearer authentication, `lan.ready`, server session acceptance, Answer-only Android role, Host-only ICE, structured errors, reconnect reset, and `lan.leave` on close. The test must verify no filename or hash appears in captured WSS frames.

- [ ] **Step 2: Implement `LanSignalingClient`**

Follow the existing TimelineRepository WebSocket URL normalization and authorization header. Use strict JSON parsing and a dedicated coroutine scope that is cancelled by `close()`.

- [ ] **Step 3: Write failing `LanPeerEngine` tests behind a small libwebrtc adapter interface**

Assert empty ICE server list, 8-second timeout, accepted Offer and emitted Answer, ignored non-Host candidates, ordered DataChannel observer wiring, send-buffer backpressure, one active file, cleanup on disconnect, and idempotent close.

- [ ] **Step 4: Implement the libwebrtc adapter and peer engine**

Initialize `PeerConnectionFactory` once per application process, create no audio/video tracks, configure unified plan and an empty ICE server list, convert DataChannel string/binary buffers without whole-file copies, and run libwebrtc callbacks on a serialized coroutine dispatcher.

- [ ] **Step 5: Run focused Android tests**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest --tests '*LanSignalingClientTest' --tests '*LanPeerEngineTest'`

Expected: PASS.

- [ ] **Step 6: Commit Task 6**

```bash
git add android/app/src/main/java/com/transdot/transferassistant/lan android/app/src/test/java/com/transdot/transferassistant/lan
git commit -m "feat: connect Android LAN peer"
```

### Task 7: Android Foreground Service and Compose LAN Experience

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/MainActivity.kt`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/ui/TimelineScreen.kt`
- Create: `android/app/src/main/java/com/transdot/transferassistant/lan/LanTransferService.kt`
- Create: `android/app/src/main/java/com/transdot/transferassistant/ui/LanTransferViewModel.kt`
- Create: `android/app/src/main/java/com/transdot/transferassistant/ui/LanTransferScreen.kt`
- Create: `android/app/src/test/java/com/transdot/transferassistant/ui/LanTransferViewModelTest.kt`

**Interfaces:**
- Consumes: Task 6 peer state and Task 5 file queue/store.
- Produces: `LanTransferViewModel.UiState` and navigation between Timeline and LAN transfer.

- [ ] **Step 1: Write failing ViewModel tests**

Cover waiting, connecting countdown, connected, multi-select queue, incoming automatic acceptance, progress/speed, per-file retry, cancellation, peer offline, destination unavailable, and the invariant that no TimelineRepository upload method is referenced or called.

- [ ] **Step 2: Implement `LanTransferViewModel`**

Keep UI state immutable and stable. Accept injected peer, file store, clock, and foreground-service controller. Start the service only when a file enters transferring state and stop it when no file is active.

- [ ] **Step 3: Add foreground service declarations and implementation**

Add `android.permission.FOREGROUND_SERVICE` and the Android 14+ data-sync foreground service permission/type. Show device direction, current filename, percent, cancel action, and stop the service immediately after completion/cancel/failure.

- [ ] **Step 4: Implement the Compose screen and navigation**

Add a “局域网快传” toolbar command using the project icon system. Build a quiet work-focused full-width screen with stable connection status, peer state, send action, queue rows, progress, speed, retry/cancel, and back navigation. Avoid nested cards, preserve safe drawing insets, and ensure long filenames wrap without covering controls.

- [ ] **Step 5: Run Android unit tests and build**

Run: `cd android && ./gradlew --no-daemon testDebugUnitTest assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 7**

```bash
git add android/app/src/main/AndroidManifest.xml android/app/src/main/java android/app/src/test
git commit -m "feat: add Android LAN transfer experience"
```

### Task 8: Integration, Privacy Regression, Documentation, and Release Verification

**Files:**
- Modify: `README.md`
- Modify: `android/README.md`
- Modify: `docker/README.md`
- Modify: `server/internal/httpserver/websocket_lan_test.go`
- Modify: `web/src/lan/peer.test.ts`
- Modify: `android/app/src/test/java/com/transdot/transferassistant/lan/LanPeerEngineTest.kt`

**Interfaces:**
- Consumes all previous tasks.
- Produces a documented, verified C-branch feature ready for real-device acceptance.

- [ ] **Step 1: Add a server privacy regression test**

Run a complete ready/offer/answer/ICE signaling exchange and assert there are no rows added to messages/files tables, no data-volume file created, and no server event contains `file_offer`, filename, MIME, size, or SHA-256.

- [ ] **Step 2: Add Web and Android cloud-fallback guards**

Use injected cloud clients that fail the test if invoked. Exercise connect timeout, DataChannel close, hash mismatch, cancellation, and peer offline; assert every case stays entirely in LAN state.

- [ ] **Step 3: Document operation and limitations**

Document Chrome/Edge-only support, both devices online, first Web directory grant, Android automatic destination, 20 files, 2 GiB, no resume, no cloud fallback, client-isolation failures, no new server ports, and no Reset requirement.

- [ ] **Step 4: Run all automated verification**

Run:

```bash
cd server && go test -race ./... -count=1
cd ../web && npm test && npm run build
cd ../android && ./gradlew --no-daemon testDebugUnitTest assembleDebug
cd .. && docker compose config --quiet
git diff --check
```

Expected: all commands exit 0; Web reports all tests passed; Gradle reports `BUILD SUCCESSFUL`.

- [ ] **Step 5: Perform real-device acceptance**

On the same non-isolated LAN, verify Android→Chrome and Chrome→Android with empty, Chinese-name, duplicate-name, 100 MiB, 1 GiB, and near-2-GiB files. Verify SHA-256 equality, automatic receive after directory permission, screen lock during active Android transfer, network switch cleanup, different-network timeout, and zero cloud upload requests/data-volume growth.

- [ ] **Step 6: Record environment-limited evidence**

Add a concise verification note to the branch handoff identifying any skipped 1 GiB/2 GiB, physical-device, browser-permission, firewall, or AP-isolation case. Do not claim skipped cases passed.

- [ ] **Step 7: Commit Task 8**

```bash
git add README.md android/README.md docker/README.md server web android
git commit -m "docs: complete LAN transfer verification"
```
