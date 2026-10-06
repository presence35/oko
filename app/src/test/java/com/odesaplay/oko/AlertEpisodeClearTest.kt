package com.odesaplay.oko

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.odesaplay.oko.engine.AlertLevel
import com.odesaplay.oko.engine.OblastAlert
import com.odesaplay.oko.engine.ThreatEngine
import com.odesaplay.oko.engine.ZoneParams
import com.odesaplay.oko.engine.canonicalOblastId
import com.odesaplay.oko.service.MonitoringStatus
import com.odesaplay.oko.service.ServiceState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController

/**
 * One real tick at a time through [AlertService.handleState]. The episode/all-clear gates live in
 * private locals inside that function, so this is the only seam that reaches them: a fabricated
 * [AlertService.MonitorState] stands in for the combine's output, and `now` is supplied per tick so
 * the 20-minute TTL can be observed without waiting it out.
 *
 * The three-step rhythm every test uses: announce (a live red alert, two ticks so the latch is
 * confirmed live), then a quiet tick with `alertsReady = true` and no alerts, which is what ends
 * the episode.
 */
@RunWith(RobolectricTestRunner::class)
class AlertEpisodeClearTest {

    private lateinit var controller: ServiceController<AlertService>
    private val engine = ThreatEngine()

    private val alert = OblastAlert(
        key = "Луганська область",
        name = "Луганська область",
        oblast = "Луганська область",
        since = "t0",
        wide = true,
        level = "red"
    )
    private val token = alert.canonicalOblastId()!!

    private companion object {
        const val MIN = 60_000L
        const val CITY = "Сєвєроводськ"
        const val OTHER_CITY = "Дніпро"
    }

    @Before
    fun setUp() {
        clearPersistedServiceState()
        controller = Robolectric.buildService(AlertService::class.java).create()
        // The tick loop starts only after the persisted episode row has been restored; a tick
        // before that would race it (see AlertService.monitoringReady).
        runBlocking { service().awaitMonitoringReady() }
    }

    @After
    fun tearDown() {
        controller.destroy()
        MonitoringStatus.setRunning(false)
        DebugLog.clear()
    }

    private fun service() = controller.get()

    private fun notificationManager() = Shadows.shadowOf(
        service().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    )

    private fun allClear(): Notification? =
        notificationManager().getNotification(AlertNotificationManager.NOTIF_ALLCLEAR)

    private fun allClearTitle(): String? =
        allClear()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    /** (deadline, closed) — the two facts only the TTL and the debris ticks read. */
    private fun allClearLifecycle() = service().allClearLifecycleForTest()

    private fun tick(now: Long, state: AlertService.MonitorState) = runBlocking {
        service().handleState(state, now, engine)
    }

    private fun live() = monitor(level = AlertLevel.RED, alerts = listOf(alert))

    private fun announce(now: Long) {
        tick(now, live())
        // Second live tick: the persisted latch is now re-read, so it counts as "observed live".
        tick(now + 1, live())
    }

    private fun monitor(
        level: AlertLevel = AlertLevel.NONE,
        redEnabled: Boolean = true,
        yellowEnabled: Boolean = true,
        city: String = CITY,
        alerts: List<OblastAlert> = emptyList(),
        debrisSec: Int = 0,
        autoDismiss: Boolean = true,
        focusToken: String? = token
    ) = AlertService.MonitorState(
        focusOblastAlertActive = level == AlertLevel.RED,
        focusOblastYellowAlertActive = level == AlertLevel.YELLOW,
        focusOblastLevel = level,
        focusOblastRawLevel = level,
        focusOblastRawSince = "t0",
        focusToken = focusToken,
        focusOblastAlertSince = "t0",
        focusBannerCity = city,
        focusCityUa = city,
        focusRegion = "Луганська область",
        focusPinned = false,
        officialReason = null,
        officialReasonThreatId = null,
        officialOblastId = null,
        officialRegion = "Луганська область",
        zoneThreats = emptyMap(),
        params = ZoneParams(20, 50, 5, 20),
        lang = AppLanguage.EN,
        slowRedArmed = false,
        slowYellowArmed = false,
        fastRedArmed = false,
        fastYellowArmed = false,
        officialRedAlertsEnabled = redEnabled,
        officialYellowAlertsEnabled = yellowEnabled,
        zoneSirenOverride = false,
        officialSirenOverride = false,
        fallingDebrisDelaySec = debrisSec,
        autoDismissAllClear = autoDismiss,
        threats = emptyMap(),
        alerts = alerts,
        alertsReady = true,
        criticalOfflineOverride = false,
        criticalOfflineBypassSilent = false,
        fastVibrationLevel = 3,
        slowVibrationLevel = 2,
        focusLocation = null,
        nightActive = false,
        enabled = ThreatType.values().toSet(),
        hiddenTypes = emptySet(),
        notifyPolicyEnabled = false,
        zonePolicy = ZonePolicy.EVERY_CHANGE,
        digestMax = 0,
        digestWindow = DigestWindow.EPISODE,
        digestPerType = false
    )

    /** Rebuild the service on top of the pair a process restart restores: the
     *  `LEVEL|token|since|city[|cityUa|scoped]` row and the legacy token/city keys it gates on.
     *  [handleState] then has to survive a first tick whose focus has not resolved yet. */
    private fun restartWithLatchedEpisode(row: String) {
        controller.destroy()
        runBlocking {
            val state = ServiceState(RuntimeEnvironment.getApplication())
            val parts = row.split('|')
            state.setOfficialAnnounced(parts[1], parts[2], null, parts[3])
            state.setOfficialAnnouncedRow(row)
        }
        controller = Robolectric.buildService(AlertService::class.java).create()
        runBlocking { service().awaitMonitoringReady() }
    }

    @Test
    fun `all-clear names the episode's city, not where the focus moved to`() {
        announce(1_000)
        // The feed ends at 3_000, by which point follow-me has moved the focus to another city.
        tick(3_000, monitor(city = OTHER_CITY))

        val title = allClearTitle()
        assertNotNull("the episode ended, so an all-clear must be in the shade", title)
        assertTrue("titled for the announced episode, got: $title", title!!.contains(CITY))
        assertFalse("must not name the new focus, got: $title", title.contains(OTHER_CITY))
    }

    @Test
    fun `all-clear retires itself 20 minutes after it is posted`() {
        announce(1_000)
        tick(3_000, monitor())
        assertNotNull(allClear())
        assertNotNull("the TTL must be armed by the post itself", allClearLifecycle().first)

        tick(3_000 + 19 * MIN, monitor())
        assertNotNull("19 minutes is inside the TTL", allClear())

        tick(3_000 + 21 * MIN, monitor())
        assertNull("21 minutes is past it", allClear())
    }

    @Test
    fun `auto-dismiss off never arms the TTL`() {
        announce(1_000)
        tick(3_000, monitor(autoDismiss = false))
        assertNotNull(allClear())
        assertNull(allClearLifecycle().first)

        tick(3_000 + 21 * MIN, monitor())
        assertNotNull("nothing may retire it with the toggle off", allClear())
    }

    @Test
    fun `a level switched off mid-episode is never cleared, and leaves no latch`() {
        announce(1_000)
        // Ended while red is switched off: documented policy says no all-clear for a level that
        // was never announced through this gate.
        tick(3_000, monitor(redEnabled = false))
        assertNull(allClear())

        // Level back on, still quiet: a latch left armed would fire its clear long after the
        // episode ended. That stale clear is the bug this guards.
        tick(4_000, monitor(redEnabled = true))
        assertNull("a stale latch must not fire an all-clear later", allClear())
        assertNull(allClearLifecycle().first)
    }

    @Test
    fun `a restored episode survives a cold start that has no focus yet`() {
        // The service restores its announced row before the first GPS fix lands, so the focus token
        // is still null on the first tick. The effective level then comes from the latch while the
        // live frontier is null — the state that used to crash the whole process on launch.
        restartWithLatchedEpisode("YELLOW|$token|t0|$CITY||||0")
        tick(1_000, monitor(level = AlertLevel.NONE, focusToken = null, alerts = listOf(alert)))

        assertEquals(
            "the restored episode must still be announced, named for its own city",
            "yellow|$token|$CITY",
            service().lastShownIdForTest()
        )
    }

    @Test
    fun `the next episode's all-clear still ticks after the previous one was superseded`() {
        announce(1_000)
        tick(3_000, monitor())
        assertNotNull(allClear())
        assertFalse("a fresh post is open", allClearLifecycle().second)

        // A new red episode in the same city supersedes the live all-clear (notification gone).
        tick(4_000, live())
        assertNull(allClear())
        assertTrue("superseded all-clears are closed", allClearLifecycle().second)

        tick(5_000, live())
        tick(6_000, monitor())
        assertNotNull(allClear())
        assertFalse(
            "the second episode's all-clear must reopen the debris countdown",
            allClearLifecycle().second
        )
    }
}
