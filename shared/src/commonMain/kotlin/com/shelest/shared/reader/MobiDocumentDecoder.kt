package com.shelest.shared.reader

/** A DRM-free Mobipocket document decoded to the HTML/text carried by its Palm database. */
data class MobiDocument(
    val title: String,
    val author: String,
    val html: String
)

/**
 * Small, platform-independent reader for the common unencrypted MOBI/AZW (KF7) variants.
 * PalmDOC compression and uncompressed records are supported. DRM and Huff/CDIC are reported
 * explicitly; silently showing an empty book for either is much worse than a useful error.
 */
object MobiDocumentDecoder {
    const val MAX_TEXT_BYTES = 64 * 1024 * 1024

    fun decodeBinary(binary: String): MobiDocument {
        require(binary.length <= MAX_TEXT_BYTES) { "Файл MOBI слишком большой для обработки" }
        val bytes = ByteArray(binary.length) { binary[it].code.toByte() }
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): MobiDocument {
        require(bytes.size >= 94) { "Файл MOBI повреждён или имеет неизвестный формат" }
        val recordCount = u16(bytes, 76)
        require(recordCount in 2..100_000) { "В файле MOBI не найдены записи книги" }
        val tableEnd = 78L + recordCount.toLong() * 8L
        require(tableEnd <= bytes.size) { "Таблица записей MOBI повреждена" }

        val offsets = ArrayList<Int>(recordCount)
        for (index in 0 until recordCount) {
            val offset = u32(bytes, 78 + index * 8)
            require(offset >= tableEnd && offset < bytes.size.toLong()) { "Запись MOBI выходит за границы файла" }
            offsets += offset.toInt()
        }
        require(offsets.zipWithNext().all { (left, right) -> right > left }) {
            "Порядок записей MOBI повреждён"
        }

        val record0 = bytes.copyOfRange(offsets[0], offsets.getOrElse(1) { bytes.size })
        require(record0.size >= 32 && ascii(record0, 16, 4) == "MOBI") {
            "Файл не содержит поддерживаемую книгу MOBI"
        }
        val compression = u16(record0, 0)
        val expectedLength = u32(record0, 4)
        val textRecordCount = u16(record0, 8)
        val encryption = u16(record0, 12)
        require(encryption == 0) { "Книга защищена DRM и не может быть открыта в Шелесте" }
        require(compression == 1 || compression == 2) {
            "Этот вариант сжатия MOBI пока не поддерживается. Попробуйте конвертировать книгу в EPUB"
        }
        require(expectedLength in 1..MAX_TEXT_BYTES.toLong()) { "Размер текста MOBI некорректен или слишком велик" }
        require(textRecordCount in 1..recordCount) { "Текстовые записи MOBI повреждены" }

        val headerLength = u32(record0, 20)
        require(headerLength >= 232 && 16L + headerLength <= record0.size.toLong()) {
            "Заголовок MOBI повреждён"
        }
        require(u32(record0, 36) != 8L) {
            "Формат AZW3/KF8 пока не поддерживается. Попробуйте конвертировать книгу в EPUB"
        }
        val encoding = u32(record0, 28).toInt()
        val firstTextRecord = if (record0.size >= 194) u16(record0, 192).takeIf { it > 0 } ?: 1 else 1
        require(firstTextRecord >= 1 && firstTextRecord.toLong() + textRecordCount <= recordCount.toLong()) {
            "Диапазон текстовых записей MOBI повреждён"
        }

        val decoded = ByteArrayBuilder(expectedLength.toInt())
        for (recordIndex in firstTextRecord until firstTextRecord + textRecordCount) {
            if (decoded.size >= expectedLength.toInt()) break
            val start = offsets[recordIndex]
            val end = offsets.getOrElse(recordIndex + 1) { bytes.size }
            val remaining = expectedLength.toInt() - decoded.size
            val chunk = bytes.copyOfRange(start, end)
            if (compression == 1) decoded.append(chunk, minOf(chunk.size, remaining))
            else decompressPalmDoc(chunk, decoded, remaining)
        }
        require(decoded.size > 0) { "В MOBI не найден текст книги" }
        val markup = decodeText(decoded.toByteArray(), encoding).trim('\u0000', '\u0004', '\u0005', ' ', '\r', '\n')
        require(markup.isNotBlank()) { "В MOBI не найден текст книги" }

        val exth = exthStrings(record0, 16 + headerLength.toInt(), encoding)
        val nameOffset = u32(record0, 84).toInt()
        val nameLength = u32(record0, 88).toInt()
        val fullName = if (nameLength > 0 && nameOffset >= 0 && nameOffset.toLong() + nameLength <= record0.size) {
            decodeText(record0.copyOfRange(nameOffset, nameOffset + nameLength), encoding).trim('\u0000', ' ')
        } else ""
        return MobiDocument(
            title = exth[503]?.firstOrNull()?.ifBlank { null }
                ?: exth[99]?.firstOrNull()?.ifBlank { null }
                ?: fullName,
            author = exth[100]?.firstOrNull().orEmpty(),
            html = markup
        )
    }

    private fun exthStrings(record0: ByteArray, start: Int, encoding: Int): Map<Int, List<String>> {
        if (start < 0 || start + 12 > record0.size || ascii(record0, start, 4) != "EXTH") return emptyMap()
        val length = u32(record0, start + 4).toInt()
        val count = u32(record0, start + 8).toInt()
        if (length < 12 || start.toLong() + length > record0.size || count !in 0..4096) return emptyMap()
        val values = HashMap<Int, MutableList<String>>()
        var cursor = start + 12
        val end = start + length
        repeat(count) {
            if (cursor + 8 > end) return@repeat
            val tag = u32(record0, cursor).toInt()
            val recordLength = u32(record0, cursor + 4).toInt()
            if (recordLength < 8 || cursor.toLong() + recordLength > end) return@repeat
            if (tag == 99 || tag == 100 || tag == 503) {
                val text = decodeText(record0.copyOfRange(cursor + 8, cursor + recordLength), encoding)
                    .trim('\u0000', ' ')
                if (text.isNotBlank()) values.getOrPut(tag) { mutableListOf() }.add(text)
            }
            cursor += recordLength
        }
        return values
    }

    private fun decompressPalmDoc(input: ByteArray, output: ByteArrayBuilder, limit: Int) {
        var index = 0
        val endSize = output.size + limit
        while (index < input.size && output.size < endSize) {
            val first = input[index++].toInt() and 0xff
            when {
                first == 0 -> output.append(0)
                first in 1..8 -> {
                    val count = minOf(first, input.size - index, endSize - output.size)
                    output.append(input, index, count)
                    index += first
                }
                first in 9..0x7f -> output.append(first)
                first <= 0xbf -> {
                    if (index >= input.size) break
                    val second = input[index++].toInt() and 0xff
                    val distance = ((first and 0x3f) shl 5) or (second ushr 3)
                    val count = minOf((second and 0x07) + 3, endSize - output.size)
                    require(distance > 0 && distance <= output.size) { "Сжатые данные MOBI повреждены" }
                    repeat(count) { output.append(output.byteAt(output.size - distance).toInt() and 0xff) }
                }
                else -> {
                    output.append(0x20)
                    output.append(first xor 0x80)
                }
            }
        }
    }

    private fun decodeText(bytes: ByteArray, encoding: Int): String = when (encoding) {
        65001 -> bytes.decodeToString(throwOnInvalidSequence = false)
        65002 -> buildString(bytes.size / 2) {
            var index = 0
            while (index + 1 < bytes.size) {
                append((((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)).toChar())
                index += 2
            }
        }
        1251 -> buildString(bytes.size) {
            for (byte in bytes) append(cp1251(byte.toInt() and 0xff))
        }
        else -> buildString(bytes.size) {
            for (byte in bytes) append(cp1252(byte.toInt() and 0xff))
        }
    }

    private fun cp1251(value: Int): Char {
        val special = "ЂЃ‚ѓ„…†‡€‰Љ‹ЊЌЋЏђ‘’“”•–—\uFFFD™љ›њќћџ"
        return when {
            value in 0x80..0x9f -> special[value - 0x80]
            value in 0xa0..0xbf -> "\u00a0ЎўЈ¤Ґ¦§Ё©Є«¬\u00ad®Ї°±Ііґµ¶·ё№є»јЅѕї"[value - 0xa0]
            value in 0xc0..0xff -> (0x0410 + value - 0xc0).toChar()
            else -> value.toChar()
        }
    }

    private fun cp1252(value: Int): Char {
        val special = "€�‚ƒ„…†‡ˆ‰Š‹Œ�Ž��‘’“”•–—˜™š›œ�žŸ"
        return if (value in 0x80..0x9f) special[value - 0x80] else value.toChar()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 2 <= bytes.size) { "Заголовок MOBI обрезан" }
        return ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 4 <= bytes.size) { "Заголовок MOBI обрезан" }
        return ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (offset < 0 || offset + length > bytes.size) "" else buildString(length) {
            for (index in offset until offset + length) append((bytes[index].toInt() and 0xff).toChar())
        }

    private class ByteArrayBuilder(capacity: Int) {
        private var bytes = ByteArray(capacity.coerceAtMost(MAX_TEXT_BYTES))
        var size = 0
            private set

        fun append(value: Int) {
            ensure(1)
            bytes[size++] = value.toByte()
        }

        fun byteAt(index: Int): Byte {
            require(index in 0 until size)
            return bytes[index]
        }

        fun append(source: ByteArray, count: Int) = append(source, 0, count)

        fun append(source: ByteArray, offset: Int, count: Int) {
            if (count <= 0) return
            ensure(count)
            source.copyInto(bytes, size, offset, offset + count)
            size += count
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        private fun ensure(count: Int) {
            require(size.toLong() + count <= MAX_TEXT_BYTES.toLong()) { "Распакованный текст MOBI слишком велик" }
            if (size + count <= bytes.size) return
            var next = (bytes.size.toLong().coerceAtLeast(1) * 2).coerceAtMost(MAX_TEXT_BYTES.toLong()).toInt()
            while (next < size + count) next = (next.toLong() * 2).coerceAtMost(MAX_TEXT_BYTES.toLong()).toInt()
            bytes = bytes.copyOf(next)
        }
    }
}
