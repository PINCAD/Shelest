import hashlib
import json
import tempfile
from pathlib import Path
from unittest.mock import patch

from django.test import RequestFactory, SimpleTestCase, TestCase, override_settings
from django.utils import timezone

from .auth import issue_session, json_payload, request_content_length
from .models import SyncedBook, User


class PayloadTests(SimpleTestCase):
    def test_valid_json_on_django_request(self):
        request = RequestFactory().post("/", '{"value": 1}', content_type="application/json")
        payload, error = json_payload(request)
        self.assertIsNone(error)
        self.assertEqual(payload, {"value": 1})

    def test_oversized_body(self):
        request = RequestFactory().post("/", '{"value": 1}', content_type="application/json")
        self.assertEqual(json_payload(request, max_bytes=2)[1].status_code, 413)

    def test_invalid_length(self):
        request = RequestFactory().get("/")
        for value in ("invalid", "-1"):
            request.META["CONTENT_LENGTH"] = value
            self.assertEqual(json_payload(request)[1].status_code, 400)

    def test_missing_length(self):
        request = RequestFactory().get("/")
        request.META.pop("CONTENT_LENGTH", None)
        self.assertIsNone(request_content_length(request))


@override_settings(SECURE_SSL_REDIRECT=False, ALLOWED_HOSTS=["testserver"])
class SyncTests(TestCase):
    def setUp(self):
        self.user = User.objects.create_user(username="reader")
        _, self.access, self.refresh = issue_session(self.user)
        self.headers = {"HTTP_AUTHORIZATION": f"Bearer {self.access}"}

    def sync(self, records):
        return self.client.put("/api/v1/sync/books", json.dumps({"books": records}),
                               content_type="application/json", **self.headers)

    def record(self, updated=None, position=0.4):
        return {"sha256": "a" * 64, "fileType": "TXT", "deviceId": "phone001",
                "title": "Book", "author": "", "size": 10, "position": position,
                "pageIndex": 12, "updatedAt": updated or int(timezone.now().timestamp() * 1000)}

    def test_free_progress_sync(self):
        response = self.sync([self.record()])
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["books"][0]["pageIndex"], 12)
        self.assertFalse(response.json()["books"][0]["hasFile"])

    def test_deleted_book_cannot_be_resurrected_by_offline_device(self):
        self.sync([self.record()])
        url = "/api/v1/sync/books/" + "a" * 64
        self.assertEqual(self.client.delete(url, **self.headers).status_code, 200)
        self.assertEqual(self.client.delete(url, **self.headers).status_code, 200)
        result = self.sync([self.record()]).json()
        self.assertEqual(result["books"], [])
        self.assertIn("a" * 64, result["deletedBooks"])
        self.assertFalse(SyncedBook.objects.filter(user=self.user).exists())

    def test_deletion_is_account_scoped(self):
        other = User.objects.create_user(username="another-reader")
        SyncedBook.objects.create(user=other, content_sha256="a" * 64, title="Other", file_type="TXT")
        self.client.delete("/api/v1/sync/books/" + "a" * 64, **self.headers)
        self.assertTrue(SyncedBook.objects.filter(user=other).exists())

    def test_explicit_import_can_restore_deleted_book(self):
        self.sync([self.record()])
        self.client.delete("/api/v1/sync/books/" + "a" * 64, **self.headers)
        record = self.record()
        record["restoreDeleted"] = True
        result = self.sync([record]).json()
        self.assertEqual(len(result["books"]), 1)
        self.assertEqual(result["deletedBooks"], [])

    def test_older_progress_does_not_overwrite(self):
        now = int(timezone.now().timestamp() * 1000)
        self.assertEqual(self.sync([self.record(now)]).status_code, 200)
        result = self.sync([self.record(now - 10000, 0.1)])
        self.assertEqual(result.json()["books"][0]["position"], 0.4)

    def test_account_books_are_isolated(self):
        other = User.objects.create_user(username="other")
        SyncedBook.objects.create(user=other, content_sha256="b" * 64, title="Private", file_type="TXT")
        self.assertEqual(self.sync([]).json()["books"], [])

    def test_free_user_cannot_upload(self):
        response = self.client.put(f'/api/v1/sync/books/{"a" * 64}/file', b"book",
                                   content_type="application/octet-stream", **self.headers)
        self.assertEqual(response.status_code, 403)

    def test_paid_upload_checks_real_bytes(self):
        self.user.is_premium = True
        self.user.storage_quota_bytes = 1024
        self.user.save()
        data = b"book content"
        digest = hashlib.sha256(data).hexdigest()
        book = SyncedBook.objects.create(user=self.user, content_sha256=digest,
                                         title="Book", file_type="TXT", content_size=len(data))
        with tempfile.TemporaryDirectory() as directory, override_settings(BOOK_STORAGE_DIR=Path(directory)):
            response = self.client.put(f"/api/v1/sync/books/{digest}/file", data,
                                       content_type="application/octet-stream", **self.headers)
            self.assertEqual(response.status_code, 200)
            book.refresh_from_db()
            self.assertTrue(book.has_file)
            self.assertEqual(book.stored_size, len(data))

    def test_refresh_rotates_tokens(self):
        def refresh():
            return self.client.post("/api/v1/auth/refresh", json.dumps({"refreshToken": self.refresh}),
                                    content_type="application/json")
        self.assertEqual(refresh().status_code, 200)
        self.assertEqual(refresh().status_code, 401)

    def test_upload_stream_does_not_hold_database_transaction(self):
        from django.db import connection
        from unittest.mock import patch
        from .storage import atomic_store
        self.user.is_premium = True
        self.user.storage_quota_bytes = 1024
        self.user.save()
        data = b"slow book content"
        digest = hashlib.sha256(data).hexdigest()
        SyncedBook.objects.create(user=self.user, content_sha256=digest, title="Test", file_type="TXT", content_size=len(data))
        depth = len(connection.atomic_blocks)
        def checked_stream(*args):
            self.assertEqual(len(connection.atomic_blocks), depth)
            return atomic_store(*args)
        with tempfile.TemporaryDirectory() as directory, override_settings(BOOK_STORAGE_DIR=Path(directory)), patch("syncapi.views.atomic_store", side_effect=checked_stream):
            response = self.client.put(f"/api/v1/sync/books/{digest}/file", data, content_type="application/octet-stream", **self.headers)
            self.assertEqual(response.status_code, 200)

    def test_corrupt_upload_is_rejected_and_removed(self):
        self.user.is_premium = True
        self.user.storage_quota_bytes = 1024
        self.user.save()
        digest = hashlib.sha256(b"good").hexdigest()
        book = SyncedBook.objects.create(user=self.user, content_sha256=digest, title="Test", file_type="TXT", content_size=4)
        with tempfile.TemporaryDirectory() as directory, override_settings(BOOK_STORAGE_DIR=Path(directory)):
            response = self.client.put(f"/api/v1/sync/books/{digest}/file", b"evil", content_type="application/octet-stream", **self.headers)
            self.assertEqual(response.status_code, 422)
            self.assertFalse(any(path.is_file() for path in Path(directory).rglob("*")))
            book.refresh_from_db()
            self.assertFalse(book.has_file)

    def test_account_requires_token(self):
        self.assertEqual(self.client.get("/api/v1/account").status_code, 401)

    def test_unchanged_sync_does_not_update_book(self):
        record = self.record()
        self.sync([record])
        previous = SyncedBook.objects.get(user=self.user).updated_at
        self.sync([record])
        self.assertEqual(SyncedBook.objects.get(user=self.user).updated_at, previous)

    def test_exact_place_roundtrip(self):
        record = self.record()
        record["readingPlace"] = {"platform": "android", "segment": 2, "page": 7, "character": 450, "layout": "v3"}
        response = self.sync([record])
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["books"][0]["readingPlace"], record["readingPlace"])

    def test_cookie_sync_requires_csrf(self):
        from django.test import Client
        browser = Client(enforce_csrf_checks=True)
        browser.force_login(self.user)
        browser.cookies["shelest_reader_session"] = browser.cookies["sessionid"].value
        response = browser.put("/api/v1/sync/books", '{"books": []}', content_type="application/json")
        self.assertEqual(response.status_code, 403)

    @override_settings(GOOGLE_OAUTH_CLIENT_IDS=["web-client"])
    @patch("syncapi.views.id_token.verify_oauth2_token")
    def test_google_nonce_cannot_be_replayed(self, verify):
        nonce = self.client.get("/api/v1/auth/config").json()["nonce"]
        verify.return_value = {"sub": "google-reader", "email": "reader@example.com", "email_verified": True, "nonce": nonce}
        payload = json.dumps({"idToken": "test-token", "nonce": nonce})
        self.assertEqual(self.client.post("/api/v1/auth/google", payload, content_type="application/json").status_code, 200)
        self.assertEqual(self.client.post("/api/v1/auth/google", payload, content_type="application/json").status_code, 401)

    @override_settings(GOOGLE_OAUTH_CLIENT_IDS=["web-client"])
    @patch("syncapi.views.id_token.verify_oauth2_token")
    def test_web_login_uses_session_not_bearer_tokens(self, verify):
        from django.test import Client
        browser = Client(enforce_csrf_checks=True)
        config = browser.get("/api/v1/auth/config").json()
        verify.return_value = {"sub": "google-browser", "email": "browser@example.com", "email_verified": True, "nonce": config["nonce"]}
        response = browser.post("/api/v1/auth/web/google", json.dumps({"idToken": "test", "nonce": config["nonce"]}),
                                content_type="application/json", HTTP_X_CSRFTOKEN=config["csrfToken"])
        self.assertEqual(response.status_code, 200)
        self.assertNotIn("refreshToken", response.json())
        self.assertTrue(response.cookies["shelest_reader_session"]["httponly"])
        result = browser.put("/api/v1/sync/books", '{"books": []}', content_type="application/json", HTTP_X_CSRFTOKEN=response.json()["csrfToken"])
        self.assertEqual(result.status_code, 200)

    def test_admin_session_is_not_a_reader_account(self):
        self.client.force_login(self.user)
        self.assertEqual(self.client.get("/api/v1/account").status_code, 401)

    def test_reader_session_survives_admin_sign_in(self):
        self.client.force_login(self.user)
        self.client.cookies["shelest_reader_session"] = self.client.cookies["sessionid"].value
        del self.client.cookies["sessionid"]
        admin = User.objects.create_superuser(username="administrator", password="test-only-admin-password")
        self.client.force_login(admin)
        self.assertEqual(self.client.get("/api/v1/account").json()["account"]["id"], str(self.user.pk))
