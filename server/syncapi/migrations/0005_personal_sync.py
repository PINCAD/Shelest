from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):
    dependencies = [("syncapi", "0004_deletedbook")]
    operations = [
        migrations.CreateModel(name="PersonalClock", fields=[
            ("user", models.OneToOneField(primary_key=True, serialize=False, on_delete=django.db.models.deletion.CASCADE, to=settings.AUTH_USER_MODEL)),
            ("revision", models.PositiveBigIntegerField(default=0)),
        ]),
        migrations.CreateModel(name="PersonalRecord", fields=[
            ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
            ("key", models.CharField(max_length=256)),
            ("value", models.JSONField(null=True)),
            ("changed_ms", models.PositiveBigIntegerField()),
            ("revision", models.PositiveBigIntegerField()),
            ("user", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, to=settings.AUTH_USER_MODEL)),
        ], options={"constraints": [models.UniqueConstraint(fields=("user", "key"), name="unique_personal_key")],
                    "indexes": [models.Index(fields=("user", "revision"), name="personal_user_revision")]}),
    ]
