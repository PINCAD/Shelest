package com.lexiread.app.domain.parser

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Books built by hand for the tests.
 *
 * Every format read by this app is either a text file or a zip of them, so a test needs no
 * fixtures on disk and no sample books with unclear licences: the file under test is written a few
 * lines above the assertion that reads it, which also means each test states exactly which shape of
 * book it is about.
 *
 * These run on the JVM rather than on a device. The parsers were made to make that possible —
 * nothing in the package touches an Android class any more, except the PDF one, which draws pages
 * and is left to the device it draws on.
 */

/** Writes [entries] into [file] as a zip, in the order given. */
internal fun zipInto(file: File, entries: List<Pair<String, ByteArray>>): File {
    ZipOutputStream(file.outputStream().buffered()).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return file
}

internal fun zipInto(file: File, vararg entries: Pair<String, String>): File =
    zipInto(file, entries.map { (name, text) -> name to text.toByteArray(Charsets.UTF_8) })

/** A minimal EPUB: [opf] as the package document under `OEBPS/`, plus whatever else is given. */
internal fun epubInto(
    file: File,
    opf: String,
    entries: List<Pair<String, ByteArray>>
): File {
    val container = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="OEBPS/content.opf"
            media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()
    val all = listOf(
        "mimetype" to "application/epub+zip".toByteArray(),
        "META-INF/container.xml" to container.toByteArray(Charsets.UTF_8),
        "OEBPS/content.opf" to opf.toByteArray(Charsets.UTF_8)
    ) + entries
    return zipInto(file, all)
}

internal fun page(title: String, body: String): String = """
    <?xml version="1.0" encoding="utf-8"?>
    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
    <body>$body</body></html>
""".trimIndent()

/** The three bytes that make a file a PNG as far as anything here is concerned. */
internal fun fakePng(): ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

/** A PNG that also states a size — the header only, which is all anything here reads. */
internal fun pngOf(width: Int, height: Int): ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        byteArrayOf(0, 0, 0, 13) + "IHDR".toByteArray(Charsets.US_ASCII) +
        beBytes(width) + beBytes(height) + ByteArray(5)

/** A GIF, likewise: the size sits in the header, little-endian. */
internal fun gifOf(width: Int, height: Int): ByteArray =
    "GIF89a".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(
            (width and 0xFF).toByte(), ((width shr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte()
        ) + ByteArray(4)

/**
 * A JPEG, which says nothing at the front: the size is in a frame header somewhere after an
 * arbitrary run of other segments. [padding] stands in for that run — an EXIF block, usually.
 */
internal fun jpegOf(width: Int, height: Int, padding: Int = 0): ByteArray {
    val start = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    val filler = if (padding <= 0) ByteArray(0) else {
        val length = padding + 2
        byteArrayOf(0xFF.toByte(), 0xE1.toByte(),
            ((length shr 8) and 0xFF).toByte(), (length and 0xFF).toByte()) + ByteArray(padding)
    }
    // SOF0: length, one byte of precision, the two dimensions, then one component.
    val frame = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 11, 8) +
        byteArrayOf(((height shr 8) and 0xFF).toByte(), (height and 0xFF).toByte()) +
        byteArrayOf(((width shr 8) and 0xFF).toByte(), (width and 0xFF).toByte()) +
        ByteArray(4)
    return start + filler + frame
}

private fun beBytes(value: Int): ByteArray = byteArrayOf(
    ((value shr 24) and 0xFF).toByte(), ((value shr 16) and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte()
)
