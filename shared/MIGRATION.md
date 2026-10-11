# Android / browser convergence

## Step 1 — common reader document

Implemented and tested in APK 1.4.2 (versionCode 6) and the web build.
Published to `https://shelest-book.ru/` on 2026-10-08; public production smoke
check passed with all API calls mocked. Previous static files are recoverable from
`/var/backups/shelest/web-before-shared-reader-20261008-114956.tar.gz` on the VDS.

Single source: `src/commonMain/kotlin/com/shelest/shared/reader/ReaderDocument.kt`.
Android passes native appearance/position/font URLs into `buildReaderHtml`.
Web calls the compiled Kotlin/JS export; React only hosts a sandboxed iframe and
maps its events onto IndexedDB/book state. Editing the common document changes
both builds, not a generated copy of Android source.

Shared: column geometry, scrolling mode, page turns, exact position/layout
identity, live font/theme changes, text indexing, search and annotation rendering.
Native curl drawing remains Android-specific; the browser adapter falls back to
the shared in-document animation. PDF rendering remains platform-specific.

Web EPUB.js now only opens the archive, spine, contents and old CFI positions.
The shared engine renders chapters. TXT/FB2/HTML/MD/DOCX/RTF retain their existing
text-import adapters. CBZ sends an image page into the same engine.
Storage schemas, book hashes, account IDs and sync API payloads did not change.
Existing EPUB CFI locations migrate before using the new renderer. Foreign-device
page/segment numbers are not assumed compatible: restore the unique text anchor.
An absent/ambiguous anchor shows an error without replacing saved cloud progress.
Opening, changing the font, rotating, or completing the page count is not a newer
reading event. Deliberate navigation advances progressUpdatedAt. Leaving the reader
flushes the exact location before unmounting.

Browser book markup is allowlisted and placed in an opaque-origin `allow-scripts`
iframe, without `allow-same-origin`, navigation or account/storage access. CSP
blocks external requests. Images are sent as structured-cloned raster Blobs and
get object URLs inside the sandbox, not the parent origin; no base64 book payload
or account token is sent. Keep a few chapter/resource caches and a single sequential
measurement iframe; do not create one DOM/WebView per chapter. Exact total pages
appear only after all segment counts are known. Screen/font differences can produce
different total pages even though both clients use the same engine and text place.

Verification:

- `:app:assembleDebug :app:testDebugUnitTest :shared:jvmTest`
- `:shared:jsBrowserProductionLibraryDistribution`, web TypeScript/Vite production build
- `node app/tests/paged-reader.test.cjs` (long shared document, geometry/reopen/resize/animations)
- `node web/tests/shared-reader-browser.test.cjs` against isolated Vite on 127.0.0.1:5180
  (TXT/EPUB/CBZ, legacy CFI, foreign anchors, failed restore, notes, images, boundary
  turns, resize/font changes, full totals, unsafe markup). Requires Playwright and
  Chrome via PLAYWRIGHT_MODULE/BROWSER_EXECUTABLE; no account, personal profile or phone.
- Existing web shared-core, anchor and cloud-position tests.
- `node web/tests/shared-reader-production.test.cjs` (published production bundle,
  isolated guest-local import and exact reopening, all API requests mocked).

Static-only server publication: `server/deploy/publish-web.sh ARCHIVE SHA256` as
root, after verifying the SSH host fingerprint. It backs up only the previous web
build, publishes assets before index.html and retains old hashes for open clients.
No API deployment, database migration or service restart is required for this step.

## Step 2 — common application logic

Implemented in Android 1.4.4 (versionCode 8) and the web client on 2026-10-08.
Shared typed account/book/reading-place models and JSON contract validate an entire
response before applying deletions. Newer exact places win; missing PDF coordinates
or ambiguous text anchors never fall back to percentages. Anchor whitespace and
UTF-16 offsets match on JVM and JS.

SyncCoordinator owns identity/entitlement invalidation, monotonically advancing
cursors, one run at a time, and generation fences for opening a reader, switching
accounts, import/deletion or disabling auto-sync. Platform adapters own network,
tokens/cookies, persistence, hashing, streaming limits and lifecycle scheduling.
Free users transfer metadata/positions only; Premium enables verified book files.
Explicit import cancels queued offline deletion and requests restoration.

Shared auth request validation/paths/errors and account presentation do not store
passwords or tokens. Keystore encryption and HttpOnly web session transport remain
unchanged. Settings numerical bounds/defaults are common; existing saved false
flags and platform enum IDs survive. Appearance preferences are not cloud-synced.
Native PDF, OAuth provider APIs, Room/IndexedDB and the React/Compose UIs remain
platform-specific adapters rather than being copied into common code.

Verification: 24 shared JVM tests (14 new sync tests), 107 Android JVM tests,
compiled JS parity and mock cloud regressions (offline import/deletion, promotion
to Premium, malformed response, late account response, reader-open cancellation),
Android repository/reader instrumentation, browser TXT/EPUB/CBZ regression suite.
No production account/library is used by these tests. Static publication retains
the previous build for rollback and needs no database migration or API restart.

Published to `https://shelest-book.ru/`; server index SHA-256 matches the local
production build and its `index-DidyXF8c.js` asset. Public guest smoke passed with
all API calls mocked. Caddy/API remain active. Rollback:
`/var/backups/shelest/web-before-shared-reader-20261008-153015.tar.gz`.

All 15 targeted Android instrumentation tests passed in separate groups on
Pixel_9_Pro (3 shared integration, 1 persistence, 5 WebView position, 3 PDF,
3 library). A combined stress run exhausted the emulator's WebView memory
(system exit-info reported LOW_MEMORY for the renderer and SIGKILL for the app);
this is recorded rather than counted as a successful aggregate run. No personal
phone, real cloud account or live book was changed by instrumentation.

## Step 3 — common interface (implemented)

Compose Multiplatform introduced gradually: account, settings, library, reader chrome.
Keep the shared HTML text engine and platform PDF/curl adapters. Preserve databases,
preferences, keys and package/signature when migrating. First validate browser input,
accessibility, startup and bundle size with an isolated UI slice.

Step 3a: shared-ui module now supplies the common Compose account summary and
sync preference panels. Android maps semantic primitives to Material 3; the web
maps them to Compose HTML DOM. There is no canvas/Wasm requirement. This is an
explicit custom renderer boundary, not full cross-platform Material UI parity.
Provider/password forms and unrelated statistics/discovery/vocabulary screens are
platform adapters, not part of the interface slices migrated in this stage.
Account/settings UI is lazily loaded in the web, with working React fallbacks;
no UI runtime download on initial library screen. Details: shared-ui/README.md.
Existing databases, account transport and preference keys stay unchanged.
Published on 2026-10-08 to shelest-book.ru, verified
`index-B62VPqXh.js` and lazy `Sh-shared-ui-C4iHdVFm.js`.
Public UI and reader smoke tests passed with mocked API in an isolated browser;
no production accounts or library data changed. Caddy/API active. Rollback:
`/var/backups/shelest/web-before-shared-reader-20261008-170234.tar.gz`.

Step 3b: Android 1.4.6 (versionCode 10) and web now use common appearance,
library grouping/cards/menu actions and reader header/tools/appearance components.
Android retains stable-key lazy/adaptive grid, native cover loading, all bundled
fonts/palettes and native PDF/audio/curl capabilities. Browser uses keyboard-accessible
DOM, lazy images and content-visibility; a React fallback remains usable while the
shared UI module loads. The full module is approximately 201 KB gzip and now loads
asynchronously on the library too; the old 3a no-library-download guarantee no longer
applies. No book file/text or credentials enter the UI bridge.
System app theme, six accents/reset and showStats persist on the web. Old saved
darkApp flags map to LIGHT/DARK; saved false sync flags are preserved. The reader's
available controls come from renderer capabilities: sharing UI does not pretend
browser PDF/audio/font implementations are identical to Android.
113 Android JVM + 24 shared JVM tests passed, plus common browser UI, existing
reader and mock sync tests. Separate emulator runs passed 3 workspace, 1 account,
5 WebView reading-place, 3 PDF and 3 library checks. Native library long-press regression
was caught and fixed. No personal phone or production account data was used.

## Step 4 — unified build/release (implemented for existing debug APK workflow)

One entry command: `./scripts/build-release.ps1 -BrowserTests`. Set PLAYWRIGHT_MODULE
and BROWSER_EXECUTABLE first; Gradle can be supplied through GRADLE_EXECUTABLE.
Builds/tests Android + shared JVM/JS + web, generates a SHA-256 release manifest,
debug APK and web-only deployment archive under artifacts/. Browser tests run
against an owned local production preview; the script closes only that process.
Android device instrumentation remains an explicit emulator-only action, never a
silent connected-device run. Website versionName is read from Android's build file.
Publishing is a separate release step, not an automatic production write after
every local source edit. Production-signing/Play Store automation is not implied.

Final step 3b/4 release on 2026-10-08:
`artifacts/release-1.4.6-20261008-175756/` contains the verified APK, web archive
and build manifest. APK SHA-256:
`f6510dafbba721daa0c88a73b74a0359cb1ead36a26bc1ec200d19d770584a54`.
Published to https://shelest-book.ru/ with `index-CohmXHf3.js`,
`index-BpxSqz1k.css` and lazy `Sh-shared-ui-CUX6vZW9.js`.
Server index SHA-256 matches the local manifest:
`6b8910cbcf35da539ffd3e8e01858c254b4a47303fba353be7c639909a58481b`.
Public common-account, common-workspace and reader tests passed with mocked API
and isolated synthetic books. Caddy/API remain active; no API/database changes.
Rollback archive:
`/var/backups/shelest/web-before-shared-reader-20261008-180148.tar.gz`.
Final native account/workspace checks passed on the release APK. The library
rename regression passed three consecutive runs after removing a redundant
AnimatedContent transition that briefly created duplicate interactive shelves.
Owned preview processes and the test emulator were stopped after verification.

Step 4 follow-up: added explicit local-tool/dependency preflight and -CheckOnly,
respected GRADLE_USER_HOME and quoted preview launcher paths containing spaces.
Full build/Node/browser suites passed again in release-1.4.6-20261008-180757;
Gradle reused current successful JVM results. APK/index hashes are unchanged;
no new publication or device installation was needed. Missing-browser preflight
also rejects correctly without creating artifacts. PROJECT_HANDOFF.md and root
README.md document module ownership, commands, debug signing limitations, actual
deployment paths, safety constraints and a copyable prompt for another chat.

Android shelf layout regression fixed in release-1.4.6-20261008-183644: a lazy grid
item must contain a Column to arrange optional statistics, search and empty-state
roots vertically, rather than drawing them in the same slot. Native regression
checks non-overlapping bounds, stats click, search editing/clearing and hidden stats.
Web regression checks stats/search bounds at 320/768/1440 px. JVM total 137, Node
and browser suites passed; emulator groups passed 5 UI/account, 5 position, 3 PDF,
3 library tests. Crash buffer empty. Web index unchanged; no deployment needed.
