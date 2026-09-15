package ua.ukrainedrones.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import ua.ukrainedrones.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-priority, zero-disk-write audio and haptic dispatcher for emergency alerts.
 *
 * Safety Invariants:
 * 1. ZERO disk I/O on the critical audio triggering path.
 * 2. Pre-loaded in-memory audio buffers via SoundPool with USAGE_ALARM and FLAG_AUDIBILITY_ENFORCED.
 * 3. Bypasses device silence/vibrate settings when override option is armed.
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

    private val loadedSampleIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
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
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attributes)
                .build()
                .apply {
                    setOnLoadCompleteListener { _, sampleId, status ->
                        if (status == 0) loadedSampleIds.add(sampleId)
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
        if (overrideSilence) {
            enforceAlarmStreamVolume()
            val soundId = if (isRed) soundRedAlertId else soundYellowAlertId
            if (soundId != 0 && loadedSampleIds.contains(soundId)) {
                stopActiveAlert()
                val loopCount = if (loop) -1 else 0
                activeLoopStreamId = soundPool?.play(soundId, 1.0f, 1.0f, 10, loopCount, 1.0f) ?: 0
            }
        }

        triggerEmergencyHaptics()
    }

    /**
     * Plays the authoritative All-Clear chime.
     */
    fun dispatchAllClearChime() {
        stopActiveAlert()
        if (soundAllClearId != 0 && loadedSampleIds.contains(soundAllClearId)) {
            soundPool?.play(soundAllClearId, 0.9f, 0.9f, 5, 0, 1.0f)
        }
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
        if (overrideSilence) {
            enforceAlarmStreamVolume()
            stopActiveAlert()
            if (soundCriticalOfflineId != 0 && loadedSampleIds.contains(soundCriticalOfflineId)) {
                soundPool?.play(soundCriticalOfflineId, 1.0f, 1.0f, 8, 0, 1.0f)
            }
        }
        triggerEmergencyHaptics()
    }

    fun stopActiveAlert() {
        if (activeLoopStreamId != 0) {
            soundPool?.stop(activeLoopStreamId)
            activeLoopStreamId = 0
        }
        vibrator.cancel()
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

    private fun triggerEmergencyHaptics() {
        try {
            if (vibrator.hasVibrator()) {
                val timings = longArrayOf(0, 800, 300, 800, 300, 1200, 500)
                val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // repeat = -1: Discrete one-shot pattern (never loop endlessly)
                    val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                    vibrator.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timings, -1)
                }
            }
        } catch (_: Exception) {}
    }

    fun release() {
        stopActiveAlert()
        loadedSampleIds.clear()
        soundPool?.release()
        soundPool = null
    }
}
