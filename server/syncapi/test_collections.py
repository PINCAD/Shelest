import json
from django.test import TestCase, override_settings
from .auth import issue_session
from .models import User, PersonalRecord, SyncedBook


@override_settings(SECURE_SSL_REDIRECT=False, ALLOWED_HOSTS=['testserver'])
class CollectionsTests(TestCase):
    group = '12345678-1234-1234-1234-123456789abc'
    sha = 'a' * 64

    def setUp(self):
        self.user = User.objects.create_user(username='collection-reader')
        _, token, _ = issue_session(self.user)
        self.headers = {'HTTP_AUTHORIZATION': 'Bearer ' + token}

    def sync(self, entries=(), since=0, collections=True, headers=None):
        payload = {'entries': list(entries), 'since': since}
        if collections is not None: payload['collections'] = collections
        return self.client.put('/api/v1/sync/personal', json.dumps(payload), content_type='application/json', **(headers or self.headers))

    def row(self, key, value, stamp=100):
        return {'key': key, 'value': value, 'changedAt': stamp}

    def test_free_account_round_trip_old_client_compatibility_and_isolation(self):
        rows = [self.row('collection:' + self.group, '  Серия  '), self.row('member:' + self.group + ':' + self.sha, 0), self.row('goal', 45)]
        self.assertEqual(self.sync(rows).status_code, 200)
        self.assertFalse(self.user.is_premium)
        old = self.sync(collections=None).json()
        self.assertEqual([row['key'] for row in old['entries']], ['goal'])
        self.assertEqual(self.sync(since=old['cursor'], collections=None).json()['entries'], [])
        replay = self.sync(since=0).json()
        self.assertEqual(len(replay['entries']), 3)
        self.assertEqual(replay['entries'][0]['value'], 'Серия')
        self.assertEqual(self.sync(since=replay['cursor']).json()['entries'], [])
        other = User.objects.create_user(username='other-collection-reader')
        _, token, _ = issue_session(other)
        self.assertEqual(self.sync(headers={'HTTP_AUTHORIZATION':'Bearer ' + token}).json()['entries'], [])

    def test_validation_and_capability_are_atomic(self):
        key = 'member:' + self.group + ':' + self.sha
        for bad in (True, -1, 1.5, '0', 1000001):
            self.assertEqual(self.sync([self.row('goal', 30), self.row(key, bad)]).status_code, 400)
        for bad in ('', ' ', 'x' * 121, 42):
            self.assertEqual(self.sync([self.row('collection:' + self.group, bad)]).status_code, 400)
        self.assertEqual(self.sync([self.row(key, 1)], collections=False).status_code, 400)
        self.assertEqual(self.sync(collections='true').status_code, 400)
        self.assertEqual(PersonalRecord.objects.count(), 0)

    def test_member_tombstone_beats_stale_offline_upload(self):
        key = 'member:' + self.group + ':' + self.sha
        self.sync([self.row('collection:' + self.group, 'Серия'), self.row(key, 3)])
        deleted = self.sync([self.row(key, None, 200)]).json()
        losing = self.sync([self.row(key, 0, 150)], since=deleted['cursor']).json()
        self.assertIsNone(losing['ack'][0]['value'])
        self.assertEqual(losing['entries'], [])
        self.assertEqual(SyncedBook.objects.count(), 0)

    def test_deleting_group_preserves_book_progress_and_other_membership(self):
        other = '22345678-1234-1234-1234-123456789abc'
        book = SyncedBook.objects.create(user=self.user, content_sha256=self.sha, title='Том', file_type='EPUB')
        self.sync([self.row('collection:' + self.group, 'Серия'), self.row('member:' + self.group + ':' + self.sha, 0),
                   self.row('collection:' + other, 'Избранное'), self.row('member:' + other + ':' + self.sha, 0)])
        self.sync([self.row('collection:' + self.group, None, 200), self.row('member:' + self.group + ':' + self.sha, None, 200)])
        self.assertTrue(SyncedBook.objects.filter(pk=book.pk).exists())
        self.assertEqual(PersonalRecord.objects.get(key='member:' + other + ':' + self.sha).value, 0)

    def test_book_deletion_from_legacy_client_clears_all_memberships(self):
        key = 'member:' + self.group + ':' + self.sha
        before = self.sync([self.row('collection:' + self.group, 'Серия'), self.row(key, 0)]).json()
        reply = self.client.delete('/api/v1/sync/books/' + self.sha, **self.headers)
        self.assertEqual(reply.status_code, 200)
        self.assertIsNone(PersonalRecord.objects.get(key=key).value)
        delta = self.sync(since=before['cursor']).json()
        self.assertEqual(delta['entries'][0]['key'], key)
        self.assertIsNone(delta['entries'][0]['value'])
        self.assertEqual(self.client.delete('/api/v1/sync/books/' + self.sha, **self.headers).status_code, 200)
        self.assertEqual(self.sync(since=delta['cursor']).json()['entries'], [], 'Repeated deletion is idempotent')
        losing = self.sync([self.row(key, 0, 100)], since=delta['cursor']).json()
        self.assertIsNone(losing['ack'][0]['value'])
