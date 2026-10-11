@file:OptIn(ExperimentalJsExport::class)
package com.shelest.ui

import androidx.compose.runtime.*
import com.shelest.shared.sync.SyncProtocol
import kotlinx.serialization.json.*
import org.jetbrains.compose.web.renderComposable

private fun JsonObject.text(key: String, fallback: String = "") = (this[key] as? JsonPrimitive)?.contentOrNull ?: fallback
private fun JsonObject.flag(key: String, fallback: Boolean = false) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
private fun JsonObject.number(key: String, fallback: Double = 0.0) = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: fallback
private fun JsonObject.objects(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** One mount boundary for all remaining semantic interface panels. */
@JsExport
class WorkspaceMount(elementId: String, kind: String, initialState: String, onAction: (String, String) -> Unit) {
    private var current by mutableStateOf(SyncProtocol.objectOf(initialState))
    private val composition = renderComposable(rootElementId = elementId) {
        val state = current
        when(kind) {
            "appearance" -> AppearancePanel(AppearanceState(state.text("themeMode", "SYSTEM"), state.text("accentChoice", "DEFAULT"),
                state.flag("dark"), state.flag("showStats", true), state.number("goal").toInt(), state.text("version")), onAction) { onAction("stats", "") }
            "reading" -> ReadingAppearancePanel(state.objects("selections").map { selection ->
                UiSelection(selection.text("key"), selection.text("title"), selection.text("value"), selection.objects("choices").map {
                    UiChoice(it.text("id"), it.text("label"), it.text("color"), it.text("foreground")) }) },
                state.objects("numbers").map { UiNumber(it.text("key"), it.text("title"), it.number("value"), it.number("min"), it.number("max"), it.number("step",1.0), it.text("suffix")) },
                state.objects("toggles").map { UiToggle(it.text("key"), it.text("title"), it.flag("value")) }, onAction)
            "library" -> LibraryShelf(state.objects("books").map { ShelfBook(it.text("id"), it.text("title"), it.text("author"), it.text("format"),
                it.number("progress"), it.flag("liked"), it.flag("opened"), it.text("cover")) },
                state.text("query"), state.text("sort", "RECENT"), state.number("total").toInt(), state.flag("busy"), onAction,
                selectedIds = (state["selectedIds"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet(),
                allowRecent = state.flag("allowRecent", true), afterControls = {
                    CollectionShelf(state.objects("collections").map { c -> UiCollection(c.text("id"), c.text("title"),
                        c.objects("books").map { ShelfBook(it.text("id"), it.text("title"), cover = it.text("cover")) }, c.number("total").toInt()) },
                        onOpen = { onAction("collection-open", it) })
                })
            "reader-header" -> ReaderHeader(state.text("title"), state.text("author"), state.objects("actions").map {
                ReaderAction(it.text("id"), it.text("label"), it.flag("active"), it.flag("enabled",true), it.text("badge")) }) { onAction(it, "") }
            "reader-pages" -> ReaderPageControls(state.number("page",1.0).toInt(), state.number("total").toInt(),state.text("label","Страница")) { onAction(it, "") }
        }
    }
    fun update(json: String) { current = SyncProtocol.objectOf(json) }
    fun dispose() { composition.dispose() }
}
