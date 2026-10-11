# 1.4.6 — shared interface and unified local release

Implemented the remaining step 3 scope: common Kotlin Compose app appearance,
reading appearance controls, library grouping/cards/actions and reader chrome.
Account/sync panels remain common from 1.4.5. Native Material 3 and browser DOM
are explicit semantic renderers; platform services and capabilities are adapters.

- App theme: system/light/dark, six accents and reset; website preferences persist.
  Old darkApp preferences migrate to the equivalent mode without changing DB schema.
- Library: native lazy adaptive grid; shared recent/all grouping, search/sort,
  menus, favorites, rename and covers. Entire native card still accepts long press.
- Reader: shared action/header/tools/appearance trees; PDF never offers ineffective
  text settings. Native font previews, all eight palettes/fonts and existing tools
  retained. Browser's existing available fonts/palettes are capability adapters,
  not a claim that all native PDF/audio functionality has been ported to the browser.
- Transparent Android page counter and exact position engine are unchanged.
- Unified build: scripts/build-release.ps1. No automatic device install or deploy.
  Creates debug APK, web archive and SHA-256 manifest; website version uses Android
  versionName. Package, signing key, Room/IndexedDB schema and saved pref IDs unchanged.

Verification: 113 Android JVM + 24 shared JVM checks passed. Compiled JS sync/core,
anchor and mocked cloud conflict/offline/deletion checks passed. Browser tests
covered common account/workspace UI and TXT/EPUB/CBZ exact-place renderer. Production
bundle tests covered local import, navigation, reopening and 320/768/1440px layouts.
Native tests on a disposable 4 GB emulator passed: 3 common workspace, 1 common
account, 5 reading-place, 3 PDF and 3 library editing checks (separate runs).
Personal phone and production accounts/books were not modified by tests.

The initial new UI test used the project's old Espresso runner, which is incompatible
with this emulator's current Android InputManager API. Replaced test interaction with
UiAutomation, matching existing project instrumentation; final checks passed. A
library regression check caught title long-press loss and it was fixed before handoff.
Repeated integration checks also found a transition race: the old AnimatedContent
created two identical shelf roots during the first DAO update, so a menu could open
on the disappearing root. A single persistent lazy grid removes that race and avoids
duplicate cover work. The actual rename dialog passed three consecutive reopen runs.
Mobile browser checks assert toolbar/button bounds, visible back icon and menu bounds;
off-screen card paint containment is disabled while its menu is open.
