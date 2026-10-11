from django.db import migrations


class Migration(migrations.Migration):
    dependencies = [
        ("syncapi", "0001_initial"),
    ]

    operations = [
        migrations.RenameIndex(
            model_name="bookprogress",
            old_name="syncapi_boo_book_id_54a2c0_idx",
            new_name="syncapi_boo_book_id_310e6b_idx",
        ),
        migrations.RenameIndex(
            model_name="syncedbook",
            old_name="syncapi_syn_user_id_81ed91_idx",
            new_name="syncapi_syn_user_id_4677ae_idx",
        ),
    ]
