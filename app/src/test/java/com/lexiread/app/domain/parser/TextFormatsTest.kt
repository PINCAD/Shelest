package com.lexiread.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset

/** Plain text and Markdown — the two formats that are only a file and a guess about its charset. */
class TextFormatsTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ── .txt ────────────────────────────────────────────────────────────────────

    private val txt = TxtParser()

    @Test
    fun `a blank line separates paragraphs, whatever wrote the line endings`() {
        val file = temp.newFile("book.txt")
        file.writeText("Первый абзац.\r\n\r\nВторой абзац.\r\nЕго вторая строка.\r\n")
        val html = txt.extractContent(file)
        assertEquals(2, Regex("<p>").findAll(html).count())
        // A single newline inside a paragraph is a line, not a new paragraph.
        assertTrue(html.contains("<br/>"))
    }

    @Test
    fun `an older Russian book is not read as mojibake`() {
        val file = temp.newFile("old.txt")
        file.writeBytes("Привет, читатель".toByteArray(Charset.forName("Windows-1251")))
        assertTrue(txt.extractContent(file).contains("Привет, читатель"))
    }

    @Test
    fun `length is counted in characters, not in bytes`() {
        val file = temp.newFile("chars.txt")
        // Five letters that cost ten bytes in UTF-8: the file's size would say the book is twice
        // as long as it is, and every "about an hour left" is built on this number.
        file.writeText("Слово")
        assertEquals(5, txt.extractMetadata(file).totalChars)
    }

    @Test
    fun `the first non-empty line is the title`() {
        val file = temp.newFile("titled.txt")
        file.writeText("\n\nВойна и мир\n\nВсё смешалось.")
        assertEquals("Война и мир", txt.extractMetadata(file).title)
    }

    // ── .md ─────────────────────────────────────────────────────────────────────

    private val md = MarkdownParser()

    private fun markdown(source: String) =
        temp.newFile("doc-${counter++}.md").apply { writeText(source) }

    @Test
    fun `headings, rules and consecutive list items`() {
        val html = md.extractContent(
            markdown(
                """
                # Заголовок

                - раз
                - два

                ---

                1. первый
                2. второй
                """.trimIndent()
            )
        )
        assertTrue(html.contains("<h1>Заголовок</h1>"))
        assertTrue(html.contains("<hr/>"))
        // One list, not one list per item.
        assertEquals(1, Regex("<ul>").findAll(html).count())
        assertEquals(1, Regex("<ol>").findAll(html).count())
        assertEquals(4, Regex("<li>").findAll(html).count())
    }

    @Test
    fun `a fenced block is taken as it is written`() {
        val html = md.extractContent(
            markdown(
                """
                ```
                if (a < b) { *not italic* }
                ```
                """.trimIndent()
            )
        )
        assertTrue(html.contains("<pre><code>"))
        assertTrue(html.contains("a &lt; b"))
        assertFalse(html.contains("<em>"))
    }

    @Test
    fun `a fence that is never closed still ends`() {
        // Not a made-up case: a book pasted together out of several files ends mid-block, and a
        // loop that waits for the closing fence would never come back at all.
        val html = md.extractContent(markdown("```\nвечный блок\n"))
        assertTrue(html.contains("вечный блок"))
    }

    @Test
    fun `links keep their words and lose their addresses`() {
        val html = md.extractContent(markdown("Смотри [сюда](http://example.com) и ![вид](pic.png)"))
        assertTrue(html.contains("сюда"))
        assertTrue(html.contains("вид"))
        assertFalse(html.contains("example.com"))
        assertFalse(html.contains("pic.png"))
    }

    @Test
    fun `emphasis, and a paragraph that runs across wrapped lines`() {
        val html = md.extractContent(markdown("**жирно** и *косо*\nпродолжение строки"))
        assertTrue(html.contains("<strong>жирно</strong>"))
        assertTrue(html.contains("<em>косо</em>"))
        assertEquals(1, Regex("<p>").findAll(html).count())
    }

    @Test
    fun `the first heading is the title`() {
        val file = markdown("Преамбула\n\n# Настоящее название\n")
        assertEquals("Настоящее название", md.extractMetadata(file).title)
    }

    // ── .html ───────────────────────────────────────────────────────────────────

    private val htmlParser = HtmlParser()

    private fun html(source: String) =
        temp.newFile("page-${counter++}.html").apply { writeText(source) }

    @Test
    fun `a saved page keeps its words and loses its machinery`() {
        val file = html(
            """
            <html><head><title>Страница</title><meta name="author" content="Кто-то">
            <style>p { color: red }</style></head>
            <body><p style="font-size:40pt">Текст</p><script>alert(1)</script>
            <img src="pictures/one.png"/>
            <img src="data:image/png;base64,aGVsbG8="/></body></html>
            """.trimIndent()
        )
        val meta = htmlParser.extractMetadata(file)
        assertEquals("Страница", meta.title)
        assertEquals("Кто-то", meta.author)

        val out = htmlParser.extractContent(file)
        assertTrue(out.contains("Текст"))
        assertFalse(out.contains("alert"))
        assertFalse(out.contains("font-size"))
        // Only the file itself was imported, so a picture in a folder beside it has nothing to
        // point at; one carried inside the page still has.
        assertFalse(out.contains("pictures/one.png"))
        assertTrue(out.contains("data:image/png;base64,aGVsbG8="))
    }

    @Test
    fun `a page with no title falls back to its first heading`() {
        val file = html("<html><body><h1>Название</h1><p>Текст</p></body></html>")
        assertEquals("Название", htmlParser.extractMetadata(file).title)
    }

    private companion object {
        var counter = 0
    }
}
