package com.lexiread.app.ui.reader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lexiread.app.data.model.Annotation
import com.lexiread.app.data.model.AnnotationType
import com.lexiread.app.data.model.Bookmark
import com.lexiread.app.ui.settings.OptionChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the reader has personally marked in one book, in one place.
 *
 * Bookmarks, highlights, notes and drawings are stored in two unrelated tables and,
 * before this sheet existed, were only ever visible in-place inside the text — a
 * bookmark in particular was write-only, with no way to get back to it. They are
 * merged here into a single reverse-chronological list so the marks are actually
 * navigable rather than just persisted.
 */
private sealed class NoteEntry {
    abstract val createdAt: Long

    data class BookmarkEntry(val bookmark: Bookmark) : NoteEntry() {
        override val createdAt get() = bookmark.createdAt
    }

    data class AnnotationEntry(val annotation: Annotation) : NoteEntry() {
        override val createdAt get() = annotation.createdAt
    }
}

private enum class NotesFilter(val label: String) {
    ALL("Все"),
    BOOKMARKS("Закладки"),
    HIGHLIGHTS("Цитаты"),
    NOTES("Заметки")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookNotesSheet(
    bookTitle: String,
    isPdf: Boolean,
    bookmarks: List<Bookmark>,
    annotations: List<Annotation>,
    bookmarkLabel: (Int) -> String = { page -> "Страница ${page + 1}" },
    onJumpToPage: (Int) -> Unit,
    onOpenAnnotation: (Annotation) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDeleteAnnotation: (Annotation) -> Unit,
    onShareAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var filter by remember { mutableStateOf(NotesFilter.ALL) }

    val entries = remember(bookmarks, annotations, filter) {
        val merged = buildList<NoteEntry> {
            if (filter == NotesFilter.ALL || filter == NotesFilter.BOOKMARKS) {
                bookmarks.forEach { add(NoteEntry.BookmarkEntry(it)) }
            }
            annotations.forEach { ann ->
                val matches = when (filter) {
                    NotesFilter.ALL        -> true
                    NotesFilter.BOOKMARKS  -> false
                    NotesFilter.HIGHLIGHTS -> ann.type == AnnotationType.HIGHLIGHT
                    NotesFilter.NOTES      -> ann.type == AnnotationType.NOTE
                }
                if (matches) add(NoteEntry.AnnotationEntry(ann))
            }
        }
        merged.sortedByDescending { it.createdAt }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surface,
        tonalElevation   = 0.dp,
        // Top inset only, so the sheet paints past the gesture bar instead of leaving a
        // strip of page colour under it; the content below keeps navigationBarsPadding.
        windowInsets     = WindowInsets.statusBars,
        dragHandle = {
            // Same pill as the definition and ambient sheets — three different
            // handles across three sheets is the kind of thing users feel as sloppy
            // without being able to name it.
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = "Мои пометки",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text     = bookTitle,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onShareAll) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Экспорт",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // The app's own segmented selector, not the Material default chip — the
            // rest of the UI is built from hairline-bordered pills and the stock chip
            // reads as a foreign control here.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NotesFilter.entries.forEach { f ->
                    OptionChip(label = f.label, selected = filter == f, onSelect = { filter = f })
                }
            }

            Spacer(Modifier.height(14.dp))

            if (entries.isEmpty()) {
                EmptyNotes(filter, isPdf)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { entry ->
                        when (entry) {
                            is NoteEntry.BookmarkEntry   -> "b_${entry.bookmark.id}"
                            is NoteEntry.AnnotationEntry -> "a_${entry.annotation.id}"
                        }
                    }) { entry ->
                        when (entry) {
                            is NoteEntry.BookmarkEntry -> NoteRow(
                                icon      = Icons.Filled.Bookmark,
                                accent    = MaterialTheme.colorScheme.primary,
                                title     = bookmarkLabel(entry.bookmark.pageIndex),
                                quote     = null,
                                body      = null,
                                createdAt = entry.bookmark.createdAt,
                                onClick   = { onJumpToPage(entry.bookmark.pageIndex) },
                                onDelete  = { onDeleteBookmark(entry.bookmark) }
                            )
                            is NoteEntry.AnnotationEntry -> {
                                val ann = entry.annotation
                                NoteRow(
                                    icon      = when (ann.type) {
                                        AnnotationType.DRAWING   -> Icons.Filled.Brush
                                        AnnotationType.HIGHLIGHT -> Icons.Outlined.FormatQuote
                                        AnnotationType.NOTE      -> Icons.Outlined.Notes
                                    },
                                    accent    = Color(ann.color),
                                    title     = ann.rowTitle(),
                                    quote     = ann.selectedText?.takeIf { it.isNotBlank() },
                                    body      = ann.noteText?.takeIf { it.isNotBlank() },
                                    createdAt = ann.createdAt,
                                    onClick   = {
                                        // A PDF mark is anchored to a page we can actually jump to;
                                        // a text mark has no page, so open it for editing instead.
                                        val page = ann.pageIndex
                                        if (page != null) onJumpToPage(page) else onOpenAnnotation(ann)
                                    },
                                    onDelete  = { onDeleteAnnotation(ann) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Short label above the quote — the quote itself carries the content. */
private fun Annotation.rowTitle(): String = when (type) {
    AnnotationType.DRAWING   -> "Рисунок · стр. ${(pageIndex ?: 0) + 1}"
    AnnotationType.HIGHLIGHT -> "Цитата"
    AnnotationType.NOTE      -> pageIndex?.let { "Заметка · стр. ${it + 1}" } ?: "Заметка"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    quote: String?,
    body: String?,
    createdAt: Long,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                // Outlined, not filled: the sheet's own container is already a tinted
                // surface, and any fill that works in light mode inverts in dark. The
                // hairline is the separation device used everywhere else in the app.
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                // Delete lives behind a long press, exactly as it does on a book card
                // in the library — a permanent trash icon on every row is both visual
                // noise and a 24dp tap target sitting next to a 300dp one.
                .combinedClickable(onClick = onClick, onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                })
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text     = title,
                        style    = MaterialTheme.typography.labelMedium,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text  = formatNoteDate(createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                if (quote != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text      = quote,
                        style     = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color     = MaterialTheme.colorScheme.onSurface,
                        maxLines  = 3,
                        overflow  = TextOverflow.Ellipsis
                    )
                }
                if (body != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text     = body,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        com.lexiread.app.ui.components.DeleteMenu(
            expanded         = menuOpen,
            onDismissRequest = { menuOpen = false },
            onDelete         = onDelete,
            modifier         = Modifier.matchParentSize()
        )
    }
}

@Composable
private fun EmptyNotes(filter: NotesFilter, isPdf: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Bookmarks,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text  = when (filter) {
                NotesFilter.BOOKMARKS  -> "Закладок пока нет"
                NotesFilter.HIGHLIGHTS -> "Цитат пока нет"
                NotesFilter.NOTES      -> "Заметок пока нет"
                NotesFilter.ALL        -> "Пометок пока нет"
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // PDF pages are bitmaps — there is no text to select there, so suggesting
            // it would be advice the app can't honour.
            text  = if (isPdf) "Ставьте закладки и заметки-кнопки на страницах"
                    else "Выделите текст в книге или добавьте закладку",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

private val noteDateFormat = SimpleDateFormat("d MMM", Locale("ru"))

private fun formatNoteDate(millis: Long): String = noteDateFormat.format(Date(millis))
