import json
from django.test import TestCase, override_settings
from .models import User, PersonalRecord, SyncedBook
from .auth import issue_session


@override_settings(SECURE_SSL_REDIRECT=False, ALLOWED_HOSTS=["testserver"])
class PersonalTests(TestCase):
    def setUp(self):
        self.user=User.objects.create_user(username="personal-reader")
        _,token,_=issue_session(self.user)
        self.headers={"HTTP_AUTHORIZATION":f"Bearer {token}"}
    def sync(self,entries=(),since=0):
        return self.client.put('/api/v1/sync/personal',json.dumps({"entries":list(entries),"since":since}),content_type='application/json',**self.headers)
    def record(self,key,value,stamp=100): return {"key":key,"value":value,"changedAt":stamp}
    def test_goal_conflict_delta_and_isolation(self):
        first=self.sync([self.record('goal',30)]).json()
        self.assertEqual(first['entries'][0]['value'],30)
        self.assertEqual(self.sync(since=first['cursor']).json()['entries'],[])
        losing=self.sync([self.record('goal',10,50)],since=first['cursor']).json()
        self.assertEqual(losing['entries'],[])
        self.assertEqual(losing['ack'][0]['value'],30)
        self.assertEqual(PersonalRecord.objects.get().value,30)
        other=User.objects.create_user(username='other-reader')
        self.assertEqual(PersonalRecord.objects.filter(user=other).count(),0)
        _,token,_=issue_session(other)
        response=self.client.put('/api/v1/sync/personal','{"entries":[]}',content_type='application/json',HTTP_AUTHORIZATION=f'Bearer {token}')
        self.assertEqual(response.json()['entries'],[])
    def test_word_delete_stays_deleted_until_explicit_readd(self):
        word={"word":"Шелест","definition":"Тихий шорох","bookTitle":"Книга","addedAt":100,"learned":False}
        self.sync([self.record('word:шелест',word)])
        self.sync([self.record('word:шелест',None,200)])
        self.sync([self.record('word:шелест',word,150)])
        self.assertIsNone(PersonalRecord.objects.get().value)
        word['learned']=True
        self.sync([self.record('word:шелест',word,300)])
        self.assertTrue(PersonalRecord.objects.get().value['learned'])
    def test_validation_is_atomic(self):
        for entry in [self.record('goal',True),self.record('goal',241),self.record('admin',True),self.record('word:шум',{}),self.record('goal',20,False)]:
            self.assertEqual(self.sync([self.record('goal',30),entry]).status_code,400)
        self.assertEqual(PersonalRecord.objects.count(),0)
    def test_distinct_russian_spelling_and_legacy_migration(self):
        from importlib import import_module
        from django.apps import apps
        from django.db import connection
        from .models import PersonalClock
        def word(spelling):
            return {"word":spelling,"definition":"test","bookTitle":"","addedAt":100,"learned":False}
        self.assertEqual(self.sync([self.record('word:все',word('все')),self.record('word:всё',word('всё'))]).status_code,200)
        self.sync([self.record('word:все',None,200)])
        self.assertEqual(PersonalRecord.objects.get(key='word:всё').value['word'],'всё')
        PersonalRecord.objects.create(user=self.user,key='word:еж',value=word('ёж'),changed_ms=300,revision=1)
        before=PersonalClock.objects.get(user=self.user).revision
        import_module('syncapi.migrations.0006_preserve_word_spelling').preserve_spelling(apps,connection.schema_editor())
        self.assertFalse(PersonalRecord.objects.filter(key='word:еж').exists())
        self.assertEqual(PersonalRecord.objects.get(key='word:ёж').value['word'],'ёж')
        self.assertGreater(PersonalRecord.objects.get(key='word:ёж').revision,before)
    def test_title_updates_metadata_not_progress(self):
        book=SyncedBook.objects.create(user=self.user,content_sha256='a'*64,title='Original',file_type='EPUB')
        self.sync([self.record('title:'+'a'*64,'Переименованная книга')])
        book.refresh_from_db()
        self.assertEqual(book.title,'Переименованная книга')
        self.assertEqual(book.progresses.count(),0)
    def test_annotations_merge_delete_and_validate_coordinates(self):
        key='mark:'+'a'*64+':12345678-1234-1234-1234-123456789abc'
        value={"type":"DRAWING","color":0xff3976ba,"createdAt":100,"selectedText":None,"contextBefore":None,"contextAfter":None,"noteText":None,
               "pageIndex":2,"normX":None,"normY":None,"width":4,"points":[{"x":.2,"y":.3},{"x":.4,"y":.5}]}
        self.assertEqual(self.sync([self.record(key,value)]).status_code,200)
        bad={**value,"points":[{"x":2,"y":.3}]}
        self.assertEqual(self.sync([self.record(key,bad,200)]).status_code,400)
        self.assertEqual(PersonalRecord.objects.get(key=key).value,value)
        self.sync([self.record(key,None,300)])
        losing=self.sync([self.record(key,value,200)]).json()
        self.assertIsNone(losing['ack'][0]['value'])
    def test_paging_and_retry(self):
        for offset in range(0,205,100):
            self.sync([self.record('word:'+str(i),{"word":str(i),"definition":"test","bookTitle":"","addedAt":100,"learned":False}) for i in range(offset,min(205,offset+100))])
        cursor=0;keys=[]
        while True:
            page=self.sync(since=cursor).json();keys.extend(e['key'] for e in page['entries']);cursor=page['cursor']
            if not page['hasMore']: break
        self.assertEqual(len(set(keys)),205)
        before=cursor
        self.sync([self.record('goal',45)])
        first=self.sync(since=before).json()
        self.assertEqual(first,self.sync(since=before).json())
    def test_browser_requires_csrf(self):
        from django.test import Client
        browser=Client(enforce_csrf_checks=True);browser.force_login(self.user)
        browser.cookies["shelest_reader_session"]=browser.cookies["sessionid"].value
        self.assertEqual(browser.put('/api/v1/sync/personal','{"entries":[]}',content_type='application/json').status_code,403)
