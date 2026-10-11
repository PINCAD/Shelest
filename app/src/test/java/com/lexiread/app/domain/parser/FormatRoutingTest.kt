package com.lexiread.app.domain.parser

import com.lexiread.app.data.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That every format the app claims is actually wired to something that reads it.
 *
 * A format is added in three places — the enum, the extensions it answers to, and the factory —
 * and forgetting the third is a book that imports cleanly and then opens empty, which no other
 * test here would catch.
 */
class FormatRoutingTest {

    @Test
    fun `every declared format has a parser`() {
        for (type in FileType.values()) {
            // Throws rather than failing quietly if the factory is missing a branch.
            BookParserFactory.create(type)
        }
    }

    @Test
    fun `the newer formats answer to their own names`() {
        assertEquals(FileType.CBZ, FileType.fromExtension("cbz"))
        assertEquals(FileType.RTF, FileType.fromExtension("rtf"))
        assertEquals(FileType.RTF, FileType.fromExtension("RTF"))
        assertEquals(FileType.CBZ, FileType.fromMimeType("application/vnd.comicbook+zip"))
        assertEquals(FileType.RTF, FileType.fromMimeType("application/rtf"))
        assertEquals(FileType.ODT, FileType.fromExtension("odt"))
        assertEquals(FileType.MOBI, FileType.fromExtension("mobi"))
        assertEquals(FileType.AZW, FileType.fromExtension("azw"))
        assertEquals(FileType.ODT, FileType.fromMimeType("application/vnd.oasis.opendocument.text"))
    }

    @Test
    fun `the picker's own guesses are not trusted`() {
        // What a file picker says when it has no idea. The name and then the first bytes of the
        // file decide instead, so answering here would overrule both.
        assertNull(FileType.fromMimeType("application/octet-stream"))
        assertNull(FileType.fromMimeType("application/zip"))
    }

    @Test
    fun `each parser is the one the format needs`() {
        assertTrue(BookParserFactory.create(FileType.CBZ) is CbzParser)
        assertTrue(BookParserFactory.create(FileType.RTF) is RtfParser)
        assertTrue(BookParserFactory.create(FileType.EPUB) is EpubParser)
        assertTrue(BookParserFactory.create(FileType.ODT) is OdtParser)
        assertTrue(BookParserFactory.create(FileType.MOBI) is MobiParser)
    }
}
