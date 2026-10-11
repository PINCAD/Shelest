package com.lexiread.app.ui

import com.shelest.ui.ShelfBook
import com.shelest.ui.shelfStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfStatusTest {
    @Test fun unopenedBooksNeverShowPercent() {
        assertEquals("Новая", shelfStatus(ShelfBook("1", "Book", progress = .4)))
    }
    @Test fun openedBooksShowEvenZeroAndClampProgress() {
        assertEquals("0%", shelfStatus(ShelfBook("1", "Book", opened = true)))
        assertEquals("42%", shelfStatus(ShelfBook("1", "Book", opened = true, progress = .42)))
        assertEquals("100%", shelfStatus(ShelfBook("1", "Book", opened = true, progress = 2.0)))
        assertEquals("0%", shelfStatus(ShelfBook("1", "Book", opened = true, progress = Double.NaN)))
    }
}
