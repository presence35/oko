package com.odesaplay.oko.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.odesaplay.oko.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-priority, zero-disk-write audio dispatcher for emergency alerts.
 *
 * Safety Invariants:
 * 1. ZERO disk I/O on the critical audio triggering path.
 * 2. Pre-loaded in-memory audio buffers via SoundPool with USAGE_ALARM and FLAG_AUDIBILITY_ENFORCED.
 * 3. A siren requested before its buffer finished loading is queued, never dropped.
 * 4. Audio focus is requested but never abandoned on transient loss — the siren is short and
 *    safety-first, so an incoming call must not silence it (see [PendingAlarm] / focus listener).
 */
class AudioAlarmDispatcher(
    private val context: Context
) {
    companion object {
        private const val TAG = "AudioAlarmDispatcher"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
        .build()

    private val audioFocusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(alarmAttributes)
        .setWillPauseWhenDucked(false)
        // Safety-first: ignore transient/duck loss so a phone call never silences the siren.
        .setOnAudioFocusChangeListener { }
        .build()
    private val hasFocus = AtomicBoolean(false)

    private val loadedSampleIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val pendingAlarm = PendingAlarm()
    private var soundPool: SoundPool? = null

    private var soundRedAlertId: Int = 0
    private var soundYellowAlertId: Int = 0
    private var soundCriticalOfflineId: Int = 0

    /**
     * The one stream this dispatcher owns, if any. Every dispatched sound is a one-shot that
     * runs to its end: nothing about the alert's own lifecycle may truncate it.
     */
    private var activeStreamId: Int? = null

    init {
        initSoundPool()
    }

    private fun initSoundPool() {
        try {
            soundPool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(alarmAttributes)
                .build()
                .apply {
                    setOnLoadCompleteListener { _, sampleId, status ->
                        val ok = status == 0
                        if (ok) loadedSampleIds.add(sampleId)
                        // Play a siren that was queued while this buffer was still loading.
                        pendingAlarm.onLoaded(sampleId, ok)?.let { playNow(it) }
                    }
                }

            soundRedAlertId = soundPool?.load(context, R.raw.air_raid_siren, 1) ?: 0
            soundYellowAlertId = soundPool?.load(context, R.raw.zone_outer, 1) ?: 0
            soundCriticalOfflineId = soundPool?.load(context, R.raw.critical_offline, 1) ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SoundPool", e)
        }
    }

    /**
     * Plays immediate emergency danger siren in memory with zero disk I/O.
     * When overrideSilence is false, SoundPool audio is completely suppressed,
     * delegating notification sounds/vibration to standard Android notification channels.
     */
    fun dispatchDangerAlarm(isRed: Boolean, overrideSilence: Boolean = true) {
        if (!overrideSilence) return
        playSound(if (isRed) soundRedAlertId else soundYellowAlertId, priority = 10)
    }

    /**
     * Subtle vibration when a background countdown completes (e.g. falling debris advisory).
     */
    fun dispatchSmallVibration() {
        try {
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(150L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(150L)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Plays critical offline warning chime.
     */
    fun dispatchCriticalOffline(overrideSilence: Boolean = true) {
        if (!overrideSilence) return
        playSound(soundCriticalOfflineId, priority = 8)
    }

    private fun playSound(
        sampleId: Int,
        leftVol: Float = 1.0f,
        rightVol: Float = 1.0f,
        priority: Int = 10,
        rate: Float = 1.0f
    ): Boolean {
        val sound = PendingAlarm.Sound(sampleId, leftVol, rightVol, priority, 0, rate)
        return when (pendingAlarm.onRequest(sound, sampleId != 0 && loadedSampleIds.contains(sampleId))) {
            PendingAlarm.Decision.DROP, PendingAlarm.Decision.QUEUE -> false
            PendingAlarm.Decision.PLAY -> playNow(sound)
        }
    }

    /**
     * An alert preempts whatever is already playing — the only stop a play ever performs.
     */
    private fun playNow(sound: PendingAlarm.Sound): Boolean {
        if (activeStreamId != null) {
            stopActiveAlert()
        }
        requestFocus()
        val streamId = soundPool?.play(
            sound.sampleId, sound.leftVol, sound.rightVol, sound.priority, sound.loopCount, sound.rate
        ) ?: 0
        if (streamId == 0) return false
        activeStreamId = streamId
        return true
    }

    /**
     * Stops everything: explicit user mute, service teardown — and nothing else. Lifecycle
     * housekeeping (the alert notification being torn down, the all-clear arriving) never clips
     * a dispatched one-shot; only a new alert preempts it, or the user.
     */
    fun stopActiveAlert() {
        activeStreamId?.let { soundPool?.stop(it) }
        activeStreamId = null
        vibrator.cancel()
        abandonFocus()
    }

    private fun requestFocus() {
        if (hasFocus.compareAndSet(false, true)) {
            try {
                audioManager.requestAudioFocus(audioFocusRequest)
            } catch (_: Exception) {
                hasFocus.set(false)
            }
        }
    }

    private fun abandonFocus() {
        if (hasFocus.compareAndSet(true, false)) {
            try {
                audioManager.abandonAudioFocusRequest(audioFocusRequest)
            } catch (_: Exception) {}
        }
    }

    fun release() {
        stopActiveAlert()
        pendingAlarm.clear()
        loadedSampleIds.clear()
        soundPool?.release()
        soundPool = null
    }
}
