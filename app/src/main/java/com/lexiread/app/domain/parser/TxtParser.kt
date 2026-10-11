package com.lexiread.app.domain.parser

import java.io.File
import java.nio.charset.Charset

class TxtParser : BookParser {

    override fun extractMetadata(file: File): BookMetadata {
        val charset = detectCharset(file)
        // Characters, not bytes. In UTF-8 a Cyrillic letter costs two of them, so the file's size
        // stated a Russian book as twice its real length — and everything downstream that turns a
        // character count into "about two hours left" was doubling it.
        val text      = runCatching { file.readText(charset) }.getOrDefault("")
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return BookMetadata(
            title      = firstLine.take(120).ifBlank { file.nameWithoutExtension },
            totalChars = text.length
        )
    }

    override fun extractContent(file: File): String {
        val charset = detectCharset(file)
        // Line endings are normalised before anything looks for a blank line. A .txt written
        // on Windows separates paragraphs with "\r\n\r\n", which the blank-line pattern below
        // does not match at all — so every such book arrived as one enormous paragraph with a
        // line break after every line, and no paragraph indents anywhere in it.
        val text = file.readText(charset).replace("\r\n", "\n").replace('\r', '\n')
        // Convert plain text to minimal HTML preserving paragraphs
        return buildString {
            text.split(Regex("\n{2,}")).forEach { paragraph ->
                val trimmed = paragraph.trim()
                if (trimmed.isNotEmpty()) {
                    append("<p>")
                    append(trimmed.escapeHtml().replace("\n", "<br/>"))
                    append("</p>\n")
                }
            }
        }
    }

    private fun detectCharset(file: File): Charset {
        val header = readHead(file, 4)
        // BOM checks
        if (header.size >= 3 &&
            header[0] == 0xEF.toByte() &&
            header[1] == 0xBB.toByte() &&
            header[2] == 0xBF.toByte()
        ) return Charsets.UTF_8
        if (header.size >= 2 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xFE.toByte()
        ) return Charsets.UTF_16LE
        // Heuristic: try UTF-8, fall back to Windows-1251 (common in Russian e-books)
        return try {
            val sample = readHead(file, 4096)
            val decoded = String(sample.dropTruncatedUtf8Tail(), Charsets.UTF_8)
            if (decoded.contains('\uFFFD')) Charset.forName("Windows-1251")
            else Charsets.UTF_8
        } catch (_: Exception) {
            Charset.forName("Windows-1251")
        }
    }

    /**
     * The sample without whatever multi-byte character the 4096-byte cut landed inside.
     *
     * Cyrillic costs two bytes in UTF-8, so a fixed-size sample of a Russian book ends
     * mid-character about half the time. The decoder then produces a replacement character
     * for the half it was given, the test above reads that as "not UTF-8", and a perfectly
     * good UTF-8 book was rendered as Windows-1251 mojibake from end to end \u2014 on a coin
     * flip. A truncated tail is at most three bytes and always ends in continuation bytes,
     * so it can be identified and dropped before the decoder ever sees it.
     */
    private fun ByteArray.dropTruncatedUtf8Tail(): ByteArray {
        var end = size
        // Walk back over continuation bytes (10xxxxxx), at most the three a sequence can have.
        var walked = 0
        while (end > 0 && walked < 3 && (this[end - 1].toInt() and 0xC0) == 0x80) {
            end--
            walked++
        }
        if (end == 0) return this
        val lead = this[end - 1].toInt() and 0xFF
        // How many bytes the character starting at `lead` needs, and how many we actually have.
        val needed = when {
            lead < 0x80 -> 1
            lead >= 0xF0 -> 4
            lead >= 0xE0 -> 3
            lead >= 0xC0 -> 2
            else -> return this   // a stray continuation byte: not our problem to repair
        }
        // Complete character: the sample ends cleanly, keep everything.
        if (walked + 1 >= needed) return this
        return copyOfRange(0, end - 1)
    }
}
