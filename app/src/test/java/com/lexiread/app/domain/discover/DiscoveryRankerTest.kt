package com.lexiread.app.domain.discover

import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The searching cannot be tested here — it needs a live catalogue — but the deciding can, and
 * the deciding is what the reader sees. Everything guarded below is a rule that fails silently
 * when a weight moves: a book already on the shelf coming back as a discovery, one author
 * taking the whole list, four editions of the same novel in a row, or a candidate that matched
 * nothing but arrived first from the catalogue.
 */
class DiscoveryRankerTest {

    private fun shelf(id: Long, title: String, author: String = "") = Book(
        id       = id,
        title    = title,
        author   = author,
        filePath = "/books/$id.epub",
        fileType = FileType.EPUB
    )

    private fun found(
        id: String,
        title: String,
        authors: List<String> = listOf("Некто Иной"),
        categories: List<String> = emptyList(),
        rating: Double? = null,
        ratingsCount: Int = 0,
        cover: String? = "https://example/$id.jpg",
        description: String = "Описание"
    ) = DiscoveredBook(
        id            = id,
        title         = title,
        authors       = authors,
        description   = description,
        categories    = categories,
        thumbnailUrl  = cover,
        averageRating = rating,
        ratingsCount  = ratingsCount,
        infoLink      = "https://example/$id"
    )

    private val solaris = TasteEntry(
        likedTitle = "Солярис",
        authors    = listOf("Станислав Лем"),
        categories = listOf("Fiction / Science Fiction / Space Opera")
    )

    @Test
    fun `nothing liked means nothing suggested`() {
        val result = DiscoveryRanker.rank(
            profile    = emptyList(),
            candidates = listOf(found("1", "Что угодно")),
            owned      = emptyList()
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a book already on the shelf is never suggested`() {
        val result = DiscoveryRanker.rank(
            profile    = listOf(solaris),
            candidates = listOf(found("1", "Непобедимый", authors = listOf("Станислав Лем"))),
            owned      = listOf(shelf(1, "Непобедимый", "Станислав Лем"))
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `shelf titles carry edition noise and still block their book`() {
        // The file is "Война и мир. Том 1"; the catalogue calls it "Война и мир". An exact
        // comparison would offer the reader a book they are holding.
        val result = DiscoveryRanker.rank(
            profile    = listOf(solaris),
            candidates = listOf(found("1", "Война и мир", authors = listOf("Станислав Лем"))),
            owned      = listOf(shelf(1, "Война и мир. Том 1"))
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `the liked books themselves are not handed back`() {
        val result = DiscoveryRanker.rank(
            profile    = listOf(solaris),
            candidates = listOf(found("1", "Солярис", authors = listOf("Станислав Лем"))),
            owned      = emptyList()
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a candidate connected to nothing is dropped however it was found`() {
        // Returned by a subject query, shares neither author nor any meaningful genre.
        val stranger = found("1", "Пособие по бухучёту", categories = listOf("Business & Economics"))
        val result = DiscoveryRanker.rank(listOf(solaris), listOf(stranger), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a genre every novel carries is not a connection`() {
        val vague = found("1", "Какой-то роман", categories = listOf("Fiction", "General"))
        val result = DiscoveryRanker.rank(listOf(solaris), listOf(vague), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `the same author beats the same shelf`() {
        val sameAuthor = found("1", "Непобедимый", authors = listOf("Станислав Лем"))
        val sameGenre = found(
            "2", "Гиперион",
            authors    = listOf("Дэн Симмонс"),
            categories = listOf("Fiction / Science Fiction / Space Opera")
        )
        val result = DiscoveryRanker.rank(listOf(solaris), listOf(sameAuthor, sameGenre), emptyList())

        assertEquals("1", result.first().book.id)
        assertEquals("Тот же автор, что и «Солярис»", result.first().reason)
        assertEquals("Похоже на «Солярис»", result.first { it.book.id == "2" }.reason)
    }

    @Test
    fun `an author spelled the other way round is still the same author`() {
        val entry = TasteEntry("Девушка с татуировкой", listOf("Стиг Ларссон"), emptyList())
        val result = DiscoveryRanker.rank(
            profile    = listOf(entry),
            candidates = listOf(found("1", "Девушка, которая играла с огнём", authors = listOf("Ларссон, Стиг"))),
            owned      = emptyList()
        )
        assertEquals(1, result.size)
        assertEquals("Тот же автор, что и «Девушка с татуировкой»", result.first().reason)
    }

    @Test
    fun `editions of one book collapse into one suggestion`() {
        // Every query returns the same novel under a different volume id.
        val editions = listOf(
            found("a", "Непобедимый", authors = listOf("Станислав Лем")),
            found("b", "Непобедимый!", authors = listOf("Станислав Лем")),
            found("c", "непобедимый", authors = listOf("Станислав Лем"))
        )
        val result = DiscoveryRanker.rank(listOf(solaris), editions, emptyList())
        assertEquals(1, result.size)
    }

    @Test
    fun `sparse catalogue record does not hide a relevant edition`() {
        val sparse = found("a", "Гиперион", authors = listOf("Дэн Симмонс"), categories = emptyList())
        val tagged = found(
            "b", "Гиперион", authors = listOf("Дэн Симмонс"),
            categories = listOf("Fiction / Science Fiction / Space Opera")
        )
        val result = DiscoveryRanker.rank(listOf(solaris), listOf(sparse, tagged), emptyList())
        assertEquals(listOf("b"), result.map { it.book.id })
    }

    @Test
    fun `one author cannot take over the list`() {
        val many = (1..5).map { found("$it", "Книга $it", authors = listOf("Станислав Лем")) }
        val result = DiscoveryRanker.rank(listOf(solaris), many, emptyList())
        assertEquals(2, result.size)
    }

    @Test
    fun `among equals the better rated book comes first`() {
        val genre = listOf("Fiction / Science Fiction / Space Opera")
        val loved = found("1", "Гиперион", listOf("Дэн Симмонс"), genre, rating = 4.8, ratingsCount = 900)
        val unrated = found("2", "Эндимион", listOf("Другой Автор"), genre)
        val result = DiscoveryRanker.rank(listOf(solaris), listOf(unrated, loved), emptyList())

        assertEquals("1", result.first().book.id)
    }

    @Test
    fun `a single glowing review does not outweigh a shelf full of them`() {
        val genre = listOf("Fiction / Science Fiction / Space Opera")
        val onePerfectReview = found("1", "Никому не известное", listOf("А"), genre, rating = 5.0, ratingsCount = 1)
        val widelyLiked = found("2", "Гиперион", listOf("Дэн Симмонс"), genre, rating = 4.4, ratingsCount = 800)
        val result = DiscoveryRanker.rank(listOf(solaris), listOf(onePerfectReview, widelyLiked), emptyList())

        assertEquals("2", result.first().book.id)
    }

    @Test
    fun `the suggestion names the liked book it came from`() {
        val detective = TasteEntry("Собака Баскервилей", listOf("Артур Конан Дойл"), listOf("Fiction / Mystery & Detective"))
        val result = DiscoveryRanker.rank(
            profile    = listOf(solaris, detective),
            candidates = listOf(found("1", "Лунный камень", listOf("Уилки Коллинз"), listOf("Fiction / Mystery & Detective"))),
            owned      = emptyList()
        )
        assertEquals("Похоже на «Собака Баскервилей»", result.first().reason)
    }

    @Test
    fun `the list stays a list, not a second library`() {
        val many = (1..60).map {
            found("$it", "Книга $it", listOf("Автор $it"), listOf("Fiction / Science Fiction"))
        }
        val result = DiscoveryRanker.rank(listOf(solaris), many, emptyList(), limit = 24)
        assertEquals(24, result.size)
    }
}
