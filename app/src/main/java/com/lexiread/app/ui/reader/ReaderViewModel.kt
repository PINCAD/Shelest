package com.lexiread.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lexiread.app.data.db.AnnotationDao
import com.lexiread.app.data.db.BookmarkDao
import com.lexiread.app.data.db.VocabularyDao
import com.lexiread.app.data.model.Annotation
import com.lexiread.app.data.model.AnnotationType
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.ReadingPosition
import com.lexiread.app.data.model.readingPosition
import com.lexiread.app.data.model.Bookmark
import com.lexiread.app.data.model.VocabularyWord
import com.lexiread.app.data.model.WordDefinition
import com.lexiread.app.data.repository.BookRepository
import com.lexiread.app.data.repository.DefinitionRepository
import com.lexiread.app.data.repository.ReadingStatsRepository
import com.lexiread.app.domain.parser.TocEntry
import com.lexiread.app.domain.parser.BookSegment
import com.lexiread.app.domain.parser.DocumentCache
import com.lexiread.app.domain.pdf.PdfTextLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class ContentState {
    object Loading  : ContentState()
    data class Html(
        val html: String,
        val book: Book,
        val savedProgress: Float = 0f,
        /** What the book says its own chapters are; empty for a format that states nothing. */
        val toc: List<TocEntry> = emptyList(),
        val segments: List<BookSegment> = emptyList(),
        val segmentIndex: Int = 0,
        val plainTextPath: String? = null,
        val savedPosition: ReadingPosition? = null
    ) : ContentState() {
        val isSegmented: Boolean get() = segments.size > 1
        val currentSegment: BookSegment? get() = segments.getOrNull(segmentIndex)

        fun globalPercent(localPercent: Float): Float {
            val segment = currentSegment ?: return localPercent.coerceIn(0f, 100f)
            val total = segments.maxOfOrNull { it.endChar }?.coerceAtLeast(1) ?: return localPercent
            val at = segment.startChar + segment.chars * localPercent.coerceIn(0f, 100f) / 100f
            return (at / total * 100f).coerceIn(0f, 100f)
        }
    }

    data class Pdf(
        val filePath: String,
        val pageCount: Int,
        val book: Book,
        val savedProgress: Float = 0f,
        /** The text behind the pictures, once it has been read out of the file. */
        val text: PdfTextLayer? = null,
        val textLoading: Boolean = false
    ) : ContentState()
    data class Error(val message: String) : ContentState()
}

sealed class DefinitionState {
    object Hidden   : DefinitionState()
    object Loading  : DefinitionState()
    data class Success(val definition: WordDefinition) : DefinitionState()
    data class Error(val word: String, val message: String) : DefinitionState()
}

data class PageState(
    val currentPage: Int = 0,
    val totalPages: Int  = 0,
    val bookPercent: Float = 0f,
    /** Page values for the footer.  The local values above remain for PDF marks and WebView. */
    val displayCurrentPage: Int = 0,
    val displayTotalPages: Int = 0,
    val isTotalEstimated: Boolean = false,
    /** Exact segment measurements already present for the active viewport/layout. */
    val measuredSegmentIndices: Set<Int> = emptySet()
) {
    val progress: Float get() = (bookPercent / 100f).coerceIn(0f, 1f)
}

/** Encodes a segment and its local page in the existing integer bookmark column. */
const val SEGMENT_BOOKMARK_STRIDE = 10_000

/**
 * How long the volume slider must sit still before the position is written down. Long enough
 * that a drag across the whole track is one write, short enough that letting go and closing the
 * sheet feels like it took effect immediately — which it did; only the record waits.
 */
private const val VOLUME_SAVE_DELAY_MS = 400L

/**
 * Above this, a minute's progress was not reading.
 *
 * Six thousand characters a minute is around a thousand words — several times the fastest
 * anybody actually reads a novel, so nothing genuine is thrown away, while a tap in the table of
 * contents or a drag of the progress slider lands far above it and is discarded. The bound is
 * generous on purpose: the cost of dropping a real minute is a pace measured from slightly fewer
 * samples, and the cost of keeping a fake one is an estimate that says a novel takes an hour.
 */
private const val MAX_CHARS_PER_MINUTE = 6_000

data class AmbientState(
    val soundId: String? = null,
    val customPath: String? = null,
    val isEnabled: Boolean = false,
    val volume: Float = 0.5f
)

data class TtsState(
    val isActive: Boolean = false,
    val isPlaying: Boolean = false,
    val speechRate: Float = 1.0f,
    val currentSentenceIndex: Int = 0,
    val currentSentenceText: String = "",
    /**
     * Whether the page turns itself to keep the spoken line in view.
     *
     * The line is always marked; this is only about the page moving under it. Off is for the
     * reader who is following along ahead of the voice, or who has turned back to re-read
     * something — for them a page that keeps snapping forward is the app arguing with them.
     */
    val followText: Boolean = true
)

data class DrawingHistoryState(val canUndo: Boolean = false, val canRedo: Boolean = false)
private data class DrawingHistoryEntry(
    val bookId: Long,
    val before: List<Annotation>,
    val after: List<Annotation>
) {
    val affectedIds get() = (before + after).map { it.id }.filter { it > 0 }.distinct()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    private val bookRepo: BookRepository,
    private val defRepo: DefinitionRepository,
    private val bookmarkDao: BookmarkDao,
    private val annotationDao: AnnotationDao,
    private val vocabularyDao: VocabularyDao,
    private val statsRepo: ReadingStatsRepository
) : ViewModel() {

    private val _content    = MutableStateFlow<ContentState>(ContentState.Loading)
    val content: StateFlow<ContentState> = _content.asStateFlow()

    private val _definition = MutableStateFlow<DefinitionState>(DefinitionState.Hidden)
    val definition: StateFlow<DefinitionState> = _definition.asStateFlow()

    private val _pageState  = MutableStateFlow(PageState())
    val pageState: StateFlow<PageState> = _pageState.asStateFlow()

    private val _isBookmarked = MutableStateFlow(false)
    val isBookmarked: StateFlow<Boolean> = _isBookmarked.asStateFlow()

    private val _snackMessage = MutableStateFlow<String?>(null)
    val snackMessage: StateFlow<String?> = _snackMessage.asStateFlow()

    private val _ambientState = MutableStateFlow(AmbientState())
    val ambientState: StateFlow<AmbientState> = _ambientState.asStateFlow()

    private val _ttsState = MutableStateFlow(TtsState())
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private var currentBookId: Long = -1L
    private val drawingHistoryMutex = Mutex()
    private val drawingUndo = mutableListOf<DrawingHistoryEntry>()
    private val drawingRedo = mutableListOf<DrawingHistoryEntry>()
    private val _drawingHistory = MutableStateFlow(DrawingHistoryState())
    val drawingHistory: StateFlow<DrawingHistoryState> = _drawingHistory.asStateFlow()
    private var currentBookmarkPage: Int = 0
    private var segmentLoadJob: Job? = null
    private var pageLayoutKey: String? = null
    private var measuredSegmentPages: Map<Int, Int> = emptyMap()

    /**
     * The open PDF, kept so it can be closed.
     *
     * It holds a file handle and the pages it has already read, and the reader may open one book
     * after another without ever leaving the screen — so the previous one is closed as the next
     * is opened, and the last one when the screen goes.
     */
    private var pdfText: PdfTextLayer? = null

    private val _bookIdFlow = MutableStateFlow(-1L)
    val annotations: StateFlow<List<Annotation>> = _bookIdFlow
        .flatMapLatest { id -> if (id <= 0L) flowOf(emptyList()) else annotationDao.getForBook(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookmarks: StateFlow<List<Bookmark>> = _bookIdFlow
        .flatMapLatest { id -> if (id <= 0L) flowOf(emptyList()) else bookmarkDao.getBookmarksForBook(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Words already in the personal vocabulary, so the definition sheet can show "saved" state. */
    val savedWords: StateFlow<Set<String>> = vocabularyDao.getAll()
        .map { list -> list.map { it.word.lowercase() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun loadBook(bookId: Long) {
        if (currentBookId != bookId) {
            drawingUndo.clear(); drawingRedo.clear(); _drawingHistory.value = DrawingHistoryState()
        }
        currentBookId = bookId
        _bookIdFlow.value = bookId
        viewModelScope.launch {
            _content.value = ContentState.Loading
            try {
            // Keep the exact local place available even when the network is slow.
            // Cancellation fences the cloud adapter, so a late response cannot move an open book.
            val cloudBegan = android.os.SystemClock.uptimeMillis()
            kotlinx.coroutines.withTimeoutOrNull(1200) { bookRepo.beforeReading(bookId) }
            android.util.Log.d("ShelestTiming", "open-cloud: ${android.os.SystemClock.uptimeMillis() - cloudBegan} ms")
            val book = bookRepo.getBook(bookId)
            if (book == null) {
                _content.value = ContentState.Error("Книга не найдена")
                return@launch
            }
            _ambientState.value = AmbientState(
                soundId    = book.ambientSoundId,
                customPath = book.ambientCustomPath,
                isEnabled  = book.ambientEnabled,
                volume     = book.ambientVolume
            )
            // Where the book was left, until the page that is about to open says otherwise.
            progressPercent = book.scrollPercent.coerceIn(0f, 100f)
            // Timed because this is one of three unrelated waits between tapping a book and
            // reading it — this one, building the document, and laying it out — and which of
            // them is the long one differs by book. See ShelestTiming in the log.
            val began = android.os.SystemClock.uptimeMillis()
            val parsed = bookRepo.loadBook(book)
            val raw = parsed.html
            android.util.Log.d(
                "ShelestTiming",
                "load: ${android.os.SystemClock.uptimeMillis() - began} ms, ${raw.length} chars"
            )
            if (raw.startsWith("__PDF__:")) {
                // "__PDF__:<path>:<pageCount>". Split from the right, not the left: a colon
                // is a legal character in a file name, and one in the book's own name used to
                // shift every field along and open the reader on a path that does not exist.
                val body = raw.removePrefix("__PDF__:")
                val path = body.substringBeforeLast(":").ifBlank { book.filePath }
                // Opening the file is cheap; reading a page out of it is not, and nothing here
                // asks it to. The pages of text are pulled apart only when a search, the voice
                // or the contents actually needs them.
                pdfText?.close()
                pdfText = null
                _content.value = ContentState.Pdf(
                    filePath      = path,
                    pageCount     = body.substringAfterLast(":").toIntOrNull() ?: 0,
                    book          = book,
                    savedProgress = book.scrollPercent,
                    text          = null,
                    textLoading   = true
                )
                // Paint the first page without waiting for PDFBox to parse the document.
                viewModelScope.launch {
                    val text = withContext(Dispatchers.IO) { PdfTextLayer.open(java.io.File(path)) }
                    val current = _content.value as? ContentState.Pdf
                    if (currentBookId == bookId && current?.filePath == path) {
                        pdfText = text
                        _content.value = current.copy(text = text, textLoading = false)
                    } else withContext(Dispatchers.IO) { text?.close() }
                }
            } else {
                val localProgress = parsed.segments.getOrNull(parsed.segmentIndex)?.let { segment ->
                    val total = parsed.segments.maxOfOrNull { it.endChar }?.coerceAtLeast(1) ?: 1
                    val target = book.scrollPercent.coerceIn(0f, 100f) / 100f * total
                    ((target - segment.startChar) / segment.chars.coerceAtLeast(1) * 100f)
                        .coerceIn(0f, 100f)
                } ?: book.scrollPercent
                _content.value = ContentState.Html(
                    html          = raw,
                    book          = book,
                    savedProgress = localProgress,
                    toc           = parsed.toc,
                    segments      = parsed.segments,
                    segmentIndex  = parsed.segmentIndex,
                    plainTextPath = parsed.plainTextPath,
                    savedPosition = book.readingPosition?.takeIf { it.segment == parsed.segmentIndex }
                )
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _content.value = ContentState.Error(error.message ?: "Не удалось открыть книгу. Проверьте файл и попробуйте снова")
            }
        }
    }

    /** Switches the bounded WebView window to one cached segment. */
    fun openHtmlSegment(index: Int, localProgress: Float = 0f) {
        val current = _content.value as? ContentState.Html ?: return
        if (!current.isSegmented || index !in current.segments.indices) return
        if (index == current.segmentIndex) return
        segmentLoadJob?.cancel()
        segmentLoadJob = viewModelScope.launch {
            val html = bookRepo.loadBookSegment(current.book.filePath, index) ?: return@launch
            val latest = _content.value as? ContentState.Html ?: return@launch
            if (latest.book.id != current.book.id) return@launch
            _content.value = latest.copy(
                html          = html,
                segmentIndex  = index,
                savedProgress = localProgress.coerceIn(0f, 100f),
                savedPosition = null
            )
        }
    }

    fun moveHtmlSegment(direction: Int) {
        val current = _content.value as? ContentState.Html ?: return
        if (direction == 0) return
        openHtmlSegment(
            index         = current.segmentIndex + if (direction < 0) -1 else 1,
            localProgress = if (direction < 0) 100f else 0f
        )
    }

    /** Percentage for library progress and reading statistics. Exact reopening uses ReadingPosition. */
    private var progressPercent: Float = 0f

    fun onReadingPositionChanged(position: ReadingPosition) {
        val html = _content.value as? ContentState.Html ?: return
        if (html.segmentIndex != position.segment || position.page < 0) return
        bookRepo.saveReadingPosition(html.book.id, position)
    }

    fun saveProgress() {
        if (currentBookId <= 0L) return
        val id = currentBookId
        val pct = progressPercent
        viewModelScope.launch {
            bookRepo.updateProgress(id, pct)
        }
    }

    /**
     * [percent] is the position in characters, as the page measured it. A reader with no such
     * measure to give — the PDF pager, whose pages are the file's own and not the screen's —
     * passes nothing, and the page count answers instead.
     */
    fun onPageChanged(
        currentPage: Int,
        totalPages: Int,
        percent: Float = -1f,
        bookmarkPage: Int = currentPage
    ) {
        val pct = when {
            percent >= 0f     -> percent
            totalPages > 1    -> currentPage.toFloat() / (totalPages - 1) * 100f
            else              -> 0f
        }.coerceIn(0f, 100f)
        _pageState.value = PageState(
            currentPage = currentPage,
            totalPages = totalPages,
            bookPercent = pct,
            displayCurrentPage = currentPage,
            displayTotalPages = totalPages
        )
        progressPercent = pct
        currentBookmarkPage = bookmarkPage
        if (_content.value is ContentState.Pdf) {
            bookRepo.saveReadingPosition(currentBookId, ReadingPosition(0, currentPage, null, "pdf"))
        }
        viewModelScope.launch {
            bookRepo.updateProgress(currentBookId, pct)
            _isBookmarked.value = bookmarkDao.getBookmarkAt(currentBookId, bookmarkPage) != null
        }
    }

    /**
     * Incorporates one WebView segment's real count into a whole-book page ledger.
     *
     * The current segment is laid out anyway, so recording it is free.  The unseen pieces are
     * projected from measured characters-per-page; the projection is replaced with an exact
     * sum as pieces are visited, and is kept on disk for the same layout on the next opening.
     */
    fun onHtmlPageChanged(
        currentPage: Int,
        segmentPages: Int,
        percent: Float,
        bookmarkPage: Int,
        layoutKey: String
    ) {
        val html = _content.value as? ContentState.Html
        if (html == null || !html.isSegmented) {
            onPageChanged(currentPage, segmentPages, percent, bookmarkPage)
            return
        }
        val segmentIndex = html.segmentIndex
        if (pageLayoutKey != layoutKey) {
            pageLayoutKey = layoutKey
            measuredSegmentPages = emptyMap()
            val book = html.book
            viewModelScope.launch {
                val cached = withContext(Dispatchers.IO) {
                    DocumentCache.pageCounts(java.io.File(book.filePath), layoutKey)
                }
                if (pageLayoutKey == layoutKey) {
                    // The visible segment may have reported while the small ledger file was
                    // being read; its just-measured value is newer than the saved one.
                    measuredSegmentPages = cached + measuredSegmentPages
                    updateSegmentedPageState(html, currentPage, segmentPages, percent)
                }
            }
        }
        if (segmentPages > 0 && measuredSegmentPages[segmentIndex] != segmentPages) {
            measuredSegmentPages = measuredSegmentPages + (segmentIndex to segmentPages)
            val book = html.book
            viewModelScope.launch(Dispatchers.IO) {
                DocumentCache.savePageCount(java.io.File(book.filePath), layoutKey, segmentIndex, segmentPages)
            }
        }
        updateSegmentedPageState(html, currentPage, segmentPages, percent)
        progressPercent = percent.coerceIn(0f, 100f)
        currentBookmarkPage = bookmarkPage
        viewModelScope.launch {
            bookRepo.updateProgress(currentBookId, progressPercent)
            _isBookmarked.value = bookmarkDao.getBookmarkAt(currentBookId, bookmarkPage) != null
        }
    }

    /** A hidden, size-matched WebView uses this while it measures unopened segments. */
    fun recordPrecalculatedSegmentPages(segmentIndex: Int, pages: Int, layoutKey: String) {
        val html = _content.value as? ContentState.Html ?: return
        if (!html.isSegmented || segmentIndex !in html.segments.indices || pages <= 0) return
        if (pageLayoutKey != layoutKey) return
        if (measuredSegmentPages[segmentIndex] == pages) return
        measuredSegmentPages = measuredSegmentPages + (segmentIndex to pages)
        val book = html.book
        viewModelScope.launch(Dispatchers.IO) {
            DocumentCache.savePageCount(java.io.File(book.filePath), layoutKey, segmentIndex, pages)
        }
        val local = _pageState.value
        updateSegmentedPageState(html, local.currentPage, local.totalPages, local.bookPercent)
    }

    private fun updateSegmentedPageState(
        html: ContentState.Html,
        currentPage: Int,
        segmentPages: Int,
        percent: Float
    ) {
        val known = measuredSegmentPages.filterKeys { it in html.segments.indices }
        val knownChars = known.keys.sumOf { html.segments[it].chars }.coerceAtLeast(1)
        val knownPages = known.values.sum().coerceAtLeast(1)
        val pagesFor: (Int) -> Int = { index ->
            known[index] ?: kotlin.math.max(1, kotlin.math.round(
                html.segments[index].chars.toDouble() * knownPages / knownChars
            ).toInt())
        }
        val prefix = (0 until html.segmentIndex).sumOf(pagesFor)
        val total = html.segments.indices.sumOf(pagesFor)
        _pageState.value = PageState(
            currentPage = currentPage,
            totalPages = segmentPages,
            bookPercent = percent.coerceIn(0f, 100f),
            displayCurrentPage = prefix + currentPage,
            displayTotalPages = total,
            isTotalEstimated = known.size != html.segments.size,
            measuredSegmentIndices = known.keys
        )
    }

    /** Resolve a global page only from measured counts, never percentages. */
    fun resolveGlobalPage(page: Int): Pair<Int, Int>? {
        val html = _content.value as? ContentState.Html ?: return null
        if (!html.isSegmented) return 0 to page
        var remaining = page
        for (segment in html.segments.indices) {
            val count = measuredSegmentPages[segment] ?: return null
            if (remaining < count) return segment to remaining
            remaining -= count
        }
        return null
    }

    fun toggleBookmark() {
        val page = currentBookmarkPage
        viewModelScope.launch {
            val existing = bookmarkDao.getBookmarkAt(currentBookId, page)
            if (existing != null) {
                bookmarkDao.delete(currentBookId, page)
                _isBookmarked.value  = false
                _snackMessage.value  = "Закладка удалена"
            } else {
                bookmarkDao.insert(Bookmark(bookId = currentBookId, pageIndex = page))
                _isBookmarked.value  = true
                _snackMessage.value  = "Закладка добавлена"
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            bookmarkDao.delete(bookmark.bookId, bookmark.pageIndex)
            // The top-bar bookmark icon shows state for the *current* page only, so it
            // has to be re-checked in case the row just deleted was that very page.
            if (bookmark.pageIndex == currentBookmarkPage) _isBookmarked.value = false
            _snackMessage.value = "Закладка удалена"
        }
    }

    // ── Reading time ─────────────────────────────────────────────────────

    private var readingJob: Job? = null

    /**
     * Where in the book the last tick found the reader, in characters.
     *
     * Null means "no earlier position to measure from" — the start of a sitting, or right after
     * a jump was thrown away — and the next tick will set it without counting anything.
     */
    private var lastCharOffset: Int? = null

    /**
     * Accumulates reading time in whole minutes while the reader is on screen, along with how
     * far through the book those minutes carried them.
     *
     * Ticking once a minute (instead of every second) keeps the DB writes rare; the price is
     * that an abandoned session loses its last partial minute.
     */
    fun startReadingSession() {
        if (readingJob?.isActive == true) return
        // A new sitting starts from wherever the book was left, which is not a distance anybody
        // read — most obviously when it is a different book from last time.
        lastCharOffset = null
        readingJob = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                statsRepo.addSeconds(60, charsSinceLastTick())
            }
        }
    }

    fun stopReadingSession() {
        readingJob?.cancel()
        readingJob = null
        lastCharOffset = null
    }

    /**
     * How much of the book went past in the minute just ended.
     *
     * The position is the reader's own, in characters, so this is a genuine count of text
     * covered rather than the guess it used to be — pages turned, multiplied by the length of
     * the book, which on a short one made a single page a whole percent of it.
     *
     * Two things are deliberately not counted. Going backwards, because re-reading a page is
     * reading but is not progress, and letting it subtract would make a careful reader look
     * slow. And any jump too large to have been read, because using the table of contents or
     * dragging the progress slider covers a chapter in a second and would otherwise be recorded
     * as the fastest minute of the reader's life.
     */
    private fun charsSinceLastTick(): Int {
        val totalChars = when (val state = _content.value) {
            is ContentState.Html -> state.book.totalChars
            is ContentState.Pdf  -> state.book.totalChars
            else                 -> 0
        }
        if (totalChars <= 0) return 0

        val here = (progressPercent / 100f * totalChars).toInt().coerceIn(0, totalChars)
        val before = lastCharOffset
        lastCharOffset = here
        if (before == null) return 0

        val delta = here - before
        return if (delta <= 0 || delta > MAX_CHARS_PER_MINUTE) 0 else delta
    }

    override fun onCleared() {
        super.onCleared()
        stopReadingSession()
        flushPendingVolume()
        pdfText?.close()
        pdfText = null
    }

    // ── Vocabulary ───────────────────────────────────────────────────────

    fun saveWordToVocabulary(word: String, definition: String) {
        val clean = word.trim()
        if (clean.isBlank()) return
        val bookTitle = (_content.value as? ContentState.Html)?.book?.title
            ?: (_content.value as? ContentState.Pdf)?.book?.title
        viewModelScope.launch {
            val existing = vocabularyDao.getByWord(clean)
            if (existing != null) {
                vocabularyDao.delete(existing)
                _snackMessage.value = "Убрано из словарика"
            } else {
                vocabularyDao.insert(
                    VocabularyWord(
                        word            = clean,
                        definition      = definition.take(600),
                        sourceBookTitle = bookTitle
                    )
                )
                _snackMessage.value = "«$clean» сохранено в словарике"
            }
        }
    }

    fun lookupWord(word: String) {
        if (word.isBlank() || word.length > 150) return
        _definition.value = DefinitionState.Loading
        // No manual language toggle anymore — detect from the selected word's script.
        val wikiLang = if (word.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }) "ru" else "en"
        viewModelScope.launch {
            defRepo.getDefinition(word.trim(), wikiLang)
                .onSuccess { _definition.value = DefinitionState.Success(it) }
                .onFailure { _definition.value = DefinitionState.Error(word.trim(), it.message ?: "Ошибка") }
        }
    }

    fun dismissDefinition() { _definition.value = DefinitionState.Hidden }
    fun clearSnack()        { _snackMessage.value = null }

    /** A word to the reader from the screen itself — a PDF with nothing in it to read aloud, say. */
    fun showSnack(text: String) { _snackMessage.value = text }

    fun toggleAmbient() {
        val next = !_ambientState.value.isEnabled
        _ambientState.value = _ambientState.value.copy(isEnabled = next)
        saveAmbientSettings()
    }

    fun setAmbientSound(soundId: String?, customPath: String? = null) {
        _ambientState.value = _ambientState.value.copy(
            soundId = soundId,
            customPath = customPath,
            isEnabled = !soundId.isNullOrBlank()
        )
        saveAmbientSettings()
    }

    fun disableAmbient() {
        if (_ambientState.value.isEnabled) {
            _ambientState.value = _ambientState.value.copy(isEnabled = false)
            saveAmbientSettings()
        }
    }

    /**
     * The volume slider reports every value it passes through, sixty times a second while a
     * finger is moving, and each one used to be an SQLite write — a hundred writes to say
     * "quieter", all but the last of them describing a position the finger has already left.
     *
     * The sound itself is not delayed: the player follows [ambientState], which is updated
     * here and now. Only the record of where the slider ended up waits for it to settle.
     */
    fun setAmbientVolume(volume: Float) {
        _ambientState.value = _ambientState.value.copy(volume = volume)
        volumeSaveJob?.cancel()
        volumeSaveJob = viewModelScope.launch {
            delay(VOLUME_SAVE_DELAY_MS)
            saveAmbientSettings()
        }
    }

    fun saveCustomAudio(uri: android.net.Uri) {
        viewModelScope.launch {
            val path = bookRepo.saveCustomAudioFile(uri)
            if (path != null) {
                _ambientState.value = _ambientState.value.copy(
                    soundId = "custom",
                    customPath = path,
                    isEnabled = true
                )
                saveAmbientSettings()
                _snackMessage.value = "Аудиофайл сохранен"
            } else {
                _snackMessage.value = "Ошибка сохранения аудиофайла"
            }
        }
    }

    private var volumeSaveJob: Job? = null

    private fun saveAmbientSettings() {
        if (currentBookId <= 0L) return
        // Any save writes the whole of the current state, so a pending debounce would only
        // repeat what is about to be written — and would do it after this one, which is how a
        // stale value overwrites a fresh one.
        volumeSaveJob?.cancel()
        volumeSaveJob = null
        val s = _ambientState.value
        viewModelScope.launch {
            bookRepo.updateAmbientSettings(
                id = currentBookId,
                soundId = s.soundId,
                customPath = s.customPath,
                enabled = s.isEnabled,
                volume = s.volume
            )
        }
    }

    /**
     * The pending volume, written out on the way out.
     *
     * Leaving the reader within the debounce window cancels viewModelScope with the save still
     * waiting in it, and the volume the reader just set would be forgotten — the one case where
     * debouncing would have cost them something rather than saved a write. Outside the scope
     * and outside the cancellation, because the point is to survive it.
     */
    private fun flushPendingVolume() {
        val pending = volumeSaveJob ?: return
        volumeSaveJob = null
        pending.cancel()
        if (currentBookId <= 0L) return
        val s = _ambientState.value
        val id = currentBookId
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            bookRepo.updateAmbientSettings(
                id = id,
                soundId = s.soundId,
                customPath = s.customPath,
                enabled = s.isEnabled,
                volume = s.volume
            )
        }
    }

    fun openTts() {
        _ttsState.value = _ttsState.value.copy(isActive = true, isPlaying = true)
    }

    fun setTtsPlaying(playing: Boolean) {
        _ttsState.value = _ttsState.value.copy(isPlaying = playing)
    }

    fun setTtsSpeed(speed: Float) {
        _ttsState.value = _ttsState.value.copy(speechRate = speed)
    }

    fun setTtsFollowText(follow: Boolean) {
        _ttsState.value = _ttsState.value.copy(followText = follow)
    }

    fun updateTtsSentence(index: Int, text: String) {
        _ttsState.value = _ttsState.value.copy(
            currentSentenceIndex = index,
            currentSentenceText = text
        )
    }

    fun closeTts() {
        _ttsState.value = _ttsState.value.copy(isActive = false, isPlaying = false)
    }

    // ── Annotations ──────────────────────────────────────────────────────

    fun addHighlight(selectedText: String, contextBefore: String, contextAfter: String, color: Int) {
        if (currentBookId <= 0L) return
        viewModelScope.launch {
            annotationDao.insert(
                Annotation(
                    bookId        = currentBookId,
                    type          = AnnotationType.HIGHLIGHT,
                    color         = color,
                    selectedText  = selectedText,
                    contextBefore = contextBefore,
                    contextAfter  = contextAfter
                )
            )
        }
    }

    fun addTextNote(selectedText: String, contextBefore: String, contextAfter: String, noteText: String, color: Int) {
        if (currentBookId <= 0L) return
        viewModelScope.launch {
            annotationDao.insert(
                Annotation(
                    bookId        = currentBookId,
                    type          = AnnotationType.NOTE,
                    color         = color,
                    selectedText  = selectedText,
                    contextBefore = contextBefore,
                    contextAfter  = contextAfter,
                    noteText      = noteText
                )
            )
        }
    }

    fun addPdfNote(pageIndex: Int, normX: Float, normY: Float, noteText: String, color: Int) {
        if (currentBookId <= 0L) return
        viewModelScope.launch {
            annotationDao.insert(
                Annotation(
                    bookId    = currentBookId,
                    type      = AnnotationType.NOTE,
                    color     = color,
                    pageIndex = pageIndex,
                    normX     = normX,
                    normY     = normY,
                    noteText  = noteText
                )
            )
        }
    }

    fun addDrawing(pageIndex: Int, pathData: String, color: Int) {
        if (currentBookId <= 0L) return
        viewModelScope.launch {
            drawingHistoryMutex.withLock {
                val bookId = currentBookId
                val drawing = Annotation(
                    bookId    = currentBookId,
                    type      = AnnotationType.DRAWING,
                    color     = color,
                    pageIndex = pageIndex,
                    pathData  = pathData
                )
                val id = annotationDao.insert(drawing)
                recordDrawingEdit(DrawingHistoryEntry(bookId, emptyList(), listOf(drawing.copy(id = id))))
            }
        }
    }

    private fun recordDrawingEdit(entry: DrawingHistoryEntry) {
        if (entry.bookId != currentBookId) return
        drawingUndo.add(entry)
        if (drawingUndo.size > 20) drawingUndo.removeAt(0)
        drawingRedo.clear()
        _drawingHistory.value = DrawingHistoryState(canUndo = drawingUndo.isNotEmpty())
    }

    fun undoDrawing() = applyDrawingHistory(undo = true)
    fun redoDrawing() = applyDrawingHistory(undo = false)

    private fun applyDrawingHistory(undo: Boolean) {
        viewModelScope.launch {
            drawingHistoryMutex.withLock {
                val source = if (undo) drawingUndo else drawingRedo
                val destination = if (undo) drawingRedo else drawingUndo
                val entry = source.lastOrNull()?.takeIf { it.bookId == currentBookId } ?: return@withLock
                annotationDao.replaceDrawingState(entry.affectedIds, if (undo) entry.before else entry.after)
                source.removeAt(source.lastIndex)
                destination.add(entry)
                _drawingHistory.value = DrawingHistoryState(drawingUndo.isNotEmpty(), drawingRedo.isNotEmpty())
            }
        }
    }

    fun updateAnnotationColor(annotation: Annotation, color: Int) {
        viewModelScope.launch { annotationDao.setColor(annotation.id,color) }
    }

    fun updateAnnotationNote(annotation: Annotation, noteText: String) {
        viewModelScope.launch { annotationDao.setNote(annotation.id,noteText) }
    }

    fun deleteAnnotation(annotation: Annotation) {
        viewModelScope.launch {
            annotationDao.delete(annotation)
            if (annotation.type == AnnotationType.DRAWING) _snackMessage.value = "Рисунок удалён"
        }
    }

    /** Erasing one whole stroke is a single undoable edit. */
    fun eraseDrawing(annotation: Annotation) {
        viewModelScope.launch {
            drawingHistoryMutex.withLock {
                if (annotation.bookId != currentBookId) return@withLock
                val current = annotationDao.getById(annotation.id) ?: return@withLock
                annotationDao.delete(current)
                recordDrawingEdit(DrawingHistoryEntry(currentBookId, listOf(current), emptyList()))
            }
        }
    }

    /** A moving eraser splits each touched stroke, preserving the untouched pieces. */
    fun eraseDrawingParts(changes: List<Pair<Annotation, List<String>>>) {
        if (changes.isEmpty()) return
        viewModelScope.launch {
            drawingHistoryMutex.withLock {
                val before = mutableListOf<Annotation>()
                val after = mutableListOf<Annotation>()
                changes.forEach { (original, fragments) ->
                    if (original.bookId != currentBookId) return@forEach
                    val current = annotationDao.getById(original.id) ?: return@forEach
                    val replacements = annotationDao.eraseDrawingPartsWithSnapshot(current, fragments)
                    if (replacements.map { it.pathData } != listOf(current.pathData)) {
                        before.add(current); after.addAll(replacements)
                    }
                }
                if (before.isNotEmpty()) recordDrawingEdit(DrawingHistoryEntry(currentBookId, before, after))
            }
        }
    }

    /** Erase every drawing on one PDF page in a single action. */
    fun deleteAnnotations(list: List<Annotation>) {
        if (list.isEmpty()) return
        viewModelScope.launch {
            drawingHistoryMutex.withLock {
                val current = list.filter { it.type == AnnotationType.DRAWING && it.bookId == currentBookId }
                    .mapNotNull { annotationDao.getById(it.id) }
                if (current.isEmpty()) return@withLock
                current.forEach { annotationDao.delete(it) }
                recordDrawingEdit(DrawingHistoryEntry(currentBookId, current, emptyList()))
                _snackMessage.value = "Удалено рисунков: ${current.size}"
            }
        }
    }

    /**
     * Plain-text dump of everything marked in this book, for the share sheet —
     * notes are worth little if they can only ever be read inside this one app.
     */
    fun buildNotesExport(): String {
        val htmlState = _content.value as? ContentState.Html
        val book = htmlState?.book
            ?: (_content.value as? ContentState.Pdf)?.book
        val marks = annotations.value
        val marked = bookmarks.value

        return buildString {
            appendLine(book?.title ?: "Книга")
            book?.author?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            appendLine()

            marks.filter { it.type != AnnotationType.DRAWING }.forEach { a ->
                a.selectedText?.takeIf { it.isNotBlank() }?.let { appendLine("«$it»") }
                a.noteText?.takeIf { it.isNotBlank() }?.let { appendLine("— $it") }
                a.pageIndex?.let { appendLine("(стр. ${it + 1})") }
                appendLine()
            }

            if (marked.isNotEmpty()) {
                appendLine("Закладки: " + marked.sortedBy { it.pageIndex }
                    .joinToString(", ") {
                        if (htmlState?.isSegmented == true) {
                            val segment = it.pageIndex / SEGMENT_BOOKMARK_STRIDE
                            val page = it.pageIndex % SEGMENT_BOOKMARK_STRIDE
                            "фрагм. ${segment + 1}, стр. ${page + 1}"
                        } else "стр. ${it.pageIndex + 1}"
                    })
            }

            appendLine()
            append("Шелест")
        }
    }
}

class ReaderViewModelFactory(
    private val bookRepo: BookRepository,
    private val defRepo: DefinitionRepository,
    private val bookmarkDao: BookmarkDao,
    private val annotationDao: AnnotationDao,
    private val vocabularyDao: VocabularyDao,
    private val statsRepo: ReadingStatsRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ReaderViewModel(bookRepo, defRepo, bookmarkDao, annotationDao, vocabularyDao, statsRepo) as T
}
