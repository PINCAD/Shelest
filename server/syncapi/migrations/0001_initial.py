import django.contrib.auth.models
import django.contrib.auth.validators
import django.db.models.deletion
import django.utils.timezone
import uuid
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):
    initial = True
    dependencies = [
        ("auth", "0012_alter_user_first_name_max_length"),
    ]
    operations = [
        migrations.CreateModel(
            name="User",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("password", models.CharField(max_length=128, verbose_name="password")),
                ("last_login", models.DateTimeField(blank=True, null=True, verbose_name="last login")),
                ("is_superuser", models.BooleanField(default=False, help_text="Designates that this user has all permissions without explicitly assigning them.", verbose_name="superuser status")),
                ("username", models.CharField(error_messages={"unique": "A user with that username already exists."}, help_text="Required. 150 characters or fewer. Letters, digits and @/./+/-/_ only.", max_length=150, unique=True, validators=[django.contrib.auth.validators.UnicodeUsernameValidator()], verbose_name="username")),
                ("first_name", models.CharField(blank=True, max_length=150, verbose_name="first name")),
                ("last_name", models.CharField(blank=True, max_length=150, verbose_name="last name")),
                ("email", models.EmailField(blank=True, max_length=254, verbose_name="email address")),
                ("is_staff", models.BooleanField(default=False, help_text="Designates whether the user can log into this admin site.", verbose_name="staff status")),
                ("is_active", models.BooleanField(default=True, help_text="Designates whether this user should be treated as active. Unselect this instead of deleting accounts.", verbose_name="active")),
                ("date_joined", models.DateTimeField(default=django.utils.timezone.now, verbose_name="date joined")),
                ("google_sub", models.CharField(blank=True, max_length=255, null=True, unique=True)),
                ("display_name", models.CharField(blank=True, max_length=200)),
                ("is_premium", models.BooleanField(default=False)),
                ("subscription_until", models.DateTimeField(blank=True, null=True)),
                ("storage_quota_bytes", models.PositiveBigIntegerField(default=0)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                ("updated_at", models.DateTimeField(auto_now=True)),
                ("groups", models.ManyToManyField(blank=True, help_text="The groups this user belongs to. A user will get all permissions granted to each of their groups.", related_name="user_set", related_query_name="user", to="auth.group", verbose_name="groups")),
                ("user_permissions", models.ManyToManyField(blank=True, help_text="Specific permissions for this user.", related_name="user_set", related_query_name="user", to="auth.permission", verbose_name="user permissions")),
            ],
            options={"verbose_name": "user", "verbose_name_plural": "users", "abstract": False},
            managers=[("objects", django.contrib.auth.models.UserManager())],
        ),
        migrations.CreateModel(
            name="AccessSession",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("access_hash", models.CharField(max_length=64, unique=True)),
                ("refresh_hash", models.CharField(max_length=64, unique=True)),
                ("access_expires_at", models.DateTimeField()),
                ("refresh_expires_at", models.DateTimeField()),
                ("device_name", models.CharField(blank=True, max_length=120)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                ("last_used_at", models.DateTimeField(auto_now=True)),
                ("revoked_at", models.DateTimeField(blank=True, null=True)),
                ("user", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="api_sessions", to=settings.AUTH_USER_MODEL)),
            ],
        ),
        migrations.CreateModel(
            name="SyncedBook",
            fields=[
                ("id", models.UUIDField(default=uuid.uuid4, editable=False, primary_key=True, serialize=False)),
                ("content_sha256", models.CharField(max_length=64)),
                ("title", models.CharField(max_length=500)),
                ("author", models.CharField(blank=True, max_length=500)),
                ("file_type", models.CharField(max_length=20)),
                ("content_size", models.PositiveBigIntegerField(default=0)),
                ("stored_size", models.PositiveBigIntegerField(default=0)),
                ("has_file", models.BooleanField(default=False)),
                ("created_at", models.DateTimeField(auto_now_add=True)),
                ("updated_at", models.DateTimeField(auto_now=True)),
                ("user", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="synced_books", to=settings.AUTH_USER_MODEL)),
            ],
            options={"indexes": [models.Index(fields=["user", "updated_at"], name="syncapi_syn_user_id_81ed91_idx")]},
        ),
        migrations.CreateModel(
            name="BookProgress",
            fields=[
                ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
                ("device_id", models.CharField(max_length=64)),
                ("position", models.FloatField(default=0.0)),
                ("page_index", models.PositiveIntegerField(blank=True, null=True)),
                ("client_updated_at", models.DateTimeField()),
                ("received_at", models.DateTimeField(auto_now=True)),
                ("book", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="progresses", to="syncapi.syncedbook")),
            ],
            options={"indexes": [models.Index(fields=["book", "client_updated_at"], name="syncapi_boo_book_id_54a2c0_idx")]},
        ),
        migrations.AddConstraint(model_name="syncedbook", constraint=models.UniqueConstraint(fields=("user", "content_sha256"), name="unique_user_book_content")),
        migrations.AddConstraint(model_name="bookprogress", constraint=models.UniqueConstraint(fields=("book", "device_id"), name="unique_book_device_progress")),
    ]
