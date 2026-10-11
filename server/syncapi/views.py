import json
import ipaddress
import secrets
from django.contrib.auth import authenticate, login as session_login, logout as session_logout, update_session_auth_hash
from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError
from django.middleware.csrf import get_token
import logging
import re
import shutil
import threading
from datetime import datetime, timedelta, timezone as datetime_timezone

from django.conf import settings
from django.contrib.auth import get_user_model
from django.core.cache import cache
from django.db import IntegrityError, transaction
from django.db.models import Sum, Prefetch
from django.http import FileResponse, JsonResponse
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_GET, require_POST, require_http_methods
from google.auth.transport.requests import Request as GoogleRequest
from google.oauth2 import id_token

from .auth import api_auth, digest_token, issue_session, json_payload, new_access_token, request_content_length
from .models import AccessSession, BookProgress, SyncedBook, DeletedBook, PersonalRecord, AccountSecurity
from .storage import atomic_store, book_path, delete_book_file

logger = logging.getLogger(__name__)
User = get_user_model()
_upload_lock = threading.Lock()
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
DEVICE_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
FILE_TYPES = {"TXT", "EPUB", "FB2", "PDF", "HTML", "MD", "DOCX", "CBZ", "RTF", "ODT", "MOBI", "AZW"}


def _error(code, status=400):
    return JsonResponse({"error": code}, status=status)


def _storage_used(user=None):
    books = SyncedBook.objects.filter(has_file=True)
    if user is not None:
        books = books.filter(user=user)
    return books.aggregate(total=Sum("stored_size"))["total"] or 0


def _account_json(user):
    used = _storage_used(user)
    return {
        "id": str(user.pk),
        "email": user.email,
        "displayName": user.display_name,
        "login": "" if user.username.startswith("g_") else user.username,
        "hasPassword": user.has_usable_password(),
        "twoFactorEnabled": AccountSecurity.objects.filter(user=user).exclude(secret="").exists(),
        "isPremium": user.has_paid_storage,
        "quotaBytes": user.storage_quota_bytes if user.has_paid_storage else 0,
        "storageUsedBytes": used,
        "maxBookBytes": settings.MAX_BOOK_BYTES,
    }


def _rate_limited(request, bucket, limit, seconds):
    address = request.META.get("REMOTE_ADDR", "unknown")[:64]
    # Only the loopback reverse proxy may provide the client identity.
    if address in ("127.0.0.1", "::1"):
        forwarded = request.META.get("HTTP_X_SHELEST_CLIENT_IP", "")
        try:
            address = str(ipaddress.ip_address(forwarded))
        except ValueError:
            pass
    key = f"shelest:ratelimit:{bucket}:{address}"
    if not cache.add(key, 1, timeout=seconds):
        try:
            return cache.incr(key) > limit
        except ValueError:
            cache.set(key, 1, timeout=seconds)
    return False


@require_GET
def health(request):
    return JsonResponse({"status": "ok", "service": "shelest-api", "apiVersion": 1})


@require_GET
def auth_config(request):
    if _rate_limited(request, "auth-config", 60, 60):
        return _error("rate_limited", 429)
    nonce = secrets.token_urlsafe(32)
    cache.set("shelest:nonce:" + nonce, True, timeout=300)
    response = JsonResponse({"clientId": settings.GOOGLE_OAUTH_CLIENT_IDS[0] if settings.GOOGLE_OAUTH_CLIENT_IDS else "",
                             "nonce": nonce, "csrfToken": get_token(request)})
    response["Cache-Control"] = "no-store"
    return response


@require_POST
def web_google_login(request):
    # Standard Django CSRF middleware protects this cookie-based endpoint.
    request.web_login = True
    return google_login(request)


@require_POST
def web_logout(request):
    session_logout(request)
    return JsonResponse({"status": "signed_out"})


@csrf_exempt
@require_POST
def google_login(request):
    if _rate_limited(request, "google-login", 20, 60):
        return _error("rate_limited", 429)
    if not settings.GOOGLE_OAUTH_CLIENT_IDS:
        return _error("google_sign_in_not_configured", 503)
    payload, error = json_payload(request, max_bytes=32 * 1024)
    if error:
        return error
    raw_id_token = payload.get("idToken")
    if not isinstance(raw_id_token, str) or len(raw_id_token) > 16 * 1024:
        return _error("invalid_id_token")

    claims = None
    for audience in settings.GOOGLE_OAUTH_CLIENT_IDS:
        try:
            claims = id_token.verify_oauth2_token(raw_id_token, GoogleRequest(), audience=audience)
            break
        except ValueError:
            continue
        except Exception:
            logger.warning("Google token verification temporarily failed")
            return _error("identity_provider_unavailable", 503)
    if not claims:
        return _error("invalid_id_token", 401)

    nonce = payload.get("nonce")
    if (not isinstance(nonce, str) or len(nonce) > 128 or claims.get("nonce") != nonce
            or not cache.get("shelest:nonce:" + nonce)):
        return _error("invalid_login_nonce", 401)
    cache.delete("shelest:nonce:" + nonce)

    subject = claims.get("sub")
    email = claims.get("email", "").strip().lower()
    if not subject or not email or claims.get("email_verified") is not True:
        return _error("verified_email_required", 401)

    username = "g_" + __import__("hashlib").sha256(subject.encode("utf-8")).hexdigest()[:32]
    try:
        with transaction.atomic():
            user, created = User.objects.get_or_create(
                google_sub=subject,
                defaults={
                    "username": username,
                    "email": email,
                    "display_name": str(claims.get("name", ""))[:200],
                },
            )
            if created:
                user.set_unusable_password()
                user.save(update_fields=("password",))
            if not user.is_active:
                return _error("account_disabled", 403)
            changed = []
            if user.email != email:
                user.email = email
                changed.append("email")
            name = str(claims.get("name", ""))[:200]
            if name and user.display_name != name:
                user.display_name = name
                changed.append("display_name")
            if changed:
                user.save(update_fields=changed + ["updated_at"])
    except IntegrityError:
        logger.warning("Google account uniqueness conflict during sign-in")
        return _error("account_conflict", 409)

    return _login_response(request, user, payload)


def _login_response(request, user, payload, mfa_verified=False):
    if not mfa_verified and AccountSecurity.objects.filter(user=user).exclude(secret="").exists():
        from .security import login_challenge
        return login_challenge(request, user, payload)
    if getattr(request, "web_login", False):
        session_login(request, user, backend="django.contrib.auth.backends.ModelBackend")
        request.session["reader_authenticated_at"] = timezone.now().timestamp()
        request.session["reader_security_epoch"] = AccountSecurity.objects.filter(user=user).values_list("session_epoch", flat=True).first() or 0
        return JsonResponse({"account": _account_json(user), "csrfToken": get_token(request)})
    _, access, refresh = issue_session(user, str(payload.get("deviceName", "")))
    return JsonResponse({
        "accessToken": access,
        "accessTokenExpiresIn": settings.ACCESS_TOKEN_SECONDS,
        "refreshToken": refresh,
        "refreshTokenExpiresIn": settings.REFRESH_TOKEN_SECONDS,
        "account": _account_json(user),
    })


def _password_payload(request):
    payload, error = json_payload(request, max_bytes=4096)
    if error:
        return None, error
    login = payload.get("login")
    password = payload.get("password")
    if not isinstance(login, str) or not isinstance(password, str) or not 1 <= len(password) <= 128:
        return None, _error("invalid_credentials")
    login = login.strip().lower()
    if not re.fullmatch(r"[a-z0-9][a-z0-9_.-]{2,31}", login) or login.startswith("g_"):
        return None, _error("invalid_login")
    payload["login"] = login
    return payload, None


def _password_auth(request, register=False):
    if _rate_limited(request, "password-auth", 10, 300):
        return _error("rate_limited", 429)
    payload, error = _password_payload(request)
    if error:
        return error
    if register:
        user = User(username=payload["login"], display_name=payload["login"])
        try:
            validate_password(payload["password"], user)
        except ValidationError as error:
            return JsonResponse({"error": "weak_password", "messages": error.messages}, status=400)
        user.set_password(payload["password"])
        try:
            with transaction.atomic():
                user.save()
        except IntegrityError:
            return _error("login_unavailable", 409)
    else:
        user = authenticate(request, username=payload["login"], password=payload["password"])
        if user is None or user.is_staff or user.is_superuser:
            return _error("invalid_credentials", 401)
    return _login_response(request, user, payload)


@csrf_exempt
@require_POST
def password_login(request):
    return _password_auth(request)


@csrf_exempt
@require_POST
def password_register(request):
    return _password_auth(request, register=True)


@require_POST
def web_password_login(request):
    request.web_login = True
    return _password_auth(request)


@require_POST
def web_password_register(request):
    request.web_login = True
    return _password_auth(request, register=True)


@csrf_exempt
@require_POST
@api_auth
def set_password(request):
    if _rate_limited(request, "set-password", 5, 300):
        return _error("rate_limited", 429)
    payload, error = _password_payload(request)
    if error:
        return error
    user = request.api_user
    if AccountSecurity.objects.filter(user=user).exclude(secret="").exists():
        return _error("use_account_security", 403)
    if user.is_staff or user.is_superuser:
        return _error("forbidden", 403)
    current_password = payload.get("currentPassword", "")
    if not isinstance(current_password, str) or len(current_password) > 128:
        return _error("invalid_credentials", 401)
    if user.has_usable_password() and not user.check_password(current_password):
        return _error("invalid_credentials", 401)
    user.username = payload["login"]
    try:
        validate_password(payload["password"], user)
    except ValidationError as error:
        return JsonResponse({"error": "weak_password", "messages": error.messages}, status=400)
    user.set_password(payload["password"])
    try:
        with transaction.atomic():
            user.save(update_fields=("username", "password", "updated_at"))
            AccessSession.objects.filter(user=user, revoked_at__isnull=True).exclude(pk=getattr(request.api_session, "pk", None)).update(revoked_at=timezone.now())
    except IntegrityError:
        return _error("login_unavailable", 409)
    if request.api_session is None:
        update_session_auth_hash(request, user)
    return JsonResponse({"account": _account_json(user), "csrfToken": get_token(request)})


@csrf_exempt
@require_POST
def refresh_session(request):
    payload, error = json_payload(request, max_bytes=16 * 1024)
    if error:
        return error
    token = payload.get("refreshToken")
    if not isinstance(token, str) or len(token) > 256:
        return _error("invalid_refresh_token", 401)
    try:
        with transaction.atomic():
            session = AccessSession.objects.select_for_update().select_related("user").get(
                refresh_hash=digest_token(token), revoked_at__isnull=True,
                refresh_expires_at__gt=timezone.now(), user__is_active=True,
            )
            access = new_access_token()
            refresh = __import__("secrets").token_urlsafe(48)
            session.access_hash = digest_token(access)
            session.refresh_hash = digest_token(refresh)
            session.access_expires_at = timezone.now() + timedelta(seconds=settings.ACCESS_TOKEN_SECONDS)
            session.refresh_expires_at = timezone.now() + timedelta(seconds=settings.REFRESH_TOKEN_SECONDS)
            session.save(update_fields=("access_hash", "refresh_hash", "access_expires_at", "refresh_expires_at", "last_used_at"))
    except AccessSession.DoesNotExist:
        return _error("invalid_or_expired_refresh_token", 401)
    return JsonResponse({
        "accessToken": access,
        "accessTokenExpiresIn": settings.ACCESS_TOKEN_SECONDS,
        "refreshToken": refresh,
        "refreshTokenExpiresIn": settings.REFRESH_TOKEN_SECONDS,
    })


@csrf_exempt
@require_POST
@api_auth
def logout(request):
    if request.api_session is None:
        session_logout(request)
        return JsonResponse({"status": "signed_out"})
    request.api_session.revoked_at = timezone.now()
    request.api_session.save(update_fields=("revoked_at",))
    return JsonResponse({"status": "signed_out"})


@require_GET
@api_auth
def account(request):
    return JsonResponse({"account": _account_json(request.api_user)})


@csrf_exempt
@require_http_methods(["PUT"])
@api_auth
def sync_books(request):
    payload, error = json_payload(request, max_bytes=512 * 1024)
    if error:
        return error
    items = payload.get("books")
    if not isinstance(items, list) or len(items) > 100:
        return _error("books_must_be_a_list_of_at_most_100")

    now = timezone.now()
    validated = []
    for item in items:
        if not isinstance(item, dict):
            return _error("invalid_book_record")
        content_sha256 = item.get("sha256")
        file_type = item.get("fileType")
        device_id = item.get("deviceId")
        title = item.get("title")
        author = item.get("author", "")
        size = item.get("size", 0)
        position = item.get("position")
        updated_ms = item.get("updatedAt")
        page_index = item.get("pageIndex")
        reading_place = item.get("readingPlace")
        if reading_place is not None:
            if not isinstance(reading_place, dict) or len(json.dumps(reading_place)) > 4096:
                return _error("invalid_reading_place")
            if reading_place.get("platform") not in ("android", "web"):
                return _error("invalid_reading_place_platform")
            for field, maximum in (("anchor", 240), ("cfi", 2048), ("layout", 256)):
                value = reading_place.get(field)
                if value is not None and (not isinstance(value, str) or len(value) > maximum):
                    return _error("invalid_reading_place")
            for field in ("segment", "page", "character"):
                value = reading_place.get(field)
                if value is not None and (isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 2_147_483_647):
                    return _error("invalid_reading_place")
        if not isinstance(content_sha256, str) or not SHA256_RE.fullmatch(content_sha256):
            return _error("invalid_book_hash")
        if file_type not in FILE_TYPES or not isinstance(title, str) or not title.strip() or len(title) > 500:
            return _error("invalid_book_metadata")
        if not isinstance(author, str) or len(author) > 500:
            return _error("invalid_author")
        if isinstance(size, bool) or not isinstance(size, int) or not (0 <= size <= 1 << 40):
            return _error("invalid_book_size")
        if not isinstance(device_id, str) or not DEVICE_RE.fullmatch(device_id):
            return _error("invalid_device_id")
        if isinstance(position, bool) or not isinstance(position, (int, float)) or not (0.0 <= position <= 1.0):
            return _error("invalid_progress")
        if isinstance(updated_ms, bool) or not isinstance(updated_ms, int) or updated_ms <= 0:
            return _error("invalid_progress_timestamp")
        if page_index is not None and (isinstance(page_index, bool) or not isinstance(page_index, int) or not (0 <= page_index < 10_000_000)):
            return _error("invalid_page_index")

        try:
            client_updated = datetime.fromtimestamp(updated_ms / 1000, tz=datetime_timezone.utc)
        except (OverflowError, OSError, ValueError):
            return _error("invalid_progress_timestamp")
        if client_updated.year < 2000:
            return _error("invalid_progress_timestamp")
        if client_updated > now + timedelta(minutes=5):
            client_updated = now
        validated.append((content_sha256, file_type, device_id, title.strip(), author.strip(), size,
                          float(position), page_index, client_updated, reading_place))

    with transaction.atomic():
        for content_sha256, file_type, device_id, title, author, size, position_value, page_index, client_updated, reading_place in validated:
            # Only an explicit user import may restore a tombstoned book.
            if any(item.get("sha256") == content_sha256 and item.get("restoreDeleted") is True for item in items):
                DeletedBook.objects.filter(user=request.api_user, content_sha256=content_sha256).delete()
            if DeletedBook.objects.filter(user=request.api_user, content_sha256=content_sha256).exists():
                continue
            book, _ = SyncedBook.objects.get_or_create(
                user=request.api_user,
                content_sha256=content_sha256,
                defaults={"title": title, "author": author, "file_type": file_type, "content_size": size},
            )
            custom_title = PersonalRecord.objects.filter(user=request.api_user, key="title:"+content_sha256).first()
            if custom_title: title = custom_title.value
            metadata_changed = (book.title, book.author, book.file_type, book.content_size) != (title, author, file_type, size)
            progress, created = BookProgress.objects.get_or_create(
                book=book,
                device_id=device_id,
                defaults={"position": position_value, "page_index": page_index, "client_updated_at": client_updated, "reading_place": reading_place},
            )
            progress_changed = not created and client_updated > progress.client_updated_at
            if progress_changed:
                progress.position = position_value
                progress.page_index = page_index
                progress.client_updated_at = client_updated
                progress.reading_place = reading_place
                progress.save(update_fields=("position", "page_index", "client_updated_at", "received_at", "reading_place"))
            if metadata_changed or created or progress_changed:
                SyncedBook.objects.filter(pk=book.pk).update(title=title, author=author, file_type=file_type,
                                                           content_size=size, updated_at=now)

    all_books = SyncedBook.objects.filter(user=request.api_user).prefetch_related(
        Prefetch("progresses", queryset=BookProgress.objects.order_by("-client_updated_at", "-received_at"))
    )
    since = payload.get("since")
    if isinstance(since, int) and not isinstance(since, bool) and since > 0:
        try:
            cutoff = datetime.fromtimestamp(max(0, since - 1000) / 1000, tz=datetime_timezone.utc)
            all_books = all_books.filter(updated_at__gte=cutoff)
        except (ValueError, OverflowError, OSError):
            return _error("invalid_sync_cursor")
    serialized = []
    for book in all_books:
        latest = next(iter(book.progresses.all()), None)
        serialized.append({
            "sha256": book.content_sha256,
            "title": book.title,
            "author": book.author,
            "fileType": book.file_type,
            "size": book.content_size,
            "hasFile": book.has_file,
            "position": latest.position if latest else 0.0,
            "pageIndex": latest.page_index if latest else None,
            "readingPlace": latest.reading_place if latest else None,
            "updatedAt": int(latest.client_updated_at.timestamp() * 1000) if latest else None,
        })
    return JsonResponse({
        "books": serialized,
        "deletedBooks": list(DeletedBook.objects.filter(user=request.api_user).values_list("content_sha256", flat=True)),
        "account": _account_json(request.api_user),
        "serverTime": int(now.timestamp() * 1000),
    })


@csrf_exempt
@require_http_methods(["PUT"])
@api_auth
def upload_book(request, content_sha256):
    if not SHA256_RE.fullmatch(content_sha256):
        return _error("invalid_book_hash")
    user = request.api_user
    if not user.has_paid_storage:
        return _error("paid_storage_required", 403)
    try:
        content_length = request_content_length(request)
    except ValueError:
        return _error("invalid_content_length")
    if content_length is None or content_length <= 0:
        return _error("content_length_required", 411)
    if content_length > settings.MAX_BOOK_BYTES:
        return _error("book_too_large", 413)
    try:
        book = SyncedBook.objects.get(user=user, content_sha256=content_sha256)
    except SyncedBook.DoesNotExist:
        return _error("book_metadata_required", 404)
    if book.content_size and book.content_size != content_length:
        return _error("size_mismatch", 409)

    # Receive the network stream without holding a SQLite transaction. Other
    # devices must still be able to sign in and sync during a slow upload.
    destination = book_path(user.pk, content_sha256)
    disk = shutil.disk_usage(settings.BOOK_STORAGE_DIR)
    if disk.free - content_length < 1024 * 1024 * 1024:
        return _error("server_disk_reserve_reached", 507)
    if _storage_used(user) + content_length - (book.stored_size if book.has_file else 0) > user.storage_quota_bytes:
        return _error("user_storage_quota_exceeded", 413)
    try:
        temporary, actual_size, actual_hash = atomic_store(request, destination, settings.MAX_BOOK_BYTES)
    except ValueError:
        return _error("book_too_large", 413)
    except OSError:
        logger.exception("Book upload failed while writing to disk")
        return _error("storage_unavailable", 507)
    if actual_size != content_length or actual_hash != content_sha256:
        logger.warning("Book upload integrity check failed: expected_bytes=%s received_bytes=%s hash_matches=%s", content_length, actual_size, actual_hash == content_sha256)
        temporary.unlink(missing_ok=True)
        return _error("uploaded_file_integrity_mismatch", 422)

    with _upload_lock, transaction.atomic():
        try:
            book.refresh_from_db()
        except SyncedBook.DoesNotExist:
            temporary.unlink(missing_ok=True)
            return _error("book_deleted", 409)
        used_for_user = _storage_used(user)
        used_global = _storage_used()
        old_size = book.stored_size if book.has_file else 0
        if used_for_user - old_size + content_length > user.storage_quota_bytes:
            temporary.unlink(missing_ok=True)
            return _error("user_storage_quota_exceeded", 413)
        if used_global - old_size + content_length > settings.MAX_STORED_BOOK_BYTES:
            temporary.unlink(missing_ok=True)
            return _error("server_storage_limit_reached", 507)
        disk = shutil.disk_usage(settings.BOOK_STORAGE_DIR)
        reserve_bytes = 1024 * 1024 * 1024
        if disk.free < reserve_bytes:
            temporary.unlink(missing_ok=True)
            return _error("server_disk_reserve_reached", 507)

        try:
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            os_replace = __import__("os").replace
            os_replace(temporary, destination)
            destination.chmod(0o600)
            book.content_size = actual_size
            book.stored_size = actual_size
            book.has_file = True
            book.save(update_fields=("content_size", "stored_size", "has_file", "updated_at"))
        except OSError:
            temporary.unlink(missing_ok=True)
            logger.exception("Book upload finalization failed")
            return _error("storage_unavailable", 507)
    return JsonResponse({"status": "stored", "storedBytes": book.stored_size})


@require_GET
@api_auth
def download_book(request, content_sha256):
    if not SHA256_RE.fullmatch(content_sha256):
        return _error("invalid_book_hash")
    user = request.api_user
    if not user.has_paid_storage:
        return _error("paid_storage_required", 403)
    try:
        book = SyncedBook.objects.get(user=user, content_sha256=content_sha256, has_file=True)
    except SyncedBook.DoesNotExist:
        return _error("book_file_not_found", 404)
    path = book_path(user.pk, content_sha256)
    try:
        stream = path.open("rb")
    except OSError:
        logger.exception("Book file is missing from storage")
        return _error("book_file_unavailable", 503)
    response = FileResponse(stream, as_attachment=True, filename=f"{content_sha256}.{book.file_type.lower()}")
    response["Content-Length"] = str(book.stored_size)
    response["Cache-Control"] = "private, no-store"
    return response


@csrf_exempt
@require_http_methods(["DELETE"])
@api_auth
def delete_book(request, content_sha256):
    if request.body:
        payload, error = json_payload(request, max_bytes=128)
        if error:
            return error
        if payload.get("accountId") != str(request.api_user.pk):
            return _error("account_changed", 409)
    if not SHA256_RE.fullmatch(content_sha256):
        return _error("invalid_book_hash")
    with _upload_lock, transaction.atomic():
        DeletedBook.objects.get_or_create(user=request.api_user, content_sha256=content_sha256)
        delete_book_file(request.api_user.pk, content_sha256)
        SyncedBook.objects.filter(user=request.api_user, content_sha256=content_sha256).delete()
        # Includes deletions from old clients which do not know about collections.
        from .models import PersonalClock, PersonalRecord
        clock, _ = PersonalClock.objects.get_or_create(user=request.api_user)
        clock = PersonalClock.objects.select_for_update().get(pk=clock.pk)
        for member in PersonalRecord.objects.filter(user=request.api_user, key__startswith="member:", key__endswith=":" + content_sha256).exclude(value=None):
            clock.revision += 1
            member.value = None
            member.changed_ms = max(int(timezone.now().timestamp() * 1000), member.changed_ms + 1)
            member.revision = clock.revision
            member.save(update_fields=("value", "changed_ms", "revision"))
        clock.save(update_fields=("revision",))
    return JsonResponse({"status": "deleted"})


@csrf_exempt
@require_http_methods(["DELETE"])
@api_auth
def delete_account(request):
    user = request.api_user
    if user.is_staff or user.is_superuser:
        return _error("admin_account_cannot_self_delete", 403)
    files = list(user.synced_books.filter(has_file=True).values_list("content_sha256", flat=True))
    user_id = user.pk
    for content_sha256 in files:
        delete_book_file(user_id, content_sha256)
    user.delete()
    return JsonResponse({"status": "deleted"})
