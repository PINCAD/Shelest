# 1.4.4 — shared application logic

- Android and web use the Kotlin common sync protocol/models, conflict and
  tombstone rules, account/login validation and numerical settings normalization.
- Per-session generation fences reject obsolete sync work after reader opening,
  account/entitlement changes, reimport/deletion or disabling automatic sync.
- Explicit offline reimport clears pending cloud deletion. Premium file transfer
  retains size limits and SHA-256 verification; free accounts never upload books.
- Cloud positions require exact native coordinates, PDF pages or a unique text
  anchor. Missing/ambiguous coordinates do not replace progress with a percentage.
- Existing legacy reading timestamps migrate once rather than changing whenever
  a book opens. Initial layout/restyling is not automatically a new reading event.
- Local queued position writes and accepted cloud writes are serialized to avoid
  an older local SQLite write replacing a newer restored place.
- Package, signing configuration, Room/IndexedDB schemas, credential storage,
  existing book files, notes and settings identifiers are unchanged.

The common module is source-shared, not a rewrite of Android into a browser app.
The UI and device integration remain platform-specific. Step 3 (common interface)
and step 4 (unified release) are separate work; this release does not synchronize
appearance preferences across accounts/devices.

Verification: 107 Android + 24 shared JVM tests; Kotlin/JS contract/parity checks,
mock sync/account races; browser shared-reader regression and production guest
smoke; 15 Android instrumentation tests passed in separate emulator groups.
Combined UI stress execution hit the emulator memory limit (renderer LOW_MEMORY
and app SIGKILL); separate runs completed. Tests use synthetic books and temporary
databases. The attached personal phone was not modified or reinstalled.

Static website published to shelest-book.ru with server rollback archive
`/var/backups/shelest/web-before-shared-reader-20261008-153015.tar.gz`.
