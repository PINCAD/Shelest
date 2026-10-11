#!/bin/bash
# Static-only update: leaves API, accounts, uploads and signing keys untouched.
set -euo pipefail
test "$(id -u)" = 0 || { echo 'Run with sudo'; exit 1; }
archive=$(realpath "${1:?Web archive required}")
expected=${2:?Expected SHA-256 required}
test -f "$archive"
actual=$(sha256sum "$archive" | cut -d ' ' -f 1)
test "$actual" = "$expected" || { echo 'Archive hash mismatch'; exit 1; }
target=/opt/shelest/web/dist
test "$(realpath "$target")" = "$target" || { echo 'Unexpected web target'; exit 1; }
test -f "$target/index.html"
while IFS= read -r entry; do
    case "$entry" in
        ./|./index.html|./logo.jpg|./assets/|./assets/*|./downloads/|./downloads/*) ;;
        *) echo 'Unexpected archive entry'; exit 1 ;;
    esac
    case "$entry" in *../*|*/..|/*) echo 'Unsafe archive path'; exit 1 ;; esac
done < <(tar -tzf "$archive")
stamp=$(date -u +%Y%m%d-%H%M%S)
install -d -m 700 /var/backups/shelest
tar -czf "/var/backups/shelest/web-before-shared-reader-$stamp.tar.gz" -C /opt/shelest/web dist
stage=$(mktemp -d /opt/shelest/web/.publish-web.XXXXXX)
trap 'if [[ "$stage" == /opt/shelest/web/.publish-web.* && "$(realpath "$stage")" == "$stage" ]]; then rm -rf -- "$stage"; fi' EXIT
tar -xzf "$archive" -C "$stage" --no-same-owner
test -f "$stage/index.html"
test -f "$stage/downloads/latest.json"
test -f "$stage/downloads/shelest-latest.apk"
test -z "$(find "$stage" -type l -print -quit)" || { echo 'Symlinks are not web assets'; exit 1; }
# Preserve old hashed assets for clients which still have the previous index open.
cp -a "$stage/assets/." "$target/assets/"
install -m 644 "$stage/logo.jpg" "$target/logo.jpg"
install -d -m 755 "$target/downloads"
cp -a "$stage/downloads/." "$target/downloads/"
install -m 644 "$stage/index.html" "$target/index.html.new-shared-reader"
mv "$target/index.html.new-shared-reader" "$target/index.html"
chown -R root:root "$target"
find "$target" -type d -exec chmod 755 {} \;
find "$target" -type f -exec chmod 644 {} \;
sha256sum "$target/index.html"
systemctl is-active caddy shelest-api
printf 'Static web published; rollback archive: /var/backups/shelest/web-before-shared-reader-%s.tar.gz\n' "$stamp"
