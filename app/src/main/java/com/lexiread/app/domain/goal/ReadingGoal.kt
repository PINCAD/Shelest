package com.lexiread.app.domain.goal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lexiread.app.MainActivity
import com.lexiread.app.R
import com.lexiread.app.data.prefs.PrefKeys
import com.lexiread.app.data.prefs.settingsDataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * The daily reading goal: how long the reader means to read, and what happens when they do.
 *
 * A goal is minutes, not pages or books. Pages are a property of the file rather than of the
 * reading — a page in a large-print EPUB is not a page in a PDF — and books finish on their own
 * schedule, which makes them a target you can miss for three weeks while reading every day.
 * Minutes are the one unit that means the same thing in every book and can actually be met today.
 *
 * The congratulation is a notification rather than something on a screen because meeting the goal
 * happens *while reading*, and a banner over the page at minute twenty would interrupt exactly
 * the thing it is congratulating. In the shade it waits until the book is put down.
 */
internal object ReadingGoal {

    /** No goal set. Distinct from "a goal of zero", which would be met by not reading. */
    const val OFF = 0

    /** Offered when a reader turns the goal on without saying how long. */
    const val DEFAULT_MINUTES = 20

    /** Four hours. Past this it is not a daily habit, it is a holiday. */
    const val MAX_MINUTES = 240

    /** What the picker offers. Anything can be set; these are the ones worth one tap. */
    val PRESETS = listOf(5, 10, 15, 20, 30, 45, 60, 90)

    suspend fun minutes(context: Context): Int =
        context.settingsDataStore.data.first()[PrefKeys.GOAL_MINUTES] ?: OFF

    /**
     * Marks [today] as celebrated and answers whether it had already been.
     *
     * Read and write in one `edit` block: the caller is a repository ticking from a coroutine
     * that the reader screen can start twice in quick succession, and two checks that both read
     * "not yet" before either writes would congratulate the same afternoon twice.
     */
    suspend fun claimCelebration(context: Context, today: String): Boolean {
        var alreadyDone = true
        context.settingsDataStore.edit { prefs ->
            alreadyDone = prefs[PrefKeys.GOAL_CELEBRATED] == today
            if (!alreadyDone) prefs[PrefKeys.GOAL_CELEBRATED] = today
        }
        return !alreadyDone
    }

    /**
     * The congratulation itself.
     *
     * Silently does nothing when notifications are not permitted — the permission is asked for
     * once, on Android 13 and up, for the read-aloud controls, and a reader who said no to that
     * has said no to this too. Meeting the goal is not a reason to ask again.
     */
    fun celebrate(context: Context, minutes: Int, streakDays: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(manager)

        val text = when {
            streakDays >= 100 -> "$streakDays ${days(streakDays)} подряд. В такое уже трудно поверить."
            streakDays >= 30  -> "$streakDays ${days(streakDays)} подряд. Это уже не привычка, это характер."
            streakDays >= 7   -> "$streakDays ${days(streakDays)} подряд. Неделя держится."
            streakDays >= 2   -> "$streakDays ${days(streakDays)} подряд. Не отпускайте."
            else              -> "Начало положено. Завтра будет второй день."
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_goal)
            .setContentTitle("$minutes ${minutesWord(minutes)} прочитано. Цель дня достигнута")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post. Nothing to do and nothing worth
            // saying: the reading itself was already counted.
        }
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Цель дня",
            // Default, not low: this one is meant to be seen and heard once. It happens at most
            // once a day, and a congratulation that arrives silently is not a congratulation.
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Поздравление, когда дневная цель чтения выполнена"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun days(n: Int): String {
        val mod100 = n % 100
        if (mod100 in 11..14) return "дней"
        return when (n % 10) {
            1       -> "день"
            2, 3, 4 -> "дня"
            else    -> "дней"
        }
    }

    private fun minutesWord(n: Int): String {
        val mod100 = n % 100
        if (mod100 in 11..14) return "минут"
        return when (n % 10) {
            1       -> "минута"
            2, 3, 4 -> "минуты"
            else    -> "минут"
        }
    }

    private const val CHANNEL_ID      = "shelest_goal"
    private const val NOTIFICATION_ID = 4712
}
