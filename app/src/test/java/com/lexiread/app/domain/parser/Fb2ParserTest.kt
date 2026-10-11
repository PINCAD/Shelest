package com.lexiread.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * FB2 — the Russian-language format, which is one XML file carrying its own pictures as base64 and,
 * as often as not, its own charset from before UTF-8 was the default anywhere.
 */
class Fb2ParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val parser = Fb2Parser()

    private fun source(encoding: String = "UTF-8") = """
        <?xml version="1.0" encoding="$encoding"?>
        <FictionBook xmlns:l="http://www.w3.org/1999/xlink">
          <description>
            <title-info>
              <book-title>Мастер и Маргарита</book-title>
              <author><first-name>Михаил</first-name><last-name>Булгаков</last-name></author>
              <coverpage><image l:href="#cover.png"/></coverpage>
            </title-info>
          </description>
          <body>
            <section>
              <title><p>Глава</p></title>
              <p>Текст</p>
              <empty-line/>
              <image l:href="#cover.png"/>
            </section>
          </body>
          <binary id="cover.png" content-type="image/png">aGVsbG8=</binary>
        </FictionBook>
    """.trimIndent()

    private fun book(encoding: String = "UTF-8"): File {
        val file = temp.newFile("book-${counter++}.fb2")
        file.writeBytes(source(encoding).toByteArray(Charset.forName(encoding)))
        return file
    }

    @Test
    fun `title and author are joined the way a person writes them`() {
        val meta = parser.extractMetadata(book())
        assertEquals("Мастер и Маргарита", meta.title)
        assertEquals("Михаил Булгаков", meta.author)
    }

    @Test
    fun `the cover is decoded even though its base64 is wrapped`() {
        val meta = parser.extractMetadata(book())
        assertNotNull(meta.coverBytes)
        assertEquals("hello", String(meta.coverBytes!!))
    }

    @Test
    fun `a book written in windows-1251 is read in windows-1251`() {
        val meta = parser.extractMetadata(book("windows-1251"))
        assertEquals("Мастер и Маргарита", meta.title)
    }

    @Test
    fun `length is the text of the book, not the picture inside it`() {
        // "Глава Текст" and however the parser spaces the two apart — the base64 is not reading
        // matter, and counting it once said a slim book with one cover was a thousand pages long.
        val chars = parser.extractMetadata(book()).totalChars
        assertTrue("counted $chars characters", chars in 10..14)
    }

    @Test
    fun `sections, headings and blank lines become ordinary markup`() {
        val html = parser.extractContent(book())
        assertTrue(html.contains("<h2 id=\"shl-t0\">Глава</h2>"))
        assertTrue(html.contains("<p>Текст</p>"))
        assertTrue(html.contains("<br/>"))
    }

    @Test
    fun `the sections of the book are its table of contents`() {
        val toc = parser.extractBook(book()).toc
        assertEquals(listOf(TocEntry("Глава", "shl-t0", 1)), toc)
    }

    @Test
    fun `an illustration is written out beside the book, not carried inside it`() {
        val file = book()
        val html = parser.extractContent(file)
        // It used to be pasted into the document as base64, which on an illustrated book was
        // tens of megabytes of text before the first word could be shown.
        assertTrue(html.contains("<img src=\"/media/cover.png\"/>"))
        assertFalse(html.contains("base64"))
        assertEquals("hello", File(mediaDirFor(file), "cover.png").readText())
    }

    @Test
    fun `an illustration is written down with the size it turns out to be`() {
        // FB2 keeps its pictures at the very end of the file, so at the moment a paragraph
        // mentions one, nothing about it is known. Without going back and saying how large it is,
        // the reader cannot measure a single column until every picture in the book has been
        // fetched and decoded — which on a book of plates is the whole wait.
        val picture = java.util.Base64.getEncoder().encodeToString(pngOf(1200, 1600))
        val source = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FictionBook xmlns:l="http://www.w3.org/1999/xlink">
              <description><title-info><book-title>С иллюстрацией</book-title></title-info></description>
              <body><section><p>Текст</p><image l:href="#plate.png"/></section></body>
              <binary id="plate.png" content-type="image/png">$picture</binary>
            </FictionBook>
        """.trimIndent()
        val file = temp.newFile("plates-${counter++}.fb2")
        file.writeText(source)

        val html = parser.extractContent(file)
        assertTrue(html, html.contains("<img width=\"1200\" height=\"1600\" src=\"/media/plate.png\"/>"))
    }

    @Test
    fun `a picture already unpacked is not decoded again`() {
        val file = book()
        parser.extractContent(file)
        val unpacked = File(mediaDirFor(file), "cover.png")
        unpacked.writeText("untouched")

        parser.extractContent(file)
        // Opening a book for the second time must not decode its pictures all over again — on a
        // book of plates that is the whole wait, paid twice.
        assertEquals("untouched", unpacked.readText())
    }

    @Test
    fun `a book a strict parser would refuse is read anyway`() {
        // Every one of these is ordinary in a converted FB2, and every one of them is a fatal
        // error to a validating XML parser — which answers by refusing the entire book.
        val broken = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FictionBook>
              <description><title-info><book-title>Смесь &amp; ошибки</book-title></title-info></description>
              <body>
                <section>
                  <title><p>Глава&nbsp;первая</p></title>
                  <p>Чай &amp; кофе, R&D, 5 &lt; 6</p>
                  <p>Неизвестный <badtag>тег</badtag> и &weird; сущность</p>
                </section>
              </body>
            </FictionBook>
        """.trimIndent()
        val file = temp.newFile("broken-${counter++}.fb2")
        file.writeText(broken)

        val parsed = parser.extractBook(file)
        assertEquals("Смесь & ошибки", parser.extractMetadata(file).title)
        assertTrue(parsed.html.contains("Чай &amp; кофе, R&amp;D, 5 &lt; 6"))
        // An unknown tag keeps its words and loses itself.
        assertTrue(parsed.html.contains("Неизвестный тег и"))
        assertFalse(parsed.html.contains("badtag"))
        // An entity nobody declared is left exactly as it was written.
        assertTrue(parsed.html.contains("&amp;weird;"))
    }

    @Test
    fun `a zipped book is read out of its archive`() {
        val file = temp.newFile("book-${counter++}.fb2.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("book.fb2"))
            zip.write(source().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        assertEquals("Мастер и Маргарита", parser.extractMetadata(file).title)
    }

    private companion object {
        var counter = 0
    }
}
