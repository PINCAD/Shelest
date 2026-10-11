package com.lexiread.app.domain.parser

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File
import java.util.zip.ZipFile

/** OpenDocument text (.odt): a ZIP package whose content.xml carries the reading text. */
class OdtParser : BookParser {
    override fun extractMetadata(file: File): BookMetadata = runCatching {
        ZipFile(file).use { zip ->
            val archive = Archive(zip)
            val meta = archive.entryNamed("meta.xml")?.let(archive::xml)
            val title = meta?.byLocalName("title")?.firstOrNull()?.text()?.trim().orEmpty()
            val author = meta?.byLocalName("creator")?.firstOrNull()?.text()?.trim().orEmpty()
            val body = contentBody(archive)
            BookMetadata(
                title = title.ifBlank { file.nameWithoutExtension },
                author = author,
                totalChars = body?.let { content ->
                    content.byLocalName("p").sumOf { it.text().length } +
                        content.byLocalName("h").sumOf { it.text().length }
                } ?: 0
            )
        }
    }.getOrElse { BookMetadata(title = file.nameWithoutExtension) }

    override fun extractContent(file: File): String = ZipFile(file).use { zip ->
        val archive = Archive(zip)
        val body = contentBody(archive) ?: throw IllegalArgumentException("В ODT не найден текст документа")
        buildString {
            for (block in body.allElements.toList().filter { it.localName() == "p" || it.localName() == "h" }) {
                if (block.children().any { it.localName() == "p" || it.localName() == "h" }) continue
                val tag = if (block.localName() == "h") {
                    "h${block.attrByLocalName("outline-level").toIntOrNull()?.coerceIn(1, 6) ?: 2}"
                } else "p"
                val content = buildString { block.childNodes().forEach { append(renderNode(it)) } }
                if (content.isNotBlank()) append('<').append(tag).append('>').append(content)
                    .append("</").append(tag).append(">\n")
            }
        }
    }

    private fun contentBody(archive: Archive): Element? = archive.entryNamed("content.xml")
        ?.let(archive::xml)?.byLocalName("body")?.firstOrNull()
        ?.byLocalName("text")?.firstOrNull()

    private fun renderNode(node: Node): String = when {
        node is TextNode -> node.wholeText.escapeHtml()
        node !is Element -> ""
        node.localName() == "s" -> " ".repeat(node.attrByLocalName("c").toIntOrNull()?.coerceIn(1, 100) ?: 1)
        node.localName() == "tab" -> "&#9;"
        node.localName() == "line-break" -> "<br/>"
        else -> buildString { node.childNodes().forEach { append(renderNode(it)) } }
    }
}
