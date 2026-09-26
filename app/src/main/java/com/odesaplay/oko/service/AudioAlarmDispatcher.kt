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
    private var soundAllClearId: Int = 0
    private var soundCriticalOfflineId: Int = 0

    private var activeLoopStreamId: Int = 0

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
            soundAllClearId = soundPool?.load(context, R.raw.all_clear, 1) ?: 0
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
    fun dispatchDangerAlarm(isRed: Boolean, overrideSilence: Boolean = true, loop: Boolean = false) {
        if (!overrideSilence) return
        enforceAlarmStreamVolume()
        playSound(
            if (isRed) soundRedAlertId else soundYellowAlertId,
            priority = 10,
            loopCount = if (loop) -1 else 0
        )
    }

    /**
     * Plays the authoritative All-Clear chime.
     */
    fun dispatchAllClearChime() {
        playSound(soundAllClearId, 0.9f, 0.9f, 5)
        vibrator.cancel()
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
        enforceAlarmStreamVolume()
        playSound(soundCriticalOfflineId, priority = 8)
    }

    private fun playSound(
        sampleId: Int,
        leftVol: Float = 1.0f,
        rightVol: Float = 1.0f,
        priority: Int = 10,
        loopCount: Int = 0,
        rate: Float = 1.0f
    ): Boolean {
        val sound = PendingAlarm.Sound(sampleId, leftVol, rightVol, priority, loopCount, rate)
        return when (pendingAlarm.onRequest(sound, sampleId != 0 && loadedSampleIds.contains(sampleId))) {
            PendingAlarm.Decision.DROP, PendingAlarm.Decision.QUEUE -> false
            PendingAlarm.Decision.PLAY -> playNow(sound)
        }
    }

    private fun playNow(sound: PendingAlarm.Sound): Boolean {
        stopActiveAlert()
        requestFocus()
        activeLoopStreamId = soundPool?.play(
            sound.sampleId, sound.leftVol, sound.rightVol, sound.priority, sound.loopCount, sound.rate
        ) ?: 0
        return true
    }

    fun stopActiveAlert() {
        if (activeLoopStreamId != 0) {
            soundPool?.stop(activeLoopStreamId)
            activeLoopStreamId = 0
        }
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

    private fun enforceAlarmStreamVolume() {
        try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            if (currentVol < (maxVol * 0.70).toInt()) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    (maxVol * 0.85).toInt(),
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                )
            }
        } catch (_: Exception) {}
    }

    fun release() {
        stopActiveAlert()
        pendingAlarm.clear()
        loadedSampleIds.clear()
        soundPool?.release()
        soundPool = null
    }
}
