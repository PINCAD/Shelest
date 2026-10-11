# Android reader virtual regression

`paged-reader.test.cjs` runs the CSS and JavaScript extracted from the Android
shared `buildReaderHtml` template in an isolated Chromium frame. Android's
`buildPagedHtml` is now only a platform adapter. The test does not touch a
phone, personal browser profile, account or server. The long book is synthetic.

Run with Node.js and Playwright available:

```powershell
$env:PLAYWRIGHT_MODULE = 'path/to/node_modules/playwright'
$env:BROWSER_EXECUTABLE = 'path/to/chrome.exe'
node app/tests/paged-reader.test.cjs
```

The environment variables are optional when Playwright and its Chromium runtime
are installed normally. `TRACE_PAGING=1` logs scroll updates when investigating
an animation. Generated fixtures/screenshots go to ignored `app/build/pagination`.

Checks cover long EPUB-like chapter wrappers, opening at page 19, viewport width
and height changes, exact reopening, a synced character anchor with a foreign
page number, final-page scroll range, fonts/margins/line spacing, and slide/fade/
flip/no-animation turns. The column box must stay one viewport wide and the
scroll offset must agree with the current page after every transition.

Native curl rendering should additionally be tested on an Android emulator. Do
not run connected instrumentation tests on a personal phone: test installation
and cleanup can remove its app data. Use an explicitly selected emulator serial.

Android smoke check (2026-10-08): APK 1.4.1 was installed with `install -r` on
the isolated, read-only Pixel 9 Pro emulator (API 37, serial `emulator-5580`).
The generated TXT fixture had 1,362 portrait pages. Sequential swipes reached
page 19 without split columns; rotating to landscape retained character 16,385
(page 24 in the new layout). Native curl turns and reopening from the library
retained page 22. The crash log was empty. Screenshots are in `app/build/pagination`.
Reopen from the library, not another ACTION_VIEW import: importing creates a new
book entry and is not a saved-position test. No personal phone data was changed.
