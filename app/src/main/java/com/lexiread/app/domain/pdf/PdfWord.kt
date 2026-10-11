package com.lexiread.app.domain.pdf

/** Page-normalized geometry, independent of bitmap resolution and reader zoom. */
data class PdfWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

fun pdfWordAt(words: List<PdfWord>, x: Float, y: Float): Int? = words.indices.firstOrNull {
    val word = words[it]
    x >= word.left && x <= word.right && y >= word.top - .004f && y <= word.bottom + .004f
}

fun pdfSelectionText(words: List<PdfWord>, start: Int, end: Int): String =
    if (words.isEmpty()) "" else words.subList(minOf(start, end).coerceIn(0, words.lastIndex), (maxOf(start, end) + 1).coerceIn(1, words.size))
        .joinToString(" ") { it.text }
