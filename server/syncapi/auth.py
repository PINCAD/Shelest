import hashlib
import json
import logging
import secrets
from datetime import timedelta
from functools import wraps

from django.conf import settings
from django.core.exceptions import RequestDataTooBig
from django.http import JsonResponse
from django.middleware.csrf import CsrfViewMiddleware
from django.utils import timezone

from .models import AccessSession, AccountSecurity

logger = logging.getLogger(__name__)


def _digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def issue_session(user, device_name=""):
    now = timezone.now()
    access = secrets.token_urlsafe(32)
    refresh = secrets.token_urlsafe(48)
    session = AccessSession.objects.create(
        user=user,
        access_hash=_digest(access),
        refresh_hash=_digest(refresh),
        access_expires_at=now + timedelta(seconds=settings.ACCESS_TOKEN_SECONDS),
        refresh_expires_at=now + timedelta(seconds=settings.REFRESH_TOKEN_SECONDS),
        device_name=device_name[:120],
    )
    return session, access, refresh


def api_auth(view):
    @wraps(view)
    def wrapped(request, *args, **kwargs):
        header = request.headers.get("Authorization", "")
        scheme, _, token = header.partition(" ")
        if not header and request.user.is_authenticated and request.user.is_active:
            epoch = AccountSecurity.objects.filter(user=request.user).values_list("session_epoch", flat=True).first() or 0
            if request.session.get("reader_security_epoch", 0) != epoch:
                return JsonResponse({"error": "authentication_required"}, status=401)
            # The browser uses an HttpOnly Django session, never JS-readable refresh tokens.
            error = CsrfViewMiddleware(lambda r: None).process_view(request, lambda r: None, (), {})
            if error:
                return error
            request.api_user = request.user
            request.api_session = None
            response = view(request, *args, **kwargs)
            response["Cache-Control"] = "private, no-store"
            return response
        if scheme.lower() != "bearer" or not token or len(token) > 256:
            return JsonResponse({"error": "authentication_required"}, status=401)
        try:
            session = AccessSession.objects.select_related("user").get(
                access_hash=_digest(token),
                revoked_at__isnull=True,
                access_expires_at__gt=timezone.now(),
                user__is_active=True,
            )
        except AccessSession.DoesNotExist:
            return JsonResponse({"error": "invalid_or_expired_session"}, status=401)
        session.save(update_fields=("last_used_at",))
        request.api_session = session
        request.api_user = session.user
        response = view(request, *args, **kwargs)
        response["Cache-Control"] = "private, no-store"
        return response

    return wrapped


def request_content_length(request):
    """Django exposes the HTTP length through META, not a content_length attribute."""
    raw = request.META.get("CONTENT_LENGTH", "")
    if raw in (None, ""):
        return None
    try:
        length = int(raw)
    except (TypeError, ValueError):
        raise ValueError("invalid_content_length") from None
    if length < 0:
        raise ValueError("invalid_content_length")
    return length


def json_payload(request, max_bytes=256 * 1024):
    try:
        length = request_content_length(request)
    except ValueError:
        return None, JsonResponse({"error": "invalid_content_length"}, status=400)
    if length is not None and length > max_bytes:
        return None, JsonResponse({"error": "request_too_large"}, status=413)
    try:
        raw_body = request.body
        if len(raw_body) > max_bytes:
            return None, JsonResponse({"error": "request_too_large"}, status=413)
        body = json.loads(raw_body or b"{}")
    except RequestDataTooBig:
        return None, JsonResponse({"error": "request_too_large"}, status=413)
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None, JsonResponse({"error": "invalid_json"}, status=400)
    if not isinstance(body, dict):
        return None, JsonResponse({"error": "invalid_json_object"}, status=400)
    return body, None


def new_access_token():
    return secrets.token_urlsafe(32)


def digest_token(value):
    return _digest(value)
