import uuid

from django.contrib.auth.models import AbstractUser
from django.db import models


class User(AbstractUser):
    """Account identity comes from a verified Google subject, never from a client user ID."""

    google_sub = models.CharField(max_length=255, unique=True, null=True, blank=True)
    display_name = models.CharField(max_length=200, blank=True)
    is_premium = models.BooleanField(default=False)
    subscription_until = models.DateTimeField(null=True, blank=True)
    storage_quota_bytes = models.PositiveBigIntegerField(default=0)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    @property
    def has_paid_storage(self):
        from django.utils import timezone

        return self.is_active and self.is_premium and (
            self.subscription_until is None or self.subscription_until > timezone.now()
        )


class AccessSession(models.Model):
    """Opaque bearer tokens are random and only their SHA-256 hashes are stored."""

    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="api_sessions")
    access_hash = models.CharField(max_length=64, unique=True)
    refresh_hash = models.CharField(max_length=64, unique=True)
    access_expires_at = models.DateTimeField()
    refresh_expires_at = models.DateTimeField()
    device_name = models.CharField(max_length=120, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    last_used_at = models.DateTimeField(auto_now=True)
    revoked_at = models.DateTimeField(null=True, blank=True)


class AccountSecurity(models.Model):
    user = models.OneToOneField(User, on_delete=models.CASCADE)
    session_epoch = models.PositiveIntegerField(default=0)
    secret = models.TextField(blank=True)
    pending_secret = models.TextField(blank=True)
    pending_until = models.DateTimeField(null=True)
    last_step = models.BigIntegerField(default=-1)
    recovery_hashes = models.JSONField(default=list)


class LoginChallenge(models.Model):
    token_hash = models.CharField(max_length=64, primary_key=True)
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    web = models.BooleanField(default=False)
    device_name = models.CharField(max_length=120, blank=True)
    expires_at = models.DateTimeField()
    attempts = models.PositiveIntegerField(default=0)


class SyncedBook(models.Model):
    """A local book is matched across devices by the hash of its original file bytes."""

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="synced_books")
    content_sha256 = models.CharField(max_length=64)
    title = models.CharField(max_length=500)
    author = models.CharField(max_length=500, blank=True)
    file_type = models.CharField(max_length=20)
    content_size = models.PositiveBigIntegerField(default=0)
    stored_size = models.PositiveBigIntegerField(default=0)
    has_file = models.BooleanField(default=False)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(
                fields=("user", "content_sha256"), name="unique_user_book_content"
            )
        ]
        indexes = [models.Index(fields=("user", "updated_at"))]

    def __str__(self):
        return f"{self.title} ({self.content_sha256[:12]})"


class DeletedBook(models.Model):
    """Small permanent tombstones stop offline devices from resurrecting files."""
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="deleted_books")
    content_sha256 = models.CharField(max_length=64)
    deleted_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        constraints = [models.UniqueConstraint(fields=("user", "content_sha256"), name="unique_user_deleted_book")]


class PersonalClock(models.Model):
    user = models.OneToOneField(User, on_delete=models.CASCADE, primary_key=True)
    revision = models.PositiveBigIntegerField(default=0)


class PersonalRecord(models.Model):
    user = models.ForeignKey(User, on_delete=models.CASCADE)
    key = models.CharField(max_length=256)
    value = models.JSONField(null=True)
    changed_ms = models.PositiveBigIntegerField()
    revision = models.PositiveBigIntegerField()

    class Meta:
        constraints = [models.UniqueConstraint(fields=("user", "key"), name="unique_personal_key")]
        indexes = [models.Index(fields=("user", "revision"), name="personal_user_revision")]


class BookProgress(models.Model):
    """Keep each device's latest position so an offline device cannot erase another's update."""

    book = models.ForeignKey(SyncedBook, on_delete=models.CASCADE, related_name="progresses")
    device_id = models.CharField(max_length=64)
    position = models.FloatField(default=0.0)
    page_index = models.PositiveIntegerField(null=True, blank=True)
    reading_place = models.JSONField(null=True, blank=True)
    client_updated_at = models.DateTimeField()
    received_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=("book", "device_id"), name="unique_book_device_progress")
        ]
        indexes = [models.Index(fields=("book", "client_updated_at"))]
