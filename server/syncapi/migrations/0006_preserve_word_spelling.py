from django.db import migrations


def preserve_spelling(apps, schema_editor):
    Record = apps.get_model("syncapi", "PersonalRecord")
    Clock = apps.get_model("syncapi", "PersonalClock")
    alias = schema_editor.connection.alias
    for record in Record.objects.using(alias).filter(key__startswith="word:").exclude(value=None).iterator():
        canonical = "word:" + record.value["word"].strip().lower()[:200]
        if canonical == record.key:
            continue
        existing = Record.objects.using(alias).filter(user_id=record.user_id, key=canonical).first()
        clock, _ = Clock.objects.using(alias).get_or_create(user_id=record.user_id)
        if existing is None:
            record.key = canonical
            winner = record
        elif record.changed_ms > existing.changed_ms:
            existing.value, existing.changed_ms = record.value, record.changed_ms
            winner = existing
        else:
            winner = existing
        clock.revision += 1
        clock.save(using=alias, update_fields=["revision"])
        winner.revision = clock.revision
        winner.save(using=alias)
        if existing is not None:
            record.delete(using=alias)


class Migration(migrations.Migration):
    dependencies = [("syncapi", "0005_personal_sync")]
    operations = [migrations.RunPython(preserve_spelling, migrations.RunPython.noop)]
