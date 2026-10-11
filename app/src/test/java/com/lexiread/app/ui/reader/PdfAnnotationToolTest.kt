package com.lexiread.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfAnnotationToolTest {
    @Test fun eraserStartsInPartialLineModeWithoutOpeningItsOptions() {
        assertEquals(PdfAnnotationMode.ERASE_PART,
            selectPdfAnnotationTool(PdfAnnotationMode.DRAW, PdfAnnotationMode.ERASE))
        assertEquals(PdfAnnotationMode.NONE,
            selectPdfAnnotationTool(PdfAnnotationMode.ERASE_PART, PdfAnnotationMode.ERASE))
    }

    @Test fun penAndNoteToggleWithoutChangingTheOptionsPanel() {
        assertEquals(PdfAnnotationMode.NONE,
            selectPdfAnnotationTool(PdfAnnotationMode.DRAW, PdfAnnotationMode.DRAW))
        assertEquals(PdfAnnotationMode.NOTE,
            selectPdfAnnotationTool(PdfAnnotationMode.ERASE_PART, PdfAnnotationMode.NOTE))
    }
}
