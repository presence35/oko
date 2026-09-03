package ua.ukrainedrones

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Manages the looping jet-engine sound for the MiG-31K flyby.
 * Uses [MediaPlayer] with USAGE_MEDIA so it respects device volume and DND.
 */
class FlybyAudioPlayer private constructor(
    private val context: Context,
    private val mediaPlayer: MediaPlayer
) {

    /** Starts (or restarts) the looping engine sound. */
    fun start() {
        mediaPlayer.seekTo(0)
        mediaPlayer.start()
    }

    /** Stops the sound; safe to call multiple times. */
    fun stop() {
        try { if (mediaPlayer.isPlaying) mediaPlayer.stop() } catch (_: Exception) {}
        try { mediaPlayer.release() } catch (_: Exception) {}
    }

    /** Factory: prepares a looping [MediaPlayer] for the engine sound. */
    companion object {
        private val RES_ID = R.raw.mig_engine

        fun create(context: Context): FlybyAudioPlayer {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val mp = MediaPlayer.create(context, RES_ID).apply {
                setAudioAttributes(attrs)
                isLooping = false
                setVolume(1.0f, 1.0f)
            }
            return FlybyAudioPlayer(context, mp)
        }
    }

    fun release() {
        try { mediaPlayer.release() } catch (_: Exception) {}
    }
}