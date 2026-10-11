from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):
    dependencies = [("syncapi", "0006_preserve_word_spelling")]
    operations = [
        migrations.CreateModel(name="AccountSecurity", fields=[
            ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
            ("secret", models.TextField(blank=True)),
            ("session_epoch", models.PositiveIntegerField(default=0)),
            ("pending_secret", models.TextField(blank=True)),
            ("pending_until", models.DateTimeField(null=True)),
            ("last_step", models.BigIntegerField(default=-1)),
            ("recovery_hashes", models.JSONField(default=list)),
            ("user", models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, to=settings.AUTH_USER_MODEL)),
        ]),
        migrations.CreateModel(name="LoginChallenge", fields=[
            ("token_hash", models.CharField(max_length=64, primary_key=True, serialize=False)),
            ("web", models.BooleanField(default=False)),
            ("device_name", models.CharField(max_length=120, blank=True)),
            ("expires_at", models.DateTimeField()),
            ("attempts", models.PositiveIntegerField(default=0)),
            ("user", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to=settings.AUTH_USER_MODEL)),
        ]),
    ]
