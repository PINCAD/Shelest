"""Read-only diagnosis; prints lengths and match counts, never reading excerpts."""
import os
import re
import zipfile
from html.parser import HTMLParser
from syncapi.models import BookProgress
from syncapi.storage import book_path

class BodyText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.body = False
        self.hidden = 0
        self.parts = []
    def handle_starttag(self, tag, attrs):
        if tag == 'body': self.body = True
        if tag in ('script', 'style'): self.hidden += 1
    def handle_endtag(self, tag):
        if tag == 'body': self.body = False
        if tag in ('script', 'style'): self.hidden = max(0, self.hidden - 1)
    def handle_data(self, data):
        if self.body and not self.hidden: self.parts.append(data)

user_id = int(os.environ['SHELEST_CHECK_USER_ID'])
for progress in BookProgress.objects.filter(book__user_id=user_id, book__file_type='EPUB').select_related('book').order_by('-client_updated_at')[:12]:
    place = progress.reading_place or {}
    anchor = place.get('anchor', '')
    if not anchor or not progress.book.has_file: continue
    matches = 0
    prefix_matches = 0
    letters_matches = 0
    nonempty_files = 0
    with zipfile.ZipFile(book_path(user_id, progress.book.content_sha256)) as archive:
        for name in archive.namelist():
            if not name.lower().endswith(('.html', '.xhtml', '.htm', '.xml')): continue
            parser = BodyText()
            parser.feed(archive.read(name).decode('utf-8', errors='replace'))
            text = re.sub(r'\s', '', ''.join(parser.parts))
            matches += text.count(anchor)
            nonempty_files += bool(text)
            prefix_matches += text.count(anchor[:32])
            letters_matches += ''.join(char for char in text if char.isalnum()).count(''.join(char for char in anchor if char.isalnum()))
    print(progress.book.title, place.get('platform'), 'anchor_length', len(anchor), 'original_file_matches', matches, 'prefix_matches', prefix_matches, 'letters_matches', letters_matches, 'nonempty_html_files', nonempty_files)
