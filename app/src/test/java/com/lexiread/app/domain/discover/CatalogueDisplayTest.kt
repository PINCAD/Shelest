package com.lexiread.app.domain.discover

import org.junit.Assert.*
import org.junit.Test

class CatalogueDisplayTest {
    @Test fun removesEditionNoiseWithoutChangingCatalogueIdentity() {
        val book = DiscoveredBook("id", "Herland (Russian Edition)", emptyList())
        assertEquals("Herland", book.displayTitle)
        assertEquals("Herland (Russian Edition)", book.title)
        assertEquals("Оно (роман)", cleanCatalogueTitle("Оно (роман)"))
    }
    @Test fun omittedQuotesAreNotSynopses() {
        assertTrue(looksLikeOpeningQuote(" … человек должен платить"))
        assertTrue(looksLikeOpeningQuote("...цитата"))
        assertFalse(looksLikeOpeningQuote("Действие романа происходит в Дерри."))
    }
}
