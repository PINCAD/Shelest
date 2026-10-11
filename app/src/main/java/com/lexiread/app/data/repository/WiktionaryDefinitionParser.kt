package com.lexiread.app.data.repository

/** Extracts actual meanings, not morphology, quotations or other-language sections. */
internal object WiktionaryDefinitionParser {
    private val heading = Regex("^(=+)\\s*(.*?)\\s*\\1$")

    fun parse(extract: String, language: String): String? {
        if (extract.isBlank()) return null
        var inLanguage = false
        var inMeanings = false
        val meanings = mutableListOf<String>()
        for (rawLine in extract.lineSequence()) {
            val line = rawLine.trim()
            val match = heading.matchEntire(line)
            if (match != null) {
                val depth = match.groupValues[1].length
                val name = match.groupValues[2]
                if (depth == 1) {
                    if (inLanguage) break
                    inLanguage = name.equals(language, ignoreCase = true)
                    inMeanings = false
                } else if (inLanguage && language == "Русский") {
                    inMeanings = name.equals("Значение", ignoreCase = true) ||
                        name.equals("Значения", ignoreCase = true)
                } else if (inLanguage && depth == 3) {
                    inMeanings = name in setOf("Noun", "Verb", "Adjective", "Adverb", "Pronoun")
                }
                continue
            }
            if (!inLanguage || !inMeanings || line.isBlank()) continue
            val meaning = line.substringBefore('◆').trim()
                .replace(Regex("^\\d+[.)]\\s*"), "")
                .trim()
            if (meaning.length >= 3 && !meaning.startsWith("Отсутствует пример")) {
                meanings += meaning.take(400)
                if (meanings.size == 4) break
            }
        }
        return meanings.distinct().takeIf { it.isNotEmpty() }
            ?.mapIndexed { index, meaning -> "${index + 1}. $meaning" }
            ?.joinToString("\n\n")
    }
}
