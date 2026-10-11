package com.lexiread.app.domain.tts

import java.text.BreakIterator
import java.util.Locale

/**
 * Cuts a chapter into the units the voice reads out one at a time.
 *
 * This used to be `split(Regex("(?<=[.!?])\\s+"))`, in two places that had to agree with
 * each other by luck. On Russian prose that rule is wrong far more often than it looks:
 * "А. С. Пушкин" becomes three sentences, "т. е." and "и т. д." each end one, and a
 * decimal number ends one in the middle of itself. Every such mistake is audible — the
 * voice drops its intonation and pauses where the author did not.
 *
 * BreakIterator knows the general shape of the problem, so it does the first pass. What it
 * does not know is this particular language's abbreviations, so two repairs follow it:
 * a fragment ending in a known abbreviation or an initial is glued to the next one, and so
 * is any fragment whose successor starts lowercase — nothing in Russian starts a sentence
 * with a small letter, so that is a boundary the iterator invented.
 *
 * The result is also the index space the rest of the feature counts in: the player speaks
 * sentence *n*, the reader highlights sentence *n*, the saved position is sentence *n*.
 * There must therefore be exactly one implementation of it, which is why this is an object
 * and not a private helper.
 */
object SentenceSplitter {

    /**
     * Longest chunk handed to the engine in one call. `TextToSpeech` refuses anything over
     * `getMaxSpeechInputLength()` (4000 on every device that matters) *silently* — the
     * utterance simply never speaks and never reports done, which stalls the whole chapter.
     * A paragraph of dialogue with no full stop in it can genuinely run that long, so an
     * over-long sentence is broken at a lesser punctuation mark rather than risked.
     */
    private const val MAX_CHUNK = 800

    /**
     * Words that end in a full stop without ending a sentence. Stored without the trailing
     * dot and matched case-insensitively against the last word of a fragment.
     *
     * Not exhaustive and cannot be — this is a heuristic, and the cost of a miss is one
     * unnatural pause, not a broken reading. The list covers what actually turns up in
     * books: reference and measurement abbreviations, and the address and date forms that
     * appear in nineteenth-century prose.
     */
    private val ABBREVIATIONS = setOf(
        "т", "е", "д", "п", "к", "н", "э", "др", "пр", "см", "ср", "стр", "с",
        "рис", "табл", "гл", "изд", "ред", "сост", "пер", "прим", "примеч",
        "г", "гг", "в", "вв", "тыс", "млн", "млрд", "руб", "коп",
        "ул", "пл", "просп", "наб", "корп", "кв", "обл", "респ",
        "им", "гр", "тов", "проф", "акад", "доц", "канд", "докт",
        "св", "оз", "р", "м", "км", "мм", "кг", "мл", "ч", "мин", "сек"
    )

    /**
     * A fragment ends in an initial when its last word is one capital letter — "А." in
     * "А. С. Пушкин". The letter has to be the whole word: matching a bare capital before
     * the full stop also matched the last letter of "СССР." and "США.", and a sentence
     * ending in an acronym then never ended at all, swallowing the one after it.
     */
    private val INITIAL = Regex("""(?:^|[\s(«"'])\p{Lu}\.$""")

    /** The last word of a fragment, without its trailing full stop. */
    private val LAST_WORD = Regex("""(\p{L}+)\.$""")

    /**
     * @param text plain text — HTML must already have been stripped by the caller, since
     *   the caller is the one that knows whether it is holding a chapter or a page.
     */
    fun split(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val raw = mutableListOf<String>()
        val iterator = BreakIterator.getSentenceInstance(Locale("ru", "RU"))
        iterator.setText(text)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            raw += text.substring(start, end)
            start = end
            end = iterator.next()
        }

        // Repair pass. Fragments are joined forward, so the decision at each step is only
        // ever "does this one finish a thought", which is the thing the heuristics can
        // actually answer. Trimming happens at the end so the joins keep their spacing.
        val joined = mutableListOf<String>()
        val pending = StringBuilder()
        for ((i, fragment) in raw.withIndex()) {
            pending.append(fragment)
            if (!endsSentence(pending.toString(), raw.getOrNull(i + 1))) continue
            joined += pending.toString()
            pending.setLength(0)
        }
        if (pending.isNotBlank()) joined += pending.toString()

        // The blank filter runs after the long ones are broken up, not before: a cut can land
        // so that a piece is nothing but punctuation, and an utterance with no letters in it
        // is handed to an engine that may never report it finished — which stalls the chain
        // for the rest of the chapter.
        return joined
            .map { it.trim() }
            .flatMap { if (it.length <= MAX_CHUNK) listOf(it) else breakUp(it) }
            .filter { it.any(Char::isLetterOrDigit) }
    }

    private fun endsSentence(fragment: String, next: String?): Boolean {
        val trimmed = fragment.trimEnd()
        if (trimmed.isEmpty()) return false

        // A lowercase start means the iterator broke inside a sentence — no author begins
        // one with a small letter, so whatever it found was not a full stop doing its job.
        val nextStart = next?.trimStart()?.firstOrNull()
        if (nextStart != null && nextStart.isLowerCase()) return false

        if (INITIAL.containsMatchIn(trimmed)) return false
        val lastWord = LAST_WORD.find(trimmed)?.groupValues?.get(1)
        if (lastWord != null && lastWord.lowercase() in ABBREVIATIONS) return false

        return true
    }

    /**
     * Splits a sentence that is too long for one call to the engine. Prefers a clause
     * boundary, falls back to a word boundary, and only cuts mid-word if handed something
     * with no spaces in it at all — at which point the alternative is silence.
     */
    private fun breakUp(sentence: String): List<String> {
        val parts = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAX_CHUNK) {
            val window = rest.substring(0, MAX_CHUNK)
            val cut = window.lastIndexOfAny(charArrayOf(';', ':', ',', '—', '–'))
                .takeIf { it > MAX_CHUNK / 4 }
                ?: window.lastIndexOf(' ').takeIf { it > MAX_CHUNK / 4 }
                ?: MAX_CHUNK - 1
            parts += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trimStart()
        }
        if (rest.isNotBlank()) parts += rest.trim()
        return parts
    }
}
