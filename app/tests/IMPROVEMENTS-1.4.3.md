# Android 1.4.3 (versionCode 7)

## User controls

- Long press a library book → Переименовать / Обложка. Rename changes the library title,
  not the source file or content hash. Local reading positions and notes retain the same book ID.
- Custom covers are copied into private storage, decoded with bounded resolution (1200 px),
  and stored as JPEG. Input limit: 20 MB. Covers are local; cloud-cover transport is not included.
- Tap the reader's page counter → enter a page number or drag the slider, then Перейти.
  Segmented text books offer exact global navigation once all segment counts are measured.
  Until then the dialog explicitly offers the current segment only; no percentage guessing.
- In PDF tools: Извлечь текст → select/copy the current page, or save the whole PDF as UTF-8 TXT.
  Extraction is on-device and uses an existing text layer. Image-only scans need separate OCR.
- In non-drawing mode, one finger pans a magnified PDF in both axes. A new outward swipe
  at the page edge can turn the page. Pinch and two-finger pan remain available.

## Performance and compatibility

- Book is immutable to Compose; lazy grid book content types are reusable.
- No file existence checks on the composition path. Coil owns cover loading/fallback.
- Library filtering and sorting run on Default rather than the UI dispatcher.
- Text-document caches now include small/compressed files, not just files larger than 1 MB.
  Already-read small books preserve their old whole-document coordinates when first cached,
  to avoid moving existing positions/bookmarks. Existing valid caches are retained.
- PDF appears before PDFBox finishes opening its optional text layer. Failed text-layer loading
  is distinguishable from ongoing loading, rather than leaving an indefinite progress indicator.
- One PDF renderer per reading session and a 24 MB bitmap LRU avoid reopening/re-rendering
  recently viewed pages. Bitmap ownership is retained safely by visible Compose pages.
- PDF text cache is bounded to 32 pages; TXT export writes incrementally on IO.
- Database schema unchanged. No destructive migration, uninstall or data clearing.
- No web/server deployment in this change set; native library/PDF adapters are platform-specific.

## Verification (2026-10-08)

- Android APK and instrumentation APK compiled successfully.
- 117 Android/common JVM tests, zero failures.
- Disposable Pixel_9_Pro emulator only; no personal phone or real cloud account used.
- LibraryEditingTest: actual accessible UI long-press menu → rename dialog → saved name;
  custom cover bounded decode + original file/identity/place retained;
  synthetic 23,420,000-byte TXT reopened from exact saved segment.
- Synthetic TXT preparation 3579 ms; cached saved-segment loading 8 ms.
  This excludes network and WebView layout and is not a universal end-to-end speed guarantee.
- PdfImprovementsTest: extraction from text pages; image-only page returns empty text;
  repeated page rendering reuses bitmap; real one-finger touch input changes the screenshot
  without turning the page; direct jump to PDF page 254 in a 260-page synthetic file.
- ReadingPositionPersistenceTest: immediate reopen and new repository preserve exact segment/page.
- ReadingPositionTest: all five real Android WebView position/pagination tests passed.
- Total: 117 JVM tests and 12 emulator instrumentation tests passed.
- Menu screenshot: app/build/library-menu-test.png (synthetic library).

The emulator's new Android API is incompatible with the existing Espresso 3.5.1 input injector.
New UI tests use ActivityScenario + platform accessibility/UiAutomation input, without changing
the application's dependencies to solve a test-runtime issue.
