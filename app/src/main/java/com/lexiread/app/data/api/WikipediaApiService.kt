package com.lexiread.app.data.api

import com.lexiread.app.data.model.WikipediaArticlesResponse
import com.lexiread.app.data.model.WikipediaParseResponse
import com.lexiread.app.data.model.WikipediaSearchResponse
import com.lexiread.app.data.model.WikipediaSummaryResponse
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Single Retrofit interface using @Url for full URL flexibility.
 * Allows switching between ru/en Wikipedia, Wiktionary, and other sources at runtime.
 */
interface WikipediaApiService {

    /** Search Wikipedia / Wiktionary — returns top matching article title */
    @GET
    suspend fun searchWikipedia(@Url url: String): WikipediaSearchResponse

    /** Get page summary — extract + thumbnail + URL */
    @GET
    suspend fun getPageSummary(@Url url: String): WikipediaSummaryResponse

    /**
     * Lead sections of several articles at once — `action=query&prop=extracts|pageimages`.
     *
     * Either for named titles or for the results of a search, depending on the URL: with
     * `generator=search` one request does the looking up and the reading both.
     */
    @GET
    suspend fun getArticles(@Url url: String): WikipediaArticlesResponse

    /**
     * One article, parsed — `action=parse`.
     *
     * Used for its table of contents and then for a single named section of it, which is how the
     * plot of a book is read without downloading the several hundred kilobytes of scholarship
     * that surround it.
     */
    @GET
    suspend fun getParse(@Url url: String): WikipediaParseResponse
}
