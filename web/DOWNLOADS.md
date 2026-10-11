# Android download page

The public page is `/download/`. The current APK is copied to
`web/public/downloads/shelest-latest.apk`; its version, size, signing variant and
SHA-256 are published in `web/public/downloads/latest.json`.

`scripts/build-release.ps1` builds QA Android APKs and the web client, and packages
the site for explicit publication. It preserves the existing public APK and metadata;
it must never replace a signed release download with a debug/test APK. The APK is
intentionally excluded from Git. Before publishing the site, update the APK in
`web/public/downloads` and its `latest.json` to the intended signed release.
Write JSON as UTF-8 without BOM. Upload the generated static archive through the
allowlisted static-site publishing flow, or explicitly replace only the two download
files on the server with backups and temporary files followed by atomic rename.

A user-owned production signing key exists locally, outside the repository.
Use `scripts/sign-release-local.ps1` with the owner's permission and local masked
password entry. Verify the production certificate before distributing an APK.
Keep using the same release signing key and applicationId for upgrades; increase
versionCode. Do not change the signature for an existing installation without
planning a migration: Android rejects the update, and uninstalling can remove data.
See `START_HERE_20261011.md` for verified certificate fingerprints and release status.
