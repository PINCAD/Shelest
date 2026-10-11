package com.lexiread.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The EPUB reader, on the cases that used to open a book blank or in the wrong order.
 *
 * Each test is one shape of book that exists in the wild: a namespace-prefixed package document, a
 * spine that names files the zip spells differently, a table of contents pointing into the middle
 * of a chapter, and a book with no usable manifest at all.
 */
class EpubParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val parser = EpubParser()

    private fun build(opf: String, entries: List<Pair<String, ByteArray>>) =
        epubInto(temp.newFile("book-${counter++}.epub"), opf, entries)

    private fun text(name: String, content: String) = name to content.toByteArray(Charsets.UTF_8)

    // ── EPUB 2: prefixed tags, an NCX, and a page the book says is not part of the reading ──

    private val epub2Opf = """
        <?xml version="1.0"?>
        <opf:package xmlns:opf="http://www.idpf.org/2007/opf"
                     xmlns:dc="http://purl.org/dc/elements/1.1/" version="2.0">
          <opf:metadata>
            <dc:title>Тихий Дон</dc:title>
            <dc:creator>Шолохов</dc:creator>
          </opf:metadata>
          <opf:manifest>
            <opf:item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <opf:item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <opf:item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
            <opf:item id="col" href="text/colophon.xhtml" media-type="application/xhtml+xml"/>
          </opf:manifest>
          <opf:spine toc="ncx">
            <opf:itemref idref="c1"/>
            <opf:itemref idref="c2"/>
            <opf:itemref idref="col" linear="no"/>
          </opf:spine>
        </opf:package>
    """.trimIndent()

    private val ncx = """
        <?xml version="1.0"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
          <navMap>
            <navPoint id="p1" playOrder="1">
              <navLabel><text>Часть первая</text></navLabel>
              <content src="text/ch1.xhtml"/>
              <navPoint id="p2" playOrder="2">
                <navLabel><text>Сноска</text></navLabel>
                <content src="text/ch2.xhtml#note"/>
              </navPoint>
            </navPoint>
          </navMap>
        </ncx>
    """.trimIndent()

    private fun epub2() = build(
        epub2Opf,
        listOf(
            text("OEBPS/toc.ncx", ncx),
            text("OEBPS/text/ch1.xhtml", page("Один", """<h1 id="top">Часть первая</h1><p>Мелехов</p>""")),
            text("OEBPS/text/ch2.xhtml", page("Два", """<p id="top">Вторая</p><p id="note">Сноска</p>""")),
            text("OEBPS/text/colophon.xhtml", page("Колофон", "<p>Колофон</p>"))
        )
    )

    @Test
    fun `reads title and author through a namespace prefix`() {
        val meta = parser.extractMetadata(epub2())
        assertEquals("Тихий Дон", meta.title)
        assertEquals("Шолохов", meta.author)
    }

    @Test
    fun `spine order is followed and a non-linear page is left out`() {
        val html = parser.extractBook(epub2()).html
        assertTrue(html.indexOf("Мелехов") < html.indexOf("Вторая"))
        assertFalse(html.contains("Колофон"))
    }

    @Test
    fun `an id repeated in two chapters stays reachable in both`() {
        val html = parser.extractBook(epub2()).html
        assertTrue(html.contains("id=\"shl-c0-top\""))
        assertTrue(html.contains("id=\"shl-c1-top\""))
    }

    @Test
    fun `the NCX becomes the table of contents, with its nesting`() {
        val toc = parser.extractBook(epub2()).toc
        assertEquals(2, toc.size)
        assertEquals(TocEntry("Часть первая", "shl-c0", 1), toc[0])
        // Points at the note itself, not merely at the chapter holding it.
        assertEquals(TocEntry("Сноска", "shl-c1-note", 2), toc[1])
    }

    // ── EPUB 3: a navigation document, a folder spelled differently, a name that is a URI ──

    @Test
    fun `the EPUB 3 nav is read, and only the contents part of it`() {
        val encoded = "%D0%B3%D0%BB%D0%B0%D0%B2%D0%B0%202.xhtml"
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Книга</dc:title></metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="Text/$encoded" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>
        """.trimIndent()

        val nav = """
            <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
              <nav epub:type="toc"><ol>
                <li><a href="Text/ch1.xhtml">Часть</a>
                  <ol><li><a href="Text/$encoded#mid">Глава</a></li></ol>
                </li>
              </ol></nav>
              <nav epub:type="landmarks"><ol><li><a href="Text/ch1.xhtml">Начало</a></li></ol></nav>
            </body></html>
        """.trimIndent()

        val file = build(
            opf,
            listOf(
                text("OEBPS/nav.xhtml", nav),
                // The manifest says "text/ch1.xhtml"; the zip spells the folder with a capital.
                text("OEBPS/Text/ch1.xhtml", page("Один", "<p>Первая</p>")),
                text("OEBPS/Text/глава 2.xhtml", page("Два", """<p id="mid">Вторая</p>"""))
            )
        )

        val book = parser.extractBook(file)
        assertTrue(book.html.contains("Первая"))
        assertTrue(book.html.contains("Вторая"))

        assertEquals(2, book.toc.size)
        assertEquals(TocEntry("Часть", "shl-c0", 1), book.toc[0])
        assertEquals(TocEntry("Глава", "shl-c1-mid", 2), book.toc[1])
    }

    // ── No manifest to go on ────────────────────────────────────────────────────

    @Test
    fun `a book with no usable spine is read in a human's idea of order`() {
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata/><manifest/><spine/>
            </package>
        """.trimIndent()
        val file = build(
            opf,
            listOf(
                text("OEBPS/ch10.xhtml", page("10", "<p>Десятая</p>")),
                text("OEBPS/ch2.xhtml", page("2", "<p>Вторая</p>")),
                text("OEBPS/ch1.xhtml", page("1", "<p>Первая</p>"))
            )
        )
        val html = parser.extractBook(file).html
        assertTrue(html.indexOf("Первая") < html.indexOf("Вторая"))
        assertTrue(html.indexOf("Вторая") < html.indexOf("Десятая"))
        // Nothing states a contents, and guessing one is the reader's business, not the parser's.
        assertTrue(parser.extractBook(file).toc.isEmpty())
    }

    // ── What survives sanitising ────────────────────────────────────────────────

    @Test
    fun `styling, scripts and outside addresses do not survive`() {
        val opf = """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata/>
              <manifest><item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
        """.trimIndent()
        val body = """
            <p style="color:red" class="fancy">abcde</p>
            <script>alert(1)</script>
            <a href="http://example.com/">ссылка</a>
            <img src="../images/pic%201.png"/>
            <img src="http://example.com/track.gif"/>
        """.trimIndent()
        val file = build(
            opf,
            listOf(
                text("OEBPS/text/ch1.xhtml", page("Один", body)),
                "OEBPS/images/pic 1.png" to fakePng()
            )
        )

        val html = parser.extractBook(file).html
        assertFalse(html.contains("style="))
        assertFalse(html.contains("class="))
        assertFalse(html.contains("alert"))
        assertFalse(html.contains("href="))
        // The words of a link stay; only the address goes.
        assertTrue(html.contains("ссылка"))
        // A picture inside the book is served out of the archive, escaped a path segment at a time.
        assertTrue(html.contains("/res/OEBPS/images/pic%201.png"))
        // One that is not inside the book is dropped rather than fetched.
        assertFalse(html.contains("track.gif"))

        // Length is measured in reading matter: the picture and the markup count for nothing.
        // "abcde ссылка" and however jsoup decides to space the two apart.
        val chars = parser.extractMetadata(file).totalChars
        assertTrue("counted $chars characters of reading matter", chars in 11..13)
    }

    @Test
    fun `extractContent is the same document as extractBook`() {
        val file = epub2()
        assertEquals(parser.extractBook(file).html, parser.extractContent(file))
    }

    private companion object {
        var counter = 0
    }
}
