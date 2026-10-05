package com.odesaplay.oko.service

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class AudioAlarmDispatcherTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    @Test
    fun `small vibration pulses the vibrator`() {
        val mgr = AudioAlarmDispatcher(context)

        mgr.dispatchSmallVibration()

        assertTrue(Shadows.shadowOf(vibrator()).isVibrating)
        mgr.release()
    }

    @Test
    fun `stopActiveAlert cancels an in-flight vibration`() {
        val mgr = AudioAlarmDispatcher(context)
        mgr.dispatchSmallVibration()

        mgr.stopActiveAlert()

        assertFalse(Shadows.shadowOf(vibrator()).isVibrating)
        mgr.release()
    }

    @Test
    fun `muted alarms never pulse the vibrator`() {
        val mgr = AudioAlarmDispatcher(context)

        mgr.dispatchDangerAlarm(isRed = true, overrideSilence = false)
        mgr.dispatchCriticalOffline(overrideSilence = false)

        assertFalse(Shadows.shadowOf(vibrator()).isVibrating)
        mgr.release()
    }

    @Test
    fun `release is idempotent`() {
        val mgr = AudioAlarmDispatcher(context)

        mgr.release()
        mgr.release()
    }
}
