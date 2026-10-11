package com.lexiread.app.ui.library

import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

class LibraryFilterTest {
    private val books = listOf(
        Book(id = 1, title = "Игра престолов", author = "Мартин", filePath = "1", fileType = FileType.EPUB, addedAt = 3, scrollPercent = 42f),
        Book(id = 2, title = "Битва королей", author = "Мартин", filePath = "2", fileType = FileType.EPUB, addedAt = 1, scrollPercent = 0f),
        Book(id = 3, title = "Зелёная миля", author = "Кинг", filePath = "3", fileType = FileType.EPUB, addedAt = 2, scrollPercent = 80f)
    )

    @Test fun emptyRecentQueryReusesListWithoutReordering() {
        assertSame(books, filterLibrary(books, "  ", LibrarySort.RECENT))
    }

    @Test fun searchIgnoresCaseAndSurroundingWhitespace() {
        assertEquals(listOf(1L, 2L), filterLibrary(books, " МАРТИН ", LibrarySort.RECENT).map { it.id })
        assertEquals(listOf(3L), filterLibrary(books, "милЯ", LibrarySort.TITLE).map { it.id })
        assertEquals(emptyList<Book>(), filterLibrary(books, "не существует", LibrarySort.RECENT))
    }

    @Test fun sortModesPreserveOriginalBooksAndPositions() {
        assertEquals(listOf(2L, 3L, 1L), filterLibrary(books, "", LibrarySort.TITLE).map { it.id })
        assertEquals(listOf(3L, 1L, 2L), filterLibrary(books, "", LibrarySort.AUTHOR).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), filterLibrary(books, "", LibrarySort.ADDED).map { it.id })
        assertEquals(listOf(3L, 1L, 2L), filterLibrary(books, "", LibrarySort.PROGRESS).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), books.map { it.id })
        filterLibrary(books, "", LibrarySort.TITLE).forEach { sorted ->
            assertSame(books.first { it.id == sorted.id }, sorted)
        }
    }

    @Test fun sortingDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val titles = listOf("Zebra", "Island").mapIndexed { index, title -> books[0].copy(id = index.toLong(), title = title) }
            assertEquals(listOf("Island", "Zebra"), filterLibrary(titles, "", LibrarySort.TITLE).map { it.title })
        } finally { Locale.setDefault(previous) }
    }
}
