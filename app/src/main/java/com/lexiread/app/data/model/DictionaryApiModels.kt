package com.lexiread.app.data.model

// ── Яндекс.Словарь (dictionary.yandex.net) ────────────────────────────────────

data class YandexLookupResponse(
    val def: List<YandexDef> = emptyList()
)

data class YandexDef(
    val text: String? = null,
    val pos: String? = null,
    val tr: List<YandexTranslation> = emptyList()
)

data class YandexTranslation(
    val text: String? = null,
    val pos: String? = null,
    val syn: List<YandexSynonym>? = null
)

data class YandexSynonym(
    val text: String? = null
)

// ── dictionaryapi.dev (English explanatory dictionary) ────────────────────────

data class DictionaryApiEntry(
    val word: String? = null,
    val meanings: List<DictionaryApiMeaning> = emptyList()
)

data class DictionaryApiMeaning(
    val partOfSpeech: String? = null,
    val definitions: List<DictionaryApiDefinition> = emptyList()
)

data class DictionaryApiDefinition(
    val definition: String? = null,
    val example: String? = null
)
