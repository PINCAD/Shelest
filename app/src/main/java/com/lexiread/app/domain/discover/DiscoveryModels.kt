package com.lexiread.app.domain.discover

import androidx.compose.runtime.Immutable

/**
 * A book from the catalogue — by definition one the reader does not have. Everything here is
 * already display-ready: the repository does the cleaning up, so the screen never has to know
 * that thumbnails arrive over plain http or that a "published date" can be a bare year, a full
 * ISO date, or nothing at all.
 *
 * [Immutable] because of the two `List` fields. Compose has no way of knowing that a list handed
 * to it will not be written to behind its back, so it assumes the worst and treats anything
 * holding one as changed on every pass — which meant every card on the discovery screen was
 * rebuilt whenever any part of the screen's state moved. These are built by the repository, read
 * by the screen, and never modified; saying so is the whole fix.
 */
@Immutable
data class DiscoveredBook(
    /** Catalogue volume id. Stable, and the list key. */
    val id: String,
    val title: String,
    val authors: List<String>,
    val description: String = "",
    val categories: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
    val publishedYear: String? = null,
    val pageCount: Int? = null,
    val averageRating: Double? = null,
    val ratingsCount: Int = 0,
    /**
     * The catalogue's own page for this book, where it has one.
     *
     * Never shown and never opened: the reader is told what the book is about on the screen
     * they are already on. It is kept because Wikidata's link is the Russian Wikipedia article
     * itself, which saves the synopsis lookup from having to guess which article that is —
     * see [com.lexiread.app.data.repository.BookSynopsisRepository].
     */
    val infoLink: String? = null,
    /**
     * The other name the catalogue holds for the same book — the original where [title] is the
     * Russian one, the Russian where it is not.
     *
     * Never shown; it is a second thing to search for when the first finds nothing. A record can
     * carry a Russian title that no search engine knows — Open Library has «Академия и Империя»
     * filed as «Акадимия и Империя», one letter out — and the original name is then the only way
     * back to the book. See [com.lexiread.app.data.repository.BookSynopsisRepository].
     */
    val alternateTitle: String? = null
) {
    val displayTitle: String get() = cleanCatalogueTitle(title)
    /**
     * The authors as the card shows them.
     *
     * A catalogue files one writer under several spellings at once — the Strugatskys arrive as
     * `["Аркадий Стругацкий", "Борис Стругацкий", "Arkady and Boris Strugatsky"]` — and printing
     * all of them reads as three different people. When any of the names is in Russian, the
     * Russian ones are the whole answer and the transliterations are the same names again.
     *
     * [authors] itself keeps every spelling: that is what the ranker matches against, and the
     * shelf may well spell the name the other way.
     */
    val authorLine: String
        get() = authors.filter { hasCyrillic(it) }
            .ifEmpty { authors }
            .take(MAX_AUTHORS_SHOWN)
            .joinToString(", ") { asPrinted(it) }

    private companion object {
        /** Past three names the line is a credits list, not an attribution. */
        const val MAX_AUTHORS_SHOWN = 3
    }
}

private val PATRONYMIC = Regex("(ович|евич|ьич|инична|ична|овна|евна)$", RegexOption.IGNORE_CASE)
private val INITIAL = Regex("^\\p{L}\\.$")
private val WORDS = Regex("\\s+")

/**
 * The name the way it is printed on a cover, not the way a catalogue files it.
 *
 * Wikidata's Russian label for a writer is the full legal name — «Фёдор Михайлович Достоевский»,
 * «Аркадий Натанович Стругацкий», «Евгений Иванович Замятин» — because that is what a database of
 * people stores. No cover has ever said that. The middle name goes, and so does a bare initial
 * standing in for one, and what is left is the two words a reader recognises.
 *
 * Only the middle of exactly three words, which is why «Ганс Христиан Андерсен» and «Артур Конан
 * Дойл» are safe: neither middle name is a patronymic and neither is an initial.
 */
internal fun asPrinted(name: String): String {
    val words = name.trim().split(WORDS).filter { it.isNotBlank() }
    if (words.size != 3) return name.trim()
    val middle = words[1]
    val droppable = PATRONYMIC.containsMatchIn(middle) || INITIAL.matches(middle)
    return if (droppable) "${words[0]} ${words[2]}" else words.joinToString(" ")
}

internal fun cleanCatalogueTitle(title: String): String = title
    .replace(Regex("\\s*\\((?:Russian|English|Kindle|Paperback|Hardcover)(?:\\s+Edition)?\\)\\s*", RegexOption.IGNORE_CASE), " ")
    .trim().ifBlank { title.trim() }

internal fun looksLikeOpeningQuote(text: String): Boolean =
    text.trimStart().startsWith("…") || text.trimStart().startsWith("...")

private val CYRILLIC = Regex("[\\u0400-\\u04FF]")

/** Whether this text is in the script the reader actually reads. */
internal fun hasCyrillic(text: String): Boolean = CYRILLIC.containsMatchIn(text)

/**
 * Whether a passage — a blurb, not a name — is written in the reader's own language.
 *
 * Google files a Russian novel under a Danish edition's blurb readily enough: «Метро 2035» comes
 * back with «3. del af den post-apokalyptiske science fiction-romanserie», which is a correct
 * description of the book and of no use to anyone reading this screen. `langRestrict=ru` restricts
 * the *edition*, not the prose Google attached to it.
 *
 * A share rather than [hasCyrillic], because a Russian blurb routinely carries a Latin word or
 * two — a title in the original, a name — and a foreign one carries no Cyrillic at all. Counted
 * over letters only, so digits and punctuation cannot tip a short line either way.
 */
internal fun inReadersLanguage(text: String): Boolean {
    var cyrillic = 0
    var letters = 0
    for (c in text) {
        if (!c.isLetter()) continue
        letters++
        if (c in CYRILLIC_RANGE) cyrillic++
    }
    return letters > 0 && cyrillic.toDouble() / letters >= CYRILLIC_SHARE
}

private val CYRILLIC_RANGE = 'Ѐ'..'ӿ'
private const val CYRILLIC_SHARE = 0.5

/**
 * The words that say "this is a book" and the words that say "this is what was made out of one".
 *
 * Every famous novel shares its name with a film, and every reference work describes both in the
 * same shape of sentence. Wikidata answers «Девушка с татуировкой дракона» with four items and
 * puts David Fincher's film first; Wikipedia answers «Атлант расправил плечи» with the 2011
 * adaptation. The two lists are used against one-line Wikidata descriptions and against the lead
 * paragraph of a Wikipedia article, which is why they live here rather than in either caller.
 */
internal val A_BOOK = Regex(
    "роман|повест|рассказ|книг|поэма|новелл|сборник|трилог|сказк|эпопе|антиутоп|мемуар|пьес",
    RegexOption.IGNORE_CASE
)

internal val NOT_A_BOOK = Regex(
    "фильм|сериал|мультфильм|аниме|опер[аы]|спектакл|игра|игры|альбом|песн|группа|" +
        "журнал|газет|экраниз|киноадаптац|кинокартин|телепередач|мюзикл|манга|комикс",
    RegexOption.IGNORE_CASE
)

/**
 * Whether a description is of a book rather than of something made from one.
 *
 * Position, not presence. A film is routinely described by way of the novel it came from —
 * «экранизация одноимённого детектива Стига Ларссона» names the writer and the genre and is
 * still not the book — so what decides it is which kind of word the sentence leads with.
 */
internal fun describesABook(text: String): Boolean {
    val book = A_BOOK.find(text)?.range?.first ?: return false
    val other = NOT_A_BOOK.find(text)?.range?.first ?: return true
    return book < other
}

/**
 * One suggestion. The reason names the reader's own book it came from, which is the only form
 * of explanation that means anything here: "Fiction / Science Fiction" is the catalogue's
 * language, «Научная фантастика — как «Солярис»» is theirs.
 *
 * [Immutable] for the same reason as [DiscoveredBook]: it holds one.
 */
@Immutable
data class DiscoverySuggestion(
    val book: DiscoveredBook,
    val reason: String,
    val score: Double,
    /**
     * Whether this is another book by a writer the reader already likes, as opposed to a
     * different writer working in the same genres.
     *
     * The two are worth different amounts to a reader and the list is built to hold some of
     * each — a screen of nothing but "тот же автор" is a bibliography, not a recommendation.
     * See [DiscoveryRanker.rank].
     */
    val byAuthor: Boolean
)

/**
 * One liked book, resolved against the catalogue.
 *
 * [authors] and [categories] come from the catalogue rather than from the file's own metadata,
 * and that is the point of resolving at all: a shelf author is whatever the EPUB's publisher
 * typed, while a catalogue author is the same string the catalogue will hand back for every
 * other book by that person. Comparing the two directly is what makes "тот же автор" work.
 */
data class TasteEntry(
    /** The title as it appears on the reader's shelf — what the reason line quotes back. */
    val likedTitle: String,
    val authors: List<String>,
    val categories: List<String>,
    /**
     * Wikidata item ids for the authors, when Wikidata recognised this book.
     *
     * Names are ambiguous and spelled a dozen ways; an id is neither. Only the Wikidata
     * queries use these — the other catalogues have never heard of a QID — so they are
     * empty whenever that lookup found nothing, and everything still works without them.
     */
    val authorIds: List<String> = emptyList(),
    /** Wikidata item ids for the genres, on the same terms as [authorIds]. */
    val genreIds: List<String> = emptyList()
)

/**
 * Something the reader likes, whether or not the app has the file.
 *
 * The taste profile used to be a list of shelf books, which quietly meant a reader could only
 * describe their taste with files they had already imported. Most of what anyone has read is
 * not on their shelf. A typed-in entry carries exactly what a shelf book contributes to the
 * profile — a title and an author — and nothing else, because nothing else was ever used.
 */
data class LikedItem(
    /** Stable across both sources, and the list key: `book:12` or `typed:5`. */
    val key: String,
    val title: String,
    val author: String,
    /** A local cover file, for shelf books only. Typed entries have no file and no cover. */
    val coverPath: String? = null,
    /** Whether removing the mark means unliking a book or deleting a typed line. */
    val onShelf: Boolean
)
