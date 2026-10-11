package com.lexiread.app.domain.parser

import java.io.File
import java.security.MessageDigest

/**
 * The book, already turned into a document, kept next to the file it came from — and kept in
 * pieces.
 *
 * Two things are being avoided here, and they are different sizes of the same problem.
 *
 * The first is parsing. A twenty-megabyte FB2 is twenty million characters to scan, a few hundred
 * pictures to decode and a document of its own size to build, and none of it changes between one
 * opening and the next. Doing it again every time the reader taps the book on the shelf is a wait
 * paid on every opening rather than on the first.
 *
 * The second is laying the book out. The reader shows a book as one multicolumn flow, and a flow
 * cannot be shown until all of it has been laid out — so a long book costs time in proportion to
 * its length before its first page appears. That is why the document is stored cut into pieces
 * (see [splitIntoSegments]): the reader can then hold a few of them at a time and never pay for
 * the rest of the book at all.
 *
 * Alongside them is the book as plain words. Search and the reading voice both want the text
 * without its markup, and both used to build it from the whole document every time they were
 * asked — on the main thread, in the case of search, once per keystroke.
 *
 * All text books are cached, including small compressed EPUBs: archive size says little
 * about the cost of parsing its chapters and extracting its pictures.
 *
 * Nothing here is load-bearing: every failure — a half-written file, a cache from an older version
 * of the app, a stamp that no longer matches — answers null and the book is parsed as it always
 * was. That matters, because a cache that can be wrong in a way the reader has to notice is worse
 * than no cache at all.
 */
internal object DocumentCache {

    /**
     * Bumped whenever the parsers or the cut change what they produce. A document built by an
     * older version of the app is not wrong so much as out of date, so the stamp refuses it and
     * the book is read again.
     */
    private const val FORMAT = 4

    /** Cache every text format; PDF retains its own page renderer. */
    private const val CACHE_ABOVE = 0L // Compressed EPUBs can be small but expensive to parse.

    private const val STAMP    = "stamp"
    private const val INDEX    = "segments.tsv"
    private const val CONTENTS = "toc.tsv"
    private const val PLAIN    = "plain.txt"
    private const val PAGES    = "pages-"

    /** What joins two pieces when the book is read as one thing — one word boundary, no more. */
    private const val JOIN = "\n"

    /**
     * Where [book]'s cache lives — beside the book, in the same hidden directory scheme its
     * pictures use, so that removing the book removes this too.
     */
    fun dirFor(book: File): File =
        File(book.parentFile ?: book.absoluteFile.parentFile, ".cache/${mediaSafeName(book.name)}")

    fun worthCaching(book: File): Boolean = book.length() >= CACHE_ABOVE

    /**
     * The pieces this book was cut into, or an empty list if nothing was kept for it.
     *
     * This is what the reader asks for. It is deliberately only the index — where each piece
     * starts, how long it is and what it is called — so that knowing the shape of a book costs a
     * few kilobytes rather than the book.
     */
    fun segments(book: File): List<BookSegment> {
        if (!stampMatches(book)) return emptyList()
        return readIndex(File(dirFor(book), INDEX))
    }

    /** One piece's markup, read when the reader is about to need it. */
    fun segmentHtml(book: File, index: Int): String? = runCatching {
        File(dirFor(book), segmentName(index)).takeIf { it.isFile }?.readText()
    }.getOrNull()

    /** The whole book as words, for search and for the reading voice. */
    fun plainText(book: File): String? = runCatching {
        if (!stampMatches(book)) return null
        File(dirFor(book), PLAIN).takeIf { it.isFile }?.readText()
    }.getOrNull()

    /** Path only: opening a book must not read the full search corpus into memory. */
    fun plainTextPath(book: File): String? =
        if (stampMatches(book)) File(dirFor(book), PLAIN).takeIf { it.isFile }?.absolutePath else null

    /**
     * Page counts measured by the WebView for one particular typesetting layout.
     *
     * A page is a property of the font and viewport, rather than of the book file.  The layout
     * key is consequently part of the file name: changing type size or rotating the device
     * starts a separate, correct count instead of reusing a plausible but wrong old one.
     */
    fun pageCounts(book: File, layoutKey: String): Map<Int, Int> = runCatching {
        if (!stampMatches(book)) return emptyMap()
        val file = File(dirFor(book), "$PAGES${layoutHash(layoutKey)}.tsv")
        if (!file.isFile) return emptyMap()
        file.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            val index = parts.getOrNull(0)?.toIntOrNull()
            val pages = parts.getOrNull(1)?.toIntOrNull()
            if (index == null || pages == null || pages <= 0) null else index to pages
        }.toMap()
    }.getOrDefault(emptyMap())

    /** Writes one measured segment count without making opening the reader wait for disk I/O. */
    fun savePageCount(book: File, layoutKey: String, segmentIndex: Int, pages: Int) {
        if (segmentIndex < 0 || pages <= 0 || !stampMatches(book)) return
        runCatching {
            val target = File(dirFor(book), "$PAGES${layoutHash(layoutKey)}.tsv")
            val counts = pageCounts(book, layoutKey).toMutableMap()
            if (counts[segmentIndex] == pages) return
            counts[segmentIndex] = pages
            writeAtomically(target, counts.toSortedMap().entries.joinToString("\n") {
                "${it.key}\t${it.value}"
            })
        }
    }

    fun contents(book: File): List<TocEntry> = readContents(File(dirFor(book), CONTENTS))

    /**
     * The document built the last time this exact file was read, as one thing, or null.
     *
     * "This exact file" is length and modification time. Two books whose names differ only in
     * characters a file name cannot hold would share a directory; the stamp is what stops one of
     * them being served the other's text.
     */
    fun load(book: File): ParsedBook? = runCatching {
        val index = segments(book)
        if (index.isEmpty()) return null

        val dir  = dirFor(book)
        val html = StringBuilder(index.sumOf { it.chars } * 2)
        for (segment in index) {
            val piece = File(dir, segmentName(segment.index)).takeIf { it.isFile } ?: return null
            if (html.isNotEmpty()) html.append(JOIN)
            html.append(piece.readText())
        }
        if (html.isBlank()) return null

        // The legacy whole-document API has no segment boundary for callers to act on. Keep its
        // value semantics unchanged; the bounded reader uses contents(book) directly.
        ParsedBook(
            html.toString(),
            readContents(File(dir, CONTENTS)).map { it.copy(segmentIndex = -1) }
        )
    }.getOrNull()

    /**
     * Cuts [parsed] into pieces and writes them down for next time.
     *
     * The stamp is written last, and only once everything else is safely in place: a write
     * interrupted half way — the reader leaving, the process being killed — must leave a cache
     * that fails its own check rather than one that reads back as a truncated book.
     */
    fun store(book: File, parsed: ParsedBook, preserveWholeDocument: Boolean = false) {
        runCatching {
            val dir = dirFor(book)
            // Whatever was here belongs to a different reading of this file, and a piece of it
            // left over would be served as part of the new one.
            dir.deleteRecursively()
            dir.mkdirs()

            val pieces = if (preserveWholeDocument) {
                listOf(SegmentPiece(parsed.html, plainTextOf(parsed.html), ""))
            } else splitIntoSegments(parsed.html)
            val index  = StringBuilder()
            val plain  = StringBuilder()
            val anchorSegments = HashMap<String, Int>()

            pieces.forEachIndexed { number, piece ->
                writeAtomically(File(dir, segmentName(number)), piece.html)
                ID_ATTRIBUTE.findAll(piece.html).forEach { match ->
                    val id = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    if (id.isNotEmpty()) anchorSegments.putIfAbsent(id, number)
                }
                // The pieces are joined by the same single space that [plainTextOf] would have
                // left between two blocks, so that this file reads as the book does and an offset
                // into it means the same thing wherever it was measured.
                if (plain.isNotEmpty()) plain.append(' ')
                val at = plain.length
                plain.append(piece.plain)
                index.append(at).append('\t').append(plain.length - at).append('\t')
                    .append(oneLine(piece.title)).append('\t')
                    // Counted once, here, and never again: this is what lets the reader open a
                    // book knowing how long it is without any of it being read.
                    .append(domTextLengthOf(piece.html)).append('\n')
            }

            writeAtomically(File(dir, INDEX), index.toString())
            writeAtomically(File(dir, PLAIN), plain.toString())
            writeAtomically(File(dir, CONTENTS), parsed.toc.joinToString("\n") { entry ->
                val segment = anchorSegments[entry.anchor] ?: -1
                "${oneLine(entry.title)}\t${oneLine(entry.anchor)}\t${entry.level}\t$segment"
            })
            File(dir, STAMP).writeText(stampOf(book))
        }
    }

    /** Called when the book itself goes, so its cache does not outlive it. */
    fun clear(book: File) {
        runCatching { dirFor(book).deleteRecursively() }
    }

    private fun stampMatches(book: File): Boolean = runCatching {
        File(dirFor(book), STAMP).takeIf { it.isFile }?.readText() == stampOf(book)
    }.getOrDefault(false)

    private fun stampOf(book: File): String = "$FORMAT:${book.length()}:${book.lastModified()}"

    private fun segmentName(index: Int): String = "seg-%04d.html".format(index)

    private fun layoutHash(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun readIndex(file: File): List<BookSegment> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines().mapIndexedNotNull { number, line ->
                if (line.isBlank()) return@mapIndexedNotNull null
                val parts = line.split('\t')
                if (parts.size < 4) return@mapIndexedNotNull null
                BookSegment(
                    index     = number,
                    startChar = parts[0].toIntOrNull() ?: return@mapIndexedNotNull null,
                    chars     = parts[1].toIntOrNull() ?: return@mapIndexedNotNull null,
                    title     = parts[2],
                    domChars  = parts[3].toIntOrNull() ?: return@mapIndexedNotNull null
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun readContents(file: File): List<TocEntry> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                TocEntry(
                    title        = parts[0],
                    anchor       = parts[1],
                    level        = parts[2].toIntOrNull() ?: 1,
                    segmentIndex = parts.getOrNull(3)?.toIntOrNull() ?: -1
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                error("cannot place ${target.name}")
            }
        }
    }

    /** A field of a tab-separated line cannot hold a tab or a newline; a heading rarely wants one. */
    private fun oneLine(raw: String): String =
        raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private val ID_ATTRIBUTE = Regex(
        """\bid\s*=\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE
    )
}
