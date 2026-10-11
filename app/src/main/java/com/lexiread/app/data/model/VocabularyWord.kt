package com.lexiread.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A word the reader looked up and chose to keep.
 *
 * Deliberately *not* tied to [Book] by a foreign key: the whole point of the
 * collection is that it outlives the book it came from, so deleting a book must
 * not take the vocabulary with it. The originating title is kept as a plain
 * string snapshot instead, purely for context in the list.
 */
@Entity(
    tableName = "vocabulary",
    indices = [Index(value = ["word"], unique = true)]
)
data class VocabularyWord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val word: String,
    val definition: String,
    val sourceBookTitle: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val isLearned: Boolean = false
)
