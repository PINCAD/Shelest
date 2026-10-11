package com.lexiread.app.domain.parser

import com.lexiread.app.data.model.FileType
import java.io.File

data class BookMetadata(
    val title: String = "",
    val author: String = "",
    val totalChars: Int = 0,
    val coverBytes: ByteArray? = null
)

/**
 * One line of a book's own table of contents.
 *
 * [anchor] is the id of the element the line points at inside the assembled document — the reader
 * jumps to it directly, rather than hunting for a heading whose text looks similar. [level] is the
 * depth the book declared, so a part and the chapters under it can be told apart.
 */
data class TocEntry(
    val title: String,
    val anchor: String,
    val level: Int = 1,
    /** Cached segment containing this entry, or -1 for a non-segmented document. */
    val segmentIndex: Int = -1
)

/** A book ready to be shown: the document, and whatever contents the book stated for itself. */
data class ParsedBook(
    val html: String,
    val toc: List<TocEntry> = emptyList(),
    /** A bounded document is exposed one segment at a time instead of as one giant DOM. */
    val segments: List<BookSegment> = emptyList(),
    val segmentIndex: Int = 0,
    /** Plain-text cache used only when search is opened, never on the opening path. */
    val plainTextPath: String? = null
)

interface BookParser {
    fun extractMetadata(file: File): BookMetadata

    /** Returns HTML string ready for WebView rendering. */
    fun extractContent(file: File): String

    /**
     * The document together with the book's own contents.
     *
     * Only the formats that state a table of contents override this. For the rest the reader
     * falls back to reading headings out of the finished document, which is a guess — a good one
     * for a book that uses headings, and nothing at all for a book that does not.
     */
    fun extractBook(file: File): ParsedBook = ParsedBook(extractContent(file))
}

object BookParserFactory {
    fun create(type: FileType): BookParser = when (type) {
        FileType.TXT  -> TxtParser()
        FileType.EPUB -> EpubParser()
        FileType.FB2  -> Fb2Parser()
        FileType.PDF  -> PdfParser()
        FileType.HTML -> HtmlParser()
        FileType.MD   -> MarkdownParser()
        FileType.DOCX -> DocxParser()
        FileType.CBZ  -> CbzParser()
        FileType.RTF  -> RtfParser()
        FileType.ODT  -> OdtParser()
        FileType.MOBI -> MobiParser()
        FileType.AZW  -> MobiParser()
    }
}
