import os
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parent.parent

SECRET_KEY = os.environ.get("SHELEST_SECRET_KEY", "")
if not SECRET_KEY:
    raise RuntimeError("SHELEST_SECRET_KEY must be set in the process environment")

DEBUG = os.environ.get("SHELEST_DEBUG", "false").lower() == "true"
ALLOWED_HOSTS = [host.strip() for host in os.environ.get("SHELEST_ALLOWED_HOSTS", "").split(",") if host.strip()]
if DEBUG and not ALLOWED_HOSTS:
    ALLOWED_HOSTS = ["127.0.0.1", "localhost"]

ROOT_DIR = Path(os.environ.get("SHELEST_DATA_DIR", BASE_DIR / "var")).resolve()
ROOT_DIR.mkdir(parents=True, exist_ok=True)
DATABASE_PATH = Path(os.environ.get("SHELEST_DATABASE", ROOT_DIR / "shelest.sqlite3")).resolve()
BOOK_STORAGE_DIR = Path(os.environ.get("SHELEST_BOOKS_DIR", ROOT_DIR / "books")).resolve()
BOOK_STORAGE_DIR.mkdir(parents=True, exist_ok=True)

INSTALLED_APPS = [
    "django.contrib.admin",
    "django.contrib.auth",
    "django.contrib.contenttypes",
    "django.contrib.sessions",
    "django.contrib.messages",
    "django.contrib.staticfiles",
    "syncapi",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "whitenoise.middleware.WhiteNoiseMiddleware",
    "syncapi.middleware.SeparateReaderSessionMiddleware",
    "django.middleware.common.CommonMiddleware",
    "django.middleware.csrf.CsrfViewMiddleware",
    "django.contrib.auth.middleware.AuthenticationMiddleware",
    "django.contrib.messages.middleware.MessageMiddleware",
    "django.middleware.clickjacking.XFrameOptionsMiddleware",
]

ROOT_URLCONF = "config.urls"
TEMPLATES = [{
    "BACKEND": "django.template.backends.django.DjangoTemplates",
    "DIRS": [],
    "APP_DIRS": True,
    "OPTIONS": {
        "context_processors": [
            "django.template.context_processors.debug",
            "django.template.context_processors.request",
            "django.contrib.auth.context_processors.auth",
            "django.contrib.messages.context_processors.messages",
        ],
    },
}]

WSGI_APPLICATION = "config.wsgi.application"
ASGI_APPLICATION = "config.asgi.application"

DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": DATABASE_PATH,
        "OPTIONS": {"timeout": 20},
    }
}

AUTH_USER_MODEL = "syncapi.User"
AUTH_PASSWORD_VALIDATORS = [
    {"NAME": "django.contrib.auth.password_validation.UserAttributeSimilarityValidator"},
    {"NAME": "django.contrib.auth.password_validation.MinimumLengthValidator"},
    {"NAME": "django.contrib.auth.password_validation.CommonPasswordValidator"},
    {"NAME": "django.contrib.auth.password_validation.NumericPasswordValidator"},
]

LANGUAGE_CODE = "ru-ru"
TIME_ZONE = "UTC"
USE_I18N = True
USE_TZ = True

STATIC_URL = "static/"
STATIC_ROOT = ROOT_DIR / "static"
STORAGES = {
    "default": {"BACKEND": "django.core.files.storage.FileSystemStorage"},
    "staticfiles": {"BACKEND": "whitenoise.storage.CompressedManifestStaticFilesStorage"},
}
DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

GOOGLE_OAUTH_CLIENT_IDS = [
    item.strip()
    for item in os.environ.get("SHELEST_GOOGLE_CLIENT_IDS", "").split(",")
    if item.strip()
]
MAX_BOOK_BYTES = int(os.environ.get("SHELEST_MAX_BOOK_BYTES", str(100 * 1024 * 1024)))
MAX_STORED_BOOK_BYTES = int(os.environ.get("SHELEST_MAX_STORAGE_BYTES", str(5 * 1024 * 1024 * 1024)))
ACCESS_TOKEN_SECONDS = int(os.environ.get("SHELEST_ACCESS_TOKEN_SECONDS", "900"))
REFRESH_TOKEN_SECONDS = int(os.environ.get("SHELEST_REFRESH_TOKEN_SECONDS", str(60 * 60 * 24 * 30)))

CSRF_COOKIE_SECURE = not DEBUG
SESSION_COOKIE_SECURE = not DEBUG
SESSION_COOKIE_HTTPONLY = True
SESSION_COOKIE_SAMESITE = "Lax"
SECURE_CONTENT_TYPE_NOSNIFF = True
X_FRAME_OPTIONS = "DENY"
SECURE_REFERRER_POLICY = "same-origin"
SECURE_PROXY_SSL_HEADER = ("HTTP_X_FORWARDED_PROTO", "https")
SECURE_SSL_REDIRECT = os.environ.get("SHELEST_FORCE_HTTPS", "true").lower() == "true" and not DEBUG
SECURE_HSTS_SECONDS = 31_536_000 if not DEBUG else 0
SECURE_HSTS_INCLUDE_SUBDOMAINS = False
DATA_UPLOAD_MAX_MEMORY_SIZE = 1 * 1024 * 1024
FILE_UPLOAD_MAX_MEMORY_SIZE = 1 * 1024 * 1024
