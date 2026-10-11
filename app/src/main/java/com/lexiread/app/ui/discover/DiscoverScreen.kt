package com.lexiread.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.isLiked
import com.lexiread.app.data.repository.BookDiscoveryRepository
import com.lexiread.app.domain.discover.DiscoverySuggestion
import com.lexiread.app.domain.discover.LikedItem
import com.lexiread.app.domain.discover.hasCyrillic
import java.io.File

/**
 * Books the reader does not have.
 *
 * The other recommender ranks the shelf; this one looks past it, and the two are kept apart on
 * purpose. Ranking what you own needs no permission and no network — it can just appear on the
 * library screen. Asking a catalogue what else exists needs the reader to say what they liked
 * first, and that is a thing they do deliberately, on a screen of its own, where they can see
 * exactly which of their books the suggestions are built from and take any of them back.
 *
 * Nothing here is downloaded and nothing here leaves the app. A suggestion opens a sheet saying
 * what the book is about, which is the question a recommendation raises and the one a link to a
 * shop page answers worst: it drops the reader into a browser, on a page in a language the
 * catalogue chose, to find out whether they were interested. The answer belongs on this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onBack: () -> Unit
) {
    val liked   by viewModel.liked.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val state   by viewModel.state.collectAsStateWithLifecycle()
    val notice  by viewModel.notice.collectAsStateWithLifecycle()
    val detail  by viewModel.detail.collectAsStateWithLifecycle()

    var pickerOpen by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }

    // "Уже в списке" is an answer to something the reader just did, and it belongs next to the
    // action rather than in place of the results.
    LaunchedEffect(notice) {
        notice?.let {
            snackbars.showSnackbar(it)
            viewModel.clearNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(
                            text  = "Что почитать дальше",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text  = if (liked.isEmpty()) "Книги, которых у вас ещё нет"
                                    else "По вашим отметкам · ${liked.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (liked.isNotEmpty()) {
                        IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "Обновить подборку",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier            = Modifier.padding(padding).fillMaxSize(),
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                LikedSection(
                    liked     = liked,
                    onPick    = { pickerOpen = true },
                    onRemove  = viewModel::removeLiked,
                    hasShelf  = library.isNotEmpty()
                )
            }

            // The search only uses the most recent few marks. A reader who marked twelve books
            // and sees suggestions from five would otherwise think the rest were ignored.
            if (liked.size > BookDiscoveryRepository.MAX_LIKED_USED) {
                item {
                    Text(
                        text  = "Подборка строится по последним " +
                            "${BookDiscoveryRepository.MAX_LIKED_USED} отметкам.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (liked.isEmpty()) return@LazyColumn

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Text(
                        text  = "Возможно понравится",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    HorizontalDivider(
                        modifier  = Modifier.weight(1f),
                        thickness = 0.5.dp,
                        color     = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }

            if (state.isLoading) {
                item { SearchingRow() }
            }

            state.message?.let { message ->
                item {
                    Text(
                        text     = message,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }

            items(state.suggestions, key = { it.book.id }) { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    preview = state.cardPreviews[suggestion.book.id],
                    onOpen = { viewModel.open(suggestion) }
                )
            }

            // Breathing room where the catalogue note used to be — the last card should not sit
            // against the navigation bar.
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (pickerOpen) {
        LikedPickerSheet(
            library  = library,
            onToggle = { book, isLiked -> viewModel.setLiked(book, isLiked) },
            onAdd    = viewModel::addTitle,
            onClose  = { pickerOpen = false }
        )
    }

    detail?.let { open ->
        BookSheet(detail = open, onClose = viewModel::closeDetail)
    }
}

// ── What the reader liked ─────────────────────────────────────────────────────

@Composable
private fun LikedSection(
    liked: List<LikedItem>,
    hasShelf: Boolean,
    onPick: () -> Unit,
    onRemove: (LikedItem) -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text  = "Вам понравилось",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            HorizontalDivider(
                modifier  = Modifier.weight(1f),
                thickness = 0.5.dp,
                color     = MaterialTheme.colorScheme.outlineVariant
            )
        }

        Spacer(Modifier.height(10.dp))

        if (liked.isEmpty()) {
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    .clickable(onClick = onPick)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Favorite,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text  = if (hasShelf) "Отметьте книги, которые понравились"
                                else "Впишите книги, которые понравились",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    // The list is not limited to the shelf, and saying so here is the only place
                    // a reader with an empty library will find that out.
                    text  = if (hasShelf)
                        "Или впишите любую книгу, которой у вас нет. Чем больше отметок, тем точнее."
                    else
                        "Подойдёт любая книга, которую вы читали. Файл для этого не нужен.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onPick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Выбрать книги")
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(liked, key = { it.key }) { item ->
                    LikedChip(item = item, onRemove = { onRemove(item) })
                }
                item {
                    AddLikedChip(onClick = onPick)
                }
            }
        }
    }
}

/**
 * One height for both chips in the row.
 *
 * It used to be whatever each one's contents added up to — a cover's worth for the marks and a
 * line of text's worth for «Ещё» — and a row of panels that are nearly but not quite the same
 * height looks like a mistake rather than like two different things.
 */
private val CHIP_HEIGHT = 44.dp

@Composable
private fun LikedChip(item: LikedItem, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .height(CHIP_HEIGHT)
            .widthIn(max = 190.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                // Sized in full rather than by ratio: the row's height is now fixed, and an
                // `aspectRatio` inside a Row resolves against the width it is offered, which
                // here is all the space left over.
                .size(width = 22.dp, height = 32.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            // A typed-in book has no file and so no cover. Marking it with a pen rather than
            // with the same empty-book placeholder says which of the two it is.
            if (item.onShelf) {
                LocalCover(item.coverPath)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.EditNote,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text     = item.title,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Filled.Close,
            contentDescription = "Убрать отметку",
            tint     = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .padding(4.dp)
        )
    }
}

@Composable
private fun AddLikedChip(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .height(CHIP_HEIGHT)
            .clip(shape)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text  = "Ещё",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ── Picker ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LikedPickerSheet(
    library: List<Book>,
    onToggle: (Book, Boolean) -> Unit,
    onAdd: (String, String) -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor   = MaterialTheme.colorScheme.surface,
        tonalElevation   = 0.dp,
        windowInsets     = WindowInsets.statusBars,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
            )
        }
    ) {
        // imePadding, because this sheet now has text fields in it: without it the keyboard
        // opens straight over the field the reader is typing into.
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            Text(
                text     = "Какие книги понравились",
                style    = MaterialTheme.typography.titleMedium,
                color    = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text     = "Отметьте любимые на полке или впишите любую другую.",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(16.dp))

            AddByHand(onAdd = onAdd)

            Spacer(Modifier.height(16.dp))

            if (library.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                ) {
                    Text(
                        text  = "С вашей полки",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    HorizontalDivider(
                        modifier  = Modifier.weight(1f),
                        thickness = 0.5.dp,
                        color     = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(library, key = { it.id }) { book ->
                    val checked = book.isLiked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(book, !checked) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(30.dp)
                                .aspectRatio(0.68f)
                                .clip(RoundedCornerShape(4.dp))
                        ) {
                            LocalCover(book.coverPath)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text     = com.lexiread.app.domain.discover.cleanCatalogueTitle(book.title),
                                style    = MaterialTheme.typography.bodyMedium,
                                color    = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (book.author.isNotBlank()) {
                                Text(
                                    text     = book.author,
                                    style    = MaterialTheme.typography.bodySmall,
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (checked) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .border(
                                    width = if (checked) 0.dp else 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(6.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (checked) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint     = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/**
 * A book the reader liked that the app has never seen.
 *
 * The author field is optional and says so. It is worth asking for — it is what tells «Солярис»
 * the novel from «Солярис» the film when the title is looked up — but a reader who cannot
 * remember it should not be stopped by that, and one who is forced to fill the field in will
 * guess, which is worse than leaving it empty.
 */
@Composable
private fun AddByHand(onAdd: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        OutlinedTextField(
            value         = title,
            onValueChange = { title = it },
            label         = { Text("Название") },
            placeholder   = { Text("Например, Мастер и Маргарита") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value         = author,
            onValueChange = { author = it },
            label         = { Text("Автор (необязательно)") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                onAdd(title, author)
                // Cleared unconditionally: a repeat is reported by the snackbar, and leaving the
                // text in place invites the reader to press the button again to no effect.
                title = ""
                author = ""
            },
            enabled  = title.isNotBlank(),
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Добавить")
        }
    }
}

// ── Suggestions ───────────────────────────────────────────────────────────────

@Composable
private fun SearchingRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier    = Modifier.size(16.dp),
            color       = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text  = "Ищем в каталоге…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuggestionCard(
    suggestion: DiscoverySuggestion,
    preview: DiscoverCardPreview?,
    onOpen: () -> Unit
) {
    val book  = suggestion.book
    val cover = book.thumbnailUrl ?: preview?.coverUrl
    // The synopsis fallback can be a biography. The detail sheet labels that honestly;
    // a card headed "О чём эта книга" must not present it as the book's plot.
    val description = book.description.takeUnless { com.lexiread.app.domain.discover.looksLikeOpeningQuote(it) }.orEmpty().ifBlank {
        preview?.takeUnless { it.aboutAuthor }?.description.orEmpty()
    }
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onOpen)
            .padding(12.dp)
    ) {
        Row {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .aspectRatio(0.68f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (cover != null) {
                    AsyncImage(
                        model              = cover,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Outlined.MenuBook,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = book.displayTitle,
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.authors.isNotEmpty()) {
                    Text(
                        text     = book.authorLine + (book.publishedYear?.let { ", $it" } ?: ""),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Blank for the books picked because the reader already likes the author: the
                // line under the title says the author's name, and repeating it as a reason is
                // one row of the card spent on nothing.
                if (suggestion.reason.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp).size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            // Two lines: the genre reasons name the genre, and «Научная
                            // фантастика — как «Пикник на обочине»» does not fit on one line.
                            text     = suggestion.reason,
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                val rating = book.averageRating
                if (rating != null && book.ratingsCount > 0) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Star,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text  = "${"%.1f".format(rating)} · ${book.ratingsCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (description.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text     = description,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        // The card is entirely tappable; this says so. Without it the only thing inviting a tap
        // is the habit of tapping things, and a reader who never taps never finds out that the
        // app has anything more to say about these books.
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.MenuBook,
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text  = "О чём эта книга",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ── One suggestion, opened ────────────────────────────────────────────────────

/**
 * What the book is about, which is the only thing a reader actually wants from a suggestion.
 *
 * Everything on the card answers "why is this here"; this answers "should I read it". The text
 * is the publisher's own blurb where the catalogue has a real one, and Fantlab's or Wikipedia's
 * where it does not — see [com.lexiread.app.data.repository.BookSynopsisRepository]. Where it
 * came from somebody else, they are named: one licence asks for attribution, and a reader is
 * entitled either way to know who is telling them this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSheet(detail: BookDetail, onClose: () -> Unit) {
    val book = detail.suggestion.book
    val cover = book.thumbnailUrl ?: detail.coverUrl
    val genres = remember(book.id) {
        book.categories.map { it.trim() }
            // Cyrillic ones only: Open Library's headings are English cataloguing terms
            // ("translations into english"), and a chip is too small to explain itself.
            .filter { it.isNotBlank() && hasCyrillic(it) }
            .distinctBy { it.lowercase() }
            .take(4)
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor   = MaterialTheme.colorScheme.surface,
        tonalElevation   = 0.dp,
        windowInsets     = WindowInsets.statusBars,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Row {
                Box(
                    modifier = Modifier
                        .width(86.dp)
                        .aspectRatio(0.68f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (cover != null) {
                        AsyncImage(
                            model              = cover,
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            Icons.Outlined.MenuBook,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = book.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (book.authors.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text  = book.authorLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val facts = remember(book.id) { factsLine(detail.suggestion) }
                    if (facts.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text  = facts,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Same as on the card: empty for an author match, and an accent-coloured sparkle
            // over nothing is worse than no line at all.
            if (detail.suggestion.reason.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp).size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = detail.suggestion.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            // Not «о чём эта книга» when nothing about the book could be found and what follows is
            // about the writer instead. Putting that heading over it would be a lie, and the
            // reader would spot it in the first sentence anyway.
            Text(
                text  = if (detail.aboutAuthor) "Об этой книге ничего не нашлось. Кто её написал:"
                        else "О чём эта книга",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            when {
                detail.isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(14.dp),
                        color       = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text  = "Читаем описание…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                detail.text != null -> Text(
                    text  = detail.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Said plainly rather than left blank. A catalogue with no blurb and a book
                // nobody has written about are ordinary things, and an empty space reads as a
                // broken screen.
                else -> Text(
                    text  = "Ни в каталоге, ни на Фантлабе, ни в Википедии не нашлось, о чём " +
                        "эта книга. Так бывает с новыми изданиями и с книгами, о которых пока " +
                        "мало писали.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (detail.credit.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text  = detail.credit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (genres.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(genres, key = { it }) { genre ->
                        Text(
                            text  = genre.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    0.5.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Year, length and rating, whichever of them the catalogue actually knew. */
private fun factsLine(suggestion: DiscoverySuggestion): String {
    val book = suggestion.book
    val parts = mutableListOf<String>()
    book.publishedYear?.let { parts += "$it год" }
    book.pageCount?.let { parts += "$it с." }
    val rating = book.averageRating
    if (rating != null && book.ratingsCount > 0) {
        parts += "★ ${"%.1f".format(rating)}"
    }
    return parts.joinToString(" · ")
}

// ── Bits ──────────────────────────────────────────────────────────────────────

/**
 * A cover off the reader's own shelf, for the row of marked books.
 *
 * The file is looked at once per path and not once per frame. `File.exists()` is a disk call, and
 * this ran inside composition for every chip in the row on every recomposition of the screen —
 * which is every keystroke in the search field, every arriving suggestion, every scroll that
 * brings a chip back. A dozen stats on the frame thread is where the stutter came from.
 */
@Composable
private fun LocalCover(coverPath: String?) {
    val cover = remember(coverPath) {
        coverPath?.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
    }
    if (cover != null) {
        AsyncImage(
            model              = cover,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize()
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.MenuBook,
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
