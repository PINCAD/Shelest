# Shared Kotlin Multiplatform core

`commonMain` contains rules used by the Android app and the web client:
sync models and wire validation, exact-position conflict resolution, Unicode text
anchors, tombstones and file eligibility, account display and login validation,
settings bounds/defaults and session coordination.
`commonMain/com/shelest/shared/reader/ReaderDocument.kt` now owns the reader's
HTML/CSS/JavaScript: paging, resize handling, exact positions, appearance, search
and text annotations. Android `buildPagedHtml` only maps native settings into it.
The browser uses the same compiled builder through `ShelestCore.readerDocument`.
Android consumes `implementation(project(":shared"))`; web consumes the Kotlin/JS
ES module with generated TypeScript types through `@shelest/shared`.

## Build

Use Android Studio's JDK 17, the Android SDK and the project's Gradle version.
`npm install` / `npm run build` in `web` build the shared JS module automatically.
Set `GRADLE_EXECUTABLE` to a Gradle executable if neither a wrapper nor a cached
Gradle distribution exists. Node.js must be on PATH. Gradle obtains Yarn itself.

Direct tasks: `:shared:jsBrowserProductionLibraryDistribution`, `:shared:jvmTest`,
`:app:assembleDebug`. Check the actual JS export with
`node web/tests/shared-core.test.mjs`; it runs in Node without a browser runner.

Deploy only `web/dist` and the existing server package. Compiled shared code is
already included in the browser bundle; the VDS needs no Kotlin compiler/Gradle.

## Migration boundary

The website currently keeps React. EPUB.js is now a package/CFI parsing adapter,
not a second paging renderer. Text books and CBZ use the shared document engine;
PDF still has platform renderers (Android PdfRenderer and browser PDF.js).
The common UI slices are implemented in shared-ui; this is not a complete
all-screen Compose Multiplatform migration. Room schemas, IndexedDB stores, content hashes, encrypted credentials,
and API payloads stay compatible. Shared code holds no passwords or tokens.

Step 2 uses typed models in `commonMain/com/shelest/shared/sync`.
`SyncCoordinator` is an I/O-free per-client state machine: owner, entitlement,
cursor, reader state, and generation-fenced runs. Android serializes access under
a lock; the JS adapter exports `SharedSyncSession`. Network, cookies/Keystore,
SQLite/IndexedDB, local tombstone persistence, file hashing and provider login
remain platform adapters. No shared engine ever owns credentials.

Settings validation is shared, but existing platform defaults and enum IDs remain
compatible. This is not cloud synchronization of appearance preferences. Native
Room entities and web Blob/file models deliberately remain platform-specific.
Accounts and books still use the existing API; no server schema migration.
Explicit user preferences override defaults, including disabled auto-sync.

Additional verification: `:shared:jvmTest`, `node web/tests/sync-core.test.mjs`,
`node web/tests/cloud-position.test.mjs`, and Android
`SharedSyncIntegrationTest` on a disposable emulator.

Steps 3 and 4 are implemented for account/settings/library/reader chrome and the
existing debug APK workflow. Use scripts/build-release.ps1 -BrowserTests for
the unified build; -CheckOnly validates primary local tools without a build.
Publishing remains an explicit separate step. See ../PROJECT_HANDOFF.md.

See `MIGRATION.md` for the staged migration and compatibility/verification notes.
