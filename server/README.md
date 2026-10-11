# Шелест — API синхронизации (пилот)

Этот сервис хранит только метаданные книг и прогресс бесплатных аккаунтов. Оригиналы файлов принимаются отдельно и только для пользователей с включённым администратором платным хранилищем. Сейчас это пилот для одного Ubuntu-сервера: Django, SQLite и локальный каталог файлов; глобальный лимит загрузок — 5 ГБ, файл — до 100 МБ. На сервере следует оставить не менее 1 ГБ свободного места.

## Состояние и ограничения

- Вход реализован через проверку Google ID token на сервере. Пароль Google и сам ID token после входа не сохраняются.
- Случайные access/refresh-токены хранятся только в виде SHA-256; refresh-токен ротируется при каждом обновлении.
- Бесплатная синхронизация — библиотечные карточки и позиции, книга идентифицируется SHA-256 исходного файла. Файлы бесплатных аккаунтов не загружаются.
- Оплата и автоматическое продление пока не подключены. Администратор вручную включает подписку, задаёт дату окончания и квоту.
- Клиенты Android и веб поддерживают Google-вход и обмен прогрессом; для входа настройте Web OAuth client ID на сервере, разрешённый origin сайта и Android-клиент с SHA-1 подписи APK в том же Google Cloud проекте.
- Домен и HTTPS подключены: сайт shelest-book.ru, админка /admin/, API /api/v1/.
  Это ещё не завершённый платный публичный продукт: нужны проверенные резервные копии
  SQLite/файлов, политика удаления/экспорта данных и выбранная платёжная схема.
  Не публикуйте API по HTTP. Актуальная карта развёртывания: ../PROJECT_HANDOFF.md.

## Локальный запуск на Windows

Из корня репозитория выполните `.\server\run-local.ps1`. API запустится только на `127.0.0.1:8000`; база хранится в `server/var` и не попадёт в Git. Для первой учётной записи администратора откройте второе окно PowerShell, выполните `.\server\create-admin.ps1` и задайте логин/пароль в интерактивном запросе Django. Затем откройте `http://127.0.0.1:8000/admin/`.

## API v1

- `GET /api/v1/health` — проверка доступности.
- `POST /api/v1/auth/google` с `{"idToken":"…","deviceName":"…"}` — вход/создание аккаунта.
- `POST /api/v1/auth/refresh` с `{"refreshToken":"…"}` — ротация пары токенов.
- `POST /api/v1/auth/logout` — завершить текущую сессию.
- `GET /api/v1/account` — подписка и квота (`Authorization: Bearer …`).
- `PUT /api/v1/sync/books` с массивом до 100 записей — синхронизация метаданных/прогресса.
- `PUT /api/v1/sync/books/<sha256>/file` и `GET /api/v1/sync/books/<sha256>/download` — загрузить/скачать книгу платного аккаунта.
- `DELETE /api/v1/sync/books/<sha256>` и `DELETE /api/v1/account/delete` — удалить запись/аккаунт.

## Подготовка веб-версии к переносу

Соберите веб-версию командой `npm run build` из `web`. На VDS переносите
`web/dist` в `/opt/shelest/web/dist`, а исходники сервера — в `/opt/shelest/server`.
Не переносите `.venv`, `node_modules`, локальную `.env` и `server/var` вместе с кодом.
Базу и книги при необходимости переносите отдельно как данные, с резервной копией.

`deploy/Caddyfile` обслуживает сайт на `https://shelest-book.ru`, а API и админку —
на обоих доменах. Укажите оба домена в `SHELEST_ALLOWED_HOSTS`.
Запросы веб-версии к `/api/` идут на тот же домен; CORS для этого не нужен.
В веб-интерфейсе уже подключены аккаунты и синхронизация.

После сборки выполните `./server/deploy/package.ps1` из корня проекта.
Архив и его SHA-256 появятся в `server/build`. Состав архива задан явным списком;
ключи подписи Android, пароли, локальная база, книги и окружения туда не входят.
Распаковывайте код в `/opt/shelest`, а постоянные данные оставляйте в `/var/lib/shelest`.
Перед обновлением сервиса остановите его и сделайте резервную копию БД и книг.

Сервер хранит прогресс отдельно для каждого устройства и возвращает самое свежее состояние; это защищает от ситуации, когда давно не подключавшийся телефон затрёт более новый прогресс. В запросе записи нужны поля `sha256`, `fileType`, `deviceId`, `title`, `author`, `size`, `position`, `pageIndex`, `updatedAt` (Unix milliseconds).

Дополнительно передаётся `readingPlace` — точная позиция конкретного движка чтения.
Android хранит сегмент, символ и параметры вёрстки; CFI веб-EPUB остаётся
форматным адаптером. Общие текстовые якоря и KMP-читалка позволяют сопоставлять
текстовые позиции между клиентами. При несовместимом readingPlace используется
подходящий текстовый якорь, а не чужой номер страницы. PDF переносит физическую
страницу. Страницы текстовой книги зависят от размера экрана и шрифта — одинаковые
номера на разных устройствах не гарантируются. Правила находятся в shared/sync.

`since` в запросе синхронизации — время предыдущего ответа `serverTime`.
Сервер возвращает только изменившиеся карточки с перекрытием 1 секунду; повторы
не изменяют дату записи. Первое подключение выполняет полную сверку.
Файлы адресуются SHA-256 и передаются только при отсутствии на другой стороне.
Веб использует HttpOnly-сессии с CSRF-защитой, Android — ротируемые токены,
зашифрованные Android Keystore в каталоге, исключённом из резервной копии.
Google-вход требует одноразового `nonce` из `GET /api/v1/auth/config`.

## Развёртывание на Ubuntu 24.04

API разворачивается на `api.shelest-book.ru`. До установки убедитесь, что DNS A-запись этого поддомена указывает на IP VDS, а сервер доступен на 80/443. Caddy сам получит и обновит TLS-сертификат; без готового DNS и доступного снаружи порта 80 выпуск сертификата не пройдёт. На DNS-провайдере для `api` оставьте режим «только DNS» во время первоначальной настройки. Не создавайте AAAA-запись, пока на VDS не настроен IPv6.

Создайте отдельного системного пользователя и каталог данных вне checkout, ограничьте права файлов, настройте резервное копирование обоих — БД и книг. Не запускайте Django development server в интернете. Установку Python-пакетов выполняйте в виртуальном окружении.

```sh
cd /opt/shelest/server
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
```

Секреты задавайте через защищённый EnvironmentFile systemd (права `0600`), а не в Git. Сгенерируйте `SHELEST_SECRET_KEY` криптографически случайным генератором; оставьте `SHELEST_ALLOWED_HOSTS=api.shelest-book.ru`, задайте Google OAuth client ID в `SHELEST_GOOGLE_CLIENT_IDS`, и укажите `SHELEST_DATA_DIR=/var/lib/shelest`. Перед запуском:

```sh
set -a
. ./.env
set +a
python manage.py migrate
python manage.py createsuperuser
python manage.py collectstatic --noinput
```

Установите Caddy и перенесите `deploy/Caddyfile` в `/etc/caddy/Caddyfile`; он проксирует API на loopback и автоматически поднимает HTTPS. Установите `deploy/shelest-api.service` в `/etc/systemd/system/shelest-api.service`. Передайте секреты службе через `/etc/shelest/shelest.env` с правами `0600`. Для SQLite оставьте один worker, откройте во firewall TCP 80/443, сохранив доступ к фактическому SSH-порту (на текущем сервере 22), и настройте регулярные зашифрованные off-site backups. Сначала проверьте восстановление резервной копии. Админ-панель: `https://shelest-book.ru/admin/`.

## Ручное управление подпиской

В админке откройте пользователя, включите `is_premium`, задайте `subscription_until` и `storage_quota_bytes` (байты). Общий серверный предел остаётся 5 ГБ независимо от пользовательских квот. Не выдавайте квоту свыше остатка общего дискового лимита.
# Password sign-in

Reader accounts support username/password registration and login alongside Google.
Use `auth/register` and `auth/password` for Android bearer sessions, or
`auth/web/register` and `auth/web/password` for CSRF-protected HttpOnly browser sessions.
Passwords use Django's salted PBKDF2-SHA256 hasher and password validators; raw
passwords are not persisted or logged. Login names are normalized to lowercase.
Registration never grants Premium. Admin accounts cannot sign in through these reader endpoints.

An authenticated Google user can add credentials through `account/password`
without changing the user ID, library, or subscription. Accounts are not merged
based on an unverified email. Changing existing credentials requires the current
password and revokes other mobile sessions; browser sessions are invalidated by
the changed authentication hash. Login/register attempts share an IP rate limit.
Self-service forgotten-password recovery is not implemented; do not present an
email recovery button until verified email delivery and one-use reset tokens exist.

`deploy/check_password_sync.py` verifies public HTTPS login, file integrity and
bidirectional position exchange using a unique temporary account; credentials
remain in memory and that account/file are deleted in `finally`.
