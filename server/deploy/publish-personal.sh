#!/usr/bin/env bash
set -euo pipefail
archive=${1:?archive}; expected=${2:?sha256}
[[ "$archive" == /home/ai/shelest-personal-*.tar.gz && "$expected" =~ ^[0-9a-f]{64}$ ]]
test "$(sha256sum "$archive" | cut -d ' ' -f 1)" = "$expected"
target=/opt/shelest/server
test -x "$target/.venv/bin/python"
stamp=$(date -u +%Y%m%d-%H%M%S)
backup=/var/backups/shelest/personal-$stamp
mkdir -p "$backup"
chmod 700 "$backup"
tar -czf "$backup/code.tar.gz" -C "$target" syncapi
stage=$(mktemp -d /opt/shelest/server/.publish-personal.XXXXXX)
trap 'if [[ "$stage" == /opt/shelest/server/.publish-personal.* ]]; then rm -rf -- "$stage"; fi' EXIT
set -a
source /etc/shelest/shelest.env
set +a
# SQLite backup API captures a consistent database while the service is running.
"$target/.venv/bin/python" - "$backup/database.sqlite3" <<'PY'
import os, sqlite3, sys
from pathlib import Path
data=Path(os.environ.get('SHELEST_DATA_DIR','/var/lib/shelest'))
database=Path(os.environ.get('SHELEST_DATABASE',str(data/'shelest.sqlite3')))
with sqlite3.connect(str(database)) as source, sqlite3.connect(sys.argv[1]) as output:
    source.backup(output)
PY
chmod 600 "$backup/database.sqlite3"
"$target/.venv/bin/python" - "$archive" "$stage" <<'PY'
import sys, tarfile
allowed={'syncapi/models.py','syncapi/views.py','syncapi/urls.py','syncapi/personal.py','syncapi/migrations/0005_personal_sync.py','syncapi/migrations/0006_preserve_word_spelling.py'}
with tarfile.open(sys.argv[1]) as tar:
    members=tar.getmembers()
    if {m.name for m in members}!=allowed or any(not m.isfile() for m in members):
        raise SystemExit('Unexpected files in server patch')
PY
tar -xzf "$archive" -C "$stage" --no-same-owner
for file in syncapi/models.py syncapi/views.py syncapi/urls.py syncapi/personal.py syncapi/migrations/0005_personal_sync.py syncapi/migrations/0006_preserve_word_spelling.py; do
    install -m 644 "$stage/$file" "$target/$file"
done
cd "$target"
if ! .venv/bin/python manage.py check || ! .venv/bin/python manage.py migrate --noinput; then
    tar -xzf "$backup/code.tar.gz" -C "$target"
    echo 'Code restored; additive tables and database backup preserved.' >&2
    exit 1
fi
chown shelest:shelest "${SHELEST_DATABASE:-${SHELEST_DATA_DIR:-/var/lib/shelest}/shelest.sqlite3}"
systemctl restart shelest-api
systemctl is-active shelest-api caddy
echo "Personal sync published; code and database backup: $backup"
