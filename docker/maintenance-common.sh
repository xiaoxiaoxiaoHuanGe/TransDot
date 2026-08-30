#!/bin/sh

PROJECT_DIR=${TRANSDOT_PROJECT_DIR:-/opt/transdot}
PROJECT_NAME=transdot
SERVICE_NAME=transfer-assistant
VOLUME_NAME=transfer-assistant-data
HEALTH_URL=${TRANSDOT_HEALTH_URL:-http://127.0.0.1:5757/healthz}
LOCK_DIR=${TRANSDOT_MAINTENANCE_LOCK:-/var/lock/transdot-maintenance.lock}

fail() { echo "ERROR: $*" >&2; return 1; }

require_project() {
  if [ ! -f "$PROJECT_DIR/docker-compose.yml" ]; then
    fail "TransDot compose file not found in $PROJECT_DIR"
    return 1
  fi
  cd "$PROJECT_DIR"
}

acquire_lock() {
  if [ -n "${TRANSDOT_LOCK_TOKEN:-}" ] && [ -f "$LOCK_DIR/token" ] && [ "$(cat "$LOCK_DIR/token")" = "$TRANSDOT_LOCK_TOKEN" ]; then
    return 0
  fi
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    fail "another TransDot maintenance operation is running ($LOCK_DIR)"
    return 1
  fi
  LOCK_TOKEN="$$-$(date -u +%Y%m%d%H%M%S)"
  printf '%s\n' "$LOCK_TOKEN" >"$LOCK_DIR/token"
  chmod 600 "$LOCK_DIR/token" 2>/dev/null || true
  LOCK_OWNED=1
}

release_lock() {
  if [ "${LOCK_OWNED:-0}" = "1" ]; then
    rm -f -- "$LOCK_DIR/token"
    rmdir "$LOCK_DIR" 2>/dev/null || true
  fi
}

service_is_running() {
  container_id=$(docker compose -p "$PROJECT_NAME" ps -q "$SERVICE_NAME")
  [ -n "$container_id" ] && [ "$(docker inspect -f '{{.State.Running}}' "$container_id")" = "true" ]
}

current_image() {
  image=$(docker compose -p "$PROJECT_NAME" images -q "$SERVICE_NAME" | sed -n '1p')
  if [ -z "$image" ]; then fail "TransDot runtime image is not available"; return 1; fi
  printf '%s\n' "$image"
}

stop_service() { docker compose -p "$PROJECT_NAME" stop -t 30 "$SERVICE_NAME"; }
start_service() { docker compose -p "$PROJECT_NAME" up -d --no-deps "$SERVICE_NAME"; }

wait_for_health() {
  attempt=1
  while [ "$attempt" -le 30 ]; do
    if curl --fail --silent --show-error "$HEALTH_URL" >/dev/null 2>&1; then return 0; fi
    attempt=$((attempt + 1))
    sleep 2
  done
  fail "TransDot did not become healthy: $HEALTH_URL"
}

verify_fixed_volume() {
  if ! resolved=$(docker volume inspect -f '{{.Name}}' "$VOLUME_NAME" 2>/dev/null); then
    fail "required volume $VOLUME_NAME does not exist"
    return 1
  fi
  if [ "$resolved" != "$VOLUME_NAME" ]; then fail "refusing unexpected volume: $resolved"; return 1; fi
}

json_field() {
  key=$1
  value=$(printf '%s' "$2" | sed -n "s/.*\"$key\":\([^,}]*\).*/\1/p")
  if [ -z "$value" ]; then fail "missing JSON field $key"; return 1; fi
  printf '%s\n' "$value"
}
