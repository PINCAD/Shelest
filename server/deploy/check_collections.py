"""Public HTTPS phone-token/browser-cookie round trip on one disposable FREE account."""
import os, secrets, time, uuid
os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'config.settings')
import django
django.setup()
import requests
from syncapi.models import User

base='https://shelest-book.ru/api/v1/'
login='check.groups.' + uuid.uuid4().hex[:16]
password=secrets.token_urlsafe(32)
user=None
def checked(response):
    assert response.status_code == 200, f"Unexpected HTTP {response.status_code}: {response.json().get('error', 'unknown')}"
    return response.json()
try:
    phone=requests.Session()
    signed=checked(phone.post(base+'auth/register',json={'login':login,'password':password},timeout=20))
    user=User.objects.get(pk=signed['account']['id'],username=login)
    assert not user.is_premium
    phone.headers['Authorization']='Bearer '+signed['accessToken']
    web=requests.Session();web.headers['Origin']='https://shelest-book.ru'
    config=checked(web.get(base+'auth/config',timeout=20))
    signed=checked(web.post(base+'auth/web/password',json={'login':login,'password':password},headers={'X-CSRFToken':config['csrfToken']},timeout=20))
    web.headers['X-CSRFToken']=signed['csrfToken']
    now=int(time.time()*1000);group=str(uuid.uuid4());a='a'*64;b='b'*64
    name='collection:'+group;member_a='member:'+group+':'+a;member_b='member:'+group+':'+b
    def row(key,value,stamp=now): return {'key':key,'value':value,'changedAt':stamp}
    def sync(client,entries=(),since=0,collections=True):
        return checked(client.put(base+'sync/personal',json={'entries':list(entries),'since':since,'collections':collections},timeout=20))
    first=sync(phone,[row(name,'Серия'),row(member_a,0),row(member_b,1)])
    remote=sync(web); values={e['key']:e['value'] for e in remote['entries']}
    assert values[name]=='Серия' and values[member_a]==0 and values[member_b]==1
    assert sync(phone,collections=False)['entries']==[], 'Legacy client must not see collection keys'
    assert sync(web,since=remote['cursor'])['entries']==[], 'No repeated unchanged payload'
    sync(web,[row(name,'Новое название',now+1),row(member_a,1,now+1),row(member_b,0,now+1)])
    changed=sync(phone,since=first['cursor']);values={e['key']:e['value'] for e in changed['entries']}
    assert values[name]=='Новое название' and values[member_a]==1 and values[member_b]==0
    deleted=sync(web,[row(member_a,None,now+2)])
    stale=sync(phone,[row(member_a,0)],since=deleted['cursor'])
    assert stale['ack'][0]['value'] is None
    sync(web,[row(name,None,now+3),row(member_b,None,now+3)])
    deleted=sync(phone);values={e['key']:e['value'] for e in deleted['entries']}
    assert all(values[key] is None for key in (name,member_a,member_b))
    print('Public HTTPS collections passed: phone token ↔ browser cookie, FREE metadata, rename/order, deltas, stale retry and deletion.')
finally:
    if user is not None:
        assert user.username==login and login.startswith('check.groups.')
        user.delete()
