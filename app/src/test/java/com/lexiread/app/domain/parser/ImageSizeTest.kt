package com.lexiread.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * How large a picture is, read out of its header.
 *
 * This is not a nicety. The reader lays the book out in columns, and a column cannot be measured
 * until everything in it has a height — so a document that does not state the size of its pictures
 * cannot show its first page until every picture in the book has been fetched and decoded. On a
 * comic that is the entire file.
 */
class ImageSizeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var counter = 0

    private fun file(bytes: ByteArray) =
        temp.newFile("pic-${counter++}").apply { writeBytes(bytes) }

    @Test
    fun `a png says so in its first chunk`() {
        assertEquals(1280 to 1920, imagePixelSize(file(pngOf(1280, 1920))))
    }

    @Test
    fun `a gif says so little-endian`() {
        assertEquals(640 to 480, imagePixelSize(file(gifOf(640, 480))))
    }

    @Test
    fun `a jpeg is walked segment by segment until the frame header`() {
        assertEquals(800 to 600, imagePixelSize(file(jpegOf(800, 600))))
    }

    @Test
    fun `a jpeg is still found behind a block of camera data`() {
        // A photograph out of a phone carries tens of kilobytes of EXIF, thumbnail included,
        // before it says anything about itself.
        assertEquals(3024 to 4032, imagePixelSize(file(jpegOf(3024, 4032, padding = 40_000))))
    }

    @Test
    fun `a file that is not a picture is not guessed at`() {
        assertNull(imagePixelSize(file("это не картинка".toByteArray())))
    }

    @Test
    fun `a header that claims nothing is treated as claiming nothing`() {
        // Eight bytes of PNG signature and zeroes after it: a real decoder would refuse it, and
        // writing width="0" into the document would collapse the picture rather than reserve it.
        assertNull(imagePixelSize(file(fakePng())))
    }

    @Test
    fun `a picture inside an archive is measured without unpacking all of it`() {
        val zip = zipInto(
            temp.newFile("pages-${counter++}.cbz"),
            listOf("001.jpg" to (jpegOf(1600, 2400) + ByteArray(400_000)))
        )
        java.util.zip.ZipFile(zip).use { archive ->
            val entry = archive.getEntry("001.jpg")
            val size = archive.getInputStream(entry).use { imagePixelSizeOf(it) }
            assertEquals(1600 to 2400, size)
        }
    }
}
