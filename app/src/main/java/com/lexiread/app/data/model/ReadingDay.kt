package com.lexiread.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per calendar day the user actually read on, keyed by local `yyyy-MM-dd`.
 *
 * Storing the date as a formatted local-time string (rather than an epoch) keeps
 * "did I read today?" and streak arithmetic trivial and timezone-stable for the
 * user's own device, which is the only place these stats are ever shown.
 */
@Entity(tableName = "reading_days")
data class ReadingDay(
    @PrimaryKey
    val date: String,
    val seconds: Int = 0,
    /**
     * Characters of the book the reader actually moved through on this day.
     *
     * Kept beside the seconds rather than derived from them, because the two together are the
     * only honest measure of a reading pace this app can take: how long the reader sat with a
     * book, and how far they got. Everything that isn't reading — a page jumped to from the
     * table of contents, a chapter re-read backwards — is discarded before it lands here, so a
     * day's figure is smaller than the distance between where the book opened and where it
     * closed, and that is correct.
     *
     * Zero on every row that existed before this column did, which is why the pace falls back
     * to an assumed one until enough has been measured. See ReadingStatsRepository.
     */
    val chars: Int = 0
)
