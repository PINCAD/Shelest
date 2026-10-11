import base64
import json
import time
from unittest.mock import patch
from django.core.cache import cache
from django.test import Client, TestCase, override_settings
from .auth import issue_session
from .models import AccountSecurity, User
from .security import otp


@override_settings(SECURE_SSL_REDIRECT=False, ALLOWED_HOSTS=["testserver"])
class SecurityTests(TestCase):
    password = "Reading!River#2026"

    def setUp(self):
        cache.clear()
        self.user = User.objects.create_user(username="reader", password=self.password)
        self.post("auth/web/password", {"login":"reader","password":self.password})

    def post(self, path, payload, client=None):
        return (client or self.client).post("/api/v1/"+path, json.dumps(payload), content_type="application/json")

    def setup_mfa(self):
        result = self.post("account/security", {"action":"start", "currentPassword":self.password})
        self.assertEqual(result.status_code, 200)
        secret = result.json()["secret"]
        state = AccountSecurity.objects.get(user=self.user)
        self.assertNotIn(secret, state.pending_secret)
        confirmed = self.post("account/security", {"action":"confirm", "code":otp(secret, int(time.time())//30)})
        self.assertEqual(confirmed.status_code, 200)
        self.assertTrue(confirmed.json()["account"]["twoFactorEnabled"])
        return secret, confirmed.json()["recoveryCodes"]

    def test_rfc6238_vector(self):
        secret = base64.b32encode(b"12345678901234567890").decode()
        self.assertEqual(otp(secret, 59//30), "287082")

    def test_enable_confirm_replay_and_recovery(self):
        secret, codes = self.setup_mfa()
        guest = Client()
        first = self.post("auth/password", {"login":"reader","password":self.password}, guest)
        self.assertEqual(first.status_code, 202)
        self.assertNotIn("accessToken", first.json())
        challenge = first.json()["challenge"]
        replay = self.post("auth/mfa", {"challenge":challenge,"code":otp(secret,int(time.time())//30)}, guest)
        self.assertEqual(replay.status_code, 401)
        done = self.post("auth/mfa", {"challenge":challenge,"code":codes[0]}, guest)
        self.assertEqual(done.status_code, 200)
        self.assertIn("accessToken", done.json())
        self.assertNotIn(codes[0], AccountSecurity.objects.get(user=self.user).recovery_hashes)
        self.assertEqual(self.post("auth/mfa", {"challenge":challenge,"code":codes[1]},guest).status_code,401)
        second = self.post("auth/password", {"login":"reader","password":self.password},guest).json()["challenge"]
        self.assertEqual(self.post("auth/mfa", {"challenge":second,"code":codes[0]},guest).status_code,401)

    def test_setup_needs_password_and_confirmation(self):
        self.assertEqual(self.post("account/security", {"action":"start","currentPassword":"bad"}).status_code,401)
        self.post("account/security", {"action":"start","currentPassword":self.password})
        self.assertEqual(self.post("account/security", {"action":"confirm","code":"wrong"}).status_code,401)
        self.assertFalse(AccountSecurity.objects.get(user=self.user).secret)

    def test_enable_revokes_existing_devices_and_browser_sessions(self):
        other = Client(); self.post("auth/web/password", {"login":"reader","password":self.password},other)
        _, access, _ = issue_session(self.user)
        self.setup_mfa()
        self.assertEqual(other.get("/api/v1/account").status_code,401)
        self.assertEqual(Client().get("/api/v1/account", HTTP_AUTHORIZATION="Bearer "+access).status_code,401)
        self.assertEqual(self.client.get("/api/v1/account").status_code,200)

    def test_credentials_and_disable_require_second_factor(self):
        _, codes = self.setup_mfa()
        payload={"action":"credentials","login":"reader.new","currentPassword":self.password,"password":""}
        self.assertEqual(self.post("account/security",payload).status_code,401)
        payload["code"]=codes[0]
        self.assertEqual(self.post("account/security",payload).status_code,200)
        self.user.refresh_from_db();self.assertEqual(self.user.username,"reader.new")
        self.assertTrue(self.user.check_password(self.password))
        self.assertEqual(self.post("account/password",{"login":"reader","password":self.password,"currentPassword":self.password}).status_code,403)
        self.assertEqual(self.post("account/security",{"action":"disable","currentPassword":self.password,"code":codes[1]}).status_code,200)

    def test_web_challenge_needs_csrf_and_does_not_login_early(self):
        _, codes = self.setup_mfa()
        web = Client(enforce_csrf_checks=True)
        token=web.get("/api/v1/auth/config").json()["csrfToken"]
        response=web.post("/api/v1/auth/web/password",json.dumps({"login":"reader","password":self.password}),content_type="application/json",HTTP_X_CSRFTOKEN=token)
        self.assertEqual(response.status_code,202)
        self.assertEqual(web.get("/api/v1/account").status_code,401)
        body=json.dumps({"challenge":response.json()["challenge"],"code":codes[0]})
        self.assertEqual(web.post("/api/v1/auth/mfa",body,content_type="application/json").status_code,403)
        self.assertEqual(web.post("/api/v1/auth/mfa",body,content_type="application/json",HTTP_X_CSRFTOKEN=token).status_code,200)
        self.assertEqual(web.get("/api/v1/account").status_code,200)

    def test_google_login_also_requires_mfa(self):
        self.user.google_sub="google-sub";self.user.save()
        self.setup_mfa()
        guest=Client();nonce=guest.get("/api/v1/auth/config").json()["nonce"]
        claims={"sub":"google-sub","email":"reader@example.com","email_verified":True,"nonce":nonce}
        with override_settings(GOOGLE_OAUTH_CLIENT_IDS=["client"]), patch("syncapi.views.id_token.verify_oauth2_token",return_value=claims):
            response=self.post("auth/google",{"idToken":"token","nonce":nonce},guest)
        self.assertEqual(response.status_code,202)

    def test_challenge_attempts_are_bounded(self):
        self.setup_mfa();guest=Client()
        challenge=self.post("auth/password",{"login":"reader","password":self.password},guest).json()["challenge"]
        for _ in range(5):
            self.assertEqual(self.post("auth/mfa",{"challenge":challenge,"code":"bad"},guest).status_code,401)
        self.assertEqual(self.post("auth/mfa",{"challenge":challenge,"code":"bad"},guest).json()["error"],"mfa_challenge_expired")
