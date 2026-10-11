package com.lexiread.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * A SPARQL result set, in the shape the query service returns it.
 *
 * Every row is a map from the variable names in the SELECT to a wrapped value, so the parser
 * cannot be typed any tighter than this without a class per query. `?workLabel` for a variable
 * `?work` is not a column we asked for by name — the label service invents it — which is one
 * more reason the row stays a map.
 */
data class SparqlResponse(
    @SerializedName("results") val results: SparqlResults? = null
)

data class SparqlResults(
    @SerializedName("bindings") val bindings: List<Map<String, SparqlValue>>? = null
)

data class SparqlValue(
    @SerializedName("value") val value: String? = null
)

/** The reply to `action=wbsearchentities` — free text in, catalogue ids out. */
data class WikidataSearchResponse(
    @SerializedName("search") val search: List<WikidataSearchHit>? = null
)

data class WikidataSearchHit(
    /** The item id, e.g. `Q189474`. */
    @SerializedName("id") val id: String? = null,
    @SerializedName("label") val label: String? = null,
    /**
     * The one line that separates «Солярис» the novel from «Солярис» the film: asked for in
     * Russian, it comes back as «роман Станислава Лема» or «фильм Андрея Тарковского».
     */
    @SerializedName("description") val description: String? = null
)
