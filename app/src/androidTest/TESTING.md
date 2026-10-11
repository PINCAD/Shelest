# Testing on a personal device

Do not run `connectedDebugAndroidTest` on a device containing user data.
The Gradle device runner removes the target application after testing, including
its private library, preferences and credentials. Use a disposable emulator for
that task.

For a personal device, first back up private application data and stop if a
complete backup cannot be verified. Build with `assembleDebug` and
`assembleDebugAndroidTest`, install both APKs with `adb install -r`, then execute
`adb shell am instrument -w com.lexiread.app.test/androidx.test.runner.AndroidJUnitRunner`.
Do not uninstall the target package or clear its data during cleanup.

Repository and migration tests use temporary databases, not the user's database.
CollectionsTest and LibraryCollectionsIntegrationTest are emulator-only: they
temporarily create synthetic books and mutate the application journal/database.
Never run these collection tests on a reader's personal device, even with backup.
Reader WebView tests use synthetic documents and temporarily replace the activity
view. Restart the normal activity after completing the instrumentation run.
