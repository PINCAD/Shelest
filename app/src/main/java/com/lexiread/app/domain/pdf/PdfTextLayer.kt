package com.lexiread.app.domain.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File

/** One line of a PDF's own bookmarks. */
data class PdfOutlineItem(val title: String, val pageIndex: Int, val level: Int)

/** Where a search found something, and enough of the line around it to recognise. */
data class PdfSearchHit(val pageIndex: Int, val snippet: String)

/**
 * The words behind the pictures of a PDF.
 *
 * The reader draws PDF pages with Android's own `PdfRenderer`, which does exactly one thing: it
 * paints a page. It cannot say what is written on it — which is why, for as long as PDFs have been
 * openable in this app, they have had no search, no bookmarks of their own and no voice. Reading
 * the text is a separate job from drawing it, and this is the half that reads.
 *
 * Everything is done on demand and kept. A page is extracted the first time something asks for it;
 * recent pages stay in a bounded cache. That ordering matters on a
 * phone: pulling four hundred pages apart takes seconds, and doing it when a book is opened would
 * make every PDF feel broken to pay for a feature most openings never use.
 *
 * Not thread-safe by nature — a `PDFTextStripper` holds the state of the page it is working on —
 * so every entry point takes the lock. Callers must stay off the main thread.
 */
class PdfTextLayer private constructor(
    private val document: PDDocument
) : Closeable {

    private val cache = object : LinkedHashMap<Int, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean = size > 32
    }
    private val lock = Any()
    private val wordCache = object : LinkedHashMap<Int, List<PdfWord>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<PdfWord>>?): Boolean = size > 8
    }

    /** Extract geometry only for requested pages; never read all pages on opening. */
    fun pageWords(index: Int): List<PdfWord> = synchronized(lock) {
        if (index !in 0 until pageCount) return@synchronized emptyList()
        wordCache[index]?.let { return@synchronized it }
        val words = ArrayList<PdfWord>()
        val page = document.getPage(index)
        val rotated = page.rotation % 180 != 0
        val width = if (rotated) page.cropBox.height else page.cropBox.width
        val height = if (rotated) page.cropBox.width else page.cropBox.height
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                val token = StringBuilder()
                var left = 0f; var top = 0f; var right = 0f; var bottom = 0f
                fun flush() {
                    if (token.isNotEmpty()) words.add(PdfWord(token.toString(), left / width, top / height, right / width, bottom / height))
                    token.setLength(0)
                }
                for (position in textPositions) {
                    val value = position.unicode.orEmpty()
                    if (value.isBlank()) { flush(); continue }
                    val x = position.xDirAdj; val baseline = position.yDirAdj
                    val t = baseline - position.heightDir.coerceAtLeast(1f)
                    if (token.isNotEmpty() && (x - right > position.widthOfSpace.coerceAtLeast(1f) * .5f || kotlin.math.abs(baseline - bottom) > position.heightDir)) flush()
                    if (token.isEmpty()) { left = x; top = t; right = x; bottom = baseline }
                    token.append(value); top = minOf(top, t); right = maxOf(right, x + position.widthDirAdj); bottom = maxOf(bottom, baseline)
                }
                flush()
            }
        }
        stripper.startPage = index + 1; stripper.endPage = index + 1; stripper.sortByPosition = true
        runCatching { stripper.getText(document) }.getOrElse { words.clear() }
        words.toList().also { wordCache[index] = it }
    }

    val pageCount: Int = document.numberOfPages

    /** The text of one page, or an empty string for a page that is nothing but a picture. */
    fun pageText(index: Int): String {
        if (index !in 0 until pageCount) return ""
        synchronized(lock) {
            cache[index]?.let { return it }
            val text = runCatching {
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                stripper.startPage = index + 1
                stripper.endPage   = index + 1
                stripper.getText(document).trim()
            }.getOrDefault("")
            cache[index] = text
            return text
        }
    }

    /**
     * Whether this file has a text layer at all.
     *
     * A scanned book is a stack of photographs: every page comes back empty, and the honest answer
     * is that there is nothing to search or read aloud — not that the search found nothing. Judged
     * from the first few pages, since a title page is often blank in a book that is otherwise fine.
     */
    fun hasText(): Boolean = (0 until pageCount.coerceAtMost(8)).any { pageText(it).length > 40 }

    /** Characters in the whole book, estimated from a sample rather than read end to end. */
    fun estimatedChars(): Int {
        if (pageCount == 0) return 0
        val sampled = (0 until pageCount).take(SAMPLE_PAGES)
        val total = sampled.sumOf { pageText(it).length }
        if (total == 0) return 0
        return (total.toDouble() / sampled.size * pageCount).toInt()
    }

    /** The book's own bookmarks, flattened with the depth they were nested at. */
    fun outline(): List<PdfOutlineItem> = synchronized(lock) {
        runCatching {
            val root = document.documentCatalog?.documentOutline ?: return emptyList()
            val out = ArrayList<PdfOutlineItem>()
            collect(root.children(), 1, out)
            out
        }.getOrDefault(emptyList())
    }

    private fun collect(items: Iterable<PDOutlineItem>, level: Int, out: MutableList<PdfOutlineItem>) {
        for (item in items) {
            if (out.size >= MAX_OUTLINE) return
            val title = runCatching { item.title }.getOrNull()?.trim().orEmpty()
            val page  = runCatching { item.findDestinationPage(document) }.getOrNull()
            val index = page?.let { runCatching { document.pages.indexOf(it) }.getOrDefault(-1) } ?: -1
            if (title.isNotEmpty() && index >= 0) out.add(PdfOutlineItem(title, index, level))
            // Four levels is as deep as a table of contents can be read on a phone; below that
            // the entries are subsections whose titles mean nothing on their own.
            if (level < 4) collect(item.children(), level + 1, out)
        }
    }

    /** Every page carrying [query], with a line of context. */
    fun search(query: String, limit: Int = 80): List<PdfSearchHit> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        val hits = ArrayList<PdfSearchHit>()
        for (page in 0 until pageCount) {
            val text = pageText(page)
            if (text.isEmpty()) continue
            var from = 0
            while (hits.size < limit) {
                val found = text.indexOf(needle, from, ignoreCase = true)
                if (found < 0) break
                val start = (found - 40).coerceAtLeast(0)
                val end   = (found + needle.length + 50).coerceAtMost(text.length)
                hits.add(
                    PdfSearchHit(page, "…" + text.substring(start, end).replace('\n', ' ') + "…")
                )
                from = found + needle.length
            }
            if (hits.size >= limit) break
        }
        return hits
    }

    override fun close() {
        synchronized(lock) { runCatching { document.close() } }
    }

    companion object {
        private const val SAMPLE_PAGES = 12
        private const val MAX_OUTLINE = 500

        /**
         * Opens [file] for reading, or returns null when it cannot be read — which is a real
         * possibility here and not a defect: a PDF may be encrypted, malformed, or simply too
         * large for the memory this app is allowed. The reader still shows the pages in every one
         * of those cases, because drawing them goes through a different door entirely.
         */
        fun open(file: File): PdfTextLayer? = runCatching {
            PdfTextLayer(PDDocument.load(file))
        }.getOrNull()
    }
}
