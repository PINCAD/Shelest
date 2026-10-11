package com.lexiread.app.domain.parser

import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * FB2 — one XML file carrying the whole book, its pictures included, and the format most of this
 * reader's books arrive in.
 *
 * It is read a character at a time and turned into markup as it goes, rather than parsed into a
 * tree first. That is the whole point of this file. A twenty-megabyte FB2 is an ordinary thing —
 * a collected works, or a novel with plates — and building a document tree out of one costs
 * several times its own size in memory, all of it live at once, before a single word can be shown.
 * On a phone that is not slowness, it is minutes of garbage collection and, often enough, being
 * killed for it. Scanning instead means the memory in play is one paragraph, plus the document
 * being written out.
 *
 * The pictures are written out beside the book on the way past and asked for by name afterwards
 * (see [MEDIA_PATH]); they used to be pasted into the document as base64 data URIs, which on an
 * illustrated book was tens of megabytes of text nobody reads.
 *
 * The scanner is deliberately forgiving where a real XML parser is not. Books in the wild are full
 * of undeclared entities, stray ampersands and tags that never close, and a strict parser answers
 * all of those the same way: by refusing the entire book. Here an ampersand that leads nowhere is
 * an ampersand.
 */
class Fb2Parser : BookParser {

    override fun extractMetadata(file: File): BookMetadata = runCatching {
        val reading = Fb2Reading(mediaDir = null, wantsDocument = false, sizeHint = 0L)
        scan(file, reading)
        BookMetadata(
            title      = reading.bookTitle.ifBlank { file.nameWithoutExtension },
            author     = reading.author(),
            totalChars = reading.chars,
            coverBytes = reading.cover()
        )
    }.getOrElse { BookMetadata(title = file.nameWithoutExtension) }

    override fun extractContent(file: File): String = extractBook(file).html

    override fun extractBook(file: File): ParsedBook = runCatching {
        val reading = Fb2Reading(
            mediaDir      = mediaDirFor(file),
            wantsDocument = true,
            sizeHint      = file.length()
        )
        scan(file, reading)
        ParsedBook(reading.document(), reading.toc)
    }.getOrElse { ParsedBook("<p>Ошибка чтения FB2: ${it.message}</p>") }

    private fun scan(file: File, reading: Fb2Reading) {
        openReader(file).use { reader -> XmlScanner(reader, reading).run() }
    }

    /**
     * The book's text as characters, in whatever alphabet it was written in.
     *
     * The charset is read off the front of the file rather than assumed: FB2 predates UTF-8 being
     * the default anywhere, and a good half of the Russian-language ones still in circulation are
     * windows-1251.
     */
    private fun openReader(file: File): Reader {
        val stream = BufferedInputStream(openStream(file), 64 * 1024)
        stream.mark(HEADER_BYTES)
        val head = ByteArray(HEADER_BYTES)
        var filled = 0
        while (filled < HEADER_BYTES) {
            val read = stream.read(head, filled, HEADER_BYTES - filled)
            if (read <= 0) break
            filled += read
        }
        stream.reset()
        // Latin-1 so that every byte becomes some character rather than a decoding failure; the
        // declaration being looked for is ASCII either way.
        return InputStreamReader(stream, charsetOf(String(head, 0, filled, Charsets.ISO_8859_1)))
    }

    private fun charsetOf(header: String): Charset = runCatching {
        when {
            header.contains("windows-1251", true) || header.contains("cp1251", true) ->
                Charset.forName("windows-1251")
            header.contains("koi8", true)       -> Charset.forName("KOI8-R")
            header.contains("iso-8859-5", true) -> Charset.forName("ISO-8859-5")
            header.contains("windows-1252", true) || header.contains("cp1252", true) ->
                Charset.forName("windows-1252")
            else -> Charsets.UTF_8
        }
    }.getOrDefault(Charsets.UTF_8)

    /**
     * The book itself, opened for reading — out of its zip if it is in one, which is how nearly
     * every FB2 is handed round.
     *
     * Two bytes decide that, not the whole file: this used to load the entire book to look at the
     * front of it, and then load it a second time out of the archive.
     */
    private fun openStream(file: File): InputStream {
        val magic = readHead(file, 2)
        val zipped = magic.size == 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
        if (!zipped) return file.inputStream()

        val zip = ZipFile(file)
        val entry = runCatching {
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            entries.firstOrNull { it.name.endsWith(".fb2", ignoreCase = true) } ?: entries.firstOrNull()
        }.getOrNull()
        if (entry == null) {
            zip.close()
            return file.inputStream()
        }
        // The archive has to outlive the stream taken out of it, and be closed with it.
        return object : FilterInputStream(zip.getInputStream(entry)) {
            override fun close() {
                try { super.close() } finally { zip.close() }
            }
        }
    }

    private companion object {
        /** Enough to cover an XML declaration however much whitespace is around it. */
        const val HEADER_BYTES = 512
    }
}

/**
 * One pass over one book: what has been read so far, and the document being built out of it.
 *
 * The same class does both jobs the parser has. Asked for metadata it builds no document and
 * writes no pictures, so importing a book onto the shelf costs one read of the file and nothing
 * else; asked for the book itself it builds the markup and unpacks the illustrations.
 */
private class Fb2Reading(
    private val mediaDir: File?,
    private val wantsDocument: Boolean,
    sizeHint: Long
) {

    /**
     * Sized from the file up front. A document that grows from nothing spends the opening of a
     * long book copying itself into a bigger buffer over and over; half the file's own length is
     * a fair guess at the markup, since an FB2's other half tends to be base64.
     */
    private val out = StringBuilder(
        if (wantsDocument) (sizeHint / 2).coerceIn(16_384L, 16_000_000L).toInt() else 16
    )

    val toc = mutableListOf<TocEntry>()

    /** Where in [out] each illustration's size has to be written, and which picture it is. */
    private val imageSpots = mutableListOf<Pair<Int, String>>()
    /** A picture repeated through a book — a divider, a drop cap — is measured once. */
    private val imageSizes = HashMap<String, String>()

    /** Reading matter, counted the way a person would: markup and base64 are not text. */
    var chars = 0
        private set

    // ── where the scanner currently is ───────────────────────────────────────

    private var inDescription = false
    private var inTitleInfo   = false
    private var inCoverpage   = false
    private var inAuthor      = false
    private var authorDone    = false

    private val titleText  = StringBuilder()
    private val firstName  = StringBuilder()
    private val lastName   = StringBuilder()
    /** The description field text is going into, or null when it is going nowhere. */
    private var field: StringBuilder? = null

    private var bodyDepth    = 0
    private var sectionDepth = 0

    /** The heading being assembled, or null outside one. */
    private var heading: StringBuilder? = null
    private var headingTag    = "h2"
    private var headingAnchor = ""
    private var headingListed = false
    private var anchorCount   = 0

    private var inBinary      = false
    private var binaryBuffer: StringBuilder? = null
    private var binaryTarget: File? = null
    private var binaryIsCover = false
    private var coverRef: String? = null
    private var coverBytes: ByteArray? = null
    private var firstPicture: ByteArray? = null

    // ── what the scanner reports ─────────────────────────────────────────────

    fun start(tag: String, attrs: Map<String, String>) {
        when (tag) {
            "description" -> inDescription = true
            "title-info"  -> if (inDescription) inTitleInfo = true
            "coverpage"   -> if (inTitleInfo) inCoverpage = true
            "author"      -> if (inTitleInfo && !authorDone) inAuthor = true
            "book-title"  -> if (inTitleInfo) field = titleText
            "first-name"  -> if (inAuthor) field = firstName
            "last-name"   -> if (inAuthor) field = lastName
            "body"        -> bodyDepth++
            "binary"      -> beginBinary(attrs)
            "image"       -> if (inCoverpage) {
                if (coverRef == null) coverRef = href(attrs)
            } else if (bodyDepth > 0) {
                image(attrs)
            }
            else          -> if (bodyDepth > 0) startInBody(tag)
        }
    }

    fun end(tag: String) {
        when (tag) {
            "description" -> { inDescription = false; inTitleInfo = false; field = null }
            "title-info"  -> { inTitleInfo = false; field = null }
            "coverpage"   -> inCoverpage = false
            "author"      -> if (inAuthor) { inAuthor = false; authorDone = true }
            "book-title", "first-name", "last-name" -> field = null
            "body"        -> { if (bodyDepth > 0) bodyDepth--; sectionDepth = 0; endHeading() }
            "binary"      -> endBinary()
            "image"       -> Unit
            else          -> if (bodyDepth > 0) endInBody(tag)
        }
    }

    fun text(chunk: CharSequence) {
        if (inBinary) {
            binaryBuffer?.append(chunk)
            return
        }
        val target = field
        if (target != null) {
            target.append(chunk)
            return
        }
        if (bodyDepth == 0) return

        chars += countText(chunk)
        val head = heading
        if (head != null) {
            head.append(chunk)
            return
        }
        if (wantsDocument) appendEscaped(chunk)
    }

    // ── the book, once it has all been read ──────────────────────────────────

    /**
     * The finished document, with every illustration's size written into the tag that refers to
     * it.
     *
     * The sizes could not be written when the tags were: an FB2 keeps all of its pictures at the
     * end of the file, so at the moment a paragraph mentions one, nothing about it is known yet.
     * They are spliced in here instead, in one pass, and only for a book that has pictures at all
     * — for a plain novel this is the same single copy it always was.
     *
     * Saying the size matters more than it looks. A column layout cannot be measured until it
     * knows how tall its contents are, so without it the reader has to wait for every picture in
     * the book to be fetched and decoded before it can show the first page.
     */
    fun document(): String {
        if (imageSpots.isEmpty()) return out.toString()
        val result = StringBuilder(out.length + imageSpots.size * 28)
        var cursor = 0
        for ((at, name) in imageSpots) {
            result.appendRange(out, cursor, at)
            sizeAttributes(name)?.let { result.append(it) }
            cursor = at
        }
        result.appendRange(out, cursor, out.length)
        return result.toString()
    }

    private fun sizeAttributes(name: String): String? = imageSizes.getOrPut(name) {
        val dir  = mediaDir ?: return@getOrPut ""
        val size = imagePixelSize(File(dir, name)) ?: return@getOrPut ""
        " width=\"${size.first}\" height=\"${size.second}\""
    }.takeIf { it.isNotEmpty() }

    val bookTitle: String get() = collapse(titleText)

    fun author(): String =
        listOf(firstName, lastName)
            .map { collapse(it) }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** The cover the book named, or failing that the first picture in it, which usually is one. */
    fun cover(): ByteArray? = coverBytes ?: firstPicture

    // ── markup ───────────────────────────────────────────────────────────────

    private fun startInBody(tag: String) {
        when (tag) {
            "section"    -> sectionDepth++
            // A part and the chapters inside it are both written <title>; the only thing telling
            // them apart is how deep the section holding them is.
            "title"      -> beginHeading(if (sectionDepth > 1) "h3" else "h2", listed = true)
            "subtitle"   -> beginHeading("h3", listed = false)
            "empty-line" -> emit("<br/>\n")
            // A <p> inside a heading is a line of it, not a paragraph of its own.
            "p"          -> if (heading != null) headingBreak() else emit("<p>")
            "v"          -> if (heading != null) headingBreak() else emit("<div class='verse-line'>")
            else         -> openMarkup(tag)?.let { emit(it) }
        }
    }

    private fun endInBody(tag: String) {
        when (tag) {
            "section"           -> if (sectionDepth > 0) sectionDepth--
            "title", "subtitle" -> endHeading()
            "empty-line"        -> Unit
            "p"                 -> if (heading == null) emit("</p>\n")
            "v"                 -> if (heading == null) emit("</div>")
            else                -> closeMarkup(tag)?.let { emit(it) }
        }
    }

    private fun beginHeading(tag: String, listed: Boolean) {
        if (heading != null) return
        heading       = StringBuilder()
        headingTag    = tag
        headingListed = listed
        headingAnchor = "shl-t${anchorCount++}"
    }

    private fun endHeading() {
        val buffer = heading ?: return
        heading = null
        val text = collapse(buffer)
        if (text.isEmpty() || !wantsDocument) return
        out.append('<').append(headingTag).append(" id=\"").append(headingAnchor).append("\">")
        appendEscaped(text)
        out.append("</").append(headingTag).append(">\n")
        // The book's own contents, which is worth having for its own sake and worth having
        // instead of the reader hunting through the finished document for anything that looks
        // like a heading — on a long book that hunt is a second parse of the whole thing.
        if (headingListed) toc += TocEntry(text, headingAnchor, sectionDepth.coerceAtLeast(1))
    }

    private fun headingBreak() {
        heading?.let { if (it.isNotEmpty() && !it.endsWith(' ')) it.append(' ') }
    }

    private fun openMarkup(tag: String): String? = when (tag) {
        "strong", "b"        -> "<strong>"
        "emphasis", "i"      -> "<em>"
        "strikethrough"      -> "<s>"
        "sub", "sup", "code" -> "<$tag>"
        "epigraph"           -> "<blockquote class='epigraph'>"
        "cite"               -> "<blockquote class='cite'>"
        "poem"               -> "<div class='poem'>"
        "stanza"             -> "<div class='stanza'>"
        "text-author"        -> "<p class='text-author'>"
        "table"              -> "<table>"
        "tr"                 -> "<tr>"
        "td"                 -> "<td>"
        "th"                 -> "<th>"
        // Everything else keeps its words and loses its tag: a converter's own inventions, and
        // <a> above all. A footnote link has nowhere to go — the whole book is one document and
        // the note is further down it — and following one would navigate out of the book.
        else                 -> null
    }

    private fun closeMarkup(tag: String): String? = when (tag) {
        "strong", "b"        -> "</strong>"
        "emphasis", "i"      -> "</em>"
        "strikethrough"      -> "</s>"
        "sub", "sup", "code" -> "</$tag>"
        "epigraph", "cite"   -> "</blockquote>\n"
        "poem", "stanza"     -> "</div>\n"
        "text-author"        -> "</p>\n"
        "table"              -> "</table>\n"
        "tr"                 -> "</tr>"
        "td"                 -> "</td>"
        "th"                 -> "</th>"
        else                 -> null
    }

    /**
     * Markup goes nowhere while a heading is being assembled: a heading is one line of text, and
     * an <image/> or a <strong> inside one would otherwise be written out ahead of the heading
     * that contains it.
     */
    private fun emit(markup: String) {
        if (wantsDocument && heading == null) out.append(markup)
    }

    private fun appendEscaped(chunk: CharSequence) {
        for (ch in chunk) when (ch) {
            '&'  -> out.append("&amp;")
            '<'  -> out.append("&lt;")
            '>'  -> out.append("&gt;")
            else -> out.append(ch)
        }
    }

    // ── pictures ─────────────────────────────────────────────────────────────

    private fun image(attrs: Map<String, String>) {
        if (!wantsDocument || heading != null) return
        val id = href(attrs)
        if (id.isNullOrEmpty()) return
        // Named, not carried: the picture itself is written out beside the book when the scanner
        // reaches it, which is at the end of the file, long after this line of the text.
        val name = mediaSafeName(id)
        out.append("<img")
        // How large it is cannot be known yet, for the same reason — so the place to say it is
        // remembered and filled in by document(), once the pictures have all gone past.
        imageSpots += out.length to name
        out.append(" src=\"").append(MEDIA_PATH)
            .append(encodePathSegment(name))
            .append("\"/>\n")
    }

    private fun href(attrs: Map<String, String>): String? =
        attrs["href"]?.removePrefix("#")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A picture is about to arrive as base64. Whether any of it is kept depends on what this pass
     * is for: the document needs it on disk, the shelf needs only the cover, and a picture already
     * unpacked from a previous reading needs nothing at all — which is what makes opening a book
     * the second time quick.
     */
    private fun beginBinary(attrs: Map<String, String>) {
        inBinary      = true
        binaryIsCover = false
        binaryBuffer  = null
        binaryTarget  = null

        val id = attrs["id"].orEmpty().trim()
        if (id.isEmpty()) return
        val type = attrs["content-type"].orEmpty()

        if (wantsDocument) {
            val dir    = mediaDir ?: return
            val target = File(dir, mediaSafeName(id))
            if (target.exists() && target.length() > 0L) return
            binaryTarget = target
            binaryBuffer = StringBuilder(64 * 1024)
        } else if (coverBytes == null && type.startsWith("image/", ignoreCase = true)) {
            val named = coverRef?.equals(id, ignoreCase = true) == true
            if (named || firstPicture == null) {
                binaryIsCover = named
                binaryBuffer  = StringBuilder(64 * 1024)
            }
        }
    }

    private fun endBinary() {
        val buffer = binaryBuffer
        val target = binaryTarget
        inBinary     = false
        binaryBuffer = null
        binaryTarget = null
        if (buffer == null) return

        // The MIME decoder rather than the plain one: an FB2 wraps its base64 at seventy-odd
        // characters, and the strict decoder refuses at the first newline.
        val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(buffer.toString()) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return

        when {
            target != null -> runCatching {
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            binaryIsCover  -> coverBytes = bytes
            else           -> if (firstPicture == null) firstPicture = bytes
        }
    }

    // ── counting ─────────────────────────────────────────────────────────────

    /**
     * Text as a reader sees it: a run of spaces and newlines between two tags is one space, and
     * indentation is nothing. Counting the characters as they sit in the file said that a
     * prettily-indented book was half again as long as it is, and every estimate in the app —
     * pages left, minutes left, pace — is built on this number.
     */
    private fun countText(chunk: CharSequence): Int {
        var count     = 0
        var lastSpace = true
        for (ch in chunk) {
            if (ch.isWhitespace()) {
                if (!lastSpace) { count++; lastSpace = true }
            } else {
                count++
                lastSpace = false
            }
        }
        return count
    }

    private fun collapse(raw: CharSequence): String {
        val text      = StringBuilder(raw.length)
        var lastSpace = true
        for (ch in raw) {
            if (ch.isWhitespace()) {
                if (!lastSpace) { text.append(' '); lastSpace = true }
            } else {
                text.append(ch)
                lastSpace = false
            }
        }
        return text.toString().trim()
    }
}

/**
 * A forgiving XML scanner: tags, text and nothing else.
 *
 * It reports what it sees to [Fb2Reading] as it passes it and keeps nothing, so the memory it
 * needs does not depend on how long the book is. What it does not do is validate. An FB2 out of a
 * converter routinely contains entities nobody declared and ampersands that are just ampersands,
 * and a validating parser's answer to those is to refuse the book outright — which is not an
 * answer a reader can use.
 */
private class XmlScanner(private val reader: Reader, private val sink: Fb2Reading) {

    private val buf = CharArray(16 * 1024)
    private var len = 0
    private var pos = 0
    private var pushed = -1

    private val text  = StringBuilder(1024)
    private val name  = StringBuilder(32)
    private val value = StringBuilder(64)

    fun run() {
        while (true) {
            val c = next()
            if (c < 0) break
            if (c == LT) {
                flush()
                tag()
            } else if (c == AMP) {
                entity()
            } else {
                text.append(c.toChar())
                if (text.length >= FLUSH_AT) flush()
            }
        }
        flush()
    }

    private fun next(): Int {
        val waiting = pushed
        if (waiting >= 0) {
            pushed = -1
            return waiting
        }
        if (pos >= len) {
            len = reader.read(buf)
            pos = 0
            if (len <= 0) {
                len = 0
                return -1
            }
        }
        return buf[pos++].code
    }

    private fun flush() {
        if (text.isEmpty()) return
        sink.text(text)
        text.setLength(0)
    }

    private fun tag() {
        val c = next()
        when {
            c < 0        -> return
            c == SLASH   -> closeTag()
            c == BANG    -> declaration()
            c == QUESTION -> skipTo("?>")
            else         -> { pushed = c; openTag() }
        }
    }

    private fun closeTag() {
        name.setLength(0)
        while (true) {
            val c = next()
            if (c < 0 || c == GT) break
            if (!c.toChar().isWhitespace()) name.append(c.toChar())
        }
        sink.end(localName())
    }

    private fun declaration() {
        val c = next()
        when {
            c < 0          -> return
            c == DASH      -> skipTo("-->")
            c == BRACKET   -> cdata()
            // A doctype, and whatever a converter decided to put in one.
            else           -> skipTo(">")
        }
    }

    private fun cdata() {
        // Past "CDATA[" itself; whatever is inside is text exactly as written.
        while (true) {
            val c = next()
            if (c < 0 || c == BRACKET) break
        }
        var closing = 0
        while (true) {
            val c = next()
            if (c < 0) break
            if (c == CLOSE_BRACKET) {
                closing++
                continue
            }
            if (c == GT && closing >= 2) break
            repeat(closing) { text.append(']') }
            closing = 0
            text.append(c.toChar())
            if (text.length >= FLUSH_AT) flush()
        }
    }

    private fun openTag() {
        name.setLength(0)
        var c = next()
        while (c >= 0 && !isNameEnd(c)) {
            name.append(c.toChar())
            c = next()
        }
        val tag = localName()

        var attrs: MutableMap<String, String>? = null
        var selfClosing = false
        while (c >= 0 && c != GT) {
            if (c == SLASH) {
                selfClosing = true
                c = next()
                continue
            }
            if (c.toChar().isWhitespace()) {
                c = next()
                continue
            }
            name.setLength(0)
            while (c >= 0 && c != EQUALS && c != GT && c != SLASH && !c.toChar().isWhitespace()) {
                name.append(c.toChar())
                c = next()
            }
            val attribute = localName()

            value.setLength(0)
            while (c >= 0 && c.toChar().isWhitespace()) c = next()
            if (c == EQUALS) {
                c = next()
                while (c >= 0 && c.toChar().isWhitespace()) c = next()
                if (c == QUOTE || c == APOSTROPHE) {
                    val quote = c
                    c = next()
                    while (c >= 0 && c != quote) {
                        value.append(c.toChar())
                        c = next()
                    }
                    if (c >= 0) c = next()
                } else {
                    // Unquoted, which is not legal XML and is written all the same.
                    while (c >= 0 && c != GT && !c.toChar().isWhitespace()) {
                        value.append(c.toChar())
                        c = next()
                    }
                }
            }
            if (attribute.isNotEmpty()) {
                val map = attrs ?: HashMap<String, String>(4).also { attrs = it }
                map[attribute] = unescape(value.toString())
            }
        }

        sink.start(tag, attrs ?: emptyMap())
        // Reported as an element that opened and closed, so that nothing downstream has to know
        // the difference between <empty-line/> and <empty-line></empty-line>.
        if (selfClosing) sink.end(tag)
    }

    private fun isNameEnd(c: Int): Boolean =
        c == GT || c == SLASH || c.toChar().isWhitespace()

    /**
     * The tag or attribute just read, without its namespace prefix and lowercased: `l:href` and
     * `xlink:href` are the same attribute, written by two different converters.
     */
    private fun localName(): String {
        val raw   = name.toString()
        val colon = raw.lastIndexOf(':')
        return (if (colon >= 0) raw.substring(colon + 1) else raw).lowercase()
    }

    private fun entity() {
        value.setLength(0)
        while (true) {
            val c = next()
            if (c < 0) {
                text.append('&').append(value)
                return
            }
            if (c == SEMICOLON) break
            if (value.length >= 12 || c == LT || c == AMP || c.toChar().isWhitespace()) {
                // Not an entity at all — a stray ampersand, of which books are full.
                text.append('&').append(value)
                pushed = c
                return
            }
            value.append(c.toChar())
        }
        text.append(decodeEntity(value.toString()))
    }

    private fun unescape(raw: String): String {
        if (!raw.contains('&')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '&') {
                out.append(ch)
                i++
                continue
            }
            val end = raw.indexOf(';', i + 1)
            if (end < 0 || end - i > 12) {
                out.append(ch)
                i++
                continue
            }
            out.append(decodeEntity(raw.substring(i + 1, end)))
            i = end + 1
        }
        return out.toString()
    }

    private fun decodeEntity(body: String): String {
        NAMED[body]?.let { return it }
        val point = when {
            body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)
            body.startsWith("#")                     -> body.drop(1).toIntOrNull()
            else                                     -> null
        }
        if (point != null && point in 1..0x10FFFF) {
            return runCatching { String(Character.toChars(point)) }.getOrDefault("")
        }
        // An entity nobody declared. Left as it was written, which is the one answer that never
        // loses anything.
        return "&$body;"
    }

    private fun skipTo(marker: String) {
        var matched = 0
        while (matched < marker.length) {
            val c = next()
            if (c < 0) return
            matched = when {
                c == marker[matched].code -> matched + 1
                c == marker[0].code       -> 1
                else                      -> 0
            }
        }
    }

    private companion object {
        const val FLUSH_AT = 8 * 1024

        const val LT            = '<'.code
        const val GT            = '>'.code
        const val AMP           = '&'.code
        const val SLASH         = '/'.code
        const val BANG          = '!'.code
        const val QUESTION      = '?'.code
        const val DASH          = '-'.code
        const val BRACKET       = '['.code
        const val CLOSE_BRACKET = ']'.code
        const val EQUALS        = '='.code
        const val QUOTE         = '"'.code
        const val APOSTROPHE    = '\''.code
        const val SEMICOLON     = ';'.code

        /**
         * The five XML entities, and the handful of HTML ones that FB2 files use anyway despite
         * never declaring them — a dash and a non-breaking space between them account for most of
         * what a strict parser would throw a book out over.
         */
        val NAMED = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to "\u00A0", "shy" to "\u00AD",
            "mdash" to "—", "ndash" to "–", "minus" to "−", "hellip" to "…",
            "laquo" to "«", "raquo" to "»", "ldquo" to "“", "rdquo" to "”",
            "lsquo" to "‘", "rsquo" to "’", "bdquo" to "„",
            "bull" to "•", "middot" to "·", "dagger" to "†", "sect" to "§", "para" to "¶",
            "deg" to "°", "plusmn" to "±", "times" to "×", "divide" to "÷",
            "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€", "pound" to "£"
        )
    }
}
