package com.lexiread.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A book the reader likes but does not own — typed in by hand on the discovery screen.
 *
 * The taste profile used to be built only from the shelf, which quietly limited it to books
 * already imported into the app. Most of what a person has read is not on their shelf, and
 * the few files that are there are a poor description of what they actually enjoy.
 *
 * Nothing here points at a file: there is no file. [author] may be blank — the reader
 * remembers «Пикник на обочине» far more reliably than they remember who wrote it, and
 * demanding both would only get one of them invented.
 */
@Entity(
    tableName = "liked_titles",
    // Typing the same book twice is a slip, not an intention. The insert ignores conflicts,
    // so the second attempt is simply absorbed instead of doubling the entry's weight.
    indices = [Index(value = ["title", "author"], unique = true)]
)
data class LikedTitle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val addedAt: Long = System.currentTimeMillis()
)
