import hashlib
import os
from pathlib import Path

from django.conf import settings


def book_path(user_id, content_sha256):
    # The digest is validated before reaching this function; user input never supplies a path.
    return settings.BOOK_STORAGE_DIR / str(user_id) / content_sha256[:2] / content_sha256


def delete_book_file(user_id, content_sha256):
    path = book_path(user_id, content_sha256)
    try:
        path.unlink(missing_ok=True)
        parent = path.parent
        if parent.exists() and not any(parent.iterdir()):
            parent.rmdir()
        user_dir = parent.parent
        if user_dir.exists() and not any(user_dir.iterdir()):
            user_dir.rmdir()
    except OSError:
        # Database deletion must not fail because the disk is temporarily unavailable.
        pass


def hash_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def atomic_store(stream, destination, max_bytes):
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(destination.parent, 0o700)
    temporary = destination.with_name(destination.name + ".uploading-" + os.urandom(8).hex())
    digest = hashlib.sha256()
    size = 0
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as output:
            while True:
                chunk = stream.read(64 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > max_bytes:
                    raise ValueError("book_too_large")
                digest.update(chunk)
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        return temporary, size, digest.hexdigest()
    except Exception:
        temporary.unlink(missing_ok=True)
        raise
