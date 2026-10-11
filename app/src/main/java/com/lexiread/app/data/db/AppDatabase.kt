package com.lexiread.app.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lexiread.app.data.model.Annotation
import com.lexiread.app.data.model.AnnotationType
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.Bookmark
import com.lexiread.app.data.model.DefinitionCache
import com.lexiread.app.data.model.FileType
import com.lexiread.app.data.model.LikedTitle
import com.lexiread.app.data.model.OfflineDictEntry
import com.lexiread.app.data.model.ReadingDay
import com.lexiread.app.data.model.SynopsisCache
import com.lexiread.app.data.model.VocabularyWord
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun fromFileType(v: FileType): String = v.name
    @TypeConverter fun toFileType(v: String): FileType    = FileType.valueOf(v)

    @TypeConverter fun fromAnnotationType(v: AnnotationType): String = v.name
    @TypeConverter fun toAnnotationType(v: String): AnnotationType    = AnnotationType.valueOf(v)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books")
    suspend fun getBooksForSync(): List<Book>
    @Query("UPDATE books SET readingSegment = NULL, readingPage = NULL, readingChar = NULL, readingLayout = NULL, readingScrollY = NULL WHERE id = :id")
    suspend fun clearReadingPosition(id: Long)
    @Query("SELECT * FROM books ORDER BY COALESCE(lastOpenedAt, addedAt) DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: Long): Book?

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :id")
    suspend fun updateMetadata(id: Long, title: String, author: String)

    @Query("UPDATE books SET coverPath = :path WHERE id = :id")
    suspend fun updateCover(id: Long, path: String)

    /**
     * The top of the library — the book to offer next and the few behind it — asked for once
     * rather than watched.
     *
     * The home-screen widgets are drawn from a broadcast that has a few seconds to live and then
     * the process may be gone; a Flow there would be a collector with nobody left to collect it.
     */
    @Query("SELECT * FROM books ORDER BY COALESCE(lastOpenedAt, addedAt) DESC LIMIT :limit")
    suspend fun getRecentBooks(limit: Int): List<Book>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Delete
    suspend fun deleteBook(book: Book)

    @Query("UPDATE books SET scrollPercent = :percent, lastOpenedAt = :time WHERE id = :id")
    suspend fun updateProgress(id: Long, percent: Float, time: Long)

    @Query("UPDATE books SET readingSegment = :segment, readingPage = :page, readingChar = :character, readingLayout = :layout, readingScrollY = :scrollY WHERE id = :id")
    suspend fun updateReadingPosition(id: Long, segment: Int, page: Int, character: Int?, layout: String, scrollY: Double)

    @Query("UPDATE books SET ambientSoundId = :soundId, ambientCustomPath = :customPath, ambientEnabled = :enabled, ambientVolume = :volume WHERE id = :id")
    suspend fun updateAmbientSettings(id: Long, soundId: String?, customPath: String?, enabled: Boolean, volume: Float)

    /** Pass the current time to mark a book as liked, or null to take the mark back. */
    @Query("UPDATE books SET likedAt = :time WHERE id = :id")
    suspend fun setLikedAt(id: Long, time: Long?)

    /** Most recently liked first — that is the order the taste profile is built in. */
    @Query("SELECT * FROM books WHERE likedAt IS NOT NULL ORDER BY likedAt DESC")
    fun getLikedBooks(): Flow<List<Book>>
}

@Dao
interface LikedTitleDao {
    /** Most recently added first, to match the order liked shelf books arrive in. */
    @Query("SELECT * FROM liked_titles ORDER BY addedAt DESC")
    fun getAll(): Flow<List<LikedTitle>>

    /** Ignores a repeat of something already listed — see the unique index on the entity. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: LikedTitle): Long

    @Query("DELETE FROM liked_titles WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DefinitionCacheDao {
    @Query("SELECT * FROM definition_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun get(key: String): DefinitionCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: DefinitionCache)

    @Query("DELETE FROM definition_cache WHERE cachedAt < :threshold")
    suspend fun evictOld(threshold: Long)
}

@Dao
interface SynopsisCacheDao {
    @Query("SELECT * FROM synopsis_cache WHERE bookId = :bookId LIMIT 1")
    suspend fun get(bookId: String): SynopsisCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SynopsisCache)

    /**
     * Older than the given moment, dropped.
     *
     * A blurb is not news, but it is not permanent either: Fantlab's editors fill in the works
     * that had no description, and a card that once answered with the author's biography should
     * get another chance at the book itself rather than repeat the fallback forever.
     */
    @Query("DELETE FROM synopsis_cache WHERE cachedAt < :threshold")
    suspend fun evictOld(threshold: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND pageIndex = :page LIMIT 1")
    suspend fun getBookmarkAt(bookId: Long, page: Int): Bookmark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND pageIndex = :page")
    suspend fun delete(bookId: Long, page: Int)
}

@Dao
abstract class AnnotationDao {
    var onLocalChanged: (suspend (Annotation, Boolean) -> Unit)? = null
    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY createdAt ASC")
    abstract fun getForBook(bookId: Long): Flow<List<Annotation>>
    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY createdAt ASC")
    abstract suspend fun getOnce(bookId: Long): List<Annotation>
    @Query("SELECT * FROM annotations WHERE id = :id")
    abstract suspend fun getById(id: Long): Annotation?
    @Query("SELECT * FROM annotations WHERE id IN (:ids)")
    protected abstract suspend fun getByIds(ids: List<Long>): List<Annotation>
    @Query("UPDATE annotations SET color = :color WHERE id = :id")
    protected abstract suspend fun setColorRaw(id: Long,color: Int)
    @Query("UPDATE annotations SET noteText = :text WHERE id = :id")
    protected abstract suspend fun setNoteRaw(id: Long,text: String)
    @Transaction
    open suspend fun setColor(id: Long,color: Int) {setColorRaw(id,color);getById(id)?.let {onLocalChanged?.invoke(it,false)}}
    @Transaction
    open suspend fun setNote(id: Long,text: String) {setNoteRaw(id,text);getById(id)?.let {onLocalChanged?.invoke(it,false)}}

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRaw(annotation: Annotation): Long

    @Update
    protected abstract suspend fun updateRaw(annotation: Annotation)

    @Delete
    protected abstract suspend fun deleteRaw(annotation: Annotation)

    @Transaction
    open suspend fun insert(annotation: Annotation): Long {
        if(annotation.type == com.lexiread.app.data.model.AnnotationType.HIGHLIGHT && !annotation.selectedText.isNullOrEmpty()) {
            val existing=getOnce(annotation.bookId).lastOrNull {it.type==annotation.type && it.selectedText==annotation.selectedText && it.contextBefore==annotation.contextBefore && it.contextAfter==annotation.contextAfter}
            if(existing!=null) {setColor(existing.id,annotation.color);return existing.id}
        }
        val id = insertRaw(annotation)
        onLocalChanged?.invoke(annotation.copy(id=id), false)
        return id
    }
    @Transaction
    open suspend fun update(annotation: Annotation) { updateRaw(annotation); onLocalChanged?.invoke(annotation,false) }
    @Transaction
    open suspend fun delete(annotation: Annotation) { deleteRaw(annotation); onLocalChanged?.invoke(annotation,true) }
    @Transaction
    open suspend fun applyCloud(id: Long?, replacement: Annotation?, valid: () -> Boolean): Long? {
        if (!valid()) return null
        if (replacement != null) return insertRaw(replacement.copy(id=id ?: 0))
        if (id != null) deleteById(id)
        return 0
    }
    @Query("DELETE FROM annotations WHERE id = :id")
    protected abstract suspend fun deleteById(id: Long)

    @Transaction
    open suspend fun replaceDrawingFragments(original: Annotation, fragments: List<String>) {
        delete(original)
        fragments.forEach { path ->
            insert(original.copy(id = 0, pathData = path))
        }
    }

    /** Replace only these drawing rows, atomically; used by the reader's undo/redo history. */
    @Transaction
    open suspend fun replaceDrawingState(ids: List<Long>, target: List<Annotation>) {
        getByIds(ids.distinct()).forEach { delete(it) }
        target.forEach { drawing -> insert(drawing) }
    }

    @Transaction
    open suspend fun eraseDrawingPartsWithSnapshot(original: Annotation, fragments: List<String>): List<Annotation> {
        delete(original)
        return fragments.map { path ->
            val replacement = original.copy(id = 0, pathData = path)
            replacement.copy(id = insertRaw(replacement)).also { onLocalChanged?.invoke(it, false) }
        }
    }
}

@Dao
interface OfflineDictDao {
    /** Exact match */
    @Query("SELECT * FROM offline_dict WHERE word = :word LIMIT 1")
    suspend fun getExact(word: String): OfflineDictEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<OfflineDictEntry>)

    @Query("SELECT COUNT(*) FROM offline_dict")
    suspend fun count(): Int
}

@Dao
abstract class VocabularyDao {
    var onLocalChanged: (VocabularyWord, Boolean) -> Unit = { _, _ -> }
    @Query("SELECT * FROM vocabulary ORDER BY addedAt DESC")
    abstract fun getAll(): Flow<List<VocabularyWord>>

    @Query("SELECT * FROM vocabulary ORDER BY addedAt DESC")
    abstract suspend fun getOnce(): List<VocabularyWord>

    @Query("SELECT * FROM vocabulary WHERE id = :id")
    abstract suspend fun getById(id: Long): VocabularyWord?

    @Query("SELECT * FROM vocabulary WHERE word = :word LIMIT 1")
    abstract suspend fun getByWord(word: String): VocabularyWord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRaw(word: VocabularyWord)
    @Transaction
    open suspend fun insert(word: VocabularyWord, notifyCloud: Boolean = true) {
        insertRaw(word); if (notifyCloud) onLocalChanged(word, false)
    }

    @Delete
    protected abstract suspend fun deleteRaw(word: VocabularyWord)
    @Transaction
    open suspend fun delete(word: VocabularyWord, notifyCloud: Boolean = true) {
        deleteRaw(word); if (notifyCloud) onLocalChanged(word, true)
    }

    @Query("UPDATE vocabulary SET isLearned = :learned WHERE id = :id")
    protected abstract suspend fun setLearnedRaw(id: Long, learned: Boolean)
    @Transaction
    open suspend fun setLearned(id: Long, learned: Boolean) {
        val word = getById(id) ?: return
        setLearnedRaw(id, learned); onLocalChanged(word.copy(isLearned = learned), false)
    }

    @Transaction
    open suspend fun applyCloudWord(matches: List<VocabularyWord>, replacement: VocabularyWord?, valid: () -> Boolean): Boolean {
        // Runs on Room's transaction executor; check after queued local writes, not before.
        if (!valid()) return false
        if (replacement == null) matches.forEach { deleteRaw(it) }
        else { insertRaw(replacement); matches.drop(1).forEach { deleteRaw(it) } }
        return true
    }
}

@Dao
interface ReadingDayDao {
    @Query("SELECT * FROM reading_days ORDER BY date DESC LIMIT 400")
    fun getRecent(): Flow<List<ReadingDay>>

    /** The same rows as [getRecent], for callers that want one answer and not a subscription. */
    @Query("SELECT * FROM reading_days ORDER BY date DESC LIMIT 400")
    suspend fun getRecentOnce(): List<ReadingDay>

    @Query("SELECT * FROM reading_days WHERE date = :date LIMIT 1")
    suspend fun getDay(date: String): ReadingDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: ReadingDay)
}

@Database(
    entities     = [
        Book::class, DefinitionCache::class, Bookmark::class, OfflineDictEntry::class,
        Annotation::class, VocabularyWord::class, ReadingDay::class, LikedTitle::class,
        SynopsisCache::class
    ],
    version      = 11,
    // Exported to app/schemas (see build.gradle.kts) so every future version has a written
    // description of the tables it expects, which is what a migration test compares against.
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun definitionCacheDao(): DefinitionCacheDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun offlineDictDao(): OfflineDictDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun vocabularyDao(): VocabularyDao
    abstract fun readingDayDao(): ReadingDayDao
    abstract fun likedTitleDao(): LikedTitleDao
    abstract fun synopsisCacheDao(): SynopsisCacheDao

    companion object {
        /**
         * Every schema change from version 6 onwards, in order.
         *
         * The rule from here on: bumping [version] means adding a Migration to this list, in the
         * same commit. Room will not start without one — which is the point. Up to version 6 the
         * database was built with a blanket `fallbackToDestructiveMigration()`, so a reader who
         * updated the app across a schema change silently lost their entire library: every book,
         * every note, every saved word, and the reading streak with them. Nothing warned about
         * it, because from Room's side wiping the file *is* the configured behaviour.
         *
         * Writing a migration is a few lines of ALTER TABLE. It is not what makes this hard —
         * remembering that it is required is, and that is now enforced rather than remembered.
         */
        /**
         * 7: books the reader marked as liked, for the discovery screen. Nullable, so every
         * book already on the shelf simply arrives unmarked and nothing is lost — which is the
         * whole reason this list exists.
         *
         * A column Room considers NOT NULL would have to spell its default out in the SQL as
         * well, or the exported schema and the real table would not match.
         *
         * Named rather than anonymous so the migration test can run this exact object against
         * a real version 6 database. A migration that is only reachable through the builder is
         * a migration that is only ever tested by shipping it.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN likedAt INTEGER")
            }
        }

        /**
         * 8: books the reader likes but does not own, typed in by hand.
         *
         * Purely additive — an empty new table alongside everything that was already there.
         * The SQL has to match what Room generates for [LikedTitle] character for character,
         * index included, or the schema comparison in the migration test fails.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `liked_titles` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`author` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_liked_titles_title_author` " +
                        "ON `liked_titles` (`title`, `author`)"
                )
            }
        }

        /**
         * 9: what a suggested book is about, kept between runs.
         *
         * Additive again, and a cache rather than the reader's own data — losing it would cost
         * nothing but the requests to fill it again. It gets a migration all the same, because
         * the alternative is a destructive fallback, and a fallback configured once is a fallback
         * that applies to every table in the file.
         *
         * `aboutAuthor` is a boolean, which SQLite stores as INTEGER and Room declares NOT NULL;
         * the SQL has to say so in the same words the generated schema does.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `synopsis_cache` (" +
                        "`bookId` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`credit` TEXT NOT NULL, " +
                        "`coverUrl` TEXT, " +
                        "`aboutAuthor` INTEGER NOT NULL, " +
                        "`cachedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`bookId`))"
                )
            }
        }

        /**
         * The reading day learns how far the reader got, not just how long they sat.
         *
         * Existing rows default to zero characters, which is not the same as "read nothing" —
         * it is "not measured". The pace calculation knows the difference and keeps using its
         * assumed figure until enough measured days have accumulated to beat it.
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_days ADD COLUMN chars INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN readingSegment INTEGER")
                db.execSQL("ALTER TABLE books ADD COLUMN readingPage INTEGER")
                db.execSQL("ALTER TABLE books ADD COLUMN readingChar INTEGER")
                db.execSQL("ALTER TABLE books ADD COLUMN readingLayout TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN readingScrollY REAL")
            }
        }

        private val MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)

        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                // The second check is not redundant. Two threads can both find INSTANCE null
                // and both enter here in turn, and without it the second one builds a whole
                // second database: two connection pools over one file, and two invalidation
                // trackers that never hear about each other's writes — so whichever Flows
                // were collected from the loser simply stop updating.
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pebble.db"
                ).addMigrations(*MIGRATIONS)
                 // Only the versions that were already destroyed on the way to 6, and only
                 // those: no schema history was ever exported for them, so there is nothing
                 // left to migrate from and anyone still on one of them lost the library the
                 // last time they updated regardless. Listing them explicitly, rather than
                 // leaving the blanket fallback in place, is what makes version 7 a compile-
                 // and-crash-at-launch problem for us instead of a silent wipe for them.
                 .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                 .build()
                 .also { INSTANCE = it }
            }
    }
}
