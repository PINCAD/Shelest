package com.lexiread.app.domain.parser

import java.io.File
import java.nio.charset.Charset

/**
 * Rich Text Format (.rtf), read directly.
 *
 * RTF is a stream of groups and control words, and reading it is mostly a matter of knowing what
 * to throw away: the font table, the colour table, the stylesheet and the embedded pictures are
 * all destinations that say nothing to a reader. What is kept is the shape of the prose —
 * paragraphs, line breaks, bold and italic — and, above all, the text itself in the right letters.
 *
 * That last part is the whole difficulty of the format. RTF is a seven-bit file: every character
 * outside ASCII arrives either as `\'e0`, a byte to be read in whatever code page the document
 * declared, or as `ၲ`, a Unicode point followed by a fallback the reader is meant to skip. A
 * Russian document written in 1998 is the first kind and one saved by Word today is the second,
 * and both have to work, so both are handled — the bytes are gathered and decoded in runs rather
 * than one at a time, which is also what makes a multi-byte code page come out right.
 */
class RtfParser : BookParser {

    override fun extractMetadata(file: File): BookMetadata {
        val rendered = runCatching { render(read(file)) }.getOrNull()
        return BookMetadata(
            title      = rendered?.title?.take(200)?.ifBlank { null } ?: file.nameWithoutExtension,
            author     = rendered?.author.orEmpty(),
            totalChars = rendered?.chars ?: 0
        )
    }

    override fun extractContent(file: File): String = render(read(file)).html

    /**
     * Read as Latin-1 so every byte survives as itself.
     *
     * The escapes have to be decoded against the code page the document names, which is not known
     * until it is read — so decoding the file up front, in any charset, would destroy the very
     * bytes that decision applies to.
     */
    private fun read(file: File): String =
        runCatching { file.readText(Charsets.ISO_8859_1) }.getOrDefault("")

    private class Rendered(
        val html: String,
        val chars: Int,
        val title: String,
        val author: String
    )

    /** Where the text being read is going: into the book, into a field, or nowhere. */
    private enum class Sink { BODY, SKIP, TITLE, AUTHOR }

    private class State(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var skipFallback: Int = 1,
        var charset: Charset = Charsets.ISO_8859_1,
        var sink: Sink = Sink.BODY
    ) {
        fun copy() = State(bold, italic, skipFallback, charset, sink)
    }

    private fun render(source: String): Rendered {
        val out       = StringBuilder()
        val paragraph = StringBuilder()
        val title     = StringBuilder()
        val author    = StringBuilder()
        val bytes     = ArrayList<Byte>()
        val stack     = ArrayDeque<State>()

        var state    = State()
        var openBold = false
        var openItalic = false
        var chars = 0

        // ── The three ways text leaves this loop ─────────────────────────────

        fun flushBytes() {
            if (bytes.isEmpty()) return
            val decoded = String(bytes.toByteArray(), state.charset)
            bytes.clear()
            when (state.sink) {
                Sink.TITLE  -> title.append(decoded)
                Sink.AUTHOR -> author.append(decoded)
                Sink.SKIP   -> {}
                Sink.BODY   -> {
                    // Marks are opened only once text actually arrives: a run that turns bold on
                    // and immediately off again would otherwise leave an empty <strong> on every
                    // paragraph Word ever touched.
                    if (openItalic != state.italic) {
                        paragraph.append(if (state.italic) "<em>" else "</em>")
                        openItalic = state.italic
                    }
                    if (openBold != state.bold) {
                        paragraph.append(if (state.bold) "<strong>" else "</strong>")
                        openBold = state.bold
                    }
                    paragraph.append(decoded.escapeHtml())
                    chars += decoded.length
                }
            }
        }

        fun emit(text: String) {
            if (text.isEmpty()) return
            flushBytes()
            when (state.sink) {
                Sink.TITLE  -> title.append(text)
                Sink.AUTHOR -> author.append(text)
                Sink.SKIP   -> {}
                Sink.BODY   -> {
                    if (openItalic != state.italic) {
                        paragraph.append(if (state.italic) "<em>" else "</em>")
                        openItalic = state.italic
                    }
                    if (openBold != state.bold) {
                        paragraph.append(if (state.bold) "<strong>" else "</strong>")
                        openBold = state.bold
                    }
                    paragraph.append(text.escapeHtml())
                    chars += text.length
                }
            }
        }

        /** Markup this parser writes itself, which must not be escaped on the way in. */
        fun raw(markup: String) {
            flushBytes()
            if (state.sink == Sink.BODY) paragraph.append(markup)
        }

        fun endParagraph() {
            flushBytes()
            if (openItalic) { paragraph.append("</em>"); openItalic = false }
            if (openBold)   { paragraph.append("</strong>"); openBold = false }
            val text = paragraph.toString().trim()
            paragraph.setLength(0)
            if (text.isNotEmpty()) out.append("<p>").append(text).append("</p>\n")
        }

        // ── The walk ─────────────────────────────────────────────────────────

        var i = 0
        val n = source.length
        while (i < n) {
            when (val c = source[i]) {
                '{' -> { flushBytes(); stack.addLast(state.copy()); i++ }
                // The bytes belong to the group that is closing, and so does the code page they
                // have to be read in — decoding them after the pop would use the wrong one.
                '}' -> { flushBytes(); state = stack.removeLastOrNull() ?: State(); i++ }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val head = source[i]
                    if (head.isLetter()) {
                        val wordStart = i
                        while (i < n && source[i].isLetter()) i++
                        val word = source.substring(wordStart, i)

                        var param: Int? = null
                        val numberStart = i
                        if (i < n && source[i] == '-') i++
                        while (i < n && source[i].isDigit()) i++
                        if (i > numberStart) param = source.substring(numberStart, i).toIntOrNull()
                        // A single space after a control word is part of it, not of the text.
                        if (i < n && source[i] == ' ') i++

                        when (word) {
                            "par", "sect" -> endParagraph()
                            "line" -> raw("<br/>")
                            "tab"  -> emit("\t")
                            "pard", "plain" -> {
                                flushBytes()
                                state.bold = false
                                state.italic = false
                            }
                            "b"  -> { flushBytes(); state.bold = param != 0 }
                            "i"  -> { flushBytes(); state.italic = param != 0 }
                            "uc" -> state.skipFallback = param ?: 1
                            "u"  -> {
                                val code = param ?: 0
                                // Word writes points above the BMP as two negative halves; both
                                // arrive here as ordinary negative numbers.
                                val point = if (code < 0) code + 65536 else code
                                // A lone surrogate is not a character and would be rejected;
                                // it means a document splitting an astral point across two
                                // escapes, which is rare enough to be worth only not crashing on.
                                if (point in 0x20..0x10FFFF && point !in 0xD800..0xDFFF) {
                                    emit(String(Character.toChars(point)))
                                }
                                i = skipFallback(source, i, state.skipFallback)
                            }
                            "ansi" -> { flushBytes(); state.charset = codePage(1252) }
                            "ansicpg" -> { flushBytes(); state.charset = codePage(param) }
                            "fcharset" -> { flushBytes(); state.charset = fontCharset(param, state.charset) }
                            "emdash"     -> emit("—")
                            "endash"     -> emit("–")
                            "lquote"     -> emit("‘")
                            "rquote"     -> emit("’")
                            "ldblquote"  -> emit("“")
                            "rdblquote"  -> emit("”")
                            "bullet"     -> emit("•")
                            "title"      -> { flushBytes(); state.sink = Sink.TITLE }
                            "author"     -> { flushBytes(); state.sink = Sink.AUTHOR }
                            in SKIPPED   -> { flushBytes(); state.sink = Sink.SKIP }
                        }
                    } else {
                        when (head) {
                            // A byte in the document's code page. Gathered rather than decoded on
                            // the spot: consecutive escapes are one string in a multi-byte page,
                            // and decoding them separately would make two broken characters.
                            '\'' -> {
                                val hex = source.substring(
                                    (i + 1).coerceAtMost(n), (i + 3).coerceAtMost(n)
                                )
                                hex.toIntOrNull(16)?.let { bytes.add(it.toByte()) }
                                i += 3
                            }
                            '\\', '{', '}' -> { emit(head.toString()); i++ }
                            '~' -> { emit(" "); i++ }
                            '_' -> { emit("‑"); i++ }
                            '*' -> { flushBytes(); state.sink = Sink.SKIP; i++ }
                            '\n', '\r' -> { endParagraph(); i++ }
                            else -> i++
                        }
                    }
                }
                // Line endings inside an RTF are formatting of the file, not of the text.
                '\r', '\n' -> i++
                else -> {
                    // Anything above ASCII here is a file that ignored the rules and wrote its
                    // bytes raw. Treating them as code-page bytes reads such a file correctly and
                    // costs a conforming one nothing, since it never has any.
                    if (c.code >= 0x80) bytes.add(c.code.toByte()) else emit(c.toString())
                    i++
                }
            }
        }
        endParagraph()

        return Rendered(out.toString(), chars, title.toString().trim(), author.toString().trim())
    }

    /**
     * Steps over the plain-text fallback that follows `\uN`.
     *
     * The document repeats the character it just gave as Unicode in its own code page, for readers
     * that cannot do better — printing both would double every letter of a Russian book.
     */
    private fun skipFallback(source: String, from: Int, count: Int): Int {
        var i = from
        var left = count
        while (left > 0 && i < source.length) {
            when {
                source.startsWith("\\'", i) -> { i += 4; left-- }
                source[i] == '\\' -> {
                    i++
                    while (i < source.length && source[i].isLetter()) i++
                    left--
                }
                source[i] == '{' || source[i] == '}' -> break
                else -> { i++; left-- }
            }
        }
        return i
    }

    private fun codePage(param: Int?): Charset = when (param) {
        1250 -> charsetOrLatin1("windows-1250")
        1251 -> charsetOrLatin1("windows-1251")
        1253 -> charsetOrLatin1("windows-1253")
        1254 -> charsetOrLatin1("windows-1254")
        1257 -> charsetOrLatin1("windows-1257")
        65001 -> Charsets.UTF_8
        866  -> charsetOrLatin1("IBM866")
        else -> charsetOrLatin1("windows-1252")
    }

    /** A font's own script, which is all an older document says about its letters. */
    private fun fontCharset(param: Int?, current: Charset): Charset = when (param) {
        204  -> charsetOrLatin1("windows-1251")   // Cyrillic
        238  -> charsetOrLatin1("windows-1250")   // Central European
        161  -> charsetOrLatin1("windows-1253")   // Greek
        162  -> charsetOrLatin1("windows-1254")   // Turkish
        186  -> charsetOrLatin1("windows-1257")   // Baltic
        else -> current
    }

    private fun charsetOrLatin1(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)

    private companion object {
        /** Destinations that hold machinery rather than reading matter. */
        val SKIPPED = setOf(
            "fonttbl", "colortbl", "stylesheet", "listtable", "listoverridetable", "listtext",
            "rsidtbl", "generator", "themedata", "colorschememapping", "latentstyles", "datastore",
            "xmlnstbl", "pict", "nonshppict", "shppict", "object", "objdata", "info", "fldinst",
            "header", "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
            "footnote", "annotation", "xe", "tc", "bkmkstart", "bkmkend", "pntext", "pnseclvl",
            "filetbl", "revtbl", "upr", "mmath"
        )
    }
}
