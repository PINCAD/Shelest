package com.lexiread.app.domain.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the notification, the lock screen and a pair of headphone buttons are allowed to ask
 * of the voice.
 *
 * Deliberately the same four things the player bar offers and nothing more: the controls
 * outside the app are a remote for the ones inside it, not a second, subtly different player.
 */
interface TtsCommands {
    fun play()
    fun pause()
    fun next()
    fun previous()
    /** Stop reading and put the player away, as the bar's close button does. */
    fun close()
}

/** What the notification says while the voice is reading. */
data class TtsNowPlaying(
    val bookTitle: String = "",
    val sentence: String  = "",
    val isPlaying: Boolean = false
)

/**
 * The one place the reader screen and [TtsPlaybackService] can see each other.
 *
 * The voice is owned by the reader, not by the service: the engine, the chapter it was given
 * and the sentence it is on all live in [BookTextToSpeech] alongside the page they came from,
 * and moving that into a service would mean shipping a chapter across a binder and keeping two
 * ideas of "where we are" in step. The service's job is narrower — hold the process at
 * foreground priority so Android stops killing the voice when the screen goes off, and put a
 * real transport control on the lock screen. For that it needs a handle on the player and a
 * line of text to display, which is all this is.
 *
 * A singleton rather than a bound service: binding is asynchronous, and the thing being bound
 * to is a plain object in the same process, so a connection would buy nothing but a window in
 * which the play button does not work yet.
 */
object TtsPlaybackLink {

    /**
     * Null whenever no reader is on screen. The service checks it on every command rather than
     * assuming: a notification survives the Activity it belongs to, and its buttons keep being
     * pressable for the moment between the reader going away and the service noticing.
     */
    @Volatile
    var commands: TtsCommands? = null

    private val _nowPlaying = MutableStateFlow(TtsNowPlaying())
    val nowPlaying: StateFlow<TtsNowPlaying> = _nowPlaying.asStateFlow()

    fun update(bookTitle: String, sentence: String, isPlaying: Boolean) {
        _nowPlaying.value = TtsNowPlaying(bookTitle, sentence, isPlaying)
    }

    fun clear() {
        commands = null
        _nowPlaying.value = TtsNowPlaying()
    }
}
