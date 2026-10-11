"""Public HTTPS integration test with one disposable account, no real book files."""
import os, secrets, time, uuid
os.environ.setdefault("DJANGO_SETTINGS_MODULE","config.settings")
import django
django.setup()
import requests
from syncapi.models import User

base="https://shelest-book.ru/api/v1/"
login="check.personal."+uuid.uuid4().hex[:16]
password=secrets.token_urlsafe(32)
user=None
def checked(response):
    assert response.status_code==200,f"Unexpected HTTP {response.status_code}"
    return response.json()
try:
    mobile=requests.Session()
    signed=checked(mobile.post(base+"auth/register",json={"login":login,"password":password},timeout=20))
    user=User.objects.get(pk=signed["account"]["id"],username=login)
    assert not user.is_premium
    mobile.headers["Authorization"]="Bearer "+signed["accessToken"]
    now=int(time.time()*1000);sha="a"*64
    book={"sha256":sha,"title":"Original","author":"","fileType":"TXT","size":4,"deviceId":"personal-test-phone","position":.2,"pageIndex":2,"updatedAt":now}
    checked(mobile.put(base+"sync/books",json={"books":[book]},timeout=20))
    word={"word":"Шелест","definition":"Тихий шорох","bookTitle":"Original","addedAt":now,"learned":False}
    def record(key,value,stamp):return {"key":key,"value":value,"changedAt":stamp}
    first=checked(mobile.put(base+"sync/personal",json={"entries":[record("word:шелест",word,now),record("goal",30,now),record("title:"+sha,"Новое название",now)],"since":0},timeout=20))
    web=requests.Session();web.headers["Origin"]="https://shelest-book.ru"
    config=checked(web.get(base+"auth/config",timeout=20))
    signed=checked(web.post(base+"auth/web/password",json={"login":login,"password":password},headers={"X-CSRFToken":config["csrfToken"]},timeout=20))
    assert web.put(base+"sync/personal",json={"entries":[]},timeout=20).status_code==403
    web.headers["X-CSRFToken"]=signed["csrfToken"]
    pulled=checked(web.put(base+"sync/personal",json={"entries":[],"since":0},timeout=20))
    values={e["key"]:e["value"] for e in pulled["entries"]}
    assert values["goal"]==30 and values["title:"+sha]=="Новое название" and values["word:шелест"]==word
    learned={**word,"learned":True}
    checked(web.put(base+"sync/personal",json={"entries":[record("word:шелест",learned,now+1),record("goal",45,now+1)],"since":pulled["cursor"]},timeout=20))
    back=checked(mobile.put(base+"sync/personal",json={"entries":[],"since":first["cursor"]},timeout=20))
    values={e["key"]:e["value"] for e in back["entries"]}
    assert values["goal"]==45 and values["word:шелест"]["learned"] is True
    checked(mobile.put(base+"sync/personal",json={"entries":[record("word:все",{**word,"word":"все"},now),record("word:всё",{**word,"word":"всё"},now)],"since":0},timeout=20))
    spelling=checked(web.put(base+"sync/personal",json={"entries":[record("word:все",None,now+1)],"since":0},timeout=20))
    spellings={e["key"]:e["value"] for e in spelling["entries"]}
    assert spellings["word:все"] is None and spellings["word:всё"]["word"]=="всё"
    mark_key="mark:"+sha+":"+str(uuid.uuid4())
    mark={"type":"DRAWING","color":0xff3976ba,"createdAt":now,"selectedText":None,"contextBefore":None,"contextAfter":None,"noteText":None,
          "pageIndex":2,"normX":None,"normY":None,"width":4,"points":[{"x":.2,"y":.3},{"x":.4,"y":.5}]}
    checked(mobile.put(base+"sync/personal",json={"entries":[record(mark_key,mark,now)],"since":0},timeout=20))
    marks=checked(web.put(base+"sync/personal",json={"entries":[],"since":0},timeout=20))
    assert next(e["value"] for e in marks["entries"] if e["key"]==mark_key)==mark
    checked(web.put(base+"sync/personal",json={"entries":[record(mark_key,None,now+1)],"since":0},timeout=20))
    lost=checked(mobile.put(base+"sync/personal",json={"entries":[record(mark_key,mark,now)],"since":0},timeout=20))
    assert lost["ack"][0]["value"] is None
    deletion=checked(web.put(base+"sync/personal",json={"entries":[record("word:шелест",None,now+2)],"since":0},timeout=20))
    stale=checked(mobile.put(base+"sync/personal",json={"entries":[record("word:шелест",word,now)],"since":deletion["cursor"]},timeout=20))
    assert stale["entries"]==[] and stale["ack"][0]["value"] is None
    book.update(pageIndex=3,updatedAt=now+3)
    legacy=checked(mobile.put(base+"sync/books",json={"books":[book]},timeout=20))["books"][0]
    assert legacy["title"]=="Новое название" and legacy["pageIndex"]==3
    assert requests.put(base+"sync/personal",json={"entries":[]},timeout=20).status_code==401
    print("PASS: public HTTPS, free-account dictionary/goals/titles/annotations, bearer/cookie roundtrip, learned/deletion, PDF coordinates, stale ACK, legacy title protection and CSRF")
finally:
    if user is not None:
        user.delete()
        print("Disposable account and metadata removed; existing accounts unchanged")
