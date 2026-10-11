from django.conf import settings
from django.db import migrations, models
import django.db.models.deletion


class Migration(migrations.Migration):
    dependencies = [("syncapi", "0003_bookprogress_reading_place")]
    operations = [migrations.CreateModel(
        name="DeletedBook",
        fields=[
            ("id", models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name="ID")),
            ("content_sha256", models.CharField(max_length=64)),
            ("deleted_at", models.DateTimeField(auto_now_add=True)),
            ("user", models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name="deleted_books", to=settings.AUTH_USER_MODEL)),
        ],
        options={"constraints": [models.UniqueConstraint(fields=("user", "content_sha256"), name="unique_user_deleted_book")]},
    )]
