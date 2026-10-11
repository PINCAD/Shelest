package com.lexiread.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Fantlab — a Russian readers' encyclopaedia, and the only free source of a Russian blurb.
 *
 * Its `work_description` is the publisher's own back-cover copy, which is exactly what the sheet
 * is trying to show and what neither of the other two sources has: Google's Russian editions of
 * the classics carry no description at all (nineteen of twenty records for «Анна Каренина» have
 * an empty one), and Wikipedia has a plot summary, which is a different thing and gives away the
 * ending. See [com.lexiread.app.data.repository.BookSynopsisRepository].
 *
 * The API is open, needs no key, and answers in about 90 ms.
 *
 * One search hit. `search-works` with `onlymatches=1` answers with a bare array of these and no
 * envelope around it, which is why [com.lexiread.app.data.api.FantLabApiService] asks for a list.
 */
data class FantLabMatch(
    /** The work id. Named `doc` in the answer, and what [FantLabWork] is asked for. */
    @SerializedName("doc") val id: Int = 0,
    /** The title. Padded with spaces at both ends in every record the site returns. */
    val fullname: String? = null,
    /** Everyone credited, in Russian, separated by commas. */
    @SerializedName("all_autor_rusname") val authors: String? = null,
    /** The first author alone, for the records where the combined field is empty. */
    @SerializedName("autor_rusname") val author: String? = null,
    /**
     * The form, in Russian: «роман», «повесть», «рассказ» — and also «статья» and «эссе», which
     * is why it is read. Searching for «Улитка на склоне» offers a lecture about the novella
     * above the novella itself.
     */
    @SerializedName("name_show_im") val form: String? = null
)

/**
 * One author search hit, from `search-autors` — the same bare array as [FantLabMatch].
 *
 * Read for one thing: turning a name into an id. It answers to either spelling, which matters
 * because Open Library credits a translated writer in Latin and the shelf credits them in Russian.
 */
data class FantLabAuthorMatch(
    @SerializedName("autor_id") val id: Int = 0,
    /** Every spelling of the name at once — « Фрэнк Герберт Frank Herbert». What is matched. */
    val fullname: String? = null
)

/**
 * An author's page — about a kilobyte, of which the part worth having is [anons].
 *
 * This is the answer of last resort for the sheet. It is not what the book is about, and it is
 * not offered as if it were; it is the one thing that can always be found out about a suggestion
 * whose title no catalogue and no encyclopaedia recognises.
 */
data class FantLabAuthor(
    /** In Russian wherever the site has a Russian form — for a translated writer it always does. */
    val name: String? = null,
    /** The short life-and-work note at the top of the page, plain text, three or four sentences. */
    val anons: String? = null
)

data class FantLabWork(
    @SerializedName("work_name") val title: String? = null,
    /** The blurb. Plain text as a rule, with the occasional `<br>` or `<i>` left in it. */
    @SerializedName("work_description") val description: String? = null,
    /**
     * The cover of a Russian edition, as a path with no host — `/images/editions/big/266824`.
     *
     * Worth having because half the suggestions arrive with no cover at all: Wikidata carries
     * none, and that is where the genre-matched books come from.
     */
    val image: String? = null
)
