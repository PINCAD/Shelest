package com.shelest.ui

import androidx.compose.runtime.*
import kotlin.math.roundToInt

/** Small semantic view models. No database entity, file bytes or credentials enter UI. */
data class UiChoice(val id: String, val label: String, val color: String = "", val foreground: String = "", val fontAsset: String = "")
data class UiSelection(val key: String, val title: String, val value: String, val choices: List<UiChoice>)
data class UiNumber(val key: String, val title: String, val value: Double, val min: Double, val max: Double,
    val step: Double, val suffix: String = "")
data class UiToggle(val key: String, val title: String, val value: Boolean)
data class AppearanceState(val theme: String, val accent: String, val dark: Boolean,
    val showStats: Boolean = true, val goal: Int = 0, val version: String = "")

object InterfaceChoices {
    val themes = listOf(UiChoice("SYSTEM", "Как в системе"), UiChoice("LIGHT", "Светлая"), UiChoice("DARK", "Тёмная"))
    fun accents(dark: Boolean) = listOf(
        UiChoice("DEFAULT", "Фирменный", if (dark) "#CB7C6E" else "#A8493D"),
        UiChoice("BLUE", "Синий", if (dark) "#8AB8EA" else "#3169A8"),
        UiChoice("GREEN", "Зелёный", if (dark) "#8BCAA0" else "#3A7652"),
        UiChoice("VIOLET", "Фиолетовый", if (dark) "#BA9DE2" else "#7756A6"),
        UiChoice("AMBER", "Янтарный", if (dark) "#E1B36C" else "#9C621E"),
        UiChoice("TEAL", "Бирюзовый", if (dark) "#86C8C9" else "#257B80"))
    val sorts = listOf(UiChoice("RECENT", "Недавние"), UiChoice("TITLE", "Название"),
        UiChoice("AUTHOR", "Автор"), UiChoice("ADDED", "Добавлены"), UiChoice("PROGRESS", "Прогресс"))
    val collectionSorts = listOf(UiChoice("COLLECTION", "Порядок томов")) + sorts
}

@Composable
fun AppearancePanel(state: AppearanceState, onChange: (String, String) -> Unit, onStats: () -> Unit) {
    PanelColumn("appearance") {
        PanelText("Интерфейс", TextKind.TITLE)
        WorkspaceChoices(UiSelection("themeMode", "Тема приложения", state.theme, InterfaceChoices.themes), onChange)
        WorkspaceChoices(UiSelection("accentChoice", "Акцентный цвет", state.accent, InterfaceChoices.accents(state.dark)), onChange)
        PanelText("Чтение", TextKind.TITLE)
        PanelButton("Статистика и цель дня", ButtonKind.SECONDARY, true, onStats)
        PanelText(if (state.goal > 0) "Цель: ${state.goal} мин в день" else "Цель не поставлена", TextKind.MUTED)
        PanelToggle("Статистика чтения", "На начальном экране", state.showStats) { onChange("showStats", it.toString()) }
    }
    if (state.version.isNotBlank()) WorkspaceVersion("Шелест · версия ${state.version}")
}

/** Capability lists come from adapters: PDF never offers ineffective font controls. */
@Composable
fun ReadingAppearancePanel(selections: List<UiSelection>, numbers: List<UiNumber>, toggles: List<UiToggle>,
    onChange: (String, String) -> Unit) {
    WorkspaceColumn("reading-appearance") {
        val groups = listOf(
            "Страница" to setOf("pageTheme", "pdfZoom"),
            "Текст" to setOf("fontFamily", "fontSize", "lineHeight", "margin", "justify"),
            "Листание" to setOf("horizontal", "pdfVertical", "readingMode", "twoPage", "pageAnimation"),
            "Прогресс" to setOf("pageNumbers", "progressBar"),
            "Озвучка" to setOf("networkVoice"))
        val knownKeys = groups.flatMap { it.second }.toSet()
        val otherKeys = (selections.map { it.key } + numbers.map { it.key } + toggles.map { it.key }).toSet() - knownKeys
        (groups + ("Дополнительно" to otherKeys)).forEach { (title, keys) ->
            val groupSelections = selections.filter { it.key in keys }
            val groupNumbers = numbers.filter { it.key in keys }
            val groupToggles = toggles.filter { it.key in keys }
            if (groupSelections.isNotEmpty() || groupNumbers.isNotEmpty() || groupToggles.isNotEmpty()) {
                WorkspaceSettingGroup(title) {
                    // Choose the paging mode before choosing its animation.
                    if (title == "Листание") groupToggles.forEach { toggle ->
                        PanelToggle(toggle.title, "", toggle.value) { onChange(toggle.key, it.toString()) }
                    }
                    groupSelections.forEach { WorkspaceChoices(it, onChange) }
                    groupNumbers.forEach { number ->
                        WorkspaceStepper(number, { onChange(number.key, stepped(number, -1).toString()) },
                            { onChange(number.key, stepped(number, 1).toString()) })
                    }
                    if (title != "Листание") groupToggles.forEach { toggle ->
                        PanelToggle(toggle.title, "", toggle.value) { onChange(toggle.key, it.toString()) }
                    }
                }
            }
        }
    }
}
fun stepped(number: UiNumber, direction: Int): Double =
    ((number.value / number.step).roundToInt() + direction).times(number.step).coerceIn(number.min, number.max)
fun numberLabel(number: UiNumber): String =
    (if (number.step >= 1) number.value.roundToInt().toString() else ((number.value * 10).roundToInt() / 10.0).toString()) + number.suffix

@Immutable
data class ShelfBook(val id: String, val title: String, val author: String = "", val format: String = "",
    val progress: Double = 0.0, val liked: Boolean = false, val opened: Boolean = false, val cover: String = "")

fun shelfStatus(book: ShelfBook): String = if (!book.opened) "Новая"
    else "${((book.progress.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, 1.0) * 100).roundToInt()}%"
data class ShelfGroup(val id: String, val title: String, val books: List<ShelfBook>)
data class UiCollection(val id: String, val title: String, val books: List<ShelfBook>, val total: Int)
@Composable
fun CollectionShelf(collections: List<UiCollection>, onOpen: (String) -> Unit,
    cover: @Composable (ShelfBook) -> Unit = { WorkspaceCover(it) }) {
    if (collections.isNotEmpty()) WorkspaceCollections(collections, onOpen, cover)
}
@Composable internal expect fun WorkspaceCollections(collections: List<UiCollection>, onOpen: (String) -> Unit,
    cover: @Composable (ShelfBook) -> Unit)
fun shelfGroups(books: List<ShelfBook>, query: String, sort: String): List<ShelfGroup> {
    val recent = if (query.isBlank() && sort == "RECENT") books.filter { it.opened }.take(3) else emptyList()
    return when {
        recent.isEmpty() -> listOf(ShelfGroup("all", "Все книги", books))
        else -> listOf(ShelfGroup("recent", "Недавние", recent), ShelfGroup("all", "Все книги", books))
    }
}

@Composable
fun LibraryShelf(books: List<ShelfBook>, query: String, sort: String, total: Int, busy: Boolean,
    onAction: (String, String) -> Unit, header: @Composable () -> Unit = {},
    cover: @Composable (ShelfBook) -> Unit = { WorkspaceCover(it) }, showCount: Boolean = true, showAdd: Boolean = true,
    selectedIds: Set<String>? = null, allowRecent: Boolean = true, afterControls: @Composable () -> Unit = {}) {
    val groups = remember(books, query, sort, allowRecent) { shelfGroups(books, query, if (allowRecent) sort else "COLLECTION") }
    WorkspaceShelf(groups, header = {
        header()
        WorkspaceLibraryControls(query, sort, total, busy, showCount, showAdd, !allowRecent, onAction)
        if (books.isEmpty()) {
            PanelText(if (total == 0) "Здесь будет ваша библиотека" else "Ничего не найдено", TextKind.TITLE)
            PanelText(if (total == 0) "Выберите файлы или загрузите папку с книгами" else "Попробуйте другое название или автора", TextKind.MUTED)
        }
    }, afterRecent = afterControls) { book ->
        var menu by remember(book.id) { mutableStateOf(false) }
        val selectionMode = !selectedIds.isNullOrEmpty()
        WorkspaceBook(book, menu, selectedIds?.contains(book.id) == true, selectionMode,
            { onAction(if (selectionMode) "select" else "open", book.id) },
            { if (selectedIds != null) onAction("select", book.id) else menu = !menu }, cover = { cover(book) }) {
            WorkspaceMenu(listOf(UiChoice("like", if (book.liked) "Из избранного" else "В избранное"),
                UiChoice("rename", "Переименовать"), UiChoice("cover", "Обложка"), UiChoice("delete", "Удалить книгу")),
                { menu = false }) { action -> menu = false; onAction(action, book.id) }
        }
    }
}

data class ReaderAction(val id: String, val label: String, val active: Boolean = false,
    val enabled: Boolean = true, val badge: String = "")
@Composable
fun ReaderHeader(title: String, author: String, actions: List<ReaderAction>, onAction: (String) -> Unit) {
    WorkspaceReaderHeader(title, author, { WorkspaceTool(ReaderAction("back", "Назад")) { onAction("back") } }) {
        actions.forEach { action -> WorkspaceTool(action) { onAction(action.id) } }
    }
}
@Composable
fun ReaderTools(actions: List<ReaderAction>, onAction: (String) -> Unit) {
    val groups = listOf(setOf("notes", "pdfTools"), setOf("autoScroll", "tts", "ambient"), setOf("settings"))
        .map { ids -> actions.filter { it.id in ids } }.filter { it.isNotEmpty() }
    val knownActions = groups.flatten().map { it.id }.toSet()
    val otherActions = actions.filter { it.id !in knownActions }
    val quickControls = listOf("hideChrome", "exitMode").mapNotNull { id -> actions.find { it.id == id } }
    val remainingActions = otherActions.filter { it.id !in setOf("exitMode", "hideChrome") }
    WorkspaceTools { (listOf(quickControls) + groups + listOf(remainingActions)).filter { it.isNotEmpty() }.forEachIndexed { index, group ->
        if (index > 0) WorkspaceToolDivider()
        group.forEach { action -> WorkspaceTool(action) { onAction(action.id) } }
    } }
}
@Composable
fun ReaderPageControls(page: Int, total: Int, label:String="Страница", onAction: (String) -> Unit) {
    WorkspacePageControls(page.coerceAtLeast(1), total.coerceAtLeast(0), label,onAction)
}

@Composable internal expect fun WorkspaceColumn(kind: String, content: @Composable () -> Unit)
@Composable internal expect fun WorkspaceSettingGroup(title: String, content: @Composable () -> Unit)
@Composable internal expect fun WorkspaceToolDivider()
@Composable internal expect fun WorkspaceChoices(selection: UiSelection, onChange: (String, String) -> Unit)
@Composable internal expect fun WorkspaceStepper(number: UiNumber, onDown: () -> Unit, onUp: () -> Unit)
@Composable internal expect fun WorkspaceShelf(groups: List<ShelfGroup>, header: @Composable () -> Unit, afterRecent: @Composable () -> Unit, book: @Composable (ShelfBook) -> Unit)
@Composable internal expect fun WorkspaceLibraryControls(query: String, sort: String, total: Int, busy: Boolean, showCount: Boolean, showAdd: Boolean, collectionMode: Boolean, onAction: (String, String) -> Unit)
@Composable internal expect fun WorkspaceVersion(text: String)
@Composable internal expect fun WorkspaceBook(book: ShelfBook, menu: Boolean, selected: Boolean, selectionMode: Boolean, onOpen: () -> Unit, onMenu: () -> Unit,
    cover: @Composable () -> Unit, actions: @Composable () -> Unit)
@Composable internal expect fun WorkspaceCover(book: ShelfBook)
@Composable internal expect fun WorkspaceMenu(choices: List<UiChoice>, onDismiss: () -> Unit, onAction: (String) -> Unit)
@Composable internal expect fun WorkspaceReaderHeader(title: String, author: String, back: @Composable () -> Unit, actions: @Composable () -> Unit)
@Composable internal expect fun WorkspaceTools(content: @Composable () -> Unit)
@Composable internal expect fun WorkspaceTool(action: ReaderAction, onClick: () -> Unit)
@Composable internal expect fun WorkspacePageControls(page: Int, total: Int,label:String, onAction: (String) -> Unit)
