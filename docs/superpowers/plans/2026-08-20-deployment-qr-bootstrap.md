# Deployment QR Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make 1Panel updates deterministic and add secure QR-only first-time Android bootstrap while preserving manual setup fallback.

**Architecture:** Persist a server instance identity in SQLite, expose short-lived one-time Bootstrap sessions, and extend the existing Web/Android pairing flows with versioned payloads. Keep Reset local-only and explicit; keep ordinary Compose updates on one fixed project and volume.

**Tech Stack:** Go 1.26, SQLite migrations, net/http, React/TypeScript/Vite, Kotlin/Jetpack Compose, Docker Compose.

## Global Constraints

- Never place `OWNER_SETUP_TOKEN` or Master Token in QR payloads.
- Bootstrap and Pairing secrets are random, short-lived, single-use credentials stored only as hashes.
- Production Android connections require HTTPS; existing debug cleartext policy remains.
- Reset is not a public HTTP endpoint.
- Ordinary updates must not delete the named data volume.
- Existing v1 pairing and manual setup remain available during migration.

---

### Task 1: Compose update/reset tooling

**Files:**
- Modify: `docker-compose.yml`
- Create: `docker/update.sh`
- Create: `docker/reset.sh`
- Modify: `docker/README.md`
- Modify: `README.md`

- [ ] Make Compose project and container port explicit without changing the current default deployment.
- [ ] Add an update script that validates `/opt/transdot`, runs `git pull`, `docker compose -p transdot up -d --build --remove-orphans`, and waits on `/healthz`.
- [ ] Add a reset script requiring literal `RESET`, validating the fixed project and volume names, then stopping/removing the named volume and starting the service.
- [ ] Document ordinary update versus destructive reset and never recommend `down -v`.
- [ ] Run shell syntax checks where available and Compose config validation.

### Task 2: Persistent instance identity

**Files:**
- Create: `server/migrations/007_server_instance.sql`
- Create: `server/internal/instance/service.go`
- Create: `server/internal/instance/service_test.go`
- Modify: `server/cmd/transfer-assistant/main.go`
- Modify: `server/internal/httpserver/server.go`
- Modify: `server/internal/config/config.go`

- [ ] Add a singleton `server_instance` table with random opaque ID and fingerprint.
- [ ] Load/create identity transactionally on startup and expose it through a service.
- [ ] Add `GET /api/v1/instance/info` returning instance ID, fingerprint, initialized state, and configured public URL.
- [ ] Add `PUBLIC_URL` configuration validation; preserve current address behavior when unset.
- [ ] Add unit and handler tests for stability, reset-by-new-database, and response shape.

### Task 3: Bootstrap session service and HTTP API

**Files:**
- Create: `server/migrations/008_bootstrap_sessions.sql`
- Create: `server/internal/bootstrap/service.go`
- Create: `server/internal/bootstrap/service_test.go`
- Modify: `server/internal/setup/service.go`
- Modify: `server/internal/httpserver/server.go`
- Create: `server/internal/httpserver/bootstrap.go`
- Modify: `server/internal/httpserver/auth.go`
- Modify: `server/cmd/transfer-assistant/main.go`

- [ ] Implement cryptographically random 120-second Bootstrap sessions with hashed secret, status, expiry, and instance binding.
- [ ] Add `POST /api/v1/bootstrap/sessions` for uninitialized instances only.
- [ ] Add `POST /api/v1/bootstrap/claim` that atomically validates and consumes a session while creating the Android Master.
- [ ] Add an optional browser token return so the creating Web page can enter the timeline automatically.
- [ ] Preserve `/api/v1/setup/claim` as a manual fallback.
- [ ] Add tests for expiry, replay, concurrent claim, already initialized, and no-secret leakage.

### Task 4: Web Bootstrap UI and v2 QR payloads

**Files:**
- Modify: `web/src/App.tsx`
- Modify: `web/src/pairingPolicy.ts`
- Modify: `web/src/pairingPolicy.test.ts`
- Modify: `web/src/styles.css`
- Modify: `web/src/App.test.tsx` if present

- [ ] Fetch instance info before choosing setup, bootstrap, pairing, or timeline state.
- [ ] Render Bootstrap QR with server URL, instance ID/fingerprint, session expiry, and refresh behavior.
- [ ] Automatically authenticate the current Web browser after successful Bootstrap.
- [ ] Extend Pairing QR to v2 with server URL and instance ID while preserving the current API credential fields.
- [ ] Keep unsafe-origin and rate-limit guards.
- [ ] Add focused tests and run Web tests/build.

### Task 5: Android QR bootstrap flow

**Files:**
- Modify: `android/app/src/main/java/com/transdot/transferassistant/data/PairingPayload.kt`
- Create: `android/app/src/main/java/com/transdot/transferassistant/data/BootstrapRepository.kt`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/data/ServerProfiles.kt`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/data/SecureSessionStore.kt`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/ui/PairingViewModel.kt`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/ui/PairingFlow.kt`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/MainActivity.kt`
- Create/modify tests under `android/app/src/test/java/com/transdot/transferassistant/data/`

- [ ] Parse both Bootstrap v2 and Pairing v1/v2 payloads with strict server URL and credential validation.
- [ ] Add Bootstrap claim repository using the QR server URL, never an existing profile token.
- [ ] Show server URL/fingerprint confirmation before claim.
- [ ] Save a new profile with instance ID after successful Bootstrap; do not silently overwrite a profile when the same URL has a new instance ID.
- [ ] Preserve current Pairing Windows flow and manual setup fallback.
- [ ] Run Android unit tests and assembleDebug.

### Task 6: Documentation and integration verification

**Files:**
- Modify: `README.md`
- Modify: `android/README.md`
- Modify: `docker/README.md`

- [ ] Document the new first-deploy QR flow, normal update command, reset command, and manual fallback.
- [ ] Run Go, Web, and Android test suites.
- [ ] Run Compose config validation and inspect git diff for accidental data deletion or secret exposure.
- [ ] Record any environment-limited checks clearly.
