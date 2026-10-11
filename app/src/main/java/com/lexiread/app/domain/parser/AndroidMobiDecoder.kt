package com.lexiread.app.domain.parser

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Android implementation of the shared MOBI reader, kept in app to avoid variant metadata lag. */
internal object AndroidMobiDecoder {
    private const val LIMIT = 64 * 1024 * 1024
    data class Document(val title: String, val author: String, val html: String)

    fun decode(data: ByteArray): Document {
        require(data.size >= 94) { "Файл MOBI повреждён или имеет неизвестный формат" }
        val recordCount = u16(data, 76)
        val tableEnd = 78L + recordCount * 8L
        require(recordCount in 2..100_000 && tableEnd <= data.size) { "Таблица записей MOBI повреждена" }
        val offsets = (0 until recordCount).map { i ->
            u32(data, 78 + i * 8).also { require(it >= tableEnd && it < data.size.toLong()) }.toInt()
        }
        require(offsets.zipWithNext().all { (a, b) -> b > a }) { "Порядок записей MOBI повреждён" }
        val header = data.copyOfRange(offsets[0], offsets.getOrElse(1) { data.size })
        require(header.size >= 248 && ascii(header, 16, 4) == "MOBI") { "Файл не содержит поддерживаемую книгу MOBI" }
        val compression = u16(header, 0)
        val expected = u32(header, 4)
        val textCount = u16(header, 8)
        require(u16(header, 12) == 0) { "Книга защищена DRM и не может быть открыта в Шелесте" }
        require(compression == 1 || compression == 2) {
            "Этот вариант сжатия MOBI пока не поддерживается. Попробуйте конвертировать книгу в EPUB"
        }
        require(u32(header, 36) != 8L) { "Формат AZW3/KF8 пока не поддерживается. Попробуйте конвертировать книгу в EPUB" }
        require(expected in 1..LIMIT.toLong() && textCount in 1..recordCount) { "Размер текста MOBI некорректен или слишком велик" }
        val mobiHeaderLength = u32(header, 20)
        require(mobiHeaderLength >= 232 && 16L + mobiHeaderLength <= header.size) { "Заголовок MOBI повреждён" }
        val firstText = u16(header, 192).takeIf { it > 0 } ?: 1
        require(firstText >= 1 && firstText.toLong() + textCount <= recordCount.toLong()) { "Текстовые записи MOBI повреждены" }

        val text = ByteBuilder(expected.toInt())
        for (recordIndex in firstText until firstText + textCount) {
            if (text.size >= expected.toInt()) break
            val source = data.copyOfRange(offsets[recordIndex], offsets.getOrElse(recordIndex + 1) { data.size })
            val remaining = expected.toInt() - text.size
            if (compression == 1) text.append(source, minOf(source.size, remaining))
            else unpackPalmDoc(source, text, remaining)
        }
        val encoding = u32(header, 28).toInt()
        val body = decodeText(text.toByteArray(), encoding).trim('\u0000', '\u0004', '\u0005', ' ', '\r', '\n')
        require(body.isNotBlank()) { "В MOBI не найден текст книги" }
        val metadata = readExth(header, 16 + mobiHeaderLength.toInt(), encoding)
        val nameOffset = u32(header, 84).toInt()
        val nameLength = u32(header, 88).toInt()
        val fullName = if (nameLength > 0 && nameOffset >= 0 && nameOffset.toLong() + nameLength <= header.size)
            decodeText(header.copyOfRange(nameOffset, nameOffset + nameLength), encoding).trim('\u0000', ' ') else ""
        return Document(metadata[503].orEmpty().ifBlank { metadata[99].orEmpty().ifBlank { fullName } }, metadata[100].orEmpty(), body)
    }

    private fun readExth(data: ByteArray, start: Int, encoding: Int): Map<Int, String> {
        if (start + 12 > data.size || ascii(data, start, 4) != "EXTH") return emptyMap()
        val length = u32(data, start + 4).toInt()
        val count = u32(data, start + 8).toInt()
        if (length < 12 || start.toLong() + length > data.size || count !in 0..4096) return emptyMap()
        val values = mutableMapOf<Int, String>()
        var at = start + 12
        repeat(count) {
            if (at + 8 > start + length) return@repeat
            val tag = u32(data, at).toInt()
            val size = u32(data, at + 4).toInt()
            if (size < 8 || at.toLong() + size > start + length) return@repeat
            if (tag == 99 || tag == 100 || tag == 503)
                values[tag] = decodeText(data.copyOfRange(at + 8, at + size), encoding).trim('\u0000', ' ')
            at += size
        }
        return values
    }

    private fun unpackPalmDoc(input: ByteArray, output: ByteBuilder, limit: Int) {
        var at = 0
        val end = output.size + limit
        while (at < input.size && output.size < end) {
            val first = input[at++].toInt() and 255
            when {
                first == 0 -> output.append(0)
                first in 1..8 -> {
                    val n = minOf(first, input.size - at, end - output.size)
                    output.append(input, at, n); at += first
                }
                first in 9..0x7f -> output.append(first)
                first <= 0xbf -> {
                    require(at < input.size) { "Сжатые данные MOBI повреждены" }
                    val second = input[at++].toInt() and 255
                    val distance = ((first and 0x3f) shl 5) or (second ushr 3)
                    val n = minOf((second and 7) + 3, end - output.size)
                    require(distance > 0 && distance <= output.size) { "Сжатые данные MOBI повреждены" }
                    repeat(n) { output.append(output.byteAt(output.size - distance).toInt() and 255) }
                }
                else -> { output.append(0x20); output.append(first xor 0x80) }
            }
        }
    }

    private fun decodeText(bytes: ByteArray, encoding: Int): String = when (encoding) {
        65001 -> bytes.toString(StandardCharsets.UTF_8)
        65002 -> bytes.toString(Charset.forName("UTF-16BE"))
        1251 -> bytes.toString(Charset.forName("windows-1251"))
        else -> bytes.toString(Charset.forName("windows-1252"))
    }
    private fun u16(bytes: ByteArray, at: Int): Int {
        require(at >= 0 && at + 2 <= bytes.size) { "Заголовок MOBI обрезан" }
        return ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
    }
    private fun u32(bytes: ByteArray, at: Int): Long {
        require(at >= 0 && at + 4 <= bytes.size) { "Заголовок MOBI обрезан" }
        return ((bytes[at].toLong() and 255) shl 24) or ((bytes[at + 1].toLong() and 255) shl 16) or
            ((bytes[at + 2].toLong() and 255) shl 8) or (bytes[at + 3].toLong() and 255)
    }
    private fun ascii(bytes: ByteArray, at: Int, length: Int): String =
        if (at < 0 || at + length > bytes.size) "" else buildString(length) {
            for (i in at until at + length) append((bytes[i].toInt() and 255).toChar())
        }

    private class ByteBuilder(capacity: Int) {
        private var data = ByteArray(capacity.coerceAtMost(LIMIT))
        var size = 0
            private set
        fun append(value: Int) { ensure(1); data[size++] = value.toByte() }
        fun append(source: ByteArray, count: Int) = append(source, 0, count)
        fun append(source: ByteArray, offset: Int, count: Int) {
            if (count <= 0) return
            ensure(count); source.copyInto(data, size, offset, offset + count); size += count
        }
        fun byteAt(index: Int): Byte { require(index in 0 until size); return data[index] }
        fun toByteArray(): ByteArray = data.copyOf(size)
        private fun ensure(count: Int) {
            require(size.toLong() + count <= LIMIT) { "Распакованный текст MOBI слишком велик" }
            if (size + count <= data.size) return
            var next = (data.size.toLong().coerceAtLeast(1) * 2).coerceAtMost(LIMIT.toLong()).toInt()
            while (next < size + count) next = (next.toLong() * 2).coerceAtMost(LIMIT.toLong()).toInt()
            data = data.copyOf(next)
        }
    }
}
