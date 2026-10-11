# 1.4.5 — first common UI slice (step 3a)

- Common Kotlin Compose layout for account summary, Premium quota, actions,
  loading/error state and both synchronization switches.
- Android uses native Material 3 primitives/theme. Browser uses accessible DOM
  through Compose HTML, loaded only when account/settings is opened.
- Whole synchronization rows activate their switch; rounded touch targets,
  explicit switch semantics, keyboard focus and narrow-screen wrapping.
- Credentials, provider/password forms, storage, navigation and app themes remain
  platform adapters; no Room/IndexedDB/key migration or user-data reset.
- The rest of step 3 (appearance, library, reader chrome) is not complete.

Verification: 107 Android JVM + 24 shared JVM tests, browser common UI test and
reader TXT/EPUB/CBZ regression suite, synthetic native common account/prefs test.
Web mock tests cover switched-off preference persistence, 320 px/dark layout,
keyboard sync action, no password form while signed in, and repeated disposal.
UI does not access real cloud accounts during tests. Personal phone not modified.

Published website passed common UI and production reader browser tests.
Android ReadingPositionTest passed all 5 checks on a 4 GB disposable emulator;
the initial 2 GB emulator run was killed for low memory by Android.
