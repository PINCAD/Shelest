package com.lexiread.app.domain.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import java.io.File
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.sin

data class PresetSound(
    val id: String,
    val name: String,
    val icon: String,
    val description: String
)

object AmbientPresets {
    val RAIN         = PresetSound("rain",         "Дождь",          "🌧️", "Уютный шум дождя")
    val FIREPLACE    = PresetSound("fireplace",    "Камин",          "🪵", "Потрескивание дров")
    val NIGHT_FOREST = PresetSound("night_forest", "Ночь в лесу",    "🦉", "Шелест деревьев и сверчки")
    val STREAM       = PresetSound("stream",       "Журчание ручья", "💧", "Плавный поток воды")

    val ALL = listOf(RAIN, FIREPLACE, NIGHT_FOREST, STREAM)
}

/**
 * @param onFocusLost invoked when audio focus is lost permanently (e.g. another app
 * takes over media playback for good) so the caller can reflect that in its UI state.
 */
class AmbientSoundPlayer(
    private val context: Context,
    private val onFocusLost: () -> Unit = {}
) {

    private var mediaPlayer: MediaPlayer? = null
    private var synthTrack: AudioTrack? = null
    private var synthThread: Thread? = null
    @Volatile private var isSynthRunning = false

    private var currentSoundId: String? = null
    private var currentCustomPath: String? = null
    private var currentVolume: Float = 0.5f

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var wantsToPlay = false
    private var pausedByFocusLoss = false
    private var duckedByFocus = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                wantsToPlay = false
                stopPlaybackOnly()
                onFocusLost()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                pausedByFocusLoss = true
                stopPlaybackOnly()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckedByFocus = true
                applyVolumeNow(currentVolume * 0.25f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                when {
                    duckedByFocus -> {
                        duckedByFocus = false
                        applyVolumeNow(currentVolume)
                    }
                    pausedByFocusLoss && wantsToPlay -> {
                        pausedByFocusLoss = false
                        val soundId = currentSoundId
                        if (!soundId.isNullOrBlank()) startPlayback(soundId, currentCustomPath)
                    }
                }
            }
        }
    }

    fun play(soundId: String?, customPath: String?, volume: Float) {
        currentSoundId = soundId
        currentCustomPath = customPath
        currentVolume = volume.coerceIn(0f, 1f)

        stopPlaybackOnly()

        if (soundId.isNullOrBlank()) {
            wantsToPlay = false
            abandonFocus()
            return
        }

        wantsToPlay = true
        pausedByFocusLoss = false
        duckedByFocus = false

        if (!requestFocus()) return
        startPlayback(soundId, customPath)
    }

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (!duckedByFocus) applyVolumeNow(currentVolume)
    }

    private fun applyVolumeNow(volume: Float) {
        mediaPlayer?.setVolume(volume, volume)
        synthTrack?.run {
            if (state == AudioTrack.STATE_INITIALIZED) {
                setVolume(volume)
            }
        }
    }

    fun pause() {
        wantsToPlay = false
        stopPlaybackOnly()
        abandonFocus()
    }

    fun stop() {
        wantsToPlay = false
        pausedByFocusLoss = false
        stopPlaybackOnly()
        abandonFocus()
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        val attrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
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

    private fun stopPlaybackOnly() {
        // Stop MediaPlayer
        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        mediaPlayer = null

        // Stop the synth. The order matters and used to be wrong: the track was released
        // while the generator thread could still be sitting inside a blocking write() on it,
        // which is a native crash rather than an exception. So the field is cleared first (a
        // fresh pass through the loop then finds nothing and leaves), the track is stopped to
        // unblock a write already in flight, and only after the thread has actually finished
        // is the track released.
        isSynthRunning = false
        val track  = synthTrack
        val worker = synthThread
        synthTrack  = null
        synthThread = null

        try {
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) track.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        worker?.interrupt()
        // Bounded: the wait is only as long as one buffer takes to drain. If the thread is
        // wedged for longer than that, leaking a track beats freezing the reader.
        try {
            worker?.join(300)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            track?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startPlayback(soundId: String, customPath: String?) {
        if (soundId == "custom") {
            if (!customPath.isNullOrBlank() && File(customPath).exists()) {
                playCustomFile(customPath)
                return
            }
        }

        // Try playing MP3 resource from res/raw/
        val playedRaw = playRawResourceByName(soundId)
        if (!playedRaw) {
            // Fallback to procedural synth if raw MP3 file is empty/not replaced yet
            playPresetSynth(soundId)
        }
    }

    private fun playRawResourceByName(resName: String): Boolean {
        return try {
            val resId = context.resources.getIdentifier(resName, "raw", context.packageName)
            if (resId == 0) return false

            val afd = context.resources.openRawResourceFd(resId) ?: return false
            if (afd.length <= 0) {
                afd.close()
                return false
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                setVolume(currentVolume, currentVolume)
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun playCustomFile(filePath: String) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(filePath)
                isLooping = true
                setVolume(currentVolume, currentVolume)
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playPresetSynth(type: String) {
        val sampleRate = 22050
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        synthTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().apply {
                setVolume(currentVolume)
                play()
            }

        isSynthRunning = true
        synthThread = thread(start = true, isDaemon = true) {
            val random = Random()
            val buffer = ShortArray(bufferSize / 2)

            var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
            var phase = 0.0
            var crackleDecay = 0.0

            while (isSynthRunning && !Thread.currentThread().isInterrupted) {
                for (i in buffer.indices) {
                    val white = random.nextDouble() * 2.0 - 1.0

                    val sample = when (type) {
                        "rain" -> {
                            b0 = 0.99886 * b0 + white * 0.0555179
                            b1 = 0.99332 * b1 + white * 0.0750759
                            b2 = 0.96900 * b2 + white * 0.1538520
                            val pink = b0 + b1 + b2 + white * 0.5362
                            val drop = if (random.nextDouble() < 0.0012) (random.nextDouble() * 0.4) else 0.0
                            (pink * 0.09 + drop)
                        }
                        "fireplace" -> {
                            b0 = (b0 + (0.012 * white)) / 1.012
                            val lowRoar = b0 * 0.4
                            if (random.nextDouble() < 0.002) crackleDecay = random.nextDouble() * 0.85
                            crackleDecay *= 0.91
                            (lowRoar + crackleDecay * (if (random.nextBoolean()) 0.6 else -0.6))
                        }
                        "night_forest" -> {
                            b0 = (b0 + (0.010 * white)) / 1.010
                            phase += 0.00005
                            val breeze = (sin(phase) + 1.0) * 0.5
                            val wind = b0 * (0.2 + 0.3 * breeze)
                            val cricketChirp = if (random.nextDouble() < 0.0004) (sin(i * 0.8) * 0.18) else 0.0
                            (wind * 0.25 + cricketChirp)
                        }
                        "stream" -> {
                            phase += 0.002
                            val ripple = sin(phase) * 0.2
                            b0 = (b0 + (0.04 * white)) / 1.04
                            b1 = (b1 + (0.01 * white)) / 1.01
                            ((b0 * 0.2 + b1 * 0.15) * (0.8 + ripple) * 0.35)
                        }
                        else -> {
                            b0 = (b0 + (0.02 * white)) / 1.02
                            b0 * 0.2
                        }
                    }

                    buffer[i] = (sample.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
                }

                val track = synthTrack ?: break
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.write(buffer, 0, buffer.size)
                }
            }
        }
    }
}
