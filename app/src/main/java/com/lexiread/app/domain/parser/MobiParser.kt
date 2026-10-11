package com.lexiread.app.domain.parser

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.io.File

/** Reads the text and basic markup from unencrypted MOBI/AZW (PalmDOC/KF7) files. */
class MobiParser : BookParser {
    override fun extractMetadata(file: File): BookMetadata = runCatching {
        val document = AndroidMobiDecoder.decode(file.readBytes())
        BookMetadata(
            title = document.title.ifBlank { file.nameWithoutExtension },
            author = document.author,
            totalChars = Jsoup.parse(document.html).text().length
        )
    }.getOrElse { BookMetadata(title = file.nameWithoutExtension) }

    override fun extractContent(file: File): String {
        val document = AndroidMobiDecoder.decode(file.readBytes())
        return Jsoup.clean(
            document.html,
            Safelist.none().addTags(
                "p", "div", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
                "blockquote", "pre", "code", "strong", "b", "em", "i", "ul", "ol", "li",
                "table", "thead", "tbody", "tr", "td", "th", "sup", "sub"
            )
        )
    }
}
