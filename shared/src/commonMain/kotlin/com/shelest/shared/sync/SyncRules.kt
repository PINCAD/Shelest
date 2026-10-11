package com.shelest.shared.sync

import com.shelest.shared.SyncPolicy

data class BookMarks(val hidden: Boolean = false, val pendingDelete: Boolean = false, val deleted: Boolean = false, val restoring: Boolean = false) {
    val blocked get() = SyncPolicy.blocksBook(hidden, pendingDelete, deleted, restoring)
}

enum class PositionAction { KEEP_LOCAL, PDF_PAGE, LOCAL_PLACE, TEXT_ANCHOR, INCOMPATIBLE }

object SyncRules {
    const val BATCH_SIZE = 100
    const val DEBOUNCE_MS = 1500L
    const val MIN_TIMESTAMP = 946684800000L
    fun progressTime(explicit: Long?, legacyOpenedAt: Long, hasRead: Boolean): Long =
        (explicit ?: if (hasRead) legacyOpenedAt else 0L).coerceAtLeast(MIN_TIMESTAMP)
    fun needsPublish(localTime: Long, sentTime: Long?, marks: BookMarks) = !marks.blocked && sentTime != localTime
    fun deletionKind(syncDeletions: Boolean) = if (syncDeletions) "pending-deletes" else "hidden"
    fun marksAfterImport() = BookMarks(restoring = true)
    fun shouldDownload(account: CloudAccount, localExists: Boolean, remoteHasFile: Boolean, marks: BookMarks) =
        account.isPremium && !localExists && remoteHasFile && !marks.blocked
    fun shouldUpload(account: CloudAccount, remoteHasFile: Boolean, marks: BookMarks) =
        account.isPremium && !remoteHasFile && !marks.blocked
    fun validFileSize(size: Long, account: CloudAccount) = size in 0..account.maxBookBytes
    fun positionAction(localTime: Long, remote: SyncBook, consumer: String, readerOpen: Boolean = false): PositionAction {
        if (readerOpen || remote.updatedAt <= localTime) return PositionAction.KEEP_LOCAL
        if (remote.fileType == "PDF") return if (remote.pageIndex != null) PositionAction.PDF_PAGE else PositionAction.INCOMPATIBLE
        val place = remote.readingPlace ?: return PositionAction.INCOMPATIBLE
        if (place.platform == consumer) {
            // Legacy EPUB CFI is still a valid web-local place and is migrated by the parser.
            if ((place.segment != null && place.page != null && (place.character != null || !place.layout.isNullOrBlank())) ||
                (consumer == "web" && !place.cfi.isNullOrBlank())) return PositionAction.LOCAL_PLACE
        }
        return if (TextAnchor.normalize(place.anchor.orEmpty()).length >= 12) PositionAction.TEXT_ANCHOR
            else PositionAction.INCOMPATIBLE
    }
    /** A layout change at the same textual place must not win a cross-device conflict. */
    fun isReadingChange(previous: ReadingPlace?, next: ReadingPlace): Boolean {
        if (previous == null) return false // Initial layout is not a reading event.
        if (previous.segment != next.segment) return true
        if (previous.layout == next.layout && previous.character != null && next.character != null &&
            previous.character != next.character) return true // Repeated text still has distinct coordinates.
        if (!previous.anchor.isNullOrBlank() && !next.anchor.isNullOrBlank())
            return TextAnchor.normalize(previous.anchor) != TextAnchor.normalize(next.anchor)
        if (previous.character != null && next.character != null)
            return previous.segment != next.segment || previous.character != next.character
        return previous.segment != next.segment || previous.page != next.page || previous.scrollY != next.scrollY
    }
}

/** Deliberately identical Unicode whitespace semantics on JVM and JS, with UTF-16 offsets. */
object TextAnchor {
    private fun whitespace(c: Char) = c in '\u0009'..'\u000d' || c == ' ' || c == '\u00a0' || c == '\u1680' ||
        c in '\u2000'..'\u200a' || c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000' || c == '\ufeff'
    fun normalize(text: String): String = text.filterNot(::whitespace)
    fun rawOffset(text: String, target: Int): Int {
        var index = 0
        text.forEachIndexed { raw, char -> if (!whitespace(char) && index++ == target) return raw }
        return text.length
    }
    fun uniqueOffset(text: String, anchor: String): Int? {
        val needle = normalize(anchor)
        if (needle.length < 12) return null
        val normalized = normalize(text)
        val found = normalized.indexOf(needle)
        if (found < 0 || normalized.indexOf(needle, found + 1) >= 0) return null
        return rawOffset(text, found)
    }
}
