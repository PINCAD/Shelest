package com.lexiread.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AnnotationType { HIGHLIGHT, NOTE, DRAWING }

/**
 * A user-made mark on a book: a text highlight/note (TXT/EPUB/FB2, anchored by
 * searching for [selectedText] using [contextBefore]/[contextAfter] to
 * disambiguate repeats — robust to font/pagination changes, unlike a raw page
 * index) or a position-pinned note/drawing stroke on a PDF page (PDF has no
 * text layer, so [pageIndex]/[normX]/[normY] anchor it instead).
 */
@Entity(
    tableName = "annotations",
    foreignKeys = [ForeignKey(
        entity        = Book::class,
        parentColumns = ["id"],
        childColumns  = ["bookId"],
        onDelete      = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class Annotation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val type: AnnotationType,
    val color: Int,
    val createdAt: Long = System.currentTimeMillis(),

    // HTML text anchor (TXT/EPUB/FB2)
    val selectedText: String? = null,
    val contextBefore: String? = null,
    val contextAfter: String? = null,
    val noteText: String? = null,

    // PDF anchor — pageIndex is stable, normX/normY are 0..1 fractions of page size
    val pageIndex: Int? = null,
    val normX: Float? = null,
    val normY: Float? = null,
    val pathData: String? = null // JSON array of normalized {x,y} points for DRAWING
)
