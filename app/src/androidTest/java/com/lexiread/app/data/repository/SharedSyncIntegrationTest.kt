package com.lexiread.app.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lexiread.app.data.db.AppDatabase
import com.lexiread.app.data.model.*
import com.lexiread.app.domain.parser.DocumentCache
import com.lexiread.app.domain.parser.ParsedBook
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SharedSyncIntegrationTest {
    @Test fun pendingLocalWritesCannotOverwriteAcceptedCloudPlace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val id = db.bookDao().insertBook(Book(title = "Ordered writes", filePath = "", fileType = FileType.TXT))
            val repo = BookRepository(context, db.bookDao(), db.likedTitleDao())
            repeat(100) { repo.saveReadingPosition(id, ReadingPosition(0, it, it * 100, "local")) }
            val remote = ReadingPosition(1, 4, 400, "remote")
            repo.applyCloudPosition(id, 50f, 1770000000000, remote)
            delay(200)
            assertEquals(remote, db.bookDao().getBookById(id)?.readingPosition)
            assertEquals(remote, repo.getBook(id)?.readingPosition)
        } finally { db.close() }
    }
    @Test fun restoringAndRestylingDoNotPublishButReadingDoes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val id = db.bookDao().insertBook(Book(title = "Shared rule test", filePath = "", fileType = FileType.TXT))
            var changes = 0
            val repo = BookRepository(context, db.bookDao(), db.likedTitleDao()).apply { onLocalPlaceChanged = { changes++ } }
            val initial = ReadingPosition(0, 3, 100, "old", anchor = "Один достаточно длинный отрывок.")
            repo.saveReadingPosition(id, initial)
            assertEquals(0, changes)
            repo.saveReadingPosition(id, initial.copy(page = 7, layout = "new"))
            assertEquals(0, changes)
            repo.saveReadingPosition(id, initial.copy(page = 8, character = 200, anchor = "Другой достаточно длинный отрывок."))
            assertEquals(1, changes)
            assertEquals(200, repo.getBook(id)?.readingPosition?.character)
        } finally { db.close() }
    }
    @Test fun androidAnchorResolverUsesSharedUnicodeAndRejectsAmbiguousText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("shared-anchor-", ".txt", context.cacheDir)
        try {
            file.writeText("cache fixture")
            val quote = "Уникальный достаточно длинный отрывок."
            DocumentCache.store(file, ParsedBook("<p>До. \u00a0\ufeff$quote</p>"))
            val id = db.bookDao().insertBook(Book(title = "Anchor", filePath = file.path, fileType = FileType.TXT))
            val repo = BookRepository(context, db.bookDao(), db.likedTitleDao())
            val book = repo.getBook(id)!!
            val exact = repo.resolveCloudAnchor(book, quote)
            assertNotNull(exact)
            assertEquals(6, exact!!.character)
            DocumentCache.clear(file)
            DocumentCache.store(file, ParsedBook("<p>$quote $quote</p>"))
            assertNull(repo.resolveCloudAnchor(book, quote))
        } finally {
            DocumentCache.clear(file); file.delete(); db.close()
        }
    }
}
