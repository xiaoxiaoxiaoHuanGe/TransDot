# Phone Rebind QR and APP Header Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** Add secure Web-to-Android phone rebind QR flow for initialized servers and render the Android server address and status as separate header rows.

**Architecture:** Add a dedicated `rebind` package and migration instead of reusing bootstrap. The HTTP layer authenticates the existing browser cookie for QR creation/status and exposes a public claim endpoint that atomically rotates the Android master device. Web reuses the existing QR/polling screens; Android adds a rebind QR payload/repository and stores the returned session through `SessionStore`.

**Tech Stack:** Go `database/sql`, SQLite migrations, Go `net/http`; React/TypeScript/Vite; Android Kotlin + Jetpack Compose.

## Global Constraints

- All changes remain on `codex/d`; do not modify `codex/c`.
- Rebind sessions are random, hashed, single-use, and expire after the configured pairing TTL.
- Bootstrap remains rejected after initialization.
- Production Android accepts HTTPS only; debug cleartext behavior follows existing policy.
- Existing Pairing and Bootstrap payloads/API compatibility remains intact.

### Task 1: Rebind service and migration

**Files:** Create `server/migrations/009_rebind_sessions.sql`, `server/internal/rebind/service.go`, `server/internal/rebind/service_test.go`.

- [ ] Write tests for creation, initialized guard, expiry, replay, instance mismatch, atomic device rotation, and rollback.
- [ ] Run `go test ./server/internal/rebind`; confirm RED before implementation.
- [ ] Implement `Service.Create`, `Service.Claim`, and `Service.Poll` with SHA-256 hashes, random URL-safe secrets, and one transaction for revoke/insert/consume.
- [ ] Run the focused package tests and then `go test ./...`.

### Task 2: HTTP endpoints

**Files:** Create `server/internal/httpserver/rebind.go`, modify `server/internal/httpserver/server.go`, `server/cmd/transfer-assistant/main.go`, add `server/internal/httpserver/rebind_test.go`.

- [ ] Add failing handler tests for authenticated creation/status, unauthenticated rejection, claim errors, and QR payload fields.
- [ ] Add service construction and routes with existing limiters and browser authentication.
- [ ] Run focused HTTP tests, then all Go tests.

### Task 3: Web rebind state and entry point

**Files:** Modify `web/src/App.tsx`, `web/src/styles.css`; add tests in `web/src/rebind.test.ts` if pure helpers are extracted.

- [ ] Add a failing test for rebind state transitions/expiry.
- [ ] Add `rebind` screen state, create/status polling, QR card copy, cancel/refresh, and `TimelineApp` callback.
- [ ] Add “重新绑定手机” action to the authenticated timeline without exposing tokens.
- [ ] Run `npm test`, `npm run typecheck`, and `npm run build`.

### Task 4: Android QR claim flow

**Files:** Modify `android/app/src/main/java/com/transdot/transferassistant/data/PairingPayload.kt`, create `RebindRepository.kt`, modify pairing/setup UI and view models, add corresponding Kotlin tests.

- [ ] Write failing parser/repository/view-model tests for `kind: rebind`, HTTPS and instance checks, confirmation, and preserving old session on failure.
- [ ] Implement payload parsing, claim request, confirmation UI, and atomic `SessionStore` replacement.
- [ ] Run focused Android tests and `assembleDebug`.

### Task 5: Android three-line header

**Files:** Modify `android/app/src/main/java/com/transdot/transferassistant/data/ServerProfiles.kt`, `ui/components/AppComponents.kt`, `ui/TimelineScreen.kt`; test `ServerProfilesTest.kt`.

- [ ] Run the existing failing `activeServerStatusLines` test and confirm the missing symbol failure.
- [ ] Add the list helper, compatible `subtitleLines` API, and separate address/status `Text` nodes with ellipsis only on the address.
- [ ] Run Android unit tests/build and perform a screenshot/layout check.

### Task 6: Integrated verification

- [ ] Run Go, Web, and Android full test/build commands.
- [ ] Review `git diff --check`, migration ordering, and `git status` to confirm only D changed.
- [ ] Summarize deployment and rebind user flow; do not merge until acceptance.
