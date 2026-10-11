package com.lexiread.app.data.repository

import com.lexiread.app.data.api.FantLabApiService
import com.lexiread.app.data.api.WikipediaApiService
import com.lexiread.app.data.db.SynopsisCacheDao
import com.lexiread.app.data.model.FantLabMatch
import com.lexiread.app.data.model.SynopsisCache
import com.lexiread.app.data.model.WikipediaArticle
import com.lexiread.app.domain.discover.A_BOOK
import com.lexiread.app.domain.discover.DiscoveredBook
import com.lexiread.app.domain.discover.NOT_A_BOOK
import com.lexiread.app.domain.discover.asPrinted
import com.lexiread.app.domain.discover.describesABook
import com.lexiread.app.domain.discover.hasCyrillic
import com.lexiread.app.domain.discover.inReadersLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a suggested book is about, for the card the reader taps.
 *
 * The suggestion list can say who wrote a book and why it was picked, and neither of those is a
 * reason to read it. This is: what actually happens in it, written the way it is written on the
 * back of a paperback.
 *
 * Three sources, in the order a reader would want them.
 *
 * The catalogue's own blurb first, where there is one: it is already the back cover and costs no
 * request at all. Most Russian records of the classics have nothing — nineteen of the twenty
 * Google editions of «Анна Каренина» carry an empty description — so the other two exist.
 *
 * Fantlab second. Its `work_description` is the publisher's copy off the back of the Russian
 * edition, which is the thing being asked for, and it had all twenty of the books tried against
 * it, classics included. Two requests, about 27 KB.
 *
 * Wikipedia last, and not because it knows less. The lead of an article is a publication record
 * — «роман Фёдора Достоевского, впервые опубликованный в журнале „Русский вестник“ в двенадцати
 * выпусках» — accurate, and no reason to read anything; the section headed «Сюжет» is the part
 * that opens with «Действие романа начинается жарким июльским днём в Петербурге», and it
 * summarises the whole book, ending included. Only the opening of it is shown ([trim]). Reading
 * a section costs two more requests, because a section can only be asked for by number and every
 * article numbers its own differently.
 *
 * And the writer last of all, when the book itself cannot be found under either of the names the
 * catalogue holds for it — see [aboutTheAuthor]. That is not an answer to what was asked and the
 * sheet does not pretend it is; it is what stands in place of «ничего не нашлось», which is what a
 * misspelled title and a mis-catalogued record used to come to.
 *
 * So: five requests per tap in the ordinary case and about fifteen in the worst one — a record
 * nothing recognises, tried under both names and then by author. One book at a time, only when
 * the reader opens a card, and the answer kept for the session. Reopening a card costs nothing.
 *
 * The hard part is not fetching. It is that the title on a catalogue record is not the title of
 * the book — «Т. 1. Анна Каренина», «Толстой Л. Анна Каренина», «Анна Каренина. Роман в восьми
 * частях» — and neither search engine finds anything under those (see [asSearched]); and that a
 * name belongs to more than a book. «Мы» is a novel, two films and a song; «Бесы» is a novel, a
 * film and a play. See [looksLikeBook] and [worthReading].
 */
class BookSynopsisRepository(
    private val api: WikipediaApiService,
    private val fantLab: FantLabApiService,
    /**
     * Where an answer is kept between runs of the app. Optional so that the repository can still
     * be built without a database behind it — in a test, or by anything that only wants the
     * lookup.
     */
    private val store: SynopsisCacheDao? = null
) {

    /**
     * @param text     what happens in the book, trimmed to something readable on a phone.
     * @param credit   who is telling the reader this, as a whole line — blank for the catalogue's
     *                 own blurb, which is the book's own words and credits nobody.
     * @param coverUrl a Russian edition's cover or the article's own picture, for the many
     *                 suggestions that reach the screen with no cover at all — Wikidata carries
     *                 none, and that is where the genre-matched half of the list comes from.
     * @param aboutAuthor set when [text] is about the writer rather than about the book, which is
     *                 the last answer before none at all. The sheet says so above it: this is not
     *                 what was asked for, and passing it off as the book's own description would
     *                 be worse than the empty sheet it replaces.
     */
    data class Synopsis(
        val text: String,
        val credit: String,
        val coverUrl: String? = null,
        val aboutAuthor: Boolean = false
    )

    /** Answers only — see [of] for why a failure is not one. */
    private val cache = mutableMapOf<String, Synopsis>()
    private val lock = Mutex()

    /**
     * The book's own blurb where the catalogue has a real one, and the other two sources where
     * it does not.
     *
     * A publisher's description is already the back cover, is written to interest a reader, and
     * needs no network at all, so it wins whenever there is enough of it to be worth reading —
     * [MIN_USABLE_BLURB]. Most catalogue records have nothing, or three words of series
     * boilerplate, and those are the ones the rest of this is for.
     *
     * The short blurb is not thrown away: it is the last answer, after both searches have come
     * back with nothing, because two lines of series copy still beat an empty sheet.
     */
    suspend fun of(book: DiscoveredBook): Synopsis? {
        // Only if it is in Russian. Google hands out a Danish edition's blurb for «Метро 2035»
        // and an English one for half the translated fiction, and a blurb the reader cannot read
        // is worse than none: it fills the sheet and stops the two sources that would have found
        // the Russian one. Filtered at the source as well — this is the last gate before it is
        // shown, and the catalogues are not the only thing that ever feeds it.
        val blurb = book.description.trim().takeIf { inReadersLanguage(it) && !com.lexiread.app.domain.discover.looksLikeOpeningQuote(it) }.orEmpty()
        if (blurb.length >= MIN_USABLE_BLURB) return Synopsis(trim(blurb), credit = "")

        lock.withLock { cache[book.id]?.let { return it } }
        // The database next, and into memory with it. The suggestion list is built from the
        // reader's own shelf and changes only when they mark another book, so the same dozen
        // cards come back day after day; without this, every one of them was five requests again
        // on every launch.
        stored(book.id)?.let { remembered ->
            lock.withLock { cache[book.id] = remembered }
            return remembered
        }

        // Both names the catalogue holds, in turn. The second costs two more requests and is only
        // ever reached when the first found nothing at all, which is where a record whose Russian
        // title is misspelled — «Акадимия и Империя» — and one filed only under its original name
        // both end up. Neither search engine forgives a typo, and the other name has none.
        val titles = listOfNotNull(book.title, book.alternateTitle)
            .map { asSearched(it) }.filter { it.isNotBlank() }.distinct()
        val found = withContext(Dispatchers.IO) {
            titles.firstNotNullOfOrNull { title -> coroutineScope {
                val catalogue = async { withTimeoutOrNull(1_500) { fromFantLab(book, title) } }
                val encyclopedia = async { withTimeoutOrNull(1_500) { fromWikipedia(book, title) } }
                catalogue.await() ?: encyclopedia.await()
            } }
                ?: blurb.takeIf { it.isNotBlank() }?.let { Synopsis(trim(it), credit = "") }
                ?: withTimeoutOrNull(1_000) { aboutTheAuthor(book) }
        }

        // Only an answer is kept. A failure here is as often a dropped request as a book nobody
        // has written about — every one of the five calls swallows its exception and returns
        // nothing — and remembering that would turn one lost packet into «ничего не нашлось» for
        // the rest of the session, with no way for the reader to ask again. Closing the sheet and
        // opening it once more is exactly how someone reacts to an empty one, and it should cost
        // them a retry rather than the same empty sheet instantly.
        if (found != null) {
            lock.withLock {
                // Cleared wholesale rather than evicted one by one: this holds a session's worth
                // of taps, and the cap is there to bound it, not to be tuned.
                if (cache.size >= MAX_CACHED) cache.clear()
                cache[book.id] = found
            }
            remember(book.id, found)
        }
        return found
    }

    /**
     * What was found for this book on some earlier run, if it is still fresh enough to show.
     *
     * Anything the database cannot do is nothing: a cache that fails to answer costs the requests
     * it was meant to save, and there is no reason for that to reach the reader as an error.
     */
    private suspend fun stored(bookId: String): Synopsis? = withContext(Dispatchers.IO) {
        val dao = store ?: return@withContext null
        val row = try {
            dao.evictOld(System.currentTimeMillis() - KEPT_FOR_MS)
            dao.get(bookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withContext null

        if (!row.aboutAuthor && com.lexiread.app.domain.discover.looksLikeOpeningQuote(row.text)) return@withContext null
        Synopsis(
            text        = row.text,
            credit      = row.credit,
            coverUrl    = row.coverUrl,
            aboutAuthor = row.aboutAuthor
        )
    }

    private suspend fun remember(bookId: String, synopsis: Synopsis) {
        val dao = store ?: return
        withContext(Dispatchers.IO) {
            try {
                dao.insert(
                    SynopsisCache(
                        bookId      = bookId,
                        text        = synopsis.text,
                        credit      = synopsis.credit,
                        coverUrl    = synopsis.coverUrl,
                        aboutAuthor = synopsis.aboutAuthor
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Written on a best-effort basis. The answer is already in memory and on screen.
            }
        }
    }

    private suspend fun fromWikipedia(book: DiscoveredBook, title: String): Synopsis? {
        val article = findArticle(book, title) ?: return null
        // The plot where the article has one, the lead where it does not. Short stories and
        // recent novels often have nothing but a lead, and a publication record is still an
        // answer — it is only a worse one.
        val text = plotOf(article.pageid)?.takeUnless { com.lexiread.app.domain.discover.looksLikeOpeningQuote(it) }
            ?: trim(article.extract.orEmpty())
        if (text.isBlank()) return null
        return Synopsis(
            text     = text,
            credit   = "По статье «${article.title.orEmpty()}» в Википедии",
            coverUrl = article.thumbnail?.source
        )
    }

    // ── The blurb off the back cover ──────────────────────────────────────────

    /**
     * Fantlab's description of the work, where it has one.
     *
     * Two requests: find the work, then read it. The search answers with everything carrying the
     * words — asked for «Улитка на склоне» it offers a lecture about the novella above the
     * novella — so what comes back is sorted rather than taken.
     *
     * Three tiers, and the author is the strong one. Six writers have something called «Анна
     * Каренина» on the site, one of whom is Tolstoy; five have an «Отцы и дети». Where the
     * suggestion carries no Russian author at all — a genre match out of Wikidata sometimes does
     * not — the title alone decides, and only then.
     */
    private suspend fun fromFantLab(book: DiscoveredBook, title: String): Synopsis? {
        if (title.isBlank()) return null

        val matches = try {
            fantLab.searchWorks(worksUrl(title))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val work = worthReading(matches, title, book.authors) ?: return null

        val found = try {
            fantLab.getWork(workUrl(work.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        val text = trim(plain(found?.description.orEmpty()))
        // Many works are on the site with no description at all. Wikipedia is the better answer
        // then, and it has not been asked yet.
        if (text.length < MIN_PLOT_CHARS) return null
        return Synopsis(
            text     = text,
            credit   = "Аннотация с Фантлаба",
            coverUrl = found?.image?.trim()?.takeIf { it.startsWith("/") }?.let { "$COVERS$it" }
        )
    }

    // ── When there is nothing to be had about the book ────────────────────────

    /**
     * Who wrote it, when nothing at all can be found about what they wrote.
     *
     * Some records cannot be looked up by anybody. Open Library files «Академия и Империя» as
     * «Акадимия и Империя», one letter out, and no search engine forgives a typo; it files the
     * Foundation trilogy under an omnibus title no encyclopaedia has an entry for; and it files
     * a good deal that is not the book it says it is — Frank Herbert has three records of
     * Shakespeare's sonnets on his shelf. Those used to end at «ничего не нашлось», which tells
     * the reader nothing and leaves the sheet blank.
     *
     * The writer, on the other hand, can always be found: they are the reason the book was
     * suggested in the first place, so they are somebody the reader already reads. Three or four
     * sentences about them is not what was asked for, and the sheet says as much above it
     * ([Synopsis.aboutAuthor]) — but it is something true about the book in front of them, which
     * an empty sheet is not.
     *
     * Fantlab first: its note is short, written about the work rather than the life, and its
     * author search answers to a Latin name as readily as to a Russian one, which the catalogues
     * make necessary. Wikipedia's lead is the fallback and is longer than it needs to be.
     */
    private suspend fun aboutTheAuthor(book: DiscoveredBook): Synopsis? {
        // More than one name, because the first is not always a person anyone has heard of. A
        // catalogue record can be credited to a translator, an illustrator or a series editor
        // ahead of the writer — Open Library holds Ovid's «Metamorfosi» with two hundred names on
        // it, in no order at all. Russian first, then whatever else is there; two are enough to
        // get past a bad first name without turning a failure into four requests.
        val names = (book.authors.filter { hasCyrillic(it) } + book.authors)
            .map { it.trim() }.filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(NAMES_TRIED)

        return names.firstNotNullOfOrNull { name ->
            fromFantLabAuthor(name) ?: fromWikipediaAuthor(name)
        }
    }

    /**
     * Fantlab's note about the writer.
     *
     * Two requests. The hit is checked against the name asked for rather than taken: the search
     * ranks by weight, and a name it does not know answers with whoever is nearest — asked about
     * an author it has never heard of it should find nothing, not somebody else. The site holds
     * every spelling of a name in one string, so the check works whichever one the catalogue gave.
     */
    private suspend fun fromFantLabAuthor(name: String): Synopsis? {
        val wanted = longestWord(name)
        if (wanted.length < MIN_SURNAME) return null

        val matches = try {
            fantLab.searchAuthors(authorsUrl(name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val match = matches.firstOrNull { fold(it.fullname.orEmpty()).contains(wanted) } ?: return null

        val author = try {
            fantLab.getAuthor(authorUrl(match.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }

        val text = trim(plain(author.anons.orEmpty()))
        if (text.length < MIN_PLOT_CHARS || !inReadersLanguage(text)) return null
        return Synopsis(text, credit = "Об авторе, с Фантлаба", aboutAuthor = true)
    }

    /**
     * The Russian Wikipedia article about the writer.
     *
     * Searched for by name and not by `intitle:`, because the article is titled «Азимов, Айзек»
     * and nobody is called that. Which means the hit has to be checked, and for a foreign writer
     * it cannot be checked against the title — the title is in Russian and the catalogue gave a
     * Latin name. The lead can be: a Russian article about a foreign writer opens with the
     * original spelling in brackets, «А́йзек Ази́мов (англ. Isaac Asimov…)».
     */
    private suspend fun fromWikipediaAuthor(name: String): Synopsis? {
        val printed = asPrinted(name)
        val wanted = longestWord(printed)
        if (wanted.length < MIN_SURNAME) return null

        val page = fetch(personUrl(printed)).firstOrNull { article ->
            fold(article.title.orEmpty()).contains(wanted) ||
                fold(article.extract.orEmpty().take(LEAD_CHARS)).contains(wanted)
        } ?: return null

        val text = trim(page.extract.orEmpty())
        if (text.length < MIN_PLOT_CHARS || !inReadersLanguage(text)) return null
        return Synopsis(
            text        = text,
            credit      = "Об авторе, по статье «${page.title.orEmpty()}» в Википедии",
            aboutAuthor = true
        )
    }

    /**
     * The surname, near enough, in whichever alphabet the name arrived in.
     *
     * The longest word of a personal name is its surname in every arrangement of one, Russian or
     * not: «Ф. М. Достоевский», «Достоевский Фёдор», «Philip K. Dick». Unlike [surnameStem] it is
     * not cut short — here it is matched inside a string that holds the name in full, so there is
     * no spelling of an ending to survive.
     *
     * The last of the longest where two are the same length: «Arthur C. Clarke» is six letters at
     * either end, and taking the first asks the site about somebody called Arthur.
     */
    private fun longestWord(name: String): String =
        fold(name).split(SPACES).fold("") { best, word -> if (word.length >= best.length) word else best }

    /**
     * The work among the search hits that is the book, or nothing.
     *
     * The form filter comes first and is an allowed list rather than a banned one: the site
     * distinguishes some forty of them, and «статья», «эссе», «рецензия», «стихотворение» and
     * «интервью» are all things written *about* books that come back when a book is searched for.
     */
    private fun worthReading(
        matches: List<FantLabMatch>,
        title: String,
        authors: List<String>
    ): FantLabMatch? {
        val works = matches.filter { match ->
            val form = match.form.orEmpty().trim().lowercase()
            PROSE_FORMS.any { form.startsWith(it) }
        }
        if (works.isEmpty()) return null

        val stem = surnameStem(authors)
        val wanted = fold(title)

        // The record's name is every name the work has ever had, run together — «Зелёная миля
        // The Green Mile», «Солярис Solaris Соларис Планеты разумные» — and the Russian one is
        // always first.
        //
        // So where the catalogue gave a Russian title it has to be at the front, and where it
        // gave the original — Open Library catalogues a great many Russian editions under their
        // English or Polish name, «The Robots of Dawn», «Astronauci» — it can only ever be found
        // further in. Searched for as a whole and only from [MIN_ORIGINAL_TITLE] characters up,
        // so a one-word name cannot land inside somebody else's list of alternative titles.
        fun named(match: FantLabMatch): Boolean {
            val full = fold(match.fullname.orEmpty())
            return if (hasCyrillic(wanted)) full.startsWith(wanted)
            else wanted.length >= MIN_ORIGINAL_TITLE && full.contains(wanted)
        }
        fun written(match: FantLabMatch) = stem.isNotEmpty() &&
            fold(match.authors.orEmpty().ifBlank { match.author.orEmpty() }).contains(stem)

        return works.firstOrNull { named(it) && written(it) }
            ?: works.firstOrNull { written(it) }
            ?: works.firstOrNull { named(it) }
    }

    /**
     * The reader's author, cut short enough to survive somebody else's spelling of the name.
     *
     * Fantlab credits «Улитка на склоне» to «Аркадий и Борис Стругацкие» and the shelf says
     * «Аркадий Стругацкий»: the two agree on «стругацк» and on nothing longer. The longest word
     * of a Russian name is its surname in every arrangement of one — «Ф. М. Достоевский»,
     * «Достоевский Фёдор», «Фёдор Михайлович Достоевский».
     */
    private fun surnameStem(authors: List<String>): String {
        val name = authors.firstOrNull { hasCyrillic(it) } ?: return ""
        val surname = name.split(SPACES).fold("") { best, word -> if (word.length >= best.length) word else best }
        val folded = fold(surname)
        return if (folded.length >= STEM_FROM) folded.dropLast(2) else folded
    }

    /** Fantlab's copy is plain text with the occasional tag left in it by an editor. */
    private fun plain(text: String): String =
        unescape(text.replace(BREAK, "\n").replace(TAG, ""))
            .split('\n')
            .joinToString("\n") { line -> line.replace(SPACES, " ").trim() }
            .trim()

    // ── Finding the right article ─────────────────────────────────────────────

    /**
     * The article about this book, or nothing.
     *
     * Three ways in, cheapest and surest first.
     *
     * A Wikidata suggestion arrives with the article's own address attached, and there is nothing
     * left to decide. Everything else is searched for, and there the author is the difference
     * between an answer and none — but only in the exact form an article would use.
     *
     * Two forms fail. Google's transliteration: it files «Преступление и наказание» under *Fyodor
     * Dostoyevsky*, that string appears nowhere in the Russian article, and the search ANDs its
     * terms, so asking for the two together returned zero results for one of the most
     * written-about novels there is. And the catalogue's full legal name: nothing is written
     * about «Мы» by «Евгений Иванович Замятин», because the article calls him Евгений Замятин —
     * hence [asPrinted], the same two words the card shows.
     *
     * The fallback drops the author entirely, and then only a title match counts. Without a name
     * to narrow it, `intitle:"Мы"` answers with the anthem of Belarus.
     *
     * @param title the book's name as [asSearched] left it, not as the catalogue wrote it.
     */
    private suspend fun findArticle(book: DiscoveredBook, title: String): WikipediaArticle? {
        articleTitle(book.infoLink)?.let { known ->
            // Named rather than searched for: Wikidata already decided this is the book.
            fetch(titlesUrl(known)).firstOrNull()?.let { return it }
        }

        val author = book.authors.firstOrNull { hasCyrillic(it) }?.let { asPrinted(it) }.orEmpty()
        if (author.isNotBlank()) {
            pick(fetch(searchUrl(title, author)), title, byTitleOnly = false)
                ?.let { return it }
        }
        return pick(fetch(searchUrl(title, "")), title, byTitleOnly = true)
    }

    /**
     * The book's own article out of what the search offered, exact name first.
     *
     * The exact tier is not a nicety. Asked about «Атлант расправил плечи», the search answers
     * with «Атлант расправил плечи: Часть 1» above the novel, and that is the 2011 film — its
     * lead calls itself a «киноадаптация одноимённого романа», which is a sentence about a book
     * with the word for one in it. Matching the title as written settles that before any of the
     * guessing has to.
     */
    private fun pick(
        pages: List<WikipediaArticle>,
        title: String,
        byTitleOnly: Boolean
    ): WikipediaArticle? {
        if (pages.isEmpty()) return null
        val books = pages.filter(::looksLikeBook)
        val wanted = fold(title)
        return books.firstOrNull { fold(it.title.orEmpty()) == wanted }
            ?: books.firstOrNull { startsWithTitle(it, title) }
            ?: books.firstOrNull().takeIf { !byTitleOnly }
    }

    private suspend fun fetch(url: String): List<WikipediaArticle> =
        try {
            api.getArticles(url).query?.pages.orEmpty().values
                .filter { !it.title.isNullOrBlank() && !it.extract.isNullOrBlank() }
                .sortedBy { it.index }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Whether the article is about the book rather than about the film of the book.
     *
     * Two tests, cheapest first. The disambiguator in the title — «Солярис (роман)», «Бесы (фильм,
     * 1992)» — settles it outright where there is one. Where there is not, the lead sentence says
     * what the thing is before it says anything else, so whichever of the two vocabularies appears
     * first is the answer: «"Бесы" — кинофильм по мотивам одноимённого романа» is a film, and
     * «"Возвращение со звёзд" — научно-фантастический роман» is not.
     *
     * Position rather than presence, because an article about a novel will mention its
     * adaptations and an article about an adaptation will mention the novel. Both always contain
     * both words; only the order is reliable.
     */
    private fun looksLikeBook(page: WikipediaArticle): Boolean {
        // Asked of MediaWiki rather than guessed at. A disambiguation page is titled exactly like
        // the book and opens «„Зелёная миля“ — роман Стивена Кинга», which passes every test
        // below — and it beat the novel's own article, because the novel's is «Зелёная миля
        // (роман)» and the exact title belongs to the list.
        if (page.pageprops?.disambiguation != null) return false

        val bracket = BRACKET.find(page.title.orEmpty())?.groupValues?.get(1).orEmpty()
        if (bracket.isNotBlank()) {
            if (NOT_A_BOOK.containsMatchIn(bracket)) return false
            if (A_BOOK.containsMatchIn(bracket)) return true
        }
        return describesABook(page.extract.orEmpty().take(LEAD_CHARS))
    }

    /** «Мы (роман)» over «История создания романа «Мы»», where the search offered both. */
    private fun startsWithTitle(page: WikipediaArticle, title: String): Boolean =
        fold(page.title.orEmpty()).startsWith(fold(title))

    /**
     * A name reduced to what two catalogues can be expected to agree on.
     *
     * Case, ё, and every mark that is not a letter or a digit: the quotes a title is printed in,
     * and the punctuation inside it. Open Library holds «Иов, или, Осмеяние справедливости» with
     * a comma too many and «Смерт - дело одинокое» with a hyphen where a dash belongs; Fantlab
     * holds both without. Dropping the punctuation entirely rather than tidying it is the only
     * rule that does not need to know which of the two is right.
     */
    private fun fold(text: String): String =
        text.lowercase().replace('ё', 'е').replace(NOT_A_LETTER, " ").replace(SPACES, " ").trim()

    /**
     * The article name out of a Wikidata suggestion's link, which is the one case where the right
     * article is already known and does not have to be searched for.
     */
    private fun articleTitle(link: String?): String? {
        if (link == null || !link.startsWith(RU_WIKIPEDIA)) return null
        val encoded = link.removePrefix(RU_WIKIPEDIA).substringBefore('#')
        if (encoded.isBlank()) return null
        return try {
            // `+` is a literal in a Wikipedia path and a space to the decoder, so it is escaped
            // rather than handed over.
            URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8").replace('_', ' ')
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    // ── Reading the plot out of it ────────────────────────────────────────────

    /**
     * The plot section, in the reader's language and nobody else's.
     *
     * The table of contents first, because the section number is not predictable — «Сюжет» is
     * section 1 of «Мы» and section 5 of «Преступление и наказание» — and then that section
     * alone. Whole articles were measured at between 87 and 731 KB; a table of contents is 4 to
     * 24 KB and one section is 24 to 77 KB, which is the difference between a sheet that opens
     * and one that times out.
     *
     * Only paragraphs are kept from what comes back. The section's HTML also carries the
     * illustration captions, the nested contents list and the per-heading edit links, and every
     * one of those is a line of debris in the middle of a sentence — while the prose is, without
     * exception, in `<p>`.
     */
    private suspend fun plotOf(pageId: Int): String? {
        if (pageId <= 0) return null

        val sections = try {
            api.getParse(sectionsUrl(pageId)).parse?.sections.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }

        // In order of preference rather than in the order the article happens to list them: an
        // article carrying both «Аннотация» and «Сюжет» should answer with the second.
        val index = PLOT_HEADINGS.firstNotNullOfOrNull { wanted ->
            sections.firstOrNull { section ->
                section.line.orEmpty().trim().lowercase().startsWith(wanted)
            }?.index
        }?.takeIf { it.isNotBlank() } ?: return null

        val html = try {
            api.getParse(sectionUrl(pageId, index)).parse?.text?.html
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null

        val plot = trim(paragraphs(html).joinToString("\n"))
        // A one-line stub is not worth having gone and got. The lead is the better answer then.
        return plot.takeIf { it.length >= MIN_PLOT_CHARS }
    }

    /** The prose of a rendered section, in order, as plain text. */
    private fun paragraphs(html: String): List<String> =
        PARAGRAPH.findAll(html).map { match ->
            match.groupValues[1]
                // Reference markers and the styling the templates carry with them. Both sit
                // inside the paragraph, so they cannot be dropped along with the block tags.
                .replace(SUPERSCRIPT, "")
                .replace(STYLE, "")
                .replace(TAG, "")
                .let { stripped -> unescape(stripped) }
                .replace(FOOTNOTE, "")
                .replace(SPACES, " ")
                .trim()
        }.filter { it.isNotBlank() }.toList()

    private fun unescape(text: String): String = text
        .replace("&#160;", " ").replace("&nbsp;", " ")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        // Last, so that an escaped ampersand cannot turn the text after it into another entity.
        .replace("&amp;", "&")

    // ── The title the catalogue printed, and the one to search for ────────────

    /**
     * A catalogue record's title, reduced to the name of the book.
     *
     * This is why «Анна Каренина» had nothing to show. The record is titled «Т. 1. Анна
     * Каренина», or «Толстой Л. Анна Каренина», or «Анна Каренина. Роман в восьми частях», or
     * «Сочиненія Л.Н. Толстого: Анна Каренина : I» — and `intitle:"Т. 1. Анна Каренина"` matches
     * no article and `search-works?q=` matches no work, so the sheet reported, correctly and
     * uselessly, that nothing could be found about one of the most written-about novels there is.
     *
     * Four things come off: a bracketed tail («Обломов (Oblomov)»), a volume number, an author
     * printed in front of the title in either arrangement, and a subtitle.
     *
     * The subtitle is the one that needs a rule, because a subtitle and a second title look
     * alike. The tail goes when it begins in lower case («роман в шести частях»), when it names a
     * part of a book or writing about one («Том 4», «Краткое содержание»), or when it is a bare
     * numeral. Otherwise it stays: «Метро 2033: Питер» and «12 стульев. Золотой телёнок» are
     * titles, both halves of them. Where it is the head that names a collection instead
     * («Собрание сочинений в 8 томах. Том 3. Солярис»), the head is what goes.
     *
     * Three passes, because one exposes the next: the author has to be gone before the subtitle
     * rule can see past «Л. Н. Толстой «Война и мир». Краткое содержание». If nothing at all is
     * left, the record was a volume of collected works with no title of its own, and the original
     * goes to the search unchanged.
     */
    private fun asSearched(title: String): String {
        var text = title.trim()
        for (pass in 1..CLEANING_PASSES) {
            val before = text

            text = edges(TRAILING_BRACKET.replace(text, ""))
            for (separator in SEPARATORS) {
                val at = text.indexOf(separator)
                if (at <= 0) continue
                val head = text.substring(0, at).trim()
                val tail = text.substring(at + separator.length).trim()
                if (tail.isEmpty()) continue
                text = when {
                    COLLECTION.containsMatchIn(head) -> tail
                    tail.first().isLowerCase() -> head
                    APPARATUS.containsMatchIn(tail) -> head
                    NUMERAL.matches(tail) -> head
                    else -> text
                }
            }
            text = VOLUME.replace(text, "")
            text = INITIALS_FIRST.replace(text, "")
            text = edges(SURNAME_FIRST.replace(text, ""))

            if (text == before) break
        }
        return text.ifBlank { title.trim() }
    }

    /** Quotes and punctuation left hanging on either end by whatever was cut away. */
    private fun edges(text: String): String = text.trim().trim(*EDGE_CHARS).trim()

    // ── URLs ──────────────────────────────────────────────────────────────────

    /** `onlymatches=1` drops the site's own scoring blocks and leaves the list. */
    private fun worksUrl(title: String): String =
        "$FANTLAB/search-works?q=${encode(title)}&page=1&onlymatches=1"

    private fun workUrl(id: Int): String = "$FANTLAB/work/$id"

    private fun authorsUrl(name: String): String =
        "$FANTLAB/search-autors?q=${encode(name)}&page=1&onlymatches=1"

    private fun authorUrl(id: Int): String = "$FANTLAB/autor/$id"

    /** A person, not a title: «Азимов, Айзек» is what the article is called and nobody's name. */
    private fun personUrl(name: String): String =
        "$ACTION?action=query&generator=search&gsrnamespace=0" +
            "&gsrlimit=$CANDIDATES&gsrsearch=${encode(name)}$LEAD_FIELDS"

    private fun searchUrl(title: String, author: String): String {
        // `intitle:` rather than a free search: the title is what the article is named after, and
        // a free search for a famous novel answers with the writer's own biography first.
        val query = "intitle:\"${title.replace('"', ' ')}\" $author".trim()
        return "$ACTION?action=query&generator=search&gsrnamespace=0" +
            "&gsrlimit=$CANDIDATES&gsrsearch=${encode(query)}$LEAD_FIELDS"
    }

    private fun titlesUrl(title: String): String =
        "$ACTION?action=query&titles=${encode(title)}$LEAD_FIELDS"

    private fun sectionsUrl(pageId: Int): String =
        "$ACTION?action=parse&format=json&prop=sections&pageid=$pageId"

    private fun sectionUrl(pageId: Int, index: String): String =
        "$ACTION?action=parse&format=json&prop=text&pageid=$pageId" +
            "&section=${encode(index)}&disabletoc=1&disableeditsection=1"

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Cut to what someone will actually read on a phone.
     *
     * Whole paragraphs while they fit, because a paragraph is a thought and half of one is not;
     * the first one regardless, cut at a sentence if it alone is longer than the budget. Stopping
     * early is also the only spoiler control there is — a plot section is written to summarise
     * the whole book, and the opening of one is the premise, which is all a back cover ever gives.
     */
    private fun trim(text: String): String {
        val paragraphs = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return ""

        val kept = StringBuilder()
        for (paragraph in paragraphs) {
            if (kept.isNotEmpty() && kept.length + paragraph.length > MAX_CHARS) break
            if (kept.isNotEmpty()) kept.append("\n\n")
            kept.append(paragraph)
        }
        if (kept.length <= MAX_CHARS) return kept.toString()

        val cut = kept.substring(0, MAX_CHARS)
        val end = cut.lastIndexOfAny(SENTENCE_ENDS)
        return if (end > MIN_CHARS) cut.substring(0, end + 1) else "${cut.trimEnd()}…"
    }

    private companion object {
        const val RU_WIKIPEDIA = "https://ru.wikipedia.org/wiki/"
        const val ACTION = "https://ru.wikipedia.org/w/api.php"
        const val FANTLAB = "https://api.fantlab.ru"

        /** Covers live on their own host, and the API returns the path without it. */
        const val COVERS = "https://data.fantlab.ru"

        /**
         * The forms that are a book. Everything else the site files under a name — «статья»,
         * «эссе», «рецензия», «стихотворение», «интервью», «антология критики» — is writing
         * about one. Matched as prefixes: «роман-эпопея» is how «Война и мир» is filed.
         */
        val PROSE_FORMS = listOf(
            "роман", "повест", "рассказ", "сборник", "цикл", "поэма", "пьес", "сказк",
            "новелл", "антолог"
        )

        /** Below this a surname is too short to cut down without matching half the alphabet. */
        const val STEM_FROM = 6

        val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

        /** How many times [asSearched] goes over a title before deciding it is as clean as it gets. */
        const val CLEANING_PASSES = 3

        /** «Обломов (Oblomov)», «Улитка на склоне столетия (сборник)». */
        val TRAILING_BRACKET = Regex("\\s*[(\\[][^)\\]]*[)\\]]\\s*$")

        /** «Т. 1. Анна Каренина», «Война и мир. Том III-IV». */
        val VOLUME = Regex(
            "^\\s*(т\\.|том|кн\\.|книга|ч\\.|часть|вып\\.|выпуск)\\s*[\\dIVXivx]+\\s*[.:,]?\\s*",
            RegexOption.IGNORE_CASE
        )

        /** «И. С. Тургенев Отцы и дети». */
        val INITIALS_FIRST = Regex("^(\\p{Lu}\\.\\s*){1,3}\\p{Lu}[\\p{Ll}-]+\\s+")

        /** «Толстой Л. Анна Каренина». */
        val SURNAME_FIRST = Regex("^\\p{Lu}[\\p{Ll}-]+(\\s+\\p{Lu}\\.){1,3}\\s*")

        /** Where a subtitle is cut from a title, in the order they are looked for. */
        val SEPARATORS = listOf(": ", ":", ". ", "; ")

        /** A part of a book, or writing about one — never the second half of a title. */
        val APPARATUS = Regex(
            "^(роман|повест|рассказ|сборник|избранн|стихотворен|поэма|пьес|сказк|новелл|" +
                "книга|том|част|кн\\.|т\\.|краткое содержание|содержание|анализ|критик|" +
                "сочинени|хрестомат|коммент|учебн|для школ)",
            RegexOption.IGNORE_CASE
        )

        /** «Собрание сочинений в 8 томах. Том 3. Солярис» — here the book is the tail. */
        val COLLECTION = Regex(
            "сочинен|собран|избранн|библиотек|антолог|альманах|хрестомат",
            RegexOption.IGNORE_CASE
        )

        /** «Анна Каренина : I» — a volume number with the word for volume left off. */
        val NUMERAL = Regex("^[IVXLC\\d]{1,5}[.,]?$")

        val EDGE_CHARS = charArrayOf(
            '«', '»', '"', '„', '“', ' ', '.', ',', ':', ';', '-', '—', '/'
        )

        /** How many search hits are read before deciding none of them is the book. */
        const val CANDIDATES = 3

        /** Lead sections, as plain text, with the article's own picture and what kind of page it is. */
        const val LEAD_FIELDS =
            "&format=json&redirects=1&prop=extracts%7Cpageimages%7Cpageprops" +
                "&ppprop=disambiguation" +
                "&explaintext=1&exintro=1&exlimit=$CANDIDATES" +
                "&piprop=thumbnail&pithumbsize=320&pilimit=$CANDIDATES"

        /**
         * Below this a catalogue description is a stub — a series name, a print run, «Впервые на
         * русском» — and the article is worth the request.
         */
        const val MIN_USABLE_BLURB = 200

        /**
         * Below this a title in the original language is too short to look for inside Fantlab's
         * run-together list of a work's names. «Maska» and «Valis» are titles; they are also four
         * and five letters that turn up inside other people's alternative names.
         */
        const val MIN_ORIGINAL_TITLE = 8

        /** About the length of a back cover. */
        const val MAX_CHARS = 1100
        const val MIN_CHARS = 300
        const val LEAD_CHARS = 300
        const val MIN_PLOT_CHARS = 120
        const val MAX_CACHED = 60

        /**
         * How long a stored answer is worth showing before it is looked up again.
         *
         * A month. A blurb does not change, but what could be found does: Fantlab's editors fill
         * in the works that had none, and a card that once came back with the author's biography
         * deserves another try at the book itself rather than that fallback for ever.
         */
        const val KEPT_FOR_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * Headings that hold the story, in the order they are worth having. Matched as prefixes:
         * an article may head the section «Сюжет романа» or «Краткое содержание романа».
         */
        val PLOT_HEADINGS = listOf(
            "сюжет", "краткое содержание", "содержание", "фабула",
            "аннотация", "о книге", "описание"
        )

        val BRACKET = Regex("\\(([^)]+)\\)\\s*$")

        val PARAGRAPH = Regex("<p\\b[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE)
        val SUPERSCRIPT = Regex("<sup\\b[\\s\\S]*?</sup>", RegexOption.IGNORE_CASE)
        val STYLE = Regex("<style\\b[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
        val TAG = Regex("<[^>]+>")
        /** `[12]` — a reference marker that survived because its `<sup>` was written as text. */
        val FOOTNOTE = Regex("\\[\\d+]")
        val SPACES = Regex("\\s+")

        val SENTENCE_ENDS = charArrayOf('.', '!', '?', '…')

        /** Everything a title can be printed with rather than named with — see [fold]. */
        val NOT_A_LETTER = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Below this a surname is too short to identify anybody. «Ли» and «По» are surnames; they
         * are also two letters that turn up inside every second name on the site.
         */
        const val MIN_SURNAME = 4

        /** How many of a record's names are asked about before giving up on it — see [aboutTheAuthor]. */
        const val NAMES_TRIED = 2
    }
}
