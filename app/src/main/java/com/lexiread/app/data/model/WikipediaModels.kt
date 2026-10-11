package com.lexiread.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

// ── Wikipedia REST API response ───────────────────────────────────────────────

data class WikipediaSummaryResponse(
    val title: String? = null,
    val extract: String? = null,
    val description: String? = null,
    val thumbnail: WikipediaThumbnail? = null,
    @SerializedName("content_urls")
    val contentUrls: WikipediaContentUrls? = null
)

data class WikipediaThumbnail(
    val source: String,
    val width: Int,
    val height: Int
)

data class WikipediaContentUrls(
    val mobile: WikipediaUrl? = null,
    val desktop: WikipediaUrl? = null
)

data class WikipediaUrl(val page: String)

data class WikipediaSearchResponse(
    val query: WikipediaSearchQuery? = null
)

data class WikipediaSearchQuery(
    val search: List<WikipediaSearchResult>? = null
)

data class WikipediaSearchResult(
    val title: String? = null,
    val snippet: String? = null,
    val pageid: Int = 0
)

// ── Wikipedia action API — article leads ──────────────────────────────────────

/**
 * `action=query&prop=extracts|pageimages`, used to tell a reader what a book is about.
 *
 * The REST summary endpoint above answers with one truncated paragraph for one title at a time;
 * this one answers with the whole lead section for up to three titles at once, and can take a
 * search as its list of titles. That matters because the title alone is ambiguous — «Мы» is a
 * novel, two films and a song — so the answer has to arrive with enough text to tell which is
 * which. See [com.lexiread.app.data.repository.BookSynopsisRepository].
 */
data class WikipediaArticlesResponse(
    val query: WikipediaArticlesQuery? = null
)

/** Keyed by page id, which is why this is a map and not a list. */
data class WikipediaArticlesQuery(
    val pages: Map<String, WikipediaArticle>? = null,
    /** Wiktionary uses redirects from inflected forms (for example книги → книга). */
    val redirects: List<WikipediaRedirect>? = null
)

data class WikipediaRedirect(
    val from: String? = null,
    val to: String? = null
)

data class WikipediaArticle(
    /** Rank the search gave this page. Absent — and irrelevant — when titles were asked for. */
    val index: Int = 0,
    /** The parser addresses pages by this, and it needs no encoding. See [WikipediaParse]. */
    val pageid: Int = 0,
    val title: String? = null,
    /** The lead section as plain text: `explaintext=1&exintro=1`. */
    val extract: String? = null,
    val thumbnail: WikipediaThumbnail? = null,
    /** Absent unless the page carries one of the properties that was asked for. */
    val pageprops: WikipediaPageProps? = null
)

/**
 * `prop=pageprops&ppprop=disambiguation` — MediaWiki's own answer to "is this a real article".
 *
 * Worth the two words it costs in the URL. A disambiguation page is titled exactly like the book
 * and its text is a list of links whose first line reads «„Зелёная миля“ — роман Стивена Кинга»,
 * so every heuristic there is takes it for the article about the novel.
 */
data class WikipediaPageProps(
    /** Present, and an empty string, when the page is one. Absent otherwise. */
    val disambiguation: String? = null
)

/**
 * `action=parse` — one section of one article, rather than the lead of several.
 *
 * The lead of a book article is a publication record: who wrote it, when it came out, in which
 * journal, in how many parts. The section headed «Сюжет» is the thing a reader wanted — what
 * happens in the book — and it is the only part of the article written like the back of a
 * paperback. Reaching it costs two requests, because a section can only be asked for by number
 * and the numbering differs per article: «Сюжет» is section 1 of «Мы» and section 5 of
 * «Преступление и наказание». See [com.lexiread.app.data.repository.BookSynopsisRepository].
 */
data class WikipediaParseResponse(
    val parse: WikipediaParse? = null
)

data class WikipediaParse(
    val title: String? = null,
    /** Present for `prop=sections` — the table of contents, and nothing else. */
    val sections: List<WikipediaSection>? = null,
    /** Present for `prop=text` — the rendered HTML of the section that was asked for. */
    val text: WikipediaHtml? = null
)

data class WikipediaSection(
    /**
     * What `&section=` takes. A string and not a number: transcluded sections are numbered
     * `T-1`, and Gson would refuse the whole response over one of them.
     */
    val index: String? = null,
    /** The heading as printed — «Сюжет», «Краткое содержание». */
    val line: String? = null
)

/** The action API returns rendered HTML under the key `*`. */
data class WikipediaHtml(
    @SerializedName("*") val html: String? = null
)

// ── Room entity — cached definitions ─────────────────────────────────────────

@Entity(tableName = "definition_cache")
data class DefinitionCache(
    @PrimaryKey
    val cacheKey: String,           // "${language}:${word.lowercase()}"
    val word: String,
    val language: String,
    val title: String,
    val extract: String,
    val description: String?,
    val thumbnailUrl: String?,
    val wikiUrl: String?,
    val cachedAt: Long = System.currentTimeMillis()
)

// ── Room entity — cached synopses ────────────────────────────────────────────

/**
 * What a suggested book is about, kept between runs of the app.
 *
 * The same reasoning as [DefinitionCache], and a heavier bill: a definition is one request and a
 * synopsis is five, or fifteen for a book neither search engine recognises. The suggestion list is
 * built from the reader's own shelf and changes only when they mark another book, so the same
 * dozen cards come back day after day — and used to be looked up from scratch every time the app
 * was started.
 *
 * Keyed by the catalogue's own id, which is what the suggestion carries and is stable per record.
 *
 * @param aboutAuthor whether [text] is about the writer rather than the book — see
 *        [com.lexiread.app.data.repository.BookSynopsisRepository.Synopsis].
 */
@Entity(tableName = "synopsis_cache")
data class SynopsisCache(
    @PrimaryKey
    val bookId: String,
    val text: String,
    val credit: String,
    val coverUrl: String?,
    val aboutAuthor: Boolean,
    val cachedAt: Long = System.currentTimeMillis()
)

// ── UI model ──────────────────────────────────────────────────────────────────

data class WordDefinition(
    val word: String,
    val title: String,
    val extract: String,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val wikiUrl: String? = null,
    val fromCache: Boolean = false
)

fun DefinitionCache.toWordDefinition() = WordDefinition(
    word         = word,
    title        = title,
    extract      = extract,
    description  = description,
    thumbnailUrl = thumbnailUrl,
    wikiUrl      = wikiUrl,
    fromCache    = true
)
