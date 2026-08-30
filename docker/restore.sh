#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
. "$SCRIPT_DIR/maintenance-common.sh"

usage() {
  echo "Usage: $0 BACKUP_FILE RESTORE" >&2
  echo "RESTORE must be the final standalone argument." >&2
  exit 2
}
[ "$#" -eq 2 ] && [ "$2" = "RESTORE" ] || usage
INPUT_ARCHIVE=$1

LOCK_OWNED=0
TEMP_DIR=""
STAGE_VOLUME=""
KEEP_STAGE=0
WAS_RUNNING=0
SERVICE_STOPPED_BY_RESTORE=0
PRODUCTION_TOUCHED=0
cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  if [ "$KEEP_STAGE" = "0" ] && [ -n "$STAGE_VOLUME" ]; then docker volume rm -f "$STAGE_VOLUME" >/dev/null 2>&1 || true; fi
  [ -z "$TEMP_DIR" ] || rm -rf -- "$TEMP_DIR"
  if [ "$status" -ne 0 ] && [ "$PRODUCTION_TOUCHED" = "0" ] && [ "$WAS_RUNNING" = "1" ] && [ "$SERVICE_STOPPED_BY_RESTORE" = "1" ]; then
    start_service >/dev/null 2>&1 || true
    wait_for_health >/dev/null 2>&1 || true
  fi
  release_lock
  exit "$status"
}
trap cleanup EXIT HUP INT TERM

require_project
[ -f "$INPUT_ARCHIVE" ] && [ ! -L "$INPUT_ARCHIVE" ] || fail "backup must be a regular non-symbolic-link file"
ARCHIVE=$(CDPATH= cd -- "$(dirname -- "$INPUT_ARCHIVE")" && pwd -P)/$(basename -- "$INPUT_ARCHIVE")
[ -f "$ARCHIVE.sha256" ] && [ ! -L "$ARCHIVE.sha256" ] || fail "missing adjacent checksum file"
EXPECTED=$(sed -n '1{s/[[:space:]].*//;p;}' "$ARCHIVE.sha256")
case "$EXPECTED" in *[!0-9A-Fa-f]*|'') fail "invalid checksum file";; esac
[ "${#EXPECTED}" -eq 64 ] || fail "invalid checksum length"
ACTUAL=$(sha256sum "$ARCHIVE" | awk '{print $1}')
[ "$ACTUAL" = "$EXPECTED" ] || fail "BACKUP_CHECKSUM_MISMATCH"

TEMP_DIR=$(mktemp -d)
chmod 700 "$TEMP_DIR"
PLAIN_ARCHIVE=$ARCHIVE
case "$ARCHIVE" in
  *.age)
    command -v age >/dev/null 2>&1 || fail "encrypted backup requires age"
    [ -n "${AGE_IDENTITY_FILE:-}" ] || fail "encrypted backup requires AGE_IDENTITY_FILE"
    [ -f "$AGE_IDENTITY_FILE" ] || fail "AGE_IDENTITY_FILE is not a regular file"
    age -d -i "$AGE_IDENTITY_FILE" -o "$TEMP_DIR/archive.tar.gz" "$ARCHIVE"
    PLAIN_ARCHIVE="$TEMP_DIR/archive.tar.gz"
    ;;
esac

tar -tzf "$PLAIN_ARCHIVE" >"$TEMP_DIR/entries"
awk '
  /^\// { exit 1 }
  /(^|\/)\.\.($|\/)/ { exit 1 }
  /^data\/tmp(\/|$)/ { exit 1 }
  !($0 == "manifest.json" || $0 == "data" || $0 ~ /^data\//) { exit 1 }
' "$TEMP_DIR/entries" || fail "BACKUP_UNSAFE_PATH"
if sort "$TEMP_DIR/entries" | uniq -d | grep -q .; then fail "backup contains duplicate archive paths"; fi
[ "$(grep -c '^data/database/transfer.db$' "$TEMP_DIR/entries")" -eq 1 ] || fail "backup is missing data/database/transfer.db"
grep -E '^data/files/?$|^data/files/' "$TEMP_DIR/entries" >/dev/null || fail "backup is missing data/files"
grep -E '^data/thumbs/?$|^data/thumbs/' "$TEMP_DIR/entries" >/dev/null || fail "backup is missing data/thumbs"
tar -tvzf "$PLAIN_ARCHIVE" >"$TEMP_DIR/verbose"
awk 'substr($0,1,1) ~ /[lhbcps]/ { exit 1 }' "$TEMP_DIR/verbose" || fail "BACKUP_UNSAFE_ENTRY_TYPE"
[ "$(grep -c '^manifest.json$' "$TEMP_DIR/entries")" -eq 1 ] || fail "backup must contain exactly one manifest.json"
tar -xOzf "$PLAIN_ARCHIVE" manifest.json >"$TEMP_DIR/manifest.json"
MANIFEST=$(tr -d '\n' <"$TEMP_DIR/manifest.json")
[ "$(json_field format_version "$MANIFEST")" = "1" ] || fail "unsupported backup format"
[ "$(json_field data_layout "$MANIFEST")" = '"transdot-data-v1"' ] || fail "unsupported backup data layout"
SCHEMA=$(json_field schema_version "$MANIFEST")
case "$SCHEMA" in ''|*[!0-9]*) fail "invalid backup schema";; esac
[ "$SCHEMA" -le 11 ] || fail "BACKUP_SCHEMA_TOO_NEW"
MANIFEST_INSTANCE=$(json_field instance_id "$MANIFEST")
MANIFEST_FINGERPRINT=$(json_field instance_fingerprint "$MANIFEST")

acquire_lock
verify_fixed_volume
IMAGE=$(current_image)
if service_is_running; then WAS_RUNNING=1; fi
RANDOM_ID="$(date -u +%Y%m%d%H%M%S)-$$"
STAGE_CANDIDATE="transdot-restore-stage-$RANDOM_ID"
if docker volume inspect "$STAGE_CANDIDATE" >/dev/null 2>&1; then fail "temporary restore volume already exists"; fi
docker volume create "$STAGE_CANDIDATE" >/dev/null
STAGE_VOLUME=$STAGE_CANDIDATE
docker run --rm --user 0 --entrypoint /bin/sh -v "$STAGE_VOLUME:/app/data" -v "$PLAIN_ARCHIVE:/backup/archive.tar.gz:ro" \
  "$IMAGE" -c 'tar -xzf /backup/archive.tar.gz -C /app/data --strip-components=1 && chown -R 10001:10001 /app/data'
STAGE_REPORT=$(docker run --rm --user 0 --entrypoint /app/transdot-maintenance -v "$STAGE_VOLUME:/app/data:ro" "$IMAGE" verify --data-dir /app/data --max-schema 11 --json)
[ "$(json_field schema_version "$STAGE_REPORT")" = "$SCHEMA" ] || fail "manifest schema does not match staged database"
[ "$(json_field instance_id "$STAGE_REPORT")" = "$MANIFEST_INSTANCE" ] || fail "manifest instance ID does not match staged database"
[ "$(json_field instance_fingerprint "$STAGE_REPORT")" = "$MANIFEST_FINGERPRINT" ] || fail "manifest fingerprint does not match staged database"

SAFETY_DIR=${TRANSDOT_PRE_RESTORE_DIR:-$(dirname -- "$ARCHIVE")}
SAFETY_OUTPUT=$(AGE_RECIPIENT= TRANSDOT_LOCK_TOKEN="$LOCK_TOKEN" TRANSDOT_BACKUP_PREFIX=pre-restore sh "$SCRIPT_DIR/backup.sh" "$SAFETY_DIR")
SAFETY_ARCHIVE=$(printf '%s\n' "$SAFETY_OUTPUT" | sed -n 's/^Backup complete: //p' | tail -n 1)
[ -f "$SAFETY_ARCHIVE" ] || fail "pre-restore safety backup was not created"

if service_is_running; then SERVICE_STOPPED_BY_RESTORE=1; stop_service; fi
verify_fixed_volume
copy_stage_to_production() {
  docker run --rm --user 0 --entrypoint /bin/sh -v "$STAGE_VOLUME:/source:ro" -v "$VOLUME_NAME:/destination" "$IMAGE" -c '
    find /destination -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
    cp -a /source/. /destination/
    chown -R 10001:10001 /destination
  '
}
restore_safety_backup() {
  rollback=$SAFETY_ARCHIVE
  case "$rollback" in *.age) return 1;; esac
  docker run --rm --user 0 --entrypoint /bin/sh -v "$VOLUME_NAME:/destination" -v "$rollback:/backup/archive.tar.gz:ro" "$IMAGE" -c '
    find /destination -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
    tar -xzf /backup/archive.tar.gz -C /destination --strip-components=1
    chown -R 10001:10001 /destination
  '
}

RESTORE_OK=1
PRODUCTION_TOUCHED=1
if ! copy_stage_to_production; then RESTORE_OK=0; fi
if [ "$RESTORE_OK" = "1" ] && { ! start_service || ! wait_for_health; }; then RESTORE_OK=0; fi
if [ "$RESTORE_OK" = "1" ]; then
  stop_service
  if ! docker run --rm --user 0 --entrypoint /app/transdot-maintenance -v "$VOLUME_NAME:/app/data:ro" "$IMAGE" verify --data-dir /app/data --max-schema 11 --json >/dev/null; then RESTORE_OK=0; fi
  if [ "$RESTORE_OK" = "1" ] && [ "$WAS_RUNNING" = "1" ]; then start_service; wait_for_health || RESTORE_OK=0; fi
fi

if [ "$RESTORE_OK" != "1" ]; then
  docker compose -p "$PROJECT_NAME" stop -t 30 "$SERVICE_NAME" >/dev/null 2>&1 || true
  if restore_safety_backup && start_service && wait_for_health; then
    [ "$WAS_RUNNING" = "1" ] || stop_service
    fail "target restore failed; original data was restored automatically"
  fi
  KEEP_STAGE=1
  fail "target restore failed and automatic rollback failed; preserve $SAFETY_ARCHIVE and $STAGE_VOLUME for recovery"
fi

echo "Restore complete. Instance fingerprint: $MANIFEST_FINGERPRINT"
echo "Pre-restore safety backup: $SAFETY_ARCHIVE"
