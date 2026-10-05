package com.odesaplay.oko.engine

import android.content.Context
import com.odesaplay.oko.connection.Monotonic
import com.odesaplay.oko.threat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins the two safety invariants documented on [MonitorCoreImpl]:
 *  1. Disconnected != All Clear — a network drop flags information stale and never wipes state.
 *  2. Resurrection persistence — active threat/alert state is written to SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
class MonitorCoreImplTest {

    private lateinit var context: Context
    private lateinit var core: MonitorCoreImpl
    private lateinit var originalNow: () -> Long
    private var mono = 1_000_000L

    private fun prefs() =
        context.getSharedPreferences(MonitorCoreImpl.PREFS_NAME, Context.MODE_PRIVATE)

    private fun hadActiveAlert() = prefs().getBoolean(MonitorCoreImpl.KEY_HAD_ACTIVE_ALERT, false)

    private fun redAlert() = OblastAlert(
        key = "odeska", name = "Одеська область", oblast = "Одеська область",
        since = null, wide = true, level = "red"
    )

    @Before
    fun setUp() {
        originalNow = Monotonic.nowProvider
        Monotonic.nowProvider = { mono }
        context = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
        core = MonitorCoreImpl(context, CoroutineScope(Dispatchers.Unconfined))
    }

    @After
    fun tearDown() {
        Monotonic.nowProvider = originalNow
    }

    // ── Invariant 1: Disconnected != All Clear ──

    @Test
    fun `network drop flags stale but keeps threats and alerts`() {
        core.updateThreats(listOf(threat(id = "t1")))
        core.updateAlerts(listOf(redAlert()))

        core.onNetworkDisconnected("socket closed")

        assertEquals(1, core.threats.value.size)
        assertEquals(1, core.alerts.value.size)
        assertTrue(core.isInformationStale.value)
    }

    @Test
    fun `reconnect stays stale until the first authoritative frame`() {
        core.onNetworkDisconnected("x")
        core.onNetworkReconnected()
        assertTrue(core.isInformationStale.value)

        core.onStreamFrameReceived()
        assertFalse(core.isInformationStale.value)
    }

    @Test
    fun `baseline sync required flags stale`() {
        core.onBaselineSyncRequired()
        assertTrue(core.isInformationStale.value)
    }

    // ── Threat state ──

    @Test
    fun `updateThreats replaces the whole set and clears stale`() {
        core.onNetworkDisconnected("x")

        core.updateThreats(listOf(threat(id = "t1"), threat(id = "t2")))

        assertEquals(2, core.threats.value.size)
        assertFalse(core.isInformationStale.value)
        assertTrue(core.lastUpdateEpochMs.value > 0)
    }

    @Test
    fun `upsertThreat adds new and replaces existing by id`() {
        core.upsertThreat(threat(id = "t1", lat = 1.0))
        core.upsertThreat(threat(id = "t2", lat = 5.0))
        core.upsertThreat(threat(id = "t1", lat = 2.0))

        assertEquals(2, core.threats.value.size)
        assertEquals(2.0, core.threats.value.first { it.id == "t1" }.lat, 0.0)
    }

    @Test
    fun `removeThreat removes only the matching id`() {
        core.updateThreats(listOf(threat(id = "t1"), threat(id = "t2")))

        core.removeThreat("t1")

        assertEquals(1, core.threats.value.size)
        assertEquals("t2", core.threats.value.single().id)
    }

    @Test
    fun `removeThreat on an unknown id is a no-op`() {
        core.updateThreats(listOf(threat(id = "t1")))

        core.removeThreat("missing")

        assertEquals(1, core.threats.value.size)
    }

    // ── Alerts ──

    @Test
    fun `updateAlerts stores alerts and stamps the update`() {
        core.updateAlerts(listOf(redAlert()))

        assertEquals(1, core.alerts.value.size)
        assertTrue(core.lastUpdateEpochMs.value > 0)
    }

    // ── User-shot grace ──

    @Test
    fun `user shot is recent then expires after the grace window`() {
        core.markUserShot("t1")
        assertTrue(core.wasUserShotRecently("t1"))

        mono += MonitorCoreImpl.USER_SHOT_GRACE_MS + 1
        assertFalse(core.wasUserShotRecently("t1"))
    }

    @Test
    fun `wasUserShotRecently is false for an unknown id`() {
        assertFalse(core.wasUserShotRecently("nope"))
    }

    @Test
    fun `blank shot id is ignored`() {
        core.markUserShot("")
        assertFalse(core.wasUserShotRecently(""))
    }

    @Test
    fun `updateThreats prunes expired user shots`() {
        core.markUserShot("t1")
        mono += MonitorCoreImpl.USER_SHOT_GRACE_MS + 1

        core.updateThreats(emptyList())

        assertFalse(core.wasUserShotRecently("t1"))
    }

    // ── Invariant 2: Resurrection persistence ──

    @Test
    fun `active threat sets had_active_alert`() {
        core.updateThreats(listOf(threat(id = "t1")))
        assertTrue(hadActiveAlert())
    }

    @Test
    fun `red alert sets had_active_alert`() {
        core.updateAlerts(listOf(redAlert()))
        assertTrue(hadActiveAlert())
    }

    @Test
    fun `clearing threats with no alerts clears had_active_alert`() {
        core.updateThreats(listOf(threat(id = "t1")))
        assertTrue(hadActiveAlert())

        core.updateThreats(emptyList())

        assertFalse(hadActiveAlert())
    }

    @Test
    fun `yellow non-wide alert does not set had_active_alert`() {
        core.updateAlerts(
            listOf(
                OblastAlert(
                    key = "kyivskyi", name = "Київський район", oblast = "Київська область",
                    since = null, wide = false, level = "yellow"
                )
            )
        )
        assertFalse(hadActiveAlert())
    }

    @Test
    fun `wide alert counts even when yellow`() {
        core.updateAlerts(
            listOf(
                OblastAlert(
                    key = "odeska", name = "Одеська область", oblast = "Одеська область",
                    since = null, wide = true, level = "yellow"
                )
            )
        )
        assertTrue(hadActiveAlert())
    }
}
