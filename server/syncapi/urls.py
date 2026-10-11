from django.urls import path

from . import views
from .personal import sync_personal
from .security import finish_login, manage_security


app_name = "syncapi"

urlpatterns = [
    path("auth/mfa", finish_login),
    path("account/security", manage_security),
    path("auth/config", views.auth_config),
    path("auth/web/google", views.web_google_login),
    path("auth/password", views.password_login),
    path("auth/register", views.password_register),
    path("auth/web/password", views.web_password_login),
    path("auth/web/register", views.web_password_register),
    path("account/password", views.set_password),
    path("auth/web/logout", views.web_logout),
    path("health", views.health, name="health"),
    path("auth/google", views.google_login, name="google_login"),
    path("auth/refresh", views.refresh_session, name="refresh"),
    path("auth/logout", views.logout, name="logout"),
    path("account", views.account, name="account"),
    path("account/delete", views.delete_account, name="delete_account"),
    path("sync/books", views.sync_books, name="sync_books"),
    path("sync/personal", sync_personal),
    path("sync/books/<str:content_sha256>/file", views.upload_book, name="upload_book"),
    path("sync/books/<str:content_sha256>/download", views.download_book, name="download_book"),
    path("sync/books/<str:content_sha256>", views.delete_book, name="delete_book"),
]
