package com.lexiread.app.data.repository.discovery

import com.lexiread.app.data.api.WikidataApiService
import com.lexiread.app.data.model.SparqlValue
import com.lexiread.app.domain.discover.DiscoveredBook
import com.lexiread.app.domain.discover.LikedItem
import com.lexiread.app.domain.discover.NOT_A_BOOK
import com.lexiread.app.domain.discover.describesABook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

/**
 * Wikidata — the source that answers in Russian.
 *
 * Open Library holds editions, so a Russian title only turns up when a Russian edition happens
 * to be catalogued; Wikidata holds one item per work with a label in every language it has one
 * in, so asking in Russian gets Russian for everything famous enough to be there. It carries no
 * covers, no blurbs and no ratings, which is exactly the half Open Library is good at — the two
 * are used together rather than one instead of the other.
 *
 * Cost discipline matters here more than anywhere else in the app. The query service refuses
 * with 502 when several requests arrive at once, and one wrong clause turns a one-second query
 * into a fourteen-second one, so the whole source is three SPARQL requests, run one at a time.
 */
internal class WikidataSource(private val api: WikidataApiService) {

    /** What Wikidata knows about one book the reader likes. Ids for querying, names for reading. */
    data class Facts(
        val authorIds: List<String>,
        val authorNames: List<String>,
        val genreIds: List<String>,
        val genreNames: List<String>
    )

    /**
     * Turns the reader's marks into catalogue ids, keyed by [LikedItem.key].
     *
     * The lookups run in parallel — they go to the ordinary MediaWiki API, which does not mind —
     * and the one SPARQL request that follows covers every mark at once.
     */
    suspend fun resolve(items: List<LikedItem>, calls: CallLog): Map<String, Facts> {
        val seeds = coroutineScope {
            items.map { item -> async { item.key to findItem(item, calls) } }.awaitAll()
        }.mapNotNull { (key, id) -> id?.let { key to it } }
        if (seeds.isEmpty()) return emptyMap()

        val rows = query(
            """
            SELECT ?seed ?author ?authorLabel ?genre ?genreLabel WHERE {
              VALUES ?seed { ${values(seeds.map { it.second }.distinct())} }
              ?seed wdt:P50 ?author .
              OPTIONAL { ?seed wdt:P136 ?genre . }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "ru". }
            }
            """.trimIndent(),
            calls
        )

        // Grouped by seed rather than by row: one book produces a row per author-genre pair, so
        // Достоевский arrives four times over for one novel with four genres.
        // The label half is nullable: Wikidata has an id for everything and a Russian name for
        // most things, and the id is still worth querying with when the name is missing.
        val bySeed = mutableMapOf<String, MutableSet<Pair<String, String?>>>()
        val genresBySeed = mutableMapOf<String, MutableSet<Pair<String, String?>>>()
        for (row in rows) {
            val seed = row["seed"].entityId() ?: continue
            row["author"].entityId()?.let { id ->
                bySeed.getOrPut(seed) { linkedSetOf() } += id to row["authorLabel"].label()
            }
            row["genre"].entityId()?.takeIf { it !in GENERIC_GENRES }?.let { id ->
                genresBySeed.getOrPut(seed) { linkedSetOf() } += id to row["genreLabel"].label()
            }
        }

        return seeds.mapNotNull { (key, seed) ->
            val authors = bySeed[seed].orEmpty()
            if (authors.isEmpty()) return@mapNotNull null
            val genres = genresBySeed[seed].orEmpty()
            key to Facts(
                authorIds   = authors.map { it.first },
                authorNames = authors.mapNotNull { it.second },
                genreIds    = genres.map { it.first },
                genreNames  = genres.mapNotNull { it.second }
            )
        }.toMap()
    }

    /** Other books by the same writers, best known first. */
    suspend fun discoverByAuthor(authorIds: List<String>, calls: CallLog): List<DiscoveredBook> {
        if (authorIds.isEmpty()) return emptyList()
        return query(
            """
            SELECT ?work ?workLabel ?authorLabel ?year ?links ?article WHERE {
              VALUES ?author { ${values(authorIds)} }
              ?work wdt:P50 ?author ; wikibase:sitelinks ?links .
              OPTIONAL { ?work wdt:P577 ?d . BIND(YEAR(?d) AS ?year) }
              OPTIONAL { ?article schema:about ?work ; schema:isPartOf <$RU_WIKIPEDIA> . }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "ru". }
            } ORDER BY DESC(?links) LIMIT $AUTHOR_LIMIT
            """.trimIndent(),
            calls
        ).mapNotNull { it.toDiscovered(withGenre = false) }
    }

    /** Books in the same genres, well known enough to be worth naming. */
    suspend fun discoverByGenre(genreIds: List<String>, calls: CallLog): List<DiscoveredBook> {
        if (genreIds.isEmpty()) return emptyList()
        return query(
            """
            SELECT ?work ?workLabel ?authorLabel ?genreLabel ?year ?links ?article WHERE {
              VALUES ?genre { ${values(genreIds)} }
              ?work wdt:P136 ?genre ; wdt:P50 ?author ; wikibase:sitelinks ?links .
              FILTER(?links >= $MIN_SITELINKS)
              OPTIONAL { ?work wdt:P577 ?d . BIND(YEAR(?d) AS ?year) }
              OPTIONAL { ?article schema:about ?work ; schema:isPartOf <$RU_WIKIPEDIA> . }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "ru". }
            } LIMIT $GENRE_LIMIT
            """.trimIndent(),
            calls
        ).mapNotNull { it.toDiscovered(withGenre = true) }
    }

    /**
     * Transliterated author names to the way they are spelled in Russian, all in one query.
     *
     * Google files «Преступление и наказание» under *Fyodor Dostoyevsky* and «Зелёная миля» under
     * *Stephen King*, and neither of those is a name a Russian reader wants under a Russian title.
     * It cannot be transliterated back — *Stephen* is «Стивен» here and «Стефан» elsewhere, and
     * only a person knows which — so it has to be looked up, and Wikidata is where the two
     * spellings of a writer are stored side by side.
     *
     * Aliases as well as labels, because a catalogue's spelling is rarely Wikidata's preferred
     * one: the English label is *Fyodor Dostoevsky* and *Dostoyevsky* is an alias of it. Measured
     * at two seconds and four kilobytes for twelve names, of which it resolved eleven — the miss
     * was *Stanislaw Lem*, spelled without the ł that Wikidata knows him by. A name that does not
     * resolve is simply left as the catalogue had it.
     */
    suspend fun russianNames(names: List<String>, calls: CallLog): Map<String, String> {
        // A quote or a backslash would end the literal early and the whole query would fail, so
        // those names are dropped rather than escaped — no writer is spelled with either.
        val safe = names.map { it.trim() }
            .filter { it.isNotBlank() && it.none { c -> c == '"' || c == '\\' } }
            .distinct()
            .take(MAX_NAMES)
        if (safe.isEmpty()) return emptyMap()

        val literals = safe.joinToString(" ") { name -> "\"" + name + "\"@en" }
        val rows = query(
            """
            SELECT ?name ?ru WHERE {
              VALUES ?name { $literals }
              ?person rdfs:label|skos:altLabel ?name ; wdt:P31 wd:Q5 ; rdfs:label ?ru .
              FILTER(LANG(?ru) = "ru")
            }
            """.trimIndent(),
            calls
        )

        val found = mutableMapOf<String, String>()
        for (row in rows) {
            val latin = row["name"]?.value?.takeIf { it.isNotBlank() } ?: continue
            val russian = row["ru"]?.value?.trim()?.takeIf { it.isNotBlank() } ?: continue
            found.putIfAbsent(latin, russian)
        }
        return found
    }

    /**
     * The reader's words to a catalogue id.
     *
     * The title alone is what gets searched, because that is what is remembered accurately —
     * and a bare surname resolves badly anyway: «Достоевский» is answered with an asteroid and
     * a crater on Mercury before it is answered with the writer. The author is used to choose
     * among the results instead, against the one-line description Wikidata returns in Russian:
     * «роман Станислава Лема» and «фильм Андрея Тарковского» are both called «Солярис», and
     * only one of them is a book.
     *
     * The writer's name alone is not enough to choose by, which is the whole reason this reads
     * the description rather than searching it. «Девушка с татуировкой дракона» is answered
     * with four items, and the first is «Фильм Дэвида Финчера, экранизация одноимённого
     * детектива Стига Ларссона» — the writer's name is in it, and it is not his book. Picking
     * it cost more than one wrong genre: a film has no `wdt:P50`, so the query that follows
     * returned no author and no genre at all, this mark contributed nothing to the profile, and
     * both Wikidata halves of the screen went empty for a reader whose only mark it was.
     *
     * So a description that reads like a book comes first, and among those, one that also names
     * the writer. A book by the wrong writer still beats a film by the right one — the reader
     * is being recommended books.
     */
    private suspend fun findItem(item: LikedItem, calls: CallLog): String? {
        val title = item.title.trim().take(MAX_SEARCH_CHARS)
        if (title.isBlank()) return null
        val hits = try {
            val found = api.searchEntities(searchUrl(title)).search.orEmpty()
            calls.succeeded()
            found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val stem = authorStem(item.author)
        fun namesTheAuthor(description: String): Boolean =
            stem.isNotBlank() && description.lowercase().contains(stem)

        val described = hits.map { it to it.description.orEmpty() }
        return (described.firstOrNull { (_, text) -> describesABook(text) && namesTheAuthor(text) }
            ?: described.firstOrNull { (_, text) -> describesABook(text) }
            ?: described.firstOrNull { (_, text) ->
                namesTheAuthor(text) && !NOT_A_BOOK.containsMatchIn(text)
            }
            ?: described.firstOrNull())?.first?.id
    }

    /**
     * The author's name cut back to something a Russian sentence will still contain.
     *
     * A description reads «роман Михаила Булгакова», declined, while the shelf says «Михаил
     * Булгаков». Dropping the last two letters of the longest word leaves the stem both forms
     * share, which is crude and works: full morphology for one substring test is not a trade
     * worth making.
     */
    private fun authorStem(author: String): String {
        val longest = author.split(WHITESPACE).filter { it.isNotBlank() }.maxByOrNull { it.length }
            ?: return ""
        val stem = if (longest.length > MIN_STEM_SOURCE) longest.dropLast(2) else longest
        return if (stem.length >= MIN_STEM_LENGTH) stem.lowercase() else ""
    }

    private suspend fun query(sparql: String, calls: CallLog): List<Map<String, SparqlValue>> =
        try {
            val rows = api.sparql(sparqlUrl(sparql)).results?.bindings.orEmpty()
            calls.succeeded()
            rows
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    private fun Map<String, SparqlValue>.toDiscovered(withGenre: Boolean): DiscoveredBook? {
        val workId = this["work"].entityId() ?: return null
        val title = this["workLabel"].label() ?: return null
        val author = this["authorLabel"].label() ?: return null
        return DiscoveredBook(
            // Prefixed so it can never collide with an Open Library work key or a Google volume id.
            id            = "wd:$workId",
            title         = title,
            authors       = listOf(author),
            categories    = listOfNotNull(if (withGenre) this["genreLabel"].label() else null),
            thumbnailUrl  = null,
            publishedYear = this["year"]?.value?.takeIf { it.isNotBlank() },
            averageRating = null,
            ratingsCount  = 0,
            // The Wikipedia article when there is one; the data page reads like a spreadsheet.
            infoLink      = this["article"]?.value?.takeIf { it.isNotBlank() }
                ?: "https://www.wikidata.org/wiki/$workId"
        )
    }

    /** `http://www.wikidata.org/entity/Q1234` → `Q1234`. */
    private fun SparqlValue?.entityId(): String? =
        this?.value?.substringAfterLast('/')?.takeIf { QID.matches(it) }

    /**
     * A label, or null when there is none.
     *
     * Asked for Russian and Russian only, the label service answers with the bare item id for
     * anything that has no Russian label — so `Q124809773` arrives where a title should be. That
     * is not a title, and showing it would be worse than showing one suggestion fewer.
     */
    private fun SparqlValue?.label(): String? =
        this?.value?.trim()?.takeIf { it.isNotBlank() && !QID.matches(it) }

    private fun values(ids: List<String>): String = ids.joinToString(" ") { "wd:$it" }

    private fun sparqlUrl(query: String): String =
        "$SPARQL?format=json&query=${URLEncoder.encode(query, "UTF-8")}"

    private fun searchUrl(text: String): String =
        "$API?action=wbsearchentities&format=json&language=ru&uselang=ru&type=item" +
            "&limit=$SEARCH_HITS&search=${URLEncoder.encode(text, "UTF-8")}"

    companion object {
        private const val API = "https://www.wikidata.org/w/api.php"
        private const val SPARQL = "https://query.wikidata.org/sparql"
        private const val RU_WIKIPEDIA = "https://ru.wikipedia.org/"

        /**
         * How many language editions an article needs before its book counts as well known.
         *
         * This replaces sorting by that same number, which is the difference between a query
         * that takes two seconds and one that takes thirteen: the sort has to rank every book in
         * the genre, the filter only has to reject.
         *
         * Twenty rather than the twelve it started at. The genre query is the only part of the
         * app that recommends a writer the reader has never read, so its bar is the only thing
         * standing between them and whatever else happens to be tagged «научная фантастика» —
         * at twelve that was Stephenie Meyer's «Гостья» next to «Пикник на обочине». At twenty
         * the same query answers with «Дюна», «О дивный новый мир», «451 градус по Фаренгейту»
         * and «Двадцать тысяч льё под водой», and it answers faster, because a scarcer filter
         * with a smaller [GENRE_LIMIT] is less work than a loose one with a large one.
         */
        private const val MIN_SITELINKS = 20

        private const val AUTHOR_LIMIT = 30
        private const val GENRE_LIMIT = 40
        private const val SEARCH_HITS = 5

        /** Names per name-resolving query. The list is six books long; this is slack, not a cap. */
        private const val MAX_NAMES = 8
        private const val MAX_SEARCH_CHARS = 80

        /** Below this a word is already a stem, or too short for the match to mean anything. */
        private const val MIN_STEM_SOURCE = 5
        private const val MIN_STEM_LENGTH = 4

        /**
         * Genres that say only "this is a book". Wikidata files «Преступление и наказание» under
         * «роман-фельетон» among others, and a genre this wide would fill the results from one
         * mark alone — the whole point of the genre step is what several marks have in common.
         */
        private val GENERIC_GENRES = setOf(
            "Q8261",      // роман
            "Q7725634",   // литературное произведение
            "Q47461344",  // письменная работа
            "Q49084",     // рассказ
            "Q1667921",   // серия романов
            "Q149537",    // повесть
            "Q1279564"    // сборник рассказов
        )

        private val QID = Regex("^Q\\d+$")
        private val WHITESPACE = Regex("\\s+")
    }
}
