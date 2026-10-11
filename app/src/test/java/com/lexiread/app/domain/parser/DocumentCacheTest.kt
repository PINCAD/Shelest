package com.lexiread.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The document kept beside the book, so a long one is read from the file only once.
 *
 * The cost this exists to remove is paid on every opening, not on the first: a twenty-megabyte FB2
 * is twenty million characters to scan and a few hundred pictures to decode, and none of it
 * changes between one tap on the shelf and the next.
 *
 * What these tests are really about is the other half — that a cache which is out of date, half
 * written or simply about a different book has to fail its own check rather than be believed. The
 * penalty for a miss is a wait; the penalty for a wrong hit is the reader opening one book and
 * being shown another.
 */
class DocumentCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var counter = 0

    /** A book large enough to be worth caching at all. */
    private fun book(text: String = "Текст"): File =
        temp.newFile("book-${counter++}.fb2").apply {
            writeBytes(text.toByteArray(Charsets.UTF_8) + ByteArray(2 * 1024 * 1024))
        }

    private val parsed = ParsedBook(
        "<h2 id=\"shl-t0\">Глава</h2><p>Текст</p>",
        listOf(TocEntry("Глава", "shl-t0", 1), TocEntry("Глава вторая", "shl-t1", 2))
    )

    @Test
    fun `what was written is what comes back`() {
        val file = book()
        DocumentCache.store(file, parsed)

        val restored = DocumentCache.load(file)
        assertEquals(parsed.html, restored?.html)
        assertEquals(parsed.toc, restored?.toc)
    }

    @Test
    fun `a book nothing has been written for yet has nothing to give`() {
        assertNull(DocumentCache.load(book()))
    }

    @Test
    fun `a book that changed on disk is read again`() {
        val file = book()
        DocumentCache.store(file, parsed)
        // A book replaced by another of a different length under the same name — re-imported,
        // or a different edition dropped into the same folder.
        file.writeBytes(ByteArray(3 * 1024 * 1024))

        assertNull(DocumentCache.load(file))
    }

    @Test
    fun `a cache without its stamp is not believed`() {
        val file = book()
        DocumentCache.store(file, parsed)
        // What a write interrupted half way through leaves behind: a document, and no stamp
        // saying which book it is of.
        assertTrue(File(DocumentCache.dirFor(file), "stamp").delete())

        assertNull(DocumentCache.load(file))
    }

    @Test
    fun `a short compressed book is cached too`() {
        val file = temp.newFile("short-${counter++}.fb2").apply { writeText("Рассказ") }
        assertTrue(DocumentCache.worthCaching(file))
        DocumentCache.store(file, parsed)
        assertEquals(parsed.html, DocumentCache.load(file)?.html)
    }

    @Test
    fun `caching a previously unsegmented book preserves its positions`() {
        val file = temp.newFile("old-small.epub").apply { writeText("compressed archive") }
        DocumentCache.store(file, long, preserveWholeDocument = true)
        assertEquals(1, DocumentCache.segments(file).size)
        assertEquals(long.html, DocumentCache.segmentHtml(file, 0))
        assertEquals(domTextLengthOf(long.html), DocumentCache.segments(file).single().domChars)
    }

    @Test
    fun `removing the book removes what was kept for it`() {
        val file = book()
        DocumentCache.store(file, parsed)
        assertTrue(DocumentCache.dirFor(file).isDirectory)

        DocumentCache.clear(file)
        assertFalse(DocumentCache.dirFor(file).exists())
    }

    @Test
    fun `a book with no contents of its own comes back with none`() {
        val file = book()
        DocumentCache.store(file, ParsedBook("<p>Текст</p>"))
        assertEquals(emptyList<TocEntry>(), DocumentCache.load(file)?.toc)
    }

    /** A document long enough that it has to be kept in more than one piece. */
    private val long = ParsedBook(
        (0 until 900).joinToString("") {
            "<p>Абзац номер $it, и в нём столько слов, сколько нужно, чтобы он был похож на " +
                "настоящий абзац настоящей книги, а не на строчку в тесте.</p>"
        },
        listOf(TocEntry("Глава", "shl-t0", 1))
    )

    @Test
    fun `a long book is kept in pieces`() {
        val file = book()
        DocumentCache.store(file, long)

        val pieces = DocumentCache.segments(file)
        assertTrue("expected several pieces, got ${pieces.size}", pieces.size > 1)
        pieces.forEachIndexed { at, piece ->
            assertEquals(at, piece.index)
            assertTrue(DocumentCache.segmentHtml(file, at)?.isNotBlank() == true)
        }
    }

    @Test
    fun `contents point to the segment that owns their anchor`() {
        val file = book()
        DocumentCache.store(file, parsed)

        val contents = DocumentCache.contents(file)
        assertEquals(0, contents.first().segmentIndex)
        assertEquals(-1, contents.last().segmentIndex)
    }

    @Test
    fun `search corpus is exposed by path without being read on open`() {
        val file = book()
        DocumentCache.store(file, parsed)

        val path = DocumentCache.plainTextPath(file)
        assertTrue(path != null && File(path).isFile)
    }

    @Test
    fun `a piece knows how long it is to the browser`() {
        val file = book()
        DocumentCache.store(file, long)

        // The reader adds these up to know how long the book is and where in it a piece begins,
        // and then adds the page's own counting on top. If this were the plain-text length —
        // the other, different measure of the same piece — the two would disagree by every
        // collapsed space in the book, and the reader would be put down pages from their place.
        DocumentCache.segments(file).forEach { piece ->
            assertEquals(
                domTextLengthOf(DocumentCache.segmentHtml(file, piece.index)!!),
                piece.domChars
            )
        }
        assertEquals(
            domTextLengthOf(long.html),
            DocumentCache.segments(file).sumOf { it.domChars }
        )
    }

    @Test
    fun `every piece is where the index says it is`() {
        val file = book()
        DocumentCache.store(file, long)

        // The reader turns a place in a book into a piece to load by looking it up in this index,
        // and never checks the answer against the text. An index that is off by even a little
        // sends the reader to the wrong page, and further wrong the deeper into the book they are.
        val plain = DocumentCache.plainText(file)!!
        DocumentCache.segments(file).forEach { piece ->
            assertEquals(
                plainTextOf(DocumentCache.segmentHtml(file, piece.index)!!),
                plain.substring(piece.startChar, piece.endChar)
            )
        }
        assertEquals(plain.length, DocumentCache.segments(file).last().endChar)
    }

    @Test
    fun `the book as words is the same book`() {
        val file = book()
        DocumentCache.store(file, long)

        // Search and the reading voice both work from this file rather than from the document,
        // so a word missing from it is a word the reader cannot find and the voice does not say.
        assertEquals(plainTextOf(long.html), DocumentCache.plainText(file))
    }

    @Test
    fun `a long book read back as one thing is still the same book`() {
        val file = book()
        DocumentCache.store(file, long)

        // Cutting adds a closing tag here and an opening one there, so the markup is not the
        // markup that went in — but not one word of the book may differ.
        assertEquals(plainTextOf(long.html), plainTextOf(DocumentCache.load(file)!!.html))
    }

    @Test
    fun `a cache that fails its stamp has no pieces to offer`() {
        val file = book()
        DocumentCache.store(file, long)
        assertTrue(File(DocumentCache.dirFor(file), "stamp").delete())

        assertEquals(emptyList<BookSegment>(), DocumentCache.segments(file))
        assertNull(DocumentCache.plainText(file))
    }

    @Test
    fun `what was kept for an earlier reading is not mixed into a later one`() {
        val file = book()
        DocumentCache.store(file, long)
        val before = DocumentCache.segments(file).size
        assertTrue(before > 1)

        // The same file re-imported after being edited down to a short one. The pieces of the
        // longer reading must not be left lying in the directory to be served as its tail.
        file.writeBytes(ByteArray(2 * 1024 * 1024 + 7))
        DocumentCache.store(file, parsed)

        assertEquals(1, DocumentCache.segments(file).size)
        assertEquals(parsed.html, DocumentCache.load(file)?.html)
    }
}
