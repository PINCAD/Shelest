# Device sync preferences

Auto-sync is on by default on each Android device and in each browser. Explicitly
disabled device preferences remain disabled. Defaults and rules live in the KMP
shared module. It sends debounced
reading positions, syncs on returning to the library/foreground and checks every
minute while visible. No full file sync runs over an open reader. Manual sync
remains available with auto-sync disabled.

Cross-device deletion defaults to enabled and requires an explicit confirmation
in the library. Disabled means local-only removal; the content hash is hidden on
that device to prevent immediate downloading again. With this setting disabled,
remote deletions also preserve the local file, but it cannot recreate a deleted
server record.

Pending deletions are persisted per account and retry after reconnection, even
with auto-sync off. DELETE includes accountId so a browser session change cannot
apply another account's pending action. Remote devices apply deletion on their
next automatic or manual sync (not while a book is open).

The server deletes the file and metadata, keeping only an account-scoped SHA-256
tombstone. Old offline clients cannot recreate the record. Explicit user imports
send restoreDeleted=true once; ordinary sync and cloud downloads never do.

Verification: Django syncapi tests, web/tests/cloud-position.test.mjs, Android
testDebugUnitTest. deploy/check_password_sync.py uses a disposable account over
public HTTPS, checks disk/quota reclamation and tombstone handling, then removes
only its own fixture/account. Never run Gradle connectedDebugAndroidTest on a
personal phone: use the documented direct instrumentation workflow instead.
