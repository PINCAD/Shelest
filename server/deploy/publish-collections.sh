#!/usr/bin/env bash
# Additive, backwards-compatible metadata patch. Never replaces databases or user files.
set -euo pipefail
test "$(id -u)" = 0
archive=$(realpath "${1:?archive}"); expected=${2:?sha256}
previous_views=${3:?previous views sha256}; previous_personal=${4:?previous personal sha256}
[[ "$archive" == /home/ai/shelest-collections-*.tar.gz && "$expected" =~ ^[0-9a-f]{64}$ ]]
test "$(sha256sum "$archive" | cut -d ' ' -f 1)" = "$expected"
target=/opt/shelest/server
test -x "$target/.venv/bin/python"
test "$(sha256sum "$target/syncapi/views.py" | cut -d ' ' -f 1)" = "$previous_views"
test "$(sha256sum "$target/syncapi/personal.py" | cut -d ' ' -f 1)" = "$previous_personal"
stamp=$(date -u +%Y%m%d-%H%M%S)
backup=/var/backups/shelest/collections-$stamp
install -d -m 700 "$backup"
tar -czf "$backup/code.tar.gz" -C "$target" syncapi/personal.py syncapi/views.py
stage=$(mktemp -d /opt/shelest/server/.publish-collections.XXXXXX)
trap 'if [[ "$stage" == /opt/shelest/server/.publish-collections.* && "$(realpath "$stage")" == "$stage" ]]; then rm -rf -- "$stage"; fi' EXIT
set -a
source /etc/shelest/shelest.env
set +a
"$target/.venv/bin/python" - "$archive" "$backup/database.sqlite3" <<'PY'
import os, sqlite3, sys, tarfile
from pathlib import Path
with tarfile.open(sys.argv[1]) as tar:
    members=tar.getmembers()
    if {m.name for m in members} != {'syncapi/personal.py','syncapi/views.py','deploy/check_collections.py'} or any(not m.isfile() for m in members):
        raise SystemExit('Unexpected collection patch contents')
database=Path(os.environ.get('SHELEST_DATABASE',str(Path(os.environ.get('SHELEST_DATA_DIR','/var/lib/shelest'))/'shelest.sqlite3')))
with sqlite3.connect(str(database)) as source, sqlite3.connect(sys.argv[2]) as output:
    source.backup(output)
PY
chmod 600 "$backup/database.sqlite3"
tar -xzf "$archive" -C "$stage" --no-same-owner
for file in syncapi/personal.py syncapi/views.py deploy/check_collections.py; do
    install -m 644 "$stage/$file" "$target/$file"
done
cd "$target"
rollback() {
    tar -xzf "$backup/code.tar.gz" -C "$target"
    systemctl restart shelest-api
    echo "Collection code restored. Database was not replaced; backup: $backup" >&2
}
if ! .venv/bin/python manage.py check; then rollback; exit 1; fi
if ! systemctl restart shelest-api || ! systemctl is-active --quiet shelest-api; then rollback; exit 1; fi
systemctl is-active shelest-api caddy
printf 'Collection API published; code/database rollback backup: %s\n' "$backup"
