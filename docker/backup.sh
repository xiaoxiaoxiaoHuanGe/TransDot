#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
. "$SCRIPT_DIR/maintenance-common.sh"

usage() { echo "Usage: $0 OUTPUT_DIR [--keep COUNT]" >&2; exit 2; }
[ "$#" -ge 1 ] || usage
OUTPUT_INPUT=$1
shift
KEEP=""
if [ "$#" -gt 0 ]; then
  [ "$#" -eq 2 ] && [ "$1" = "--keep" ] || usage
  KEEP=$2
  case "$KEEP" in ''|*[!0-9]*) usage;; esac
  [ "$KEEP" -ge 1 ] || usage
fi

LOCK_OWNED=0
WAS_RUNNING=0
SERVICE_STOPPED=0
ARCHIVE_TMP=""
CHECKSUM_TMP=""
TEMP_DIR=""
cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  [ -z "$ARCHIVE_TMP" ] || rm -f -- "$ARCHIVE_TMP"
  [ -z "$CHECKSUM_TMP" ] || rm -f -- "$CHECKSUM_TMP"
  [ -z "$TEMP_DIR" ] || rm -rf -- "$TEMP_DIR"
  if [ "$SERVICE_STOPPED" = "1" ] && [ "$WAS_RUNNING" = "1" ]; then
    start_service >/dev/null 2>&1 || true
    wait_for_health >/dev/null 2>&1 || true
  fi
  release_lock
  exit "$status"
}
trap cleanup EXIT HUP INT TERM

require_project
mkdir -p -- "$OUTPUT_INPUT"
OUTPUT_DIR=$(CDPATH= cd -- "$OUTPUT_INPUT" && pwd -P)
PROJECT_REAL=$(CDPATH= cd -- "$PROJECT_DIR" && pwd -P)
case "$OUTPUT_DIR/" in "$PROJECT_REAL/"*) fail "backup directory must not be inside the project directory";; esac
chmod 700 "$OUTPUT_DIR" 2>/dev/null || true
acquire_lock
verify_fixed_volume
VOLUME_MOUNT=$(docker volume inspect -f '{{.Mountpoint}}' "$VOLUME_NAME")
case "$OUTPUT_DIR/" in "$VOLUME_MOUNT/"*) fail "backup directory must not be inside the production data volume";; esac
IMAGE=$(current_image)
if service_is_running; then WAS_RUNNING=1; SERVICE_STOPPED=1; stop_service; fi

VERIFY_JSON=$(docker run --rm --user 0 --entrypoint /app/transdot-maintenance -v "$VOLUME_NAME:/app/data:ro" "$IMAGE" verify --data-dir /app/data --max-schema 11 --json)
INSPECT_JSON=$(docker run --rm --user 0 --entrypoint /app/transdot-maintenance -v "$VOLUME_NAME:/app/data:ro" "$IMAGE" inspect --data-dir /app/data --json)
SCHEMA=$(json_field schema_version "$INSPECT_JSON")
INSTANCE_ID=$(json_field instance_id "$INSPECT_JSON")
FINGERPRINT=$(json_field instance_fingerprint "$INSPECT_JSON")
APP_VERSION=$(json_field app_version "$INSPECT_JSON")
GIT_COMMIT=$(json_field git_commit "$INSPECT_JSON")
CREATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
FINGERPRINT_NAME=$(printf '%s' "$FINGERPRINT" | tr -d '"-' | cut -c1-8)
PREFIX=${TRANSDOT_BACKUP_PREFIX:-transdot-backup}
case "$PREFIX" in transdot-backup|pre-restore) :;; *) fail "invalid internal backup prefix";; esac
BASE="$PREFIX-$STAMP-$FINGERPRINT_NAME.tar.gz"
TEMP_DIR=$(mktemp -d)
chmod 700 "$TEMP_DIR"
printf '{\n  "format_version": 1,\n  "created_at": "%s",\n  "app_version": %s,\n  "git_commit": %s,\n  "instance_id": %s,\n  "instance_fingerprint": %s,\n  "schema_version": %s,\n  "data_layout": "transdot-data-v1",\n  "includes_environment": false\n}\n' \
  "$CREATED_AT" "$APP_VERSION" "$GIT_COMMIT" "$INSTANCE_ID" "$FINGERPRINT" "$SCHEMA" >"$TEMP_DIR/manifest.json"
ARCHIVE_TMP="$OUTPUT_DIR/$BASE.tmp"
docker run --rm --user 0 --entrypoint /bin/sh \
  -v "$VOLUME_NAME:/snapshot/data:ro" -v "$TEMP_DIR/manifest.json:/snapshot/manifest.json:ro" -v "$OUTPUT_DIR:/backup" \
  "$IMAGE" -c 'tar -czf "/backup/$1" -C /snapshot manifest.json data/database data/files data/thumbs' sh "$BASE.tmp"

FINAL_PATH="$OUTPUT_DIR/$BASE"
if [ -n "${AGE_RECIPIENT:-}" ]; then
  command -v age >/dev/null 2>&1 || fail "AGE_RECIPIENT is set but age is not installed"
  ENCRYPTED_TMP="$FINAL_PATH.age.tmp"
  age -r "$AGE_RECIPIENT" -o "$ENCRYPTED_TMP" "$ARCHIVE_TMP"
  rm -f -- "$ARCHIVE_TMP"
  ARCHIVE_TMP="$ENCRYPTED_TMP"
  FINAL_PATH="$FINAL_PATH.age"
fi
HASH=$(sha256sum "$ARCHIVE_TMP" | awk '{print $1}')
CHECKSUM_TMP="$OUTPUT_DIR/.$(basename "$FINAL_PATH").sha256.tmp"
printf '%s  %s\n' "$HASH" "$(basename "$FINAL_PATH")" >"$CHECKSUM_TMP"
mv -- "$ARCHIVE_TMP" "$FINAL_PATH"
ARCHIVE_TMP=""
mv -- "$CHECKSUM_TMP" "$FINAL_PATH.sha256"
CHECKSUM_TMP=""
chmod 600 "$FINAL_PATH"
chmod 600 "$FINAL_PATH.sha256"

if [ -n "$KEEP" ]; then
  index=0
  find "$OUTPUT_DIR" -maxdepth 1 -type f \( -name 'transdot-backup-*.tar.gz' -o -name 'transdot-backup-*.tar.gz.age' \) -print | sort -r | while IFS= read -r old; do
    index=$((index + 1))
    if [ "$index" -gt "$KEEP" ]; then rm -f -- "$old" "$old.sha256"; fi
  done
fi

if [ "$WAS_RUNNING" = "1" ]; then start_service; wait_for_health; SERVICE_STOPPED=0; fi
echo "Backup complete: $FINAL_PATH"
echo "This archive contains private TransDot data; keep it encrypted and access-controlled."
