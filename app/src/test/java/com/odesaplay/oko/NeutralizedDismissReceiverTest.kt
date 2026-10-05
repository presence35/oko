package com.odesaplay.oko

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class NeutralizedDismissReceiverTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun startedAction(intent: Intent): String? {
        NeutralizedDismissReceiver().onReceive(context, intent)
        val started = Shadows.shadowOf(context).nextStartedService
        assertEquals(AlertService::class.java.name, started?.component?.className)
        return started?.action
    }

    @Test
    fun `null action defaults to the neutralized dismiss`() {
        assertEquals(NeutralizedTally.ACTION_NEUTRALIZED_DISMISS, startedAction(Intent()))
    }

    @Test
    fun `unknown action defaults to the neutralized dismiss`() {
        assertEquals(NeutralizedTally.ACTION_NEUTRALIZED_DISMISS, startedAction(Intent("some.other.action")))
    }

    @Test
    fun `alarm episode dismiss action is preserved`() {
        assertEquals(
            AlarmEpisodeTally.ACTION_ALARM_EPISODE_DISMISS,
            startedAction(Intent(AlarmEpisodeTally.ACTION_ALARM_EPISODE_DISMISS))
        )
    }
}
