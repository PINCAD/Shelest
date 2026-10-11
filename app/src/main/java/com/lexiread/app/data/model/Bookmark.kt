package com.lexiread.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(
        entity        = Book::class,
        parentColumns = ["id"],
        childColumns  = ["bookId"],
        onDelete      = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["bookId", "pageIndex"], unique = true)]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val pageIndex: Int,
    val createdAt: Long = System.currentTimeMillis()
)
