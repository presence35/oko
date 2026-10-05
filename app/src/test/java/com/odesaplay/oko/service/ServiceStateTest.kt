package com.odesaplay.oko.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Round-trip coverage for the DataStore-backed service store. Keys asserted as defaults are
 * deliberately never written by any test here, so the shared DataStore instance can't leak a
 * value into the defaults check.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceStateTest {

    private lateinit var state: ServiceState

    @Before
    fun setUp() {
        state = ServiceState(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `unwritten keys fall back to their defaults`() = runTest {
        state.clearAll()

        assertEquals("", state.officialAnnouncedRow().first())
        assertEquals(0L, state.ignoreRetryUntil().first())
        assertNull(state.criticalChannelBypassApplied().first())
        assertEquals(0, state.channelSchemaVersion().first())
    }

    @Test
    fun `serialized string values round trip`() = runTest {
        state.setConnLog("conn")
        state.setGpsLog("gps")
        state.setDebugLog("debug")
        state.setSystemLog("system")
        state.setActiveZoneAlerts("zones")

        assertEquals("conn", state.connLog().first())
        assertEquals("gps", state.gpsLog().first())
        assertEquals("debug", state.debugLog().first())
        assertEquals("system", state.systemLog().first())
        assertEquals("zones", state.activeZoneAlerts().first())
    }

    @Test
    fun `long values round trip`() = runTest {
        state.setOfflinePendingSince(111L)
        state.setReconnectStartMillis(222L)
        state.setLastUpdateCheck(333L)
        state.setLastNotifiedUpdateCode(444L)

        assertEquals(111L, state.offlinePendingSince().first())
        assertEquals(222L, state.reconnectStartMillis().first())
        assertEquals(333L, state.lastUpdateCheck().first())
        assertEquals(444L, state.lastNotifiedUpdateCode().first())
    }

    @Test
    fun `legacy official announcement fields round trip`() = runTest {
        state.setOfficialAnnounced(token = "odeska", since = "s", reasonId = "r", city = "Одеса")

        assertEquals("odeska", state.officialAnnouncedToken().first())
        assertEquals("s", state.officialAnnouncedSince().first())
        assertEquals("r", state.officialAnnouncedReasonId().first())
        assertEquals("Одеса", state.officialAnnouncedCity().first())
    }

    @Test
    fun `raid mute deadline round trips and clears`() = runTest {
        state.setRaidMuteDeadline("until-5")
        assertEquals("until-5", state.raidMuteDeadline().first())

        state.setRaidMuteDeadline(null)
        assertEquals("", state.raidMuteDeadline().first())
    }

    @Test
    fun `manifest hash round trips`() = runTest {
        state.setLastSdkManifestHash("abc")
        assertEquals("abc", state.lastSdkManifestHash().first())
    }
}
