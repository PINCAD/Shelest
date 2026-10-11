package com.lexiread.app.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.shelest.shared.BookCollection

@Composable
internal fun CollectionPicker(collections: List<BookCollection>, onDismiss: () -> Unit, onAdd: (String?, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("В коллекцию") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (collections.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(collections, key = { it.id }) { collection ->
                    TextButton(onClick = { onAdd(collection.id, collection.name) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CollectionsBookmark, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(collection.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Новая коллекция") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onAdd(null, name.trim()) }) { Text("Создать") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionEditor(collection: BookCollection, titles: Map<String, String>, onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit, onDelete: () -> Unit) {
    var name by remember(collection.id) { mutableStateOf(collection.name) }
    var members by remember(collection.id) { mutableStateOf(collection.members) }
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
            .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Коллекция", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Название") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            Text("Порядок томов", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                itemsIndexed(members, key = { _, sha -> sha }) { index, sha ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(titles[sha] ?: "Книга на другом устройстве", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(enabled = index > 0, onClick = {
                            members = members.toMutableList().apply { add(index - 1, removeAt(index)) }
                        }) { Icon(Icons.Default.KeyboardArrowUp, "Выше: " + (titles[sha] ?: "том")) }
                        IconButton(enabled = index < members.lastIndex, onClick = {
                            members = members.toMutableList().apply { add(index + 1, removeAt(index)) }
                        }) { Icon(Icons.Default.KeyboardArrowDown, "Ниже: " + (titles[sha] ?: "том")) }
                        IconButton(onClick = { members = members - sha }) { Icon(Icons.Default.Close, "Убрать из коллекции: " + (titles[sha] ?: "том")) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) { Text("Удалить коллекцию", color = MaterialTheme.colorScheme.error) }
                Button(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), members) }) { Text("Сохранить") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить коллекцию?") },
        text = { Text("Книги и место чтения сохранятся. Удалится только группа на устройствах аккаунта.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
}
