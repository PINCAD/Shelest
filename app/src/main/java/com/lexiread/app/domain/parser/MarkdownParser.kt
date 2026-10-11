package com.lexiread.app.domain.parser

import java.io.File
import java.nio.charset.Charset

/**
 * Markdown: .md, .markdown.
 *
 * A deliberately small subset — headings, quotes, lists, code, emphasis — because what arrives in
 * this app as Markdown is prose. Tables, footnote syntax and reference links are left as the text
 * they are written as rather than half-rendered, which reads worse than not rendering them at all.
 */
class MarkdownParser : BookParser {

    override fun extractMetadata(file: File): BookMetadata {
        val text = read(file)
        val heading = text.lineSequence()
            .firstOrNull { it.trimStart().startsWith("# ") }
            ?.trimStart()?.removePrefix("# ")?.trim()
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return BookMetadata(
            title      = (heading ?: firstLine).take(120).ifBlank { file.nameWithoutExtension },
            totalChars = text.length
        )
    }

    override fun extractContent(file: File): String = render(read(file))

    private fun read(file: File): String =
        // Markdown is UTF-8 by convention and by tooling; a file that isn't decodes to
        // replacement characters, which is the one case worth falling back for.
        runCatching {
            val text = file.readText(Charsets.UTF_8)
            if (text.contains('�')) file.readText(Charset.forName("Windows-1251")) else text
        }.getOrDefault("")

    private fun render(source: String): String {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder()

        var i = 0
        // Which list is open, so consecutive items become one list rather than one list each.
        var listTag: String? = null

        fun closeList() {
            listTag?.let { out.append("</").append(it).append(">\n") }
            listTag = null
        }

        fun startList(tag: String) {
            if (listTag == tag) return
            closeList()
            out.append("<").append(tag).append(">\n")
            listTag = tag
        }

        while (i < lines.size) {
            val raw  = lines[i]
            val line = raw.trim()

            // Fenced code: taken verbatim to the closing fence, so nothing inside it is read as
            // Markdown — which is the whole point of fencing it.
            if (line.startsWith("```") || line.startsWith("~~~")) {
                val fence = line.take(3)
                closeList()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++   // the closing fence
                out.append("<pre><code>").append(code.toString().escapeHtml()).append("</code></pre>\n")
                continue
            }

            if (line.isEmpty()) {
                closeList()
                i++
                continue
            }

            // A rule, in any of its three spellings.
            if (line.length >= 3 && line.all { it == '-' || it == '*' || it == '_' }) {
                closeList()
                out.append("<hr/>\n")
                i++
                continue
            }

            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                closeList()
                val level = heading.groupValues[1].length.coerceAtMost(6)
                out.append("<h").append(level).append('>')
                    .append(inline(heading.groupValues[2]))
                    .append("</h").append(level).append(">\n")
                i++
                continue
            }

            if (line.startsWith("> ")) {
                closeList()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.append(lines[i].trim().removePrefix(">").trim()).append(' ')
                    i++
                }
                out.append("<blockquote><p>").append(inline(quote.toString().trim())).append("</p></blockquote>\n")
                continue
            }

            val bullet = BULLET.matchEntire(line)
            if (bullet != null) {
                startList("ul")
                out.append("<li>").append(inline(bullet.groupValues[1])).append("</li>\n")
                i++
                continue
            }

            val numbered = NUMBERED.matchEntire(line)
            if (numbered != null) {
                startList("ol")
                out.append("<li>").append(inline(numbered.groupValues[1])).append("</li>\n")
                i++
                continue
            }

            // A paragraph runs to the next blank line; single newlines inside it are wrapping,
            // not breaks, which is what Markdown means by them.
            closeList()
            val para = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && !isBlockStart(lines[i].trim())) {
                if (para.isNotEmpty()) para.append(' ')
                para.append(lines[i].trim())
                i++
            }
            // Nothing was taken, which can only mean this line starts a block the branches above
            // did not claim. Stepping over it is what keeps the loop a loop.
            if (para.isEmpty()) { i++; continue }
            out.append("<p>").append(inline(para.toString())).append("</p>\n")
        }
        closeList()
        return out.toString()
    }

    /** Whether a line inside a paragraph ends it by starting something else. */
    private fun isBlockStart(line: String): Boolean =
        HEADING.matches(line) || BULLET.matches(line) || NUMBERED.matches(line) ||
            line.startsWith("> ") || line.startsWith("```") || line.startsWith("~~~")

    /**
     * The span-level marks, applied to text that has already been escaped — so a `<` the author
     * typed stays a `<` and cannot open a tag, while the tags built here are the only markup in
     * the result.
     */
    private fun inline(text: String): String {
        var s = text.escapeHtml()
        // Pictures and links both keep their words and lose their address: the file was imported
        // on its own, so a relative picture has nothing to point at, and a link inside the reader
        // would navigate the whole book away.
        s = IMAGE.replace(s) { it.groupValues[1] }
        s = LINK.replace(s) { it.groupValues[1] }
        s = CODE.replace(s) { "<code>" + it.groupValues[1] + "</code>" }
        s = BOLD.replace(s) { "<strong>" + it.groupValues[1] + "</strong>" }
        s = ITALIC.replace(s) { "<em>" + it.groupValues[1] + "</em>" }
        return s
    }

    private companion object {
        val HEADING  = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
        val BULLET   = Regex("""^[-*+]\s+(.*)$""")
        val NUMBERED = Regex("""^\d+[.)]\s+(.*)$""")
        val IMAGE    = Regex("""!\[([^\]]*)]\([^)]*\)""")
        val LINK     = Regex("""\[([^\]]*)]\([^)]*\)""")
        val CODE     = Regex("""`([^`]+)`""")
        val BOLD     = Regex("""(?<![*_])(?:\*\*|__)(?=\S)(.+?)(?<=\S)(?:\*\*|__)""")
        val ITALIC   = Regex("""(?<![*_\w])[*_](?=\S)([^*_]+?)(?<=\S)[*_](?![*_\w])""")
    }
}
