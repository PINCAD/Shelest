package com.lexiread.app.data.api

import com.lexiread.app.data.model.FantLabAuthor
import com.lexiread.app.data.model.FantLabAuthorMatch
import com.lexiread.app.data.model.FantLabMatch
import com.lexiread.app.data.model.FantLabWork
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Fantlab's open API — `api.fantlab.ru`. Two calls: find the work, then read its blurb.
 *
 * See [com.lexiread.app.data.model.FantLabMatch] for why this source is here at all.
 */
interface FantLabApiService {

    /**
     * `search-works?q=…` — titles, authors and forms, about 25 KB of them.
     *
     * A list rather than a wrapper: with `onlymatches=1` the answer is a bare JSON array.
     */
    @GET
    suspend fun searchWorks(@Url url: String): List<FantLabMatch>

    /** `work/<id>` — the blurb, and under 2 KB with it. */
    @GET
    suspend fun getWork(@Url url: String): FantLabWork

    /**
     * `search-autors?q=…` — the same bare array, half a kilobyte of it.
     *
     * Asked only when nothing at all could be found about the book itself. There is no endpoint
     * for an author's works — `alltitles`, `works` and `titles` all answer 404 and `?showworks=1`
     * answers with the fields empty — so this cannot be used to find the book by another route.
     * It is here for [com.lexiread.app.data.model.FantLabAuthor].
     */
    @GET
    suspend fun searchAuthors(@Url url: String): List<FantLabAuthorMatch>

    /** `autor/<id>` — who the writer was, when there is nothing to be had about what they wrote. */
    @GET
    suspend fun getAuthor(@Url url: String): FantLabAuthor
}
