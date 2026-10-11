package com.lexiread.app.domain.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File

/**
 * A single web page saved to disk: .html, .htm, .xhtml.
 *
 * Whole books are distributed this way — one file, one long document — and until now the app
 * imported them as plain text, which meant reading the markup rather than the book.
 */
class HtmlParser : BookParser {

    override fun extractMetadata(file: File): BookMetadata = runCatching {
        val doc = parse(file)
        val title = doc.title().trim().ifBlank {
            doc.select("h1").firstOrNull()?.text()?.trim().orEmpty()
        }
        BookMetadata(
            title      = title.take(200).ifBlank { file.nameWithoutExtension },
            author     = doc.select("meta[name=author]").firstOrNull()?.attr("content").orEmpty(),
            totalChars = doc.body().text().length
        )
    }.getOrElse { BookMetadata(title = file.nameWithoutExtension) }

    override fun extractContent(file: File): String {
        val body = parse(file).body()
        // Only pictures the page carries inside itself survive. Anything else lives in a folder
        // next to the original, and only the one file was imported — there is nothing to point at.
        sanitizeBody(body, images = { src -> src.takeIf { it.startsWith("data:") } })
        return body.html()
    }

    /** Parsed with the charset the file declares; null lets jsoup read the meta tag and the BOM. */
    private fun parse(file: File): Document = file.inputStream().use { Jsoup.parse(it, null, "") }
}
