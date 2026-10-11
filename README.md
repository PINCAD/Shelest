# Шелест.

Читалка для Android и браузера с Django API синхронизации.

Начните с [памятки проекта](PROJECT_HANDOFF.md): структура, общий Kotlin-код,
платформенные адаптеры, сборка, сервер, публикация, ограничения и текст для другого чата.

Единая сборка из PowerShell в корне:

```powershell
.\scripts\build-release.ps1
```

Для изолированных браузерных UI-тестов задайте PLAYWRIGHT_MODULE и
BROWSER_EXECUTABLE, добавьте `-BrowserTests`. Проверка окружения без сборки:
`-BrowserTests -CheckOnly`. Результаты — в artifacts/; публикация не автоматическая.
Это debug APK workflow, не настроенный production signing pipeline.

Подробнее: [общая логика](shared/README.md), [общий интерфейс](shared-ui/README.md),
[веб](web/README.md), [API](server/README.md), [этапы миграции](shared/MIGRATION.md).
