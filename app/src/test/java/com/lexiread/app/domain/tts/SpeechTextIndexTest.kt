package com.lexiread.app.domain.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechTextIndexTest {
    @Test fun startsAtWordInsideSentenceNotAtFirstOccurrence() {
        val sentences = listOf("Первый шелест деревьев.", "Второй шелест страниц слышен рядом.")
        val place = SpeechTextIndex(sentences).locate("шелест страниц слышен рядом.", "Первый шелест деревьев. Второй ")!!
        assertEquals(1, place.sentence)
        assertEquals("шелест страниц слышен рядом.", sentences[place.sentence].substring(place.offset))
    }
    @Test fun ignoresWhitespaceMarkupAndPunctuationDifferences() {
        val place = SpeechTextIndex(listOf("Читаем книгу. Продолжим с этого слова!")).locate("с\n этого слова", "Читаем книгу.\nПродолжим ")!!
        assertEquals(0, place.sentence)
        assertEquals("Читаем книгу. Продолжим с этого слова!".indexOf("с этого"), place.offset)
    }
    @Test fun missingOrAmbiguousPlaceNeverFallsBackToChapterStart() {
        val index = SpeechTextIndex(listOf("Одинаковая строка.", "Одинаковая строка."))
        assertNull(index.locate("Одинаковая строка.", ""))
        assertNull(index.locate("Такого текста здесь нет", ""))
        assertNull(index.locate("", ""))
    }
}
