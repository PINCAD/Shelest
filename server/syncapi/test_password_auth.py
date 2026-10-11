import json

from django.core.cache import cache
from django.test import Client, TestCase, override_settings

from .auth import issue_session
from .models import AccessSession, SyncedBook, User


@override_settings(SECURE_SSL_REDIRECT=False, ALLOWED_HOSTS=["testserver"])
class PasswordAuthTests(TestCase):
    password = "Reading!River#2026"

    def setUp(self):
        cache.clear()

    def post(self, path, payload, **headers):
        return self.client.post("/api/v1/" + path, json.dumps(payload), content_type="application/json", **headers)

    def test_register_hashes_password_and_logs_in_mobile(self):
        response = self.post("auth/register", {"login": "Reader.One", "password": self.password})
        self.assertEqual(response.status_code, 200)
        user = User.objects.get(username="reader.one")
        self.assertNotEqual(user.password, self.password)
        self.assertTrue(user.password.startswith("pbkdf2_sha256$"))
        self.assertTrue(user.check_password(self.password))
        self.assertFalse(user.is_premium)
        self.assertNotIn("password", response.json())
        token = response.json()["accessToken"]
        self.assertEqual(self.client.get("/api/v1/account", HTTP_AUTHORIZATION="Bearer " + token).status_code, 200)
        login = self.post("auth/password", {"login": "READER.ONE", "password": self.password})
        self.assertEqual(login.json()["account"]["id"], str(user.pk))

    def test_wrong_password_unknown_and_disabled_are_generic(self):
        user = User.objects.create_user(username="reader", password=self.password)
        for login in ("reader", "missing"):
            result = self.post("auth/password", {"login": login, "password": "WrongPassword!"})
            self.assertEqual(result.status_code, 401)
            self.assertEqual(result.json()["error"], "invalid_credentials")
        user.is_active = False
        user.save()
        self.assertEqual(self.post("auth/password", {"login": "reader", "password": self.password}).status_code, 401)

    def test_weak_duplicate_and_oversized_password(self):
        self.assertEqual(self.post("auth/register", {"login": "reader", "password": "12345678"}).status_code, 400)
        self.assertEqual(self.post("auth/register", {"login": "reader", "password": self.password}).status_code, 200)
        self.assertEqual(self.post("auth/register", {"login": "reader", "password": self.password}).status_code, 409)
        self.assertEqual(self.post("auth/register", {"login": "other", "password": "x" * 129}).status_code, 400)

    def test_google_account_can_add_password_without_new_identity(self):
        user = User.objects.create_user(username="g_original", google_sub="verified-google", is_premium=True, storage_quota_bytes=123)
        user.set_unusable_password()
        user.save()
        book = SyncedBook.objects.create(user=user, content_sha256="a" * 64, title="Same library", file_type="TXT")
        session, access, _ = issue_session(user)
        other, _, _ = issue_session(user)
        result = self.post("account/password", {"login": "linked.reader", "password": self.password}, HTTP_AUTHORIZATION="Bearer " + access)
        self.assertEqual(result.status_code, 200)
        self.assertEqual(User.objects.count(), 1)
        login = self.post("auth/password", {"login": "linked.reader", "password": self.password})
        self.assertEqual(login.json()["account"]["id"], str(user.pk))
        self.assertTrue(login.json()["account"]["isPremium"])
        book.refresh_from_db()
        self.assertEqual(book.user_id, user.pk)
        other.refresh_from_db()
        session.refresh_from_db()
        self.assertIsNotNone(other.revoked_at)
        self.assertIsNone(session.revoked_at)

    def test_change_requires_current_password(self):
        user = User.objects.create_user(username="reader", password=self.password)
        _, token, _ = issue_session(user)
        self.assertEqual(self.post("account/password", {"login": "reader", "password": "Another!Strong#2026"}, HTTP_AUTHORIZATION="Bearer " + token).status_code, 401)

    def test_web_requires_csrf_and_returns_only_httponly_session(self):
        self.client = Client(enforce_csrf_checks=True)
        payload = {"login": "web.reader", "password": self.password}
        self.assertEqual(self.post("auth/web/register", payload).status_code, 403)
        config = self.client.get("/api/v1/auth/config").json()
        result = self.post("auth/web/register", payload, HTTP_X_CSRFTOKEN=config["csrfToken"])
        self.assertEqual(result.status_code, 200)
        self.assertNotIn("accessToken", result.json())
        self.assertTrue(result.cookies["shelest_reader_session"]["httponly"])
        self.assertEqual(self.client.get("/api/v1/account").status_code, 200)

    def test_attempts_are_rate_limited(self):
        for _ in range(10):
            self.assertEqual(self.post("auth/password", {"login": "missing", "password": self.password}).status_code, 401)
        self.assertEqual(self.post("auth/password", {"login": "missing", "password": self.password}).status_code, 429)

    def test_admin_cannot_use_reader_login(self):
        User.objects.create_superuser(username="admin", password=self.password)
        self.assertEqual(self.post("auth/password", {"login": "admin", "password": self.password}).status_code, 401)
