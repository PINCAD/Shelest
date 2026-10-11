package com.lexiread.app.domain.parser

import org.jsoup.nodes.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Word documents (.docx), which is a zip of XML — no library needed, and the older binary .doc is
 * a different format entirely and is not read here.
 *
 * Only the parts that mean something to a reader are kept: the order of the paragraphs, what each
 * one is (a heading, a quotation, an item in a list), emphasis inside it, and the pictures. Word's
 * own styling — its fonts, its sizes, its margins — is thrown away on purpose, because the page in
 * this app is set by the reader's own settings, not by whoever typed the manuscript.
 */
class DocxParser : BookParser {

    override fun extractMetadata(file: File): BookMetadata = runCatching {
        ZipFile(file).use { zipFile ->
            val archive = Archive(zipFile)
            val core    = archive.entryNamed("docProps/core.xml")?.let { archive.xml(it) }

            val title  = core?.byLocalName("title")?.firstOrNull()?.text()?.trim().orEmpty()
            val author = core?.byLocalName("creator")?.firstOrNull()?.text()?.trim().orEmpty()

            BookMetadata(
                title      = title.take(200).ifBlank { file.nameWithoutExtension },
                author     = author,
                totalChars = archive.plainTextLength()
            )
        }
    }.getOrElse { BookMetadata(title = file.nameWithoutExtension) }

    override fun extractContent(file: File): String =
        ZipFile(file).use { zipFile -> Archive(zipFile).render() }

    private fun Archive.documentBody(): Element? {
        val entry = entryNamed("word/document.xml") ?: return null
        return xml(entry)?.byLocalName("body")?.firstOrNull()
    }

    private fun Archive.plainTextLength(): Int =
        documentBody()?.byLocalName("t")?.sumOf { it.wholeText().length } ?: 0

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun Archive.render(): String {
        val body = documentBody() ?: return ""
        val rels = relationships()
        val out = StringBuilder()

        var listOpen = false
        fun closeList() {
            if (listOpen) out.append("</ul>\n")
            listOpen = false
        }

        for (paragraph in body.byLocalName("p")) {
            val style = paragraph.byLocalName("pStyle").firstOrNull()
                ?.attrByLocalName("val").orEmpty().lowercase()
            val inner = renderRuns(paragraph, rels)
            if (inner.isBlank()) continue

            // Word records a bulleted list as ordinary paragraphs wearing a list style; the
            // bullets themselves are in the numbering definitions and are not worth chasing.
            if (style.startsWith("list")) {
                if (!listOpen) { out.append("<ul>\n"); listOpen = true }
                out.append("<li>").append(inner).append("</li>\n")
                continue
            }
            closeList()

            when {
                style == "title" || style.startsWith("heading1") -> out.append("<h1>").append(inner).append("</h1>\n")
                style == "subtitle" || style.startsWith("heading2") -> out.append("<h2>").append(inner).append("</h2>\n")
                style.startsWith("heading") -> out.append("<h3>").append(inner).append("</h3>\n")
                style.contains("quote")     -> out.append("<blockquote><p>").append(inner).append("</p></blockquote>\n")
                else                        -> out.append("<p>").append(inner).append("</p>\n")
            }
        }
        closeList()
        return out.toString()
    }

    /** One paragraph's runs, in order, carrying whatever emphasis each of them declares. */
    private fun Archive.renderRuns(
        paragraph: Element,
        rels: Map<String, String>
    ): String {
        val out = StringBuilder()
        for (run in paragraph.byLocalName("r")) {
            val properties = run.byLocalName("rPr").firstOrNull()
            val bold   = properties?.hasToggle("b") == true
            val italic = properties?.hasToggle("i") == true

            val text = StringBuilder()
            for (node in run.children()) {
                when (node.localName()) {
                    "t"   -> text.append(node.wholeText().escapeHtml())
                    "tab" -> text.append("&#9;")
                    "br", "cr" -> text.append("<br/>")
                    "drawing", "pict", "object" -> node.imageTag(this, rels)?.let { text.append(it) }
                }
            }
            if (text.isEmpty()) continue

            if (italic) { text.insert(0, "<em>"); text.append("</em>") }
            if (bold)   { text.insert(0, "<strong>"); text.append("</strong>") }
            out.append(text)
        }
        return out.toString()
    }

    /**
     * Word writes bold as `<w:b/>`, and un-bold — inside a style that was already bold — as
     * `<w:b w:val="0"/>`. Reading the presence of the tag alone would set half a document in bold.
     */
    private fun Element.hasToggle(name: String): Boolean {
        val toggle = children().firstOrNull { it.localName() == name } ?: return false
        val value = toggle.attrByLocalName("val")
        return value.isBlank() || value == "1" || value.equals("true", ignoreCase = true)
    }

    /**
     * The picture this drawing points at, as a finished tag — its size included where it can be
     * read out of the file's header, so the reader can measure its columns without waiting for
     * every picture in the document to be fetched and decoded first.
     */
    private fun Element.imageTag(
        archive: Archive,
        rels: Map<String, String>
    ): String? {
        val id = byLocalName("blip").firstOrNull()?.attrByLocalName("embed")?.takeIf { it.isNotBlank() }
            // The older VML shape, still emitted by Word for anything pasted from an old document.
            ?: byLocalName("imagedata").firstOrNull()?.attrByLocalName("id")?.takeIf { it.isNotBlank() }
            ?: return null
        val target = rels[id] ?: return null
        val url    = archive.archiveUrl("word", target) ?: return null
        val size   = archive.find("word", target)?.let { archive.pixelSize(it) }
        val dimensions =
            if (size != null) " width=\"${size.first}\" height=\"${size.second}\"" else ""
        return "<img$dimensions src=\"$url\"/>"
    }

    /** Relationship id → the file it points at, which is how a picture is named inside a .docx. */
    private fun Archive.relationships(): Map<String, String> {
        val entry = entryNamed("word/_rels/document.xml.rels") ?: return emptyMap()
        val doc   = xml(entry) ?: return emptyMap()
        return doc.byLocalName("Relationship").mapNotNull { rel ->
            val id     = rel.attrByLocalName("Id").ifBlank { return@mapNotNull null }
            val target = rel.attrByLocalName("Target").ifBlank { return@mapNotNull null }
            // "External" targets live on the network; there is nothing in the file to show.
            if (rel.attrByLocalName("TargetMode").equals("External", ignoreCase = true)) null
            else id to target
        }.toMap()
    }
}
