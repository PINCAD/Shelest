"""Run through manage.py shell on the VDS; isolates and removes its own test data."""
import hashlib
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
import requests
from django.contrib.auth import get_user_model
from syncapi.auth import issue_session
from syncapi.storage import delete_book_file

size = 55 * 1024 * 1024
block = b'Synthetic upload test.\n' * 4096
digest = hashlib.sha256()
left = size
while left:
    chunk = block[:min(left, len(block))]
    digest.update(chunk)
    left -= len(chunk)
sha = digest.hexdigest()
user = get_user_model().objects.create_user(username='upload-test-' + uuid.uuid4().hex, is_premium=True, storage_quota_bytes=100 * 1024 * 1024)
try:
    _, access, _ = issue_session(user, 'isolated upload verification')
    headers = {'Authorization': 'Bearer ' + access}
    url = 'https://api.shelest-book.ru/api/v1/'
    record = {'sha256':sha,'title':'Synthetic verification','fileType':'TXT','deviceId':'isolated_test_device','size':size,'position':0,'updatedAt':int(time.time()*1000)}
    response = requests.put(url + 'sync/books', headers=headers, json={'books':[record]}, timeout=20)
    assert response.status_code == 200, response.status_code

    class SlowStream:
        remaining = size
        offset = 0
        def __len__(self): return size
        def read(self, requested=-1):
            if not self.remaining: return b''
            # More than 30 seconds in total: reproduce the old worker timeout.
            time.sleep(0.02)
            count = min(self.remaining, 16 * 1024)
            data = (block + block)[self.offset:self.offset+count]
            self.offset = (self.offset + len(data)) % len(block)
            self.remaining -= len(data)
            return data

    with ThreadPoolExecutor(max_workers=1) as pool:
        started = time.monotonic()
        future = pool.submit(requests.put, url + f'sync/books/{sha}/file', headers={**headers,'Content-Type':'application/octet-stream','Content-Length':str(size)}, data=SlowStream(), timeout=180)
        time.sleep(2)
        account = requests.get(url + 'account', headers=headers, timeout=10)
        assert account.status_code == 200, account.status_code
        response = future.result()
        assert response.status_code == 200, (response.status_code, response.text)
        print('slow_upload_ok', response.json()['storedBytes'], 'seconds', round(time.monotonic()-started,1), 'parallel_account_ok')
finally:
    delete_book_file(user.pk, sha)
    user.delete()
    print('isolated_test_data_removed')
