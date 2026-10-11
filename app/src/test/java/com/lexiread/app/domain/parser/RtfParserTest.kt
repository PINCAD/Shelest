package com.lexiread.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RTF, which is where the letters go wrong if anything is going to.
 *
 * The format is seven-bit: a Russian document written in 1998 gives every letter as `\'e0`, a byte
 * in the code page it declared, and one saved by Word today gives the same letter as a Unicode
 * point followed by a replacement the reader is meant to step over. Get the first wrong and the
 * book is mojibake; get the second wrong and every letter appears twice.
 */
class RtfParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val parser = RtfParser()

    /** Written byte for byte: an RTF is an ASCII file that carries other alphabets as escapes. */
    private fun rtf(source: String): File {
        val file = temp.newFile("doc-${counter++}.rtf")
        file.writeText(source, Charsets.ISO_8859_1)
        return file
    }

    @Test
    fun `bytes are read in the code page the document declares`() {
        val file = rtf(
            """{\rtf1\ansi\ansicpg1251\deff0{\fonttbl{\f0\fnil\fcharset204 Times New Roman;}}
               \pard\f0 \'cf\'f0\'e8\'e2\'e5\'f2\par}"""
        )
        val html = parser.extractContent(file)
        assertTrue(html.contains("Привет"))
        // The font table is machinery, not reading matter.
        assertFalse(html.contains("Times New Roman"))
    }

    @Test
    fun `a document that wrote its bytes raw is read the same way`() {
        // Not conforming, and not rare: some tools put code-page bytes straight into the stream.
        val file = rtf("{\\rtf1\\ansi\\ansicpg1251 Ïðèâåò\\par}")
        assertTrue(parser.extractContent(file).contains("Привет"))
    }

    @Test
    fun `a unicode escape wins and its fallback is stepped over`() {
        // Ж is 1046 and и is 1080; each is followed by the "?" a plain reader would have shown.
        val file = rtf("{\\rtf1\\ansi\\ansicpg1252\\uc1 \\u1046 ?\\u1080 ?\\par}")
        val html = parser.extractContent(file)
        assertTrue(html.contains("Жи"))
        // The fallback is there for readers that cannot do better; printing it too would double
        // every letter of the book.
        assertFalse(html.contains("?"))
    }

    @Test
    fun `a fallback of more than one character is stepped over whole`() {
        val file = rtf("{\\rtf1\\ansi\\uc2 \\u1046 ??\\par}")
        val html = parser.extractContent(file)
        assertTrue(html.contains("Ж"))
        assertFalse(html.contains("?"))
    }

    @Test
    fun `paragraphs, emphasis and escaped punctuation come through`() {
        val file = rtf(
            """{\rtf1\ansi \b Bold\b0  plain\par\i Slanted\i0\par Braces \{ and \\ \par}"""
        )
        val html = parser.extractContent(file)
        assertTrue(html.contains("<strong>Bold</strong>"))
        assertTrue(html.contains("<em>Slanted</em>"))
        assertTrue(html.contains("{"))
        assertTrue(html.contains("\\"))
        assertEquals(3, Regex("<p>").findAll(html).count())
    }

    @Test
    fun `a line break is markup rather than text`() {
        val file = rtf("""{\rtf1\ansi First\line Second\par}""")
        val html = parser.extractContent(file)
        assertTrue(html.contains("<br/>"))
        assertFalse(html.contains("&lt;br"))
    }

    @Test
    fun `pictures and other destinations say nothing`() {
        val file = rtf(
            """{\rtf1\ansi{\*\shppict{\pict\pngblip 89504e470d0a1a0a}}Text after\par}"""
        )
        val html = parser.extractContent(file)
        assertTrue(html.contains("Text after"))
        assertFalse(html.contains("89504e47"))
    }

    @Test
    fun `the title and the author are taken from the document's own record of them`() {
        val file = rtf(
            """{\rtf1\ansi{\info{\title Zagolovok}{\author Petrov}}Body text\par}"""
        )
        val meta = parser.extractMetadata(file)
        assertEquals("Zagolovok", meta.title)
        assertEquals("Petrov", meta.author)
        assertTrue(meta.totalChars > 0)
        // What the record says is not part of the book.
        assertFalse(parser.extractContent(file).contains("Zagolovok"))
    }

    @Test
    fun `a file that is not an RTF at all is not a crash`() {
        val file = temp.newFile("plain.rtf")
        file.writeText("just some words")
        assertEquals("plain", parser.extractMetadata(file).title)
    }

    private companion object {
        var counter = 0
    }
}
