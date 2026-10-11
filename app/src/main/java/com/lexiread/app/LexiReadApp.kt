package com.lexiread.app

import android.app.Application
import com.lexiread.app.data.api.NetworkModule
import com.lexiread.app.data.db.AppDatabase
import com.lexiread.app.data.repository.BookDiscoveryRepository
import com.lexiread.app.data.repository.BookRepository
import com.lexiread.app.data.repository.BookSynopsisRepository
import com.lexiread.app.data.repository.DefinitionRepository
import com.lexiread.app.data.repository.OfflineDictLoader
import com.lexiread.app.data.repository.ReadingStatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LexiReadApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { AppDatabase.getInstance(this) }
    val network  by lazy { NetworkModule() }
    val cloud by lazy { com.lexiread.app.data.cloud.CloudSync(this) }

    val bookRepository by lazy {
        BookRepository(this, database.bookDao(), database.likedTitleDao()).also {
            it.onLocalPlaceChanged = { id -> cloud.placeChanged(id) }
            it.onAnchorCaptured = { id, anchor -> cloud.anchorCaptured(id, anchor) }
            it.beforeReading = { id -> cloud.pullPositionBeforeReading(id) }
            it.beforeDeleting = { book -> cloud.bookDeleted(book) }
            it.onLibraryChanged = { cloud.schedule() }
            it.onUserImport = { book -> cloud.bookImported(book) }
            it.onTitleChanged = { book, title -> cloud.titleChanged(book, title) }
        }
    }

    val definitionRepository by lazy {
        DefinitionRepository(
            api           = network.wikipediaApiService,
            dictionaryApi = network.dictionaryApiService,
            yandexApiKey  = network.yandexDictionaryApiKey,
            cache         = database.definitionCacheDao(),
            offlineDao    = database.offlineDictDao()
        )
    }

    val bookDiscoveryRepository by lazy {
        BookDiscoveryRepository(
            openLibraryApi = network.openLibraryApiService,
            wikidataApi    = network.wikidataApiService,
            googleBooksApi = network.googleBooksApiService
        )
    }

    /** What a suggested book is about, fetched when the reader opens one. */
    val bookSynopsisRepository by lazy {
        BookSynopsisRepository(
            api     = network.wikipediaApiService,
            fantLab = network.fantLabApiService,
            store   = database.synopsisCacheDao()
        )
    }

    val bookmarkDao by lazy { database.bookmarkDao() }
    val annotationDao by lazy { database.annotationDao().also { dao ->
        dao.onLocalChanged = { annotation, deleted -> cloud.markChanged(annotation,deleted) }
    } }
    val vocabularyDao by lazy { database.vocabularyDao().also { dao ->
        dao.onLocalChanged = { word, deleted -> cloud.personal.wordChanged(word, deleted) }
    } }

    val statsRepository by lazy { ReadingStatsRepository(this, database.readingDayDao()) }

    override fun onCreate() {
        super.onCreate()
        // PdfBox keeps its font metrics and glyph lists as ordinary resources and cannot find
        // them on Android without being handed the assets first. Done here rather than where a
        // PDF is opened: it is cheap, it is once, and forgetting it turns text extraction into a
        // silent failure that looks exactly like a scanned book.
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        // Populate offline dict in background on first launch
        appScope.launch {
            OfflineDictLoader(this@LexiReadApp, database.offlineDictDao()).ensureLoaded()
        }
    }
}
