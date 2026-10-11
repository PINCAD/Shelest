package com.lexiread.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lexiread.app.R
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.isLiked
import com.lexiread.app.data.repository.ReadingStats
import com.lexiread.app.ui.settings.HomeSettings
import com.lexiread.app.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    /** Which blocks above the shelf the reader has left switched on. See [HomeSettings]. */
    home: HomeSettings,
    showOnboarding: Boolean,
    onOnboardingDone: () -> Unit,
    onBookClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onVocabularyClick: () -> Unit,
    onDiscoverClick: () -> Unit,
    /** The stats strip is the whole entry point to the statistics screen — see [ReadingStatsStrip]. */
    onStatsClick: () -> Unit
) {
    val books        by viewModel.books.collectAsStateWithLifecycle()
    val allBooks     by viewModel.allBooks.collectAsStateWithLifecycle()
    val query        by viewModel.query.collectAsStateWithLifecycle()
    val librarySort  by viewModel.sort.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val bookHashes by viewModel.bookHashes.collectAsStateWithLifecycle()
    var activeCollectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var collectionSort by rememberSaveable { mutableStateOf(LibrarySort.COLLECTION.name) }
    val sort = if (activeCollectionId == null) librarySort else LibrarySort.valueOf(collectionSort)
    val activeCollection = collections.find { it.id == activeCollectionId }
    var showCollectionPicker by remember { mutableStateOf(false) }
    var editCollection by remember { mutableStateOf(false) }
    val collectionBooks = remember(collections, allBooks, bookHashes) {
        val byHash = allBooks.associateBy { bookHashes[it.id] }
        collections.map { collection -> com.shelest.ui.UiCollection(collection.id, collection.name,
            collection.members.mapNotNull { sha -> byHash[sha]?.let { book -> book.toShelfBook() } }, collection.members.size) }
    }
    val stats        by viewModel.stats.collectAsStateWithLifecycle()
    val isLoading    by viewModel.isLoading.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val snackMessage by viewModel.snackMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val compactHeader = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 380
    val cloud = (context.applicationContext as com.lexiread.app.LexiReadApp).cloud
    val syncDeletions by cloud.syncDeletions.collectAsState()
    val account by cloud.account.collectAsState()
    var deleteCandidates by remember { mutableStateOf<List<Book>>(emptyList()) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    val selectedBooks = remember(allBooks, selectedIds) { selectedIds.mapNotNull { id -> allBooks.find { it.id == id } } }
    val leaveCollection = {
        activeCollectionId = null; viewModel.setQuery(""); selectedIds = emptyList()
    }
    val visibleBooks = remember(books, activeCollection, activeCollectionId, bookHashes, sort) {
        if (activeCollectionId == null) books else {
            val ranks = activeCollection?.members.orEmpty().withIndex().associate { it.value to it.index }
            val local = books.filter { bookHashes[it.id] in ranks }
            when (sort) {
                LibrarySort.COLLECTION -> local.sortedBy { ranks[bookHashes[it.id]] }
                LibrarySort.RECENT -> local.sortedWith(compareByDescending<Book> { it.lastOpenedAt ?: 0L }.thenByDescending { it.addedAt })
                else -> filterLibrary(local, "", sort)
            }
        }
    }
    var lastOwner by rememberSaveable { mutableStateOf(account?.optString("id").orEmpty()) }
    LaunchedEffect(account?.optString("id")) {
        val owner = account?.optString("id").orEmpty()
        if (owner != lastOwner) {
            leaveCollection(); showCollectionPicker = false; editCollection = false; deleteCandidates = emptyList()
            lastOwner = owner
        }
    }
    LaunchedEffect(allBooks) {
        if (allBooks.isNotEmpty()) selectedIds = selectedIds.filter { id -> allBooks.any { it.id == id } }
    }
    BackHandler(selectedIds.isNotEmpty() && deleteCandidates.isEmpty()) { selectedIds = emptyList() }
    BackHandler(activeCollectionId != null && selectedIds.isEmpty()) { leaveCollection() }
    var renameCandidate by remember { mutableStateOf<Book?>(null) }
    var coverCandidate by remember { mutableStateOf<Book?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val book = coverCandidate
        coverCandidate = null
        if (uri != null && book != null) viewModel.setCover(book, uri)
    }
    val scope = rememberCoroutineScope()
    var showAddMenu by remember { mutableStateOf(false) }
    var tourDismissed by remember { mutableStateOf(false) }
    val tourTargets = remember { mutableStateMapOf<Int, Rect>() }
    var scanning by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.addBooks(uris) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) scope.launch {
            scanning = true
            val found = runCatching { findBooksInTree(context.contentResolver, uri) }
            scanning = false
            found.onSuccess { booksInFolder ->
                if (booksInFolder.isEmpty()) snackbarHostState.showSnackbar("Подходящих книг в папке не найдено")
                else viewModel.addBooks(booksInFolder.map { it.uri })
            }.onFailure {
                snackbarHostState.showSnackbar("Не удалось прочитать папку. Выберите другую")
            }
        }
    }

    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnack()
        }
    }
    if (showCollectionPicker) CollectionPicker(collections, { showCollectionPicker = false }) { id, name ->
        viewModel.addCollection(id, name, selectedBooks); showCollectionPicker = false; selectedIds = emptyList()
    }
    if (editCollection && activeCollection != null) CollectionEditor(activeCollection,
        allBooks.mapNotNull { book -> bookHashes[book.id]?.let { it to book.title } }.toMap(),
        { editCollection = false }, { name, members ->
            viewModel.editCollection(activeCollection.id, name, remove = activeCollection.members - members.toSet(), order = members)
            editCollection = false
        }, { viewModel.editCollection(activeCollection.id, delete = true); editCollection = false; leaveCollection() })

    renameCandidate?.let { candidate ->
        var name by remember(candidate.id) { mutableStateOf(candidate.title) }
        AlertDialog(onDismissRequest = { renameCandidate = null },
            title = { Text("Переименовать") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(500) },
                label = { Text("Название книги") }, singleLine = true, shape = RoundedCornerShape(12.dp)) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                viewModel.renameBook(candidate, name); renameCandidate = null
            }) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { renameCandidate = null }) { Text("Отмена") } })
    }
    if (deleteCandidates.isNotEmpty()) {
        val candidates = deleteCandidates
        AlertDialog(onDismissRequest = { deleteCandidates = emptyList() },
            title = { Text(if (candidates.size == 1) "Удалить книгу?" else "Удалить выбранные книги?") },
            text = { Text((if (candidates.size == 1) "«${candidates.single().title}» будет удалена " else "Выбранные книги (${candidates.size}) будут удалены ") +
                if (account != null && syncDeletions) "из облака и со всех устройств аккаунта." else "только с этого устройства.") },
            confirmButton = { TextButton(onClick = { deleteCandidates = emptyList(); selectedIds = emptyList(); viewModel.deleteBooks(candidates) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleteCandidates = emptyList() }) { Text("Отмена") } })
    }
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
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
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment   = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (selectedBooks.isNotEmpty()) {
                        LibrarySelectionBar(selectedBooks.size, selectedBooks.first().isLiked,
                            onCancel = { selectedIds = emptyList() },
                            onLike = { selectedBooks.singleOrNull()?.let { viewModel.setLiked(it, !it.isLiked) }; selectedIds = emptyList() },
                            onRename = { renameCandidate = selectedBooks.singleOrNull(); selectedIds = emptyList() },
                            onCover = { coverCandidate = selectedBooks.singleOrNull(); selectedIds = emptyList(); coverPicker.launch(arrayOf("image/*")) },
                            onDelete = { deleteCandidates = selectedBooks.toList() },
                            onCollection = { showCollectionPicker = true })
                    } else {
                    // Keep 48dp touch targets. At 320dp the brand is its logo + count;
                    // squeezing a two-line title here otherwise wraps the count letter by letter.
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(R.mipmap.logo),
                            contentDescription = "Шелест",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(Modifier.width(if (compactHeader) 8.dp else 10.dp))
                        if (compactHeader) Text(allBooks.size.toString(),
                            modifier = Modifier.semantics { contentDescription = "Книг: " + allBooks.size },
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Column {
                            Text(
                                text     = "Шелест",
                                style    = MaterialTheme.typography.titleMedium,
                                color    = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                // The shelf's own size, rather than the word "библиотека" over
                                // a screen that is visibly one. It is the one number about the
                                // library the reader cannot get by looking at it.
                                text  = if (allBooks.isEmpty()) "Моя библиотека"
                                        else "${allBooks.size} " +
                                            plural(allBooks.size, "книга", "книги", "книг"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        // Up here rather than in a panel down the screen. It is a way through to
                        // another screen, which is what every other control in this row is, and
                        // it no longer has a section of its own to sit in.
                        ChromeIconButton(
                            icon               = Icons.Outlined.AutoAwesome,
                            contentDescription = "Новые книги по вашему вкусу",
                            // Quiet like the dictionary and the settings beside it. «Добавить»
                            // stays the one accent in the row; two would be neither.
                            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                            onPositioned       = { tourTargets[0] = it },
                            onClick            = onDiscoverClick
                        )
                        ChromeIconButton(
                            icon               = Icons.Outlined.Translate,
                            contentDescription = "Словарик",
                            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                            onPositioned       = { tourTargets[1] = it },
                            onClick            = onVocabularyClick
                        )
                        ChromeIconButton(
                            icon = Icons.Filled.Add,
                            contentDescription = "Добавить книги",
                            isLoading = isLoading || scanning,
                            tint = MaterialTheme.colorScheme.primary,
                            onPositioned = { tourTargets[2] = it },
                            onClick = { if (!isLoading && !scanning) showAddMenu = true }
                        )
                        ChromeIconButton(
                            icon               = Icons.Outlined.Settings,
                            contentDescription = "Настройки",
                            tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                            onPositioned       = { tourTargets[3] = it },
                            onClick            = onSettingsClick
                        )
                    }
                    }
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color     = MaterialTheme.colorScheme.outlineVariant
                )
                if (importProgress != null || scanning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        importProgress ?: "Ищем книги в папке…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    ) { padding ->
                BookGrid(
                    books           = visibleBooks,
                    total           = activeCollection?.members?.size ?: allBooks.size,
                    busy            = isLoading || scanning,
                    onAdd           = { showAddMenu = true },
                    stats           = stats,
                    query           = query,
                    sort            = sort,
                    home            = home,
                    modifier        = Modifier.padding(padding),
                    onQueryChange   = viewModel::setQuery,
                    onSortChange    = { if (activeCollectionId == null) viewModel.setSort(it) else collectionSort = it.name },
                    onBookClick     = onBookClick,
                    onDeleteClick   = { deleteCandidates = listOf(it) },
                    onLikeClick     = viewModel::setLiked,
                    onRenameClick   = { renameCandidate = it },
                    onCoverClick    = { coverCandidate = it; coverPicker.launch(arrayOf("image/*")) },
                    onStatsClick    = onStatsClick,
                    selectedIds     = selectedIds.toSet(),
                    onSelect        = { id -> selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id },
                    collections = if (activeCollectionId == null) collectionBooks else emptyList(),
                    collectionTitle = if (activeCollectionId == null) "" else activeCollection?.name ?: "Коллекция удалена",
                    onCollectionOpen = { activeCollectionId = it; collectionSort = LibrarySort.COLLECTION.name; selectedIds = emptyList(); viewModel.setQuery("") },
                    onCollectionBack = { leaveCollection() },
                    onCollectionEdit = { editCollection = true },
                    missingCount = activeCollection?.members?.count { sha -> sha !in bookHashes.values } ?: 0
                )
    }

    if (showAddMenu) {
        ModalBottomSheet(
            onDismissRequest = { showAddMenu = false },
            containerColor = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ImportActionRow(
                    icon = Icons.Outlined.FileOpen,
                    title = "Выбрать файлы",
                    subtitle = "Один или несколько файлов",
                    onClick = {
                        showAddMenu = false
                        filePicker.launch(arrayOf("*/*"))
                    }
                )
                ImportActionRow(
                    icon = Icons.Outlined.FolderOpen,
                    title = "Загрузить папку",
                    subtitle = "Добавим все книги из неё и подпапок",
                    onClick = {
                        showAddMenu = false
                        folderPicker.launch(null)
                    }
                )
            }
        }
    }
    if (showOnboarding && !tourDismissed && tourTargets.size == 4) {
        LibraryTour(
            targets = (0..3).mapNotNull { tourTargets[it] },
            onFinish = {
                tourDismissed = true
                onOnboardingDone()
            }
        )
    }
    }
}

@Composable
private fun ImportActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp))
    }
}

// ── Minimal chrome buttons — plus and settings share one style ────────────────

/** Shared so the sort menu can be placed just below a button of this height. */
@Composable
internal fun LibrarySelectionBar(count: Int, liked: Boolean, onCancel: () -> Unit, onLike: () -> Unit,
    onRename: () -> Unit, onCover: () -> Unit, onDelete: () -> Unit, onCollection: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onCancel) { Icon(Icons.Default.Close, "Отменить выделение") }
        Text(if (count == 1 && onCollection != null) count.toString() else "Выбрано: $count",
            Modifier.weight(1f).semantics { contentDescription = "Выбрано: $count" }, style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count == 1 && onCollection == null) {
            IconButton(onLike) { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (liked) "Из избранного" else "В избранное", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onRename) { Icon(Icons.Default.Edit, "Переименовать") }
            IconButton(onCover) { Icon(Icons.Default.Image, "Обложка") }
        }
        if (onCollection != null) IconButton(onCollection) { Icon(Icons.Default.CollectionsBookmark, "В коллекцию") }
        if (count == 1 && onCollection != null) {
            IconButton(onLike) { Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (liked) "Из избранного" else "В избранное", tint = MaterialTheme.colorScheme.primary) }
            // Keep narrow-screen top actions compact; editing is in the single-book overflow.
            var more by remember { mutableStateOf(false) }
            Box {
                IconButton({ more = true }) { Icon(Icons.Default.MoreVert, "Редактировать книгу") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("Переименовать") }, onClick = { more = false; onRename() })
                    DropdownMenuItem(text = { Text("Обложка") }, onClick = { more = false; onCover() })
                }
            }
        }
        IconButton(onDelete) { Icon(Icons.Default.DeleteOutline, "Удалить выбранные книги", tint = MaterialTheme.colorScheme.error) }
    }
}

private val CHROME_BUTTON_SIZE = 40.dp
private val CHROME_TOUCH_SIZE = 48.dp

@Composable
private fun ChromeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    isLoading: Boolean = false,
    onPositioned: ((Rect) -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .size(CHROME_TOUCH_SIZE)
            .onGloballyPositioned { coordinates -> onPositioned?.invoke(coordinates.boundsInRoot()) }
            // The hit target is deliberately larger than the painted 40dp button. It still
            // needs its own shape: ripple belongs to clickable(), and otherwise fills the
            // transparent 48dp parent as a hard square around the rounded button.
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(CHROME_BUTTON_SIZE)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp), color = tint, strokeWidth = 2.dp
                )
            } else {
                Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Grid ──────────────────────────────────────────────────────────────────────

@Composable
private fun BookGrid(
    books: List<Book>,
    total: Int,
    busy: Boolean,
    onAdd: () -> Unit,
    stats: ReadingStats,
    query: String,
    sort: LibrarySort,
    home: HomeSettings,
    modifier: Modifier,
    onQueryChange: (String) -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onBookClick: (Long) -> Unit,
    onDeleteClick: (Book) -> Unit,
    onLikeClick: (Book, Boolean) -> Unit,
    onRenameClick: (Book) -> Unit,
    onCoverClick: (Book) -> Unit,
    onStatsClick: () -> Unit,
    selectedIds: Set<Long>,
    onSelect: (Long) -> Unit,
    collections: List<com.shelest.ui.UiCollection> = emptyList(), collectionTitle: String = "",
    onCollectionOpen: (String) -> Unit = {}, onCollectionBack: () -> Unit = {}, onCollectionEdit: () -> Unit = {}, missingCount: Int = 0
) {
    val byId = remember(books) { books.associateBy { it.id.toString() } }
    val shelf = remember(books) { books.map {
        it.toShelfBook()
    } }
    Box(modifier.fillMaxSize()) {
        com.shelest.ui.LibraryShelf(shelf, query, sort.name, total, busy, { action, id ->
            when(action) {
                "query" -> onQueryChange(id)
                "sort" -> LibrarySort.entries.find { it.name == id }?.let(onSortChange)
                "add" -> onAdd()
                else -> byId[id]?.let { book ->
                    when(action) {
                        "open" -> onBookClick(book.id)
                        "select" -> onSelect(book.id)
                        "delete" -> onDeleteClick(book)
                        "like" -> onLikeClick(book, !book.isLiked)
                        "rename" -> onRenameClick(book)
                        "cover" -> onCoverClick(book)
                    }
                }
            }
        }, header = {
            if (collectionTitle.isNotEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onCollectionBack) { Icon(Icons.Default.ArrowBack, "Все книги") }
                Text(collectionTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onCollectionEdit) { Icon(Icons.Default.Edit, "Изменить коллекцию") }
            }
            if (missingCount > 0) Text("Книг на других устройствах: $missingCount. Они появятся после импорта или облачной загрузки.",
                Modifier.padding(horizontal = 8.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (home.showStats && (stats.streakDays > 0 || stats.todaySeconds > 0))
                ReadingStatsStrip(stats, onClick = onStatsClick)
        }, cover = { entry ->
            GeneratedCover()
            val path = byId[entry.id]?.coverPath
            if (!path.isNullOrEmpty()) AsyncImage(model = remember(path) { java.io.File(path) },
                contentDescription = entry.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }, showCount = false, showAdd = false, selectedIds = selectedIds.map { it.toString() }.toSet(),
            allowRecent = collectionTitle.isEmpty(), afterControls = {
                com.shelest.ui.CollectionShelf(collections, onCollectionOpen) { entry ->
                    GeneratedCover()
                    if (entry.cover.isNotEmpty()) AsyncImage(model = remember(entry.cover) { java.io.File(entry.cover) },
                        contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            })
    }
}

private fun Book.toShelfBook() = com.shelest.ui.ShelfBook(id.toString(), title, author, fileType.name,
    scrollPercent.toDouble() / 100.0, isLiked, lastOpenedAt != null, coverPath.orEmpty())


// ── Reading stats strip ───────────────────────────────────────────────────────

/**
 * A quiet, one-line reminder of the reading habit — streak, today, this week.
 * Only appears once there is something to show, so a brand-new install isn't
 * greeted by a row of zeroes.
 *
 * Also the door to the whole of it: tapping opens the statistics screen, where these three
 * numbers are the summary line. Deliberately without a chevron or any other invitation — the
 * strip's job above the shelf is to be glanced at and ignored, and something that asks to be
 * tapped every time the library opens is not that.
 */
@Composable
private fun ReadingStatsStrip(stats: ReadingStats, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .height(IntrinsicSize.Min)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatCell(
            value     = if (stats.streakDays > 0) stats.streakDays.toString() else "—",
            unit      = if (stats.streakDays > 0) plural(stats.streakDays, "день", "дня", "дней") else null,
            label     = "подряд",
            // The streak is the one number worth defending, so it is the only one
            // that gets the accent — a second accent would flatten the hierarchy.
            highlight = stats.streakDays > 0,
            modifier  = Modifier.weight(1f)
        )
        CellDivider()
        val today = splitDuration(stats.todaySeconds)
        StatCell(value = today.first, unit = today.second, label = "сегодня", modifier = Modifier.weight(1f))
        CellDivider()
        val week = splitDuration(stats.weekSeconds)
        StatCell(value = week.first, unit = week.second, label = "за неделю", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CellDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun StatCell(
    value: String,
    unit: String?,
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Number and unit on one baseline: the digit carries the weight, the unit
        // stays quiet, so the row scans as numbers rather than as sentences.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text  = value,
                style = MaterialTheme.typography.headlineMedium,
                color = if (highlight) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
            if (unit != null) {
                Text(
                    text     = unit,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp, bottom = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text  = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Splits a duration into the number and its unit so they can be styled separately. */
private fun splitDuration(seconds: Int): Pair<String, String?> {
    val minutes = seconds / 60
    return when {
        minutes <= 0 -> "—" to null
        minutes < 60 -> minutes.toString() to "мин"
        else         -> {
            val hours = minutes / 60
            val rest  = minutes % 60
            if (rest == 0) hours.toString() to "ч" else "$hours,${rest * 10 / 60}" to "ч"
        }
    }
}

/** Russian numeral agreement: 1 день, 2 дня, 5 дней. */
private fun plural(count: Int, one: String, few: String, many: String): String {
    val mod100 = count % 100
    if (mod100 in 11..14) return many
    return when (count % 10) {
        1    -> one
        2, 3, 4 -> few
        else -> many
    }
}


// ── Neutral placeholder cover — used for every book without a real cover ──────

@Composable
private fun GeneratedCover() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Outlined.MenuBook,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(28.dp)
        )
    }
}
