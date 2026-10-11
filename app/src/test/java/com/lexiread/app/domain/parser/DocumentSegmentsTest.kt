package com.lexiread.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cutting a book into pieces the reader can lay out one at a time.
 *
 * The reason this exists is a hard limit rather than a slow function: the whole book is one
 * multicolumn flow, and a flow is not shown until all of it has been laid out. A long book
 * therefore costs time in proportion to its length before a single word appears, and the only
 * answer is to stop giving the browser the whole book.
 *
 * Which makes these tests about two promises. The first is that the pieces still add up to the
 * book — every word in the same order, so a place recorded in one reading is the same place in the
 * next. The second is that no cut lands anywhere the reader would see it: not inside a table, not
 * inside a paragraph, and not leaving a chapter hanging open.
 */
class DocumentSegmentsTest {

    private fun paragraphs(count: Int, from: Int = 0): String =
        (from until from + count).joinToString("") {
            "<p>Абзац номер $it, и в нём столько слов, сколько нужно, чтобы он был похож на " +
                "настоящий абзац настоящей книги, а не на строчку в тесте.</p>"
        }

    @Test
    fun `a document that fits is one piece`() {
        val html = paragraphs(3)
        val pieces = splitIntoSegments(html, target = 10_000)

        assertEquals(1, pieces.size)
        assertEquals(html, pieces[0].html)
    }

    @Test
    fun `a long document is cut into pieces of about the size asked for`() {
        val pieces = splitIntoSegments(paragraphs(200), target = 2_000)

        assertTrue("expected several pieces, got ${pieces.size}", pieces.size > 5)
        // A cut is made at the first block boundary past the target, never before it, so a piece
        // overruns by at most one block and never falls far short.
        pieces.dropLast(1).forEach {
            assertTrue("piece of ${it.html.length}", it.html.length in 2_000..2_600)
        }
    }

    @Test
    fun `the words of the pieces are the words of the book`() {
        val html = paragraphs(200)
        val pieces = splitIntoSegments(html, target = 2_000)

        // This is the promise everything else rests on. Positions in a book are measured in
        // characters of its plain text, so if joining the pieces did not give back exactly the
        // text of the whole book, every bookmark, every note and every search result would land
        // somewhere slightly wrong — and further wrong the deeper into the book it was.
        assertEquals(plainTextOf(html), pieces.joinToString(" ") { it.plain })
    }

    @Test
    fun `a chapter is closed at the end of a piece and opened again in the next`() {
        // What EPUB gives: one wrapper per chapter, with the anchor its contents point at.
        val html = "<div id=\"shl-c0\">${paragraphs(200)}</div>"
        val pieces = splitIntoSegments(html, target = 2_000)

        assertTrue(pieces.size > 5)
        pieces.forEach {
            assertTrue("piece does not open the chapter: ${it.html.take(40)}", it.html.startsWith("<div"))
            assertTrue("piece does not close the chapter: ${it.html.takeLast(40)}", it.html.endsWith("</div>"))
        }
        // The anchor belongs to the place it was declared. Repeated on every piece, a jump into
        // the chapter would land on whichever copy the browser happened to find first.
        assertTrue(pieces.first().html.startsWith("<div id=\"shl-c0\">"))
        pieces.drop(1).forEach { assertFalse(it.html.contains("shl-c0")) }
    }

    @Test
    fun `a table is not cut in half`() {
        val rows = (0 until 200).joinToString("") { "<tr><td>Ячейка $it</td><td>и вторая</td></tr>" }
        val html = paragraphs(20) + "<table>$rows</table>" + paragraphs(20, from = 20)
        val pieces = splitIntoSegments(html, target = 2_000)

        // A table split down the middle shows as two tables. A piece larger than asked for costs
        // a little layout; a broken one costs the reader the thing they were reading.
        val holding = pieces.filter { it.html.contains("<table") }
        assertEquals(1, holding.size)
        assertEquals(1, Regex("<table").findAll(holding[0].html).count())
        assertEquals(200, Regex("<tr>").findAll(holding[0].html).count())
    }

    @Test
    fun `a heading near the size is where the cut is made`() {
        val html = paragraphs(12) + "<h2 id=\"shl-t1\">Глава вторая</h2>" + paragraphs(40, from = 12)
        val pieces = splitIntoSegments(html, target = 2_000)

        // The heading sits at some ~1.6 kB, past the point where an approaching heading is a
        // better seam than an arbitrary count.
        val opening = pieces.first { it.html.startsWith("<h2") }
        assertEquals("Глава вторая", opening.title)
        assertFalse(pieces.first().html.contains("<h2"))
    }

    @Test
    fun `a piece that begins in the middle of a chapter has no title of its own`() {
        val pieces = splitIntoSegments(paragraphs(200), target = 2_000)

        assertTrue(pieces.all { it.title.isEmpty() })
    }

    @Test
    fun `no piece is empty`() {
        // A cut made on the very last block of a book leaves nothing behind but the wrapper that
        // was opened again for it, and an empty chapter is not a piece.
        val html = "<div id=\"shl-c0\">${paragraphs(30)}</div>"
        val pieces = splitIntoSegments(html, target = 500)

        assertTrue(pieces.all { it.plain.isNotBlank() })
    }

    @Test
    fun `a picture is a piece worth keeping even with nothing to read in it`() {
        val html = paragraphs(30) + "<p><img width=\"800\" height=\"600\" src=\"/media/plate.png\"/></p>"
        val pieces = splitIntoSegments(html, target = 500)

        assertTrue(pieces.last().html.contains("<img"))
    }

    @Test
    fun `an element nobody closed does not lose the rest of the book`() {
        val html = paragraphs(30) + "<p>Хвост, который начали и не закрыли"
        val pieces = splitIntoSegments(html, target = 500)

        assertTrue(pieces.last().plain.contains("Хвост, который начали и не закрыли"))
    }
}
