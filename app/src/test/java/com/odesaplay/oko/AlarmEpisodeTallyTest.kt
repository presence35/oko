package com.odesaplay.oko

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import kotlin.coroutines.CoroutineContext

/**
 * The two-finish race, forced instead of waited for. The real pool normally runs the posts in
 * submission order and only loses that ordering under load, so in a full suite this shows up as an
 * intermittent that moves between tests and never reproduces in isolation — which is no way to pin
 * a rule. [LifoDispatcher] runs the newest submission first and the oldest last, the worst order
 * the real one can produce, so the rule the shade depends on is asserted on every run.
 *
 * The title is the same for the summary and the quiet card, so these assert which row reached the
 * shade, not how it was presented.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmEpisodeTallyTest {

    private class LifoDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<java.lang.Runnable>()

        override fun dispatch(context: CoroutineContext, block: java.lang.Runnable) {
            synchronized(tasks) { tasks.addFirst(block) }
        }

        fun drainInverted() {
            while (true) {
                // All deque access under the lock: the store and gate collectors the tally starts can
                // dispatch onto this dispatcher from their own threads while we are draining it.
                val next = synchronized(tasks) { tasks.removeFirstOrNull() } ?: return
                next.run()
            }
        }
    }

    private val dispatcher = LifoDispatcher()
    private lateinit var context: Application
    private lateinit var tally: AlarmEpisodeTally

    @Before
    fun setUp() {
        isolateStores()
        context = RuntimeEnvironment.getApplication()
        // Explicit, because a gate that is off would make both tests below pass by doing nothing.
        assertTrue("the master morale switch is on out of the box", moraleGate.isOn)
        tally = AlarmEpisodeTally(context, CoroutineScope(dispatcher))
    }

    @Test
    fun `the newest row wins when two alarms finish in flight`() {
        tally.begin(CITY, startMs = 1_000, fromOnset = true, live = true)
        tally.finish(CITY, AppLanguage.EN, officialAlertsEnabled = true, nowMs = 1_000 + 5 * MIN)

        tally.begin(CITY, startMs = 1_000 + 10 * MIN, fromOnset = true, live = true)
        tally.finish(CITY, AppLanguage.EN, officialAlertsEnabled = true, nowMs = 1_000 + 32 * MIN)

        dispatcher.drainInverted()

        assertEquals("$CITY: 27min · 2 alarms", shadeTitle())
    }

    @Test
    fun `a dismissed row is not resurrected by a post already in flight`() {
        tally.begin(CITY, startMs = 1_000, fromOnset = true, live = true)
        tally.finish(CITY, AppLanguage.EN, officialAlertsEnabled = true, nowMs = 1_000 + 5 * MIN)

        tally.reset()
        dispatcher.drainInverted()

        assertNull("the row was dismissed, so nothing may put it back", shadeTitle())
    }

    private fun shadeTitle(): String? = Shadows.shadowOf(
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    ).getNotification(AlertNotificationManager.NOTIF_ALARM_EPISODE)
        ?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private companion object {
        const val MIN = 60_000L
        const val CITY = "Testville"
    }
}
