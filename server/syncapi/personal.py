"""Small free-account metadata sync; no book bytes or passwords in this protocol."""
import json
import re
import math
from django.db import transaction
from django.db.models import Sum
from django.db.models.functions import Length
from django.http import JsonResponse
from django.utils import timezone
from django.views.decorators.csrf import csrf_exempt
from django.views.decorators.http import require_http_methods
from .auth import api_auth, json_payload
from .models import PersonalClock, PersonalRecord, SyncedBook


def validate(item):
    key, value, stamp = item["key"], item["value"], item["changedAt"]
    if not isinstance(key, str) or len(key) > 256 or type(stamp) is not int or not 0 < stamp < 2**53:
        raise ValueError()
    if key == "goal":
        if type(value) is not int or not 0 <= value <= 240: raise ValueError()
    elif re.fullmatch(r"title:[0-9a-f]{64}", key):
        if not isinstance(value, str) or not value.strip() or len(value) > 500: raise ValueError()
        value = value.strip()
    elif re.fullmatch(r"collection:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", key):
        if value is not None and (not isinstance(value, str) or not value.strip() or len(value.strip()) > 120): raise ValueError()
        if value is not None: value = value.strip()
    elif re.fullmatch(r"member:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9a-f]{64}", key):
        if value is not None and (type(value) is not int or not 0 <= value <= 1000000): raise ValueError()
    elif key.startswith("word:") and 1 <= len(key[5:]) <= 200:
        if value is not None:
            if not isinstance(value, dict) or set(value) != {"word", "definition", "bookTitle", "addedAt", "learned"}: raise ValueError()
            for name, limit in (("word", 200), ("definition", 12000), ("bookTitle", 500)):
                if not isinstance(value[name], str) or len(value[name]) > limit: raise ValueError()
            if value["word"].strip().lower()[:200] != key[5:] or type(value["learned"]) is not bool: raise ValueError()
            if type(value["addedAt"]) is not int or not 0 < value["addedAt"] < 2**53: raise ValueError()
    elif re.fullmatch(r"mark:[0-9a-f]{64}:[0-9a-f-]{36}", key):
        if value is not None:
            if not isinstance(value, dict) or set(value) != {"type","color","createdAt","selectedText","contextBefore","contextAfter","noteText","pageIndex","normX","normY","points","width"}: raise ValueError()
            if value['type'] not in ('NOTE','HIGHLIGHT','DRAWING'): raise ValueError()
            if type(value['color']) is not int or not 0 <= value['color'] <= 0xffffffff: raise ValueError()
            if type(value['createdAt']) is not int or not 0 < value['createdAt'] < 2**53: raise ValueError()
            for field in ('selectedText','contextBefore','contextAfter','noteText'):
                if value[field] is not None and (not isinstance(value[field],str) or len(value[field]) > 20000): raise ValueError()
            if value['pageIndex'] is not None and (type(value['pageIndex']) is not int or not 0 <= value['pageIndex'] <= 1000000): raise ValueError()
            def number(n,low,high): return type(n) in (float,int) and math.isfinite(n) and low <= n <= high
            for field in ('normX','normY'):
                if value[field] is not None and not number(value[field],0,1): raise ValueError()
            if not number(value['width'],.1,40): raise ValueError()
            points=value['points']
            if not isinstance(points,list) or len(points)>10000: raise ValueError()
            for point in points:
                if not isinstance(point,dict) or set(point)!={'x','y'} or not all(number(point[f],0,1) for f in ('x','y')): raise ValueError()
            if value['type']=='DRAWING' and (not points or value['pageIndex'] is None): raise ValueError()
    else: raise ValueError()
    return key, value, stamp


@csrf_exempt
@require_http_methods(["PUT"])
@api_auth
def sync_personal(request):
    payload, error = json_payload(request, max_bytes=2*1024*1024)
    if error: return error
    try:
        if not isinstance(payload.get("entries"), list) or len(payload["entries"]) > 100: raise ValueError()
        since = payload.get("since", 0)
        if type(since) is not int or since < 0 or since >= 2**53: raise ValueError()
        entries = [validate(item) for item in payload["entries"]]
        collections = payload.get("collections", False)
        if type(collections) is not bool: raise ValueError()
        if not collections and any(key.startswith(("collection:", "member:")) for key, _, _ in entries): raise ValueError()
        if len({item[0] for item in entries}) != len(entries): raise ValueError()
    except (ValueError, KeyError, TypeError):
        return JsonResponse({"error": "invalid_personal_sync"}, status=400)
    now_ms = int(timezone.now().timestamp()*1000)
    with transaction.atomic():
        clock, _ = PersonalClock.objects.get_or_create(user=request.api_user)
        clock = PersonalClock.objects.select_for_update().get(pk=clock.pk)
        if since > clock.revision: since = 0  # restored server backup
        current = {r.key: r for r in PersonalRecord.objects.filter(user=request.api_user, key__in=[e[0] for e in entries])}
        new_count = sum(key not in current for key, _, _ in entries)
        if new_count and PersonalRecord.objects.filter(user=request.api_user).count()+new_count > 10000:
            return JsonResponse({"error": "personal_storage_limit"}, status=413)
        # A free metadata endpoint must not allow unbounded drawing storage.
        used=PersonalRecord.objects.filter(user=request.api_user).aggregate(size=Sum(Length('value')))['size'] or 0
        projected=used
        for key,value,stamp in entries:
            old=current.get(key)
            size=lambda v:len(json.dumps(v)) if v is not None else 0
            projected+=size(value)-(size(old.value) if old else 0)
        if projected > 64*1024*1024 and projected > used:
            return JsonResponse({"error":"personal_storage_limit"},status=413)
        for key, value, stamp in entries:
            stamp = min(stamp, now_ms) if stamp > now_ms+300000 else stamp
            old = current.get(key)
            canonical = lambda v: json.dumps(v, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
            wins = not old or stamp > old.changed_ms or (stamp == old.changed_ms and value != old.value and
                    (value is None or (old.value is not None and canonical(value) > canonical(old.value))))
            if wins:
                clock.revision += 1
                PersonalRecord.objects.update_or_create(user=request.api_user, key=key,
                    defaults={"value": value, "changed_ms": stamp, "revision": clock.revision})
                if key.startswith("title:"):
                    SyncedBook.objects.filter(user=request.api_user, content_sha256=key[6:]).update(title=value, updated_at=timezone.now())
        clock.save(update_fields=("revision",))
        visible = PersonalRecord.objects.filter(user=request.api_user, revision__gt=since)
        if not collections:
            visible = visible.exclude(key__startswith="collection:").exclude(key__startswith="member:")
        rows = list(visible.order_by("revision")[:101])
        page = []; size = 0
        for row in rows[:100]:
            row_size=len(json.dumps(row.value,ensure_ascii=False).encode('utf-8'))+512
            if page and size+row_size>1500000: break
            page.append(row); size+=row_size
        more=len(rows)>len(page)
        ack = PersonalRecord.objects.filter(user=request.api_user,key__in=[e[0] for e in entries])
        return JsonResponse({"accountId": str(request.api_user.pk), "ack": [
            {"key": r.key, "value": r.value, "changedAt": r.changed_ms} for r in ack], "entries": [
            {"key": r.key, "value": r.value, "changedAt": r.changed_ms} for r in page],
            "cursor": page[-1].revision if more else clock.revision, "hasMore": more})
