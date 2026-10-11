package com.lexiread.app.ui

import com.lexiread.app.domain.pdf.*
import org.junit.Assert.*
import org.junit.Test

class PdfSelectionRulesTest {
    private val words = listOf(PdfWord("Первое", .1f, .2f, .3f, .24f), PdfWord("слово", .35f, .2f, .5f, .24f))
    @Test fun hitTestingRejectsBlankMargins() {
        assertEquals(0, pdfWordAt(words, .2f, .22f))
        assertNull(pdfWordAt(words, .8f, .22f))
        assertNull(pdfWordAt(words, .2f, .5f))
    }
    @Test fun selectionWorksInBothDragDirectionsAndForEmptyPages() {
        assertEquals("Первое слово", pdfSelectionText(words, 0, 1))
        assertEquals("Первое слово", pdfSelectionText(words, 1, 0))
        assertEquals("слово", pdfSelectionText(words, 1, 1))
        assertEquals("", pdfSelectionText(emptyList(), 0, 0))
    }
}
