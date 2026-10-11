package com.lexiread.app.domain.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Shared machinery for the formats that are really archives of markup — EPUB and DOCX — plus the
 * one thing every imported document needs: to be cut back to markup this reader can style.
 */

/**
 * A tag name with its namespace prefix taken off, lowercased.
 *
 * An OPF written as `<opf:manifest><opf:item/></opf:manifest>` is as valid as one written without
 * the prefix, and the CSS-style selectors jsoup offers match the literal tag name — so a book from
 * a converter that prefixes everything used to come back with an empty spine and open blank.
 */
internal fun Element.localName(): String = tagName().substringAfterLast(':').lowercase()

/** Every element under this one (and this one) whose tag is [name], whatever its namespace. */
internal fun Element.byLocalName(name: String): List<Element> {
    val wanted = name.lowercase()
    // Through toList first: jsoup's Elements has a filter(NodeFilter) of its own, and a member
    // beats an extension, so the lambda below would be read as a tree-walking callback.
    return allElements.toList().filter { it.localName() == wanted }
}

/** The children of the element called [parent] that are themselves called [child]. */
internal fun Element.pairedByLocalName(parent: String, child: String): List<Element> {
    val wantedParent = parent.lowercase()
    return byLocalName(child).filter { it.parent()?.localName() == wantedParent }
}

/** An attribute read without its prefix: `w:val` and `val` are the same attribute. */
internal fun Element.attrByLocalName(name: String): String =
    attributes().firstOrNull { it.key.substringAfterLast(':').equals(name, ignoreCase = true) }
        ?.value.orEmpty()

/**
 * A zip opened for reading a book out of.
 *
 * The lookups exist because an href inside a book and a name inside its zip agree far less often
 * than the formats pretend. An href is a URI, so a chapter called "глава 1.xhtml" is written
 * percent-encoded while the zip stores it as typed. It may be relative to the folder the manifest
 * lives in, or reach out of it with "../". And a book repacked on a case-insensitive filesystem
 * can end up with "Text/Ch1.xhtml" in the zip and "text/ch1.xhtml" in the manifest. Every one of
 * those used to mean the chapter was silently skipped.
 */
internal class Archive(val zip: ZipFile) {

    private val byPath: Map<String, ZipEntry>
    private val byFileName: Map<String, ZipEntry>

    init {
        val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
        byPath = entries.associateBy { it.name.lowercase() }
        byFileName = entries.groupBy { it.name.substringAfterLast('/').lowercase() }
            .mapValues { (_, group) -> group.first() }
    }

    /** The entry [href] points at, read as relative to [dir], or null if nothing matches. */
    fun find(dir: String, href: String): ZipEntry? {
        if (href.isBlank()) return null
        val clean = href.substringBefore('#').substringBefore('?')
        if (clean.isBlank()) return null
        val candidates = LinkedHashSet<String>()
        for (form in listOf(decode(clean), clean)) {
            if (form.isBlank()) continue
            if (dir.isNotEmpty() && !form.startsWith("/")) candidates += normalize("$dir/$form")
            candidates += normalize(form)
        }
        for (candidate in candidates) {
            zip.getEntry(candidate)?.let { return it }
            byPath[candidate.lowercase()]?.let { return it }
        }
        // Last resort: the file name alone. A manifest that disagrees with the zip about which
        // folder a chapter is in is still telling the truth about which chapter it is.
        return candidates.firstNotNullOfOrNull {
            byFileName[it.substringAfterLast('/').lowercase()]
        }
    }

    fun entryNamed(path: String): ZipEntry? = zip.getEntry(path) ?: byPath[path.lowercase()]

    fun bytes(entry: ZipEntry): ByteArray? =
        runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()

    /**
     * How large a picture inside the book is, from its header rather than by decoding it — so the
     * document can state the size and be laid out before any picture has been fetched.
     */
    fun pixelSize(entry: ZipEntry): Pair<Int, Int>? =
        runCatching { zip.getInputStream(entry).use { imagePixelSizeOf(it) } }.getOrNull()

    /**
     * A document parsed with the charset it declares rather than the one we hope for. Books
     * converted from FB2 still turn up in windows-1251, and jsoup reads the meta tag and the XML
     * declaration for us — which reading the stream as text would have thrown away first.
     */
    fun html(entry: ZipEntry): Document? =
        runCatching { zip.getInputStream(entry).use { Jsoup.parse(it, null, "") } }.getOrNull()

    fun xml(entry: ZipEntry): Document? = runCatching {
        zip.getInputStream(entry).use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }.getOrNull()

    /** Every markup document in the archive, in a human's idea of order. */
    fun allDocuments(): List<ZipEntry> =
        zip.entries().asSequence()
            .filter { !it.isDirectory && isMarkup(it.name) }
            .sortedWith(Comparator { a, b -> compareNatural(a.name, b.name) })
            .toList()

    private fun isMarkup(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "xhtml" || ext == "html" || ext == "htm"
    }

    private fun decode(href: String): String = runCatching {
        // "+" is a space only in a query string; in a path it is a plus, and books whose file
        // names contain one are not rare.
        java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(href)

    private fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".."    -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else    -> out.add(part)
            }
        }
        return out.joinToString("/")
    }
}

/**
 * The path under the reader's own origin that everything inside the book is served from.
 *
 * Pictures in a book are entries in its archive, and the document handed to the WebView has no
 * folder to resolve them against. They used to be carried inside the document as data URIs — four
 * bytes for every three, all of it in memory before the first page could be shown, which is why
 * there was a budget dropping the pictures past three megabytes. The reader serves the archive
 * itself now (see BookAssetServer), so a picture costs nothing until the page it is on is reached,
 * and an illustrated book has no ceiling to run into.
 */
internal const val ARCHIVE_PATH = "/res/"

/**
 * The path pictures are served from for a format that carries them *inside* the book rather than
 * beside it — which in practice means FB2, where every illustration is base64 in the middle of the
 * text.
 *
 * There is nothing for the reader to serve them out of, so they are written out once, next to the
 * book, and asked for by name from there. The alternative is what this used to do: paste every
 * picture into the document as a data URI, which on a large illustrated book meant tens of
 * megabytes of base64 built in memory, held in memory, and handed to the WebView before a single
 * word could be shown.
 */
internal const val MEDIA_PATH = "/media/"

/**
 * Where [book]'s own pictures are unpacked to. Both the parser that writes them and the server
 * that reads them work this out from the book's path alone, so neither has to be told.
 */
internal fun mediaDirFor(book: java.io.File): java.io.File =
    java.io.File(book.parentFile ?: book.absoluteFile.parentFile, ".media/${mediaSafeName(book.name)}")

/**
 * A name safe to use as a file name, for something named by the book — a picture's id, or the
 * book's own name. Anything that is not a letter, a digit or one of a few harmless marks becomes
 * an underscore, which rules out a separator or a "..", and leaves Cyrillic names readable.
 */
internal fun mediaSafeName(raw: String): String {
    val cleaned = buildString(raw.length) {
        for (ch in raw) append(if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_')
    }.trimStart('.')
    // Long enough for any real name, short enough to stay under the filesystem's own limit once
    // a multi-byte alphabet is encoded.
    return if (cleaned.isBlank()) "unnamed" else cleaned.take(80)
}

/** [href] as a URL the reader can fetch out of the book, or null if there is nothing to fetch. */
internal fun Archive.archiveUrl(dir: String, href: String): String? {
    if (href.isBlank()) return null
    if (href.startsWith("data:")) return href
    // A book that reaches out to the network for a picture doesn't get to; the reader is offline
    // by nature and a hanging request would hold up the page.
    if (href.startsWith("http://") || href.startsWith("https://")) return null

    val entry = find(dir, href) ?: return null
    return ARCHIVE_PATH + entry.name.split('/').joinToString("/") { encodePathSegment(it) }
}

/**
 * One path segment, escaped for a URL.
 *
 * `URLEncoder` is a form encoder rather than a path encoder — it writes a space as `+`, which in a
 * path means a literal plus. A chapter called "глава 1.jpg" would come back asking for
 * "глава+1.jpg", which is not in the zip.
 */
internal fun encodePathSegment(segment: String): String =
    java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

/**
 * The first [count] bytes of [file] — fewer if that is all there is.
 *
 * `InputStream.readNBytes` does exactly this and is not on Android before 13. Against a compile
 * target of 34 it builds without complaint and then throws NoSuchMethodError on a phone running
 * anything older, which for this app's minimum of Android 8 means every book of that format
 * failing to open on a device nobody here has to test on.
 */
internal fun readHead(file: java.io.File, count: Int): ByteArray {
    val head = ByteArray(count)
    var filled = 0
    file.inputStream().use { stream ->
        while (filled < count) {
            val read = stream.read(head, filled, count - filled)
            if (read <= 0) break
            filled += read
        }
    }
    return if (filled == count) head else head.copyOf(filled)
}

/**
 * How large a picture is, in its own pixels, read out of the front of the file.
 *
 * This exists so the document can *state* the size of every illustration it refers to. A column
 * layout cannot be measured until it knows how tall everything in it is, so a document whose
 * pictures have no stated size cannot be paginated until every one of them has been fetched and
 * decoded — on a book of plates that is several hundred decodes standing between the reader and
 * the first page, all but one of them for a page they are nowhere near. Stated up front, the
 * columns are measured immediately and the pictures arrive whenever they arrive.
 *
 * Null when the format is not one of these, or the file is not what its name says. The caller
 * then says nothing, which is what the document did before.
 */
internal fun imagePixelSize(file: java.io.File): Pair<Int, Int>? =
    // Enough for any header, and for the JPEG case enough to walk a good many segments; a JPEG
    // whose dimensions are further in than this is one nobody has.
    runCatching { imagePixelSize(readHead(file, 128 * 1024)) }.getOrNull()

/**
 * The same, for a picture that is not a file of its own — a page inside a comic's archive, which
 * has to be inflated to be looked at.
 *
 * [budget] is deliberately smaller than the version above: a comic is several hundred pages, and
 * every byte read here is a byte inflated, so the saving of not decoding the whole comic has to
 * not be spent on reading the whole comic.
 */
internal fun imagePixelSizeOf(stream: java.io.InputStream, budget: Int = 64 * 1024): Pair<Int, Int>? =
    runCatching {
        val head = ByteArray(budget)
        var filled = 0
        while (filled < budget) {
            val read = stream.read(head, filled, budget - filled)
            if (read <= 0) break
            filled += read
        }
        imagePixelSize(if (filled == budget) head else head.copyOf(filled))
    }.getOrNull()

private fun imagePixelSize(head: ByteArray): Pair<Int, Int>? = runCatching {
    when {
        head.size >= 24 && head.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) ->
            // IHDR is the first chunk and always at the same place.
            beInt(head, 16) to beInt(head, 20)

        head.size >= 10 && head.startsWith('G'.code, 'I'.code, 'F'.code) ->
            leShort(head, 6) to leShort(head, 8)

        head.size >= 30 && head.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) -> webpSize(head)

        head.size >= 4 && head.startsWith(0xFF, 0xD8) -> jpegSize(head)

        else -> null
    }?.takeIf { it.first in 1..40_000 && it.second in 1..40_000 }
}.getOrNull()

private fun ByteArray.startsWith(vararg bytes: Int): Boolean {
    if (size < bytes.size) return false
    for (i in bytes.indices) if (this[i] != bytes[i].toByte()) return false
    return true
}

private fun beInt(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
        ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

private fun leShort(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

private fun beShort(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

/** The three WEBP encodings, which agree on the container and on nothing else. */
private fun webpSize(head: ByteArray): Pair<Int, Int>? {
    if (head[8] != 'W'.code.toByte()) return null
    val chunk = String(head, 12, 4, Charsets.ISO_8859_1)
    return when (chunk) {
        // Lossy: the dimensions follow the three-byte start code inside the VP8 bitstream.
        "VP8 " -> if (head.size >= 30) (leShort(head, 26) and 0x3FFF) to (leShort(head, 28) and 0x3FFF)
                  else null
        // Lossless: fourteen bits each, packed across four bytes, and one less than the real size.
        "VP8L" -> if (head.size >= 25) {
            val bits = (head[21].toInt() and 0xFF) or ((head[22].toInt() and 0xFF) shl 8) or
                ((head[23].toInt() and 0xFF) shl 16) or ((head[24].toInt() and 0xFF) shl 24)
            ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
        } else null
        // Extended: three bytes each, little-endian, and again one less than the real size.
        "VP8X" -> if (head.size >= 30) {
            val w = (head[24].toInt() and 0xFF) or ((head[25].toInt() and 0xFF) shl 8) or
                ((head[26].toInt() and 0xFF) shl 16)
            val h = (head[27].toInt() and 0xFF) or ((head[28].toInt() and 0xFF) shl 8) or
                ((head[29].toInt() and 0xFF) shl 16)
            (w + 1) to (h + 1)
        } else null
        else -> null
    }
}

/**
 * A JPEG says nothing about its size at the front; it has to be walked segment by segment until
 * the frame header. Everything before it is a marker and a length, so the walk is short.
 */
private fun jpegSize(head: ByteArray): Pair<Int, Int>? {
    var at = 2
    while (at + 9 < head.size) {
        if (head[at] != 0xFF.toByte()) {
            at++
            continue
        }
        val marker = head[at + 1].toInt() and 0xFF
        // Padding between segments, and the standalone markers that carry no length.
        if (marker == 0xFF) { at++; continue }
        if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { at += 2; continue }
        val length = beShort(head, at + 2)
        if (length < 2) return null
        // The frame headers, of which SOF0 and SOF2 are all anybody writes. C4, C8 and CC are a
        // Huffman table, an extension and an arithmetic table wearing the same numbering.
        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            return beShort(head, at + 7) to beShort(head, at + 5)
        }
        at += 2 + length
    }
    return null
}

internal fun imageMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png"          -> "image/png"
    "gif"          -> "image/gif"
    "svg"          -> "image/svg+xml"
    "webp"         -> "image/webp"
    "bmp"          -> "image/bmp"
    else           -> "image/jpeg"
}

/** Tags that carry no reading matter, and several that would fetch or run something. */
private const val DROPPED_TAGS =
    "script, style, link, meta, noscript, iframe, object, embed, form, input, " +
    "select, textarea, button, audio, video, source, track, base"

/**
 * Attributes worth keeping. Everything else — and `style` above all — is dropped on purpose.
 *
 * A book's own stylesheet never arrives here: it is a separate file in the archive, and the reader
 * shows one document assembled out of many. What used to survive was the inline styles, which is
 * the worst of both worlds — a book that pinned its text at 11pt in a fixed colour ignored the
 * font size, the line spacing and the page theme, and the reader's settings appeared broken. `href`
 * goes too: a footnote link inside a WebView navigates away from the whole book, and the note it
 * points at is in the text below anyway.
 *
 * `id` stays, because it is what a table of contents points at. A book's own contents names a file
 * and, often, a place inside it — and once the whole book is one document, that place is an id and
 * nothing else. They are made unique per chapter by whoever assembles the document, since two
 * chapters that were separate files are free to have used the same id.
 */
private val KEPT_ATTRIBUTES = setOf("src", "alt", "colspan", "rowspan", "id")

/** Kept only on a picture, and only when they are pixels — see the check in [sanitizeBody]. */
private val SIZE_ATTRIBUTES = setOf("width", "height")

/**
 * Cuts an imported document back to plain semantic markup, resolving its pictures through [images].
 *
 * [images] is handed the `src` as the document wrote it and returns what to put there instead, or
 * null to drop the picture — a broken-image icon in the middle of a paragraph is worse than no
 * picture at all.
 */
internal fun sanitizeBody(
    body: Element,
    images: (String) -> String?,
    sizes: (String) -> Pair<Int, Int>? = { null }
) {
    body.select(DROPPED_TAGS).remove()

    // A cover page is very often an <svg> wrapping a single <image>, which is markup this reader
    // has no use for; the picture inside it is the whole point of the page.
    body.select("svg").forEach { svg ->
        val href = svg.byLocalName("image").firstOrNull()?.let {
            it.attr("xlink:href").ifBlank { it.attr("href") }
        }
        if (href.isNullOrBlank()) svg.remove()
        else svg.replaceWith(Element("img").attr("src", href))
    }

    body.select("img").forEach { img ->
        val href = img.attr("src")
        val data = images(href)
        if (data == null) {
            img.remove()
            return@forEach
        }
        img.attr("src", data)
        // Stated up front so the columns can be measured from the markup alone. Whatever the book
        // itself said is overwritten rather than trusted: a picture's real size is in its own
        // header, and an EPUB's width attribute is as often "100%" or a leftover from a different
        // edition. Where nothing can be read, nothing is claimed.
        sizes(href)?.let { (width, height) ->
            img.attr("width", width.toString())
            img.attr("height", height.toString())
        }
    }

    body.allElements.forEach { el ->
        el.attributes().asList().forEach { attr ->
            val key = attr.key.lowercase()
            // A size is kept only when it is a plain number of pixels, which is the only form
            // that tells the layout anything before the picture arrives.
            val keep = key in KEPT_ATTRIBUTES ||
                (key in SIZE_ATTRIBUTES && attr.value.isNotEmpty() && attr.value.all { it.isDigit() })
            if (!keep) el.removeAttr(attr.key)
        }
    }
}

/**
 * How many characters [html] becomes once a browser has parsed it.
 *
 * Not the same number as `plainTextOf(html).length`, and deliberately so. That one is the book as
 * a person reads it — runs of whitespace squeezed to one space, a space put in wherever a tag
 * separated two words. This one is the book as the *document* holds it: exactly the characters
 * that end up inside text nodes, whitespace and all.
 *
 * It exists because the reader is being handed its book a piece at a time, and every piece has to
 * arrive with the character it begins at. The reader works out where it is by walking the text
 * nodes of what it has been given, so a piece whose stated length was counted by any other rule
 * would put the reader's place a little further out with every piece that went by. Two measures,
 * counted two ways, cannot be added together — so this is the one that matches what the page will
 * find when it counts for itself.
 *
 * The rules are the parser's own: a tag contributes nothing, a comment contributes nothing, an
 * entity contributes the character it stands for, and CRLF — which the parser turns into a single
 * newline before anything else sees it — contributes one.
 */
internal fun domTextLengthOf(html: String): Int {
    var count = 0
    var i = 0
    while (i < html.length) {
        val ch = html[i]
        when {
            ch == '<' -> {
                i = if (html.startsWith("<!--", i)) {
                    val end = html.indexOf("-->", i + 4)
                    if (end < 0) html.length else end + 3
                } else {
                    val close = html.indexOf('>', i + 1)
                    if (close < 0) html.length else close + 1
                }
            }
            ch == '&' -> {
                val semicolon = html.indexOf(';', i + 1)
                if (semicolon < 0 || semicolon - i > 12) {
                    count++
                    i++
                } else {
                    count += htmlEntityChar(html.substring(i + 1, semicolon)).length
                    i = semicolon + 1
                }
            }
            ch == '\r' -> {
                count++
                i += if (i + 1 < html.length && html[i + 1] == '\n') 2 else 1
            }
            else -> {
                count++
                i++
            }
        }
    }
    return count
}

/**
 * [html] as the words in it: tags dropped, entities resolved, runs of whitespace collapsed to one
 * space — the same text the page itself sees, which is what lets the search count occurrences here
 * and have the page find the same one.
 */
internal fun plainTextOf(html: String): String {
    val out = StringBuilder(html.length / 2)
    var i = 0
    var lastSpace = true
    while (i < html.length) {
        val ch = html[i]
        when {
            ch == '<' -> {
                val close = html.indexOf('>', i + 1)
                if (close < 0) break
                i = close + 1
                // A tag is a word boundary: "…конец</p><p>Начало…" is two words, not one.
                if (!lastSpace) {
                    out.append(' ')
                    lastSpace = true
                }
            }
            ch == '&' -> {
                val semicolon = html.indexOf(';', i + 1)
                if (semicolon < 0 || semicolon - i > 12) {
                    out.append(ch)
                    lastSpace = false
                    i++
                } else {
                    out.append(htmlEntityChar(html.substring(i + 1, semicolon)))
                    lastSpace = false
                    i = semicolon + 1
                }
            }
            ch.isWhitespace() -> {
                if (!lastSpace) {
                    out.append(' ')
                    lastSpace = true
                }
                i++
            }
            else -> {
                out.append(ch)
                lastSpace = false
                i++
            }
        }
    }
    return out.toString().trim()
}

private fun htmlEntityChar(body: String): String = when {
    body.equals("amp", true)    -> "&"
    body.equals("lt", true)     -> "<"
    body.equals("gt", true)     -> ">"
    body.equals("quot", true)   -> "\""
    body.equals("apos", true)   -> "'"
    // Kept as it is rather than turned into a plain space: the page shows a non-breaking space
    // too, and a search counting characters has to see the same ones the page does.
    body.equals("nbsp", true)   -> "\u00A0"
    body.startsWith("#x", true) -> codePointText(body.drop(2).toIntOrNull(16))
    body.startsWith("#")        -> codePointText(body.drop(1).toIntOrNull())
    // Something nobody declared. One character stood here, so one stands here now.
    else                        -> " "
}

private fun codePointText(point: Int?): String =
    if (point != null && point in 1..0x10FFFF) {
        runCatching { String(Character.toChars(point)) }.getOrDefault(" ")
    } else " "

/** Text on its way into markup this reader builds by hand. */
internal fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * "ch2" before "ch10", which plain string order gets backwards — and plain string order is all
 * there is to go on when a book has no manifest to state its own reading order.
 */
internal fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
            // Compared as numbers, but only as far as Long reaches: a run of forty digits is a
            // hash, not a chapter number, and falling back to text order for it is fine.
            val na = a.substring(i, ei).trimStart('0').ifEmpty { "0" }
            val nb = b.substring(j, ej).trimStart('0').ifEmpty { "0" }
            if (na.length != nb.length) return na.length - nb.length
            val cmp = na.compareTo(nb)
            if (cmp != 0) return cmp
            i = ei; j = ej
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
}
