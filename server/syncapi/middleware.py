from django.conf import settings
from django.contrib.sessions.middleware import SessionMiddleware


class SeparateReaderSessionMiddleware(SessionMiddleware):
    """Admin and reader must not switch each other's identity in the same browser."""
    cookie_name = "shelest_reader_session"

    def process_request(self, request):
        request.reader_session = request.path.startswith("/api/")
        name = self.cookie_name if request.reader_session else settings.SESSION_COOKIE_NAME
        request.session = self.SessionStore(request.COOKIES.get(name))

    def process_response(self, request, response):
        response = super().process_response(request, response)
        if getattr(request, "reader_session", False) and settings.SESSION_COOKIE_NAME in response.cookies:
            cookie = response.cookies.pop(settings.SESSION_COOKIE_NAME)
            cookie.set(self.cookie_name, cookie.value, cookie.coded_value)
            cookie["path"] = "/api/"
            response.cookies[self.cookie_name] = cookie
        return response
