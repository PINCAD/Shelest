package com.shelest.ui

import androidx.compose.runtime.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.compose.web.attributes.InputType
import kotlin.math.roundToInt

@Composable internal actual fun WorkspaceColumn(kind: String, content: @Composable () -> Unit) {
    Div(attrs = { classes("common-controls"); attr("data-common-ui", kind) }) { content() }
}
@Composable internal actual fun WorkspaceSettingGroup(title: String, content: @Composable () -> Unit) {
    Section(attrs = { classes("common-reading-group"); attr("aria-label", title) }) {
        H3(attrs = { classes("common-reading-group-title") }) { Text(title) }
        content()
    }
}
@Composable internal actual fun WorkspaceToolDivider() {
    Span(attrs = { classes("common-tool-divider"); attr("aria-hidden", "true") })
}
@Composable internal actual fun WorkspaceChoices(selection: UiSelection, onChange: (String, String) -> Unit) {
    Div(attrs = { classes("common-choice-group"); attr("role","group"); attr("aria-label",selection.title) }) {
        P(attrs = { classes("common-control-title") }) { Text(selection.title) }
        Div(attrs = { classes("common-choices") }) { selection.choices.forEach { choice ->
            Button(attrs = {
                classes("common-choice", if (selection.value == choice.id) "selected" else "unselected")
                attr("type","button"); attr("aria-pressed",(selection.value == choice.id).toString())
                attr("aria-label","${selection.title}: ${choice.label}")
                onClick { onChange(selection.key,choice.id) }
            }) {
                if (choice.color.isNotBlank()) Span(attrs = { classes("common-color-dot"); attr("aria-hidden","true"); attr("style","background:${choice.color}") })
                Text(choice.label)
            }
        } }
    }
}
@Composable internal actual fun WorkspaceStepper(number: UiNumber, onDown: () -> Unit, onUp: () -> Unit) {
    Div(attrs = { classes("common-stepper"); attr("role","group"); attr("aria-label",number.title) }) {
        Span { Text(number.title) }
        Button(attrs = { attr("type","button"); attr("aria-label","Уменьшить: ${number.title}");
            if (number.value <= number.min) attr("disabled",""); onClick { onDown() } }) { Text("−") }
        Span(attrs = { classes("common-number"); attr("aria-live","polite") }) { Text(numberLabel(number)) }
        Button(attrs = { attr("type","button"); attr("aria-label","Увеличить: ${number.title}");
            if (number.value >= number.max) attr("disabled",""); onClick { onUp() } }) { Text("+") }
    }
}
@Composable internal actual fun WorkspaceShelf(groups: List<ShelfGroup>, header: @Composable () -> Unit, afterRecent: @Composable () -> Unit, book: @Composable (ShelfBook) -> Unit) {
    Section(attrs = { attr("data-common-ui","library"); classes("common-library") }) {
        header()
        if (groups.none { it.id == "recent" && it.books.isNotEmpty() }) afterRecent()
        groups.filter { it.books.isNotEmpty() }.forEach { group -> key(group.id) {
            Section(attrs = { classes(if (group.id == "recent") "common-recent-panel" else "common-all-panel") }) {
                H2(attrs = { classes("common-shelf-heading"); if (group.id == "recent") classes("common-recent-heading") }) { Text(group.title) }
                Div(attrs = { classes("book-grid") }) { group.books.forEach { entry -> key(entry.id) { book(entry) } } }
            }
            if (group.id == "recent") afterRecent()
        } }
    }
}
@Composable internal actual fun WorkspaceLibraryControls(query: String, sort: String, total: Int, busy: Boolean, showCount: Boolean, showAdd: Boolean, collectionMode: Boolean, onAction: (String, String) -> Unit) {
    Div(attrs = { classes("common-library-controls") }) {
        Div(attrs = { classes("common-search-sort") }) {
            Input(InputType.Search, attrs = { value(query); attr("aria-label","Найти книгу"); attr("placeholder","Найти книгу"); onInput { onAction("query",it.value) } })
        Select(attrs = { attr("aria-label","Сортировка книг"); onChange { onAction("sort",it.value.orEmpty()) } }) {
            (if (collectionMode) InterfaceChoices.collectionSorts else InterfaceChoices.sorts).forEach { choice -> Option(value = choice.id, attrs = { if (sort == choice.id) attr("selected","") }) { Text("Сортировка: ${choice.label}") } }
        }
        }
        if (showCount) Span(attrs = { classes("common-book-count") }) { Text("Книги: $total") }
        if (showAdd) Button(attrs = { classes("primary-button"); attr("type","button"); if (busy) attr("disabled",""); onClick { onAction("add","") } }) {
            Text(if (busy) "Добавляем…" else "Добавить книги")
        }
    }
}
@Composable internal actual fun WorkspaceVersion(text: String) {
    P(attrs = { classes("common-version-footer"); attr("style", "text-align:center;margin:24px 0;color:var(--muted);font-size:12px") }) { Text(text) }
}
@Composable internal actual fun WorkspaceBook(book: ShelfBook, menu: Boolean, selected: Boolean, selectionMode: Boolean, onOpen: () -> Unit, onMenu: () -> Unit,
    cover: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Article(attrs = { classes("book-card", "common-book-card"); attr("data-book-id",book.id) }) {
        Div(attrs = { classes("common-cover-wrap") }) {
            Button(attrs = { classes("cover-button"); attr("type","button"); attr("aria-label", (if (selectionMode) "Выбрать " else "Открыть ") + book.title);
                attr("aria-pressed",selected.toString())
                addEventListener("contextmenu") { it.preventDefault(); onMenu() }; onClick { onOpen() } }) { cover() }
            if (selected) Span(attrs = { classes("collection-selected-cover"); attr("aria-hidden","true") })
            if (book.liked) Span(attrs = { classes("common-favorite"); attr("aria-label","В избранном") }) { Text("♥") }
            Span(attrs = { classes("common-cover-status"); if (!book.opened) classes("common-new-book") }) { Text(shelfStatus(book)) }
            if (menu) actions()
        }
        Div(attrs = { classes("book-card-copy") }) {
            Button(attrs = { classes("book-title"); attr("type","button"); attr("title",book.title); onClick { onOpen() } }) { Text(book.title) }
            Span(attrs = { classes("book-author") }) { Text(book.author.ifBlank { "Автор не указан" }) }
            Div(attrs = { classes("book-card-bottom") }) { Span { Text(book.format) } }
            if (book.opened) Div(attrs = { classes("mini-progress"); attr("aria-hidden","true") }) { Span(attrs = { attr("style","width:${book.progress.coerceIn(0.0,1.0)*100}%") }) }
        }
    }
}
@Composable internal actual fun WorkspaceCover(book: ShelfBook) {
    var failed by remember(book.cover) { mutableStateOf(false) }
    Div(attrs = { classes("book-cover", "cover-clay") }) {
        if (book.cover.isNotBlank() && !failed) Img(src = book.cover, alt = "Обложка: ${book.title}", attrs = {
            attr("loading","lazy"); attr("decoding","async"); addEventListener("error") { failed = true }
        }) else {
            Span(attrs = { classes("cover-frame") }); Span(attrs = { classes("cover-format") }) { Text(book.format) }
            Span(attrs = { classes("cover-title") }) { Text(book.title) }; Span(attrs = { classes("cover-author") }) { Text(book.author.ifBlank { "Шелест" }) }
        }
    }
}
@Composable internal actual fun WorkspaceMenu(choices: List<UiChoice>, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    Div(attrs = { classes("card-menu", "common-book-menu"); attr("role","menu"); onKeyDown { if (it.key == "Escape") onDismiss() } }) {
        choices.forEach { choice -> Button(attrs = { attr("type","button"); attr("role","menuitem");
            if (choice.id == "delete") classes("danger-action"); onClick { onAction(choice.id) } }) { Text(choice.label) } }
        Button(attrs = { attr("type","button"); attr("role","menuitem"); onClick { onDismiss() } }) { Text("Закрыть") }
    }
}
@Composable internal actual fun WorkspaceReaderHeader(title: String, author: String, back: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Header(attrs = { classes("reader-toolbar", "common-reader-header"); attr("data-common-ui","reader-header") }) {
        back()
        Div(attrs = { classes("reader-book-label") }) { B { Text(title) }; Small { Text(author) } }
        Div(attrs = { classes("reader-actions") }) { actions() }
    }
}
@Composable internal actual fun WorkspaceTools(content: @Composable () -> Unit) {
    Div(attrs = { classes("common-reader-tools"); attr("data-common-ui","reader-tools") }) { content() }
}
@Composable internal actual fun WorkspaceTool(action: ReaderAction, onClick: () -> Unit) {
    Button(attrs = { classes("reader-tool", if (action.active) "active" else "inactive"); if(action.id == "back") classes("reader-back"); attr("type","button");
        attr("aria-label",action.label); attr("title",action.label); attr("aria-pressed",action.active.toString());
        if (!action.enabled) attr("disabled",""); onClick { onClick() } }) {
        if (action.badge.isNotBlank()) Span(attrs = { attr("aria-hidden","true") }) { Text(action.badge) }
        else {
            val svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path d='${readerIconPath(action.id)}' fill='none' stroke='black' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'/></svg>"
            val encoded = js("encodeURIComponent")(svg) as String
            Span(attrs = { classes("common-tool-icon"); attr("aria-hidden","true"); attr("style","mask-image:url(\"data:image/svg+xml,$encoded\");-webkit-mask-image:url(\"data:image/svg+xml,$encoded\")") })
        }
    }
}
@Composable internal actual fun WorkspacePageControls(page: Int, total: Int,label:String, onAction: (String) -> Unit) {
    Footer(attrs = { classes("reader-footer"); attr("data-common-ui","reader-pages") }) {
        Button(attrs = { attr("type","button"); attr("aria-label","Назад"); if (page <= 1) attr("disabled",""); onClick { onAction("previous") } }) { Text("← Назад") }
        Div(attrs = { classes("reader-page-meter"); attr("aria-live","polite") }) {
            Span { Text(label) }; B { Text(page.toString()) }
            if (total > 0) { Span { Text("из") }; B { Text(total.toString()) } }
        }
        Button(attrs = { attr("type","button"); attr("aria-label","Дальше"); if (total > 0 && page >= total) attr("disabled",""); onClick { onAction("next") } }) { Text("Дальше →") }
    }
}
