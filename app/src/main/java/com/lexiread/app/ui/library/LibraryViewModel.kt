package com.lexiread.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.cloud.addToCollection
import com.lexiread.app.data.cloud.editCollection
import com.lexiread.app.data.repository.BookRepository
import com.lexiread.app.data.repository.ReadingStats
import com.lexiread.app.data.repository.ReadingStatsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class LibrarySort(val label: String) {
    RECENT("Недавние"),
    TITLE("Название"),
    AUTHOR("Автор"),
    ADDED("Добавлены"),
    PROGRESS("Прогресс"),
    COLLECTION("Порядок томов")
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class LibraryViewModel(
    private val repo: BookRepository,
    statsRepo: ReadingStatsRepository,
    private val cloud: com.lexiread.app.data.cloud.CloudSync? = null
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(LibrarySort.RECENT)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    /** The full shelf, unfiltered — the screen needs it to tell "no books" from "no matches". */
    val allBooks: StateFlow<List<Book>> = repo.allBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val books: StateFlow<List<Book>> =
        // Share the DAO subscription with the unfiltered count/selection. The field itself
        // updates immediately; only shelf work waits briefly while the user is typing.
        combine(allBooks, _query.debounce { if (it.isBlank()) 0L else 120L }, _sort) { list, q, sortMode ->
            filterLibrary(list, q, sortMode)
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<ReadingStats> = statsRepo.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingStats())
    val collections: StateFlow<List<com.shelest.shared.BookCollection>> = if (cloud == null) MutableStateFlow(emptyList())
        else combine(cloud.account, cloud.personal.revision) { account, _ -> cloud.personal.collections(account?.optString("id").orEmpty()) }
            .flowOn(kotlinx.coroutines.Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val bookHashes: StateFlow<Map<Long, String>> = if (cloud == null) MutableStateFlow(emptyMap())
        else combine(allBooks, collections) { books, groups ->
            // Resolve newly imported local copies even with automatic sync disabled.
            // Hashes are cached by size/mtime; no file work takes place during scrolling.
            books.mapNotNull { book -> (cloud.knownCollectionHash(book)
                ?: if (groups.any { it.members.isNotEmpty() }) runCatching { cloud?.collectionHash(book) }.getOrNull() else null)
                ?.let { book.id to it } }.toMap()
        }.flowOn(kotlinx.coroutines.Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    fun addCollection(id: String?, name: String, books: List<Book>) { viewModelScope.launch {
        try { cloud?.addToCollection(id, name, books); _snackMessage.value = "Книги добавлены в коллекцию" }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { _snackMessage.value = error.message ?: "Не удалось сохранить коллекцию" }
    } }
    fun editCollection(id: String, name: String? = null, delete: Boolean = false, remove: List<String> = emptyList(), order: List<String>? = null) { viewModelScope.launch {
        try { cloud?.editCollection(id, name, delete, remove, order) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { _snackMessage.value = error.message ?: "Не удалось изменить коллекцию" }
    } }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _importProgress = MutableStateFlow<String?>(null)
    val importProgress: StateFlow<String?> = _importProgress.asStateFlow()

    private val _snackMessage = MutableStateFlow<String?>(null)
    val snackMessage: StateFlow<String?> = _snackMessage.asStateFlow()

    fun setQuery(value: String) { _query.value = value }
    fun setSort(value: LibrarySort) { _sort.value = value }

    fun addBook(uri: Uri) = addBooks(listOf(uri))

    fun addBooks(uris: List<Uri>) {
        if (uris.isEmpty() || _isLoading.value) return
        val uniqueUris = uris.distinct()
        viewModelScope.launch {
            _isLoading.value = true
            var added = 0
            uniqueUris.forEachIndexed { index, uri ->
                _importProgress.value = "Добавляем книги: ${index + 1} из ${uniqueUris.size}"
                if (repo.addBook(uri) != null) added++
            }
            _snackMessage.value = when {
                added == uniqueUris.size -> "Добавлено книг: $added"
                added == 0 -> "Не удалось добавить книги. Проверьте формат и доступ к файлам"
                else -> "Добавлено: $added из ${uniqueUris.size}; остальные файлы не открылись"
            }
            _importProgress.value = null
            _isLoading.value = false
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch { runCatching { repo.deleteBook(book) }.onFailure { _snackMessage.value = "Не удалось удалить книгу. Повторите попытку" } }
    }

    /** Use the ordinary repository path for each book, including cloud tombstones. */
    fun deleteBooks(books: List<Book>) {
        val targets = books.distinctBy { it.id }
        viewModelScope.launch {
            var failures = 0
            targets.forEach { book ->
                try { repo.deleteBook(book) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { failures++ }
            }
            if (failures > 0) _snackMessage.value = "Не удалось удалить книг: $failures. Повторите попытку"
        }
    }

    fun renameBook(book: Book, title: String) {
        viewModelScope.launch {
            runCatching { repo.renameBook(book.id, title) }
                .onSuccess { _snackMessage.value = "Название изменено" }
                .onFailure { _snackMessage.value = it.message ?: "Не удалось переименовать книгу" }
        }
    }

    fun setCover(book: Book, uri: Uri) {
        viewModelScope.launch {
            runCatching { repo.setCover(book.id, uri) }
                .onSuccess { _snackMessage.value = "Обложка установлена" }
                .onFailure { _snackMessage.value = it.message ?: "Не удалось установить обложку" }
        }
    }

    /**
     * Marks a book as one the reader liked, from the long-press menu on the shelf.
     *
     * The mark belongs to the discovery screen — it is what the catalogue search is built from —
     * but the moment a reader knows they liked a book is the moment they finish it, on this
     * screen, not on that one. Reachable from both, stored in one place.
     */
    fun setLiked(book: Book, liked: Boolean) {
        viewModelScope.launch {
            repo.setLiked(book.id, liked)
            _snackMessage.value =
                if (liked) "«${book.title}» в избранном" else "Убрано из избранного"
        }
    }

    fun clearSnack() { _snackMessage.value = null }
}

internal fun filterLibrary(books: List<Book>, query: String, sort: LibrarySort): List<Book> {
    val term = query.trim()
    val filtered = if (term.isEmpty()) books else books.filter {
        it.title.contains(term, ignoreCase = true) || it.author.contains(term, ignoreCase = true)
    }
    return when (sort) {
        LibrarySort.RECENT, LibrarySort.COLLECTION -> filtered // The screen applies the collection's saved order.
        // Compute each normalised key once, not during every comparator invocation.
        LibrarySort.TITLE -> filtered.map { it.title.lowercase(java.util.Locale.ROOT) to it }
            .sortedBy { it.first }.map { it.second }
        LibrarySort.AUTHOR -> filtered.map { it.author.lowercase(java.util.Locale.ROOT) to it }
            .sortedBy { it.first }.map { it.second }
        LibrarySort.ADDED -> filtered.sortedByDescending { it.addedAt }
        LibrarySort.PROGRESS -> filtered.sortedByDescending { it.scrollPercent }
    }
}

class LibraryViewModelFactory(
    private val repo: BookRepository,
    private val statsRepo: ReadingStatsRepository,
    private val cloud: com.lexiread.app.data.cloud.CloudSync? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LibraryViewModel(repo, statsRepo, cloud) as T
}
