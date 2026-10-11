package com.lexiread.app.domain.parser

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Comics and manga (.cbz): a zip of page images and nothing else — no manifest, no order stated
 * anywhere, just file names that a human is expected to read in the obvious way. So the pages are
 * sorted the way a human would sort them, where "page 2" comes before "page 10".
 *
 * The pages are not carried into the document. A comic is several hundred photographs and would be
 * hundreds of megabytes of base64; each page is a URL into the archive instead, which the reader
 * serves as the page is reached (see BookAssetServer). Reading one is a matter of turning pages,
 * so nothing here needs the text machinery — there is no text.
 *
 * .cbr is the same idea inside a RAR archive, which Android cannot open without a library, so it
 * is not claimed here: a file that cannot be read should fail at the door rather than open empty.
 */
class CbzParser : BookParser {

    override fun extractMetadata(file: File): BookMetadata = runCatching {
        ZipFile(file).use { zipFile ->
            val pages = zipFile.pages()
            BookMetadata(
                title = file.nameWithoutExtension,
                // A comic has no text to count, and reporting a length in characters would put it
                // into the reading-pace figures as a book that is never read. Its page count is
                // what the reader shows for it instead.
                totalChars = 0,
                coverBytes = pages.firstOrNull()?.let { entry ->
                    runCatching { zipFile.getInputStream(entry).use { it.readBytes() } }.getOrNull()
                }
            )
        }
    }.getOrElse { BookMetadata(title = file.nameWithoutExtension) }

    override fun extractContent(file: File): String =
        ZipFile(file).use { zipFile ->
            buildString {
                for (entry in zipFile.pages()) {
                    val url = ARCHIVE_PATH +
                        entry.name.split('/').joinToString("/") { encodePathSegment(it) }
                    // How large the page is, read out of the front of it rather than by decoding
                    // it. A document that does not say cannot be laid out until every page of the
                    // comic has been fetched and decoded — several hundred photographs, all of
                    // them, before the first one can be shown.
                    val size = runCatching {
                        zipFile.getInputStream(entry).use { imagePixelSizeOf(it) }
                    }.getOrNull()
                    append("<img class=\"cbz-page\"")
                    if (size != null) append(" width=\"${size.first}\" height=\"${size.second}\"")
                    append(" src=\"").append(url).append("\"/>\n")
                }
            }
        }

    private fun ZipFile.pages(): List<ZipEntry> =
        entries().asSequence()
            .filter { !it.isDirectory && isPicture(it.name) }
            // A cover exported alongside the pages, and the metadata file some tools add, are
            // not pages of the comic.
            .filterNot { it.name.substringAfterLast('/').startsWith(".") }
            .sortedWith(Comparator { a, b -> compareNatural(a.name, b.name) })
            .toList()

    private fun isPicture(name: String): Boolean =
        when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp" -> true
            else -> false
        }
}
