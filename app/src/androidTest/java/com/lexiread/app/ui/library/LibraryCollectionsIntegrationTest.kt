package com.lexiread.app.ui.library

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lexiread.app.LexiReadApp
import com.lexiread.app.MainActivity
import com.lexiread.app.data.cloud.addToCollection
import com.lexiread.app.data.cloud.editCollection
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.FileType
import com.lexiread.app.ui.settings.HomeSettings
import com.lexiread.app.ui.theme.LexiReadTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Full native library adapter + durable guest journal, on a disposable emulator only. */
class LibraryCollectionsIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun visit(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
        for (index in 0 until node.childCount) visit(node.getChild(index), label)?.let { return it }
        return null
    }
    private fun waitNode(label: String): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            visit(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return it }
            Thread.sleep(100)
        }
        error("Missing collection UI node: $label")
    }
    private fun click(label: String) {
        var node = waitNode(label)
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun nativeLibraryResolvesCoversManualOrderAndSafeCollectionDeletion() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as LexiReadApp
        assertNull("Never run this on a reader's signed-in phone", app.cloud.account.value)
        val suffix = UUID.randomUUID().toString()
        val files = (1..2).map { File.createTempFile("collection-library-$it-", ".txt", app.cacheDir).apply { writeText("Synthetic $it $suffix") } }
        val covers = (1..2).map { index ->
            File.createTempFile("collection-cover-$index-", ".png", app.cacheDir).apply {
                val bitmap = android.graphics.Bitmap.createBitmap(240, 360, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(if (index == 1) 0xff477579.toInt() else 0xff9b7741.toInt())
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 22f }
                canvas.drawText("SHELEST", 24f, 54f, paint); paint.textSize = 70f; canvas.drawText(index.toString(), 90f, 220f, paint)
                outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        val dao = app.database.bookDao()
        val ids = files.mapIndexed { index, file -> dao.insertBook(Book(title = if (index == 0) "Первый том" else "Второй том",
            author = "Тестовый автор", filePath = file.path, fileType = FileType.TXT, coverPath = covers[index].path, readingPage = 254)) }
        val books = ids.map { dao.getBookById(it)!! }
        val group = app.cloud.addToCollection(null, "Тестовая серия", books)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity, LibraryViewModelFactory(app.bookRepository, app.statsRepository, app.cloud))[LibraryViewModel::class.java]
                activity.setContent { LexiReadTheme { LibraryScreen(model, HomeSettings(showStats = false), false, {}, {}, {}, {}, {}, {}) } }
            }
            waitNode("Открыть коллекцию: Тестовая серия")
            val logo = android.graphics.Rect().also { waitNode("Шелест").getBoundsInScreen(it) }
            val add = android.graphics.Rect().also { waitNode("Добавить книги").getBoundsInScreen(it) }
            assertTrue("Brand/count must not expand the top bar on a narrow screen", kotlin.math.abs(logo.centerY() - add.centerY()) < 4)
            Thread.sleep(300)
            screenshot("native-collection-library.png")
            click("Открыть коллекцию: Тестовая серия")
            waitNode("Сортировка: Порядок томов"); waitNode("Открыть Первый том"); waitNode("Открыть Второй том")
            click("Изменить коллекцию"); click("Выше: Второй том"); click("Сохранить")
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            var reordered = false
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                val first = android.graphics.Rect().also { waitNode("Открыть Первый том").getBoundsInScreen(it) }
                val second = android.graphics.Rect().also { waitNode("Открыть Второй том").getBoundsInScreen(it) }
                reordered = second.top < first.top || (second.top == first.top && second.left < first.left)
                if (reordered) break
                Thread.sleep(100)
            }
            assertTrue("Saved volume order must be displayed, not just stored", reordered)
            screenshot("native-collection-order.png")
            click("Изменить коллекцию"); click("Удалить коллекцию"); click("Удалить")
            waitNode("Открыть Первый том"); waitNode("Открыть Второй том")
            assertTrue(ids.all { dao.getBookById(it)?.readingPage == 254 })
            assertTrue(files.all { it.exists() })
            var node = waitNode("Открыть Первый том")
            while (!node.isLongClickable && node.parent != null) node = node.parent
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            waitNode("В коллекцию")
            val width = instrumentation.targetContext.resources.displayMetrics.widthPixels
            for (label in listOf("Отменить выделение", "В коллекцию", "В избранное", "Редактировать книгу", "Удалить выбранные книги")) {
                val bounds = android.graphics.Rect().also { waitNode(label).getBoundsInScreen(it) }
                assertTrue("Top action must stay in a narrow screen: $label", bounds.left >= 0 && bounds.right <= width)
            }
            screenshot("native-collection-selection.png")
            click("В коллекцию"); waitNode("Новая коллекция"); screenshot("native-collection-picker.png")
            click("Отмена"); click("Отменить выделение")
        } finally {
            scenario.close()
            if (app.cloud.personal.collections("").any { it.id == group }) app.cloud.editCollection(group, delete = true)
            ids.forEach { dao.getBookById(it)?.let { book -> dao.deleteBook(book) } }
            (files + covers).forEach { it.delete() }
        }
    }
}
