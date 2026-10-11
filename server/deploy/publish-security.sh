#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
archive=$(realpath "${1:?archive}")
expected=${2:?sha256}
[[ "$archive" == /home/ai/shelest-security-*.tar.gz && "$expected" =~ ^[0-9a-f]{64}$ ]]
test "$(sha256sum "$archive" | cut -d ' ' -f 1)" = "$expected"
target=/opt/shelest/server
test -x "$target/.venv/bin/python"
stamp=$(date -u +%Y%m%d-%H%M%S)
backup=/var/backups/shelest/security-$stamp
install -d -m 700 "$backup"
tar -czf "$backup/code.tar.gz" -C "$target" syncapi requirements.txt
stage=$(mktemp -d /opt/shelest/server/.publish-security.XXXXXX)
trap 'if [[ "$stage" == /opt/shelest/server/.publish-security.* && "$(realpath "$stage")" == "$stage" ]]; then rm -rf -- "$stage"; fi' EXIT
set -a
source /etc/shelest/shelest.env
set +a
"$target/.venv/bin/python" - "$backup/database.sqlite3" "$archive" <<'PY'
import os, sqlite3, sys, tarfile
from pathlib import Path
data=Path(os.environ.get('SHELEST_DATA_DIR','/var/lib/shelest'))
database=Path(os.environ.get('SHELEST_DATABASE',str(data/'shelest.sqlite3')))
with sqlite3.connect(str(database)) as source, sqlite3.connect(sys.argv[1]) as output:
    source.backup(output)
allowed={'requirements.txt','syncapi/models.py','syncapi/views.py','syncapi/urls.py','syncapi/auth.py','syncapi/security.py','syncapi/migrations/0007_account_security.py'}
with tarfile.open(sys.argv[2]) as tar:
    members=tar.getmembers()
    if {m.name for m in members}!=allowed or any(not m.isfile() for m in members):
        raise SystemExit('Unexpected files in server patch')
PY
chmod 600 "$backup/database.sqlite3"
tar -xzf "$archive" -C "$stage" --no-same-owner
"$target/.venv/bin/python" -m pip install 'cryptography>=46,<47'
for file in requirements.txt syncapi/models.py syncapi/views.py syncapi/urls.py syncapi/auth.py syncapi/security.py syncapi/migrations/0007_account_security.py; do
    install -m 644 "$stage/$file" "$target/$file"
done
cd "$target"
if ! .venv/bin/python manage.py check || ! .venv/bin/python manage.py migrate --noinput; then
    tar -xzf "$backup/code.tar.gz" -C "$target"
    echo 'Previous code restored; database backup preserved.' >&2
    exit 1
fi
chown shelest:shelest "${SHELEST_DATABASE:-${SHELEST_DATA_DIR:-/var/lib/shelest}/shelest.sqlite3}"
systemctl restart shelest-api
systemctl is-active shelest-api caddy
echo "Account security published; backup: $backup"
