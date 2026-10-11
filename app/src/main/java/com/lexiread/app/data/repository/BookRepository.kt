package com.lexiread.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lexiread.app.data.db.BookDao
import com.lexiread.app.data.db.LikedTitleDao
import com.lexiread.app.data.model.Book
import com.lexiread.app.data.model.FileType
import com.lexiread.app.data.model.LikedTitle
import com.lexiread.app.data.model.ReadingPosition
import com.lexiread.app.data.model.readingPosition
import com.lexiread.app.data.model.withReadingPosition
import com.lexiread.app.domain.discover.LikedItem
import com.lexiread.app.domain.parser.BookParserFactory
import com.lexiread.app.domain.parser.DocumentCache
import com.lexiread.app.domain.parser.ParsedBook
import com.lexiread.app.domain.parser.TocEntry
import com.lexiread.app.domain.parser.mediaDirFor
import com.lexiread.app.domain.parser.readHead
import com.lexiread.app.widget.BookWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class BookRepository(
    private val context: Context,
    private val dao: BookDao,
    private val likedTitleDao: LikedTitleDao
) {
    val allBooks: Flow<List<Book>> = dao.getAllBooks()
    var onLocalPlaceChanged: (Long) -> Unit = {}
    var onAnchorCaptured: (Long, String) -> Unit = { _, _ -> }
    var beforeReading: suspend (Long) -> Unit = {}
    var beforeDeleting: suspend (Book) -> Unit = {}
    var onLibraryChanged: () -> Unit = {}
    var onUserImport: suspend (Book) -> Unit = {}
    var onTitleChanged: suspend (Book, String) -> Unit = { _, _ -> }

    /** Change the displayed name, not the content identity, anchors or source URI. */
    suspend fun renameBook(id: Long, name: String) = withContext(Dispatchers.IO) {
        val title = name.trim().take(500)
        require(title.isNotEmpty()) { "Введите название" }
        val book = dao.getBookById(id) ?: error("Книга не найдена")
        dao.updateMetadata(id, title, book.author)
        if (title != book.title) onTitleChanged(book, title)
        onLibraryChanged()
        BookWidget.refresh(context, force = true)
    }

    suspend fun setCover(id: Long, uri: Uri) = withContext(Dispatchers.IO) {
        val book = dao.getBookById(id) ?: error("Книга не найдена")
        // Decode bounded pixels, never a camera photograph at its original resolution.
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                require(buffer.size() + n <= 20 * 1024 * 1024) { "Обложка больше 20 МБ" }
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        } ?: error("Не удалось открыть изображение")
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Выберите изображение" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1200) sample *= 2
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Не удалось прочитать изображение")
        val file = File(File(context.filesDir, "covers").apply { mkdirs() }, "custom-$id-${java.util.UUID.randomUUID()}.jpg")
        try {
            file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)) }
            dao.updateCover(id, file.absolutePath)
        } catch (e: Exception) { file.delete(); throw e }
        finally { bitmap.recycle() }
        book.coverPath?.let { old ->
            val previous = File(old)
            if (previous.parentFile?.canonicalPath == file.parentFile?.canonicalPath) previous.delete()
        }
        BookWidget.refresh(context, force = true)
    }

    // Navigation cancels the screen's ViewModel scope. Position writes must outlive it,
    // stay ordered, and also be visible to an immediate reopen before SQLite finishes.
    private val latestPositions = ConcurrentHashMap<Long, ReadingPosition>()
    private val pendingPositions = ConcurrentHashMap<Long, ReadingPosition>()
    private val positionWrites = Channel<Unit>(Channel.CONFLATED)
    private val positionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val positionPersistenceLock = Mutex()

    init {
        positionScope.launch {
            for (signal in positionWrites) {
                for ((id, position) in pendingPositions.entries) {
                    if (!pendingPositions.remove(id, position)) continue
                    try {
                        positionPersistenceLock.withLock {
                            if (latestPositions[id] == position)
                                dao.updateReadingPosition(id, position.segment, position.page,
                                    position.character, position.layout, position.scrollY)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ReadingPosition", "Unable to save reading place", e)
                    }
                }
            }
        }
    }

    fun saveReadingPosition(id: Long, position: ReadingPosition) {
        if (id <= 0) return
        if (position.anchor.isNotBlank()) onAnchorCaptured(id, position.anchor)
        val previous = latestPositions.put(id, position)
        if (previous?.copy(anchor = "") == position.copy(anchor = "")) return
        pendingPositions[id] = position
        positionWrites.trySend(Unit)
        fun ReadingPosition.sharedPlace() = com.shelest.shared.sync.ReadingPlace(
            "android", segment, page, character, layout, scrollY, anchor = anchor)
        if (com.shelest.shared.sync.SyncRules.isReadingChange(previous?.sharedPlace(), position.sharedPlace()))
            onLocalPlaceChanged(id)
    }

    suspend fun applyCloudPosition(id: Long, percent: Float, updatedAt: Long, position: ReadingPosition?) = positionPersistenceLock.withLock {
        pendingPositions.remove(id)
        if (position != null) {
            latestPositions[id] = position
            dao.updateReadingPosition(id, position.segment, position.page, position.character, position.layout, position.scrollY)
        } else {
            latestPositions.remove(id)
            dao.clearReadingPosition(id)
        }
        dao.updateProgress(id, percent, updatedAt)
    }

    suspend fun addBook(uri: Uri, fromCloud: Boolean = false): Book? = withContext(Dispatchers.IO) {
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val tempFile = File(booksDir, "temp_${System.currentTimeMillis()}")
        try {
            val mimeType    = context.contentResolver.getType(uri) ?: "*/*"
            val displayName = queryFileName(uri)

            // The name first, the declared type second. A picker opened on "*/*" answers
            // "application/octet-stream" for most books and "application/zip" for the three
            // formats that are zips, so the type it declares is the weakest of the three signals
            // and used to overrule the two better ones.
            val declaredType = extensionType(displayName)
                ?: extensionType(uri.lastPathSegment)
                ?: FileType.fromMimeType(mimeType)

            val stream = context.contentResolver.openInputStream(uri)
                ?: error("Не удалось прочитать файл")
            stream.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            // Nothing useful said by either — the picker can hand back a name with no dot at all.
            // Ask the bytes themselves; every format here announces itself in its first few.
            val finalFileType = declaredType ?: sniffType(tempFile)

            val fileName = displayName
                ?: "book_${System.currentTimeMillis()}.${finalFileType.extension}"

            val destFile = uniqueFile(booksDir, fileName)
            if (!tempFile.renameTo(destFile)) error("Не удалось сохранить файл книги")

            try {
                val parser   = BookParserFactory.create(finalFileType)
                val metadata = parser.extractMetadata(destFile)

                // Save cover file if extracted
                var coverPath: String? = null
                if (metadata.coverBytes != null && metadata.coverBytes.isNotEmpty()) {
                    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
                    val coverFile = File(coversDir, "${System.currentTimeMillis()}_cover.jpg")
                    coverFile.writeBytes(metadata.coverBytes)
                    coverPath = coverFile.absolutePath
                }

                val book = Book(
                    title      = metadata.title.ifBlank { fileName.substringBeforeLast(".") },
                    author     = metadata.author,
                    filePath   = destFile.absolutePath,
                    fileType   = finalFileType,
                    coverPath  = coverPath,
                    totalChars = metadata.totalChars
                )
                val id = dao.insertBook(book)
                // A freshly imported book is the one the reader is about to open, so it is what
                // the widget should be offering before they ever get back to the home screen.
                BookWidget.refresh(context, force = true)
                book.copy(id = id).also { if (!fromCloud) { runCatching { onUserImport(it) }; onLibraryChanged() } }
            } catch (e: Exception) {
                destFile.delete()
                throw e
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            // Anything that went wrong between the copy and the rename leaves a half-written
            // temp file sitting in the books folder, where nothing ever looks at it again. A
            // successful import has already renamed it away, so this only ever finds wreckage.
            if (tempFile.exists()) tempFile.delete()
        }
    }

    suspend fun getBook(id: Long): Book? = dao.getBookById(id)?.let { book ->
        book.readingPosition?.let { latestPositions.putIfAbsent(id, it) }
        latestPositions[id]?.let(book::withReadingPosition) ?: book
    }

    suspend fun deleteBook(book: Book, notifyCloud: Boolean = true) = withContext(Dispatchers.IO) {
        if (notifyCloud) beforeDeleting(book)
        latestPositions.remove(book.id)
        pendingPositions.remove(book.id)
        book.coverPath?.let { File(it).delete() }
        // Pictures a format carries inside itself are unpacked next to the book the first time it
        // is opened; without this they would outlive it, and an illustrated book leaves as much
        // behind as it took up.
        mediaDirFor(File(book.filePath)).deleteRecursively()
        // The same goes for the document built out of it, which for a long book is a second copy
        // of the whole text.
        DocumentCache.clear(File(book.filePath))
        File(book.filePath).delete()
        dao.deleteBook(book)
        // A widget still offering a book that no longer exists is worse than an empty one.
        BookWidget.refresh(context, force = true)
        if (notifyCloud) onLibraryChanged()
    }

    suspend fun updateProgress(id: Long, percent: Float) {
        dao.updateProgress(id, percent, System.currentTimeMillis())
        // Not forced: this is called on every page turn, and while a book is being read aloud
        // the pages turn themselves. The widget's own quiet period decides how often that is
        // worth a redraw.
        BookWidget.refresh(context)
    }

    suspend fun updateAmbientSettings(id: Long, soundId: String?, customPath: String?, enabled: Boolean, volume: Float) =
        dao.updateAmbientSettings(id, soundId, customPath, enabled, volume)

    suspend fun setLiked(id: Long, liked: Boolean) =
        dao.setLikedAt(id, if (liked) System.currentTimeMillis() else null)

    /**
     * Everything the reader likes, from the shelf and typed in by hand, newest first.
     *
     * Both halves are only a title and an author by the time anything downstream sees them, so
     * they merge into one list rather than being carried separately the whole way down. Sorting
     * across the two by time is what keeps "the last thing I marked" at the front no matter
     * which way it was marked.
     */
    val likedItems: Flow<List<LikedItem>> =
        combine(dao.getLikedBooks(), likedTitleDao.getAll()) { books, typed ->
            val fromShelf = books.map { book ->
                LikedItem(
                    key       = "book:${book.id}",
                    title     = book.title,
                    author    = book.author,
                    coverPath = book.coverPath,
                    onShelf   = true
                ) to (book.likedAt ?: 0L)
            }
            val fromTyped = typed.map { entry ->
                LikedItem(
                    key     = "typed:${entry.id}",
                    title   = entry.title,
                    author  = entry.author,
                    onShelf = false
                ) to entry.addedAt
            }
            (fromShelf + fromTyped).sortedByDescending { it.second }.map { it.first }
        }

    /** Returns false when the entry is blank or already listed. */
    suspend fun addLikedTitle(title: String, author: String): Boolean =
        withContext(Dispatchers.IO) {
            val cleanTitle = title.trim()
            if (cleanTitle.isEmpty()) return@withContext false
            // IGNORE on conflict returns -1 for a row the unique index rejected: the reader
            // typed something they had already added, which is worth saying so rather than
            // silently doing nothing.
            likedTitleDao.insert(
                LikedTitle(title = cleanTitle, author = author.trim())
            ) != -1L
        }

    suspend fun removeLikedTitle(id: Long) = likedTitleDao.delete(id)

    suspend fun saveCustomAudioFile(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val ambientDir = File(context.filesDir, "ambient").apply { mkdirs() }
            val fileName   = "custom_${System.currentTimeMillis()}.mp3"
            val destFile   = File(ambientDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * The document to show, together with whatever contents the book states for itself.
     *
     * A long book is parsed once and kept (see [DocumentCache]): the file does not change between
     * one opening and the next, and re-reading twenty megabytes of XML every time the reader taps
     * the book on the shelf was the largest part of the wait before the first page appeared.
     */
    suspend fun loadBook(book: Book): ParsedBook = withContext(Dispatchers.IO) {
        val file = File(book.filePath)
        try {
            val cacheable = book.fileType != FileType.PDF && DocumentCache.worthCaching(file)
            if (cacheable) cachedBook(file, book.scrollPercent, book.readingSegment)?.let { return@withContext it }

            val parsed = BookParserFactory.create(book.fileType).extractBook(file)
            // A book that parsed cleanly and yielded nothing used to open as a blank white page
            // with no way to tell a broken file from a slow one. Saying so is not a fix for the
            // file, but it is the difference between a bug and an explanation.
            if (parsed.html.isNotBlank()) {
                // Only a document worth the read: nothing is gained by keeping an error page, and
                // a PDF never gets this far — its "document" is a path, and its pages are drawn
                // from the file itself.
                if (cacheable) {
                    // Before 1.4.3, small files were read as one document. Preserve that
                    // coordinate system for already-read books, including their bookmarks.
                    DocumentCache.store(file, parsed,
                        preserveWholeDocument = file.length() < (1L shl 20) && book.lastOpenedAt != null)
                    // Do not immediately undo the split by returning the full string. The parser
                    // needed it on this first pass, but the WebView only needs the piece containing
                    // the saved position; releasing the full value here keeps layout bounded.
                    cachedBook(file, book.scrollPercent, book.readingSegment) ?: parsed
                } else parsed
            } else ParsedBook(
                "<h2>Не удалось прочитать текст</h2>" +
                "<p>Файл открылся, но текста в нём не нашлось. Так бывает, когда книга " +
                "состоит из сканов страниц, защищена DRM или собрана с ошибками.</p>"
            )
        } catch (e: Exception) {
            ParsedBook("<p style='color:red'>Ошибка загрузки книги: ${e.message}</p>")
        }
    }

    /** Reads one cached piece for a page-boundary turn. Never joins the rest of the book. */
    suspend fun loadBookSegment(filePath: String, index: Int): String? =
        withContext(Dispatchers.IO) { DocumentCache.segmentHtml(File(filePath), index) }

    suspend fun resolveCloudAnchor(book: Book, anchor: String): ReadingPosition? = withContext(Dispatchers.IO) {
        if (anchor.length < 12) return@withContext null
        val parsed = loadBook(book)
        val indices = parsed.segments.map { it.index }.ifEmpty { listOf(0) }
        var found: ReadingPosition? = null
        for (index in indices) {
            val html = if (parsed.segments.isEmpty() || index == parsed.segmentIndex) parsed.html
                else loadBookSegment(book.filePath, index) ?: continue
            val document = org.jsoup.Jsoup.parse(html)
            document.select("script,style").remove()
            val text = StringBuilder()
            org.jsoup.select.NodeTraversor.traverse(object : org.jsoup.select.NodeVisitor {
                override fun head(node: org.jsoup.nodes.Node, depth: Int) {
                    if (node is org.jsoup.nodes.TextNode) text.append(node.wholeText)
                }
                override fun tail(node: org.jsoup.nodes.Node, depth: Int) {}
            }, document.body())
            val at = com.shelest.shared.sync.TextAnchor.uniqueOffset(text.toString(), anchor)
            if (at != null) {
                if (found != null) return@withContext null
                found = ReadingPosition(index, 0, at, "", 0.0, anchor)
            }
        }
        found
    }

    private fun cachedBook(file: File, progress: Float, savedSegment: Int? = null): ParsedBook? {
        val segments = DocumentCache.segments(file)
        if (segments.isEmpty()) return null

        val total = segments.maxOfOrNull { it.endChar }?.coerceAtLeast(1) ?: return null
        val target = (progress.coerceIn(0f, 100f) / 100f * total).toInt()
        val index = savedSegment?.takeIf { it in segments.indices }
            ?: segments.indexOfFirst { target < it.endChar }
                .let { if (it >= 0) it else segments.lastIndex }
        val html = DocumentCache.segmentHtml(file, index) ?: return null
        val stated = DocumentCache.contents(file)
        val toc = if (stated.isNotEmpty()) stated else segments.mapNotNull { segment ->
            segment.title.takeIf { it.isNotBlank() }?.let {
                TocEntry(title = it, anchor = "", level = 1, segmentIndex = segment.index)
            }
        }
        return ParsedBook(
            html          = html,
            toc           = toc,
            segments      = segments,
            segmentIndex  = index,
            plainTextPath = DocumentCache.plainTextPath(file)
        )
    }

    private fun extensionType(name: String?): FileType? =
        name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }?.let { FileType.fromExtension(it) }

    /**
     * The format a file is in, judged by what it starts with.
     *
     * The zip formats need a second look inside: EPUB, DOCX and a zipped FB2 are the same four
     * bytes at the front, and telling them apart by hunting for "epub+zip" in the first fifty
     * bytes only worked when the archive happened to store its mimetype entry first.
     */
    private fun sniffType(file: File): FileType {
        val head = readHead(file, 64)
        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
            return sniffZip(file)
        }
        // Read as Latin-1 so that binary bytes map to characters instead of decoding failures;
        // every marker looked for here is ASCII.
        val header = String(head, Charsets.ISO_8859_1)
        return when {
            header.startsWith("%PDF")                          -> FileType.PDF
            header.startsWith("{\\rtf")                        -> FileType.RTF
            header.contains("<FictionBook", ignoreCase = true) -> FileType.FB2
            header.contains("<html", ignoreCase = true) ||
                header.contains("<!DOCTYPE html", ignoreCase = true) -> FileType.HTML
            header.contains("<?xml")                           -> FileType.FB2
            else                                               -> FileType.TXT
        }
    }

    private fun sniffZip(file: File): FileType = runCatching {
        java.util.zip.ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            when {
                zip.getEntry("META-INF/container.xml") != null -> FileType.EPUB
                zip.getEntry("word/document.xml") != null      -> FileType.DOCX
                zip.getEntry("content.xml") != null && zip.getEntry("meta.xml") != null -> FileType.ODT
                // Nothing but pictures inside means a comic: no format states that about itself,
                // and there is nothing else a zip full of numbered JPEGs could be.
                names.any { isPictureName(it) } && names.all { isPictureName(it) || isPackerNote(it) }
                    -> FileType.CBZ
                else                                          -> FileType.FB2
            }
        }
    }.getOrDefault(FileType.FB2)

    private fun isPictureName(name: String): Boolean =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> true
            else -> false
        }

    /** What a packer leaves behind: one such file does not stop a folder of pictures being a comic. */
    private fun isPackerNote(name: String): Boolean =
        when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "xml", "nfo", "db", "sfv", "url" -> true
            else -> false
        }

    /**
     * A free name in [dir] for a file the user called [fileName].
     *
     * The shelf is keyed by row, not by path, so nothing stopped the same book being added
     * twice — but the copy on disk was keyed by the file's own name, and a rename onto an
     * existing path silently replaces it. Two rows then pointed at one file, and deleting
     * either of them deleted the other's book out from under it.
     */
    private fun uniqueFile(dir: File, fileName: String): File {
        val base = fileName.substringBeforeLast(".", fileName)
        val ext  = fileName.substringAfterLast(".", "").let { if (it.isEmpty()) "" else ".$it" }
        var candidate = File(dir, fileName)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    private fun queryFileName(uri: Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && col >= 0) cursor.getString(col) else null
        }
}
