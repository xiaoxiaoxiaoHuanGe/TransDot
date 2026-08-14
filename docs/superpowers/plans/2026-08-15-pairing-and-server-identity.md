# Pairing Protection and Server Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent misleading Web pairing retries and make the active TransDot server unmistakable in APP and Web.

**Architecture:** Keep Secure cookies mandatory, reject unsafe LAN HTTP pairing in the Web client before session creation, and expose the in-memory limiter's exact remaining window through `Retry-After`. Derive APP labels from the existing active profile and current timeline connection, without probing inactive servers.

**Tech Stack:** Go 1.26 `net/http`, React 19 + TypeScript 7 + Vitest 4, Android Kotlin + Jetpack Compose, Docker Compose.

## Global Constraints

- `Secure`, `HttpOnly`, `SameSite=Strict` cookies remain mandatory.
- HTTP pairing is allowed only for `localhost`, `127.0.0.1`, and `::1`; other origins require HTTPS.
- Only the active Android profile may have a live repository or WebSocket.
- No database schema or response-body contract changes.
- Preserve unrelated dirty-worktree changes.
- Every behavior change follows red-green TDD.

---

### Task 1: Exact pairing rate-limit metadata

**Files:**
- Create: `server/internal/httpserver/limiter_test.go`
- Modify: `server/internal/httpserver/server.go:223-259`
- Modify: `server/internal/httpserver/pairing.go:21-28`
- Test: `server/internal/httpserver/server_test.go`

**Interfaces:**
- Consumes: existing `attemptLimiter` and `createPairingSession`.
- Produces: `AllowWithRetryAfter(key string, now time.Time) (bool, time.Duration)` and integer `Retry-After` on pairing-creation 429.

- [ ] **Step 1: Write failing limiter tests**

```go
func TestAttemptLimiterReportsRemainingWindow(t *testing.T) {
    limiter := newAttemptLimiter(1, 10*time.Second)
    started := time.Date(2026, 8, 15, 0, 0, 0, 0, time.UTC)
    if allowed, wait := limiter.AllowWithRetryAfter("client", started); !allowed || wait != 0 {
        t.Fatalf("first=(%v,%v), want (true,0)", allowed, wait)
    }
    if allowed, wait := limiter.AllowWithRetryAfter("client", started.Add(2500*time.Millisecond)); allowed || wait != 7500*time.Millisecond {
        t.Fatalf("limited=(%v,%v), want (false,7.5s)", allowed, wait)
    }
}

func TestAttemptLimiterResetsAfterWindow(t *testing.T) {
    limiter := newAttemptLimiter(1, 10*time.Second)
    started := time.Date(2026, 8, 15, 0, 0, 0, 0, time.UTC)
    limiter.AllowWithRetryAfter("client", started)
    if allowed, wait := limiter.AllowWithRetryAfter("client", started.Add(10*time.Second)); !allowed || wait != 0 {
        t.Fatalf("reset=(%v,%v), want (true,0)", allowed, wait)
    }
}
```

- [ ] **Step 2: Verify RED**

Run `go test ./internal/httpserver -run AttemptLimiter -count=1` from `server`.

Expected: compilation fails because `AllowWithRetryAfter` is undefined.

- [ ] **Step 3: Implement the limiter result while preserving existing callers**

```go
func (l *attemptLimiter) Allow(key string, now time.Time) bool {
    allowed, _ := l.AllowWithRetryAfter(key, now)
    return allowed
}

func (l *attemptLimiter) AllowWithRetryAfter(key string, now time.Time) (bool, time.Duration) {
    l.mu.Lock()
    defer l.mu.Unlock()
    // Retain the current sweep and entry-cap logic.
    // Accepted requests return (true, 0).
    // Limited requests return (false, window.started.Add(l.duration).Sub(now)).
}
```

- [ ] **Step 4: Add a failing handler test**

Create a limiter with limit 1 and duration 2 minutes, call `createPairingSession` twice from the same `RemoteAddr`, then assert the second response is 429 and `Retry-After` parses to an integer between 1 and 120.

- [ ] **Step 5: Set `Retry-After` and verify GREEN**

```go
allowed, retryAfter := limiter.AllowWithRetryAfter(remoteIP(r.RemoteAddr), time.Now())
if !allowed {
    seconds := max(1, int((retryAfter+time.Second-1)/time.Second))
    w.Header().Set("Retry-After", strconv.Itoa(seconds))
    writeError(w, http.StatusTooManyRequests, "RATE_LIMITED", "Too many pairing sessions. Try again later.")
    return
}
```

Run `go test ./internal/httpserver -count=1` from `server`; expect all tests to pass.

- [ ] **Step 6: Commit**

```powershell
git add server/internal/httpserver/limiter_test.go server/internal/httpserver/server.go server/internal/httpserver/pairing.go server/internal/httpserver/server_test.go
git commit -m "fix: expose pairing retry delay"
```

---

### Task 2: Web pairing policy and countdown

**Files:**
- Create: `web/src/pairingPolicy.ts`
- Create: `web/src/pairingPolicy.test.ts`
- Modify: `web/src/App.tsx:1-355,1397-1407`
- Modify: `web/src/styles.css:35-100`

**Interfaces:**
- Consumes: browser `Location`, server `Retry-After`, and existing `ApiError`/`RetryState`.
- Produces: `pairingTransportGuidance`, `parseRetryAfterSeconds`, `instanceHostLabel`, unsafe-origin UI, countdown state, and synchronous duplicate-submit protection.

- [ ] **Step 1: Write failing policy tests**

```ts
it.each([
  ['https:', 'file.example.com'], ['http:', 'localhost'],
  ['http:', '127.0.0.1'], ['http:', '::1'],
])('allows %s//%s', (protocol, hostname) => {
  expect(pairingTransportGuidance({ protocol, hostname, port: '5758' })).toBeNull()
})

it('blocks LAN HTTP with a localhost hint', () => {
  expect(pairingTransportGuidance({ protocol: 'http:', hostname: '192.168.137.47', port: '5758' }))
    .toContain('localhost:5758')
})

it('normalizes Retry-After', () => {
  expect(parseRetryAfterSeconds('37')).toBe(37)
  expect(parseRetryAfterSeconds(null)).toBe(120)
  expect(parseRetryAfterSeconds('soon')).toBe(120)
  expect(parseRetryAfterSeconds('0')).toBe(1)
})
```

- [ ] **Step 2: Verify RED**

Run `npm test -- pairingPolicy.test.ts` from `web`.

Expected: module resolution fails because `pairingPolicy.ts` does not exist.

- [ ] **Step 3: Implement pure policy helpers**

```ts
export type PairingLocation = Pick<Location, 'protocol' | 'hostname' | 'port'>
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '::1'])

export function pairingTransportGuidance(location: PairingLocation) {
  if (location.protocol === 'https:' || LOOPBACK_HOSTS.has(location.hostname.toLowerCase())) return null
  const port = location.port ? `:${location.port}` : ''
  return `当前 HTTP 地址无法保存安全配对凭据。同一台电脑请打开 http://localhost${port}；其他设备请配置 HTTPS。`
}

export function parseRetryAfterSeconds(value: string | null, fallback = 120) {
  const seconds = Number.parseInt(value ?? '', 10)
  return Number.isFinite(seconds) ? Math.max(1, seconds) : fallback
}

export function instanceHostLabel(host: string) { return host.trim() || '当前服务器' }
```

- [ ] **Step 4: Extend request errors with retry metadata**

Add `retryAfterSeconds?: number` to `ApiError`; when a response is 429, parse `response.headers.get('Retry-After')` and pass it to the constructor.

- [ ] **Step 5: Add safe-origin and duplicate-submit guards**

Add `insecure` to `ScreenState`, `retryAfterSeconds` state, and `creatingSessionRef`. Before POST, call `pairingTransportGuidance(window.location)`; on a warning, enter `insecure` without making a request. Return immediately while the ref is true and reset it in `finally`.

- [ ] **Step 6: Render countdown behavior**

Extend `RetryState` with `retryAfterSeconds` and `retryAllowed`. Hide the button for unsafe origins; for 429, disable it and render `N 秒后可重试`. A one-second interval decrements only while the value is positive and is cleared on cleanup.

- [ ] **Step 7: Verify GREEN**

Run `npm test` and `npm run build` from `web`; expect all tests and the production build to pass.

- [ ] **Step 8: Commit**

```powershell
git add web/src/pairingPolicy.ts web/src/pairingPolicy.test.ts web/src/App.tsx web/src/styles.css
git commit -m "fix: guard unsafe web pairing retries"
```

---

### Task 3: Web and Android server identity

**Files:**
- Modify: `web/src/App.tsx:318-324,900-914,1303-1314`
- Modify: `web/src/styles.css:35-50,152-180,428-435`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/MainActivity.kt:50-130,205-275`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/ui/TimelineScreen.kt:160-370,680-715,1020-1130`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/data/ServerProfiles.kt:1-45`
- Test: `android/app/src/test/java/com/transdot/transferassistant/data/ServerProfilesTest.kt`

**Interfaces:**
- Consumes: `ServerProfileSummary`, `activeProfileId`, `TimelineConnectionState`, `window.location.host`, and the existing action notice overlay.
- Produces: `ServerProfileDisplayStatus`, `serverProfileStatus`, APP active-name/status UI, a one-shot switch notice, and Web instance labels.

- [ ] **Step 1: Write the failing Android status test**

```kotlin
@Test fun activeProfileReflectsConnectionAndInactiveProfileIsSaved() {
    assertEquals(ServerProfileDisplayStatus.Connected, serverProfileStatus(true, true, false))
    assertEquals(ServerProfileDisplayStatus.Connecting, serverProfileStatus(true, false, true))
    assertEquals(ServerProfileDisplayStatus.Offline, serverProfileStatus(true, false, false))
    assertEquals(ServerProfileDisplayStatus.Saved, serverProfileStatus(false, true, false))
}
```

- [ ] **Step 2: Verify RED**

Run `.\gradlew.bat testDebugUnitTest --tests com.transdot.transferassistant.data.ServerProfilesTest` from `android`.

Expected: compilation fails because the status type and function are undefined.

- [ ] **Step 3: Implement the status model**

```kotlin
enum class ServerProfileDisplayStatus { Connected, Connecting, Offline, Saved }

fun serverProfileStatus(isActive: Boolean, isConnected: Boolean, isConnecting: Boolean) = when {
    !isActive -> ServerProfileDisplayStatus.Saved
    isConnected -> ServerProfileDisplayStatus.Connected
    isConnecting -> ServerProfileDisplayStatus.Connecting
    else -> ServerProfileDisplayStatus.Offline
}
```

- [ ] **Step 4: Carry the active profile and notice through `MainActivity`**

Keep `pendingServerNotice` outside `key(sessionGeneration)`. On successful validation and `switchProfile`, set `已切换到 <name>` before incrementing `sessionGeneration`. Pass the active profile name, notice, and consume callback to `TimelineScreen`; a `LaunchedEffect` displays it once through the existing overlay.

- [ ] **Step 5: Render APP labels**

Render `传输助手 · <active name>` in `TimelineTopBar` with one-line ellipsis. In `SettingsSheet`, map the active profile to `已连接`, `连接中`, or `离线`; every inactive profile is `已保存`, with no new network request.

- [ ] **Step 6: Render Web host labels**

Use `instanceHostLabel(window.location.host)` inside `Brand`; render it as a secondary line in pairing and timeline headers. Keep it visible on desktop and hide only the secondary line at the existing narrow breakpoint.

- [ ] **Step 7: Verify GREEN**

Run `.\gradlew.bat testDebugUnitTest assembleDebug` from `android`, then `npm test` and `npm run build` from `web`.

Expected: Android unit tests/APK assembly and Web tests/build all pass.

- [ ] **Step 8: Commit**

```powershell
git add android/app/src/main/java/com/transdot/transferassistant/MainActivity.kt android/app/src/main/java/com/transdot/transferassistant/ui/TimelineScreen.kt android/app/src/main/java/com/transdot/transferassistant/data/ServerProfiles.kt android/app/src/test/java/com/transdot/transferassistant/data/ServerProfilesTest.kt web/src/App.tsx web/src/styles.css
git commit -m "feat: show active server identity"
```

---

### Task 4: Docker and live acceptance

**Files:**
- Verify: `Dockerfile`, `docker-compose.yml`, primary/secondary Docker containers, and `android/app/build/outputs/apk/debug/app-debug.apk`.

**Interfaces:**
- Consumes: completed server, Web, and Android changes.
- Produces: rebuilt image, healthy 5757/5758 instances, and acceptance evidence.

- [ ] **Step 1: Run complete automated verification**

Run `docker compose build --build-arg GOPROXY=https://goproxy.cn,direct`, plus `.\gradlew.bat testDebugUnitTest assembleDebug` from `android`.

- [ ] **Step 2: Recreate both containers without deleting volumes**

Use `docker compose up -d` for 5757. Recreate `transdot-secondary` on 5758 with the existing `transdot-secondary-data` volume and current token. Never remove either named data volume.

- [ ] **Step 3: Verify Web acceptance**

Open LAN HTTP and confirm guidance appears with no pairing POST. Open localhost and confirm pairing works, `Retry-After` drives a disabled countdown, and the 5757/5758 host labels differ.

- [ ] **Step 4: Verify APP acceptance**

Install the debug APK, switch between existing profiles, and confirm topbar name, settings status, success notice, timeline isolation, and real-time reconnect.

- [ ] **Step 5: Completion audit**

Check every item in `docs/superpowers/specs/2026-08-15-pairing-and-server-identity-design.md` against test output, Docker logs, and rendered state. Report any unverified visual item instead of inferring it from compilation.
