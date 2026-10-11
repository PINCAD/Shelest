package com.lexiread.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Open Library's search endpoint (`openlibrary.org/search.json`).
 *
 * Every field is nullable: the response is shaped by the `fields=` projection the caller asks
 * for, and a work with no cover, no ratings and no subjects is a perfectly ordinary record
 * rather than an error.
 */
data class OpenLibrarySearchResponse(
    @SerializedName("numFound") val numFound: Int? = null,
    @SerializedName("docs") val docs: List<OpenLibraryDoc>? = null
)

/**
 * One *work*, not one edition — [key] is `/works/OL166952W`, and the ratings, cover and page
 * count are aggregated across every edition of it. That is what makes this endpoint usable for
 * recommendations: five printings of the same novel arrive as one row.
 */
data class OpenLibraryDoc(
    @SerializedName("key") val key: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("author_name") val authorName: List<String>? = null,
    @SerializedName("first_publish_year") val firstPublishYear: Int? = null,
    @SerializedName("subject") val subject: List<String>? = null,
    /** Cover id; the image URL is built from it, there is no URL in the response. */
    @SerializedName("cover_i") val coverId: Long? = null,
    @SerializedName("ratings_average") val ratingsAverage: Double? = null,
    @SerializedName("ratings_count") val ratingsCount: Int? = null,
    @SerializedName("number_of_pages_median") val pagesMedian: Int? = null,
    /**
     * A single edition of this work, matching whatever filters the search itself carried — ask
     * with `language=rus` and this is a Russian edition, which is where the Russian title of a
     * foreign novel comes from. Only ever one, whatever limit is requested.
     */
    @SerializedName("editions") val editions: OpenLibraryEditions? = null
)

data class OpenLibraryEditions(
    @SerializedName("docs") val docs: List<OpenLibraryEdition>? = null
)

data class OpenLibraryEdition(
    @SerializedName("key") val key: String? = null,
    @SerializedName("title") val title: String? = null
)
