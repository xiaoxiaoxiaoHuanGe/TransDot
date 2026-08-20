#!/bin/sh
set -eu

PROJECT_DIR=${TRANSDOT_PROJECT_DIR:-/opt/transdot}
PROJECT_NAME=transdot
VOLUME_NAME=transfer-assistant-data
HEALTH_URL=${TRANSDOT_HEALTH_URL:-http://127.0.0.1:5757/healthz}

if [ "${1:-}" != "RESET" ]; then
  echo "Usage: $0 RESET" >&2
  echo "This permanently deletes TransDot messages, files, and device credentials." >&2
  exit 2
fi
if [ ! -f "$PROJECT_DIR/docker-compose.yml" ]; then
  echo "TransDot compose file not found in $PROJECT_DIR" >&2
  exit 1
fi

cd "$PROJECT_DIR"
if docker ps -a --filter "label=com.docker.compose.project=transfer-assistant" --format '{{.ID}}' | grep -q .; then
  docker compose -p transfer-assistant down --remove-orphans
fi
docker compose -p "$PROJECT_NAME" down
if docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1; then
  docker volume rm "$VOLUME_NAME"
fi
docker compose -p "$PROJECT_NAME" up -d --build --remove-orphans

attempt=1
while [ "$attempt" -le 12 ]; do
  if curl --fail --silent --show-error "$HEALTH_URL" >/dev/null; then
    echo "TransDot reset complete. Open the Web page and scan the new bootstrap QR code."
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 2
done

echo "TransDot did not become healthy after reset: $HEALTH_URL" >&2
exit 1
