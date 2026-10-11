package com.lexiread.app.data.repository.discovery

import com.lexiread.app.data.api.OpenLibraryApiService
import com.lexiread.app.data.model.OpenLibraryDoc
import com.lexiread.app.domain.discover.DiscoveredBook
import com.lexiread.app.domain.discover.LikedItem
import com.lexiread.app.domain.discover.TasteEntry
import com.lexiread.app.domain.discover.hasCyrillic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Open Library — the source of last resort, kept for its covers and its ratings.
 *
 * It catalogues editions rather than works, and a great many of the Russian ones it holds are
 * scans of pre-revolutionary printings, so its titles are the least presentable of the three:
 * «Бѣсы», «Записки изъ подполья», «Игрокъ». Google and Wikidata are both asked first and their
 * records win wherever the same book comes back from more than one of them
 * (see [com.lexiread.app.data.repository.BookDiscoveryRepository]).
 *
 * Its subject vocabulary is crowd-sourced and enormous: a single record for «Преступление и
 * наказание» lists sixty headings including "Theater programs" and "Habimah". Everything here
 * that looks like over-filtering is aimed at that.
 */
internal class OpenLibrarySource(private val api: OpenLibraryApiService) {

    /**
     * What the catalogue knows about one book the reader likes.
     *
     * By author first, by free text only as a fallback: `author=Достоевский` returns thousands
     * of hits with Russian titles, while `q=<Russian title>` mostly misses, because works are
     * filed under whichever language the cataloguer worked in.
     */
    suspend fun resolve(item: LikedItem, calls: CallLog): TasteEntry {
        val author = cleanForQuery(item.author, MAX_AUTHOR_QUERY_CHARS)
        val title = cleanForQuery(item.title, MAX_TITLE_QUERY_CHARS)
        val shelfAuthor = listOfNotNull(item.author.trim().takeIf { it.isNotBlank() })
        val unresolved = TasteEntry(item.title, shelfAuthor, emptyList())

        // Filtered the same way the suggestions are: the headings on somebody else's book are not
        // this reader's taste, and a shelf polluted here is a wrong profile for the whole run.
        var docs = if (author.isNotBlank()) {
            fetchDocs(authorLookupUrl(author), calls)
                .filter { doc -> worthShowing(doc.authorName.orEmpty(), author) }
        } else {
            emptyList()
        }
        if (docs.isEmpty() && title.isNotBlank()) docs = fetchDocs(textLookupUrl(title), calls)
        if (docs.isEmpty()) return unresolved

        val catalogueAuthors = docs.flatMap { it.authorName.orEmpty() }
            .map { it.trim() }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(MAX_CATALOGUE_AUTHORS).map { it.key }

        // A heading that appears on only one of the author's books describes that book, not
        // their work; one that appears on several is the closest thing here to a genre.
        val sourceDocs = docs.take(SUBJECT_SOURCE_DOCS)
        val needed = minOf(SUBJECT_MIN_DOCS, sourceDocs.size)
        val subjects = sourceDocs
            .flatMap { it.subject.orEmpty().map { subject -> subject.trim().lowercase() }.distinct() }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .filterValues { it >= needed }
            .entries.sortedByDescending { it.value }.map { it.key }

        return TasteEntry(
            likedTitle = item.title,
            authors    = (catalogueAuthors + shelfAuthor).distinctBy { it.lowercase() },
            categories = subjects
        )
    }

    /** Every author and subject query at once — they are independent and the wait is the cost. */
    suspend fun discover(
        authors: List<String>,
        subjects: List<String>,
        language: String?,
        calls: CallLog
    ): List<DiscoveredBook> = coroutineScope {
        val byAuthor = authors.map { name -> async { fetch(authorUrl(name, language), calls, name) } }
        val bySubject = subjects.map { async { fetch(subjectUrl(it, language), calls, asked = null) } }
        (byAuthor + bySubject).awaitAll().flatten().distinctBy { it.id }
    }

    private fun authorUrl(author: String, language: String?): String = buildString {
        append(SEARCH).append("?author=").append(encode(author))
        append("&limit=").append(PAGE_SIZE)
        // Popularity is a good tie-break inside one author's shelf and a disaster outside it:
        // applied to a subject it throws relevance away and answers every genre with Twilight.
        append("&sort=").append(POPULAR)
        appendLanguage(language)
        append("&fields=").append(FIELDS)
    }

    private fun subjectUrl(subject: String, language: String?): String = buildString {
        append(SEARCH).append("?subject=").append(encode(subject))
        append("&limit=").append(PAGE_SIZE)
        appendLanguage(language)
        append("&fields=").append(FIELDS)
    }

    private fun authorLookupUrl(author: String): String = buildString {
        append(SEARCH).append("?author=").append(encode(author))
        append("&limit=").append(LOOKUP_SIZE)
        append("&sort=").append(POPULAR)
        append("&fields=").append(FIELDS)
    }

    private fun textLookupUrl(title: String): String = buildString {
        append(SEARCH).append("?q=").append(encode(title))
        append("&limit=").append(LOOKUP_SIZE)
        append("&fields=").append(FIELDS)
    }

    private fun StringBuilder.appendLanguage(language: String?) {
        if (!language.isNullOrBlank()) append("&language=").append(language)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun fetch(url: String, calls: CallLog, asked: String?): List<DiscoveredBook> =
        fetchDocs(url, calls)
            .filter { doc -> worthShowing(doc.authorName.orEmpty(), asked) }
            .mapNotNull { it.toDiscovered() }

    /**
     * Whether the record is a book by the writer whose shelf it turned up on.
     *
     * Asked by author, this catalogue answers with a good deal that is not the author's: three
     * editions of Shakespeare's sonnets sit on Frank Herbert's shelf, and Ovid's «Metamorfosi»
     * sits on Arthur C. Clarke's, filed there by a John Clarke who edited one of its two hundred
     * and thirty-seven printings. Both used to reach the screen as suggestions.
     *
     * Two tests. A record credited to more names than any book has is a catalogue aggregate — a
     * work with every editor and translator of every edition heaped onto one entry. And the
     * writer asked for has to be among the names ([credited]), which is the precise test and
     * catches the sonnets.
     */
    private fun worthShowing(names: List<String>, asked: String?): Boolean {
        if (names.size > MAX_CREDITED) return false
        return asked.isNullOrBlank() || credited(names, asked)
    }

    /**
     * Whether one of the names on the record is the writer asked for.
     *
     * The surname has to match as a whole word and the given name has to not contradict it: John
     * Clarke is not Arthur C. Clarke, while «Ф. М. Достоевский» is «Фёдор Достоевский» — hence
     * the prefix comparison, which lets an initial stand for the name it abbreviates. A record
     * carrying the bare surname and nothing else is given the benefit of the doubt.
     *
     * Names are compared only against their own alphabet. Open Library credits «Преступление и
     * наказание» to *Fyodor Dostoyevsky* and the profile asks for «Фёдор Достоевский»; those two
     * strings have nothing in common, and reading that as evidence that the record is somebody
     * else's would throw out every Russian classic in the catalogue. Where there is nothing
     * comparable the record is kept: the catalogue matched it to this author itself, and a
     * spelling this code cannot follow is not a reason to disbelieve it.
     */
    private fun credited(names: List<String>, asked: String): Boolean {
        val wanted = words(asked)
        val surname = surnameOf(wanted)
        if (surname.length < MIN_SURNAME) return true

        val comparable = names.filter { hasCyrillic(it) == hasCyrillic(asked) }
        if (comparable.isEmpty()) return true

        val given = wanted.filterNot { it == surname }
        return comparable.any { name ->
            val parts = words(name)
            if (surname !in parts) return@any false
            val rest = parts.filterNot { it == surname }
            if (rest.isEmpty() || given.isEmpty()) return@any true
            rest.any { part -> given.any { part.startsWith(it) || it.startsWith(part) } }
        }
    }

    private fun words(name: String): List<String> =
        normalise(name).split(WHITESPACE).filter { it.isNotEmpty() }

    /**
     * The surname, near enough: the longest word of the name, and the last of them where two are
     * the same length. That tie is not hypothetical — «Arthur C. Clarke» is six letters either
     * end, and taking the first made *Arthur* the surname, which credited Ovid's Metamorphoses to
     * Clarke by way of an Arthur Golding.
     */
    private fun surnameOf(parts: List<String>): String =
        parts.fold("") { best, word -> if (word.length >= best.length) word else best }

    /**
     * A name reduced to letters, so that two catalogues' spellings of it can be compared.
     *
     * Accents come off — Open Library writes «Stanisław Lem» and the reader's shelf says
     * «Stanislaw Lem» — and the letters the decomposition cannot reach, the crossed and slashed
     * ones, are mapped by hand.
     */
    private fun normalise(name: String): String {
        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFD).replace(COMBINING, "")
        val plain = buildString(decomposed.length) {
            decomposed.forEach { append(CROSSED[it] ?: it) }
        }
        return plain.lowercase().replace('ё', 'е').replace(NOT_A_LETTER, " ").trim()
    }

    private suspend fun fetchDocs(url: String, calls: CallLog): List<OpenLibraryDoc> =
        try {
            val docs = api.search(url).docs.orEmpty()
            calls.succeeded()
            docs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One query failing is not the run failing: the others still have something to say.
            emptyList()
        }

    private fun OpenLibraryDoc.toDiscovered(): DiscoveredBook? {
        val workKey = key?.trim()?.takeIf { it.startsWith("/") } ?: return null
        val names = readableTitles()
        val bookTitle = names.firstOrNull() ?: return null
        return DiscoveredBook(
            id             = workKey,
            title          = bookTitle,
            alternateTitle = names.getOrNull(1),
            authors        = authorName.orEmpty().map { it.trim() }.filter { it.isNotBlank() },
            categories     = subject.orEmpty(),
            thumbnailUrl   = coverId?.takeIf { it > 0 }?.let { "$COVERS/b/id/$it-M.jpg" },
            publishedYear  = firstPublishYear?.takeIf { it in 1..LATEST_PLAUSIBLE_YEAR }?.toString(),
            pageCount      = pagesMedian?.takeIf { it > 0 },
            averageRating  = ratingsAverage,
            ratingsCount   = ratingsCount ?: 0,
            infoLink       = "$SITE$workKey"
        )
    }

    /**
     * The work is titled in whatever language it was catalogued in, while the edition attached
     * to it was filtered by the language asked for — so the Russian title of a foreign novel is
     * on the edition, not the work.
     *
     * A modern spelling is preferred over a pre-reform one where the record offers both: this
     * catalogue holds scans of nineteenth-century printings, and «Записки изъ подполья» is the
     * same book as «Записки из подполья» in a harder-to-read dress. Where the old spelling is
     * the only Russian one on the record, the record is dropped rather than shown: two better
     * catalogues are asked before this one, and «Бѣсы» on a suggestion card reads as a bug.
     *
     * Both names are kept, Russian first: the second is never printed, it is a second thing to
     * search the synopsis for when the first turns up nothing — see
     * [com.lexiread.app.domain.discover.DiscoveredBook.alternateTitle].
     */
    private fun OpenLibraryDoc.readableTitles(): List<String> {
        val titles = listOfNotNull(
            title?.trim(),
            editions?.docs?.firstOrNull()?.title?.trim()
        ).filter { it.isNotBlank() && !TRANSLITERATED.containsMatchIn(it) }

        val russian  = titles.firstOrNull { hasCyrillic(it) && !PRE_REFORM.containsMatchIn(it) }
        val original = titles.firstOrNull { !hasCyrillic(it) }
        return listOfNotNull(russian, original)
    }

    private fun cleanForQuery(raw: String, maxLength: Int): String =
        raw.replace(BRACKETED, " ").replace("\"", " ").replace(WHITESPACE, " ")
            .trim().take(maxLength).trim()

    companion object {
        /** MARC, not ISO — the code is `rus`, and `ru` silently matches nothing. */
        const val RUSSIAN = "rus"

        private const val SITE = "https://openlibrary.org"
        private const val SEARCH = "$SITE/search.json"
        private const val COVERS = "https://covers.openlibrary.org"
        private const val POPULAR = "readinglog"
        private const val FIELDS =
            "key,title,author_name,first_publish_year,subject,cover_i," +
                "ratings_average,ratings_count,number_of_pages_median,editions"
        private const val PAGE_SIZE = 20
        private const val LOOKUP_SIZE = 8
        private const val SUBJECT_SOURCE_DOCS = 4
        private const val SUBJECT_MIN_DOCS = 2
        private const val MAX_CATALOGUE_AUTHORS = 2
        private const val MAX_TITLE_QUERY_CHARS = 60
        private const val MAX_AUTHOR_QUERY_CHARS = 40
        private const val LATEST_PLAUSIBLE_YEAR = 2100
        private val BRACKETED = Regex("\\[[^]]*]|\\([^)]*\\)")
        private val WHITESPACE = Regex("\\s+")

        /**
         * Past this many names the record is not a book but a heap of editions — «Metamorfosi»
         * carries two hundred and thirty-seven. Set well above a real anthology: the Borges
         * anthology of fantastic literature is credited to seventy-four writers and is a book.
         */
        private const val MAX_CREDITED = 100

        /** Below this a surname identifies nobody — see [credited]. */
        private const val MIN_SURNAME = 4

        private val NOT_A_LETTER = Regex("[^\\p{L}\\p{N}]+")

        /** What NFD leaves behind once the accents are separated from their letters. */
        private val COMBINING = Regex("\\p{Mn}+")

        /** Struck-through letters, which decompose into nothing and have to be spelled out. */
        private val CROSSED = mapOf(
            'ł' to 'l', 'Ł' to 'L', 'ø' to 'o', 'Ø' to 'O',
            'đ' to 'd', 'Đ' to 'D', 'ı' to 'i', 'ħ' to 'h'
        )

        /**
         * Letters dropped by the 1918 reform, plus the hard sign where it no longer belongs —
         * at the end of a word. Inside one it is ordinary and still in use.
         */
        private val PRE_REFORM = Regex("[ѣіѳѵ]|ъ(?=\\s|$)", RegexOption.IGNORE_CASE)

        /**
         * The marks of an ALA-LC transliteration — the library-catalogue romanisation this site
         * files most of its Russian editions under: «Roboty utrenneĭ zari», «Kladovai︠a︡ i︠u︡mora»,
         * «Kosmicheska︠i︡a odisse︠i︡a». It is neither the Russian title nor the original one, no
         * search engine has heard of it, and on a card it reads as mojibake. A record left with
         * nothing else to show for itself is dropped ([readableTitles]).
         *
         * The breves, the two kinds of soft sign and the combining half-marks; a real English,
         * Polish or French title carries none of them.
         */
        private val TRANSLITERATED = Regex("[\u012D\u012C\u012B\u012A\u02B9\u02BA\uFE20\uFE21\uFE22\uFE23\u0361]")
    }
}
