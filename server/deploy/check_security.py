"""Public HTTPS round trip with one disposable account; no real user mutations."""
import os
import secrets
import time
import requests
import django

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")
django.setup()
from syncapi.models import User
from syncapi.security import otp

base="https://shelest-book.ru/api/v1/"
login="security.test."+secrets.token_hex(5)
password=secrets.token_urlsafe(32)
session=requests.Session()
user_id=None

def config():
    response=session.get(base+"auth/config",timeout=20);response.raise_for_status()
    session.headers["X-CSRFToken"]=response.json()["csrfToken"]
    session.headers["Origin"]="https://shelest-book.ru"

def post(path,body,status=200):
    response=session.post(base+path,json=body,timeout=20)
    assert response.status_code==status, (path,response.status_code,response.json().get("error"))
    result=response.json()
    if result.get("csrfToken"): session.headers["X-CSRFToken"]=result["csrfToken"]
    return result

try:
    config()
    registered=post("auth/web/register",{"login":login,"password":password})
    user_id=registered["account"]["id"]
    pending=post("account/security",{"action":"start","currentPassword":password})
    confirmed=post("account/security",{"action":"confirm","code":otp(pending["secret"],int(time.time())//30)})
    assert confirmed["account"]["twoFactorEnabled"]
    codes=confirmed["recoveryCodes"]
    post("auth/web/logout",{})
    config()
    challenge=post("auth/web/password",{"login":login,"password":password},202)
    assert "account" not in challenge
    assert session.get(base+"account",timeout=20).status_code==401
    post("auth/mfa",{"challenge":challenge["challenge"],"code":"invalid"},401)
    done=post("auth/mfa",{"challenge":challenge["challenge"],"code":codes[0]})
    assert done["account"]["id"]==user_id
    changed=post("account/security",{"action":"credentials","login":login+".new","password":"","currentPassword":password,"code":codes[1]})
    assert changed["account"]["id"]==user_id
    disabled=post("account/security",{"action":"disable","currentPassword":password,"code":codes[2]})
    assert not disabled["account"]["twoFactorEnabled"]
    post("auth/web/logout",{})
    config()
    post("auth/web/password",{"login":login,"password":password},401)
    assert post("auth/web/password",{"login":login+".new","password":password})["account"]["id"]==user_id
    print("Public account security passed: CSRF, encrypted TOTP setup, MFA login, recovery, rename, disable; same identity.")
finally:
    if user_id:
        User.objects.filter(pk=user_id,username__in=[login,login+".new"],is_staff=False,is_superuser=False).delete()
        print("Disposable security account removed; real users untouched.")
