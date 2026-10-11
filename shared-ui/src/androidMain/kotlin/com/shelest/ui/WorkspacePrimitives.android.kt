package com.shelest.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable internal actual fun WorkspaceColumn(kind: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}
@Composable internal actual fun WorkspaceSettingGroup(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
@Composable internal actual fun WorkspaceToolDivider() {
    Box(Modifier.padding(horizontal = 3.dp).height(24.dp).width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal actual fun WorkspaceChoices(selection: UiSelection, onChange: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(selection.title, style = MaterialTheme.typography.titleSmall)
        if (selection.key == "pageTheme") {
            selection.choices.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { choice ->
                        val active = choice.id == selection.value
                        val paper = Color(android.graphics.Color.parseColor(choice.color))
                        val ink = Color(android.graphics.Color.parseColor(choice.foreground))
                        Surface(onClick = { onChange(selection.key, choice.id) },
                            color = paper, contentColor = ink, shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(if (active) 2.dp else .8.dp,
                                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f).heightIn(min = 68.dp).semantics {
                                contentDescription = "${selection.title}: ${choice.label}"; this.selected = active
                            }) {
                            Box(Modifier.padding(12.dp)) {
                                Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Аа", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
                                    Text(choice.label, style = MaterialTheme.typography.labelMedium)
                                }
                                if (active) Surface(color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary, shape = CircleShape,
                                    modifier = Modifier.align(Alignment.TopEnd)) {
                                    Icon(Icons.Default.Check, null, Modifier.padding(3.dp).size(14.dp))
                                }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else if (selection.key == "fontFamily" || selection.key == "pageAnimation") {
            val assets = LocalContext.current.assets
            selection.choices.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { choice ->
                        val active = choice.id == selection.value
                        val font = remember(choice.fontAsset) { if (choice.fontAsset.isBlank()) null else
                            runCatching { FontFamily(Font("fonts/${choice.fontAsset}", assets)) }.getOrNull() }
                        Surface(onClick = { onChange(selection.key, choice.id) },
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f).height(48.dp).semantics {
                                contentDescription = "${selection.title}: ${choice.label}"; selected = active
                            }) {
                            Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                Text(choice.label, fontFamily = font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            selection.choices.forEach { choice -> key(choice.id) {
                val selected = selection.value == choice.id
                val assets = LocalContext.current.assets
                val font = remember(choice.fontAsset) { if (choice.fontAsset.isBlank()) null else
                    runCatching { FontFamily(Font("fonts/${choice.fontAsset}", assets)) }.getOrNull() }
                FilterChip(selected, { onChange(selection.key, choice.id) }, label = { Text(choice.label, fontFamily = font) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = .10f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary),
                    border = BorderStroke(if (selected) 1.2.dp else .8.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    leadingIcon = if (choice.color.isBlank()) null else ({
                        Box(Modifier.size(18.dp).clip(RoundedCornerShape(6.dp))
                            .background(Color(android.graphics.Color.parseColor(choice.color))))
                    }), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${selection.title}: ${choice.label}" })
            } }
        }
        }
    }
}
@Composable internal actual fun WorkspaceStepper(number: UiNumber, onDown: () -> Unit, onUp: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(number.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onDown, enabled = number.value > number.min) { Icon(Icons.Default.Remove, "Уменьшить: ${number.title}") }
        Text(numberLabel(number), Modifier.width(60.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelLarge)
        IconButton(onUp, enabled = number.value < number.max) { Icon(Icons.Default.Add, "Увеличить: ${number.title}") }
    }
}
@Composable internal actual fun WorkspaceShelf(groups: List<ShelfGroup>, header: @Composable () -> Unit,
    afterRecent: @Composable () -> Unit, book: @Composable (ShelfBook) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(96.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") {
            // A lazy grid item does not arrange multiple roots: without this Column,
            // the optional statistics strip and search controls occupy the same slot.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { header() }
        }
        if (groups.none { it.id == "recent" && it.books.isNotEmpty() }) {
            item(key = "collections", span = { GridItemSpan(maxLineSpan) }) { afterRecent() }
        }
        groups.filter { it.books.isNotEmpty() }.forEach { group ->
            if (group.id == "recent") {
                item(key = "recent-panel", span = { GridItemSpan(maxLineSpan) }, contentType = "recent-panel") {
                    Box(Modifier.fillMaxWidth()) {
                    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .09f),
                        shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 472.dp).fillMaxWidth()
                            .semantics { contentDescription = "Недавние книги" }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(group.title, style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Row(Modifier.widthIn(max = 440.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                group.books.forEach { entry -> key(entry.id) {
                                    Box(Modifier.weight(1f)) { book(entry) }
                                } }
                                repeat(3 - group.books.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    }
                }
                item(key = "collections", span = { GridItemSpan(maxLineSpan) }) { afterRecent() }
                return@forEach
            }
            item(key = "heading/${group.id}", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
                Text(group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(group.books, key = { "${group.id}/${it.id}" }, contentType = { "book" }) { book(it) }
        }
    }
}
@Composable internal actual fun WorkspaceLibraryControls(query: String, sort: String, total: Int, busy: Boolean, showCount: Boolean, showAdd: Boolean,
    collectionMode: Boolean, onAction: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val sorts = if (collectionMode) InterfaceChoices.collectionSorts else InterfaceChoices.sorts
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { onAction("query", it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Найти книгу") }, shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) IconButton({ onAction("query", "") }) { Icon(Icons.Default.Close, "Очистить поиск") }
                    Box {
                        val sortLabel = sorts.find { it.id == sort }?.label ?: "Недавние"
                        TextButton({ expanded = true }, modifier = Modifier.semantics {
                            contentDescription = "Сортировка: $sortLabel"
                        }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                            Column {
                                Text("Сортировка:", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(sortLabel, style = MaterialTheme.typography.labelMedium)
                            }
                            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
                        }
                        MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(20.dp))) {
                            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.widthIn(min = 192.dp, max = 256.dp)) {
                                sorts.forEach { choice ->
                                    val active = choice.id == sort
                                    DropdownMenuItem(text = { Text(choice.label,
                                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                                            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = .10f) else Color.Transparent),
                                        leadingIcon = { Icon(when (choice.id) {
                                            "RECENT" -> Icons.Default.History
                                            "TITLE" -> Icons.Default.SortByAlpha
                                            "AUTHOR" -> Icons.Default.PersonOutline
                                            "ADDED" -> Icons.Default.LibraryAdd
                                            "COLLECTION" -> Icons.Default.FormatListNumbered
                                            else -> Icons.Default.TrendingUp
                                        }, null) },
                                        trailingIcon = { if (active) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                                        onClick = { expanded = false; onAction("sort", choice.id) })
                                }
                            }
                        }
                    }
                }
            })
        if (showCount || showAdd) Row(verticalAlignment = Alignment.CenterVertically) {
            if (showCount) Text("Книги: $total", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            else Spacer(Modifier.weight(1f))
            if (showAdd) IconButton({ onAction("add", "") }, enabled = !busy) { Icon(Icons.Default.Add, "Добавить книги") }
        }
    }
}
@Composable internal actual fun WorkspaceVersion(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@OptIn(ExperimentalFoundationApi::class)
@Composable internal actual fun WorkspaceBook(book: ShelfBook, menu: Boolean, selected: Boolean, selectionMode: Boolean, onOpen: () -> Unit, onMenu: () -> Unit,
    cover: @Composable () -> Unit, actions: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onOpen, onLongClick = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress); onMenu()
    }).semantics { contentDescription = if (selectionMode) "Выбрать ${book.title}" else "Открыть ${book.title}"; this.selected = selected }
        .padding(bottom = 8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)) {
            cover()
            if (selected) Box(Modifier.matchParentSize().border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)))
            if (selected) Surface(color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(8.dp), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.Check, null,
                    Modifier.padding(4.dp).size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
            if (book.liked) Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                Icon(Icons.Default.Favorite, "В избранном", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(4.dp).size(14.dp))
            }
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                Text(shelfStatus(book), Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (book.opened) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
            }
            if (book.opened && book.progress > 0) LinearProgressIndicator(progress = { book.progress.coerceIn(0.0,1.0).toFloat() },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
            Box(Modifier.align(Alignment.TopEnd)) { if (menu) actions() }
        }
        Text(book.title, Modifier.fillMaxWidth().padding(top = 8.dp),
            style = MaterialTheme.typography.labelMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotBlank()) Text(book.author, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
@Composable internal actual fun WorkspaceCover(book: ShelfBook) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal actual fun WorkspaceMenu(choices: List<UiChoice>, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Действия с книгой", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            choices.forEach { choice ->
                val tint = if (choice.id == "delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onAction(choice.id) }
                    .padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(when(choice.id) { "like" -> Icons.Default.FavoriteBorder; "rename" -> Icons.Default.Edit;
                        "cover" -> Icons.Default.Image; else -> Icons.Default.DeleteOutline }, null, Modifier.size(22.dp), tint = tint)
                    Text(choice.label, style = MaterialTheme.typography.bodyMedium, color = tint)
                }
            }
        }
    }
}
@Composable internal actual fun WorkspaceReaderHeader(title: String, author: String, back: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        back()
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}
@Composable internal actual fun WorkspaceTools(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .98f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .72f)),
        shadowElevation = 3.dp,
        tonalElevation = 0.dp
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth)
                .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) { content() }
        }
    }
}
@Composable internal actual fun WorkspaceTool(action: ReaderAction, onClick: () -> Unit) {
    val activeContainer = when {
        action.id == "hideChrome" || action.id == "exitMode" -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val tint = (if (action.active) MaterialTheme.colorScheme.onPrimaryContainer
        else if (action.id == "hideChrome" || action.id == "exitMode") MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurfaceVariant).let { if (action.enabled) it else it.copy(alpha = .38f) }
    // One bounded, clipped click target. IconButton's internal circular ripple used
    // to have a different shape from the painted rounded-square tool background.
    Box(modifier = Modifier.size(48.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(if (action.active) activeContainer else Color.Transparent)
        .clickable(enabled = action.enabled, role = Role.Button, onClick = onClick)
        .semantics { if (action.active) selected = true }, contentAlignment = Alignment.Center) {
        if (action.badge.isNotBlank()) Text(action.badge, color = tint, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.semantics { contentDescription = action.label })
        else Icon(when(action.id) {
            "back", "previous" -> Icons.Default.ArrowBack
            "next" -> Icons.Default.ArrowForward
            "search" -> Icons.Default.Search
            "toc" -> Icons.Default.List
            "bookmark", "note", "notes" -> Icons.Default.Bookmarks
            "dictionary" -> Icons.Default.Translate
            "tts" -> Icons.Default.RecordVoiceOver
            "ambient" -> Icons.Default.Headphones
            "autoScroll" -> Icons.Default.KeyboardDoubleArrowDown
            "pdfTools" -> Icons.Default.Brush
            "exitMode" -> Icons.Default.Close
            "hideChrome" -> Icons.Default.KeyboardArrowDown
            else -> Icons.Default.Settings
        }, action.label, tint = tint)
    }
}
@Composable internal actual fun WorkspacePageControls(page: Int, total: Int,label:String, onAction: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WorkspaceTool(ReaderAction("previous", "Назад", enabled = page > 1)) { onAction("previous") }
        Text(if (total > 0) "$page / $total" else "$page", style = MaterialTheme.typography.labelMedium)
        WorkspaceTool(ReaderAction("next", "Дальше", enabled = total == 0 || page < total)) { onAction("next") }
    }
}
