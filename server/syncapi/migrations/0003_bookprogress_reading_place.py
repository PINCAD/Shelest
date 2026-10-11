from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [("syncapi", "0002_rename_progress_and_book_indexes")]
    operations = [migrations.AddField(model_name="bookprogress", name="reading_place",
                                      field=models.JSONField(blank=True, null=True))]
