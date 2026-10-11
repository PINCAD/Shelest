package com.shelest.shared.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MobiDocumentDecoderTest {
    @Test
    fun `reads uncompressed text and exth metadata`() {
        val document = MobiDocumentDecoder.decode(makeMobi("Текст книги".encodeToByteArray(), compression = 1))
        assertEquals("Пример книги", document.title)
        assertEquals("Иван Автор", document.author)
        assertEquals("Текст книги", document.html)
    }

    @Test
    fun `decompresses PalmDOC records`() {
        // Literal "hello ", a distance-6/length-5 back-reference, then "!".
        val compressed = byteArrayOf(6, 104, 101, 108, 108, 111, 32, 0x80.toByte(), 50, 33)
        val document = MobiDocumentDecoder.decode(makeMobi(compressed, compression = 2, expectedLength = 12))
        assertEquals("hello hello!", document.html)
    }

    @Test
    fun `explains that drm protected books cannot be opened`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            MobiDocumentDecoder.decode(makeMobi("secret".encodeToByteArray(), encryption = 1))
        }
        kotlin.test.assertTrue(failure.message.orEmpty().contains("DRM"))
    }

    @Test
    fun `explains that azw3 is not the supported palmdoc variant`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            MobiDocumentDecoder.decode(makeMobi("text".encodeToByteArray(), version = 8))
        }
        kotlin.test.assertTrue(failure.message.orEmpty().contains("AZW3"))
    }

    @Test
    fun `decodes legacy cyrillic windows encoding`() {
        val document = MobiDocumentDecoder.decode(makeMobi(byteArrayOf(0xCF.toByte(), 0xF0.toByte()), encoding = 1251))
        assertEquals("Пр", document.html)
    }

    private fun makeMobi(
        text: ByteArray,
        compression: Int = 1,
        expectedLength: Int = text.size,
        encryption: Int = 0,
        encoding: Int = 65001,
        version: Int = 6
    ): ByteArray {
        val title = "Пример книги".encodeToByteArray()
        val author = "Иван Автор".encodeToByteArray()
        val exth = ByteArray(12 + 8 + title.size + 8 + author.size)
        "EXTH".encodeToByteArray().copyInto(exth)
        setU32(exth, 4, exth.size)
        setU32(exth, 8, 2)
        setU32(exth, 12, 99); setU32(exth, 16, title.size + 8); title.copyInto(exth, 20)
        val authorRecord = 20 + title.size
        setU32(exth, authorRecord, 100)
        setU32(exth, authorRecord + 4, author.size + 8)
        author.copyInto(exth, authorRecord + 8)
        val record0 = ByteArray(16 + 232 + exth.size)
        setU16(record0, 0, compression)
        setU32(record0, 4, expectedLength)
        setU16(record0, 8, 1)
        setU16(record0, 12, encryption)
        "MOBI".encodeToByteArray().copyInto(record0, 16)
        setU32(record0, 20, 232)
        setU32(record0, 28, encoding)
        setU32(record0, 36, version)
        setU16(record0, 192, 1)
        exth.copyInto(record0, 16 + 232)

        val recordTableEnd = 94
        val textOffset = recordTableEnd + record0.size
        val result = ByteArray(textOffset + text.size)
        setU16(result, 76, 2)
        setU32(result, 78, recordTableEnd)
        setU32(result, 86, textOffset)
        record0.copyInto(result, recordTableEnd)
        text.copyInto(result, textOffset)
        return result
    }

    private fun setU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun setU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
