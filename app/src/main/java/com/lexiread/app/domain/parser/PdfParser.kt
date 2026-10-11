package com.lexiread.app.domain.parser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PDF "parser" — uses Android's built-in PdfRenderer.
 * Text content returns a metadata-only HTML since PDF pages are rendered as bitmaps.
 * Use renderPage() in the reader to display individual pages.
 */
class PdfParser : BookParser, java.io.Closeable {
    private var pageRenderer: PdfRenderer? = null
    private var renderedPath: String? = null
    private val pageCache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    override fun extractMetadata(file: File): BookMetadata {
        return runCatching {
            openRenderer(file).use { renderer ->
                BookMetadata(
                    title      = file.nameWithoutExtension,
                    totalChars = charCount(file, renderer.pageCount),
                    coverBytes = renderCoverBytes(renderer)
                )
            }
        }.getOrElse { BookMetadata(title = file.nameWithoutExtension) }
    }

    /**
     * How long the book actually is.
     *
     * Measured from a sample of real pages now that a PDF's text can be read at all: a page of a
     * poetry collection and a page of a legal textbook are not within a factor of five of each
     * other, and the reading pace and the "N hours left" estimate are built on this number. Only
     * a scanned book — where every page comes back empty — still falls back to the flat guess.
     */
    private fun charCount(file: File, pageCount: Int): Int {
        val measured = com.lexiread.app.domain.pdf.PdfTextLayer.open(file)?.use { it.estimatedChars() } ?: 0
        return if (measured > 0) measured else pageCount * CHARS_PER_PRINTED_PAGE
    }

    /**
     * Renders the first page as a small JPEG thumbnail so PDFs get a real cover
     * like EPUB/FB2, instead of falling back to the flat placeholder — the flat
     * placeholder was the actual cause of the visible tap ripple looking like a
     * "gray flash" when opening a PDF from the library.
     */
    private fun renderCoverBytes(renderer: PdfRenderer): ByteArray? = runCatching {
        if (renderer.pageCount == 0) return@runCatching null
        renderer.openPage(0).use { page ->
            val targetWidth = 400
            val scale  = targetWidth.toFloat() / page.width
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                stream.toByteArray()
            }
        }
    }.getOrNull()

    /** For PDF, content is rendered page-by-page as bitmaps. */
    override fun extractContent(file: File): String {
        val pages = runCatching { openRenderer(file).use { it.pageCount } }.getOrElse { 0 }
        return "__PDF__:${file.absolutePath}:$pages"   // sentinel handled by ReaderScreen
    }

    /**
     * Render a single PDF page to a Bitmap [targetWidth] pixels across, or as close to it as
     * fits in memory. Throws if the file or the page cannot be read — callers render in the
     * background and are expected to catch.
     */
    @Synchronized
    fun renderPage(file: File, pageIndex: Int, targetWidth: Int): Bitmap {
        val path = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        if (renderedPath != path) {
            close()
            pageRenderer = openRenderer(file)
            renderedPath = path
        }
        val renderer = pageRenderer ?: error("PDF закрыт")
        val key = "$pageIndex:$targetWidth"
        pageCache.get(key)?.let { return it }
        val index = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
        renderer.openPage(index).use { page ->
            val width  = cappedWidth(targetWidth, page.width, page.height)
            val scale  = width.toFloat() / page.width
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            pageCache.put(key, bitmap)
            return bitmap
        }
    }

    @Synchronized
    override fun close() {
        pageCache.evictAll() // Visible Compose pages may still reference bitmaps; do not recycle.
        pageRenderer?.close()
        pageRenderer = null
        renderedPath = null
    }

    /**
     * [requested] trimmed to something the heap will survive.
     *
     * Zoom asks for the page at several times screen width, and the bill grows with the square:
     * a 1080-wide screen at 4x is 4320 px across and, at A4 proportions, 6100 down — just under
     * 100 MB as ARGB_8888, for one page out of the three the pager keeps alive. Past a point the
     * extra pixels are finer than the display can show anyway, so the ceiling costs nothing
     * visible and turns an out-of-memory crash into a slightly soft page.
     */
    private fun cappedWidth(requested: Int, pageW: Int, pageH: Int): Int {
        if (pageW <= 0 || pageH <= 0) return requested.coerceAtLeast(1)
        val ratio = pageH.toFloat() / pageW.toFloat()
        val maxWidth = kotlin.math.sqrt(MAX_PIXELS / ratio).toInt().coerceAtLeast(1)
        return requested.coerceIn(1, maxWidth)
    }

    private companion object {
        /** ~24 MB at four bytes a pixel. */
        const val MAX_PIXELS = 6_000_000f

        /** Prose, set at a normal size, on a page of a normal size. */
        const val CHARS_PER_PRINTED_PAGE = 1_800
    }

    private fun openRenderer(file: File): PdfRenderer {
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            PdfRenderer(fd)
        } catch (e: Exception) {
            fd.close()
            throw e
        }
    }
}
