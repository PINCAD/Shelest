package com.lexiread.app.data.repository

import com.lexiread.app.data.api.DictionaryApiService
import com.lexiread.app.data.api.WikipediaApiService
import com.lexiread.app.data.db.DefinitionCacheDao
import com.lexiread.app.data.db.OfflineDictDao
import com.lexiread.app.data.model.DefinitionCache
import com.lexiread.app.data.model.DictionaryApiEntry
import com.lexiread.app.data.model.WikipediaSummaryResponse
import com.lexiread.app.data.model.WordDefinition
import com.lexiread.app.data.model.YandexDef
import com.lexiread.app.data.model.toWordDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Word lookup with offline-first fallback.
 *
 * Priority chain (stops at first non-empty result):
 *   1. Room cache (TTL = 7 days)
 *   2. Offline Room dictionary (bundled JSON) — exact match only
 *   3. ru.wiktionary.org (MediaWiki Action API) / dictionaryapi.dev (en)
 *   4. Яндекс.Словарь (ru/en), then en.wiktionary.org (en)
 *   5. ru.wikipedia.org  / en.wikipedia.org  (Википедия REST, encyclopedic last resort)
 *   6. en.wiktionary.org (only for Latin-script words typed while reading Russian text)
 *
 * Each candidate word is tried in order (exact form first, then at most one
 * guessed lemma) and capped to a small number of network round-trips so a
 * lookup resolves quickly instead of chaining through many slow sources.
 * Dictionary-API and Wiki lookups require an exact word/lemma match, so they
 * lean on the same candidate list produced by [getLemmaCandidates].
 */
class DefinitionRepository(
    private val api: WikipediaApiService,
    private val dictionaryApi: DictionaryApiService,
    private val yandexApiKey: String,
    private val cache: DefinitionCacheDao,
    private val offlineDao: OfflineDictDao
) {
    companion object {
        private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
        private const val MAX_CANDIDATES = 3
    }

    suspend fun getDefinition(word: String, language: String = "ru"): Result<WordDefinition> =
        withContext(Dispatchers.IO) {
            val cleaned = cleanWord(word)
            if (cleaned.isBlank()) return@withContext Result.failure(Exception("Пустое слово"))
            val key = "$language:${cleaned.lowercase()}"

            // ── 1. Cache hit ──────────────────────────────────────────────────
            // Wrapped because it sits outside the try below: a database that cannot answer
            // is a reason to go to the network, not a reason to throw out of the lookup and
            // take the caller's coroutine with it.
            val cached = runCatching { cache.get(key) }.getOrNull()
            if (cached != null && System.currentTimeMillis() - cached.cachedAt < CACHE_TTL_MS) {
                return@withContext Result.success(cached.toWordDefinition())
            }

            val candidates = getLemmaCandidates(cleaned, language)

            // ── 2. Offline Room dictionary — exact match only ───────────────────
            val offlineResult = lookupOffline(candidates)
            if (offlineResult != null) {
                // Don't cache offline hits — they're always available
                return@withContext Result.success(offlineResult)
            }

            // ── 3-5. Online sources ───────────────────────────────────────────
            try {
                var definition: WordDefinition? = null

                if (language == "ru") {
                    definition = definition ?: fetchWiktionary("https://ru.wiktionary.org", candidates)
                    definition = definition ?: fetchYandex("ru-ru", candidates)
                    definition = definition ?: fetchWikipedia("https://ru.wikipedia.org", candidates)
                    if (definition == null && cleaned.matches(Regex("[a-zA-Z]+"))) {
                        definition = fetchWiktionary("https://en.wiktionary.org", listOf(cleaned.lowercase()))
                    }
                } else {
                    definition = definition ?: fetchDictionaryApiDev(candidates)
                    definition = definition ?: fetchYandex("en-en", candidates)
                    definition = definition ?: fetchWiktionary("https://en.wiktionary.org", candidates)
                    definition = definition ?: fetchWikipedia("https://en.wikipedia.org", candidates)
                }

                if (definition == null) {
                    return@withContext Result.failure(Exception("Определение не найдено"))
                }

                cache.insert(DefinitionCache(
                    cacheKey     = key,
                    word         = cleaned,
                    language     = language,
                    title        = definition.title,
                    extract      = definition.extract,
                    description  = definition.description,
                    thumbnailUrl = definition.thumbnailUrl,
                    wikiUrl      = definition.wikiUrl
                ))
                cache.evictOld(System.currentTimeMillis() - CACHE_TTL_MS)

                Result.success(definition)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── Offline lookup — exact match only, no loose prefix guessing ───────────

    private suspend fun lookupOffline(candidates: List<String>): WordDefinition? {
        for (cand in candidates) {
            val entry = runCatching { offlineDao.getExact(cand.lowercase()) }.getOrNull() ?: continue
            val pos = if (entry.partOfSpeech.isNotBlank()) "[${entry.partOfSpeech}] " else ""
            return WordDefinition(
                word        = entry.word,
                title       = entry.word.replaceFirstChar { it.uppercase() },
                extract     = "$pos${entry.definition}",
                description = "Офлайн словарь",
                wikiUrl     = null,
                fromCache   = false
            )
        }
        return null
    }

    // ── Яндекс.Словарь — exact-form lexical lookup, no redirect risk ──────────

    private suspend fun fetchYandex(langPair: String, candidates: List<String>): WordDefinition? {
        for (cand in candidates.take(MAX_CANDIDATES)) {
            val resp = try {
                val enc = URLEncoder.encode(cand, "UTF-8").replace("+", "%20")
                dictionaryApi.yandexLookup(
                    "https://dictionary.yandex.net/api/v1/dicservice.json/lookup" +
                        "?key=$yandexApiKey&lang=$langPair&text=$enc"
                )
            } catch (_: Exception) { null } ?: continue

            if (resp.def.isEmpty()) continue
            val extract = resp.def.joinToString("\n") { formatYandexSense(it) }
                .lines().filter { it.isNotBlank() }.joinToString("\n")
            if (extract.isBlank()) continue

            return WordDefinition(
                word        = cand,
                title       = resp.def.first().text?.replaceFirstChar { it.uppercase() } ?: cand,
                extract     = extract,
                description = "Яндекс.Словарь",
                wikiUrl     = null
            )
        }
        return null
    }

    private fun formatYandexSense(def: YandexDef): String {
        val words = def.tr.flatMap { tr ->
            buildList {
                tr.text?.let { add(it) }
                tr.syn?.forEach { it.text?.let(::add) }
            }
        }.distinct().take(8)
        if (words.isEmpty()) return ""
        val pos = posAbbrev(def.pos)
        return if (pos.isNotBlank()) "$pos ${words.joinToString(", ")}" else words.joinToString(", ")
    }

    // ── dictionaryapi.dev — English explanatory dictionary, exact form only ───

    private suspend fun fetchDictionaryApiDev(candidates: List<String>): WordDefinition? {
        for (cand in candidates.take(MAX_CANDIDATES)) {
            val entries = try {
                val enc = URLEncoder.encode(cand, "UTF-8")
                dictionaryApi.dictionaryApiDev("https://api.dictionaryapi.dev/api/v2/entries/en/$enc")
            } catch (_: Exception) { null }

            if (entries.isNullOrEmpty()) continue
            val extract = formatDictionaryApiExtract(entries)
            if (extract.isBlank()) continue

            return WordDefinition(
                word        = cand,
                title       = (entries.first().word ?: cand).replaceFirstChar { it.uppercase() },
                extract     = extract,
                description = "Dictionary API",
                wikiUrl     = "https://en.wiktionary.org/wiki/${URLEncoder.encode(cand, "UTF-8")}"
            )
        }
        return null
    }

    private fun formatDictionaryApiExtract(entries: List<DictionaryApiEntry>): String =
        entries.asSequence()
            .flatMap { it.meanings.asSequence() }
            .filter { it.definitions.isNotEmpty() }
            .take(4)
            .joinToString("\n\n") { meaning ->
                val pos = posAbbrev(meaning.partOfSpeech)
                val defs = meaning.definitions.take(3)
                    .mapNotNull { it.definition }
                    .mapIndexed { i, d -> "${i + 1}. $d" }
                (if (pos.isNotBlank()) "$pos\n" else "") + defs.joinToString("\n")
            }

    private fun posAbbrev(pos: String?): String = when (pos?.lowercase()) {
        "noun"          -> "сущ."
        "verb"          -> "гл."
        "adjective"     -> "прил."
        "adverb"        -> "нареч."
        "pronoun"       -> "мест."
        "preposition"   -> "предл."
        "conjunction"   -> "союз"
        "interjection"  -> "межд."
        "numeral"       -> "числ."
        "particle"      -> "част."
        else            -> ""
    }

    // ── Викисловарь / MediaWiki Action API ────────────────────────────────────

    private suspend fun fetchWiktionary(base: String, candidates: List<String>): WordDefinition? {
        for (cand in candidates.take(MAX_CANDIDATES)) {
            val encoded = URLEncoder.encode(cand, "UTF-8").replace("+", "%20")
            val response = try {
                api.getArticles(
                    "$base/w/api.php?action=query&prop=extracts&explaintext=1" +
                        "&titles=$encoded&format=json&redirects=1"
                )
            } catch (_: Exception) { null } ?: continue
            val page = response.query?.pages?.values?.firstOrNull() ?: continue
            val title = page.title ?: continue
            // Accept only the requested title or an explicit Wiktionary redirect
            // from that exact inflected form (e.g. книги → книга).
            val exactTitle = cand.equals(title, ignoreCase = true)
            val verifiedRedirect = response.query.redirects.orEmpty().any {
                it.from.orEmpty().equals(cand, ignoreCase = true) &&
                    it.to.orEmpty().equals(title, ignoreCase = true)
            }
            if (!exactTitle && !verifiedRedirect) continue
            val language = if (base.contains("ru.wiktionary")) "Русский" else "English"
            val extract = WiktionaryDefinitionParser.parse(page.extract.orEmpty(), language)
            if (extract.isNullOrBlank()) continue
            return WordDefinition(
                word        = cand,
                title       = title.replaceFirstChar { it.uppercase() },
                extract     = extract,
                description = "Викисловарь",
                wikiUrl     = "$base/wiki/${URLEncoder.encode(title, "UTF-8").replace("+", "%20")}"
            )
        }
        return null
    }

    // ── Википедия / Wikipedia REST ────────────────────────────────────────────

    private suspend fun fetchWikipedia(base: String, candidates: List<String>): WordDefinition? {
        for (cand in candidates.take(MAX_CANDIDATES)) {
            val summary = fetchSummaryDirect(base, cand) ?: continue
            val extract = summary.extract.orEmpty()
            if (extract.length < 10) continue
            if (!titleMatchesCandidates(summary.title ?: cand, candidates)) continue
            return WordDefinition(
                word        = cand,
                title       = summary.title ?: cand,
                extract     = extract,
                description = summary.description ?: "Википедия",
                thumbnailUrl = summary.thumbnail?.source,
                wikiUrl     = summary.contentUrls?.mobile?.page
            )
        }
        val searchResult = fetchSummarySearch("$base/w/api.php", candidates, base)
        return searchResult?.let {
            WordDefinition(
                word        = candidates.first(),
                title       = it.title ?: candidates.first(),
                extract     = it.extract.orEmpty(),
                description = it.description ?: "Википедия",
                thumbnailUrl = it.thumbnail?.source,
                wikiUrl     = it.contentUrls?.mobile?.page
            )
        }
    }

    // ── Shared HTTP helpers ───────────────────────────────────────────────────

    private suspend fun fetchSummaryDirect(base: String, word: String): WikipediaSummaryResponse? =
        try {
            val enc = URLEncoder.encode(word, "UTF-8").replace("+", "%20")
            api.getPageSummary("$base/api/rest_v1/page/summary/$enc")
                .takeIf { !it.extract.isNullOrBlank() }
        } catch (_: Exception) { null }

    /**
     * Falls back to the site's own search-relevance ranking when a direct exact
     * title lookup misses (e.g. because our lemma guess was wrong). Still
     * requires the result's title to plausibly match one of our candidates —
     * a raw top search hit is often just a page that *mentions* the word, not
     * the word's own article, so accepting it unchecked reintroduces the
     * "wrong word" bug this whole matching step exists to prevent.
     */
    private suspend fun fetchSummarySearch(
        searchBase: String,
        candidates: List<String>,
        summaryBase: String
    ): WikipediaSummaryResponse? {
        val word = candidates.firstOrNull() ?: return null
        return try {
            val enc = URLEncoder.encode(word, "UTF-8").replace("+", "%20")
            val resp = api.searchWikipedia(
                "$searchBase?action=query&list=search&srsearch=$enc&format=json&srlimit=5&origin=*"
            )
            val titles = resp.query?.search?.mapNotNull { it.title } ?: return null
            titles.firstNotNullOfOrNull { title ->
                if (!titleMatchesCandidates(title, candidates)) return@firstNotNullOfOrNull null
                fetchSummaryDirect(summaryBase, title)
                    ?.takeIf { it.extract.orEmpty().length >= 10 }
            }
        } catch (_: Exception) { null }
    }

    /**
     * True when [title] plausibly refers to the same word as one of [candidates] —
     * either an exact match or a shared word-stem prefix. Guards against
     * redirects/search hits that land on a different (if related) topic, which
     * is the concrete cause of definitions being shown for the wrong word.
     */
    private fun titleMatchesCandidates(title: String, candidates: List<String>): Boolean {
        val normTitle = normalizeForMatch(title)
        if (normTitle.isBlank()) return false
        return candidates.any { cand ->
            val normCand = normalizeForMatch(cand)
            if (normCand.isBlank()) return@any false
            val prefixLen = minOf(4, normTitle.length, normCand.length)
            normTitle == normCand ||
                normTitle.startsWith(normCand.take(prefixLen)) ||
                normCand.startsWith(normTitle.take(prefixLen))
        }
    }

    private fun normalizeForMatch(s: String): String =
        s.lowercase().trim().substringBefore(' ').substringBefore('(').filter { it.isLetter() }

    // ── Lemmatisation — exact word first, at most one guessed base form ───────

    private fun getLemmaCandidates(cleaned: String, language: String): List<String> {
        val lower = cleaned.lowercase()
        val candidates = linkedSetOf(lower)
        if (cleaned != lower) candidates.add(cleaned)

        val lemma = if (language == "ru") bestRussianLemma(lower) else bestEnglishLemma(lower)
        if (lemma != null && lemma != lower) candidates.add(lemma)

        return candidates.filter { it.isNotBlank() && it.length >= 2 }.toList()
    }

    /** Single best-guess dictionary form for a Russian inflected word, or null if unclear. */
    private fun bestRussianLemma(word: String): String? {
        val adjSuffixes = listOf(
            "ого", "его", "ому", "ему", "ыми", "ими", "ая", "яя", "ое", "ее",
            "ые", "ие", "ых", "их", "ым", "им", "ую", "юю", "ой", "ом", "ем"
        )
        val verbSuffixes = listOf(
            "ается", "ился", "илась", "илось", "ились", "ется", "ются", "ится",
            "ают", "яют", "ует", "ют", "ит", "ят", "ем", "им", "ал", "ала", "ало", "али", "ет"
        )
        val nounSuffixes = listOf(
            "ами", "ями", "ах", "ях", "ов", "ев", "ей", "ам", "ям",
            "ом", "ем", "а", "я", "ы", "и", "у", "ю", "е"
        )

        adjSuffixes.sortedByDescending { it.length }
            .firstOrNull { word.endsWith(it) && word.length > it.length + 2 }
            ?.let { return word.dropLast(it.length) + "ый" }

        verbSuffixes.sortedByDescending { it.length }
            .firstOrNull { word.endsWith(it) && word.length > it.length + 2 }
            ?.let { return word.dropLast(it.length) + "ть" }

        nounSuffixes.sortedByDescending { it.length }
            .firstOrNull { word.endsWith(it) && word.length > it.length + 2 }
            ?.let { return word.dropLast(it.length) }

        return null
    }

    /** Single best-guess base form for an English inflected word, or null if unclear. */
    private fun bestEnglishLemma(word: String): String? = when {
        word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
        word.endsWith("es") && word.length > 4  -> word.dropLast(2)
        word.endsWith("s") && word.length > 3   -> word.dropLast(1)
        word.endsWith("ing") && word.length > 5 -> word.dropLast(3)
        word.endsWith("ed") && word.length > 4  -> word.dropLast(2)
        word.endsWith("ly") && word.length > 4  -> word.dropLast(2)
        else -> null
    }

    private fun cleanWord(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("""[\[\(<\{]\d+[\]\)>\}]"""), "")
        s = s.replace(Regex("""\d+$"""), "")
        return s.trimStart { !it.isLetter() }.trimEnd { !it.isLetter() }
    }
}
