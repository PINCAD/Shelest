"""TOTP: encrypted server-side secrets, single-use codes and bounded login challenges."""
import base64
import hashlib
import hmac
import secrets
import struct
import time
from datetime import timedelta
from urllib.parse import quote

from cryptography.fernet import Fernet
from django.conf import settings
from django.contrib.auth import update_session_auth_hash
from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError
from django.db import IntegrityError, transaction
from django.http import JsonResponse
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_POST

from .auth import api_auth, digest_token, json_payload
from .models import AccountSecurity, AccessSession, LoginChallenge, User


def cipher():
    key = hashlib.sha256(("shelest-totp-v1:" + settings.SECRET_KEY).encode()).digest()
    return Fernet(base64.urlsafe_b64encode(key))


def otp(secret, step):
    raw = base64.b32decode(secret)
    digest = hmac.new(raw, struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 15
    return f"{(struct.unpack('>I', digest[offset:offset+4])[0] & 0x7fffffff) % 1000000:06d}"


def consume(state, code, pending=False):
    if not isinstance(code, str) or len(code) > 32:
        return False
    code = code.strip().replace(" ", "")
    encrypted = state.pending_secret if pending else state.secret
    if not encrypted:
        return False
    secret = cipher().decrypt(encrypted.encode()).decode()
    step = int(time.time()) // 30
    for candidate in (step, step-1, step+1):
        if candidate > state.last_step and hmac.compare_digest(otp(secret, candidate), code):
            state.last_step = candidate
            return True
    if not pending:
        hashed = digest_token(code.upper().replace("-", ""))
        for stored in state.recovery_hashes:
            if hmac.compare_digest(stored, hashed):
                state.recovery_hashes = [x for x in state.recovery_hashes if x != stored]
                return True
    return False


def login_challenge(request, user, payload):
    token = secrets.token_urlsafe(32)
    LoginChallenge.objects.filter(expires_at__lt=timezone.now()).delete()
    LoginChallenge.objects.create(token_hash=digest_token(token), user=user,
        web=getattr(request, "web_login", False), device_name=str(payload.get("deviceName", ""))[:120],
        expires_at=timezone.now()+timedelta(minutes=5))
    response = JsonResponse({"mfaRequired": True, "challenge": token}, status=202)
    response["Cache-Control"] = "no-store"
    return response


@csrf_exempt
@require_POST
def finish_login(request):
    from .views import _error, _rate_limited, _login_response
    if _rate_limited(request, "mfa-login", 10, 300):
        return _error("rate_limited", 429)
    payload, error = json_payload(request, max_bytes=4096)
    if error:
        return error
    token = payload.get("challenge", "")
    if not isinstance(token, str) or len(token) > 128:
        return _error("invalid_mfa_code", 401)
    with transaction.atomic():
        challenge = LoginChallenge.objects.select_for_update().filter(token_hash=digest_token(token),
            expires_at__gt=timezone.now(), attempts__lt=5, user__is_active=True).first()
        if not challenge:
            return _error("mfa_challenge_expired", 401)
        if challenge.web:
            from django.middleware.csrf import CsrfViewMiddleware
            if CsrfViewMiddleware(lambda r: None).process_view(request, lambda r: None, (), {}):
                return _error("csrf_failed", 403)
        state = AccountSecurity.objects.select_for_update().get(user=challenge.user)
        challenge.attempts += 1
        challenge.save(update_fields=("attempts",))
        if not consume(state, payload.get("code")):
            return _error("invalid_mfa_code", 401)
        state.save(update_fields=("last_step", "recovery_hashes"))
        user, web, device = challenge.user, challenge.web, challenge.device_name
        challenge.delete()
        request.web_login = web
        return _login_response(request, user, {"deviceName": device}, mfa_verified=True)


def reauthenticate(request, payload, state):
    user = request.api_user
    if user.is_staff or user.is_superuser:
        return False
    password = payload.get("currentPassword", "")
    if user.has_usable_password():
        if not isinstance(password, str) or len(password) > 128 or not user.check_password(password):
            return False
    else:
        # Google-only accounts must freshly sign in before changing credentials/MFA.
        timestamp = request.session.get("reader_authenticated_at", 0) if request.api_session is None else request.api_session.created_at.timestamp()
        if time.time() - timestamp > 300:
            return False
    if state.secret and not consume(state, payload.get("code")):
        return False
    return True


def invalidate_other_sessions(request, user, state):
    state.session_epoch += 1
    state.save()
    if request.api_session is None:
        request.session["reader_security_epoch"] = state.session_epoch
    AccessSession.objects.filter(user=user, revoked_at__isnull=True).exclude(pk=getattr(request.api_session, "pk", None)).update(revoked_at=timezone.now())


@csrf_exempt
@require_POST
@api_auth
def manage_security(request):
    from .views import _error, _rate_limited, _account_json
    if _rate_limited(request, "account-security", 10, 300):
        return _error("rate_limited", 429)
    payload, error = json_payload(request, max_bytes=4096)
    if error:
        return error
    action = payload.get("action")
    with transaction.atomic():
        user = User.objects.select_for_update().get(pk=request.api_user.pk)
        state, _ = AccountSecurity.objects.get_or_create(user=user)
        state = AccountSecurity.objects.select_for_update().get(pk=state.pk)
        if action == "confirm":
            if not state.pending_until or state.pending_until < timezone.now() or not consume(state, payload.get("code"), pending=True):
                return _error("invalid_mfa_code", 401)
            state.secret = state.pending_secret
            state.pending_secret = ""
            state.pending_until = None
            codes = [secrets.token_hex(8).upper() for _ in range(8)]
            state.recovery_hashes = [digest_token(code) for code in codes]
            state.save()
            invalidate_other_sessions(request, user, state)
            response = JsonResponse({"account": _account_json(user), "recoveryCodes": codes})
            response["Cache-Control"] = "no-store"
            return response
        if not reauthenticate(request, payload, state):
            return _error("reauthentication_required", 401)
        if action == "start":
            if state.secret:
                return _error("mfa_already_enabled", 409)
            secret = base64.b32encode(secrets.token_bytes(20)).decode()
            state.pending_secret = cipher().encrypt(secret.encode()).decode()
            state.pending_until = timezone.now()+timedelta(minutes=10)
            state.last_step = -1
            state.save()
            label = quote("Шелест:" + (user.email or user.username), safe="")
            response = JsonResponse({"secret": secret, "uri": f"otpauth://totp/{label}?secret={secret}&issuer={quote('Шелест')}&algorithm=SHA1&digits=6&period=30"})
            response["Cache-Control"] = "no-store"
            return response
        if action == "disable":
            state.secret = state.pending_secret = ""
            state.recovery_hashes = []
            state.pending_until = None
            state.last_step = -1
        elif action == "credentials":
            import re
            login = payload.get("login", "")
            if not isinstance(login, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_.-]{2,31}", login) or login.startswith("g_"):
                return _error("invalid_login")
            password = payload.get("password", "")
            if not isinstance(password, str) or len(password) > 128:
                return _error("invalid_credentials")
            user.username = login
            if password:
                try:
                    validate_password(password, user)
                except ValidationError:
                    return _error("weak_password")
                user.set_password(password)
            elif not user.has_usable_password():
                return _error("password_required")
            try:
                with transaction.atomic():
                    user.save(update_fields=("username", "password", "updated_at"))
            except IntegrityError:
                return _error("login_unavailable", 409)
            if request.api_session is None:
                update_session_auth_hash(request, user)
        else:
            return _error("invalid_action")
        state.save()
        invalidate_other_sessions(request, user, state)
        return JsonResponse({"account": _account_json(user)})
