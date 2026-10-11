package com.lexiread.app.data.repository.discovery

import com.lexiread.app.data.api.GoogleBooksApiService
import com.lexiread.app.data.api.NetworkModule
import com.lexiread.app.data.model.GoogleBookItem
import com.lexiread.app.domain.discover.DiscoveredBook
import com.lexiread.app.domain.discover.inReadersLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.net.URLEncoder

/**
 * Google Books — the first catalogue asked, and the one whose answers are shown.
 *
 * It is the only one of the three that can be asked for a language and answer with the actual
 * Russian edition: the title as it is printed on a modern cover, a blurb, and a cover image.
 * The other two are kept for what it misses — Wikidata for works with no edition Google knows
 * about, Open Library for ratings — but where all three have the same book, this is the record
 * the reader sees. See [com.lexiread.app.data.repository.BookDiscoveryRepository].
 *
 * The API is free but never anonymous: every keyless request, down to `?q=test`, comes back 429
 * with `"quota_limit_value": "0"`. The key travels with the app —
 * [com.lexiread.app.data.api.NetworkModule.GOOGLE_BOOKS_API_KEY].
 */
internal class GoogleBooksSource(private val api: GoogleBooksApiService) {

    /**
     * Other books by the writers the reader likes.
     *
     * Authors only, no genres. `subject:"science fiction"` with `langRestrict=ru` answers with
     * twenty volumes and not one Russian title among them — Google's subject index is the
     * English BISAC heading and its language filter does not reach it — so a genre query here
     * costs a request and returns nothing the screen can use. Genres are Wikidata's job.
     */
    suspend fun discover(authors: List<String>, calls: CallLog): List<DiscoveredBook> =
        coroutineScope {
            authors.map { async { byAuthor(it, calls) } }
                .awaitAll().flatten().distinctBy { it.id }
        }

    /**
     * One writer, asked for by full name and by surname alone, and the two answers merged.
     *
     * `inauthor:` matches the name as it is written on the editions Google holds, and which form
     * that is varies by writer with no pattern to predict it from. «Фёдор Михайлович
     * Достоевский» returns twenty books and «Достоевский» returns twenty; «Аркадий Натанович
     * Стругацкий» returns **one** and «Стругацкий» returns twenty; «Евгений Иванович Замятин»
     * returns none and «Замятин» returns three. The other way round, «Булгаков» and «Толстой»
     * on their own return nothing while the full names return twenty each.
     *
     * This used to send the second query only when the first came back with fewer than five
     * books, on the reasoning that a full answer needs no fallback. A full answer is not the
     * same as a right one: «Стиг Ларссон» returns eight volumes and not one of them is his —
     * they are biographies of him and a memoir by his partner, all filed under his name — while
     * «Ларссон» returns the four Millennium novels with covers and blurbs. The count said the
     * query had worked, so the query that would have found the books never ran, and the only
     * Larsson records left in the pool were scans of a bibliographic index.
     *
     * Both, then, merged by volume id. It costs one extra request per writer and three per
     * screen, against a whole writer's work going missing from it.
     */
    private suspend fun byAuthor(author: String, calls: CallLog): List<DiscoveredBook> {
        val familyName = surname(author) ?: return fetch(url("inauthor:\"$author\""), calls)
        return coroutineScope {
            val full = async { fetch(url("inauthor:\"$author\""), calls) }
            val short = async { fetch(url("inauthor:\"$familyName\""), calls) }
            (full.await() + short.await()).distinctBy { it.id }
        }
    }

    /**
     * The surname, which in a Russian catalogue label is the last word — «Фёдор Михайлович
     * Достоевский», «братья Стругацкие». Not the longest word: in «Евгений Иванович Замятин»
     * that is the patronymic, and `inauthor:"Иванович"` answers with twenty unrelated writers.
     */
    private fun surname(author: String): String? {
        val words = author.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size < 2) return null
        return words.last().takeIf { it.length >= MIN_SURNAME_CHARS }
    }

    private fun url(query: String): String = buildString {
        append(VOLUMES).append("?q=").append(encode(query))
        // Russian-language volumes only. The whole reason for reaching past Open Library is
        // that this one can be asked for the language the reader reads in.
        append("&langRestrict=").append(RUSSIAN)
        append("&printType=books")
        append("&maxResults=").append(PAGE_SIZE)
        // Only the fields the card actually draws. A full volume record carries the sale terms,
        // the access rights, the search snippet and four more cover sizes: twenty of them is
        // 77 KB against 23 KB for this, and the difference is paid for on a phone connection
        // inside a ten-second call timeout.
        append("&fields=").append(encode(FIELDS))
        append("&key=").append(encode(NetworkModule.GOOGLE_BOOKS_API_KEY))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * One query, with one retry.
     *
     * Google answers roughly one request in seven with `503 backendFailed` — not a rate limit
     * and not tied to any particular query, since the same URL succeeds a second later. Without
     * a retry that is a catalogue's worth of suggestions silently missing from the screen, and
     * the reader has no way to tell that from "nothing matched".
     */
    private suspend fun fetch(url: String, calls: CallLog): List<DiscoveredBook> {
        repeat(ATTEMPTS) { attempt ->
            try {
                val items = api.search(url).items.orEmpty()
                calls.succeeded()
                return items.mapNotNull { it.toDiscovered() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A rejected key, an exhausted quota, no network — all the same from here, and
                // none of them should cost the reader what the other catalogues found.
                if (attempt == ATTEMPTS - 1) return emptyList()
                delay(RETRY_DELAY_MS)
            }
        }
        return emptyList()
    }

    private fun GoogleBookItem.toDiscovered(): DiscoveredBook? {
        val volumeId = id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val info = volumeInfo ?: return null
        val bookTitle = info.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bookAuthors = info.authors.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
        if (isLibraryCard(info.imageLinks?.thumbnail, info.description, bookAuthors)) return null
        return DiscoveredBook(
            // Prefixed so it cannot collide with an Open Library work key or a Wikidata item.
            id            = "gb:$volumeId",
            title         = bookTitle,
            authors       = bookAuthors,
            // Dropped when it is not in Russian. A foreign blurb is still evidence that this is
            // a real edition and not a scanned index card, so [isLibraryCard] above sees it
            // first; it just cannot go on a card the reader is meant to read.
            description   = info.description.orEmpty().trim()
                .takeIf { inReadersLanguage(it) }.orEmpty(),
            categories    = info.categories.orEmpty(),
            thumbnailUrl  = info.imageLinks?.thumbnail?.let(::secure),
            publishedYear = info.publishedDate?.take(YEAR_CHARS)?.takeIf { it.all(Char::isDigit) },
            pageCount     = info.pageCount?.takeIf { it > 0 },
            averageRating = info.averageRating,
            ratingsCount  = info.ratingsCount ?: 0
            // No link: nothing opens one, and Google's is a shop page in any case. The field
            // exists for Wikidata's Wikipedia article — see DiscoveredBook.infoLink.
        )
    }

    /**
     * A scanned library index rather than a book anyone can hold.
     *
     * Google's Russian corpus includes «Книжная летопись» and the annual bibliographic indexes
     * of the Russian Federation — page after page of one-line entries, each of which Google has
     * turned into a volume of its own. They are the records that made the screen offer «Девуска,
     * которая взрывала воздусные замки» by «Стиег Ларссон»: the OCR ate the ш, the writer's name
     * is a typo, and there is neither a cover nor a word about what is inside.
     *
     * Two marks tell them apart, and neither is a judgement about the book:
     *
     * - **No cover and no blurb at all.** A real edition Google sells or previews has at least
     *   one of the two; measured across seven writers this drops the biographies filed under
     *   their subject's name and the index scans, and keeps every edition on offer. It does cost
     *   the odd genuine record — one printing of «Трудно быть богом» is a bare line like this —
     *   but the same novel is in the same answer as a proper edition with a cover.
     * - **A name in library romanization** — «Arkadiĭ Strugat︠s︡kiĭ», «Вадим Ėрлихман». The
     *   breves, macrons and ligature halves belong to the ALA-LC scheme catalogues transliterate
     *   Cyrillic with; no reader would recognise the writer, and nothing downstream can undo it.
     *   Only those marks count, so «Böll» and «Müller» are left alone.
     *
     * A record kept here can still be plain: Wikidata supplies works with no cover and no blurb
     * either, and those are wanted. The rule is Google's alone, for Google's own corpus.
     */
    private fun isLibraryCard(cover: String?, description: String?, authors: List<String>): Boolean {
        if (authors.any { ROMANIZED.containsMatchIn(it) }) return true
        return cover.isNullOrBlank() && description.isNullOrBlank()
    }

    /**
     * Cover links come back as plain http, and the app forbids cleartext traffic outright — left
     * alone every one of them would simply fail to load. The same host serves them over https.
     */
    private fun secure(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private companion object {
        const val VOLUMES = "https://www.googleapis.com/books/v1/volumes"
        const val RUSSIAN = "ru"
        const val PAGE_SIZE = 20
        const val YEAR_CHARS = 4

        const val FIELDS =
            "totalItems,items(id,volumeInfo(title,authors,description,categories," +
                "publishedDate,pageCount,averageRating,ratingsCount,imageLinks/thumbnail))"

        const val MIN_SURNAME_CHARS = 3

        const val ATTEMPTS = 2
        const val RETRY_DELAY_MS = 400L

        val WHITESPACE = Regex("\\s+")

        /**
         * The ALA-LC marks: breve and macron over a vowel, the dot above that stands for э, and
         * the two halves of the ligature bar, both precomposed and combining. See [isLibraryCard].
         */
        val ROMANIZED = Regex("[ĭĬīĪĕĔūŪāĀōŌēĒėĖ\\u0304\\u0306\\u0307\\ufe20-\\ufe23]")
    }
}
