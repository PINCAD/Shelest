package com.lexiread.app.domain.discover

import com.lexiread.app.data.model.Book
import kotlin.math.min

/**
 * Turns a pile of catalogue search results into a short, ordered list of books worth showing.
 *
 * The searching is the easy half. A query for `subject:"Science Fiction"` returns four hundred
 * volumes, most of them editions of each other, a good number of them already on the reader's
 * shelf, and a fair few of them nothing to do with what was asked — the catalogue's genre paths
 * are broad and its relevance ordering is its own. What is left after that is a ranking problem,
 * and this is where it is solved.
 *
 * Four rules do most of the work:
 *
 *  - **Nothing the reader already has.** Titles are compared loosely, because a shelf title is
 *    whatever was in the file — "Война и мир. Том 1" against the catalogue's "Война и мир" —
 *    and suggesting someone a book they are currently reading is the one failure that makes the
 *    whole screen look broken.
 *  - **Nothing unconnected to the profile.** A candidate that shares neither an author nor a
 *    genre with anything the reader liked is dropped outright, however highly the catalogue
 *    ranked it. This is what keeps the noise from the broad subject queries out.
 *  - **Nothing twice, and no writer twice.** One book per author on a list this short.
 *  - **Not all of it the same kind of match.** Half the places at most go to "another book by a
 *    writer you like"; the rest are for other writers in the reader's genres. See [rank].
 *
 * Pure and synchronous: no Android, no network, everything passed in. The searching is untestable
 * without a live catalogue; this is not, and this is the part that decides what the reader sees.
 */
object DiscoveryRanker {

    /**
     * How many suggestions a run is worth showing.
     *
     * Six, and short on purpose. The list is not a catalogue to browse — it is an answer to
     * "what next", and an answer of twenty-four is no answer. It also keeps the composition
     * rules meaningful: with two dozen places every candidate that scored at all gets in and
     * the ordering is the only thing left deciding anything.
     */
    const val DEFAULT_LIMIT = 6

    /** Sharing an author is close to conclusive; nothing else comes near it. */
    private const val AUTHOR_MATCH = 70.0

    /** Per shared genre token. */
    private const val CATEGORY_MATCH = 14.0

    /** Four shared tokens already means "the same shelf"; more of them says nothing new. */
    private const val MAX_CATEGORY_POINTS = 56.0

    /**
     * Per liked book beyond the first that a candidate connects to.
     *
     * A book that answers to three of the reader's five marks is a better suggestion than one
     * that answers to a single mark three times over, and counting tokens alone cannot tell
     * those apart: one heavily-tagged favourite contributes as many tokens as three books do.
     */
    private const val SHARED_MARK_BONUS = 12.0

    /** A card with no cover and no blurb is a bad suggestion however well it matches. */
    private const val HAS_COVER = 5.0
    private const val HAS_BLURB = 5.0

    /** Ratings only start counting above this — a catalogue average sits near it by default. */
    private const val RATING_FLOOR = 3.5
    private const val RATING_WEIGHT = 8.0

    /** Below this many ratings the average is one person's opinion, not a signal. */
    private const val RATINGS_FOR_TRUST = 50

    /**
     * At most two books per writer. One familiar author may reasonably occupy a second place,
     * but never turn the whole short list into their bibliography.
     */
    private const val MAX_PER_AUTHOR = 2

    /**
     * Genre paths nearly every novel carries. Left in, they would match everything against
     * everything and the category signal would be indistinguishable from noise.
     */
    private val GENERIC_CATEGORIES = setOf(
        "fiction", "nonfiction", "non fiction", "general", "books", "literature",
        "литература", "художественная литература", "книги",

        // Wikidata genres, which arrive as Russian labels rather than as a catalogue path. Its
        // vocabulary is precise where Open Library's is sprawling, so only the forms that say
        // nothing beyond "this is a book" need removing.
        "роман", "повесть", "рассказ", "новелла", "проза", "художественная проза",
        "художественное произведение", "литературное произведение", "письменная работа",
        "литературный жанр", "жанр", "сборник рассказов", "серия романов", "произведение",

        // Library cataloguing headings rather than genres. Open Library's subject lists are
        // crowd-sourced from library records and carry a lot of these; a token like
        // "translations into english" is shared by half the catalogue's Russian classics, so
        // left in it would connect every one of them to every other one for no reason.
        "translations into english", "accessible book", "protected daisy", "in library",
        "open library staff picks", "large type books", "reading level", "overdrive",
        "new york times bestseller", "slavic philology", "russian language",
        "english language", "romans", "nouvelles", "textes", "manuscripts",
        "translations from russian", "criticism and interpretation", "history",
        "social life and customs", "manners and customs", "collections", "indexes",
        "adaptations", "specimens", "dictionaries", "early works to 1800",
        "controversial literature", "classic literature", "classics", "literary",

        // Left over from inverted headings — "Fiction, psychological" splits into a generic word
        // and a bare adjective, and the adjective on its own is not the genre it came from.
        // Asked of the catalogue, `subject=psychological` answers with Carnegie and Covey.
        // The compound forms ("psychological fiction") are separate tokens and survive.
        "psychological", "historical",

        // Places. Library records file a novel under where it is set and where it was written,
        // and those headings sit in the same list as the genres with nothing to tell them apart.
        // "Russia (federation), fiction" becomes the token `russia federation`, and asking the
        // catalogue for that returns statistical yearbooks of Bashkortostan.
        "russia", "russia federation", "soviet union", "united states", "great britain",
        "england", "france", "germany", "россия", "ссср"
    )

    /** A genre is a phrase, not a sentence. See [isGenreShaped]. */
    private const val MAX_GENRE_WORDS = 3

    /**
     * Below this a surname is too short to take two letters off and still mean anything — «Лем»
     * would become «Л», which is in every title there is. See [titlesTheAuthor].
     */
    private const val MIN_SURNAME_FOR_STEM = 6

    private val WHITESPACE = Regex("\\s+")

    /**
     * The suggestions, best first, composed rather than merely sorted.
     *
     * Sorting alone does not produce a good list here, because one kind of match outscores the
     * other by design: another book by a writer the reader likes is worth seventy points and
     * nothing else comes close, so a straight top-six is six books by three writers the reader
     * has already read. That is the safest possible answer and very nearly a useless one — a
     * reader who wanted more Достоевский did not need an app to find it.
     *
     * So the places are divided. Half at most go to the same-author matches, in score order;
     * the rest go to other writers whose books share the reader's genres, which is where a
     * suggestion can actually surprise. Only if there are not enough of those does the
     * remainder fall back to the author matches, and the finished list is sorted by score again
     * so the reader still meets the strongest suggestion first.
     */
    fun rank(
        profile: List<TasteEntry>,
        candidates: List<DiscoveredBook>,
        owned: List<Book>,
        limit: Int = DEFAULT_LIMIT
    ): List<DiscoverySuggestion> {
        if (profile.isEmpty() || candidates.isEmpty()) return emptyList()

        // Everything the reader already has, plus the liked books themselves — those are on the
        // shelf too, and handing one of them back as a discovery is worse than showing nothing.
        val ownedKeys = (owned.map { titleKey(it.title) } + profile.map { titleKey(it.likedTitle) })
            .filter { it.isNotBlank() }
            .distinct()

        // Which liked book each author and each genre token came from, so a suggestion can say so.
        val authorSources = mutableMapOf<String, TasteEntry>()
        val shortAuthorSources = mutableMapOf<String, TasteEntry>()
        val categorySources = mutableMapOf<String, MutableList<TasteEntry>>()
        for (entry in profile) {
            entry.authors.map { authorKey(it) }.filter { it.isNotBlank() }
                .forEach { authorSources.putIfAbsent(it, entry) }
            entry.authors.mapNotNull { authorShortKey(it) }
                .forEach { shortAuthorSources.putIfAbsent(it, entry) }
            genreTokens(entry.categories)
                .forEach { categorySources.getOrPut(it) { mutableListOf() }.add(entry) }
        }

        val seen = mutableSetOf<String>()
        val scored = candidates.mapNotNull { candidate ->
            // No author at all is the catalogue's signature for a scanned oddity or a stub.
            if (candidate.title.isBlank() || candidate.authors.isEmpty()) return@mapNotNull null

            val key = titleKey(candidate.title)
            if (key.isBlank() || ownsAlready(key, ownedKeys)) return@mapNotNull null

            // Editions. The same work comes back several times over across several queries,
            // each time with a different id, and only the title and author stay put.
            val scoredCandidate = score(candidate, authorSources, shortAuthorSources, categorySources)
                ?: return@mapNotNull null
            // A sparse record can fail the relevance test while another catalogue has useful
            // genres for the same work. Do not let the sparse record claim the duplicate key.
            if (!seen.add("$key|${authorGroupKey(candidate.authors.first())}")) return@mapNotNull null
            scoredCandidate
        }.sortedByDescending { it.score }

        val perAuthor = mutableMapOf<String, Int>()
        val picked = mutableListOf<DiscoverySuggestion>()
        var fromLikedAuthors = 0

        fun take(suggestion: DiscoverySuggestion) {
            val author = authorGroupKey(suggestion.book.authors.firstOrNull() ?: "")
            val count = perAuthor[author] ?: 0
            if (author.isNotBlank() && count >= MAX_PER_AUTHOR) return
            perAuthor[author] = count + 1
            if (suggestion.byAuthor) fromLikedAuthors++
            picked += suggestion
        }

        // The quota is a ceiling on one kind of match, not a floor under the other: when the
        // genre half comes up short the places are given back rather than left empty.
        val sameAuthorPlaces = (limit + 1) / 2
        for (suggestion in scored) {
            if (picked.size >= limit) break
            if (suggestion.byAuthor && fromLikedAuthors >= sameAuthorPlaces) continue
            take(suggestion)
        }
        if (picked.size < limit) {
            val taken = picked.mapTo(mutableSetOf<String>()) { it.book.id }
            for (suggestion in scored) {
                if (picked.size >= limit) break
                if (suggestion.book.id in taken) continue
                take(suggestion)
            }
        }
        return picked.sortedByDescending { it.score }
    }

    private fun score(
        candidate: DiscoveredBook,
        authorSources: Map<String, TasteEntry>,
        shortAuthorSources: Map<String, TasteEntry>,
        categorySources: Map<String, List<TasteEntry>>
    ): DiscoverySuggestion? {
        // The matched name as well as the book it was matched to: a catalogue record credits
        // everyone in an anthology, and the one the reader knows is not always the one printed
        // first. Google files «Врата космоса» — a collection with a Strugatsky story in it —
        // under Jules Verne, and «Тот же автор, что и «Пикник на обочине»» over Verne's name
        // reads as a broken recommender rather than as an anthology.
        val matched = candidate.authors
            .asSequence()
            .mapNotNull { name ->
                val entry = authorSources[authorKey(name)]
                    ?: authorShortKey(name)?.let { shortAuthorSources[it] }
                entry?.let { name to it }
            }
            .firstOrNull()
        val sameAuthorOf = matched?.second

        // Collected works, letters, and the scholarship about a writer, all of which a catalogue
        // files under their name: «Рукописное наследие Евгения Ивановича Замятина» is a genuine
        // answer to `inauthor:"Замятин"` and not a book anyone was asking to be recommended.
        // The stem, not the surname, because the title declines it.
        if (matched != null && titlesTheAuthor(candidate.title, matched.first)) return null

        // How much of the candidate's genre each liked book accounts for. The winner names
        // the suggestion; the total decides where it lands.
        val overlap = mutableMapOf<TasteEntry, Int>()
        val matchedGenres = mutableListOf<String>()
        for (token in genreTokens(candidate.categories)) {
            val sources = categorySources[token] ?: continue
            matchedGenres += token
            sources.distinct().forEach { overlap[it] = (overlap[it] ?: 0) + 1 }
        }
        val sharedTokens = matchedGenres.size

        // Related to nothing the reader liked. The catalogue thought it was a good answer to
        // the query; the query was only ever a proxy for this.
        if (sameAuthorOf == null && sharedTokens == 0) return null

        val rating = candidate.averageRating ?: 0.0
        val trust = min(candidate.ratingsCount, RATINGS_FOR_TRUST) / RATINGS_FOR_TRUST.toDouble()
        val ratingBonus = (rating - RATING_FLOOR).coerceAtLeast(0.0) * RATING_WEIGHT * trust

        val sharedMarks = maxOf(overlap.size, if (sameAuthorOf != null) 1 else 0)

        val score = (if (sameAuthorOf != null) AUTHOR_MATCH else 0.0) +
            min(sharedTokens * CATEGORY_MATCH, MAX_CATEGORY_POINTS) +
            (sharedMarks - 1) * SHARED_MARK_BONUS +
            ratingBonus +
            (if (candidate.thumbnailUrl != null) HAS_COVER else 0.0) +
            (if (candidate.description.isNotBlank()) HAS_BLURB else 0.0)

        val reason = when {
            sameAuthorOf != null -> "Тот же автор, что и «${sameAuthorOf.likedTitle}»"
            else -> {
                val closest = overlap.maxByOrNull { it.value }?.key
                // The genre by name where there is a Russian name for it. Open Library's tokens
                // are English cataloguing headings and unreadable on a card; Wikidata's are
                // «научная фантастика», which is the whole sentence the reader needs.
                val genre = matchedGenres.firstOrNull { hasCyrillic(it) }
                    ?.replaceFirstChar { c -> c.uppercase() }
                when {
                    // Naming one liked book would be picking arbitrarily among several: when
                    // three of the marks are science fiction, the suggestion is not "like
                    // «Солярис»" in particular, and saying so on every card in the list reads
                    // as the app having only one idea.
                    genre != null && overlap.size > 1 -> "$genre: ваш жанр"
                    genre != null && closest != null -> "$genre, как в «${closest.likedTitle}»"
                    closest != null -> "Похоже на «${closest.likedTitle}»"
                    else -> "Из ваших жанров"
                }
            }
        }
        // The matched writer put in front, so the card names the one the reader knows and the
        // per-author cap counts the book against them.
        val shown = if (matched == null) candidate else {
            val known = readersSpelling(matched.first, matched.second)
            candidate.copy(
                authors = listOf(known) +
                    candidate.authors.filterNot { it == known || it == matched.first }
            )
        }
        return DiscoverySuggestion(shown, reason, score, byAuthor = sameAuthorOf != null)
    }

    /**
     * How the reader's own shelf writes the name, in preference to how the catalogue does.
     *
     * The two forms are matched loosely on purpose — that is what lets a shelf holding «Стиг
     * Ларссон» find the books a catalogue files under «Стиег Ларссон» — and having matched them,
     * showing the catalogue's form is the worse of the two choices every time. The reader put
     * theirs on the shelf; the catalogue's is whatever an edition happened to print, which for
     * a translated writer is a typo about as often as not.
     *
     * Two things it stays out of. Where the names differ only in case or word order — Wikidata's
     * «братья Стругацкие» against a cover's «Братья Стругацкие» — there is nothing to correct
     * and the printed form is left alone. And their spelling only wins if it is in Russian, or
     * if the catalogue's is not either: a shelf built from EPUB metadata can hold «Stieg
     * Larsson», which is no improvement on a Russian name —
     * [com.lexiread.app.data.repository.BookDiscoveryRepository] has a Wikidata lookup for
     * exactly that case, and it runs on whatever is left without one.
     */
    private fun readersSpelling(matchedName: String, entry: TasteEntry): String {
        val short = authorShortKey(matchedName) ?: return matchedName
        val theirs = entry.authors
            .firstOrNull { authorShortKey(it) == short && authorKey(it) != authorKey(matchedName) }
            ?: return matchedName
        return if (hasCyrillic(theirs) || !hasCyrillic(matchedName)) theirs.trim() else matchedName
    }

    /**
     * Whether the title is about the writer rather than by them.
     *
     * Compared on a stem — the last two letters dropped — because a Russian title declines the
     * name it contains: «Замятин» appears as «Замятина», «Достоевский» as «Достоевского». Crude,
     * and it only has to hold for names the candidate was already matched to, so the words it
     * could wrongly catch are that one writer's own titles.
     */
    private fun titlesTheAuthor(title: String, author: String): Boolean {
        val surname = authorGroupKey(author)
        if (surname.length < MIN_SURNAME_FOR_STEM) return false
        return titleKey(title).contains(surname.dropLast(2))
    }

    /**
     * Loosely, on purpose. An exact comparison misses "Война и мир. Том 1" against "Война и
     * мир", and every miss is a book the reader owns being offered back to them as a discovery.
     * The length floor is what stops "Мы" from swallowing every title that contains it.
     */
    private fun ownsAlready(candidateKey: String, ownedKeys: List<String>): Boolean =
        ownedKeys.any { owned ->
            owned == candidateKey ||
                (owned.length >= 8 && candidateKey.length >= 8 &&
                    (owned.contains(candidateKey) || candidateKey.contains(owned)))
        }

    /**
     * Catalogue genre paths — "Fiction / Science Fiction / Space Opera" — split into the parts
     * that carry meaning. The separators vary by record, hence all four.
     *
     * Public because the search has to build its subject queries out of the same tokens it will
     * later be scored against. Two different splittings would mean asking the catalogue one
     * question and grading the answers against another.
     *
     * The order of [categories] is carried through — the result is insertion-ordered — because
     * the caller ranks its queries by how far down the list a token appeared. Scoring ignores
     * the order; query building does not.
     */
    fun genreTokens(categories: List<String>): Set<String> =
        categories
            .flatMap { it.split("/", ",", "&", ";") }
            .map { token ->
                token.lowercase().trim()
                    .filter { it.isLetterOrDigit() || it.isWhitespace() }
                    .replace(WHITESPACE, " ")
                    .trim()
            }
            .filter { it.length >= 3 && it !in GENERIC_CATEGORIES && isGenreShaped(it) }
            .toSet()

    /**
     * A genre is a couple of words. Library records are not, and Open Library's subject lists are
     * full of them: "continental european fiction (fictional works by one author)", shelf marks
     * like "PG3326 .Z4 1993", plot headings like "Russia -- History -- 1801-1917 -- Fiction".
     * Matched against each other these connect unrelated books, and asked as a query they return
     * nothing at all, so they are dropped before either can happen.
     */
    private fun isGenreShaped(token: String): Boolean {
        val words = token.split(" ")
        if (words.size > MAX_GENRE_WORDS) return false
        // A call number survives the punctuation strip as a run of digits.
        return words.none { word -> word.length >= 3 && word.all(Char::isDigit) }
    }

    /** Title reduced to bare lowercase words: punctuation and edition noise vary, the words do not. */
    private fun titleKey(title: String): String =
        title.lowercase()
            .replace('ё', 'е')
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Order-insensitive, so "Стиг Ларссон" and "Ларссон, Стиг" are one writer. Deliberately a
     * separate copy of the same idea as the on-shelf recommender's: that one compares two shelf
     * authors against each other, this one compares a shelf author against a catalogue author,
     * and the two will stop needing the same treatment the moment either side is normalised.
     *
     * Public for the same reason [genreTokens] is: the repository decides which candidates are
     * the same writer before ranking them, and two different notions of "same writer" would put
     * the per-author cap and that decision at odds.
     *
     * ё is folded into е here and in [titleKey], because the catalogues disagree about it and
     * nothing else does: Wikidata writes «Фёдор Достоевский», Google's editions write «Федор
     * Достоевский», and left alone those are two writers with one book each rather than one
     * writer whose second copy should have been dropped.
     */
    fun authorKey(author: String): String =
        author.lowercase()
            .replace('ё', 'е')
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(" ", ",")
            .filter { it.isNotBlank() }
            .sorted()
            .joinToString(" ")

    /**
     * The same writer written with and without a patronymic — «Фёдор Михайлович Достоевский»
     * against «Фёдор Достоевский», or «Ф. М. Достоевский» once the punctuation is stripped.
     *
     * [authorKey] compares the whole set of words and so calls those three different people,
     * which is exactly wrong here: the profile's name comes from Wikidata, in full, and the
     * catalogues' editions are credited every one of those ways. This is the looser comparison —
     * surname plus the initials of everything else — used only after the strict one has missed.
     *
     * Initials rather than nothing, because the surname alone would make Лев Толстой and
     * Алексей Толстой one writer, and «Тот же автор, что и «Война и мир»» over a book by the
     * other Tolstoy is a worse failure than one suggestion fewer.
     */
    fun authorShortKey(author: String): String? {
        val words = author.lowercase()
            .replace('ё', 'е')
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(" ", ",")
            .filter { it.isNotBlank() }
        if (words.size < 2) return null
        // The last word, not the longest: in «Евгений Иванович Замятин» the longest is the
        // patronymic, and every writer with one would then be filed under «Иванович».
        val surname = words.last()
        val initials = words.dropLast(1).map { it.first() }.sorted().joinToString("")
        return if (initials.isEmpty()) null else "$surname $initials"
    }

    /**
     * The surname alone — how two records are decided to be about the same writer.
     *
     * Used where the question is "is this the same book I already have on the list" and for the
     * two-books-per-author cap, both of which the three catalogues would otherwise defeat by
     * spelling one name three ways. «Преступление и наказание» arrives from Google as by «Федор
     * Достоевский» and from Wikidata as by «Фёдор Михайлович Достоевский»; with the full name as
     * the key, that is two suggestions of the same novel and two different writers against the
     * cap.
     *
     * Loose enough to merge Лев and Алексей Толстой, which is deliberate at this end: the cost
     * is one Tolstoy suggestion fewer, where the cost of splitting is the same book listed twice.
     * Scoring still tells them apart — see [authorShortKey].
     */
    private fun authorGroupKey(author: String): String {
        val words = author.lowercase()
            .replace('ё', 'е')
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(" ", ",")
            .filter { it.isNotBlank() }
        return words.lastOrNull() ?: ""
    }
}
