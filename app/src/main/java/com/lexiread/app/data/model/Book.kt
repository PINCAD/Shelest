package com.lexiread.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a book stored in the user's local library.
 */
@Entity(tableName = "books")
@androidx.compose.runtime.Immutable
data class Book(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String = "",
    val filePath: String,
    val fileType: FileType,
    val coverPath: String? = null,
    val scrollPercent: Float = 0f,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long? = null,
    val totalChars: Int = 0,
    val ambientSoundId: String? = null,
    val ambientCustomPath: String? = null,
    val ambientEnabled: Boolean = false,
    val ambientVolume: Float = 0.5f,
    /**
     * When the reader marked this book as one they liked, or null if they never did.
     *
     * A timestamp rather than a flag because the discovery screen has to choose which few of
     * the liked books to actually build a taste profile from — every one of them costs a
     * network lookup — and "the ones they marked most recently" is the only ordering the app
     * has any right to assume.
     */
    val likedAt: Long? = null,
    /** Exact local place; scrollPercent is only the library's progress indicator. */
    val readingSegment: Int? = null,
    val readingPage: Int? = null,
    val readingChar: Int? = null,
    val readingLayout: String? = null,
    val readingScrollY: Double? = null
)

data class ReadingPosition(
    val segment: Int,
    val page: Int,
    val character: Int?,
    val layout: String,
    val scrollY: Double = 0.0,
    val anchor: String = ""
)

val Book.readingPosition: ReadingPosition?
    get() = readingPage?.let { page ->
        ReadingPosition(readingSegment ?: 0, page, readingChar, readingLayout.orEmpty(), readingScrollY ?: 0.0)
    }

fun Book.withReadingPosition(position: ReadingPosition) = copy(
    readingSegment = position.segment, readingPage = position.page,
    readingChar = position.character, readingLayout = position.layout,
    readingScrollY = position.scrollY
)

/** Marked by the reader as something they liked. See [Book.likedAt]. */
val Book.isLiked: Boolean get() = likedAt != null

enum class FileType(val extension: String, val displayName: String) {
    TXT("txt",   "TXT"),
    EPUB("epub", "EPUB"),
    FB2("fb2",   "FB2"),
    PDF("pdf",   "PDF"),
    HTML("html", "HTML"),
    MD("md",     "Markdown"),
    DOCX("docx", "DOCX"),
    CBZ("cbz",   "Комикс"),
    RTF("rtf",   "RTF"),
    ODT("odt",   "ODT"),
    MOBI("mobi", "MOBI"),
    AZW("azw",   "AZW");

    companion object {
        /** Every spelling of a name that means one of these formats. */
        private val BY_EXTENSION = mapOf(
            "txt"      to TXT,
            "text"     to TXT,
            "epub"     to EPUB,
            "fb2"      to FB2,
            // A .fb2 is nearly always handed round zipped, and the FB2 parser unpacks it itself.
            "zip"      to FB2,
            "pdf"      to PDF,
            "html"     to HTML,
            "htm"      to HTML,
            "xhtml"    to HTML,
            "md"       to MD,
            "markdown" to MD,
            "docx"     to DOCX,
            "cbz"      to CBZ,
            "rtf"      to RTF,
            "odt"      to ODT,
            "mobi"     to MOBI,
            "azw"      to AZW
        )

        fun fromExtension(ext: String): FileType? = BY_EXTENSION[ext.lowercase()]

        fun fromMimeType(mime: String): FileType? = when {
            mime.contains("epub")                   -> EPUB
            mime.contains("pdf")                    -> PDF
            mime.contains("fb2") || mime.contains("fictionbook") -> FB2
            mime.contains("wordprocessingml")       -> DOCX
            mime.contains("markdown")               -> MD
            mime.contains("rtf")                    -> RTF
            mime.contains("opendocument.text")       -> ODT
            mime.contains("mobipocket") || mime.contains("x-mobipocket") || mime.contains("amazon.ebook") -> MOBI
            mime.contains("comicbook") || mime.contains("x-cbz") -> CBZ
            mime.contains("html")                   -> HTML
            mime.contains("text/plain")             -> TXT
            // Left unanswered on purpose. "application/zip" and "application/octet-stream" are
            // what a picker says when it has no idea, and every format here can arrive wearing
            // one — the name and then the first bytes of the file decide instead.
            else                                    -> null
        }
    }
}
