#!/bin/bash
set -euo pipefail
if [ "$(id -u)" != 0 ]; then echo 'Run using sudo bash install.sh ARCHIVE'; exit 1; fi
archive=$(realpath "${1:?Release archive required}")
test -f "$archive"
apt-get update -qq
if ! command -v caddy >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gnupg
    curl -fsSL --max-time 60 https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -fsSL --max-time 60 https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt -o /etc/apt/sources.list.d/caddy-stable.list
    chmod 644 /usr/share/keyrings/caddy-stable-archive-keyring.gpg /etc/apt/sources.list.d/caddy-stable.list
    apt-get update -qq
fi
DEBIAN_FRONTEND=noninteractive apt-get install -y python3-venv caddy
id shelest >/dev/null 2>&1 || useradd --system --home /var/lib/shelest --shell /usr/sbin/nologin shelest
install -d -m 755 /opt/shelest
install -d -m 700 -o shelest -g shelest /var/lib/shelest
install -d -m 700 /etc/shelest
stamp=$(date -u +%Y%m%d-%H%M%S)
systemctl stop shelest-api.service 2>/dev/null || true
# Preserve the previous code/config and persistent data before an upgrade.
if [ -d /opt/shelest/server ]; then
    install -d -m 700 /var/backups/shelest
    # An update does not modify immutable book files: do not duplicate gigabytes of books.
    if [ -f /var/lib/shelest/shelest.sqlite3 ]; then
        cp -a /var/lib/shelest/shelest.sqlite3 "/var/backups/shelest/database-$stamp.sqlite3"
    fi
    tar --exclude=.venv --exclude=__pycache__ -czf "/var/backups/shelest/code-$stamp.tar.gz" -C /opt shelest
fi
if [ -f /etc/caddy/Caddyfile ]; then
    cp -a /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.backup-$stamp"
fi
tar -xzf "$archive" -C /opt/shelest --no-same-owner
chown -R root:root /opt/shelest
chmod -R a+rX /opt/shelest/web/dist
python3 -m venv /opt/shelest/server/.venv
/opt/shelest/server/.venv/bin/pip install -r /opt/shelest/server/requirements.txt
if [ ! -f /etc/shelest/shelest.env ]; then
    umask 077
    secret=$(python3 -c 'import secrets; print(secrets.token_urlsafe(64))')
    printf '%s\n' "SHELEST_SECRET_KEY=$secret" \
        'SHELEST_DEBUG=false' \
        'SHELEST_ALLOWED_HOSTS=api.shelest-book.ru,shelest-book.ru' \
        'SHELEST_FORCE_HTTPS=true' \
        'SHELEST_DATA_DIR=/var/lib/shelest' \
        'SHELEST_GOOGLE_CLIENT_IDS=710173618689-0uaan7qturrea27viom0ht84pdlkcja4.apps.googleusercontent.com' > /etc/shelest/shelest.env
    unset secret
fi
chmod 600 /etc/shelest/shelest.env
if grep -q '^SHELEST_GOOGLE_CLIENT_IDS=$' /etc/shelest/shelest.env; then
    sed -i 's/^SHELEST_GOOGLE_CLIENT_IDS=$/SHELEST_GOOGLE_CLIENT_IDS=710173618689-0uaan7qturrea27viom0ht84pdlkcja4.apps.googleusercontent.com/' /etc/shelest/shelest.env
fi
set -a
source /etc/shelest/shelest.env
set +a
cd /opt/shelest/server
.venv/bin/python manage.py migrate --noinput
.venv/bin/python manage.py collectstatic --noinput
chown -R shelest:shelest /var/lib/shelest
install -m 644 deploy/shelest-api.service /etc/systemd/system/shelest-api.service
caddy validate --config deploy/Caddyfile --adapter caddyfile
install -m 644 deploy/Caddyfile /etc/caddy/Caddyfile
systemctl daemon-reload
systemctl enable --now shelest-api.service
systemctl enable --now caddy
systemctl reload caddy
systemctl is-active shelest-api.service caddy.service
