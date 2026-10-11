package com.lexiread.app.ui.reader

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import android.util.LruCache
import com.lexiread.app.domain.parser.imageMime
import com.lexiread.app.domain.parser.mediaDirFor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/**
 * Serves the book to its own WebView, from an origin that exists only inside this app.
 *
 * The reader used to be handed the whole book as one string through `loadDataWithBaseURL`, with
 * every illustration carried inside it as base64. Two things were wrong with that. The string goes
 * across a binder transaction, which has a ceiling a long illustrated book can reach — and past it
 * the page comes up blank with nothing to say why. And base64 is four bytes for every three, all
 * of them built and held in memory before the first page can be drawn, which is why pictures past
 * three megabytes had to be thrown away.
 *
 * Serving them instead costs nothing until the page carrying them is reached, has no ceiling, and
 * lets a comic — several hundred photographs and not one word — be read at all.
 *
 * Everything the document asks for goes through [intercept], and anything this class does not
 * recognise is refused. That is not only tidiness: a book is a file from the internet, and one
 * that quietly fetched a tracking pixel every time a chapter opened would otherwise be able to.
 */
class BookAssetServer(private val context: Context) {

    /** Recently visited illustrations: avoids opening and inflating the same archive entry again. */
    private val imageCache = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    @Volatile private var document: ByteArray = EMPTY

    /** The book's own file, when it is an archive the document points into. */
    @Volatile private var archivePath: String? = null
    /** Where the book's pictures were unpacked to, for a format that carries them inside itself. */
    @Volatile private var mediaDir: File? = null
    @Volatile private var version: Int = 0

    /** The archive, held open between requests, and the path it was opened from. */
    private var opened: ZipFile? = null
    private var openedPath: String? = null

    /**
     * Publishes [document] as the current document and returns the URL to load it from.
     *
     * Bytes rather than a string, and deliberately: encoding a twenty-megabyte book to UTF-8 is
     * a second copy of the whole thing, and this is called from the composition — that is, on the
     * main thread, where the price of it is a visibly frozen app. It is now done by whoever built
     * the document, on whichever thread they built it.
     *
     * The URL carries a version because a WebView asked to load the address it is already showing
     * treats it as a fragment jump; a reader who switched from scrolling to pages would have got
     * the old document back.
     */
    @Synchronized
    fun publish(document: ByteArray, sourcePath: String?): String {
        this.document = document
        val nextArchive = sourcePath?.takeIf { looksLikeArchive(it) }
        // A different book means the handle we are holding is a handle on somebody else's file.
        if (nextArchive != openedPath) closeArchive()
        archivePath = nextArchive
        mediaDir = sourcePath?.let { mediaDirFor(File(it)) }
        version++
        return documentUrl()
    }

    @Synchronized
    fun documentUrl(): String = "$ORIGIN/doc/$version/index.html"

    /** True for a URL this server owns — anything else must not be fetched at all. */
    fun handles(url: String): Boolean = url.startsWith(ORIGIN)

    /**
     * Answers one request from the document, or refuses it.
     *
     * Runs on the WebView's own thread, so nothing here may touch the UI, and everything it reads
     * is either immutable or volatile.
     */
    fun intercept(url: String): WebResourceResponse? {
        if (!handles(url)) return notFound()
        val path = runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
        return when {
            path.endsWith("/index.html") -> response("text/html", document)
            path.startsWith(FONT_PATH)   -> font(path.removePrefix(FONT_PATH))
            path.startsWith(RES_PATH)    -> archived(path.removePrefix(RES_PATH))
            path.startsWith(MEDIA_PATH)  -> unpacked(path.removePrefix(MEDIA_PATH))
            else                         -> notFound()
        }
    }

    /**
     * A picture that was written out of the book when it was read — an FB2 illustration.
     *
     * A name that leads nowhere is answered with a transparent pixel rather than a refusal. The
     * document is written before the pictures are reached (an FB2 keeps them all at the end of the
     * file), so a book referring to an illustration it does not contain is a thing that happens,
     * and a broken-image icon in the middle of a paragraph is worse than nothing at all.
     */
    private fun unpacked(name: String): WebResourceResponse {
        val dir = mediaDir ?: return blank()
        // Already decoded: Uri.getPath() undoes the escaping the document was written with.
        if (name.isBlank() || name.contains('/') || name.contains("..")) return blank()
        val image = File(dir, name)
        if (!image.isFile) return blank()
        if (image.length() > MAX_CACHED_IMAGE_BYTES) {
            return streamResponse(imageMime(name), FileInputStream(image))
        }
        val bytes = cachedImage("file:${dir.absolutePath}/$name") {
            File(dir, name).readBytes()
        }
        if (bytes == null || bytes.isEmpty()) return blank()
        return response(sniffImageMime(name, bytes), bytes)
    }

    /**
     * What a picture actually is, judged by what it starts with rather than by what it is called.
     *
     * An FB2 names its pictures whatever the person who made it felt like, extension or no
     * extension, so the name alone had every PNG in half the books announced as a JPEG.
     */
    private fun sniffImageMime(name: String, bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() -> "image/gif"
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() -> "image/webp"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 5 && bytes[0] == '<'.code.toByte() -> "image/svg+xml"
        else -> imageMime(name)
    }

    /**
     * A file out of the book itself, named the way it is named inside the archive.
     *
     * The archive is opened once and kept. Opening a zip means reading and parsing its central
     * directory — the list of everything in it — and this used to be done afresh for every
     * picture requested. An illustrated book asks for hundreds, one after another, all of them
     * before the first page can be measured, and every one of them paid for that list again.
     */
    private fun archived(entryName: String): WebResourceResponse {
        if (entryName.isBlank()) return notFound()
        val zip = openArchive() ?: return notFound()
        val entry = zip.getEntry(entryName)
            ?: zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.equals(entryName, ignoreCase = true)
            }
            ?: return notFound()
        if (entry.isDirectory) return notFound()
        if (entry.size > MAX_CACHED_IMAGE_BYTES) {
            return streamResponse(imageMime(entryName), zip.getInputStream(entry))
        }
        val bytes = cachedImage("zip:${archivePath.orEmpty()}:$entryName") {
            zip.getInputStream(entry).use { it.readBytes() }
        } ?: return notFound()
        return response(imageMime(entryName), bytes)
    }

    /**
     * The book's archive, opened if it is not open already.
     *
     * Synchronised because requests arrive on the WebView's thread while [publish] may be
     * changing the book underneath from another — and two threads opening the same file would
     * leave one of the two handles with nobody to close it.
     */
    @Synchronized
    private fun openArchive(): ZipFile? {
        val source = archivePath ?: return null
        opened?.let { if (openedPath == source) return it }
        closeArchive()
        val zip = runCatching { ZipFile(source) }.getOrNull() ?: return null
        opened = zip
        openedPath = source
        return zip
    }

    /** Called when the reader closes: nothing here should outlive the book being read. */
    @Synchronized
    fun release() {
        closeArchive()
        archivePath = null
        imageCache.evictAll()
    }

    @Synchronized
    private fun closeArchive() {
        runCatching { opened?.close() }
        opened = null
        openedPath = null
    }

    private fun cachedImage(key: String, load: () -> ByteArray?): ByteArray? = synchronized(imageCache) {
        imageCache.get(key) ?: runCatching { load() }.getOrNull()?.also { imageCache.put(key, it) }
    }

    /**
     * A bundled typeface.
     *
     * These used to be loaded straight off `file:///android_asset`, which worked only because the
     * document had no origin of its own: a real one may not reach into `file://`, and the reading
     * fonts would all have silently fallen back to the system serif.
     */
    private fun font(name: String): WebResourceResponse {
        if (name.isBlank() || name.contains('/') || name.contains("..")) return notFound()
        // WebView consumes/closes the stream. No extra full-font ByteArray is allocated
        // on each document reload or segment turn.
        val stream = runCatching { context.assets.open("fonts/$name") }.getOrNull() ?: return notFound()
        val mime = if (name.endsWith(".otf", true)) "font/otf" else "font/ttf"
        return streamResponse(mime, stream)
    }

    private fun response(mime: String, bytes: ByteArray) =
        WebResourceResponse(mime, "UTF-8", 200, "OK", HEADERS, ByteArrayInputStream(bytes))

    private fun streamResponse(mime: String, stream: java.io.InputStream) =
        WebResourceResponse(mime, "UTF-8", 200, "OK", HEADERS, stream)

    private fun notFound() =
        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", HEADERS, ByteArrayInputStream(EMPTY))

    /** One transparent pixel: a picture that isn't there, drawn as nothing rather than as a hole. */
    private fun blank() = response("image/gif", BLANK_PIXEL)

    private fun looksLikeArchive(path: String): Boolean =
        when (path.substringAfterLast('.', "").lowercase()) {
            "epub", "docx", "cbz", "zip" -> true
            else -> false
        }

    companion object {
        /**
         * A name that resolves to nothing anywhere. `.local` is reserved for exactly this, so a
         * request that somehow escaped the interceptor would fail rather than reach a stranger's
         * server — and the app asks for no internet permission for the reader in any case.
         */
        const val ORIGIN = "https://book.shelest.local"

        /** Kept in step with ARCHIVE_PATH and MEDIA_PATH in the parsers, which write these URLs. */
        private const val RES_PATH = "/res/"
        private const val MEDIA_PATH = "/media/"
        private const val FONT_PATH = "/font/"

        private val EMPTY = ByteArray(0)
        private const val MAX_CACHED_IMAGE_BYTES = 256 * 1024L

        private val BLANK_PIXEL: ByteArray = java.util.Base64.getDecoder()
            .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")

        // The document is rebuilt with a new address every time it changes, so nothing is served
        // twice; caching it would only be a copy of the book kept somewhere else.
        private val HEADERS = mapOf("Cache-Control" to "no-store")
    }
}
