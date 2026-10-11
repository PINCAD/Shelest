# 1.4.15 / code 19 — строка автора Android

Нижние скруглённые углы общего кликабельного контейнера карточки обрезали
край последней строки. Добавлены 8 dp внутреннего нижнего отступа;
автор занимает всю ширину, допускается до двух строк для длинных имён.
Скруглённый ripple сохранён; процент/обложка/прогресс/данные не менялись.

Gradle assembleDebug, assembleDebugAndroidTest, testDebugUnitTest прошли.
На отдельном emulator-5554 320 dp прошли shelfCardsAlignTitlesAndDispatchOneOpenAction
и recentCoversStayCompactAtAnyWidth; первая проверяет нижний запас у автора,
вторая сохраняет порядок недавние → коллекции и компактную геометрию.
Контрольный скриншот: app/build/collections/library-cards-1.4.15.png.
Веб-компонент не изменялся, сайт остаётся проверенной 1.4.14; публикации нет.
При исходной сборке телефон не обновлялся; последующий реальный прогон и
install -r -t описаны в PHONE-QA-1.4.15.md. Собран APK с прежней debug-подписью:
artifacts/release-1.4.15-android/shelest-1.4.15-debug.apk.
SHA256: b1c71dbf6c322c53aa70ea034893c9f31ad7fe72fa1ccfbbdf8c740e8a5fc837.
Это Android-only сборка, не полный unified web release.
