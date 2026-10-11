package com.lexiread.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Google Books volumes.
 *
 * Only ever reached when the reader has supplied their own API key: without one the service
 * answers every request, even `?q=test`, with 429 and `quota_limit_value: 0`. With a key it is
 * free, and it is the best of the three sources at Russian editions of foreign novels.
 */
data class GoogleBooksResponse(
    @SerializedName("totalItems") val totalItems: Int? = null,
    @SerializedName("items") val items: List<GoogleBookItem>? = null
)

data class GoogleBookItem(
    @SerializedName("id") val id: String? = null,
    @SerializedName("volumeInfo") val volumeInfo: GoogleVolumeInfo? = null
)

data class GoogleVolumeInfo(
    @SerializedName("title") val title: String? = null,
    @SerializedName("authors") val authors: List<String>? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("categories") val categories: List<String>? = null,
    /** A bare year, a year-month, or a full ISO date, depending on the record. */
    @SerializedName("publishedDate") val publishedDate: String? = null,
    @SerializedName("pageCount") val pageCount: Int? = null,
    @SerializedName("averageRating") val averageRating: Double? = null,
    @SerializedName("ratingsCount") val ratingsCount: Int? = null,
    @SerializedName("imageLinks") val imageLinks: GoogleImageLinks? = null,
    @SerializedName("infoLink") val infoLink: String? = null,
    @SerializedName("language") val language: String? = null
)

data class GoogleImageLinks(
    /** Served over plain http, which the app does not allow — see the repository. */
    @SerializedName("thumbnail") val thumbnail: String? = null,
    @SerializedName("smallThumbnail") val smallThumbnail: String? = null
)
