package com.lexiread.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Comics: a zip of pictures with nothing inside it stating the order they are read in.
 *
 * Which makes the order the whole of the format, and getting it wrong is not a subtle bug — the
 * book is simply shuffled. Plain string order puts page 10 before page 2, which is what "sorted by
 * name" gives you in every file manager and what this parser must not do.
 */
class CbzParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val parser = CbzParser()

    private fun comic(vararg entries: Pair<String, ByteArray>) =
        zipInto(temp.newFile("comic-${counter++}.cbz"), entries.toList())

    @Test
    fun `pages come out in the order a human would put them in`() {
        val file = comic(
            "page10.jpg" to "ten".toByteArray(),
            "page2.jpg" to "two".toByteArray(),
            "page1.jpg" to "one".toByteArray()
        )
        val html = parser.extractContent(file)
        assertTrue(html.indexOf("page1.jpg") < html.indexOf("page2.jpg"))
        assertTrue(html.indexOf("page2.jpg") < html.indexOf("page10.jpg"))
    }

    @Test
    fun `everything that is not a page is left out`() {
        val file = comic(
            "001.jpg" to "one".toByteArray(),
            "ComicInfo.xml" to "<ComicInfo/>".toByteArray(),
            "readme.txt" to "packed by".toByteArray(),
            "__MACOSX/._001.jpg" to "resource fork".toByteArray()
        )
        val html = parser.extractContent(file)
        assertEquals(1, Regex("<img").findAll(html).count())
        assertFalse(html.contains("ComicInfo"))
        assertFalse(html.contains("readme"))
        assertFalse(html.contains("._001"))
    }

    @Test
    fun `a page whose name is not a URL is escaped a segment at a time`() {
        val file = comic("страница 1.jpg" to "one".toByteArray())
        val html = parser.extractContent(file)
        assertTrue(html.contains("%20"))
        // A space in a URL is not a space, and a plus is not one either.
        assertFalse(html.contains("страница 1.jpg"))
        assertFalse(html.contains("+"))
        // The path is inside the archive, under the same prefix the reader serves.
        assertTrue(html.contains("src=\"/res/"))
    }

    @Test
    fun `a comic is not counted as reading matter, and opens on its first page`() {
        val file = comic(
            "page2.jpg" to "two".toByteArray(),
            "page1.jpg" to "one".toByteArray()
        )
        val meta = parser.extractMetadata(file)
        // No text at all — a length in characters would put it into the reading-pace figures as a
        // book that is never read.
        assertEquals(0, meta.totalChars)
        assertNotNull(meta.coverBytes)
        assertEquals("one", String(meta.coverBytes!!))
    }

    @Test
    fun `a file that is not a zip fails without taking the shelf down with it`() {
        val broken = temp.newFile("broken.cbz")
        broken.writeText("not a zip at all")
        assertEquals("broken", parser.extractMetadata(broken).title)
    }

    private companion object {
        var counter = 0
    }
}
