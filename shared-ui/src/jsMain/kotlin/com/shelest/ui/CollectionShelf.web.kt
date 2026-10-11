package com.shelest.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.jetbrains.compose.web.dom.*

@Composable internal actual fun WorkspaceCollections(collections: List<UiCollection>, onOpen: (String) -> Unit,
    cover: @Composable (ShelfBook) -> Unit) {
    Section(attrs = { classes("collection-section"); attr("aria-label", "Коллекции") }) {
        H2 { Text("Коллекции") }
        Div(attrs = { classes("collection-strip") }) {
            collections.forEach { collection -> key(collection.id) {
                Button(attrs = { classes("collection-card"); attr("type", "button"); onClick { onOpen(collection.id) } }) {
                    Span(attrs = { classes("collection-covers"); attr("aria-hidden", "true") }) {
                        collection.books.take(3).reversed().forEach { book ->
                            Span(attrs = { classes("collection-mini-cover") }) {
                                cover(book)
                            }
                        }
                        if (collection.books.isEmpty()) Text("▤")
                    }
                    Span(attrs = { classes("collection-copy") }) {
                        B { Text(collection.title) }; Small { Text("Книг: " + collection.total) }
                    }
                }
            } }
        }
    }
}
