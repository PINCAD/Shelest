package com.lexiread.app.domain.parser

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB 2 and 3.
 *
 * The format is a zip holding a package document (the OPF) that lists every file in the book and
 * then states, separately, the order they are read in. Both halves are followed here rather than
 * either one alone, and every step has a way to carry on without it: a book whose spine cannot be
 * read is still a folder full of chapters in a sensible order, and showing those is enormously
 * better than showing nothing, which is what a strict reading produces.
 *
 * A book also carries its own table of contents — an XHTML `nav` in EPUB 3, an NCX in EPUB 2 —
 * with the titles the publisher wrote and the order they meant. That is read here too, and the
 * chapters are wrapped in anchors it can point at, so the contents in the reader are the book's
 * own rather than a list of whatever looked like a heading.
 */
class EpubParser : BookParser {

    override fun extractMetadata(file: File): BookMetadata = runCatching {
        ZipFile(file).use { zipFile ->
            val archive = Archive(zipFile)
            val pack    = archive.findPackage()
            val doc     = pack?.doc

            BookMetadata(
                title  = doc?.metaText("title").orEmpty(),
                author = doc?.metaText("creator").orEmpty(),
                // Counted from the text itself rather than from the size of the files, which is
                // mostly markup. This is what the reading pace and the "N hours left" estimate
                // are measured against, so an honest number here is worth the one extra pass.
                totalChars = archive.assemble(pack).chars,
                coverBytes = archive.coverBytes(pack)
            )
        }
    }.getOrElse { BookMetadata(title = file.nameWithoutExtension) }

    override fun extractContent(file: File): String = extractBook(file).html

    // Deliberately not guarded: a zip that cannot be opened is a real failure with a real
    // message, and the repository turns it into one the reader can see. Swallowing it here would
    // leave a blank page and nothing to go on.
    override fun extractBook(file: File): ParsedBook =
        ZipFile(file).use { zipFile ->
            val archive   = Archive(zipFile)
            val pack      = archive.findPackage()
            val assembled = archive.assemble(pack)
            ParsedBook(assembled.html, archive.tableOfContents(pack, assembled))
        }

    // ── The package document ─────────────────────────────────────────────────

    /** The OPF and the folder it sits in, which every href inside it is relative to. */
    private class Package(val doc: Document, val dir: String)

    private fun Archive.findPackage(): Package? {
        val path = containerRootPath()
            // No container.xml, or one that points nowhere. The package is still in the zip, and
            // there is only ever one file with that extension.
            ?: zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.endsWith(".opf", ignoreCase = true) }
                ?.name
            ?: return null

        val entry = entryNamed(path) ?: find("", path) ?: return null
        val doc   = xml(entry) ?: return null
        return Package(doc, entry.name.substringBeforeLast('/', ""))
    }

    private fun Archive.containerRootPath(): String? {
        val entry = entryNamed("META-INF/container.xml") ?: return null
        val doc   = xml(entry) ?: return null
        return doc.byLocalName("rootfile")
            .firstOrNull { it.hasAttr("full-path") }
            ?.attr("full-path")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * A Dublin Core field, prefixed or not, from the package metadata.
     *
     * The parent is matched loosely because an older EPUB nests the Dublin Core fields one level
     * further down, inside `<dc-metadata>`, and a book from 2009 has a title just the same.
     */
    private fun Document.metaText(name: String): String =
        byLocalName(name)
            .firstOrNull { it.parent()?.localName()?.contains("metadata") == true }
            ?.text()
            ?.trim()
            .orEmpty()

    private fun Package.manifest(): List<Element> = doc.pairedByLocalName("manifest", "item")

    // ── Reading order ────────────────────────────────────────────────────────

    private fun Archive.readingOrder(pack: Package?): List<ZipEntry> {
        if (pack == null) return allDocuments()

        val manifest = pack.manifest()
        val byId     = manifest.associateBy { it.attr("id") }

        val spine = pack.doc.pairedByLocalName("spine", "itemref")
            // linear="no" is the book saying this page is not part of the read-through — a
            // colophon, or the table of contents itself, which would otherwise open the book on
            // a list of its own chapters.
            .filter { !it.attr("linear").equals("no", ignoreCase = true) }
            .mapNotNull { byId[it.attr("idref")] }

        val items = spine.ifEmpty {
            // An unreadable spine is not an unreadable book. The manifest order is the order the
            // files were listed in, which is nearly always the order they are read in.
            manifest.filter { it.attr("media-type").contains("html", ignoreCase = true) }
        }

        val entries = items.mapNotNull { find(pack.dir, it.attr("href")) }
            .distinctBy { it.name }

        return entries.ifEmpty { allDocuments() }
    }

    // ── Assembly ─────────────────────────────────────────────────────────────

    /**
     * The finished document, plus what the table of contents needs to point into it.
     *
     * [chapterAnchors] maps a file in the zip to the id its chapter was wrapped in, and [ids] is
     * every id that ended up in the document — checked before pointing at one, because a contents
     * entry naming a place that isn't there should land at the top of the chapter rather than
     * nowhere at all.
     */
    private class Assembled(
        val html: String,
        val chars: Int,
        val chapterAnchors: Map<String, String>,
        val ids: Set<String>
    )

    private fun Archive.assemble(pack: Package?): Assembled {
        val order   = readingOrder(pack)
        val anchors = LinkedHashMap<String, String>()
        val ids     = HashSet<String>()
        var chars   = 0

        val assembledHtml = buildString {
            order.forEachIndexed { index, entry ->
                val doc  = html(entry) ?: return@forEachIndexed
                val body = doc.body()
                val dir  = entry.name.substringBeforeLast('/', "")

                sanitizeBody(
                    body,
                    images = { src -> archiveUrl(dir, src) },
                    // Read from the picture's header inside the zip, not by decoding it. Without
                    // it the reader cannot measure a single column until every illustration in
                    // the book has been fetched and decoded.
                    sizes  = { src -> find(dir, src)?.let { pixelSize(it) } }
                )

                // Two chapters that were separate files may each hold an id="note1"; in one
                // document only the first of them would be findable. Prefixing by chapter keeps
                // every anchor the book declared reachable, and the contents is rewritten to
                // match a few lines below.
                val prefix = chapterAnchor(index)
                body.select("[id]").forEach { element ->
                    val moved = prefix + "-" + element.id()
                    element.attr("id", moved)
                    ids += moved
                }

                val piece = body.html()
                if (piece.isBlank()) return@forEachIndexed
                chars += body.text().length
                anchors[entry.name] = prefix

                append("<div id=\"").append(prefix).append("\">\n")
                append(piece)
                append("\n</div>\n")
            }
        }
        return Assembled(assembledHtml, chars, anchors, ids)
    }

    private fun chapterAnchor(index: Int) = "shl-c$index"

    // ── The book's own table of contents ─────────────────────────────────────

    private fun Archive.tableOfContents(pack: Package?, assembled: Assembled): List<TocEntry> {
        if (pack == null || assembled.chapterAnchors.isEmpty()) return emptyList()
        val fromNav = navEntries(pack, assembled)
        return fromNav.ifEmpty { ncxEntries(pack, assembled) }
    }

    /** EPUB 3: an ordinary XHTML page marked in the manifest as the navigation document. */
    private fun Archive.navEntries(pack: Package, assembled: Assembled): List<TocEntry> {
        val item = pack.manifest().firstOrNull {
            it.attr("properties").contains("nav", ignoreCase = true)
        } ?: return emptyList()
        val entry = find(pack.dir, item.attr("href")) ?: return emptyList()
        val doc   = html(entry) ?: return emptyList()
        val dir   = entry.name.substringBeforeLast('/', "")

        // A navigation document may hold several: the contents, the list of illustrations, the
        // page list. Only the first is a table of contents.
        val navs = doc.byLocalName("nav")
        val toc  = navs.firstOrNull { it.attrByLocalName("type").contains("toc", ignoreCase = true) }
            ?: navs.firstOrNull()
            ?: return emptyList()

        return toc.byLocalName("a").mapNotNull { link ->
            val title  = link.text().trim().ifBlank { return@mapNotNull null }
            val anchor = anchorFor(dir, link.attr("href"), assembled) ?: return@mapNotNull null
            // Minus one: a navigation document always wraps its top level in an <ol>, so counting
            // the lists above a link would report every chapter as nested inside something. An NCX
            // has no such wrapper, which is why only this side subtracts.
            TocEntry(title, anchor, (depthOf(link, toc, "ol") - 1).coerceAtLeast(1))
        }
    }

    /** EPUB 2: a separate NCX file, named by the spine or by its own media type. */
    private fun Archive.ncxEntries(pack: Package, assembled: Assembled): List<TocEntry> {
        val manifest = pack.manifest()
        val byId     = manifest.associateBy { it.attr("id") }

        val tocId = pack.doc.byLocalName("spine").firstOrNull()?.attr("toc")
        val item  = byId[tocId]
            ?: manifest.firstOrNull { it.attr("media-type").contains("dtbncx", ignoreCase = true) }
            ?: manifest.firstOrNull { it.attr("href").endsWith(".ncx", ignoreCase = true) }

        val entry = item?.let { find(pack.dir, it.attr("href")) }
            ?: zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.endsWith(".ncx", ignoreCase = true) }
            ?: return emptyList()

        val doc = xml(entry) ?: return emptyList()
        val dir = entry.name.substringBeforeLast('/', "")

        return doc.byLocalName("navPoint").mapNotNull { point ->
            val title = point.byLocalName("text").firstOrNull()?.text()?.trim()
                ?.ifBlank { null } ?: return@mapNotNull null
            val src = point.byLocalName("content").firstOrNull()?.attr("src").orEmpty()
            val anchor = anchorFor(dir, src, assembled) ?: return@mapNotNull null
            TocEntry(title, anchor, depthOf(point, doc, "navpoint"))
        }
    }

    /**
     * Where a contents entry lands in the assembled document.
     *
     * A book names a file and, often, a place inside it. The file becomes the chapter it was
     * assembled into; the place becomes that chapter's copy of the id — and if the book points at
     * an id that never made it into the document, the top of the chapter is a better answer than
     * refusing to move.
     */
    private fun Archive.anchorFor(dir: String, href: String, assembled: Assembled): String? {
        if (href.isBlank()) return null
        val file    = href.substringBefore('#')
        val chapter = assembled.chapterAnchors[find(dir, file)?.name] ?: return null

        val fragment = href.substringAfter('#', "")
        if (fragment.isBlank()) return chapter
        for (form in listOf(fragment, decodeFragment(fragment))) {
            val candidate = "$chapter-$form"
            if (candidate in assembled.ids) return candidate
        }
        return chapter
    }

    private fun decodeFragment(fragment: String): String =
        runCatching { java.net.URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment)

    /** How deeply [element] is nested in [root] by tags named [wrapper], counted from one. */
    private fun depthOf(element: Element, root: Element, wrapper: String): Int {
        var level = 1
        var parent = element.parent()
        while (parent != null && parent !== root) {
            if (parent.localName() == wrapper) level++
            parent = parent.parent()
        }
        return level.coerceAtMost(4)
    }

    // ── Cover ────────────────────────────────────────────────────────────────

    private fun Archive.coverBytes(pack: Package?): ByteArray? {
        if (pack == null) return null
        val manifest = pack.manifest()

        // 1. EPUB 3 says so outright.
        var href = manifest.firstOrNull {
            it.attr("properties").contains("cover-image", ignoreCase = true)
        }?.attr("href")

        // 2. EPUB 2 points at a manifest id from the metadata. Matched by hand rather than
        //    through a built selector: the id comes out of the book, and one carrying a bracket
        //    or a quote would make that an invalid selector — whose parse error would cost the
        //    title and the author too, and a missing cover is not worth an untitled book.
        if (href.isNullOrBlank()) {
            val id = pack.doc.byLocalName("meta")
                .firstOrNull { it.attr("name").equals("cover", ignoreCase = true) }
                ?.attr("content")
            if (!id.isNullOrBlank()) {
                href = manifest.firstOrNull { it.attr("id") == id }?.attr("href")
            }
        }

        // 3. An image that says "cover" in its own name.
        if (href.isNullOrBlank()) {
            href = manifest
                .filter { it.attr("media-type").startsWith("image", ignoreCase = true) }
                .firstOrNull { it.attr("href").contains("cover", ignoreCase = true) }
                ?.attr("href")
        }

        // 4. Whatever picture the first page shows. A book that opens on a full-page image has a
        //    cover whether or not it ever said so.
        if (href.isNullOrBlank()) {
            href = readingOrder(pack).firstOrNull()
                ?.let { html(it) }
                ?.let { firstImageHref(it.body()) }
        }

        val target = href?.takeIf { it.isNotBlank() } ?: return null
        val entry  = find(pack.dir, target) ?: return null
        return bytes(entry)
    }

    private fun firstImageHref(body: Element): String? {
        body.select("img").firstOrNull()?.attr("src")?.takeIf { it.isNotBlank() }?.let { return it }
        return body.byLocalName("image").firstOrNull()
            ?.let { it.attr("xlink:href").ifBlank { it.attr("href") } }
            ?.takeIf { it.isNotBlank() }
    }
}
