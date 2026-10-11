package com.lexiread.app.data.api

import com.lexiread.app.data.model.OpenLibrarySearchResponse
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Open Library search.
 *
 * Chosen over Google Books because it still answers anonymous requests: Google's Books API now
 * returns 429 with `quota_limit_value: 0` for every keyless call, so the previous catalogue had
 * stopped working entirely rather than merely working badly.
 *
 * The whole URL is built by the caller, as everywhere else in this app — the query is assembled
 * from several optional parameters and building it in one place beats spreading it across
 * annotations.
 */
interface OpenLibraryApiService {
    @GET
    suspend fun search(@Url url: String): OpenLibrarySearchResponse
}
