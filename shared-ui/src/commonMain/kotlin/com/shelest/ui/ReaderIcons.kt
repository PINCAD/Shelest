package com.shelest.ui

/** Trusted, code-owned vector paths, never book markup. */
internal fun readerIconPath(id: String): String = when(id) {
    "back", "previous" -> "M19 12H5m7-7-7 7 7 7"
    "next" -> "M5 12h14m-7-7 7 7-7 7"
    "toc" -> "M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"
    "search" -> "M21 21l-6-6M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0"
    "note", "notes", "bookmark" -> "M6 3h12v18l-6-4-6 4z"
    "dictionary" -> "M4 5h12M10 3v2M6 5c0 7 7 10 7 10M14 5c0 7-9 11-9 11M14 21l4-9 4 9M15.5 18h5"
    "tts", "ambient" -> "M3 14v-3a9 9 0 0 1 18 0v3M3 12h4v8H3zM17 12h4v8h-4z"
    "autoScroll" -> "M7 4v16m-4-4 4 4 4-4M17 4v16m-4-4 4 4 4-4"
    "settings" -> "M3 19 9 5l6 14M5 15h8M17 12c6-2 6 7 0 7-3 0-3-4 0-4h4"
    else -> "M5 6h14M5 12h14M5 18h14"
}
