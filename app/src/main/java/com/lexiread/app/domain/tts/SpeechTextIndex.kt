package com.lexiread.app.domain.tts

/** Maps a DOM context to the exact word, including a mid-sentence start. */
internal class SpeechTextIndex(private val sentences: List<String>) {
    data class Place(val sentence: Int, val offset: Int)
    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    private val starts = IntArray(sentences.size)
    private val text = buildString {
        sentences.forEachIndexed { i, value -> starts[i] = length; append(normalize(value)) }
    }

    fun locate(after: String, before: String): Place? {
        val tail = normalize(after).take(100)
        val head = normalize(before).takeLast(80)
        if (tail.isEmpty()) return null
        val key = head + tail
        val found = text.indexOf(key)
        // Refuse ambiguous/missing anchors instead of silently jumping to chapter zero.
        if (found < 0 || text.indexOf(key, found + 1) >= 0) return null
        val at = found + head.length
        val hit = starts.binarySearch(at)
        val sentence = (if (hit >= 0) hit else -hit - 2).coerceAtLeast(0)
        val remaining = at - starts[sentence]
        var letters = 0
        val value = sentences[sentence]
        for (offset in value.indices) {
            if (value[offset].isLetterOrDigit()) {
                if (letters == remaining) return Place(sentence, offset)
                letters++
            }
        }
        return null
    }
}
