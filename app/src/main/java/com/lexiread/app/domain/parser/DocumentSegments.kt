package com.lexiread.app.domain.parser

/**
 * A book cut into pieces the reader can lay out one at a time.
 *
 * This exists because of one fact about the reader: the whole book is a single document in a
 * single WebView, laid out as one multicolumn flow. To show the first page, the browser has to
 * build the document tree of the entire book and lay the entire flow out — so opening a long book
 * costs time in proportion to its length, and no amount of tuning around the edges changes that.
 * The only way out is to stop handing it the whole book.
 *
 * Cutting is done here rather than in each parser, on the finished markup, so that every format
 * gets it at once and none of them has to know about it.
 *
 * A cut is only ever made where the document is shallow — at the top level, or one element inside
 * it, which is where a chapter wrapper sits. Anything deeper is left whole: a long table or a poem
 * split down the middle would show as two of them, and a segment that is larger than asked for
 * costs a little layout, while a broken one costs the reader their place in a sentence.
 */

/** One piece of a book: its markup, its words, and the heading it opens with if it has one. */
internal data class SegmentPiece(
    val html: String,
    val plain: String,
    val title: String
)

/**
 * A piece as the reader knows it once the book has been put away: where it is and how big, but not
 * what it says.
 *
 * The whole point is that this is small. A book of six hundred pieces is a few kilobytes of these,
 * so the reader can know the shape of a book — how far in a place is, which piece a page belongs
 * to, what the piece is called — without any of the book being in memory.
 *
 * [startChar] and [chars] are measured in characters of the book's plain text, not of its markup
 * and not of its pages. Characters are the one measure that survives the reader changing the font,
 * the margins or the size of the screen, which is why a place in a book is recorded that way.
 *
 * [domChars] is a second, different count of the same piece: the characters that end up in the
 * document's own text nodes. It is kept alongside because the reader counts that way — it asks the
 * browser where things are, and the browser knows nothing of collapsed whitespace or of the spaces
 * plain text puts between two blocks. The two must never be added to one another; each is right
 * for what uses it, and mixing them is a position that drifts by pages over a book.
 */
data class BookSegment(
    val index: Int,
    val startChar: Int,
    val chars: Int,
    val title: String,
    val domChars: Int
) {
    val endChar: Int get() = startChar + chars
}

/**
 * How large a piece should be, in characters of markup.
 *
 * Some twenty screens of an ordinary novel. Large enough that the reader crosses from one piece to
 * the next rarely, small enough that laying three of them out is work the phone does not notice.
 */
internal const val SEGMENT_TARGET = 60_000

/** Past this much of a piece, an approaching heading is a better place to cut than the target. */
private const val HEADING_CUT_FROM = 0.6

/** Elements that stand on their own, and so may have a cut made after them. */
private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "blockquote", "table", "figure", "pre",
    "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6"
)

/** Elements that never close, and so never change how deep the document is. */
private val VOID_TAGS = setOf("br", "img", "hr", "wbr", "col", "source", "input", "meta", "link")

internal fun splitIntoSegments(html: String, target: Int = SEGMENT_TARGET): List<SegmentPiece> {
    if (html.length <= target) return listOf(pieceOf(html))

    val pieces = mutableListOf<SegmentPiece>()
    val out    = StringBuilder(target + 8_192)

    // The one element allowed to be open across a cut — a chapter wrapper, in practice. It is
    // closed at the end of the piece and opened again at the start of the next, so both pieces
    // are documents in their own right.
    var openTag: String? = null
    var openName = ""
    var depth = 0

    fun flush() {
        if (out.isBlank()) return
        if (depth == 1 && openName.isNotEmpty()) out.append("</").append(openName).append(">")
        pieces += pieceOf(out.toString())
        out.setLength(0)
        if (depth == 1) openTag?.let { out.append(it) }
    }

    var i = 0
    while (i < html.length) {
        val ch = html[i]
        if (ch != '<') {
            val next = html.indexOf('<', i)
            val end  = if (next < 0) html.length else next
            out.appendRange(html, i, end)
            i = end
            continue
        }

        val close = html.indexOf('>', i)
        if (close < 0) {
            // A tag that never ends: the rest of the document is whatever it is.
            out.appendRange(html, i, html.length)
            break
        }
        val raw    = html.substring(i, close + 1)
        val name   = tagNameOf(raw)
        val ending = raw.startsWith("</")

        // A heading coming up is a better seam than an arbitrary count, so once the piece is
        // most of the way to its size, the next one is where it ends.
        if (!ending && name in HEADING_TAGS && depth <= 1 &&
            out.length >= (target * HEADING_CUT_FROM).toInt()
        ) {
            flush()
        }

        out.append(raw)
        i = close + 1

        if (ending) {
            if (depth > 0) depth--
            if (depth == 0) {
                openTag = null
                openName = ""
            }
            // Deeper than this and the piece would have to be reassembled to make sense.
            if (depth <= 1 && name in BLOCK_TAGS && out.length >= target) flush()
        } else if (!raw.endsWith("/>") && name !in VOID_TAGS && name.isNotEmpty()) {
            depth++
            if (depth == 1) {
                openName = name
                openTag  = reopenForm(raw, name)
            }
        }
    }

    // The tail, unless the tail is only the wrapper that was opened again for it — a cut made on
    // the last block of the book leaves exactly that, and an empty chapter is not a piece.
    val tail = out.toString()
    if (plainTextOf(tail).isNotBlank() || tail.contains("<img", ignoreCase = true)) {
        pieces += pieceOf(
            if (depth == 1 && openName.isNotEmpty()) "$tail</$openName>" else tail
        )
    }
    return pieces
}

private val HEADING_TAGS = setOf("h1", "h2", "h3")

private fun pieceOf(html: String): SegmentPiece {
    val plain = plainTextOf(html)
    return SegmentPiece(html = html, plain = plain, title = headingTextOf(html))
}

/** The heading a piece opens with, for a contents that has something to call it. */
private fun headingTextOf(html: String): String {
    val start = html.indexOf("<h", ignoreCase = true).takeIf { it >= 0 } ?: return ""
    // Only a heading right at the front counts as the piece's own title; one further in belongs
    // to a chapter that began in the middle of it.
    if (plainTextOf(html.substring(0, start)).isNotBlank()) return ""
    val level = html.getOrNull(start + 2) ?: return ""
    if (level !in '1'..'6') return ""
    val open  = html.indexOf('>', start).takeIf { it >= 0 } ?: return ""
    val close = html.indexOf("</h$level", open, ignoreCase = true).takeIf { it >= 0 } ?: return ""
    return plainTextOf(html.substring(open + 1, close)).take(200)
}

/**
 * The wrapper as it should be written at the start of the next piece.
 *
 * Its id does not come with it: an anchor belongs to the place it was declared, and two elements
 * answering to the same name would make every jump into that chapter land on whichever the
 * browser found first.
 */
private fun reopenForm(raw: String, name: String): String {
    val id = Regex("""\s+id\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE)
    val stripped = id.replace(raw, "")
    return if (stripped.length >= 2) stripped else "<$name>"
}

/** The element's name, lowercased, from a raw tag — `</div >` and `<div id=x>` are both `div`. */
private fun tagNameOf(raw: String): String {
    var at = 1
    if (at < raw.length && raw[at] == '/') at++
    val start = at
    while (at < raw.length) {
        val ch = raw[at]
        if (ch == '>' || ch == '/' || ch.isWhitespace()) break
        at++
    }
    return raw.substring(start, at).lowercase()
}
