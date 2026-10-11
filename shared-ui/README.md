# Shared UI — step 3

Common Kotlin Compose trees implement account summary, synchronization preferences,
app appearance, book appearance controls, library grouping/cards/actions and reader
chrome for Android and browser. Sources: `AccountPanel.kt` and `WorkspacePanels.kt`
in `src/commonMain/kotlin/com/shelest/ui/`.

The small semantic renderer boundary uses `expect/actual`:

- Android: Material 3, current application colors/fonts, dp and native semantics.
- Browser: Compose HTML/Kotlin JS, real buttons/switch roles/progressbar/text.

This deliberately is not a Wasm/canvas port of every Material widget. Compose
HTML itself is DOM-only; sharing is provided by our explicit semantic primitives
and common composable tree, not by claiming HTML elements can run on Android.
Versions stay aligned with the existing Kotlin 1.9.22/Compose compiler 1.5.8 and
Android Compose 1.6.x; no broad build-toolchain or database migration.

Platform adapters still own login provider UI, password forms, network/storage,
navigation, preferences and app theme application. Only public view metadata,
cover URLs, choices and action callbacks enter these views. No passwords, OAuth
tokens or book text/files cross the UI bridge. Reading control capabilities come
from adapters: native PDF zoom/gestures, voices, curl and bundled fonts remain native;
this stage shares presentation, not every platform feature or font implementation.

Web React owns the lifecycle of a dedicated empty container; Compose alone owns
its children. Dispose when navigating away; update the mounted composition for
account/busy/preferences changes. The completed library now also requests the
module asynchronously after an immediately usable React fallback; no blocking
Kotlin runtime initialization before first paint. Keep accessible React controls
when the optional UI chunk is unavailable. The complete module is about 201 KB
gzipped, loaded once per page and browser-cacheable. Android uses a stable-key
LazyVerticalGrid (adaptive columns); browser covers load lazily and off-screen
cards use content-visibility. Never send book blobs into the UI mount.

Verification:

- Gradle `:shared-ui:jsBrowserProductionLibraryDistribution`, Android assemble/JVM tests.
- `node web/tests/common-account-ui.test.cjs` with Playwright/Chrome and isolated
  browser profile. Mock API only: lazy load, quota, keyboard, 320 px/light/dark,
  preference persistence, repeat mount/disposal and logged-in form hiding.
- Android `CommonAccountPanelTest` on a disposable emulator, synthetic view state:
  buttons/busy/Premium/account transitions and switch callback; no real login.
- Existing sync/anchor and shared-reader regression suites remain applicable.
- `common-workspace-ui.test.cjs`: library rename/favorites/search/sort, reader
  controls, system theme/accent/reset, persistence and 320/768/1440px layouts.
- `CommonWorkspaceTest`: native preference callbacks, lazy 100-book shelf/menu
  and reader controls. Uses UiAutomation, not old Espresso reflection APIs.
- `LibraryEditingTest` still checks long press on the entire card, real rename
  dialog/persistence and preservation of the book file identity/reading place.

Step 3's account/settings/library/reader-chrome scope is implemented. Statistics,
discovery, vocabulary, platform navigation and provider/password entry forms are
not rewritten by this stage. Database/signing/pref IDs are unchanged.

Unified local build (step 4): `./scripts/build-release.ps1 -BrowserTests` from the
repository root; set PLAYWRIGHT_MODULE and BROWSER_EXECUTABLE first. It builds
Android/debug instrumentation APK, shared JVM/JS, web, JVM/JS and isolated browser
tests, then creates APK/static archive/hash manifest under `artifacts/`. It does
not deploy automatically or touch any connected device. Android instrumentation
is a separate emulator-only run and is not falsely claimed in the manifest.
The website version is read directly from Android versionName at Vite build time.

Official API references:
[Compose HTML elements](https://github.com/JetBrains/compose-multiplatform/blob/master/tutorials/HTML/Building_UI/README.md),
[Compose 1.6.0/toolchain baseline](https://kotlinlang.org/docs/multiplatform/whats-new-compose-160.html).
