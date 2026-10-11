package com.lexiread.app.data.api

import com.lexiread.app.data.model.GoogleBooksResponse
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Google Books search. Optional: nothing calls this unless the reader has pasted an API key
 * into the settings, because anonymous requests are refused outright.
 */
interface GoogleBooksApiService {
    @GET
    suspend fun search(@Url url: String): GoogleBooksResponse
}
