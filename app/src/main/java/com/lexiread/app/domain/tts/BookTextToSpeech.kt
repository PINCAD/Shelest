package com.lexiread.app.domain.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.lexiread.app.domain.parser.plainTextOf
import java.util.Locale

data class TtsSentence(
    val index: Int,
    val text: String
)

/**
 * Reads a chapter aloud through the system voice.
 *
 * Two things about `TextToSpeech` shape everything here. It has no notion of pausing — the
 * only stop it offers throws away what it was saying — and it has no notion of a playlist,
 * so a chapter is a chain of utterances this class keeps feeding it. Both are worked
 * around rather than exposed: [pause] remembers how far into the sentence the voice had
 * got, and [play] picks up from that word, so a pause costs a word rather than a sentence.
 *
 * The next sentence is queued while the current one is still speaking. That is not an
 * optimisation — without it the engine falls silent for as long as it takes to synthesise
 * the next line, and at the end of every sentence that gap is exactly where a listener
 * hears the machine.
 *
 * Callbacks arrive on the engine's own thread and are posted to the main thread before
 * they leave this class, so callers may touch UI state in them.
 *
 * @param onSentenceStarted index and text of the line the voice has just begun. The text
 *   comes with the index deliberately: the reader displays what is being read, and when it
 *   looked that up in its own copy of the split the two could disagree about what sentence
 *   number seven was.
 * @param onCompleted the chapter ran out, or the engine failed and playback cannot continue.
 */
class BookTextToSpeech(
    private val context: Context,
    private val onSentenceStarted: (Int, String) -> Unit,
    private val onCompleted: () -> Unit,
    private val onErrorMessage: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {

    // Declared before the engine: constructing a TextToSpeech starts the bind that ends in
    // onInit, and onInit posts through this handler. The engine binds asynchronously so the
    // ordering is not observable today, but relying on that is a footgun for no gain.
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isInitialized = false
    private var initializationError: String? = null
    private fun fail(message: String) {
        stopSpeaking()
        main.post { onErrorMessage(message) }
    }

    /**
     * The lines this chapter was cut into. Public because the reader displays the one being
     * spoken, and it must be looking at the same list the voice is reading from — the two
     * used to be split independently and could disagree.
     */
    @Volatile
    var sentences: List<TtsSentence> = emptyList()
        private set

    @Volatile
    private var currentIndex = 0
    private var speechRate = 1.0f
    @Volatile
    private var preferNetworkVoice = true

    /**
     * Set when the connection died mid-chapter and [handleError] dropped to an offline voice.
     * Kept separate from [preferNetworkVoice] on purpose: that field is the user's answer and
     * this one is a temporary override of it, so the rescue never quietly rewrites a setting
     * the user would then find flipped. It also guards the rescue against looping on an
     * engine that is broken for some other reason, and it is cleared at the start of every
     * chapter — by then the signal may well be back.
     */
    @Volatile
    private var fellBackToOffline = false

    /**
     * Characters into the current sentence that the voice has already said. Maintained from
     * `onRangeStart`, which is the only window the engine gives into its own progress, and
     * is what makes a pause resume mid-sentence instead of restarting it.
     */
    @Volatile
    private var spokenChars = 0

    /**
     * When a sentence is resumed part-way, the engine is handed only the tail of it, so the
     * offsets it reports are relative to that tail. These carry the amount to add back, for
     * the one utterance it applies to — the sentences queued behind it always start whole.
     */
    @Volatile
    private var resumeIndex = -1
    @Volatile
    private var resumeOffset = 0

    /** Set while the user means to be listening, so focus changes know what to restore. */
    @Volatile
    private var wantsToPlay = false
    private var pausedByFocusLoss = false

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // Gone for good — another app took over media. Stop and tell the UI, so the
            // play button does not sit there claiming to be playing.
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                pause()
                fail("Озвучка остановлена: другое приложение заняло аудиовыход.")
            }
            // A notification or a call. Hold the place and resume when it passes.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                if (wantsToPlay) pauseForFocus()
            }
            // Speech cannot usefully duck — a quieter voice under a notification is just a
            // voice you have to rewind — so a duck request is treated as a short pause.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (wantsToPlay) pauseForFocus()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (pausedByFocusLoss) {
                    pausedByFocusLoss = false
                    play(currentIndex)
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            initializationError = "Не удалось запустить озвучку. Проверьте установленный голосовой движок в настройках устройства."
            if (wantsToPlay) fail(initializationError!!)
            return
        }
        if (!selectVoice()) {
            initializationError = "Нет доступного русского голоса. Установите русский язык в настройках озвучки устройства."
            if (wantsToPlay) fail(initializationError!!)
            return
        }
        tts?.setSpeechRate(speechRate)
        setupUtteranceListener()
        isInitialized = true
        // The user may have pressed play before the engine finished starting up.
        if (wantsToPlay) speakFrom(currentIndex)
    }

    /**
     * Picks the best installed Russian voice.
     *
     * Google's server-side voices are the ones that sound like a person; the offline ones
     * read correctly but flatly, and no amount of work on this side changes that. So which
     * family to prefer is not a decision to make silently — [preferNetworkVoice] carries
     * the user's answer, and each side falls back to the other rather than to nothing,
     * because a device may have only one of them installed.
     *
     * Within a family the engine's own quality rating decides. Voices the engine lists but
     * has not downloaded are dropped: selecting one yields silence, not a fallback.
     */
    private fun selectVoice(): Boolean {
        val engine = tts ?: return false
        try {
            val russian = engine.voices
                ?.filter { it.locale.language == "ru" }
                ?.filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .orEmpty()

            val (network, offline) = russian.partition { it.isNetworkConnectionRequired }
            val wantNetwork = preferNetworkVoice && !fellBackToOffline
            val preferred = if (wantNetwork) network.ifEmpty { offline }
                            else offline.ifEmpty { network }
            val best = preferred.maxByOrNull { it.quality }

            if (best != null) {
                return engine.setVoice(best) == TextToSpeech.SUCCESS
            }
        } catch (_: Exception) {
            // Some third-party engines throw from getVoices(). Fall through to setLanguage,
            // which every engine supports.
        }
        val result = engine.setLanguage(Locale("ru", "RU"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            return false
        }
        return true
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                currentIndex = idx
                spokenChars = if (idx == resumeIndex) resumeOffset else 0
                val text = sentenceAt(idx).drop(spokenChars)
                main.post { onSentenceStarted(idx, text) }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val idx = utteranceId?.toIntOrNull() ?: return
                if (idx != currentIndex) return
                spokenChars = start + if (idx == resumeIndex) resumeOffset else 0
            }

            override fun onDone(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                if (!wantsToPlay) return
                // idx + 1 is already queued and speaking; keep the queue one deep.
                if (idx + 2 < sentences.size) {
                    enqueue(idx + 2)
                } else if (idx + 1 >= sentences.size) {
                    wantsToPlay = false
                    abandonFocus()
                    main.post { onCompleted() }
                }
            }

            @Deprecated("Superseded by the errorCode overload; kept for older engines.")
            override fun onError(utteranceId: String?) {
                handleError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleError(utteranceId)
            }
        })
    }

    /**
     * An utterance failed. The overwhelmingly likely cause is the one this app introduced by
     * preferring a server-side voice: the connection went away mid-chapter. Losing signal is
     * not a reason to stop reading a book, so the first such failure drops to an offline
     * voice and carries on from the same sentence, and only a second failure — which means
     * the engine itself is broken, not the network — gives up and tells the UI.
     *
     * One failure arrives as two. There is always a sentence queued behind the one speaking,
     * so a connection that dies takes both down and the engine reports each: the first report
     * mounts the rescue, and the second, describing an utterance that has already been
     * flushed and replaced, would otherwise walk straight past the guard and stop the voice
     * the rescue had just restarted. Only the line actually being read is listened to.
     */
    private fun handleError(utteranceId: String?) {
        val idx = utteranceId?.toIntOrNull()
        if (idx != null && idx != currentIndex) return
        if (wantsToPlay && preferNetworkVoice && !fellBackToOffline) {
            fellBackToOffline = true
            if (selectVoice()) {
                speakFrom(currentIndex)
                return
            }
        }
        fail("Не удалось воспроизвести текст. Проверьте подключение и загруженные голоса устройства.")
    }

    /** Cuts a chapter into sentences. Resets the position — this is a new text. */
    suspend fun prepareText(htmlOrText: String) {
        // Scanned rather than parsed. The "chapter" handed to this reader is the whole book, and
        // building a document tree of a long one costs several times its size in memory for an
        // answer that is only ever the words in it.
        // Compute without mutating playback. Cancellation on a fast chapter change
        // must never install an old chapter after the new one has started.
        val (split, index) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val parts = SentenceSplitter.split(plainTextOf(htmlOrText))
            parts to SpeechTextIndex(parts)
        }
        sentences = split.mapIndexed { i, s -> TtsSentence(i, s) }
        speechIndex = index
        currentIndex = 0
        spokenChars = 0
        resumeIndex = -1
        resumeOffset = 0
        // A new chapter is a new chance for the network voice: the fallback was a reaction to
        // one dead connection, not a verdict on the device.
        if (fellBackToOffline) {
            fellBackToOffline = false
            if (isInitialized) selectVoice()
        }
    }

    private fun sentenceAt(index: Int): String = sentences.getOrNull(index)?.text.orEmpty()

    // ── Finding a place in the chapter by its words ──────────────────────────────

    /**
     * The chapter with everything but letters and digits taken out, and where each sentence
     * begins in it.
     *
     * The reader knows what is on the page in front of the eye, but it knows it as markup —
     * a paragraph's own text, with its own spacing and whatever punctuation the typesetter
     * used. The player knows the chapter as sentences. Neither can point at the other, so
     * both are reduced to bare letters and the question becomes an ordinary substring
     * search, which survives the differences that would otherwise have to be enumerated.
     */
    @Volatile
    private var speechIndex = SpeechTextIndex(emptyList())


    fun play(startIndex: Int = currentIndex) {
        initializationError?.let { fail(it); return }
        // A chapter with nothing readable in it — a full-page illustration, a title card.
        // There is no sound to make, so say so rather than leave the bar mid-press.
        if (sentences.isEmpty()) {
            fail("На этой странице нет текста для озвучки.")
            return
        }
        val target = startIndex.coerceIn(0, sentences.size - 1)
        // Resuming the sentence we were interrupted in keeps the word offset; jumping
        // anywhere else starts that line from its beginning.
        if (target != currentIndex) spokenChars = 0
        currentIndex = target
        wantsToPlay = true
        if (!requestFocus()) {
            // A call in progress, or another app holding exclusive audio. Nothing will be
            // heard, so the UI has to be told — otherwise its button sits on "pause" for a
            // voice that never started.
            fail("Аудиовыход занят. Завершите звонок или воспроизведение в другом приложении.")
            return
        }
        if (isInitialized) speakFrom(currentIndex)
    }

    fun playFromContext(after: String, before: String): Boolean {
        val place = speechIndex.locate(after, before) ?: return false
        currentIndex = place.sentence
        spokenChars = place.offset
        onSentenceStarted(place.sentence, sentenceAt(place.sentence).drop(place.offset))
        play(currentIndex)
        return true
    }

    /**
     * Stops the voice and remembers where it was. The engine has no pause, so this is a
     * stop that keeps its place — down to the word, since `onRangeStart` has been counting.
     */
    fun pause() {
        // A manual pause cancels any pending auto-resume: if the user reaches for the
        // button while a notification has the focus, they do not want the voice starting
        // itself again the moment the notification finishes.
        pausedByFocusLoss = false
        wantsToPlay = false
        tts?.stop()
        abandonFocus()
    }

    /** Pause that keeps the focus request alive, so AUDIOFOCUS_GAIN still arrives. */
    private fun pauseForFocus() {
        pausedByFocusLoss = true
        wantsToPlay = false
        tts?.stop()
    }

    fun stop() {
        pausedByFocusLoss = false
        stopSpeaking()
        currentIndex = 0
        spokenChars = 0
    }

    private fun stopSpeaking() {
        wantsToPlay = false
        tts?.stop()
        abandonFocus()
    }

    /**
     * Switches voice family. Like a speed change this has to re-speak, because the sentence
     * after the current one is already queued and would otherwise still come out in the old
     * voice — and a voice changing back and forth for one line is worse than either voice.
     */
    fun setPreferNetworkVoice(prefer: Boolean) {
        // Touching the switch deliberately also clears an earlier automatic fallback: the
        // user is saying the connection is worth trying again. That alone can change which
        // voice we should be using, so it counts as a change even when the switch lands on
        // the value it already had.
        val wasRescued = fellBackToOffline
        fellBackToOffline = false
        if (prefer == preferNetworkVoice && !wasRescued) return
        preferNetworkVoice = prefer
        if (!isInitialized) return
        selectVoice()
        if (wantsToPlay) speakFrom(currentIndex)
    }

    /**
     * Speed changes apply per utterance, and one utterance ahead is always already queued,
     * so a change made mid-chapter would otherwise not be heard until the sentence after
     * next. Re-speaking from the current word makes it immediate.
     */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
        if (wantsToPlay && isInitialized) speakFrom(currentIndex)
    }

    fun nextSentence() {
        if (currentIndex + 1 >= sentences.size) return
        jumpTo(currentIndex + 1)
    }

    fun previousSentence() {
        // Mid-sentence, "previous" means the start of this one — the same thing it means on
        // a music player, and the only reading under which the button is usable twice.
        jumpTo(if (spokenChars > 0) currentIndex else currentIndex - 1)
    }

    private fun jumpTo(index: Int) {
        if (sentences.isEmpty()) return
        currentIndex = index.coerceIn(0, sentences.size - 1)
        spokenChars = 0
        if (wantsToPlay) {
            speakFrom(currentIndex)
        } else {
            val idx = currentIndex
            val text = sentenceAt(idx)
            main.post { onSentenceStarted(idx, text) }
        }
    }

    fun release() {
        try {
            stopSpeaking()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        isInitialized = false
    }

    /**
     * Starts the chain at [index], flushing whatever was queued, and immediately queues the
     * line after it so the engine always has work in hand.
     */
    private fun speakFrom(index: Int) {
        val engine = tts ?: return
        val sentence = sentences.getOrNull(index) ?: return
        val offset = spokenChars.takeIf { it in 1 until sentence.text.length } ?: 0
        resumeIndex = if (offset > 0) index else -1
        resumeOffset = offset
        if (engine.speak(sentence.text.substring(offset), TextToSpeech.QUEUE_FLUSH, null, index.toString()) == TextToSpeech.ERROR) {
            fail("Голосовой движок не смог прочитать текст. Проверьте язык и загруженные голоса устройства.")
            return
        }
        if (index + 1 < sentences.size) enqueue(index + 1)
    }

    private fun enqueue(index: Int) {
        val engine = tts ?: return
        val sentence = sentences.getOrNull(index) ?: return
        engine.speak(sentence.text, TextToSpeech.QUEUE_ADD, null, index.toString())
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(true)
            .build()
        focusRequest = request
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasFocus = false
    }
}
