package com.lexiread.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lexiread.app.data.db.AppDatabase
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.FileType
import com.lexiread.app.data.model.ReadingPosition
import com.lexiread.app.data.model.readingPosition
import com.lexiread.app.domain.parser.DocumentCache
import com.lexiread.app.domain.parser.ParsedBook
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReadingPositionPersistenceTest {
    @Test fun immediateReopenAndNewRepositoryUseLatestPageAndExactSegment() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("reading-position-test-", ".txt", context.cacheDir)
        try {
            file.writeText("example ".repeat(150000))
            val html = (0..29).joinToString("\n") { "<p>Paragraph $it ${"word ".repeat(1800)}</p>" }
            DocumentCache.store(file, ParsedBook(html))
            assertTrue(DocumentCache.segments(file).size > 3)
            val dao = db.bookDao()
            val id = dao.insertBook(Book(title = "Test", filePath = file.path, fileType = FileType.TXT))
            val repository = BookRepository(context, dao, db.likedTitleDao())
            val expected = ReadingPosition(2, 17, 12345, "layout", 0.0)
            repeat(40) { repository.saveReadingPosition(id, expected.copy(page = it)) }
            repository.saveReadingPosition(id, expected)
            assertEquals(expected, repository.getBook(id)?.readingPosition)
            withTimeout(5000) {
                while (dao.getBookById(id)?.readingPosition != expected) delay(10)
            }
            val reopened = BookRepository(context, dao, db.likedTitleDao()).getBook(id)!!
            assertEquals(expected, reopened.readingPosition)
            // The percentage still points at the start. Exact segment must take precedence.
            assertEquals(0f, reopened.scrollPercent, 0f)
            assertEquals(2, repository.loadBook(reopened).segmentIndex)
        } finally {
            DocumentCache.clear(file)
            file.delete()
            db.close()
        }
    }
}
