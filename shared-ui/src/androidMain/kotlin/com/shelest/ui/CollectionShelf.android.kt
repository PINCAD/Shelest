package com.shelest.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark

@Composable internal actual fun WorkspaceCollections(collections: List<UiCollection>, onOpen: (String) -> Unit,
    cover: @Composable (ShelfBook) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Коллекции", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(collections, key = { it.id }) { collection ->
                Surface(onClick = { onOpen(collection.id) }, shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .07f),
                    border = BorderStroke(.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = .15f)),
                    modifier = Modifier.width(208.dp).height(112.dp).semantics { contentDescription = "Открыть коллекцию: " + collection.title }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.width(72.dp).height(82.dp)) {
                            collection.books.take(3).reversed().forEachIndexed { index, book ->
                                Box(Modifier.offset(x = ((2 - index) * 8).dp, y = ((2 - index) * 3).dp)
                                    .width(54.dp).height(76.dp).clip(RoundedCornerShape(7.dp))) { cover(book) }
                            }
                            if (collection.books.isEmpty()) Icon(Icons.Default.CollectionsBookmark, null,
                                Modifier.align(Alignment.Center).size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(collection.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Книг: " + collection.total, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
