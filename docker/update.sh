#!/bin/sh
set -eu

PROJECT_DIR=${TRANSDOT_PROJECT_DIR:-/opt/transdot}
PROJECT_NAME=transdot
HEALTH_URL=${TRANSDOT_HEALTH_URL:-http://127.0.0.1:5757/healthz}

if [ ! -f "$PROJECT_DIR/docker-compose.yml" ]; then
  echo "TransDot compose file not found in $PROJECT_DIR" >&2
  exit 1
fi

cd "$PROJECT_DIR"
git pull --ff-only
# V1 used the Compose project name "transfer-assistant". Stop it once so the
# fixed "transdot" project can take over port 5757 while retaining the named volume.
if docker ps -a --filter "label=com.docker.compose.project=transfer-assistant" --format '{{.ID}}' | grep -q .; then
  docker compose -p transfer-assistant down --remove-orphans
fi
docker compose -p "$PROJECT_NAME" up -d --build --remove-orphans

attempt=1
while [ "$attempt" -le 12 ]; do
  if curl --fail --silent --show-error "$HEALTH_URL" >/dev/null; then
    echo "TransDot update complete."
    exit 0
  fi
  attempt=$((attempt + 1))
  sleep 2
done

echo "TransDot did not become healthy: $HEALTH_URL" >&2
docker compose -p "$PROJECT_NAME" ps >&2
exit 1
