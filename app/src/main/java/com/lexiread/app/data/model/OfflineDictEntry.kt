package com.lexiread.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Offline dictionary entry stored in Room.
 * Pre-populated from assets/offline_dict.json at first launch.
 */
@Entity(tableName = "offline_dict")
data class OfflineDictEntry(
    @PrimaryKey
    val word: String,          // lowercase lemma
    val definition: String,    // short definition text
    val partOfSpeech: String = "" // сущ., гл., прил., нар. …
)
