"""Temporary-account smoke test against public HTTPS. Never prints credentials."""
import hashlib
import os
import secrets
import time
import uuid

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")
import django
django.setup()
import requests
from syncapi.models import User
from syncapi.storage import delete_book_file

base = "https://shelest-book.ru/api/v1/"
login = "check." + uuid.uuid4().hex[:20]
password = secrets.token_urlsafe(32)
user = None


def checked(response):
    assert response.status_code == 200, f"Unexpected HTTP {response.status_code}"
    return response.json()


try:
    mobile = requests.Session()
    registered = checked(mobile.post(base + "auth/register", json={"login": login, "password": password}, timeout=15))
    user = User.objects.get(pk=registered["account"]["id"], username=login)
    assert user.check_password(password) and user.password != password
    mobile.headers["Authorization"] = "Bearer " + registered["accessToken"]
    user.is_premium = True
    user.storage_quota_bytes = 1024 * 1024
    user.save(update_fields=("is_premium", "storage_quota_bytes"))
    content = "Synthetic cross-device reading fixture.\nSecond line.\n".encode()
    sha = hashlib.sha256(content).hexdigest()
    now = int(time.time() * 1000)
    anchor = "Syntheticcross-devicereadingfixture."
    record = {"sha256": sha, "title": "Temporary sync check", "author": "", "fileType": "TXT", "size": len(content),
              "deviceId": "phone-check", "position": .2, "pageIndex": 3, "updatedAt": now,
              "readingPlace": {"platform": "android", "segment": 0, "page": 3, "character": 0, "layout": "test", "scrollY": 0, "anchor": anchor}}
    checked(mobile.put(base + "sync/books", json={"books": [record], "since": 0}, timeout=15))
    checked(mobile.put(base + f"sync/books/{sha}/file", data=content, headers={"Content-Type": "application/octet-stream"}, timeout=15))
    web = requests.Session()
    web.headers["Origin"] = "https://shelest-book.ru"
    config = checked(web.get(base + "auth/config", timeout=15))
    result = checked(web.post(base + "auth/web/password", json={"login": login, "password": password}, headers={"X-CSRFToken": config["csrfToken"]}, timeout=15))
    assert result["account"]["id"] == str(user.pk) and "accessToken" not in result
    web.headers["X-CSRFToken"] = result["csrfToken"]
    remote = checked(web.put(base + "sync/books", json={"books": [], "since": 0}, timeout=15))["books"]
    assert len(remote) == 1 and remote[0]["pageIndex"] == 3 and remote[0]["readingPlace"]["anchor"] == anchor
    downloaded = web.get(base + f"sync/books/{sha}/download", timeout=15)
    assert downloaded.status_code == 200 and downloaded.content == content
    record.update(deviceId="web-check", pageIndex=5, updatedAt=now + 1,
                  readingPlace={"platform": "web", "segment": 0, "character": 0, "anchor": anchor})
    checked(web.put(base + "sync/books", json={"books": [record], "since": 0}, timeout=15))
    restored = checked(mobile.put(base + "sync/books", json={"books": [], "since": 0}, timeout=15))["books"][0]
    assert restored["pageIndex"] == 5 and restored["readingPlace"]["platform"] == "web"
    assert restored["readingPlace"]["anchor"] == anchor
    checked(web.delete(base + f"sync/books/{sha}", json={"accountId": str(user.pk)}, timeout=15))
    stale = checked(mobile.put(base + "sync/books", json={"books": [record], "since": 0}, timeout=15))
    assert stale["books"] == [] and sha in stale["deletedBooks"]
    assert not user.synced_books.exists()
    from syncapi.storage import book_path
    assert not book_path(user.pk, sha).exists()
    assert checked(mobile.get(base + "account", timeout=15))["account"]["storageUsedBytes"] == 0
    record["restoreDeleted"] = True
    again = checked(mobile.put(base + "sync/books", json={"books": [record], "since": 0}, timeout=15))
    assert len(again["books"]) == 1 and sha not in again["deletedBooks"]
    print("PASS: cross-device deletion, offline resurrection blocked, disk freed, explicit reimport")
    print("PASS: hashed password, mobile/web login, file integrity, bidirectional reading position")
finally:
    if user is not None:
        for book in user.synced_books.all():
            delete_book_file(user.pk, book.content_sha256)
        user.delete()
        print("Temporary account and file removed; existing accounts untouched")
