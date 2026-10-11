package com.lexiread.app.data.repository

import com.lexiread.app.data.api.GoogleBooksApiService
import com.lexiread.app.data.api.OpenLibraryApiService
import com.lexiread.app.data.api.WikidataApiService
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.repository.discovery.CallLog
import com.lexiread.app.data.repository.discovery.GoogleBooksSource
import com.lexiread.app.data.repository.discovery.OpenLibrarySource
import com.lexiread.app.data.repository.discovery.WikidataSource
import com.lexiread.app.domain.discover.DiscoveredBook
import com.lexiread.app.domain.discover.DiscoveryRanker
import com.lexiread.app.domain.discover.DiscoverySuggestion
import com.lexiread.app.domain.discover.LikedItem
import com.lexiread.app.domain.discover.TasteEntry
import com.lexiread.app.domain.discover.hasCyrillic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns the books a reader likes into books they might like next.
 *
 * Three catalogues, asked at once and ranked in a fixed order of preference:
 *
 *  1. **Google Books** — the Russian edition as it is actually printed, with a cover and a
 *     blurb. Best of the three at Russian editions of foreign novels.
 *  2. **Wikidata** — one item per work with a Russian label, so it names books Google has no
 *     edition for. No covers, no blurbs, no ratings.
 *  3. **Open Library** — covers and ratings, and titles in whatever language and whatever
 *     century's orthography the edition was catalogued in.
 *
 * The order is not decoration. The same novel comes back from all three, and [DiscoveryRanker]
 * keeps the first copy it meets of any title-and-author pair — so whichever source is
 * concatenated first is the record the reader ends up looking at. See [search].
 *
 * Every request is allowed to fail on its own. A catalogue that times out costs its own
 * suggestions and nothing else, which is why [CallLog] counts answers rather than checking any
 * single call: what the reader needs told apart is "nothing matched" from "nothing answered",
 * and an empty list looks identical either way.
 */
class BookDiscoveryRepository(
    openLibraryApi: OpenLibraryApiService,
    wikidataApi: WikidataApiService,
    googleBooksApi: GoogleBooksApiService
) {
    private val openLibrary = OpenLibrarySource(openLibraryApi)
    private val wikidata = WikidataSource(wikidataApi)
    private val googleBooks = GoogleBooksSource(googleBooksApi)

    data class Result(val suggestions: List<DiscoverySuggestion>, val reachedCatalogue: Boolean)
    private data class Cached(val time: Long, val result: Result)
    private val cache = linkedMapOf<String, Cached>()
    fun clearCache() { synchronized(cache) { cache.clear() } }

    suspend fun discover(liked: List<LikedItem>, owned: List<Book>, onPartial: (Result) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            if (liked.isEmpty()) return@withContext Result(emptyList(), true)
            val key = liked.toString() + owned.map { listOf(it.id, it.title, it.author) }.toString()
            synchronized(cache) { cache[key] }?.takeIf { System.nanoTime() - it.time < 600_000_000_000L }
                ?.let { return@withContext it.result }
            val calls = CallLog()
            val used = liked.take(MAX_LIKED_USED)

            val profile = buildProfile(used, calls)
            val language = if (used.any { readsRussian(it) }) OpenLibrarySource.RUSSIAN else null

            var candidates = inReadersScript(search(profile, language, calls) { books ->
                val partial = rankReadable(profile, inReadersScript(books, profile), owned, language)
                if (partial.isNotEmpty()) onPartial(Result(partial, calls.anySucceeded))
            }, profile)
            var ranked = rankReadable(profile, candidates, owned, language)

            // Too little to show, with one obvious thing to try: the language filter. Ask Open
            // Library again without it — the other two are Russian by construction and have
            // nothing wider to offer.
            if (ranked.size < MIN_RESULTS_BEFORE_WIDENING && language != null && calls.anySucceeded) {
                val wider = withTimeoutOrNull(1_000) { openLibrary.discover(
                    authors  = topAuthors(profile),
                    subjects = topSubjects(profile),
                    language = null,
                    calls    = calls
                ) }.orEmpty()
                candidates = (candidates + inReadersScript(wider, profile)).distinctBy { it.id }
                ranked = rankReadable(profile, candidates, owned, language)
            }

            val result = Result(withTimeoutOrNull(1_000) { inRussian(ranked, calls) } ?: ranked, calls.anySucceeded)
            if (result.suggestions.isNotEmpty()) synchronized(cache) {
                if (cache.size >= 8) cache.remove(cache.keys.first())
                cache[key] = Cached(System.nanoTime(), result)
            }
            result
        }

    /**
     * The finished list, with any transliterated writer looked up and named in Russian.
     *
     * Run here rather than over the candidates because it is a network request and its cost is
     * the number of names in it: the list is six books long, the candidates are hundreds. It runs
     * at all only when something in the list needs it, which for a shelf of Russian classics is
     * never — Wikidata's own suggestions arrive in Russian already.
     *
     * A failed lookup changes nothing. The catalogue's spelling is worse than the right one and
     * far better than no author at all.
     */
    private suspend fun inRussian(
        ranked: List<DiscoverySuggestion>,
        calls: CallLog
    ): List<DiscoverySuggestion> {
        val needing = ranked.filterNot { it.book.authors.any { name -> hasCyrillic(name) } }
        if (needing.isEmpty()) return ranked

        val russian = wikidata.russianNames(needing.flatMap { it.book.authors }, calls)
        if (russian.isEmpty()) return ranked

        return ranked.map { suggestion ->
            if (suggestion.book.authors.any { name -> hasCyrillic(name) }) return@map suggestion
            val known = suggestion.book.authors
                .firstNotNullOfOrNull { russian[it.trim()] } ?: return@map suggestion
            // In front of the originals rather than instead of them, as everywhere else: the
            // transliteration is still how this writer's other records are spelled.
            suggestion.copy(
                book = suggestion.book.copy(authors = listOf(known) + suggestion.book.authors)
            )
        }
    }

    /**
     * What the catalogues know about the books the reader marked.
     *
     * Both resolvers cover all the marks at once rather than running one after the other: they
     * are different services, and the wall clock rather than the request count is what the
     * reader is waiting on.
     */
    private suspend fun buildProfile(used: List<LikedItem>, calls: CallLog): List<TasteEntry> =
        coroutineScope {
            val fromWikidata = async { withTimeoutOrNull(2_000) { wikidata.resolve(used, calls) }.orEmpty() }
            val fromOpenLibrary = async {
                used.map { item -> async {
                    withTimeoutOrNull(2_000) { openLibrary.resolve(item, calls) }
                        ?: TasteEntry(item.title, listOf(item.author).filter(String::isNotBlank), emptyList())
                } }.awaitAll()
            }
            val facts = fromWikidata.await()
            val entries = fromOpenLibrary.await()

            used.mapIndexed { index, item ->
                val catalogued = entries[index]
                val known = facts[item.key]
                TasteEntry(
                    likedTitle = item.title,
                    // Both spellings of the same writer, deliberately: the Russian one matches
                    // what Wikidata suggests, the transliterated one matches Open Library.
                    authors    = (known?.authorNames.orEmpty() + catalogued.authors)
                        .distinctBy { it.lowercase() },
                    categories = catalogued.categories + known?.genreNames.orEmpty(),
                    authorIds  = known?.authorIds.orEmpty(),
                    genreIds   = known?.genreIds.orEmpty()
                )
            }
        }

    /**
     * All three catalogues at once, joined in the order their records are worth showing.
     *
     * Running them concurrently and joining them in a fixed order is deliberate: the wait is one
     * catalogue long rather than three, and the reader still sees Google's printed title over
     * Google's cover wherever Google had the book at all. Only what it did not have falls
     * through to Wikidata, and only what neither had falls through to Open Library.
     */
    private suspend fun search(
        profile: List<TasteEntry>,
        language: String?,
        calls: CallLog,
        onPartial: (List<DiscoveredBook>) -> Unit
    ): List<DiscoveredBook> = coroutineScope {
        val authors = topAuthors(profile)
        val subjects = topSubjects(profile)

        val answers = arrayOf<List<DiscoveredBook>>(emptyList(), emptyList(), emptyList())
        val answerLock = Any()
        suspend fun source(index: Int, query: suspend () -> List<DiscoveredBook>): List<DiscoveredBook> {
            val found = withTimeoutOrNull(4_000) { query() }.orEmpty()
            synchronized(answerLock) {
                answers[index] = found
                onPartial(answers.flatMap { it }.distinctBy { it.id })
            }
            return found
        }
        val fromGoogle = async { source(0) { googleBooks.discover(authors, calls) } }
        // The query service answers 502 when several requests land on it at once, so its two
        // queries run one after the other — inside a single coroutine, so that the other
        // catalogues are not made to wait for either of them.
        val fromWikidata = async { source(1) {
            wikidata.discoverByAuthor(topAuthorIds(profile), calls) +
                wikidata.discoverByGenre(topGenreIds(profile), calls)
        } }
        val fromOpenLibrary = async { source(2) { openLibrary.discover(authors, subjects, language, calls) } }

        (fromGoogle.await() + fromWikidata.await() + fromOpenLibrary.await())
            .distinctBy { it.id }
    }

    /**
     * The writer's name in Russian, where a catalogue only had the transliteration.
     *
     * Open Library files «Братья Карамазовы» under *Fyodor Dostoyevsky* and has no Russian
     * spelling on that record at all, so the card came out half translated: a Russian title over
     * a Latin name. The profile does have both spellings — Wikidata's Russian one and Open
     * Library's transliteration sit in the same entry, for the same writer — so the pairing is
     * already known here and costs nothing to apply.
     *
     * The Wikidata spelling is the one substituted in, rather than the shelf's shorter form,
     * because Wikidata's own suggestions carry exactly that string. Anything else would leave
     * the same writer under two names, which means the same book listed twice and the
     * two-books-per-author cap counting them as different people.
     *
     * Only writers the reader already likes can be fixed this way. A Latin name on a
     * genre-based suggestion has nothing in the profile to pair it with and stays as it is.
     */
    private fun inReadersScript(
        candidates: List<DiscoveredBook>,
        profile: List<TasteEntry>
    ): List<DiscoveredBook> {
        val russian = mutableMapOf<String, String>()
        for (entry in profile) {
            val cyrillic = entry.authors.firstOrNull { hasCyrillic(it) } ?: continue
            entry.authors.filterNot { hasCyrillic(it) }.forEach { latin ->
                russian.putIfAbsent(DiscoveryRanker.authorKey(latin), cyrillic)
            }
        }
        if (russian.isEmpty()) return candidates
        return candidates.map { book ->
            if (book.authors.any { hasCyrillic(it) }) return@map book
            val known = book.authors
                .firstNotNullOfOrNull { russian[DiscoveryRanker.authorKey(it)] }
                ?: return@map book
            // Kept in front of the originals rather than replacing them: the ranker matches
            // against every spelling, and the transliteration is how Open Library's own
            // suggestions for this writer will arrive.
            book.copy(authors = listOf(known) + book.authors)
        }
    }

    /**
     * The ranking, with anything the reader cannot read taken out of it first.
     *
     * Filtering the candidates rather than the results is the whole point: the ranker has six
     * places, a one-book-per-author rule and a quota on how many of them one kind of match may
     * take, and spending any of those on a title the reader will skip past costs a place a
     * readable suggestion wanted. Only when there are too few Russian titles to fill the list
     * does the rest come back, and then behind them rather than mixed in.
     */
    private fun rankReadable(
        profile: List<TasteEntry>,
        candidates: List<DiscoveredBook>,
        owned: List<Book>,
        language: String?
    ): List<DiscoverySuggestion> {
        if (language == null) return DiscoveryRanker.rank(profile, candidates, owned)
        val readable = DiscoveryRanker.rank(
            profile, candidates.filter { hasCyrillic(it.title) }, owned
        )
        if (readable.size >= DiscoveryRanker.DEFAULT_LIMIT) return readable
        val (russian, rest) = DiscoveryRanker.rank(profile, candidates, owned)
            .partition { hasCyrillic(it.book.title) }
        return (russian + rest).take(DiscoveryRanker.DEFAULT_LIMIT)
    }

    private fun topAuthors(profile: List<TasteEntry>): List<String> =
        profile.mapNotNull { it.authors.firstOrNull() }
            .map { it.trim() }.filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }.take(MAX_AUTHOR_QUERIES)

    /**
     * The genre tokens worth spending a query on.
     *
     * Cyrillic ones are dropped here and only here: they come from Wikidata, and both catalogues
     * that take a subject string index in English, so `subject=научная фантастика` finds nothing
     * at all. They stay in the profile regardless — that is what Wikidata's own suggestions get
     * scored against.
     */
    private fun topSubjects(profile: List<TasteEntry>): List<String> =
        mostShared(
            profile.map { entry ->
                DiscoveryRanker.genreTokens(entry.categories)
                    .filterNot { hasCyrillic(it) }
                    .take(MAX_TOKENS_PER_ENTRY)
            },
            MAX_SUBJECT_QUERIES
        )

    private fun topAuthorIds(profile: List<TasteEntry>): List<String> =
        mostShared(profile.map { it.authorIds }, MAX_WIKIDATA_AUTHORS)

    private fun topGenreIds(profile: List<TasteEntry>): List<String> =
        mostShared(profile.map { it.genreIds }, MAX_WIKIDATA_GENRES)

    /**
     * The values several liked books have in common, most-shared first.
     *
     * Shared beats prominent, and by a long way. Wikidata files «Преступление и наказание»
     * under «роман-фельетон» as well as «философское художественное произведение»; asking for
     * the first returns Dumas and Jules Verne, and the only thing that tells the two apart is
     * that the second is also on the reader's Lem and their Strugatskys.
     *
     * Position breaks ties because both sources arrive in a meaningful order — Open Library's
     * by how many of the author's books carry the heading, Wikidata's as it stores them — and
     * the name breaks the rest, so one profile always asks the same questions.
     */
    private fun mostShared(perEntry: List<List<String>>, limit: Int): List<String> {
        val shared = mutableMapOf<String, Int>()
        val bestPosition = mutableMapOf<String, Int>()
        for (values in perEntry) {
            values.forEachIndexed { position, value ->
                shared[value] = (shared[value] ?: 0) + 1
                bestPosition[value] = minOf(bestPosition[value] ?: Int.MAX_VALUE, position)
            }
        }
        return shared.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { bestPosition[it.key] ?: Int.MAX_VALUE }
                .thenBy { it.key }
        ).take(limit).map { it.key }
    }

    private fun readsRussian(item: LikedItem): Boolean =
        hasCyrillic(item.title) || hasCyrillic(item.author)

    companion object {
        /**
         * How many marks shape the suggestions. Public because the screen says so to the reader
         * once they have marked more than this.
         */
        const val MAX_LIKED_USED = 5

        private const val MAX_AUTHOR_QUERIES = 3
        private const val MAX_SUBJECT_QUERIES = 4
        private const val MAX_WIKIDATA_AUTHORS = 6
        private const val MAX_WIKIDATA_GENRES = 5
        private const val MAX_TOKENS_PER_ENTRY = 5

        /**
         * Below this the list is thin enough to be worth another round of requests.
         *
         * Deliberately under [DiscoveryRanker.DEFAULT_LIMIT] rather than equal to it: dropping
         * the language filter costs Open Library seven more queries, and paying that every time
         * a run comes back with five suggestions instead of six would be paying it almost
         * always, for one more card.
         */
        private const val MIN_RESULTS_BEFORE_WIDENING = 4
    }
}
