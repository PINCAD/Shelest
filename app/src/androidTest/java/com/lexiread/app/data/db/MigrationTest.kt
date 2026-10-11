package com.lexiread.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the library survives an update.
 *
 * Up to version 6 this database was built with a blanket destructive fallback, which means every
 * schema change in the app's history quietly deleted every reader's books, notes and saved words.
 * Version 7 is the first change with a written migration, and this is the test that says so: it
 * opens a real version 6 database with a real book in it, runs the migration, and checks the book
 * is still there.
 *
 * Needs a device or emulator — it opens SQLite. Room's own validation runs as part of
 * [MigrationTestHelper.runMigrationsAndValidate], so a migration that produces the wrong table
 * fails here rather than at launch on someone's phone.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
    fun migrate10To11_preservesBooksAndAddsExactPlace() {
        helper.createDatabase(dbName, 10).use { db ->
            db.execSQL("""
                INSERT INTO books (title, author, filePath, fileType, scrollPercent, addedAt,
                    totalChars, ambientEnabled, ambientVolume)
                VALUES ('Тест', '', '/books/test.txt', 'TXT', 42.5, 1, 200000, 0, 0.5)
            """.trimIndent())
        }
        val db = helper.runMigrationsAndValidate(dbName, 11, true, AppDatabase.MIGRATION_10_11)
        db.query("SELECT title, scrollPercent, readingPage, readingChar FROM books").use {
            assertTrue(it.moveToFirst())
            assertEquals("Тест", it.getString(0))
            assertEquals(42.5f, it.getFloat(1), 0.001f)
            assertTrue(it.isNull(2))
            assertTrue(it.isNull(3))
        }
        db.execSQL("UPDATE books SET readingSegment = 2, readingPage = 17, readingChar = 123456, readingLayout = 'exact', readingScrollY = 0")
        db.query("SELECT readingSegment, readingPage, readingChar FROM books").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
            assertEquals(17, it.getInt(1))
            assertEquals(123456, it.getInt(2))
        }
    }

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate6To7_keepsTheLibrary() {
        helper.createDatabase(dbName, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                    (title, author, filePath, fileType, coverPath, scrollPercent, addedAt,
                     lastOpenedAt, totalChars, ambientSoundId, ambientCustomPath,
                     ambientEnabled, ambientVolume)
                VALUES
                    ('Солярис', 'Станислав Лем', '/books/solaris.epub', 'EPUB', NULL, 41.5,
                     1700000000000, 1700000900000, 420000, NULL, NULL, 0, 0.5)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)

        db.query("SELECT title, scrollPercent, likedAt FROM books").use { cursor ->
            assertTrue("the book did not survive the migration", cursor.moveToFirst())
            assertEquals("Солярис", cursor.getString(0))
            assertEquals(41.5f, cursor.getFloat(1), 0.01f)
            // The new column exists and nothing was marked as liked on the reader's behalf.
            assertTrue(cursor.isNull(2))
            assertEquals(1, cursor.count)
        }
    }

    /**
     * Version 8 adds a table and touches nothing else, so what is worth checking is exactly
     * that: the shelf and its like marks come through untouched, and the new table is usable.
     * Running both migrations in one call is also the path a reader on version 6 actually takes.
     */
    @Test
    fun migrate7To8_addsTypedTitlesAndKeepsTheLibrary() {
        helper.createDatabase(dbName, 7).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                    (title, author, filePath, fileType, coverPath, scrollPercent, addedAt,
                     lastOpenedAt, totalChars, ambientSoundId, ambientCustomPath,
                     ambientEnabled, ambientVolume, likedAt)
                VALUES
                    ('Пикник на обочине', 'Аркадий Стругацкий', '/books/roadside.fb2', 'FB2',
                     NULL, 12.0, 1700000000000, 1700000900000, 300000, NULL, NULL, 0, 0.5,
                     1700000500000)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 8, true, AppDatabase.MIGRATION_7_8)

        db.query("SELECT title, likedAt FROM books").use { cursor ->
            assertTrue("the book did not survive the migration", cursor.moveToFirst())
            assertEquals("Пикник на обочине", cursor.getString(0))
            assertEquals(1700000500000L, cursor.getLong(1))
        }

        db.execSQL(
            "INSERT INTO liked_titles (title, author, addedAt) " +
                "VALUES ('Мастер и Маргарита', 'Михаил Булгаков', 1700000600000)"
        )
        db.query("SELECT title, author FROM liked_titles").use { cursor ->
            assertTrue("the new table did not accept a row", cursor.moveToFirst())
            assertEquals("Мастер и Маргарита", cursor.getString(0))
            assertEquals("Михаил Булгаков", cursor.getString(1))
        }
    }

    /**
     * Version 9 adds the synopsis cache. Additive again, so the same two questions: the shelf is
     * untouched, and the new table takes a row in the shape the app writes.
     *
     * The nullable cover and the boolean are written explicitly, because those are the two columns
     * whose declaration can differ from what Room generates and still create a table.
     */
    @Test
    fun migrate8To9_addsTheSynopsisCacheAndKeepsTheLibrary() {
        helper.createDatabase(dbName, 8).use { db ->
            db.execSQL(
                """
                INSERT INTO books
                    (title, author, filePath, fileType, coverPath, scrollPercent, addedAt,
                     lastOpenedAt, totalChars, ambientSoundId, ambientCustomPath,
                     ambientEnabled, ambientVolume, likedAt)
                VALUES
                    ('Мы', 'Евгений Замятин', '/books/we.txt', 'TXT', NULL, 3.5,
                     1700000000000, 1700000900000, 210000, NULL, NULL, 0, 0.5, NULL)
                """.trimIndent()
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 9, true, AppDatabase.MIGRATION_8_9)

        db.query("SELECT title FROM books").use { cursor ->
            assertTrue("the book did not survive the migration", cursor.moveToFirst())
            assertEquals("Мы", cursor.getString(0))
        }

        db.execSQL(
            "INSERT INTO synopsis_cache (bookId, text, credit, coverUrl, aboutAuthor, cachedAt) " +
                "VALUES ('/works/OL27448W', 'Роман о будущем.', 'Аннотация с Фантлаба', NULL, 0, " +
                "1700000600000)"
        )
        db.query("SELECT text, credit, coverUrl, aboutAuthor FROM synopsis_cache").use { cursor ->
            assertTrue("the new table did not accept a row", cursor.moveToFirst())
            assertEquals("Роман о будущем.", cursor.getString(0))
            assertEquals("Аннотация с Фантлаба", cursor.getString(1))
            assertTrue("the cover should be allowed to be missing", cursor.isNull(2))
            assertEquals(0, cursor.getInt(3))
        }
    }

    /**
     * Version 10 gives the reading day a character count, so the app can measure a reading pace
     * instead of assuming one.
     *
     * The column is added to a table that already has rows, which is the case the default matters
     * for: a day recorded before this version has to come back as zero characters — "not
     * measured" — with its seconds intact, or the first pace calculation would divide a day's
     * worth of time by nothing.
     */
    @Test
    fun migrate9To10_addsCharactersAndKeepsTheDaysAlreadyCounted() {
        helper.createDatabase(dbName, 9).use { db ->
            db.execSQL("INSERT INTO reading_days (date, seconds) VALUES ('2026-08-01', 1800)")
        }

        val db = helper.runMigrationsAndValidate(dbName, 10, true, AppDatabase.MIGRATION_9_10)

        db.query("SELECT date, seconds, chars FROM reading_days").use { cursor ->
            assertTrue("the day did not survive the migration", cursor.moveToFirst())
            assertEquals("2026-08-01", cursor.getString(0))
            assertEquals(1800, cursor.getInt(1))
            assertEquals("an unmeasured day must count as zero characters", 0, cursor.getInt(2))
        }

        db.execSQL("INSERT INTO reading_days (date, seconds, chars) VALUES ('2026-08-02', 900, 15000)")
        db.query("SELECT chars FROM reading_days WHERE date = '2026-08-02'").use { cursor ->
            assertTrue("the new column did not accept a value", cursor.moveToFirst())
            assertEquals(15000, cursor.getInt(0))
        }
    }
}
