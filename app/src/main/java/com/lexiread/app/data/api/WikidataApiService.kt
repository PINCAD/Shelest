package com.lexiread.app.data.api

import com.lexiread.app.data.model.SparqlResponse
import com.lexiread.app.data.model.WikidataSearchResponse
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

/**
 * Wikidata — the only catalogue here that knows books by their Russian names.
 *
 * Open Library files «Мастер и Маргарита» under *The Master and Margarita* and answers with
 * whichever edition it happens to hold; Wikidata holds one item per work with a label in every
 * language, so asking for Russian gets Russian rather than a Russian edition of an English
 * record. It has no covers and no ratings, which is why it supplements the others instead of
 * replacing them.
 *
 * Two endpoints, deliberately: `wbsearchentities` turns free text the reader typed into item
 * ids in a couple of hundred milliseconds, and the query service answers in ids only.
 */
interface WikidataApiService {

    /**
     * The SPARQL endpoint. Without the Accept header it serves XML, which Gson cannot read.
     *
     * Slow queries here are not slow by a little: sorting a genre by popularity takes fourteen
     * seconds while filtering by it takes one, and the service starts refusing with 502 if
     * several arrive at once. Keep the queries few, batched, and one at a time.
     */
    @GET
    @Headers("Accept: application/sparql-results+json")
    suspend fun sparql(@Url url: String): SparqlResponse

    /** The ordinary MediaWiki API, which is tolerant of parallel calls in a way SPARQL is not. */
    @GET
    suspend fun searchEntities(@Url url: String): WikidataSearchResponse
}
