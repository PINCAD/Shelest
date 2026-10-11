package com.lexiread.app.ui.discover

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.repository.BookDiscoveryRepository
import com.lexiread.app.data.repository.BookRepository
import com.lexiread.app.data.repository.BookSynopsisRepository
import com.lexiread.app.domain.discover.DiscoverySuggestion
import com.lexiread.app.domain.discover.LikedItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Held still on purpose. A `List` field is not a stable type to Compose — a list can be mutated
 * behind its holder's back — so a class carrying one is assumed changed on every recomposition
 * and everything reading it is rebuilt whether or not anything moved. These are all built once
 * and never touched again, which is what [Immutable] says, and the suggestion cards stop being
 * thrown away and redrawn every time an unrelated part of the state ticks.
 */
@Immutable
data class DiscoverUiState(
    val isLoading: Boolean = false,
    val suggestions: List<DiscoverySuggestion> = emptyList(),
    /** Covers and blurbs already fetched for the first visible cards. */
    val cardPreviews: Map<String, DiscoverCardPreview> = emptyMap(),
    /** Shown in place of the list. Already in Russian. */
    val message: String? = null
)

/** The small, immediately useful part of a fetched sheet detail. */
@Immutable
data class DiscoverCardPreview(
    val description: String,
    val coverUrl: String? = null,
    val aboutAuthor: Boolean = false
)

/**
 * One suggestion, opened.
 *
 * [text] arrives after the card does — it is a network call — so the sheet is opened with the
 * suggestion alone and fills in. [credit] names whoever the text came from — an article, or
 * Fantlab — and is blank when it came from the catalogue's own blurb, which credits nobody.
 *
 * [aboutAuthor] is set when nothing could be found about the book and [text] is about the writer
 * instead. The heading above it changes to say so; the reader is not shown a note about Asimov
 * under the words «О чём эта книга».
 */
@Immutable
data class BookDetail(
    val suggestion: DiscoverySuggestion,
    val isLoading: Boolean = true,
    val text: String? = null,
    val credit: String = "",
    val coverUrl: String? = null,
    val aboutAuthor: Boolean = false
)

/**
 * The discovery screen: books the reader does not own, chosen from the ones they marked.
 *
 * Marking a book re-runs the search on its own, because making the reader press "найти" after
 * every tick would be asking them to do the app's bookkeeping. The debounce is what makes that
 * affordable — ticking four books in a row is one search, not four — and [collectLatest] means
 * a search already in flight is abandoned rather than raced when the set changes again.
 */
class DiscoverViewModel(
    private val repo: BookRepository,
    private val discovery: BookDiscoveryRepository,
    private val synopses: BookSynopsisRepository
) : ViewModel() {

    /** The whole shelf, for the picker. */
    val library: StateFlow<List<Book>> = repo.allBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Everything marked, from the shelf and typed in by hand, most recent first. */
    val liked: StateFlow<List<LikedItem>> = repo.likedItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    /** A one-off line for the reader — shown once and cleared. Not part of the search result. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** The card the reader opened, or nothing. */
    private val _detail = MutableStateFlow<BookDetail?>(null)
    val detail: StateFlow<BookDetail?> = _detail.asStateFlow()

    private var detailJob: Job? = null

    /** Reading ahead — see [prefetch]. Always second in line behind a card the reader opened. */
    private var prefetchJob: Job? = null

    /** Bumped by [refresh]; nothing reads the number, only that it changed. */
    private val refreshTicks = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(
                repo.likedItems.distinctUntilChangedBy { list -> list.map { it.key } },
                refreshTicks
            ) { likedItems, _ -> likedItems }
                // The spinner goes up before the debounce, not after it: the reader ticked a
                // book and something has to acknowledge that in the same moment they did it.
                .onEach { likedItems ->
                    _state.update {
                        it.copy(isLoading = likedItems.isNotEmpty(), message = null)
                    }
                }
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { likedItems -> load(likedItems) }
        }
    }

    fun setLiked(book: Book, isLiked: Boolean) {
        viewModelScope.launch { repo.setLiked(book.id, isLiked) }
    }

    /** A book the reader likes but does not have. Blank titles and repeats are turned away. */
    fun addTitle(title: String, author: String) {
        viewModelScope.launch {
            _notice.value = when {
                title.isBlank() -> "Впишите название"
                repo.addLikedTitle(title, author) -> null
                else -> "«${title.trim()}» уже в списке"
            }
        }
    }

    /** Takes the mark back, whichever way it was made. */
    fun removeLiked(item: LikedItem) {
        viewModelScope.launch {
            val id = item.key.substringAfter(':').toLongOrNull() ?: return@launch
            if (item.onShelf) repo.setLiked(id, false) else repo.removeLikedTitle(id)
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    /**
     * Opens a suggestion and goes to find out what it is about.
     *
     * The sheet appears first and the text lands in it, rather than the tap doing nothing until
     * the network answers. A second tap while the first is still in flight cancels it: the
     * reader has moved on, and the answer to the book they closed would overwrite the one they
     * are looking at.
     */
    fun open(suggestion: DiscoverySuggestion) {
        detailJob?.cancel()
        // The reader has chosen. Reading ahead was a guess at this and has lost.
        prefetchJob?.cancel()
        _detail.value = BookDetail(suggestion)
        detailJob = viewModelScope.launch {
            val found = synopses.of(suggestion.book)
            // Opening a card cancels the speculative prefetch. Its result is still useful
            // after the sheet closes: without keeping it, the sheet has a real cover while the
            // card beneath it is left with the placeholder until the next full refresh.
            found?.let { publishPreview(suggestion, it) }
            _detail.update { current ->
                // Still the same card — see above; without the check a slow answer could land
                // on top of a different book.
                if (current?.suggestion?.book?.id != suggestion.book.id) current
                else current.copy(
                    isLoading   = false,
                    text        = found?.text,
                    credit      = found?.credit.orEmpty(),
                    coverUrl    = found?.coverUrl,
                    aboutAuthor = found?.aboutAuthor == true
                )
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        detailJob = null
        _detail.value = null
    }

    fun refresh() {
        discovery.clearCache()
        refreshTicks.value += 1
    }

    private suspend fun load(likedItems: List<LikedItem>) {
        if (likedItems.isEmpty()) {
            _state.value = DiscoverUiState()
            return
        }
        val owned = repo.allBooks.first()
        val result = discovery.discover(likedItems, owned) { partial ->
            _state.update { it.copy(isLoading = true, suggestions = partial.suggestions, message = null) }
        }
        _state.value = DiscoverUiState(
            isLoading   = false,
            suggestions = result.suggestions,
            message     = when {
                !result.reachedCatalogue ->
                    "Не удалось связаться с каталогом. Проверьте подключение к интернету."
                result.suggestions.isEmpty() ->
                    "По этим книгам ничего не нашлось. Отметьте ещё одну, чтобы уточнить подборку."
                else -> null
            }
        )
        prefetch(result.suggestions)
    }

    /**
     * Reads the first few cards' descriptions before anybody taps them.
     *
     * The list is on screen and the network is doing nothing; the reader is looking at covers and
     * deciding which one to open, and that is exactly as long as the lookup takes. Whichever they
     * open first is then already in the repository's cache and the sheet fills in at once —
     * without this, opening a card is a five-request wait every time, and up to fifteen for a book
     * nothing recognises.
     *
     * All six cards are warmed so a reader does not have to open one just to replace its empty
     * cover. Two lookups at a time are enough to make the row fill promptly, while avoiding a
     * burst of requests to the free catalogues. [prefetchJob] is cancelled by the next search,
     * and by [open] — a real tap is worth more than a guess at one.
     */
    private fun prefetch(suggestions: List<DiscoverySuggestion>) {
        prefetchJob?.cancel()
        if (suggestions.isEmpty()) return
        prefetchJob = viewModelScope.launch {
            val gate = Semaphore(MAX_CONCURRENT_PREFETCHES)
            suggestions.take(PREFETCHED).map { suggestion ->
                async {
                    // Cached inside, and a failure is nobody's problem here: the card can still
                    // be opened and will retry on demand.
                    val preview = gate.withPermit {
                        runCatching { synopses.of(suggestion.book) }.getOrNull()
                    } ?: return@async
                    publishPreview(suggestion, preview)
                }
            }.awaitAll()
        }
    }

    private fun publishPreview(
        suggestion: DiscoverySuggestion,
        preview: BookSynopsisRepository.Synopsis
    ) {
        _state.update { current ->
            // A refresh may have replaced the list while this request was in flight.
            // Never attach an old book's cover or blurb to a new set of cards.
            if (current.suggestions.none { it.book.id == suggestion.book.id }) current
            else current.copy(
                cardPreviews = current.cardPreviews + (
                    suggestion.book.id to DiscoverCardPreview(
                        description = preview.text,
                        coverUrl = preview.coverUrl,
                        aboutAuthor = preview.aboutAuthor
                    )
                )
            )
        }
    }

    private companion object {
        /** Long enough to tick several books as one search, short enough not to feel stuck. */
        const val SEARCH_DEBOUNCE_MS = 600L

        /**
         * The recommendation list is deliberately short, so every card warms its own cover and
         * preview before the reader scrolls to it.
         */
        const val PREFETCHED = 6

        /** Keeps catalogue requests responsive without treating a refresh as a traffic spike. */
        const val MAX_CONCURRENT_PREFETCHES = 2
    }
}

class DiscoverViewModelFactory(
    private val repo: BookRepository,
    private val discovery: BookDiscoveryRepository,
    private val synopses: BookSynopsisRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DiscoverViewModel(repo, discovery, synopses) as T
}
