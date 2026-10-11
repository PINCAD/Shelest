from django.contrib import admin
from django.contrib.auth.admin import UserAdmin

from .models import AccessSession, BookProgress, SyncedBook, User


@admin.register(User)
class ShelestUserAdmin(UserAdmin):
    list_display = (
        "username", "email", "is_active", "is_premium", "subscription_until",
        "storage_quota_bytes", "created_at",
    )
    list_filter = ("is_active", "is_premium", "is_staff", "is_superuser")
    search_fields = ("username", "email", "google_sub", "display_name")
    readonly_fields = ("google_sub", "created_at", "updated_at", "last_login")
    fieldsets = UserAdmin.fieldsets + (
        ("Шелест — подписка и синхронизация", {
            "fields": ("google_sub", "display_name", "is_premium", "subscription_until", "storage_quota_bytes", "created_at", "updated_at")
        }),
    )
    add_fieldsets = UserAdmin.add_fieldsets + (
        ("Шелест", {"fields": ("email", "google_sub", "is_premium", "subscription_until", "storage_quota_bytes")}),
    )


@admin.register(SyncedBook)
class SyncedBookAdmin(admin.ModelAdmin):
    list_display = ("title", "author", "user", "file_type", "content_size", "stored_size", "has_file", "updated_at")
    list_filter = ("has_file", "file_type")
    search_fields = ("title", "author", "content_sha256", "user__email")
    readonly_fields = ("id", "content_sha256", "created_at", "updated_at")
    autocomplete_fields = ("user",)


@admin.register(BookProgress)
class BookProgressAdmin(admin.ModelAdmin):
    list_display = ("book", "device_id", "position", "page_index", "client_updated_at", "received_at")
    search_fields = ("book__title", "book__user__email", "device_id")
    readonly_fields = ("received_at",)


@admin.register(AccessSession)
class AccessSessionAdmin(admin.ModelAdmin):
    list_display = ("user", "device_name", "created_at", "last_used_at", "access_expires_at", "revoked_at")
    search_fields = ("user__email", "device_name")
    readonly_fields = ("access_hash", "refresh_hash", "created_at", "last_used_at", "revoked_at")
