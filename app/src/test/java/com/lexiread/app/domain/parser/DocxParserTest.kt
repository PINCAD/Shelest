package com.lexiread.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Word documents, which are a zip of XML with a prefix on every tag in it.
 *
 * The things worth checking are the ones Word does not say plainly: a bulleted list is ordinary
 * paragraphs wearing a style, and "not bold" is written as a bold tag with a zero in it.
 */
class DocxParserTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val parser = DocxParser()

    private val document = """
        <?xml version="1.0" encoding="UTF-8"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Заголовок</w:t></w:r></w:p>
            <w:p>
              <w:r><w:rPr><w:b/></w:rPr><w:t>Жирный</w:t></w:r>
              <w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t xml:space="preserve"> обычный</w:t></w:r>
            </w:p>
            <w:p><w:pPr><w:pStyle w:val="ListParagraph"/></w:pPr><w:r><w:t>Раз</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="ListParagraph"/></w:pPr><w:r><w:t>Два</w:t></w:r></w:p>
            <w:p><w:r><w:drawing><a:blip r:embed="rId7"/></w:drawing></w:r></w:p>
          </w:body>
        </w:document>
    """.trimIndent()

    private val rels = """
        <?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId7" Type="image" Target="media/pic.png"/>
          <Relationship Id="rId8" Type="hyperlink" Target="http://example.com" TargetMode="External"/>
        </Relationships>
    """.trimIndent()

    private val core = """
        <?xml version="1.0" encoding="UTF-8"?>
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                           xmlns:dc="http://purl.org/dc/elements/1.1/">
          <dc:title>Рукопись</dc:title>
          <dc:creator>Автор</dc:creator>
        </cp:coreProperties>
    """.trimIndent()

    private fun docx() = zipInto(
        temp.newFile("doc-${counter++}.docx"),
        listOf(
            "word/document.xml" to document.toByteArray(Charsets.UTF_8),
            "word/_rels/document.xml.rels" to rels.toByteArray(Charsets.UTF_8),
            "docProps/core.xml" to core.toByteArray(Charsets.UTF_8),
            "word/media/pic.png" to fakePng()
        )
    )

    @Test
    fun `the title and author come out of the document properties`() {
        val meta = parser.extractMetadata(docx())
        assertEquals("Рукопись", meta.title)
        assertEquals("Автор", meta.author)
    }

    @Test
    fun `a heading style becomes a heading`() {
        assertTrue(parser.extractContent(docx()).contains("<h1>Заголовок</h1>"))
    }

    @Test
    fun `bold is only bold where Word says it is on`() {
        val html = parser.extractContent(docx())
        assertTrue(html.contains("<strong>Жирный</strong>"))
        // The second run turns it off with a zero rather than by leaving the tag out, which used
        // to set half a document in bold.
        assertFalse(html.contains("<strong> обычный"))
    }

    @Test
    fun `paragraphs wearing a list style become one list`() {
        val html = parser.extractContent(docx())
        assertEquals(1, Regex("<ul>").findAll(html).count())
        assertEquals(2, Regex("<li>").findAll(html).count())
    }

    @Test
    fun `a picture is served out of the file rather than carried inside the page`() {
        assertTrue(parser.extractContent(docx()).contains("src=\"/res/word/media/pic.png\""))
    }

    @Test
    fun `length is the words, not the markup around them`() {
        // Заголовок + Жирный + " обычный" + Раз + Два.
        assertEquals(29, parser.extractMetadata(docx()).totalChars)
    }

    private companion object {
        var counter = 0
    }
}
