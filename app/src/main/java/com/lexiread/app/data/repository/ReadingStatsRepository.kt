package com.lexiread.app.data.repository

import android.content.Context
import com.lexiread.app.data.db.ReadingDayDao
import com.lexiread.app.data.model.ReadingDay
import com.lexiread.app.domain.goal.ReadingGoal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ReadingStats(
    val todaySeconds: Int = 0,
    val weekSeconds: Int = 0,
    val streakDays: Int = 0,
    val totalDays: Int = 0,
    /** The longest run of consecutive days in everything still on record. */
    val bestStreakDays: Int = 0,
    val totalSeconds: Int = 0,
    /**
     * How fast this reader actually gets through a book, in characters a minute.
     *
     * Falls back to [ReadingStatsRepository.ASSUMED_CHARS_PER_MINUTE] until enough has been
     * measured to say anything — see [paceMeasured], which is what the UI should ask before
     * presenting this as a fact about the reader rather than a working assumption.
     */
    val charsPerMinute: Int = ReadingStatsRepository.ASSUMED_CHARS_PER_MINUTE,
    val paceMeasured: Boolean = false,
    /** Every day still on record, newest first. For the calendar on the statistics screen. */
    val days: List<ReadingDay> = emptyList()
)

/**
 * Tracks how long the user actually spends reading, aggregated per calendar day.
 *
 * Time is accumulated in coarse chunks by the reader (see ReaderViewModel) rather
 * than ticked every second, so an interrupted session — app killed, battery dead —
 * loses at most one chunk instead of the whole sitting.
 *
 * The same chunks carry how far the reader got, which is what turns "you have read for 40
 * minutes" into "this book will take you another six hours" without asking anybody to time
 * themselves. [context] is here for that second job and for the goal: crossing the day's target
 * is something that happens inside this class, minutes after the screen that set the target has
 * been forgotten, and it is the only place that can notice.
 */
class ReadingStatsRepository(
    private val context: Context,
    private val dao: ReadingDayDao
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    val stats: Flow<ReadingStats> = dao.getRecent().map { days -> days.toStats() }

    /**
     * The same figures, read once.
     *
     * For the home-screen widget, which is redrawn from a broadcast rather than from a screen:
     * there is nothing there with a lifetime long enough to hold a subscription open, and the
     * streak has to be counted the same way it is counted in the app or the two would disagree
     * about the number in front of the reader.
     */
    suspend fun currentStats(): ReadingStats = dao.getRecentOnce().toStats()

    /**
     * Adds a chunk of reading to today, and congratulates the reader if that finished the goal.
     *
     * [chars] is how far the book moved during those seconds, already cleaned of jumps by the
     * caller, and may legitimately be zero — a minute spent looking up a word or listening to a
     * page being read aloud is still a minute of reading, it just measures nothing about pace.
     */
    suspend fun addSeconds(seconds: Int, chars: Int = 0) {
        if (seconds <= 0) return
        val today    = todayKey()
        val existing = dao.getDay(today)
        val before   = existing?.seconds ?: 0
        val after    = before + seconds

        dao.upsert(
            ReadingDay(
                date    = today,
                seconds = after,
                chars   = (existing?.chars ?: 0) + chars.coerceAtLeast(0)
            )
        )

        checkGoal(today, before, after)
    }

    /**
     * Fires the congratulation on the minute the day's target is passed, and only then.
     *
     * The crossing test rather than a plain "is the total past the goal" is what keeps this to
     * once: totals only grow within a day, so exactly one chunk can straddle the line. The
     * claim in the preferences covers the other way in — a goal lowered in the evening, which
     * moves the line back under a total that has already passed it.
     */
    private suspend fun checkGoal(today: String, beforeSeconds: Int, afterSeconds: Int) {
        val goalMinutes = ReadingGoal.minutes(context)
        if (goalMinutes <= ReadingGoal.OFF) return

        val target = goalMinutes * 60
        if (beforeSeconds >= target || afterSeconds < target) return
        if (!ReadingGoal.claimCelebration(context, today)) return

        ReadingGoal.celebrate(
            context,
            minutes    = goalMinutes,
            streakDays = dao.getRecentOnce().toStats().streakDays
        )
    }

    private fun todayKey(): String = dateFormat.format(Calendar.getInstance().time)

    private fun dayKeyOffset(daysBack: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysBack)
        return dateFormat.format(cal.time)
    }

    private fun List<ReadingDay>.toStats(): ReadingStats {
        val byDate = associateBy { it.date }
        val today = dayKeyOffset(0)

        val weekKeys = (0..6).map { dayKeyOffset(it) }.toSet()
        val weekSeconds = filter { it.date in weekKeys }.sumOf { it.seconds }

        // A streak survives "haven't read *yet* today" — it only breaks once a full
        // day has passed with nothing logged, so opening the app in the morning
        // doesn't show yesterday's streak as already lost.
        var streak = 0
        var cursor = if (byDate[today] != null) 0 else 1
        while (true) {
            val day = byDate[dayKeyOffset(cursor)]
            if (day == null || day.seconds <= 0) break
            streak++
            cursor++
        }

        val (pace, measured) = pace()

        return ReadingStats(
            todaySeconds   = byDate[today]?.seconds ?: 0,
            weekSeconds    = weekSeconds,
            streakDays     = streak,
            totalDays      = count { it.seconds > 0 },
            bestStreakDays = maxOf(streak, bestStreak()),
            totalSeconds   = sumOf { it.seconds },
            charsPerMinute = pace,
            paceMeasured   = measured,
            days           = this
        )
    }

    /**
     * The longest unbroken run of days on record.
     *
     * Walked over the dates themselves rather than over the list's order: the rows are only the
     * days that were read on, so two adjacent rows are not necessarily two adjacent days, and
     * counting rows would turn a year of reading once a month into a streak of twelve.
     */
    private fun List<ReadingDay>.bestStreak(): Int {
        val dates = filter { it.seconds > 0 }.map { it.date }.sorted()
        if (dates.isEmpty()) return 0

        var best = 1
        var run = 1
        for (i in 1 until dates.size) {
            run = if (isNextDay(dates[i - 1], dates[i])) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    private fun isNextDay(earlier: String, later: String): Boolean {
        val parsed = try {
            dateFormat.parse(earlier) ?: return false
        } catch (e: Exception) {
            return false
        }
        val cal = Calendar.getInstance().apply {
            time = parsed
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return dateFormat.format(cal.time) == later
    }

    /**
     * The reader's own pace, and whether it is theirs at all.
     *
     * Measured over the recent window rather than over everything: reading speed drifts with
     * what is being read, and a pace averaged with last spring's textbook is nobody's pace. The
     * window is also what makes the figure recover — a fortnight of dense non-fiction stops
     * dragging the estimate down a fortnight after it ends.
     *
     * Seconds here are *all* the seconds of those days, not only the minutes that moved the
     * book. That is deliberate: what the number is used for is "how long will the rest of this
     * take me", and the answer has to include the minutes spent looking up a word, going back a
     * page, and staring out of the window. A pace measured only over the productive minutes
     * would be higher, truer as physiology, and useless as a forecast.
     */
    private fun List<ReadingDay>.pace(): Pair<Int, Boolean> {
        val window  = take(PACE_WINDOW_DAYS)
        val chars   = window.sumOf { it.chars.toLong() }
        val seconds = window.filter { it.chars > 0 }.sumOf { it.seconds.toLong() }

        if (chars < PACE_MIN_CHARS || seconds < PACE_MIN_SECONDS) {
            return ASSUMED_CHARS_PER_MINUTE to false
        }
        val measured = (chars * 60 / seconds).toInt()
        // Still bounded. A reader who spent a week on picture books can produce arithmetic that
        // says four hundred characters a minute, and an estimate built on it would promise them
        // a month for a novel.
        return measured.coerceIn(PACE_FLOOR, PACE_CEILING) to true
    }

    companion object {
        /**
         * The pace assumed before there is anything measured: roughly 180 words a minute of
         * Russian prose, which is a middling adult reading speed and nobody in particular.
         */
        const val ASSUMED_CHARS_PER_MINUTE = 1000

        /** How far back a pace is measured over. Four weeks of habit, not a year of history. */
        private const val PACE_WINDOW_DAYS = 28

        /** Roughly a short novel's worth of measured text before the figure is anyone's. */
        private const val PACE_MIN_CHARS = 60_000L

        /** …spread over at least this long, so one very fast afternoon cannot set it. */
        private const val PACE_MIN_SECONDS = 3_600L

        private const val PACE_FLOOR   = 300
        private const val PACE_CEILING = 4_000
    }
}
