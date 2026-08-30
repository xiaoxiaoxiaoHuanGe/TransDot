#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd -P)
PATH="$ROOT/docker/tests/bin:$PATH"
export PATH
TMP=$(mktemp -d)
trap 'rm -rf -- "$TMP"' EXIT HUP INT TERM
mkdir -p "$TMP/project"
cp "$ROOT/docker-compose.yml" "$TMP/project/docker-compose.yml"

expect_failure() {
  expected=$1
  shift
  output=$({ "$@"; } 2>&1) && {
    echo "expected failure containing: $expected" >&2
    exit 1
  }
  printf '%s' "$output" | grep -F "$expected" >/dev/null || {
    echo "missing expected error: $expected" >&2
    echo "$output" >&2
    exit 1
  }
}

expect_failure "backup directory must not be inside the project directory" \
  env TRANSDOT_PROJECT_DIR="$TMP/project" sh "$ROOT/docker/backup.sh" "$TMP/project/backups"

PYTHON=${PYTHON:-python3}
"$PYTHON" - "$TMP/bad.tar.gz" <<'PY'
import io, sys, tarfile
with tarfile.open(sys.argv[1], "w:gz") as archive:
    value = b"{}"
    info = tarfile.TarInfo("../manifest.json")
    info.size = len(value)
    archive.addfile(info, io.BytesIO(value))
PY
hash=$(sha256sum "$TMP/bad.tar.gz" | awk '{print $1}')
printf '%s  bad.tar.gz\n' "$hash" >"$TMP/bad.tar.gz.sha256"
expect_failure "BACKUP_UNSAFE_PATH" \
  env TRANSDOT_PROJECT_DIR="$TMP/project" sh "$ROOT/docker/restore.sh" "$TMP/bad.tar.gz" RESTORE

printf '%064d  bad.tar.gz\n' 0 >"$TMP/bad.tar.gz.sha256"
expect_failure "BACKUP_CHECKSUM_MISMATCH" \
  env TRANSDOT_PROJECT_DIR="$TMP/project" sh "$ROOT/docker/restore.sh" "$TMP/bad.tar.gz" RESTORE

TRANSDOT_MAINTENANCE_LOCK="$TMP/maintenance.lock"
export TRANSDOT_MAINTENANCE_LOCK
. "$ROOT/docker/maintenance-common.sh"
LOCK_OWNED=0
acquire_lock
expect_failure "another TransDot maintenance operation is running" \
  acquire_lock
release_lock

echo "maintenance script boundary tests passed"
