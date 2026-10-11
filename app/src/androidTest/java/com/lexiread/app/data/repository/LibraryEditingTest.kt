package com.lexiread.app.data.repository

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lexiread.app.data.db.AppDatabase
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.Annotation
import com.lexiread.app.data.model.AnnotationType
import com.lexiread.app.data.model.FileType
import com.lexiread.app.data.model.ReadingPosition
import com.lexiread.app.data.model.readingPosition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryEditingTest {
    @Test fun pdfDrawingSnapshotRestoresStrokeAndSeveralUndoRedoSteps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val bookId = db.bookDao().insertBook(Book(title = "Synthetic drawing history", filePath = "history.pdf", fileType = FileType.PDF))
            val dao = db.annotationDao()
            val first = Annotation(bookId = bookId, type = AnnotationType.DRAWING, color = 0xff315cb8.toInt(),
                pageIndex = 2, pathData = "first")
            val firstId = dao.insert(first)
            val firstSaved = first.copy(id = firstId)
            val second = first.copy(id = 0, pathData = "second")
            val secondId = dao.insert(second)
            val secondSaved = second.copy(id = secondId)

            dao.delete(secondSaved) // Undo deletion restores the same stroke and identity.
            dao.replaceDrawingState(listOf(secondId), listOf(secondSaved))
            assertEquals(listOf(firstId, secondId), dao.getOnce(bookId).map { it.id }.sorted())
            dao.replaceDrawingState(listOf(firstId, secondId), emptyList()) // Undo two draw actions.
            assertTrue(dao.getOnce(bookId).isEmpty())
            dao.replaceDrawingState(listOf(firstId, secondId), listOf(firstSaved)) // Redo first draw.
            assertEquals("first", dao.getOnce(bookId).single().pathData)
            dao.replaceDrawingState(listOf(firstId, secondId), listOf(firstSaved, secondSaved)) // Redo second.

            val fragments = listOf(firstSaved.copy(id = 0, pathData = "left"), firstSaved.copy(id = 0, pathData = "right"))
            val replacement = dao.eraseDrawingPartsWithSnapshot(firstSaved, fragments.map { it.pathData!! })
            assertEquals(listOf("left", "right", "second"), dao.getOnce(bookId).mapNotNull { it.pathData }.sorted())
            val fragmentIds = replacement.map { it.id } + secondId
            dao.replaceDrawingState(fragmentIds, listOf(firstSaved, secondSaved)) // Undo partial erase.
            assertEquals(listOf("first", "second"), dao.getOnce(bookId).mapNotNull { it.pathData }.sorted())
            dao.replaceDrawingState(listOf(firstId, secondId), replacement + secondSaved) // Redo partial erase.
            assertEquals(listOf("left", "right", "second"), dao.getOnce(bookId).mapNotNull { it.pathData }.sorted())
        } finally { db.close() }
    }

    @Test fun twentyMegabyteBookReopensFromOnlyItsSavedSegment() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("large-book-cache-test", ".txt", context.cacheDir)
        try {
            file.bufferedWriter().use { writer ->
                val line = "Book words for repeat loading test. ".repeat(65)
                repeat(10000) { writer.appendLine(line).appendLine() }
            }
            assertTrue(file.length() > 20L * 1024 * 1024)
            val id = db.bookDao().insertBook(Book(title = "Synthetic 20 MB", filePath = file.path, fileType = FileType.TXT))
            val repo = BookRepository(context, db.bookDao(), db.likedTitleDao())
            val start = android.os.SystemClock.uptimeMillis()
            val first = repo.loadBook(repo.getBook(id)!!)
            val parsedMs = android.os.SystemClock.uptimeMillis() - start
            assertTrue(first.segments.size > 100)
            val exact = ReadingPosition(2, 4, 512, "layout")
            repo.saveReadingPosition(id, exact)
            val repeatStart = android.os.SystemClock.uptimeMillis()
            val reopened = repo.loadBook(repo.getBook(id)!!)
            val cachedMs = android.os.SystemClock.uptimeMillis() - repeatStart
            assertEquals(2, reopened.segmentIndex)
            assertEquals(com.lexiread.app.domain.parser.DocumentCache.segmentHtml(file, 2), reopened.html)
            assertTrue(reopened.html.length < 100000)
            android.util.Log.i("ShelestPerformance", "Synthetic ${file.length()} bytes: first=$parsedMs ms, repeat=$cachedMs ms")
            Unit
        } finally {
            com.lexiread.app.domain.parser.DocumentCache.clear(file)
            file.delete(); db.close()
        }
    }
    @Test fun libraryMenuOpensRenameDialogAndSavesName() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("rename-ui-test", ".txt", context.cacheDir).apply { writeText("words") }
        val dao = db.bookDao()
        val id = dao.insertBook(Book(title = "Menu test book", filePath = file.path, fileType = FileType.TXT))
        val secondFile = File.createTempFile("batch-delete-test", ".txt", context.cacheDir).apply { writeText("second words") }
        val secondId = dao.insertBook(Book(title = "Second selection book", filePath = secondFile.path, fileType = FileType.TXT))
        val repo = BookRepository(context, dao, db.likedTitleDao())
        val vm = com.lexiread.app.ui.library.LibraryViewModel(repo, ReadingStatsRepository(context, db.readingDayDao()))
        val scenario = androidx.test.core.app.ActivityScenario.launch(com.lexiread.app.MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.setContent {
                    com.lexiread.app.ui.theme.LexiReadTheme(darkTheme = false) {
                        com.lexiread.app.ui.library.LibraryScreen(vm,
                            home = com.lexiread.app.ui.settings.HomeSettings(showStats = false),
                            showOnboarding = false, onOnboardingDone = {}, onBookClick = {},
                            onSettingsClick = {}, onVocabularyClick = {}, onDiscoverClick = {}, onStatsClick = {})
                    }
                }
            }
            fun find(node: android.view.accessibility.AccessibilityNodeInfo?, predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
                return null
            }
            fun waitNode(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo {
                val deadline = android.os.SystemClock.uptimeMillis() + 10000
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
                    Thread.sleep(100)
                }
                error("Expected accessible UI control was not found")
            }
            fun action(node: android.view.accessibility.AccessibilityNodeInfo, code: Int) {
                var candidate: android.view.accessibility.AccessibilityNodeInfo? = node
                while (candidate != null) {
                    if (candidate.performAction(code)) return
                    candidate = candidate.parent
                }
                error("UI control did not accept action $code")
            }
            action(waitNode { it.contentDescription?.toString() == "Открыть Menu test book" }, android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)
            action(waitNode { it.contentDescription?.toString() == "Редактировать книгу" },
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            val rename = waitNode { it.text?.toString() == "Переименовать" }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "library-menu-test.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot.recycle()
            action(rename, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            // Wait for the dialog window, otherwise the library search field can still
            // be the first editable node for one frame after the toolbar click.
            waitNode { it.text?.toString() == "Название книги" }
            instrumentation.waitForIdleSync()
            val input = waitNode { it.isEditable }
            assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Renamed in UI") }))
            action(waitNode { it.text?.toString() == "Сохранить" }, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (dao.getBookById(id)?.title != "Renamed in UI" && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            assertEquals("Renamed in UI", dao.getBookById(id)?.title)
            // The editable dialog may still expose the new title for a frame after
            // the database write. Target the actual shelf card, not that text field.
            action(waitNode { it.contentDescription?.toString() == "Открыть Renamed in UI" }, android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)
            waitNode { it.contentDescription?.toString() == "Выбрано: 1" }
            action(waitNode { it.contentDescription?.toString() == "Выбрать Second selection book" }, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            waitNode { it.text?.toString() == "Выбрано: 2" }
            action(waitNode { it.contentDescription?.toString() == "Удалить выбранные книги" }, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            waitNode { it.text?.toString() == "Удалить выбранные книги?" }
            action(waitNode { it.text?.toString() == "Отмена" }, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            waitNode { it.text?.toString() == "Выбрано: 2" }
            assertNotNull(dao.getBookById(id)); assertNotNull(dao.getBookById(secondId))
            action(waitNode { it.contentDescription?.toString() == "Удалить выбранные книги" }, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            action(waitNode { it.text?.toString() == "Удалить" }, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            val deleteDeadline = android.os.SystemClock.uptimeMillis() + 5000
            while ((dao.getBookById(id) != null || dao.getBookById(secondId) != null) && android.os.SystemClock.uptimeMillis() < deleteDeadline) Thread.sleep(100)
            assertNull(dao.getBookById(id)); assertNull(dao.getBookById(secondId))
        } finally { scenario.close(); file.delete(); secondFile.delete(); db.close() }
    }
    @Test fun renameAndCustomCoverPreserveFileIdentityAndPlace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val bookFile = File.createTempFile("library-edit-test", ".txt", context.cacheDir).apply { writeText("Original book words") }
        val image = File.createTempFile("cover-test", ".png", context.cacheDir)
        var cover: File? = null
        try {
            val bitmap = Bitmap.createBitmap(1600, 2200, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val dao = db.bookDao()
            val id = dao.insertBook(Book(title = "Original", author = "Author", filePath = bookFile.path, fileType = FileType.TXT))
            val repo = BookRepository(context, dao, db.likedTitleDao())
            val position = ReadingPosition(0, 19, 1024, "exact-layout", 0.0)
            repo.saveReadingPosition(id, position)
            repo.renameBook(id, "  New name  ")
            repo.setCover(id, Uri.fromFile(image))
            val updated = repo.getBook(id)!!
            cover = File(updated.coverPath!!)
            assertEquals("New name", updated.title)
            assertEquals("Author", updated.author)
            assertEquals(bookFile.path, updated.filePath)
            assertEquals("Original book words", bookFile.readText())
            assertEquals(position, updated.readingPosition)
            assertTrue(cover!!.isFile)
            val decoded = android.graphics.BitmapFactory.decodeFile(cover!!.path)
            assertTrue(maxOf(decoded.width, decoded.height) <= 1200)
            decoded.recycle()
            try { repo.renameBook(id, "   "); fail("Blank names must be rejected") }
            catch (_: IllegalArgumentException) { }
            assertEquals("New name", repo.getBook(id)!!.title)
        } finally {
            // Only this test's own synthetic files and in-memory database.
            cover?.delete(); image.delete(); bookFile.delete(); db.close()
        }
    }
}
