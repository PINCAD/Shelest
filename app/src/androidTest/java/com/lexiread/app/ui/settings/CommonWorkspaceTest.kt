package com.lexiread.app.ui.settings

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lexiread.app.MainActivity
import com.lexiread.app.ui.theme.LexiReadTheme
import com.shelest.ui.*
import com.lexiread.app.ui.reader.ReaderSettingsSheet
import com.lexiread.app.ui.reader.ReaderDisplayEffects
import org.junit.Assert.*
import org.junit.Test

/** Synthetic UI only; UiAutomation avoids old Espresso reflection on current Android. */
class CommonWorkspaceTest {
    private fun bounds(text: String) = android.graphics.Rect().also { waitNode(text).getBoundsInScreen(it) }

    @Test fun readingViewportUsesSystemInsetsAndDoesNotResizeWithChrome() {
        val browser = java.util.concurrent.atomic.AtomicReference<android.webkit.WebView>()
        val chrome = mutableStateOf(true)
        withContent({ Box(Modifier.fillMaxSize()) {
            com.lexiread.app.ui.reader.PagedHtmlReader(
                html = "<h1>Проверка страницы</h1>" + "<p>Текст для проверки границ экрана.</p>".repeat(90),
                settings = ReaderSettings(), onToggleChrome = {}, onWordSelected = {},
                onPageChange = { _, _, _, _, _ -> }, onWebViewReady = { browser.set(it) })
            if (chrome.value) Text("Тестовая панель", Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                .clickable { chrome.value = false })
        } }) { scenario ->
            awaitDisplay { browser.get()?.height?.let { it > 0 } == true }
            var before = 0
            scenario.onActivity { activity ->
                val view = browser.get()
                before = view.height
                val decor = activity.window.decorView
                val insets = decor.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
                assertEquals("Do not reserve toolbar-sized empty gutters in the book",
                    decor.height - insets.top - insets.bottom, view.height)
            }
            click("Тестовая панель")
            Thread.sleep(250)
            scenario.onActivity { assertEquals("Chrome must not repaginate the book", before, browser.get().height) }
        }
    }

    @Test fun readerSettingsSingleWindowAndSteppersRemainStable() {
        val state = mutableStateOf(ReaderSettings())
        withContent({ ReaderSettingsSheet(state.value,
            onFontSizeChange = { state.value = state.value.copy(fontSize = it) },
            onFontFamilyChange = { state.value = state.value.copy(fontFamily = it) }, onLineHeightChange = {}, onMarginChange = {},
            onReadingModeChange = {}, onPageAnimationChange = {}, onJustifyTextChange = {},
            onHorizontalScrollingChange = {}, onShowPageNumbersChange = {}, onShowProgressBarChange = {},
            onPreferNetworkVoiceChange = {}, onDismiss = {}) }) {
            waitNode("Увеличить: Размер"); Thread.sleep(600)
            val header = bounds("Оформление книги")
            val increase = bounds("Увеличить: Размер")
            repeat(4) { click("Увеличить: Размер") }
            assertEquals(increase, bounds("Увеличить: Размер"))
            click("Выбрать: Шрифт"); click("Шрифт: Lora"); Thread.sleep(250)
            assertEquals(ReaderFontFamily.LORA, state.value.fontFamily)
            assertEquals(header, bounds("Оформление книги"))
            assertEquals(increase, bounds("Увеличить: Размер"))
            assertNull(visit(instrumentation.uiAutomation.rootInActiveWindow) { it.className == "android.widget.TabWidget" })
        }
    }

    @Test fun pdfToolTargetsStayFixedAcrossModesAndOptions() {
        val mode = mutableStateOf(com.lexiread.app.ui.reader.PdfAnnotationMode.NONE)
        val expanded = mutableStateOf(false)
        withContent({ Column(Modifier.fillMaxSize().systemBarsPadding(),
            verticalArrangement = Arrangement.Bottom, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            com.lexiread.app.ui.reader.PdfToolsPanel(mode.value, { mode.value = it }, expanded.value,
                { expanded.value = !expanded.value }, 0xFF315CB8.toInt(), {}, 2f, {}, 1f, {}, 0,
                false, false, {}, {}, {})
            com.lexiread.app.ui.reader.ReaderBottomBar(false, false, {}, {}, {}, {},
                pdfMode = mode.value,
                onHideChrome = {}, onPdfToolsClick = {})
        } }) {
            val pen = bounds("Перо")
            val settings = bounds("Настройки чтения")
            click("Перо")
            awaitDisplay { mode.value == com.lexiread.app.ui.reader.PdfAnnotationMode.DRAW }
            assertEquals(settings, bounds("Настройки чтения"))
            click("Показать настройки пера"); Thread.sleep(400)
            assertEquals(pen, bounds("Перо"))
            click("Ластик"); Thread.sleep(250)
            assertEquals(com.lexiread.app.ui.reader.PdfAnnotationMode.ERASE_PART, mode.value)
            assertEquals(pen, bounds("Перо"))
            assertEquals(settings, bounds("Настройки чтения"))
            val snapshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "pdf-stable-tools.png").outputStream().use {
                snapshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            snapshot.recycle()
            assertNull(find("Выключить ластик")); assertNull(find("Завершить режим"))
            click("Ластик")
            awaitDisplay { mode.value == com.lexiread.app.ui.reader.PdfAnnotationMode.NONE }
            assertEquals(settings, bounds("Настройки чтения"))
        }
    }

    @Test fun collectionCardsStayCompactAndLoadOnlyThreeCovers() {
        val rendered = mutableSetOf<String>()
        var opened = ""
        val books = (1..100).map { ShelfBook(it.toString(), "Том $it") }
        val title = "Песнь льда и пламени"
        withContent({ Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            LibraryShelf(books.take(2), "", "TITLE", 2, false, { _, _ -> }, afterControls = {
                CollectionShelf(listOf(UiCollection("series", title, books, 100)), { opened = it }) {
                    rendered.add(it.id); Surface { Text(it.title) }
                }
            })
        } }) {
            val node = waitNode("Открыть коллекцию: $title")
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            val density = instrumentation.targetContext.resources.displayMetrics.density
            assertTrue(bounds.width() <= 210 * density)
            assertEquals(setOf("1", "2", "3"), rendered)
            click("Открыть коллекцию: $title")
            assertEquals("series", opened)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "collections-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun collectionEditorMovesAndRemovesMembersWithoutDeletingBooks() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        var saved = emptyList<String>()
        val visible = mutableStateOf(true)
        withContent({ if (visible.value) com.lexiread.app.ui.library.CollectionEditor(
            com.shelest.shared.BookCollection("12345678-1234-1234-1234-123456789abc", "Моя серия", listOf(a, b)),
            mapOf(a to "Первый том", b to "Второй том"), { visible.value = false },
            { _, members -> saved = members; visible.value = false }, { error("Unexpected collection deletion") }) }) {
            waitNode("Порядок томов")
            click("Выше: Второй том")
            click("Убрать из коллекции: Первый том")
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "collection-editor-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            click("Сохранить")
            assertEquals(listOf(b), saved)
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun visit(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if(node == null) return null
        if(match(node)) return node
        for(i in 0 until node.childCount) visit(node.getChild(i),match)?.let { return it }
        return null
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        // Compose can reuse an icon node while its label/enabled state changes.
        // Query live semantics, not an Accessibility cache from the previous mode.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return visit(instrumentation.uiAutomation.rootInActiveWindow) {
        it.contentDescription?.toString() == text || it.text?.toString() == text || it.text?.toString()?.startsWith(text+"\n") == true
        }
    }
    private fun waitNode(text: String): AccessibilityNodeInfo {
        val end=android.os.SystemClock.uptimeMillis()+10000
        while(android.os.SystemClock.uptimeMillis()<end) {
            find(text)?.let {return it}; Thread.sleep(100)
        }
        error("Missing common UI node: $text")
    }
    private fun click(text: String) {
        var node=waitNode(text)
        while(!node.isClickable && node.parent!=null) node=node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun awaitDisplay(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(50)
        }
        error("Reader display effect did not settle")
    }

    @Test fun readerPaletteUsesSelectablePaperPreviews() {
        val settings = mutableStateOf(ReaderSettings())
        withContent({ ReaderSettingsSheet(settings.value,
            onFontSizeChange = {}, onFontFamilyChange = {}, onLineHeightChange = {}, onMarginChange = {},
            onReadingModeChange = {settings.value = settings.value.copy(readingMode = it)}, onPageAnimationChange = {},
            onJustifyTextChange = {}, onHorizontalScrollingChange = {}, onShowPageNumbersChange = {},
            onShowProgressBarChange = {}, onPreferNetworkVoiceChange = {}, onDismiss = {}) }) {
            click("Выбрать: Цвет страницы")
            waitNode("Цвет страницы: Серый")
            click("Цвет страницы: Серый")
            assertEquals(ReadingMode.GRAY, settings.value.readingMode)
            Thread.sleep(350)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "reader-settings-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun autoScrollPanelSeparatesStartPauseAndCollapse() {
        val running = mutableStateOf(false)
        val speed = mutableStateOf(3)
        var collapsed = false
        withContent({
            Column(Modifier.fillMaxSize().systemBarsPadding(), verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            com.lexiread.app.ui.reader.AutoScrollPanel(speed.value, running.value, true, { .5f },
                { speed.value = it }, { running.value = !running.value }, { collapsed = true })
            }
        }) {
            waitNode("Перелистывание каждые 35 с")
            assertFalse(running.value)
            click("Начать прокрутку")
            waitNode("Следующая страница через 18 с")
            assertTrue(running.value)
            Thread.sleep(350)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "auto-scroll-panel.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            click("Пауза")
            waitNode("Начать прокрутку")
            assertFalse(running.value)
            click("Свернуть автопрокрутку")
            assertTrue(collapsed)
        }
    }

    @Test fun accountSecurityIsExplicitAndDoesNotSubmitOnOpening() {
        var requests = 0
        val account = com.shelest.shared.sync.CloudAccount("synthetic", login = "reader", hasPassword = true)
        withContent({ AccountSecuritySheet(account, { requests++; org.json.JSONObject() }, {}, {}) }) { scenario ->
            waitNode("Безопасность аккаунта")
            assertEquals(0, requests)
            scenario.onActivity {
                assertTrue((it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0)
            }
            click("Настроить двухфакторную защиту")
            waitNode("Текущий пароль")
            waitNode("Получить ключ")
            assertEquals(0, requests)
            click("Отмена")
            waitNode("Логин и пароль")
        }
    }

    @Test fun readerBrightnessAndAutoScrollAreScopedToReader() {
        val active = mutableStateOf(true)
        val scrolling = mutableStateOf(false)
        val keep = mutableStateOf(false)
        var readerView: android.view.View? = null
        withContent({
            readerView = androidx.compose.ui.platform.LocalView.current
            if (active.value) ReaderDisplayEffects(.3f, keep.value, scrolling.value)
            Text("Проверка экрана")
        }) { scenario ->
            waitNode("Проверка экрана")
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(.3f, it.window.attributes.screenBrightness)
                assertFalse(readerView!!.keepScreenOn)
                scrolling.value = true
            }
            awaitDisplay { readerView?.keepScreenOn == true }
            scenario.onActivity { assertTrue(readerView!!.keepScreenOn); scrolling.value = false }
            awaitDisplay { readerView?.keepScreenOn == false }
            scenario.onActivity { assertFalse(readerView!!.keepScreenOn); keep.value = true }
            awaitDisplay { readerView?.keepScreenOn == true }
            scenario.onActivity { assertTrue(readerView!!.keepScreenOn); active.value = false }
            awaitDisplay { readerView?.keepScreenOn == false }
            scenario.onActivity { assertFalse(readerView!!.keepScreenOn); assertEquals(-1f, it.window.attributes.screenBrightness) }
        }
    }
    private fun withContent(block: @Composable () -> Unit, test: (ActivityScenario<MainActivity>) -> Unit) {
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { it.setContent { LexiReadTheme { Surface(Modifier.fillMaxSize()) { block() } } } }
            test(scenario)
        } finally {scenario.close()}
    }
    @Test fun appearanceRetainsSavedChoicesAndDispatchesThemeAccentReset() {
        val state=mutableStateOf(AppearanceState("SYSTEM","BLUE",false))
        val actions=java.util.Collections.synchronizedList(mutableListOf<String>())
        withContent({
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AppearancePanel(state.value, { key,value -> actions.add("$key=$value")
                    state.value=when(key) {
                        "themeMode" -> state.value.copy(theme=value)
                        "accentChoice" -> state.value.copy(accent=value)
                        else -> state.value.copy(showStats=value=="true")
                    }
                }) {actions.add("stats")}
            }
        }) {
            click("Тёмная"); click("Зелёный"); click("Фирменный"); click("Статистика и цель дня")
            assertEquals(listOf("themeMode=DARK","accentChoice=GREEN","accentChoice=DEFAULT","stats"),actions.toList())
            assertEquals("DARK",state.value.theme); assertEquals("DEFAULT",state.value.accent)
        }
    }
    @Test fun shelfIsLazyAndBookMenuPreservesEditingActions() {
        val books=(1..100).map {ShelfBook(it.toString(),"Книга $it")}
        val actions=java.util.Collections.synchronizedList(mutableListOf<String>())
        withContent({LibraryShelf(books,"","RECENT",100,false,{key,value->actions.add("$key:$value")})}) {
            var cover = waitNode("Открыть Книга 1")
            while (!cover.isLongClickable && cover.parent != null) cover = cover.parent
            assertTrue(cover.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            click("Переименовать")
            Thread.sleep(400) // Dropdown's exit animation removes its separate accessibility window.
            assertEquals(listOf("rename:1"),actions.toList())
            assertNull(find("Открыть Книга 100"))
            repeat(50) {
                if(find("Открыть Книга 100") == null) {
                    val scroll=visit(instrumentation.uiAutomation.rootInActiveWindow) {it.isScrollable}
                    assertNotNull(scroll)
                    scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(100)
                }
            }
            waitNode("Открыть Книга 100")
        }
    }
    @Test fun shelfCardsAlignTitlesAndDispatchOneOpenAction() {
        val actions = java.util.Collections.synchronizedList(mutableListOf<String>())
        val books = listOf(ShelfBook("1", "Короткая", "Автор", opened = true, progress = .42),
            ShelfBook("2", "Очень длинное название книги для двух строк", "Другой автор"))
        withContent({ Box(Modifier.fillMaxSize().systemBarsPadding()) {
            LibraryShelf(books, "", "TITLE", 2, false,
                { action, id -> actions.add("$action:$id") }, showCount = false, showAdd = false)
        } }) {
            var first = waitNode("Открыть Короткая")
            // Custom descriptions can be separate accessibility nodes; resolve the action
            // owner exactly like the production accessibility click helper.
            while (!first.isClickable && first.parent != null) first = first.parent
            assertTrue("The whole card is one click target", first.isClickable)
            assertTrue("The same card owns long press", first.isLongClickable)
            var ancestor = first.parent
            while (ancestor != null) {
                assertFalse("Do not nest book click targets", ancestor.isClickable)
                ancestor = ancestor.parent
            }
            val firstAuthor = android.graphics.Rect().also { waitNode("Автор").getBoundsInScreen(it) }
            val secondAuthor = android.graphics.Rect().also { waitNode("Другой автор").getBoundsInScreen(it) }
            assertEquals("Authors must align below two title lines", firstAuthor.top, secondAuthor.top)
            val cardBounds = android.graphics.Rect().also { first.getBoundsInScreen(it) }
            val density = instrumentation.targetContext.resources.displayMetrics.density
            assertTrue("Author must stay clear of the rounded bottom corners", cardBounds.bottom - firstAuthor.bottom >= 7 * density)
            waitNode("42%"); waitNode("Новая")
            val statusBounds = android.graphics.Rect().also { waitNode("42%").getBoundsInScreen(it) }
            assertTrue("Progress belongs on the cover, not beneath the author", statusBounds.bottom < firstAuthor.top)
            assertTrue(first.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals(listOf("open:1"), actions.toList())
            Thread.sleep(250)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "library-cards-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    @Test fun recentCoversStayCompactAtAnyWidth() {
        val books = (1..3).map { ShelfBook(it.toString(), "Книга $it", "Автор", opened = true, progress = .42) }
        withContent({ Box(Modifier.fillMaxSize().systemBarsPadding()) {
            LibraryShelf(books, "", "RECENT", 3, false, { _, _ -> }, showCount = false, showAdd = false,
                afterControls = { Text("Коллекции после недавних") })
        } }) {
            val panel = waitNode("Недавние книги")
            val panelBounds = android.graphics.Rect().also { panel.getBoundsInScreen(it) }
            val coverBounds = android.graphics.Rect().also { waitNode("Открыть Книга 1").getBoundsInScreen(it) }
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val collectionBounds = android.graphics.Rect().also { waitNode("Коллекции после недавних").getBoundsInScreen(it) }
            assertTrue("Collections must follow the recent panel", collectionBounds.top >= panelBounds.bottom)
            assertTrue(panelBounds.contains(coverBounds))
            assertTrue("Recent panel must not have a wide empty tail", panelBounds.width() <= 473 * density)
            assertTrue("Recent cover must not inflate on a tablet", coverBounds.width() <= 150 * density)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "library-recent-preview.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    @Test fun statisticsAndSearchHaveSeparateBoundsAndRemainInteractive() {
        val showStats = mutableStateOf(true)
        val query = mutableStateOf("")
        var statsClicks = 0
        withContent({
            // Narrow mobile width, with a populated header like the real library.
            Box(Modifier.width(320.dp).fillMaxHeight()) {
                LibraryShelf(listOf(ShelfBook("1", "Книга 1")), query.value, "RECENT", 1, false,
                    { key, value -> if (key == "query") query.value = value }, header = {
                        if (showStats.value) Box(Modifier.fillMaxWidth().height(72.dp)
                            .semantics { contentDescription = "Статистика библиотеки" }
                            .clickable { statsClicks++ }) { Text("2 дня подряд · 1 мин сегодня · 12 мин за неделю") }
                    })
            }
        }) { scenario ->
            val stats = waitNode("Статистика библиотеки")
            val search = visit(instrumentation.uiAutomation.rootInActiveWindow) { it.className == "android.widget.EditText" }
            assertNotNull("Search field must be accessible", search)
            val statsBounds = android.graphics.Rect().also { stats.getBoundsInScreen(it) }
            val searchBounds = android.graphics.Rect().also { search!!.getBoundsInScreen(it) }
            assertTrue("Statistics must end above search: $statsBounds / $searchBounds", statsBounds.bottom < searchBounds.top)
            click("Статистика библиотеки")
            assertEquals(1, statsClicks)
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Книга")
            }
            assertTrue(search!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            instrumentation.waitForIdleSync()
            assertEquals("Книга", query.value)
            scenario.onActivity { showStats.value = false }
            instrumentation.waitForIdleSync()
            val disappearDeadline = android.os.SystemClock.uptimeMillis() + 10000
            while (find("Статистика библиотеки") != null && android.os.SystemClock.uptimeMillis() < disappearDeadline) {
                Thread.sleep(100) // Accessibility tree updates after the Compose frame.
            }
            assertNull(find("Статистика библиотеки"))
            val searchWithoutStats = visit(instrumentation.uiAutomation.rootInActiveWindow) { it.className == "android.widget.EditText" }
            assertNotNull(searchWithoutStats)
            val newBounds = android.graphics.Rect().also { searchWithoutStats!!.getBoundsInScreen(it) }
            assertTrue("Disabling statistics must release its space", newBounds.top < searchBounds.top)
            click("Очистить поиск")
            assertEquals("", query.value)
        }
    }
    @Test fun readerControlsAndFontStepperUseSharedCallbacks() {
        val size=mutableStateOf(18.0)
        val actions=java.util.Collections.synchronizedList(mutableListOf<String>())
        withContent({Column {
            ReaderHeader("Тестовая книга","Автор",listOf(ReaderAction("toc","Оглавление"))) {actions.add(it)}
            ReadingAppearancePanel(emptyList(),listOf(UiNumber("size","Размер",size.value,12.0,32.0,1.0)),emptyList()) {_,value->size.value=value.toDouble()}
            ReaderTools(listOf(ReaderAction("settings","Настройки чтения",badge="Aa"))) {actions.add(it)}
        }}) {
            click("Увеличить: Размер"); click("Оглавление"); click("Настройки чтения")
            assertEquals(19.0,size.value,0.0); assertEquals(listOf("toc","settings"),actions.toList())
        }
    }
    @Test fun nativeShelfCanHideDuplicateCountAndAddControls() {
        withContent({ LibraryShelf(emptyList(), "", "RECENT", 5, false, { _, _ -> },
            showCount = false, showAdd = false) }) {
            waitNode("Ничего не найдено")
            assertNull(find("Книги: 5"))
            assertNull(find("Добавить книги"))
            waitNode("Сортировка: Недавние")
        }
    }
    @Test fun readingAppearanceIsCompactAndExposesSizeControls() {
        withContent({
            com.lexiread.app.ui.reader.ReaderSettingsSheet(ReaderSettings(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onDismiss = {})
        }) {
            waitNode("Оформление книги")
            waitNode("Страница"); waitNode("Текст")
            waitNode("Закрыть настройки чтения")
            waitNode("Увеличить: Размер")
            val snapshot = instrumentation.uiAutomation.takeScreenshot()
            val output = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "reader-settings-compact.png")
            java.io.FileOutputStream(output).use { snapshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            snapshot.recycle()
        }
    }
    @Test fun pdfAppearanceHasNoIneffectiveTextOrVoiceGroups() {
        withContent({
            com.lexiread.app.ui.reader.ReaderSettingsSheet(ReaderSettings(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                textLayoutEnabled = false, onDismiss = {})
        }) {
            waitNode("Страница")
            waitNode("Увеличить: Масштаб")
            assertNull(find("Текст")); assertNull(find("Озвучка")); assertNull(find("Шрифт"))
        }
    }
    @Test fun recentPanelContainsCoversAndSortLabelsAreExplicit() {
        val sort = mutableStateOf("RECENT")
        withContent({ LibraryShelf(listOf(ShelfBook("1", "Недавняя книга", opened = true)),
            "", sort.value, 1, false, { key, value -> if (key == "sort") sort.value = value }) }) {
            val panel = waitNode("Недавние книги")
            val cover = waitNode("Открыть Недавняя книга")
            val panelBounds = android.graphics.Rect().also { panel.getBoundsInScreen(it) }
            val coverBounds = android.graphics.Rect().also { cover.getBoundsInScreen(it) }
            assertTrue("Recent cover must be inside the accent panel", panelBounds.contains(coverBounds))
            click("Сортировка: Недавние")
            click("Автор")
            waitNode("Сортировка: Автор")
            assertEquals("AUTHOR", sort.value)
        }
    }
    @Test fun controlledSelectionUsesTopActionsAndMultipleBooksOnlyExposeDelete() {
        val selected = mutableStateOf(emptySet<String>())
        val books = listOf(ShelfBook("1", "Первый"), ShelfBook("2", "Второй"))
        var deleteIds = emptySet<String>()
        withContent({ Column {
            if (selected.value.isNotEmpty()) com.lexiread.app.ui.library.LibrarySelectionBar(selected.value.size, false,
                { selected.value = emptySet() }, {}, {}, {}, { deleteIds = selected.value })
            Box(Modifier.weight(1f)) {
                LibraryShelf(books, "", "RECENT", 2, false, { action, id ->
                    if (action == "select") selected.value = if (id in selected.value) selected.value - id else selected.value + id
                }, selectedIds = selected.value)
            }
        } }) {
            var first = waitNode("Открыть Первый")
            while (!first.isLongClickable && first.parent != null) first = first.parent
            assertTrue(first.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            waitNode("Выбрано: 1"); waitNode("Переименовать"); waitNode("Обложка")
            assertNull(find("Действия с книгой"))
            click("Выбрать Второй")
            waitNode("Выбрано: 2")
            assertNull(find("Переименовать")); assertNull(find("Обложка")); assertNull(find("В избранное"))
            click("Удалить выбранные книги")
            assertEquals(setOf("1", "2"), deleteIds)
            click("Отменить выделение")
            waitNode("Открыть Первый")
            assertTrue(selected.value.isEmpty())
        }
    }
    @Test fun shelfShowsNewOrReadingPercentBasedOnOpenedState() {
        withContent({ LibraryShelf(listOf(ShelfBook("1", "Непрочитанная", progress = .5),
            ShelfBook("2", "Открытая", opened = true),
            ShelfBook("3", "Прочитанная часть", opened = true, progress = .42)),
            "", "TITLE", 3, false, { _, _ -> }) }) {
            waitNode("Новая"); waitNode("0%"); waitNode("42%")
            assertNull(find("50%"))
        }
    }
    @Test fun signedInAccountDoesNotGrowDuringAutomaticSync() {
        val state = mutableStateOf(AccountPanelState(
            account = com.shelest.shared.sync.CloudAccount("layout-test", displayName = "Читатель"),
            sync = true, logout = true, status = "Синхронизация: 2 мин назад"))
        val measuredHeight = java.util.concurrent.atomic.AtomicInteger()
        withContent({ Box(Modifier.fillMaxWidth().onGloballyPositioned { measuredHeight.set(it.size.height) }) {
            AccountPanel(state.value) { }
        } }) { scenario ->
            waitNode("Синхронизация: 2 мин назад")
            val before = measuredHeight.get()
            assertTrue(before > 0)
            scenario.onActivity { state.value = state.value.copy(busy = true) }
            Thread.sleep(250); instrumentation.waitForIdleSync()
            assertEquals("Busy sync must not insert another layout row", before, measuredHeight.get())
            scenario.onActivity { state.value = state.value.copy(busy = false, status = "Синхронизация: только что") }
            waitNode("Синхронизация: только что")
            assertEquals("Successful sync must retain panel height", before, measuredHeight.get())
        }
    }
}
