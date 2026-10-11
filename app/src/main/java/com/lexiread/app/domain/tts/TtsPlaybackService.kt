package com.lexiread.app.domain.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import com.lexiread.app.MainActivity
import com.lexiread.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps read-aloud alive while the reader is not looking at it.
 *
 * Without this the voice is just an ordinary background thread in an app the user has left, and
 * the system treats it as one: the screen goes off, the process drops down the priority list,
 * and the reading stops somewhere in the middle of a chapter with nothing to say why. A
 * foreground service with a media notification is the contract for "this app is playing
 * something", and it is what the system checks before deciding an app is idle.
 *
 * The [MediaSession] alongside it is not decoration. It is what puts the transport controls on
 * the lock screen and what makes the pause button on a pair of headphones work — and, on
 * Android 14, what makes the `mediaPlayback` foreground service type honest: the system will
 * only accept that type from an app whose session is actually playing.
 *
 * The voice itself stays in the reader; see [TtsPlaybackLink] for why.
 */
class TtsPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSession(this, "Shelest").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay()             { TtsPlaybackLink.commands?.play() }
                override fun onPause()            { TtsPlaybackLink.commands?.pause() }
                override fun onSkipToNext()       { TtsPlaybackLink.commands?.next() }
                override fun onSkipToPrevious()   { TtsPlaybackLink.commands?.previous() }
                override fun onStop()             { TtsPlaybackLink.commands?.close() }
            })
            isActive = true
        }

        scope.launch {
            TtsPlaybackLink.nowPlaying.collectLatest { state ->
                if (!started) return@collectLatest
                publish(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY     -> TtsPlaybackLink.commands?.play()
            ACTION_PAUSE    -> TtsPlaybackLink.commands?.pause()
            ACTION_NEXT     -> TtsPlaybackLink.commands?.next()
            ACTION_PREVIOUS -> TtsPlaybackLink.commands?.previous()
            ACTION_CLOSE    -> {
                TtsPlaybackLink.commands?.close()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Whatever the intent was for, the service has to be in the foreground before its
        // five seconds are up, or the system kills the process with an ANR-shaped crash.
        started = true
        publish(TtsPlaybackLink.nowPlaying.value)
        return START_NOT_STICKY
    }

    private fun publish(state: TtsNowPlaying) {
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                // Position is deliberately unknown: a chapter read aloud has no duration to
                // scrub through — the engine synthesises a sentence at a time and cannot say
                // how long the rest will take. A made-up number would only draw a progress
                // bar that lies.
                .setState(
                    if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f
                )
                .build()
        )
        startForeground(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: TtsNowPlaying): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_voice)
            .setContentTitle(state.bookTitle.ifBlank { "Озвучка книги" })
            // The line being read. It is the only thing here that changes as it plays, so it
            // doubles as the sign that the voice is still going.
            .setContentText(state.sentence.ifBlank { "Чтение вслух" })
            .setContentIntent(openReaderIntent())
            .setDeleteIntent(servicePendingIntent(ACTION_CLOSE))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Not dismissable while speaking, dismissable once paused — a paused voice the
            // reader has walked away from should not need a trip into the app to get rid of.
            .setOngoing(state.isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Назад", ACTION_PREVIOUS))
            .addAction(
                if (state.isPlaying) action(android.R.drawable.ic_media_pause, "Пауза", ACTION_PAUSE)
                else                 action(android.R.drawable.ic_media_play,  "Слушать", ACTION_PLAY)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Вперёд", ACTION_NEXT))

        session?.let { s ->
            builder.setStyle(
                Notification.MediaStyle()
                    .setMediaSession(s.sessionToken)
                    // Which of the three survive the collapsed notification and the lock screen.
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            title,
            servicePendingIntent(action)
        ).build()

    private fun servicePendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, TtsPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Tapping the notification goes back to the book, not to a fresh copy of the library. */
    private fun openReaderIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Озвучка книги",
            // Low: this notification is a control panel, not news. Anything higher and every
            // sentence change would be a reason to buzz the phone.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Управление чтением вслух"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        session?.isActive = false
        session?.release()
        session = null
        started = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID     = "shelest_tts"
        private const val NOTIFICATION_ID = 4711

        private const val ACTION_PLAY     = "com.lexiread.app.tts.PLAY"
        private const val ACTION_PAUSE    = "com.lexiread.app.tts.PAUSE"
        private const val ACTION_NEXT     = "com.lexiread.app.tts.NEXT"
        private const val ACTION_PREVIOUS = "com.lexiread.app.tts.PREVIOUS"
        private const val ACTION_CLOSE    = "com.lexiread.app.tts.CLOSE"

        /**
         * Only ever called from the reader with the app on screen. Starting a foreground
         * service from the background is refused outright on Android 12 and later, which is
         * why nothing here tries to restart itself.
         */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, TtsPlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TtsPlaybackService::class.java))
        }
    }
}
